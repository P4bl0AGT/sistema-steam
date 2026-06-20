package com.steam.common;

public final class Constantes {

    // ── Puertos ──────────────────────────────────────────────────────────────
    public static final String HOST             = "localhost";
    public static final int    PUERTO_PROXY     = 8080;
    public static final int    PUERTO_PROXY_2   = 8085;
    public static final int    PUERTO_SES_1     = 8081;
    public static final int    PUERTO_SES_2     = 8181;
    public static final int    PUERTO_JUE_1     = 8082;
    public static final int    PUERTO_JUE_2     = 8282;
    public static final int    PUERTO_MSG_1     = 8083;
    public static final int    PUERTO_MSG_2     = 8383;

    // Replicacion de estado por TCP (almacenamiento independiente por nodo)
    public static final int    PUERTO_JUE_1_REPL = 9482;
    public static final int    PUERTO_JUE_2_REPL = 9582;
    public static final int    PUERTO_SES_1_REPL = 9483;
    public static final int    PUERTO_SES_2_REPL = 9583;
    public static final int    PUERTO_MSG_1_REPL = 9484;
    public static final int    PUERTO_MSG_2_REPL = 9584;

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
    public static final String HEALTH_CHECK            = "HEALTH_CHECK";
    public static final String QUIEN_ES_COORDINADOR    = "QUIEN_ES_COORDINADOR";
    public static final String SHUTDOWN_GRACEFUL       = "SHUTDOWN_GRACEFUL";
    public static final String VER_METRICAS_COORD      = "VER_METRICAS_COORD";
    public static final String SHUTDOWN_PROXY          = "SHUTDOWN_PROXY";
    public static final String ESTADO_MEMBRESIA        = "ESTADO_MEMBRESIA";
    public static final String ESTADO_REPLICACION      = "ESTADO_REPLICACION";

    // ── Registro dinámico en Proxy ────────────────────────────────────────────
    public static final String REGISTRAR_NODO          = "REGISTRAR_NODO";
    public static final String DESREGISTRAR_NODO       = "DESREGISTRAR_NODO";

    // ── Watchdog ──────────────────────────────────────────────────────────────
    public static final int WATCHDOG_INTERVALO_SEG     = 15;
    public static final int WATCHDOG_MAX_FALLOS        = 3;

    // ── Coordinación Bully / Mutex ────────────────────────────────────────────
    public static final int    PUERTO_JUE_1_BULLY      = 9082;
    public static final int    PUERTO_JUE_2_BULLY      = 9282;
    public static final int    PUERTO_JUE_1_MUTEX      = 9182;
    public static final int    PUERTO_JUE_2_MUTEX      = 9382;

    public static final int    TIMEOUT_BULLY_OK_MS     = 3_000;
    public static final int    TIMEOUT_BULLY_COORD_MS  = 5_000;
    public static final int    TIMEOUT_MUTEX_MS        = 10_000;
    public static final int    HEARTBEAT_COORD_MS      = 5_000;

    // ── Operaciones de coordinación (enrutadas a JUEGOS por el Proxy) ─────────
    public static final String BULLY_MSG               = "BULLY_MSG";
    public static final String MUTEX_REQUEST           = "MUTEX_REQUEST";
    public static final String MUTEX_GRANT             = "MUTEX_GRANT";
    public static final String MUTEX_RELEASE           = "MUTEX_RELEASE";

    // ── Membresía ─────────────────────────────────────────────────────────────
    public static final String MEMBRESIA_FILE          = "data/MEMBRESIA.txt";

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

    // ── Prueba de carga ───────────────────────────────────────────────────────
    // Nº de compradores sembrados (cliente1..clienteN). El generador de carga
    // asigna un usuario distinto por hilo para que las sesiones no se pisen.
    public static final int NUM_COMPRADORES = 50;

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
