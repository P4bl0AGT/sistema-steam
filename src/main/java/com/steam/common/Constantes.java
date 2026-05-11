package com.steam.common;

public final class Constantes {

    // ── Puertos ──────────────────────────────────────────────────────────────
    public static final String HOST             = "localhost";
    public static final int    PUERTO_PROXY     = 8080;
    public static final int    PUERTO_SES_1     = 8081;
    public static final int    PUERTO_SES_2     = 8181;
    public static final int    PUERTO_JUE_1     = 8082;
    public static final int    PUERTO_JUE_2     = 8282;
    public static final int    PUERTO_MSG_1     = 8083;
    public static final int    PUERTO_MSG_2     = 8383;

    // ── Timeouts ─────────────────────────────────────────────────────────────
    public static final int  TIMEOUT_MS              = 5_000;
    public static final int  HEALTH_INTERVAL_MS      = 10_000;
    public static final long TTL_RESERVA_MS          = 5L * 60 * 1_000; // 5 min

    // ── Operaciones – Sesiones ────────────────────────────────────────────────
    public static final String LOGIN             = "LOGIN";
    public static final String LOGOUT            = "LOGOUT";
    public static final String VALIDAR_TOKEN     = "VALIDAR_TOKEN";
    public static final String REGISTRAR_USUARIO = "REGISTRAR_USUARIO";
    public static final String LISTAR_USUARIOS   = "LISTAR_USUARIOS";
    public static final String CAMBIAR_PASS      = "CAMBIAR_PASS";

    // ── Operaciones – Juegos ─────────────────────────────────────────────────
    public static final String LISTAR_JUEGOS     = "LISTAR_JUEGOS";
    public static final String VER_JUEGO         = "VER_JUEGO";
    public static final String COMPRAR_JUEGO     = "COMPRAR_JUEGO";
    public static final String CONFIRMAR_PAGO    = "CONFIRMAR_PAGO";
    public static final String CANCELAR_RESERVA  = "CANCELAR_RESERVA";
    public static final String PUBLICAR_JUEGO    = "PUBLICAR_JUEGO";
    public static final String MODIFICAR_JUEGO   = "MODIFICAR_JUEGO";
    public static final String ELIMINAR_JUEGO    = "ELIMINAR_JUEGO";
    public static final String VER_SALDO         = "VER_SALDO";
    public static final String AGREGAR_SALDO     = "AGREGAR_SALDO";
    public static final String VER_HISTORIAL     = "VER_HISTORIAL";
    public static final String VER_ESTADISTICAS  = "VER_ESTADISTICAS";
    public static final String VER_MIS_COMPRAS   = "VER_MIS_COMPRAS";
    public static final String VER_MIS_JUEGOS    = "VER_MIS_JUEGOS";
    public static final String VER_MIS_RESERVAS  = "VER_MIS_RESERVAS";

    // ── Operaciones – Mensajería ──────────────────────────────────────────────
    public static final String ENVIAR_MENSAJE    = "ENVIAR_MENSAJE";
    public static final String VER_MENSAJES      = "VER_MENSAJES";
    public static final String VER_CONVERSACION  = "VER_CONVERSACION";

    // ── Sistema ───────────────────────────────────────────────────────────────
    public static final String HEALTH_CHECK      = "HEALTH_CHECK";

    // ── Roles ─────────────────────────────────────────────────────────────────
    public static final String ROL_COMPRADOR     = "COMPRADOR";
    public static final String ROL_VENDEDOR      = "VENDEDOR";
    public static final String ROL_ADMIN         = "ADMINISTRADOR";

    // ── Status de respuesta ───────────────────────────────────────────────────
    public static final String OK    = "OK";
    public static final String ERROR = "ERROR";

    // ── Concurrencia ──────────────────────────────────────────────────────────
    public static final int POOL_SIZE       = 30;
    public static final int MAX_CONNECTIONS = 100;

    // ── Archivos de datos ─────────────────────────────────────────────────────
    public static final String DATA_DIR  = "data";
    public static final String SES_MAIN  = "data/SES_Main.txt";
    public static final String SES_COPY  = "data/SES_Copy.txt";
    public static final String GME_MAIN  = "data/GME_Main.txt";
    public static final String GME_COPY  = "data/GME_Copy.txt";
    public static final String MSG_MAIN  = "data/MSG_Main.txt";
    public static final String MSG_COPY  = "data/MSG_Copy.txt";

    private Constantes() {}
}
