package com.steam.common;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Deduplica requestId concurrentes y reutiliza la respuesta durante un TTL. */
public final class CacheIdempotencia {
    private record Entrada(long creadaEn, CompletableFuture<MensajeProtocolo> respuesta) {}
    private final Map<String, Entrada> entradas = new ConcurrentHashMap<>();

    public MensajeProtocolo ejecutar(String requestId, Supplier<MensajeProtocolo> operacion) {
        limpiarExpiradas();
        Entrada nueva = new Entrada(System.currentTimeMillis(), new CompletableFuture<>());
        Entrada existente = entradas.putIfAbsent(requestId, nueva);
        if (existente != null) return esperar(existente);
        try {
            MensajeProtocolo respuesta = operacion.get();
            nueva.respuesta.complete(clonar(respuesta));
            return respuesta;
        } catch (Throwable t) {
            nueva.respuesta.completeExceptionally(t);
            entradas.remove(requestId, nueva);
            throw t;
        }
    }

    private MensajeProtocolo esperar(Entrada entrada) {
        try {
            return clonar(entrada.respuesta.get(Configuracion.readTimeoutMs() * 2L, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return MensajeProtocolo.error("?", "Solicitud duplicada aun en proceso")
                    .setCodigoErrorFluent("DUPLICATE_IN_PROGRESS");
        }
    }

    private void limpiarExpiradas() {
        long limite = System.currentTimeMillis() - Configuracion.requestCacheTtlMs();
        entradas.entrySet().removeIf(e -> e.getValue().creadaEn < limite && e.getValue().respuesta.isDone());
    }

    private static MensajeProtocolo clonar(MensajeProtocolo m) {
        return m == null ? null : MensajeProtocolo.fromJson(m.toJson());
    }
}

