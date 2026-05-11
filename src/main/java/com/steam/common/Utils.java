package com.steam.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Utils {

    private Utils() {}

    /** Hash SHA-256 de una contraseña en texto plano. */
    public static String hashPassword(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 no disponible", e);
        }
    }

    /** Verifica si la contraseña en texto plano coincide con el hash almacenado. */
    public static boolean verificarPassword(String plain, String storedHash) {
        return hashPassword(plain).equals(storedHash);
    }

    /** Determina a qué clúster debe ir una operación. */
    public static String clusterParaOperacion(String operacion) {
        return switch (operacion) {
            case Constantes.LOGIN,
                 Constantes.LOGOUT,
                 Constantes.VALIDAR_TOKEN,
                 Constantes.REGISTRAR_USUARIO,
                 Constantes.LISTAR_USUARIOS,
                 Constantes.CAMBIAR_PASS        -> "SESIONES";

            case Constantes.LISTAR_JUEGOS,
                 Constantes.VER_JUEGO,
                 Constantes.COMPRAR_JUEGO,
                 Constantes.CONFIRMAR_PAGO,
                 Constantes.CANCELAR_RESERVA,
                 Constantes.PUBLICAR_JUEGO,
                 Constantes.MODIFICAR_JUEGO,
                 Constantes.ELIMINAR_JUEGO,
                 Constantes.VER_SALDO,
                 Constantes.AGREGAR_SALDO,
                 Constantes.VER_HISTORIAL,
                 Constantes.VER_ESTADISTICAS,
                 Constantes.VER_MIS_COMPRAS,
                 Constantes.VER_MIS_JUEGOS,
                 Constantes.VER_MIS_RESERVAS    -> "JUEGOS";

            case Constantes.ENVIAR_MENSAJE,
                 Constantes.VER_MENSAJES,
                 Constantes.VER_CONVERSACION    -> "MENSAJERIA";

            default                             -> "DESCONOCIDO";
        };
    }
}
