package com.steam.carga;

import com.steam.common.Constantes;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;

/**
 * GeneradorCarga – Prueba de carga para el Sistema Steam Distribuido.
 *
 * Uso: java ... GeneradorCarga [hilos=50] [duracionSeg=60]
 *
 * Cada hilo simula un ciclo completo de usuario:
 *   LOGIN → LISTAR_JUEGOS → COMPRAR_JUEGO → CONFIRMAR_PAGO →
 *   ENVIAR_MENSAJE → LOGOUT
 *
 * Métricas (thread-safe con AtomicLong):
 *  totalPeticiones, peticionesExitosas, peticionesError,
 *  sumaLatenciasMs, todasLatencias (para p95).
 *
 * Reporte parcial cada 10 s; reporte final + guardado en logs/.
 */
public class GeneradorCarga {

    // ── Métricas ──────────────────────────────────────────────────────────────
    private static final AtomicLong totalPeticiones    = new AtomicLong(0);
    private static final AtomicLong peticionesExitosas = new AtomicLong(0);
    private static final AtomicLong peticionesError    = new AtomicLong(0);
    private static final AtomicLong sumaLatenciasMs    = new AtomicLong(0);
    private static final CopyOnWriteArrayList<Long> todasLatencias = new CopyOnWriteArrayList<>();

    private static final RelojLamport reloj = new RelojLamport();
    private static final Logger       LOG   = Logger.getLogger(GeneradorCarga.class.getName());

    private static final String[] USUARIOS = { "cliente1", "cliente2" };
    private static final String   PASS     = "pass123";

    // ── main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        int hilos       = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int duracionSeg = args.length > 1 ? Integer.parseInt(args[1]) : 60;

        // Configurar log a archivo
        String timestamp = String.valueOf(System.currentTimeMillis());
        String logFile   = "logs/carga_" + timestamp + ".log";
        configurarLog(logFile);

        System.out.println("[CARGA] Iniciando: " + hilos + " hilos, " + duracionSeg + "s");
        System.out.println("[CARGA] Log: " + logFile);

