package com.steam.common;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Utilidad para que svJuegos y svMensajeria validen tokens llamando
 * directamente a svSesiones, sin pasar por el Proxy.
 *
 * MODELO DE FALLOS: intenta svSesiones nodo 1 (8081) primero;
 * si no responde en TIMEOUT_MS, conmuta al nodo 2 (8181).
 *
 * CACHÉ IN-MEMORY (TTL 30 s):
 * Cada llamada a validar() abría una nueva conexión TCP a svSesiones,
 * sumando 1 conexión extra por cada operación de negocio. El caché
 * elimina esa latencia para el caso frecuente (token recién validado).
 * Tradeoff aceptado: un token invalidado por LOGOUT puede seguir siendo
 * aceptado hasta 30 s; correcto para el contexto académico de este sistema.
 */
public class ValidadorToken {

    private static final Logger LOG     = Logger.getLogger(ValidadorToken.class.getName());
    private static final long   TTL_MS  = 2_000L;

    /** Resultado de la validación */
    public record ResultadoValidacion(boolean valido, String username, String rol, String mensaje) {}

    // ── Caché ─────────────────────────────────────────────────────────────────

    private record CachedResult(ResultadoValidacion result, long expiresAt) {}

    /** ConcurrentHashMap: seguro para acceso desde múltiples hilos del pool. */
    private static final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Valida el token contra svSesiones.
     * Primero consulta el caché; si hay hit vigente lo retorna sin abrir socket.
     * Si no, contacta svSesiones y guarda el resultado por TTL_MS.
     */
    public static ResultadoValidacion validar(String token) {
        if (token == null || token.isBlank()) {
            return new ResultadoValidacion(false, null, null, "Token vacío");
        }

        // ── Cache hit ─────────────────────────────────────────────────────────
        CachedResult cached = cache.get(token);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt()) {
            return cached.result();
        }

        // ── Cache miss: consultar svSesiones ──────────────────────────────────
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.VALIDAR_TOKEN, token);
        req.setTipo(MensajeProtocolo.TIPO_REQUEST);

        Endpoint[] destinos = {
                new Endpoint(Configuracion.hostServicio("sesiones", 1), Constantes.PUERTO_SES_1),
                new Endpoint(Configuracion.hostServicio("sesiones", 2), Constantes.PUERTO_SES_2)
        };
        for (Endpoint destino : destinos) {
            try {
                MensajeProtocolo resp = enviarYRecibir(req, destino);
                if (resp != null && resp.isOk()) {
                    ResultadoValidacion resultado = new ResultadoValidacion(
                            true, resp.getString("username"), resp.getString("rol"), "Token válido");
                    // Guardar en caché solo los tokens válidos
                    cache.put(token, new CachedResult(resultado,
                            System.currentTimeMillis() + TTL_MS));
                    return resultado;
                }
                if (resp != null) {
                    // Token inválido: no cachear (podría ser un error transitorio)
                    return new ResultadoValidacion(false, null, null, resp.getMensaje());
                }
            } catch (Exception e) {
                LOG.warning("svSesiones " + destino + " no disponible. " + e.getMessage());
            }
        }
        return new ResultadoValidacion(false, null, null, "Servicio de sesiones no disponible");
    }

    /**
     * Invalida la entrada del caché para el token dado.
     * Llamar opcionalmente tras confirmar un LOGOUT exitoso en svSesiones,
     * para no esperar a que expire el TTL.
     */
    public static void invalidarCache(String token) {
        if (token != null) cache.remove(token);
    }

    // ── Transporte ────────────────────────────────────────────────────────────

    private static MensajeProtocolo enviarYRecibir(MensajeProtocolo req, Endpoint destino)
            throws IOException {
        try (Socket socket = Transporte.conectar(destino.host(), destino.puerto())) {
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String respJson = LineaJson.leer(in, Configuracion.maxMessageBytes());
            return respJson != null ? MensajeProtocolo.fromJson(respJson) : null;
        }
    }
}
