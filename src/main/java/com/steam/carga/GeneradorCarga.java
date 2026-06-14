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
    // Distinguimos tres resultados por petición de red:
    //   - respuestasOk      : el servidor respondió OK (transacción aceptada)
    //   - respuestasRechazo : el servidor respondió ERROR de NEGOCIO (p.ej. "ya posees
    //                         el juego"). El sistema está vivo: NO es una pérdida.
    //   - perdidas          : timeout / sin respuesta / conexión rechazada. ESTA es la
    //                         "tasa de pérdida" que se dispara al caer un nodo (rúbrica 3.2).
    private static final AtomicLong totalPeticiones   = new AtomicLong(0);
    private static final AtomicLong respuestasOk      = new AtomicLong(0);
    private static final AtomicLong respuestasRechazo = new AtomicLong(0);
    private static final AtomicLong perdidas          = new AtomicLong(0);
    private static final AtomicLong sumaLatenciasMs   = new AtomicLong(0); // solo de respuestas servidas
    private static final CopyOnWriteArrayList<Long> todasLatencias = new CopyOnWriteArrayList<>();

    private static final RelojLamport reloj = new RelojLamport();
    private static final Logger       LOG   = Logger.getLogger(GeneradorCarga.class.getName());

    private static final String   PASS = "pass123";

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
            final int idx = i;   // identidad fija del hilo → usuario propio
            pool.submit(() -> cicloWorker(finMs, idx));
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
                long serv   = respuestasOk.get() + respuestasRechazo.get();
                double avg  = serv > 0 ? (double) sumaLatenciasMs.get() / serv : 0;
                String msg  = String.format("[CARGA] t=%ds | throughput=%d req/s | latAvg=%.1fms | perdidas=%d",
                        elap, tp, avg, perdidas.get());
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

    private static void cicloWorker(long finMs, int idx) {
        Random rand = new Random();
        // Usuario propio del hilo (cliente1..clienteN). Receptor de mensajes = el siguiente.
        int    n    = Constantes.NUM_COMPRADORES;
        String user = "cliente" + ((idx % n) + 1);
        String otro = "cliente" + (((idx + 1) % n) + 1);
        while (System.currentTimeMillis() < finMs
                && !Thread.currentThread().isInterrupted()) {
            try {
                // a) LOGIN
                String token = op(() -> {
                    MensajeProtocolo req = MensajeProtocolo.request(Constantes.LOGIN, null);
                    req.put("username", user);
                    req.put("password", PASS);
                    MensajeProtocolo resp = enviar(req);
                    return (resp != null && resp.isOk()) ? resp.getString("token") : null;
                });
                if (token == null) continue; // login falló (ya contabilizado en enviar)

                // b) LISTAR_JUEGOS
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> juegos = op(() -> {
                    MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, token));
                    if (resp == null || !resp.isOk()) return null;
                    return (List<Map<String, Object>>) resp.get("juegos");
                });

                // c) COMPRAR un juego al azar (reparte la contención sobre el catálogo)
                String reservaId = null;
                if (juegos != null && !juegos.isEmpty()) {
                    String juegoId = juegos.get(rand.nextInt(juegos.size())).get("id").toString();
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
                // Excepción inesperada del worker (no es una petición de red en sí).
            }
        }
    }

    // ── Helper de control de flujo ────────────────────────────────────────────
    // Las métricas se capturan dentro de enviar() (una por round-trip de red).
    // op() solo ejecuta el paso y absorbe excepciones para no cortar el ciclo.

    @FunctionalInterface
    interface Operacion<T> { T ejecutar() throws Exception; }

    private static <T> T op(Operacion<T> o) {
        try { return o.ejecutar(); }
        catch (Exception e) { return null; }
    }

    // ── Transporte ────────────────────────────────────────────────────────────

    private static MensajeProtocolo enviar(MensajeProtocolo req) {
        req.setLamportClock(reloj.tick());
        long t0 = System.currentTimeMillis();
        totalPeticiones.incrementAndGet();
        try (Socket socket = new Socket(Constantes.HOST, Constantes.PUERTO_PROXY)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String resp = in.readLine();
            if (resp != null) {
                // Hubo respuesta → petición servida; registramos su latencia.
                long lat = System.currentTimeMillis() - t0;
                sumaLatenciasMs.addAndGet(lat);
                todasLatencias.add(lat);
                MensajeProtocolo m = MensajeProtocolo.fromJson(resp);
                if (m != null) {
                    reloj.update(m.getLamportClock());
                    if (m.isOk()) respuestasOk.incrementAndGet();
                    else          respuestasRechazo.incrementAndGet(); // ERROR de negocio, sistema vivo
                    return m;
                }
            }
            // resp == null o JSON corrupto → no llegó respuesta útil = pérdida
            perdidas.incrementAndGet();
        } catch (IOException e) {
            // Timeout / conexión rechazada (típico al caer un nodo) = pérdida real
            perdidas.incrementAndGet();
        }
        return null;
    }

    // ── Reporte ───────────────────────────────────────────────────────────────

    private static void imprimirReporteFinal(int duracionSeg, String logFile) {
        long total    = totalPeticiones.get();
        long ok       = respuestasOk.get();
        long rechazo  = respuestasRechazo.get();
        long perdida  = perdidas.get();
        long servidas = ok + rechazo;
        double tp     = total > 0    ? (double) total / duracionSeg : 0;
        double avg    = servidas > 0 ? (double) sumaLatenciasMs.get() / servidas : 0;
        double pctPer = total > 0    ? perdida * 100.0 / total : 0;

        // Calcular p95 (sobre peticiones servidas)
        List<Long> sorted = new ArrayList<>(todasLatencias);
        Collections.sort(sorted);
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95));

        // Mensajes de coordinación: consultar a cada nodo de svJuegos y sumar
        long[] coord     = recolectarCoord();
        long   msgBully  = coord[0];
        long   msgMutex  = coord[1];
        long   msgCoord  = msgBully + msgMutex;

        String reporte = String.format("""
                ══════════════════════════════════════
                REPORTE FINAL DE CARGA
                ══════════════════════════════════════
                Duración           : %ds
                Total peticiones   : %d
                Throughput         : %.1f req/s
                Latencia promedio  : %.1f ms
                Latencia p95       : %d ms
                Respuestas OK      : %d
                Rechazos de negocio: %d  (sistema vivo, no es pérdida)
                Pérdidas (timeout) : %d  (%.1f%% — tasa de pérdida)
                Msgs coordinación  : %d  (Bully=%d, Mutex=%d)
                Log guardado en    : %s
                ══════════════════════════════════════""",
                duracionSeg, total, tp, avg, p95, ok, rechazo,
                perdida, pctPer, msgCoord, msgBully, msgMutex, logFile);

        System.out.println(reporte);
        LOG.info(reporte);
    }

    /**
     * Consulta VER_METRICAS_COORD a ambos nodos de svJuegos (directo, sin proxy)
     * y suma los mensajes de coordinación emitidos. Retorna {totalBully, totalMutex}.
     * Si un nodo está caído (p.ej. tras la falla inducida), se omite.
     */
    private static long[] recolectarCoord() {
        long bully = 0, mutex = 0;
        int[] puertos = { Constantes.PUERTO_JUE_1, Constantes.PUERTO_JUE_2 };
        for (int puerto : puertos) {
            MensajeProtocolo resp = enviarA(
                    MensajeProtocolo.request(Constantes.VER_METRICAS_COORD, null), puerto);
            if (resp != null && resp.isOk()) {
                bully += (long) resp.getDouble("mensajesBully");
                mutex += (long) resp.getDouble("mensajesMutex");
            }
        }
        return new long[]{ bully, mutex };
    }

    /** Envía una petición a un puerto específico (sin pasar por el proxy). */
    private static MensajeProtocolo enviarA(MensajeProtocolo req, int puerto) {
        try (Socket socket = new Socket(Constantes.HOST, puerto)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String resp = in.readLine();
            return resp != null ? MensajeProtocolo.fromJson(resp) : null;
        } catch (IOException e) {
            return null;
        }
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
