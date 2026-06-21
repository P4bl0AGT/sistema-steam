package com.steam.carga;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.steam.common.ClienteProxy;
import com.steam.common.Configuracion;
import com.steam.common.Constantes;
import com.steam.common.Endpoint;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Carga concurrente reproducible con metricas separadas por categoria. */
public final class GeneradorCarga {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter ID = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final int hilos;
    private final int duracionSeg;
    private final Path salida;
    private final LongAdder intentos = new LongAdder();
    private final LongAdder exitos = new LongAdder();
    private final LongAdder erroresNegocio = new LongAdder();
    private final LongAdder erroresSistema = new LongAdder();
    private final LongAdder indisponibilidad = new LongAdder();
    private final LongAdder respuestas = new LongAdder();
    private final LongAdder perdidasTransporte = new LongAdder();
    private final Map<String, LongAdder> porOperacion = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> porCodigoError = new ConcurrentHashMap<>();
    private final Map<Integer, AcumuladorCoordinacion> coordinacionPorNodo = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> latenciasMicros = new ConcurrentLinkedQueue<>();
    private final AtomicLongArray completadasPorSegundo;
    private final RelojLamport relojMetricas = new RelojLamport("carga-metricas");
    private final ScheduledExecutorService muestreador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "carga-metricas-coordinacion");
        t.setDaemon(true);
        return t;
    });
    private volatile long inicioNanos;

    private GeneradorCarga(int hilos, int duracionSeg, Path salida) {
        this.hilos = hilos;
        this.duracionSeg = duracionSeg;
        this.salida = salida;
        this.completadasPorSegundo = new AtomicLongArray(duracionSeg + 2);
    }

    public static void main(String[] args) throws Exception {
        int hilos = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int duracion = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        Path salida = args.length > 2 ? Path.of(args[2])
                : Path.of("evidencia", "carga", ID.format(Instant.now()));
        if (hilos < 1 || hilos > 500 || duracion < 1) throw new IllegalArgumentException("Parametros invalidos");
        new GeneradorCarga(hilos, duracion, salida).ejecutar();
    }

    private void ejecutar() throws Exception {
        Files.createDirectories(salida);
        List<String> juegos = obtenerJuegos();
        iniciarMuestreoCoordinacion();
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch listos = new CountDownLatch(hilos);
        CountDownLatch inicio = new CountDownLatch(1);
        for (int i = 1; i <= hilos; i++) {
            int usuario = i;
            pool.submit(() -> trabajador(usuario, juegos, listos, inicio));
        }
        if (!listos.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout preparando clientes");
        inicioNanos = System.nanoTime();
        inicio.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(duracionSeg + 45L, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            throw new IllegalStateException("La carga no termino");
        }
        detenerMuestreoCoordinacion();
        escribirReporte();
    }

    private void trabajador(int numero, List<String> juegos, CountDownLatch listos, CountDownLatch inicio) {
        RelojLamport reloj = new RelojLamport("carga-" + numero);
        String token = login(numero, reloj);
        Set<String> comprasIntentadas = ConcurrentHashMap.newKeySet();
        listos.countDown();
        try { inicio.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        long fin = inicioNanos + TimeUnit.SECONDS.toNanos(duracionSeg);
        while (System.nanoTime() < fin && !Thread.currentThread().isInterrupted()) {
            int opcion = ThreadLocalRandom.current().nextInt(100);
            MensajeProtocolo req;
            if (opcion < 50 || token == null) {
                req = MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, token);
            } else if (opcion < 70) {
                req = MensajeProtocolo.request(Constantes.VER_SALDO, token);
            } else if (opcion < 82) {
                req = MensajeProtocolo.request(Constantes.VER_MENSAJES, token);
            } else if (opcion < 95) {
                int receptor = numero == hilos ? 1 : numero + 1;
                req = MensajeProtocolo.request(Constantes.ENVIAR_MENSAJE, token)
                        .put("receptor", "cliente" + receptor)
                        .put("contenido", "carga-" + numero + "-" + System.nanoTime());
            } else {
                String juego = juegos.stream().filter(comprasIntentadas::add).findFirst().orElse(null);
                req = juego == null ? MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, token)
                        : MensajeProtocolo.request(Constantes.COMPRAR_JUEGO, token).put("juegoId", juego);
            }
            medir(req, reloj, "carga-" + numero);
        }
    }

    private String login(int numero, RelojLamport reloj) {
        try {
            MensajeProtocolo req = MensajeProtocolo.request(Constantes.LOGIN, null)
                    .put("username", "cliente" + numero).put("password", "pass123");
            MensajeProtocolo resp = ClienteProxy.enviar(req, reloj, "carga-login-" + numero);
            return resp.isOk() ? resp.getString("token") : null;
        } catch (Exception e) { return null; }
    }

    private List<String> obtenerJuegos() throws Exception {
        MensajeProtocolo resp = ClienteProxy.enviar(
                MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, null),
                new RelojLamport("carga-setup"), "carga-setup");
        List<String> ids = new ArrayList<>();
        Object raw = resp.get("juegos");
        if (raw instanceof List<?> lista) for (Object item : lista) {
            if (item instanceof Map<?, ?> mapa && mapa.get("id") != null) ids.add(mapa.get("id").toString());
        }
        if (ids.isEmpty()) throw new IllegalStateException("Catalogo vacio");
        return List.copyOf(ids);
    }

    private void medir(MensajeProtocolo req, RelojLamport reloj, String emisor) {
        intentos.increment();
        porOperacion.computeIfAbsent(req.getOperacion(), ignored -> new LongAdder()).increment();
        long inicio = System.nanoTime();
        try {
            MensajeProtocolo resp = ClienteProxy.enviar(req, reloj, emisor);
            respuestas.increment();
            registrarCompletada();
            if (resp.isOk()) {
                exitos.increment();
            } else {
                clasificarError(resp.getCodigoError());
            }
        } catch (Exception e) {
            perdidasTransporte.increment();
        } finally {
            long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - inicio);
            latenciasMicros.add(micros);
        }
    }

    private void registrarCompletada() {
        int segundo = (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - inicioNanos);
        if (segundo >= 0 && segundo < completadasPorSegundo.length()) {
            completadasPorSegundo.incrementAndGet(segundo);
        }
    }

    private void clasificarError(String codigo) {
        String normalizado = codigo == null || codigo.isBlank() ? "UNCLASSIFIED_ERROR" : codigo;
        porCodigoError.computeIfAbsent(normalizado, ignored -> new LongAdder()).increment();
        if (normalizado.startsWith("BUSINESS_")) erroresNegocio.increment();
        else if (normalizado.contains("UNAVAILABLE") || "NOT_PRIMARY".equals(normalizado)) {
            indisponibilidad.increment();
        } else erroresSistema.increment();
    }

    private void escribirReporte() throws Exception {
        List<Long> latencias = new ArrayList<>(latenciasMicros);
        latencias.sort(Comparator.naturalOrder());
        double promedioMs = latencias.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000.0;
        double p95Ms = latencias.isEmpty() ? 0.0
                : latencias.get(Math.min(latencias.size() - 1, (int) Math.ceil(latencias.size() * 0.95) - 1)) / 1_000.0;
        AcumuladorCoordinacion.Snapshot coordinacion = mensajesCoordinacion();
        double perdidaPct = intentos.sum() == 0 ? 0.0 : 100.0 * perdidasTransporte.sum() / intentos.sum();

        Map<String, Object> reporte = new LinkedHashMap<>();
        reporte.put("fechaUtc", Instant.now().toString());
        reporte.put("hilos", hilos);
        reporte.put("duracionSeg", duracionSeg);
        reporte.put("intentos", intentos.sum());
        reporte.put("exitos", exitos.sum());
        reporte.put("erroresNegocio", erroresNegocio.sum());
        reporte.put("erroresSistema", erroresSistema.sum());
        reporte.put("indisponibilidad", indisponibilidad.sum());
        reporte.put("respuestas", respuestas.sum());
        reporte.put("perdidasTransporte", perdidasTransporte.sum());
        reporte.put("tasaPerdidaPct", perdidaPct);
        reporte.put("throughputExitosSeg", exitos.sum() / (double) duracionSeg);
        reporte.put("throughputCompletadasSeg", respuestas.sum() / (double) duracionSeg);
        reporte.put("latenciaPromedioMs", promedioMs);
        reporte.put("latenciaP95Ms", p95Ms);
        reporte.put("mensajesBully", coordinacion.bully());
        reporte.put("mensajesMutex", coordinacion.mutex());
        reporte.put("mensajesCoordinacion", coordinacion.total());
        Map<String, Long> operaciones = new java.util.TreeMap<>();
        porOperacion.forEach((k, v) -> operaciones.put(k, v.sum()));
        reporte.put("operaciones", operaciones);
        Map<String, Long> codigos = new java.util.TreeMap<>();
        porCodigoError.forEach((k, v) -> codigos.put(k, v.sum()));
        reporte.put("codigosError", codigos);
        Files.writeString(salida.resolve("reporte-carga.json"), GSON.toJson(reporte), StandardCharsets.UTF_8);

        String cabecera = "hilos,duracionSeg,intentos,respuestas,exitos,erroresNegocio,erroresSistema,indisponibilidad,perdidasTransporte,tasaPerdidaPct,throughputExitosSeg,latenciaPromedioMs,latenciaP95Ms,mensajesBully,mensajesMutex,mensajesCoordinacion\n";
        String fila = String.format(java.util.Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%d,%d,%d%n",
                hilos, duracionSeg, intentos.sum(), respuestas.sum(), exitos.sum(), erroresNegocio.sum(),
                erroresSistema.sum(), indisponibilidad.sum(),
                perdidasTransporte.sum(), perdidaPct, exitos.sum() / (double) duracionSeg,
                promedioMs, p95Ms, coordinacion.bully(), coordinacion.mutex(), coordinacion.total());
        Files.writeString(salida.resolve("resumen.csv"), cabecera + fila, StandardCharsets.UTF_8);

        StringBuilder serie = new StringBuilder("segundo,completadas\n");
        for (int i = 0; i < duracionSeg; i++) serie.append(i + 1).append(',').append(completadasPorSegundo.get(i)).append('\n');
        Files.writeString(salida.resolve("throughput-por-segundo.csv"), serie, StandardCharsets.UTF_8);
        Files.writeString(salida.resolve("throughput.svg"), graficoSvg(), StandardCharsets.UTF_8);

        System.out.printf(java.util.Locale.ROOT,
                "OK CARGA hilos=%d duracion=%ds intentos=%d exitos=%d negocio=%d sistema=%d indisponibilidad=%d perdidas=%d perdida=%.3f%% throughput=%.2f/s promedio=%.2fms p95=%.2fms coord=%d%nEVIDENCIA=%s%n",
                hilos, duracionSeg, intentos.sum(), exitos.sum(), erroresNegocio.sum(), erroresSistema.sum(),
                indisponibilidad.sum(),
                perdidasTransporte.sum(), perdidaPct, exitos.sum() / (double) duracionSeg,
                promedioMs, p95Ms, coordinacion.total(), salida.toAbsolutePath());
    }

    private void iniciarMuestreoCoordinacion() {
        muestreador.scheduleWithFixedDelay(this::muestrearCoordinacion, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void detenerMuestreoCoordinacion() {
        muestrearCoordinacion();
        muestreador.shutdownNow();
    }

    private void muestrearCoordinacion() {
        Map<Integer, Endpoint> endpoints = Map.of(
                1, new Endpoint(Configuracion.hostServicio("juegos", 1), Constantes.PUERTO_JUE_1),
                2, new Endpoint(Configuracion.hostServicio("juegos", 2), Constantes.PUERTO_JUE_2));
        endpoints.forEach((nodo, endpoint) -> {
            try {
                MensajeProtocolo req = MensajeProtocolo.request(Constantes.VER_METRICAS_COORD, null);
                req.setLamportClock(relojMetricas.tick());
                MensajeProtocolo resp = ClienteProxy.enviarA(req, endpoint);
                relojMetricas.update(resp.getLamportClock());
                if (resp.isOk()) {
                    long bully = ((Number) resp.get("mensajesBully")).longValue();
                    long mutex = ((Number) resp.get("mensajesMutex")).longValue();
                    coordinacionPorNodo.computeIfAbsent(nodo, ignored -> new AcumuladorCoordinacion())
                            .observar(bully, mutex);
                }
            } catch (Exception ignored) {}
        });
    }

    private AcumuladorCoordinacion.Snapshot mensajesCoordinacion() {
        long bully = 0L;
        long mutex = 0L;
        for (AcumuladorCoordinacion acumulador : coordinacionPorNodo.values()) {
            AcumuladorCoordinacion.Snapshot snapshot = acumulador.snapshot();
            bully += snapshot.bully();
            mutex += snapshot.mutex();
        }
        return new AcumuladorCoordinacion.Snapshot(bully, mutex);
    }

    private String graficoSvg() {
        int ancho = 900, alto = 320, margen = 40;
        long max = 1;
        for (int i = 0; i < duracionSeg; i++) max = Math.max(max, completadasPorSegundo.get(i));
        StringBuilder puntos = new StringBuilder();
        for (int i = 0; i < duracionSeg; i++) {
            double x = margen + i * (ancho - 2.0 * margen) / Math.max(1, duracionSeg - 1);
            double y = alto - margen - completadasPorSegundo.get(i) * (alto - 2.0 * margen) / max;
            puntos.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + ancho + "\" height=\"" + alto + "\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"white\"/><text x=\"40\" y=\"22\" font-family=\"sans-serif\" font-size=\"16\">Throughput por segundo</text>"
                + "<line x1=\"40\" y1=\"280\" x2=\"860\" y2=\"280\" stroke=\"#333\"/><line x1=\"40\" y1=\"40\" x2=\"40\" y2=\"280\" stroke=\"#333\"/>"
                + "<polyline fill=\"none\" stroke=\"#1769aa\" stroke-width=\"2\" points=\"" + puntos + "\"/>"
                + "<text x=\"8\" y=\"45\" font-family=\"sans-serif\" font-size=\"12\">" + max + "</text>"
                + "<text x=\"820\" y=\"305\" font-family=\"sans-serif\" font-size=\"12\">segundos</text></svg>";
    }
}
