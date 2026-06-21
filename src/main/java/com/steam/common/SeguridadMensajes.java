package com.steam.common;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Validaciones de frescura y autenticacion de operaciones de control. */
public final class SeguridadMensajes {
    private static final String CAMPO_FIRMA = "_controlProof";
    private static final Gson GSON = new Gson();

    private SeguridadMensajes() {}

    public static String validarSolicitud(MensajeProtocolo req) {
        if (req == null) return "Mensaje nulo";
        if (!MensajeProtocolo.TIPO_REQUEST.equals(req.getTipo())) return "Tipo debe ser REQUEST";
        if (req.getRequestId() == null || req.getRequestId().isBlank()) return "requestId requerido";
        if (req.getOperacion() == null || req.getOperacion().isBlank()) return "operacion requerida";
        long edad = System.currentTimeMillis() - req.getTimestamp();
        if (edad < -5_000L || edad > Configuracion.requestMaxAgeMs()) return "Solicitud fuera de ventana temporal";
        return null;
    }

    public static void firmarControl(MensajeProtocolo req) {
        req.put(CAMPO_FIRMA, hmac(baseFirma(req), Configuracion.controlSecret()));
    }

    public static boolean validarControl(MensajeProtocolo req) {
        String recibida = req != null ? req.getString(CAMPO_FIRMA) : null;
        String secret = Configuracion.controlSecret();
        if (recibida == null || secret.isBlank()) return false;
        String esperada = hmac(baseFirma(req), secret);
        return MessageDigest.isEqual(recibida.getBytes(StandardCharsets.US_ASCII),
                esperada.getBytes(StandardCharsets.US_ASCII));
    }

    private static String baseFirma(MensajeProtocolo req) {
        return valor(req.getTipo()) + "|" + valor(req.getRequestId()) + "|"
                + valor(req.getOperacion()) + "|" + req.getTimestamp() + "|"
                + req.getVersionProtocolo() + "|" + valor(req.getToken()) + "|"
                + valor(req.getEmisor()) + "|" + valor(req.getReceptor()) + "|"
                + req.getLamportClock() + "|" + payloadCanonico(req);
    }

    private static String valor(String value) { return value == null ? "-" : value; }

    /** JSON canónico: claves ordenadas y números normalizados, sin el propio HMAC. */
    private static String payloadCanonico(MensajeProtocolo req) {
        Map<String, Object> source = req.getPayload() == null ? Map.of() : req.getPayload();
        Map<String, Object> clean = new TreeMap<>(source);
        clean.remove(CAMPO_FIRMA);
        return canonicalizar(GSON.toJsonTree(clean));
    }

    private static String canonicalizar(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isNumber()) {
                BigDecimal number = new BigDecimal(element.getAsString()).stripTrailingZeros();
                return number.signum() == 0 ? "0" : number.toPlainString();
            }
            return GSON.toJson(element);
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalizar(array.get(i)));
            }
            return out.append(']').toString();
        }
        JsonObject object = element.getAsJsonObject();
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String key : new TreeMap<>(object.asMap()).keySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(GSON.toJson(key)).append(':').append(canonicalizar(object.get(key)));
        }
        return out.append('}').toString();
    }

    private static String hmac(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC no disponible", e);
        }
    }

    public static String firmarTexto(String value) {
        String secret = Configuracion.controlSecret();
        if (secret.isBlank()) throw new IllegalStateException("steam.control.secret es obligatorio");
        return hmac(value, secret);
    }

    public static boolean validarTexto(String value, String firma) {
        if (firma == null) return false;
        String esperada = firmarTexto(value);
        return MessageDigest.isEqual(firma.getBytes(StandardCharsets.US_ASCII),
                esperada.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256Texto(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
