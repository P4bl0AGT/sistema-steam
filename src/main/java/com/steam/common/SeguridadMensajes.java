package com.steam.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Validaciones de frescura y autenticacion de operaciones de control. */
public final class SeguridadMensajes {
    private static final String CAMPO_FIRMA = "_controlProof";

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
        return req.getRequestId() + "|" + req.getOperacion() + "|" + req.getTimestamp();
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
}
