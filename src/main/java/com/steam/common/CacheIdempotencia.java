package com.steam.common;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Deduplica requestId concurrentes y reutiliza la respuesta durante un TTL. */
public final class CacheIdempotencia {
    private static final Logger LOG = Logger.getLogger(CacheIdempotencia.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type TIPO_PERSISTIDO = new TypeToken<Map<String, Persistida>>() {}.getType();

    private static final class Persistida {
        long creadaEn;
        String huella;
        String estado;
        String respuestaJson;
    }

    private record Entrada(long creadaEn, String huella,
                           CompletableFuture<MensajeProtocolo> respuesta) {}
    private final Map<String, Entrada> entradas = new ConcurrentHashMap<>();
    private final Path archivo;

    public CacheIdempotencia() { this.archivo = null; }

    public CacheIdempotencia(String path) {
        this.archivo = Path.of(path);
        cargar();
    }

    public MensajeProtocolo ejecutar(String requestId, Supplier<MensajeProtocolo> operacion) {
        return ejecutar(requestId, requestId, operacion);
    }

    public MensajeProtocolo ejecutar(MensajeProtocolo request,
                                     Supplier<MensajeProtocolo> operacion) {
        return ejecutar(request.getRequestId(), SeguridadMensajes.huellaSolicitud(request), operacion);
    }

    public MensajeProtocolo ejecutar(String requestId, String huella,
                                     Supplier<MensajeProtocolo> operacion) {
        limpiarExpiradas();
        Entrada nueva = new Entrada(System.currentTimeMillis(), huella, new CompletableFuture<>());
        Entrada existente = entradas.putIfAbsent(requestId, nueva);
        if (existente != null) {
            if (!java.util.Objects.equals(existente.huella, huella)) {
                return MensajeProtocolo.error(requestId, "REQUEST_ID_CONFLICT",
                        "requestId reutilizado con contenido diferente");
            }
            return esperar(requestId, existente);
        }
        if (!guardarMarcador()) {
            entradas.remove(requestId, nueva);
            return MensajeProtocolo.error(requestId, "IDEMPOTENCY_PERSISTENCE_ERROR",
                    "No se pudo reservar requestId antes de ejecutar la operacion");
        }
        try {
            MensajeProtocolo respuesta = operacion.get();
            nueva.respuesta.complete(clonar(respuesta));
            persistirSilencioso();
            return respuesta;
        } catch (Throwable t) {
            nueva.respuesta.completeExceptionally(t);
            if (archivo == null) entradas.remove(requestId, nueva);
            else persistirSilencioso();
            throw t;
        }
    }

    private MensajeProtocolo esperar(String requestId, Entrada entrada) {
        try {
            return clonar(entrada.respuesta.get(Configuracion.readTimeoutMs() * 2L, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return MensajeProtocolo.error(requestId, "Solicitud duplicada aun en proceso")
                    .setCodigoErrorFluent("DUPLICATE_IN_PROGRESS");
        }
    }

    private void limpiarExpiradas() {
        long ttl = archivo == null ? Configuracion.requestCacheTtlMs()
                : Configuracion.getLong("steam.request.idempotency.retention.ms", 2_592_000_000L);
        long limite = System.currentTimeMillis() - ttl;
        boolean cambio = entradas.entrySet().removeIf(e -> e.getValue().creadaEn < limite
                && e.getValue().respuesta.isDone());
        if (cambio) persistirSilencioso();
    }

    private static MensajeProtocolo clonar(MensajeProtocolo m) {
        return m == null ? null : MensajeProtocolo.fromJson(m.toJson());
    }

    private boolean guardarMarcador() {
        if (archivo == null) return true;
        try {
            persistir();
            return true;
        } catch (IOException e) {
            LOG.severe("No se pudo persistir marcador idempotente: " + e.getMessage());
            return false;
        }
    }

    private void persistirSilencioso() {
        if (archivo == null) return;
        try { persistir(); }
        catch (IOException e) {
            LOG.severe("Resultado idempotente quedo con estado incierto: " + e.getMessage());
        }
    }

    private synchronized void persistir() throws IOException {
        if (archivo.getParent() != null) Files.createDirectories(archivo.getParent());
        Map<String, Persistida> salida = new HashMap<>();
        for (Map.Entry<String, Entrada> item : entradas.entrySet()) {
            Entrada entrada = item.getValue();
            Persistida p = new Persistida();
            p.creadaEn = entrada.creadaEn;
            p.huella = entrada.huella;
            if (entrada.respuesta.isDone() && !entrada.respuesta.isCompletedExceptionally()) {
                p.estado = "DONE";
                MensajeProtocolo respuesta = entrada.respuesta.getNow(null);
                p.respuestaJson = respuesta == null ? null : respuesta.toJson();
            } else {
                p.estado = "PENDING";
            }
            salida.put(item.getKey(), p);
        }
        Path tmp = Path.of(archivo + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(tmp, GSON.toJson(salida), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void importarDesdeReplica(CacheIdempotencia peerCache) {
        if (peerCache == null) return;
        for (Map.Entry<String, Entrada> item : peerCache.entradas.entrySet()) {
            Entrada peer = item.getValue();
            if (!peer.respuesta.isDone()) continue;
            entradas.putIfAbsent(item.getKey(), peer);
        }
        persistirSilencioso();
    }

    public void importarRequestIds(Map<String, String> requestIdsConHuella) {
        if (requestIdsConHuella == null) return;
        long ahora = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : requestIdsConHuella.entrySet()) {
            entradas.computeIfAbsent(entry.getKey(), id -> {
                CompletableFuture<MensajeProtocolo> f = new CompletableFuture<>();
                f.complete(MensajeProtocolo.error(id, "REQUEST_ALREADY_PROCESSED",
                        "Operacion ya ejecutada en nodo peer"));
                return new Entrada(ahora, entry.getValue(), f);
            });
        }
        persistirSilencioso();
    }

    public Map<String, String> exportarRequestIdsRecientes(long desdeMs) {
        Map<String, String> resultado = new HashMap<>();
        long limite = System.currentTimeMillis() - desdeMs;
        for (Map.Entry<String, Entrada> item : entradas.entrySet()) {
            Entrada e = item.getValue();
            if (e.creadaEn >= limite && e.respuesta.isDone()) {
                resultado.put(item.getKey(), e.huella);
            }
        }
        return resultado;
    }

    private void cargar() {
        if (!Files.exists(archivo)) return;
        try {
            Map<String, Persistida> guardadas = GSON.fromJson(
                    Files.readString(archivo, StandardCharsets.UTF_8), TIPO_PERSISTIDO);
            if (guardadas == null) return;
            guardadas.forEach((requestId, p) -> {
                CompletableFuture<MensajeProtocolo> future = new CompletableFuture<>();
                if ("DONE".equals(p.estado) && p.respuestaJson != null) {
                    future.complete(MensajeProtocolo.fromJson(p.respuestaJson));
                } else {
                    future.complete(MensajeProtocolo.error(requestId, "REQUEST_OUTCOME_UNKNOWN",
                            "La JVM se reinicio con esta operacion en curso; no se repetira automaticamente"));
                }
                entradas.put(requestId, new Entrada(p.creadaEn, p.huella, future));
            });
        } catch (Exception e) {
            throw new IllegalStateException("Cache idempotente corrupta: " + archivo, e);
        }
    }
}
