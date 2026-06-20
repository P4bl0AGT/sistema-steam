package com.steam.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

public final class Utils {
    private static final int PBKDF2_ITERACIONES = 60_000;
    private static final int PBKDF2_BITS = 256;

    private Utils() {}

    /** Hash PBKDF2-HMAC-SHA256 con sal aleatoria. */
    public static String hashPassword(String plain) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(plain.toCharArray(), salt, PBKDF2_ITERACIONES, PBKDF2_BITS);
            return "pbkdf2-sha256$" + PBKDF2_ITERACIONES + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 no disponible", e);
        }
    }

    /** Compara en tiempo constante; acepta el SHA-256 historico para migracion. */
    public static boolean verificarPassword(String plain, String storedHash) {
        if (storedHash == null) return false;
        try {
            if (storedHash.startsWith("pbkdf2-sha256$")) {
                String[] p = storedHash.split("\\$", 4);
                int iterations = Integer.parseInt(p[1]);
                byte[] salt = Base64.getDecoder().decode(p[2]);
                byte[] expected = Base64.getDecoder().decode(p[3]);
                byte[] actual = pbkdf2(plain.toCharArray(), salt, iterations, expected.length * 8);
                return MessageDigest.isEqual(actual, expected);
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] actual = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().parseHex(storedHash);
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /** Determina el cluster al que pertenece una operacion publica. */
    public static String clusterParaOperacion(String operacion) {
        return switch (operacion) {
            case Constantes.LOGIN, Constantes.LOGOUT, Constantes.VALIDAR_TOKEN,
                 Constantes.REGISTRAR_USUARIO, Constantes.LISTAR_USUARIOS,
                 Constantes.CAMBIAR_PASS -> "SESIONES";

            case Constantes.LISTAR_JUEGOS, Constantes.VER_JUEGO,
                 Constantes.COMPRAR_JUEGO, Constantes.CONFIRMAR_PAGO,
                 Constantes.CANCELAR_RESERVA, Constantes.PUBLICAR_JUEGO,
                 Constantes.MODIFICAR_JUEGO, Constantes.ELIMINAR_JUEGO,
                 Constantes.VER_SALDO, Constantes.AGREGAR_SALDO,
                 Constantes.VER_HISTORIAL, Constantes.VER_ESTADISTICAS,
                 Constantes.VER_MIS_COMPRAS, Constantes.VER_MIS_JUEGOS,
                 Constantes.VER_MIS_RESERVAS, Constantes.QUIEN_ES_COORDINADOR,
                 Constantes.SHUTDOWN_GRACEFUL, Constantes.VER_METRICAS_COORD,
                 Constantes.ESTADO_REPLICACION -> "JUEGOS";

            case Constantes.ENVIAR_MENSAJE, Constantes.VER_MENSAJES,
                 Constantes.VER_CONVERSACION -> "MENSAJERIA";

            default -> "DESCONOCIDO";
        };
    }

    private static final Set<String> ESCRITURAS = Set.of(
            Constantes.LOGIN, Constantes.LOGOUT, Constantes.REGISTRAR_USUARIO,
            Constantes.CAMBIAR_PASS, Constantes.COMPRAR_JUEGO,
            Constantes.CONFIRMAR_PAGO, Constantes.CANCELAR_RESERVA,
            Constantes.PUBLICAR_JUEGO, Constantes.MODIFICAR_JUEGO,
            Constantes.ELIMINAR_JUEGO, Constantes.AGREGAR_SALDO,
            Constantes.ENVIAR_MENSAJE, Constantes.VER_MENSAJES
    );

    public static boolean esOperacionEscritura(String operacion) {
        return ESCRITURAS.contains(operacion);
    }
}