        long inicio = System.currentTimeMillis();
        long finMs  = inicio + duracionSeg * 1_000L;

        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> cicloWorker(finMs));
        }

        // Hilo de reporte parcial cada 10 s
        Thread reporter = new Thread(() -> {
            long lastTime  = inicio;
            long lastTotal = 0;
            while (System.currentTimeMillis() < finMs) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { break; }
                long now    = System.currentTimeMillis();
                long elap   = (now - inicio)    / 1_000;
                long inter  = (now - lastTime)  / 1_000;
                long total  = totalPeticiones.get();
                long tp     = inter > 0 ? (total - lastTotal) / inter : 0;
                double avg  = total > 0 ? (double) sumaLatenciasMs.get() / total : 0;
                String msg  = String.format("[CARGA] t=%ds | throughput=%d req/s | latAvg=%.1fms | errores=%d",
                        elap, tp, avg, peticionesError.get());
                System.out.println(msg);
                LOG.info(msg);
                lastTime  = now;
                lastTotal = total;
            }
        }, "reporter");
        reporter.setDaemon(true);
        reporter.start();

        pool.shutdown();
        pool.awaitTermination(duracionSeg + 15, TimeUnit.SECONDS);
        pool.shutdownNow();

        imprimirReporteFinal(duracionSeg, logFile);
    }

    // ── Worker ────────────────────────────────────────────────────────────────

    private static void cicloWorker(long finMs) {
        Random rand = new Random();
        while (System.currentTimeMillis() < finMs
                && !Thread.currentThread().isInterrupted()) {
            try {
                String user = USUARIOS[rand.nextInt(USUARIOS.length)];

                // a) LOGIN
                String token = op(() -> {
                    MensajeProtocolo req = MensajeProtocolo.request(Constantes.LOGIN, null);
                    req.put("username", user);
                    req.put("password", PASS);
                    MensajeProtocolo resp = enviar(req);
                    return (resp != null && resp.isOk()) ? resp.getString("token") : null;
                });
                if (token == null) { peticionesError.incrementAndGet(); continue; }

                // b) LISTAR_JUEGOS
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> juegos = op(() -> {
                    MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, token));
                    if (resp == null || !resp.isOk()) return null;
                    return (List<Map<String, Object>>) resp.get("juegos");
                });

                // c) COMPRAR primer juego disponible
                String reservaId = null;
                if (juegos != null && !juegos.isEmpty()) {
                    String juegoId = juegos.get(0).get("id").toString();
                    reservaId = op(() -> {
                        MensajeProtocolo req = MensajeProtocolo.request(Constantes.COMPRAR_JUEGO, token);
                        req.put("juegoId", juegoId);
                        MensajeProtocolo resp = enviar(req);
                        return (resp != null && resp.isOk()) ? resp.getString("reservaId") : null;
                    });
                }

                // d) CONFIRMAR_PAGO
                if (reservaId != null) {
                    String rid = reservaId;
                    op(() -> {
                        MensajeProtocolo req = MensajeProtocolo.request(Constantes.CONFIRMAR_PAGO, token);
                        req.put("reservaId", rid);
                        MensajeProtocolo resp = enviar(req);
                        return (resp != null && resp.isOk()) ? "ok" : null;
                    });
                }

                // e) ENVIAR_MENSAJE al otro usuario
                String otro = USUARIOS[(Arrays.asList(USUARIOS).indexOf(user) + 1) % USUARIOS.length];
                op(() -> {
                    MensajeProtocolo req = MensajeProtocolo.request(Constantes.ENVIAR_MENSAJE, token);
                    req.put("receptor",  otro);
                    req.put("contenido", "Carga t=" + System.currentTimeMillis());
                    MensajeProtocolo resp = enviar(req);
                    return (resp != null && resp.isOk()) ? "ok" : null;
                });

                // f) LOGOUT
                op(() -> {
                    MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.LOGOUT, token));
                    return (resp != null && resp.isOk()) ? "ok" : null;
                });

            } catch (Exception e) {
                peticionesError.incrementAndGet();
            }
        }
    }

    // ── Helper de operación con métricas ──────────────────────────────────────

    @FunctionalInterface
    interface Operacion<T> { T ejecutar() throws Exception; }

    private static <T> T op(Operacion<T> o) {
        long t0 = System.currentTimeMillis();
        try {
            T result = o.ejecutar();
            long lat = System.currentTimeMillis() - t0;
            totalPeticiones.incrementAndGet();
            sumaLatenciasMs.addAndGet(lat);
            todasLatencias.add(lat);
            if (result != null) peticionesExitosas.incrementAndGet();
            else                peticionesError.incrementAndGet();
            return result;
        } catch (Exception e) {
            peticionesError.incrementAndGet();
            totalPeticiones.incrementAndGet();
            return null;
        }
    }

    // ── Transporte ────────────────────────────────────────────────────────────

    private static MensajeProtocolo enviar(MensajeProtocolo req) {
        req.setLamportClock(reloj.tick());
        try (Socket socket = new Socket(Constantes.HOST, Constantes.PUERTO_PROXY)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String resp = in.readLine();
            if (resp != null) {
                MensajeProtocolo m = MensajeProtocolo.fromJson(resp);
                if (m != null) reloj.update(m.getLamportClock());
                return m;
            }
        } catch (IOException ignored) {}
        return null;
    }

    // ── Reporte ───────────────────────────────────────────────────────────────

    private static void imprimirReporteFinal(int duracionSeg, String logFile) {
        long total    = totalPeticiones.get();
        long exitosas = peticionesExitosas.get();
        long errores  = peticionesError.get();
        double tp     = total > 0 ? (double) total / duracionSeg : 0;
        double avg    = total > 0 ? (double) sumaLatenciasMs.get() / total : 0;
        double pct    = total > 0 ? errores * 100.0 / total : 0;

        // Calcular p95
        List<Long> sorted = new ArrayList<>(todasLatencias);
        Collections.sort(sorted);
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95));

        String reporte = String.format("""
                ══════════════════════════════════════
                REPORTE FINAL DE CARGA
                ══════════════════════════════════════
                Duración          : %ds
                Total peticiones  : %d
                Throughput        : %.1f req/s
                Latencia promedio : %.1f ms
                Latencia p95      : %d ms
                Peticiones error  : %d (%.1f%%)
                Log guardado en   : %s
                ══════════════════════════════════════""",
                duracionSeg, total, tp, avg, p95, errores, pct, logFile);

        System.out.println(reporte);
        LOG.info(reporte);
    }

    // ── Configuración de logging ──────────────────────────────────────────────

    private static void configurarLog(String logFile) {
        try {
            Files.createDirectories(Path.of("logs"));
            FileHandler fh = new FileHandler(logFile, true);
            fh.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("[CARGA] No se pudo crear log: " + e.getMessage());
        }
    }
}
