package com.steam.servidores;

import com.steam.common.*;
import com.steam.common.RegistradorProxy;
import com.steam.models.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * svSesiones – Servidor de Gestión de Sesiones y Autenticación
 *
 * Puertos: Nodo 1 → 8081 | Nodo 2 → 8181
 * Uso: java ... svSesiones [1|2]
 *
 * Operaciones soportadas:
 *  LOGIN, LOGOUT, VALIDAR_TOKEN, REGISTRAR_USUARIO, LISTAR_USUARIOS,
 *  CAMBIAR_PASS, HEALTH_CHECK
 *
 * CONCURRENCIA: un ExecutorService atiende múltiples clientes en paralelo.
 * El acceso a los datos (BDSesiones) está protegido con synchronized(lock).
 *
 * PERSISTENCIA: Main se escribe atomicamente; Copy es un respaldo periodico.
 *
 * SEGURIDAD:
 *  - Contraseñas almacenadas con PBKDF2-HMAC-SHA256 (SHA-256 legado se migra al cambiarla).
 *  - Tokens generados con UUID (aleatorio criptográfico).
 *  - Ninguna operación privilegiada se ejecuta sin verificar el rol.
 */
public class svSesiones {

    private static final Logger LOG = Logger.getLogger(svSesiones.class.getName());

    private final int                          nodo;
    private final int                          puerto;
    private final RelojLamport                 relojLamport;
    private final GestorPersistencia<BDSesiones> gp;
    private final ReplicadorEstado<BDSesiones> replicador;
    private final CacheIdempotencia             idempotencia = new CacheIdempotencia();
    private final ExecutorService              pool   = Ejecutores.acotado("sesiones-worker", Constantes.POOL_SIZE, false);
    private final Object                       lock   = new Object(); // monitor de acceso a BD
    private final Map<String, IntentosLogin>    intentosLogin = new HashMap<>();

    private static final class IntentosLogin {
        int fallos;
        long inicioVentana;
        long bloqueadoHasta;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public svSesiones(int puerto) {
        this(puerto == Constantes.PUERTO_SES_2 ? 2 : 1, puerto);
    }

    public svSesiones(int nodo, int puerto) {
        this.nodo = nodo;
        this.puerto = puerto;
        this.relojLamport = new RelojLamport("SES-" + nodo);
        String main = RutasDatos.main("sesiones", nodo);
        String copy = RutasDatos.copy("sesiones", nodo);
        this.gp     = new GestorPersistencia<>(
                main, copy, BDSesiones.class);

        // Sembrar solo si AMBOS archivos (Main y Copy) están ausentes o vacíos.
        // Evita que el Nodo 2 re-siembre usuarios cuando el Nodo 1 ya escribió.
        BDSesiones bd = gp.leer();
        if (archivoVacio(main) && archivoVacio(copy)
                && (bd == null || bd.usuarios.isEmpty())) {
            if (bd == null) bd = new BDSesiones();
            bd.usuarios.add(new Usuario("admin",     Utils.hashPassword("admin123"), Constantes.ROL_ADMIN));
            bd.usuarios.add(new Usuario("vendedor1", Utils.hashPassword("pass123"),  Constantes.ROL_VENDEDOR));
            // Compradores cliente1..clienteN (N=NUM_COMPRADORES) para la prueba de carga:
            // cada hilo del generador usa un usuario distinto y así las sesiones no se pisan.
            for (int i = 1; i <= Constantes.NUM_COMPRADORES; i++) {
                bd.usuarios.add(new Usuario("cliente" + i, Utils.hashPassword("pass123"), Constantes.ROL_COMPRADOR));
            }
            try { gp.guardar(bd); } catch (IOException e) { LOG.severe("No se pudo sembrar BD: " + e.getMessage()); }
        }
        int otroNodo = nodo == 1 ? 2 : 1;
        int localRepl = nodo == 1 ? Constantes.PUERTO_SES_1_REPL : Constantes.PUERTO_SES_2_REPL;
        int peerRepl = nodo == 1 ? Constantes.PUERTO_SES_2_REPL : Constantes.PUERTO_SES_1_REPL;
        this.replicador = new ReplicadorEstado<>("SESIONES", nodo, localRepl,
                new Endpoint(Configuracion.hostServicio("sesiones", otroNodo), peerRepl),
                RutasDatos.version("sesiones", nodo), BDSesiones.class,
                () -> { synchronized (lock) { return gp.leer(); } },
                estado -> { synchronized (lock) { guardarReplica(estado); } }, relojLamport);
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        Configuracion.validarArranque();
        int nodo   = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int puerto = (nodo == 2) ? Constantes.PUERTO_SES_2 : Constantes.PUERTO_SES_1;
        GestorLog.configurar("svSesiones-" + nodo);

        svSesiones sv = new svSesiones(nodo, puerto);
        sv.replicador.start();

        // ── Snapshot periódico: Main → Copy cada 30s (ambos nodos) ─────────
        // Nodo 1: primer snapshot a los 30s. Nodo 2: primer snapshot a los 45s.
        // El escalonamiento evita que ambos nodos escriban al mismo .snap.tmp
        // simultáneamente. Si Nodo 1 cae, Nodo 2 sigue actualizando la Copy.
        GestorSnapshot snap = new GestorSnapshot(
                RutasDatos.main("sesiones", nodo), RutasDatos.copy("sesiones", nodo),
                "svSesiones-" + nodo, 30);
        snap.start(nodo == 1 ? 30 : 45);

        // ── Registro dinámico en el Proxy ────────────────────────────────────
        // RegistradorProxy intenta contactar al Proxy con reintentos en un hilo
        // daemon, por lo que no bloquea el arranque del servidor.
        int puertoRepl = nodo == 1 ? Constantes.PUERTO_SES_1_REPL : Constantes.PUERTO_SES_2_REPL;
        RegistradorProxy.registrarAsync("SESIONES", nodo, puerto, "SES-" + nodo,
                0, 0, puertoRepl);

        // ── Desregistrar del Proxy al apagarse ───────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> { snap.stop(); sv.replicador.stop();
                    RegistradorProxy.desregistrar("SESIONES", puerto); },
            "shutdown-sesiones-" + nodo
        ));

        sv.escuchar();
    }

    public void escuchar() {
        LOG.info("=== svSesiones iniciado en puerto " + puerto + " ===");
        try (ServerSocket server = Transporte.servidor(puerto)) {
            while (true) {
                Socket cliente = Transporte.aceptar(server);
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (IOException e) {
            LOG.severe("Error en svSesiones:" + puerto + " → " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Manejo de conexión ────────────────────────────────────────────────────

    private void manejarCliente(Socket socket) {
        try (socket;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String linea;
            try { linea = LineaJson.leer(in, Configuracion.maxMessageBytes()); }
            catch (LineaJson.MensajeDemasiadoGrandeException e) {
                out.println(MensajeProtocolo.error("?", "MESSAGE_TOO_LARGE", e.getMessage()).toJson());
                return;
            }
            if (linea == null) return;

            MensajeProtocolo req;
            try { req = MensajeProtocolo.fromJson(linea); }
            catch (RuntimeException e) {
                out.println(MensajeProtocolo.error("?", "JSON_MALFORMADO",
                        "Mensaje JSON mal formado").toJson());
                return;
            }
            String error = SeguridadMensajes.validarSolicitud(req);
            if (error != null) {
                out.println(MensajeProtocolo.error(req == null ? "?" : req.getRequestId(),
                        "SECURITY_ERROR", error).toJson());
                return;
            }
            relojLamport.update(req.getLamportClock());

            MensajeProtocolo resp = idempotencia.ejecutar(req, () -> procesar(req));
            resp.setLamportClock(relojLamport.tick());
            resp.setEmisor("SES-" + nodo);
            resp.setReceptor(req.getEmisor());
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[SES] Timeout en cliente");
        } catch (IOException | RuntimeException e) {
            LOG.warning("[SES] IO: " + e.getMessage());
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private MensajeProtocolo procesar(MensajeProtocolo req) {
        if (req == null) return MensajeProtocolo.error("?", "INVALID_REQUEST", "Mensaje inválido");
        if (Utils.esOperacionEscritura(req.getOperacion())) {
            if (nodo != Configuracion.writerNodeId("SESIONES")) {
                return MensajeProtocolo.error(req.getRequestId(), "NOT_PRIMARY",
                        "Nodo secundario de sesiones; escritor=" + Configuracion.writerNodeId("SESIONES"));
            }
            if (!replicador.isListoParaEscrituras()) {
                return MensajeProtocolo.error(req.getRequestId(), "SERVICE_UNAVAILABLE",
                        "Writer de sesiones reconciliando su estado");
            }
        }

        // HEALTH_CHECK se logea en FINE para no inundar el log.
        LOG.log(Constantes.HEALTH_CHECK.equals(req.getOperacion()) ? Level.FINE : Level.INFO,
                "[SES] op=" + req.getOperacion() + " rId=" + req.getRequestId());

        return switch (req.getOperacion()) {
            case Constantes.HEALTH_CHECK      -> healthCheck(req);
            case Constantes.ESTADO_REPLICACION -> estadoReplicacion(req);
            case Constantes.LOGIN             -> login(req);
            case Constantes.LOGOUT            -> logout(req);
            case Constantes.VALIDAR_TOKEN     -> validarToken(req);
            case Constantes.VALIDAR_USUARIO_INTERNO -> validarUsuarioInterno(req);
            case Constantes.REGISTRAR_USUARIO -> registrarUsuario(req);
            case Constantes.LISTAR_USUARIOS   -> listarUsuarios(req);
            case Constantes.CAMBIAR_PASS      -> cambiarPassword(req);
            default -> MensajeProtocolo.error(req.getRequestId(), "UNKNOWN_OPERATION",
                    "Operación no soportada: " + req.getOperacion());
        };
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private MensajeProtocolo healthCheck(MensajeProtocolo req) {
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "svSesiones OK");
        resp.put("puerto", puerto);
        resp.put("writerReady", replicador.isListoParaEscrituras());
        return resp;
    }

    private MensajeProtocolo estadoReplicacion(MensajeProtocolo req) {
        if (!SeguridadMensajes.validarControl(req)) {
            return MensajeProtocolo.error(req.getRequestId(), "AUTHORIZATION_DENIED",
                    "Firma de control requerida");
        }
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Replica de sesiones");
        resp.put("nodo", nodo).put("version", replicador.getVersionActual())
                .put("peer", replicador.getPeer().toString());
        return resp;
    }

    /**
     * LOGIN: verifica usuario/contraseña y genera un token de sesión único.
     * SEGURIDAD: contraseña comparada contra su hash PBKDF2.
     */
    private MensajeProtocolo login(MensajeProtocolo req) {
        String username = req.getString("username");
        String password = req.getString("password");

        if (!Utils.usernameValido(username) || !Utils.passwordValida(password)) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "BUSINESS_INVALID_REQUEST", "Credenciales fuera de formato");
        }

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(),
                    "SERVICE_UNAVAILABLE", "BD no disponible");
            if (loginBloqueado(username)) {
                return MensajeProtocolo.error(req.getRequestId(), "RATE_LIMITED",
                        "Demasiados intentos de acceso; espere antes de reintentar");
            }

            Usuario usuario = bd.usuarios.stream()
                    .filter(u -> u.username.equals(username) && u.activo)
                    .findFirst().orElse(null);

            if (usuario == null || !Utils.verificarPassword(password, usuario.passwordHash)) {
                registrarFalloLogin(username);
                return MensajeProtocolo.error(req.getRequestId(),
                        "AUTHENTICATION_FAILED", "Credenciales incorrectas");
            }
            intentosLogin.remove(username);

            // Invalidar sesión anterior si existe
            bd.sesiones.stream()
                    .filter(s -> s.username.equals(username) && s.activa)
                    .forEach(s -> s.activa = false);
            long ahora = System.currentTimeMillis();
            long ttl = Configuracion.tokenTtlMs();
            bd.sesiones.removeIf(s -> !s.activa && ahora - s.creadoEn > ttl);
            bd.sesiones.removeIf(s -> s.activa && ahora - s.creadoEn > ttl);

            // Crear nueva sesión
            String  token = UUID.randomUUID().toString();
            Sesion  sesion = new Sesion(token, username, usuario.rol);
            bd.sesiones.add(sesion);

            ReplicadorEstado.Resultado repl;
            try {
                repl = guardarReplicado(bd, req.getRequestId());
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(),
                        "PERSISTENCE_ERROR", "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Login exitoso. Bienvenido, " + username);
            resp.put("token",    token);
            resp.put("rol",      usuario.rol);
            resp.put("username", username);
            resp.put("replicaConfirmada", repl.confirmada());
            return resp;
        }
    }

    /** LOGOUT: invalida el token de sesión activo. */
    private MensajeProtocolo logout(MensajeProtocolo req) {
        String token = req.getToken();
        if (token == null) return MensajeProtocolo.error(req.getRequestId(), "Token requerido");

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Optional<Sesion> sesion = bd.sesiones.stream()
                    .filter(s -> s.coincideToken(token) && s.vigente())
                    .findFirst();

            if (sesion.isEmpty()) {
                return MensajeProtocolo.error(req.getRequestId(), "Sesión no encontrada");
            }

            sesion.get().activa = false;
            try {
                guardarReplicado(bd, req.getRequestId());
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(), "Sesión cerrada");
        }
    }

    /**
     * VALIDAR_TOKEN: usado por svJuegos y svMensajeria para verificar
     * que el token es válido antes de procesar cualquier transacción.
     * SEGURIDAD: ninguna operación sensible ocurre sin pasar por aquí.
     */
    private MensajeProtocolo validarToken(MensajeProtocolo req) {
        String token = req.getToken();
        if (token == null) return MensajeProtocolo.error(req.getRequestId(), "Token nulo");

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Optional<Sesion> sesion = bd.sesiones.stream()
                    .filter(s -> s.coincideToken(token) && s.vigente())
                    .findFirst();

            if (sesion.isEmpty()) {
                return MensajeProtocolo.error(req.getRequestId(), "Token inválido o expirado");
            }

            Sesion s = sesion.get();
            // El token usa vida absoluta: validar no renueva su vencimiento ni escribe la BD.

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Token válido");
            resp.put("username", s.username);
            resp.put("rol",      s.rol);
            return resp;
        }
    }

    private MensajeProtocolo validarUsuarioInterno(MensajeProtocolo req) {
        if (!SeguridadMensajes.validarControl(req)) {
            return MensajeProtocolo.error(req.getRequestId(), "AUTHORIZATION_DENIED",
                    "Firma de control requerida");
        }
        String username = req.getString("username");
        if (!Utils.usernameValido(username)) {
            return MensajeProtocolo.error(req.getRequestId(), "BUSINESS_INVALID_REQUEST",
                    "Username invalido");
        }
        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(),
                    "SERVICE_UNAVAILABLE", "BD no disponible");
            boolean existe = bd.usuarios.stream()
                    .anyMatch(u -> u.activo && u.username.equals(username));
            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Usuario validado");
            resp.put("existe", existe);
            return resp;
        }
    }

    /**
     * REGISTRAR_USUARIO: solo ADMINISTRADOR puede crear cuentas.
     * SEGURIDAD: rol verificado antes de ejecutar.
     */
    private MensajeProtocolo registrarUsuario(MensajeProtocolo req) {
        // Verificar que quien llama es ADMINISTRADOR
        MensajeProtocolo valResp = validarToken(req);
        if (!valResp.isOk()) return valResp;
        if (!Constantes.ROL_ADMIN.equals(valResp.getString("rol"))) {
            return MensajeProtocolo.error(req.getRequestId(), "Acceso denegado: se requiere rol ADMINISTRADOR");
        }

        String nuevoUser = req.getString("nuevoUsername");
        String nuevaPass = req.getString("nuevaPassword");
        String nuevoRol  = req.getString("nuevoRol");

        if (nuevoUser == null || nuevaPass == null || nuevoRol == null) {
            return MensajeProtocolo.error(req.getRequestId(), "Faltan campos: nuevoUsername, nuevaPassword, nuevoRol");
        }
        String usernameNormalizado = nuevoUser.trim();
        if (!Utils.usernameValido(usernameNormalizado) || !Utils.passwordValida(nuevaPass)) {
            return MensajeProtocolo.error(req.getRequestId(), "BUSINESS_INVALID_REQUEST",
                    "Username o password fuera de formato");
        }
        if (!List.of(Constantes.ROL_COMPRADOR, Constantes.ROL_VENDEDOR, Constantes.ROL_ADMIN)
                .contains(nuevoRol)) {
            return MensajeProtocolo.error(req.getRequestId(), "Rol inválido: " + nuevoRol);
        }

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            if (bd.usuarios.stream().anyMatch(u -> u.username.equals(usernameNormalizado))) {
                return MensajeProtocolo.error(req.getRequestId(), "El usuario ya existe");
            }

            bd.usuarios.add(new Usuario(usernameNormalizado, Utils.hashPassword(nuevaPass), nuevoRol));
            try {
                guardarReplicado(bd, req.getRequestId());
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(),
                    "Usuario '" + usernameNormalizado + "' creado con rol " + nuevoRol);
        }
    }

    /** LISTAR_USUARIOS: solo ADMINISTRADOR. */
    private MensajeProtocolo listarUsuarios(MensajeProtocolo req) {
        MensajeProtocolo valResp = validarToken(req);
        if (!valResp.isOk()) return valResp;
        if (!Constantes.ROL_ADMIN.equals(valResp.getString("rol"))) {
            return MensajeProtocolo.error(req.getRequestId(), "Acceso denegado");
        }

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            List<Map<String, Object>> lista = new ArrayList<>();
            for (Usuario u : bd.usuarios) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("username", u.username);
                info.put("rol",      u.rol);
                info.put("activo",   u.activo);
                lista.add(info);
            }
            long sesActivas = bd.sesiones.stream().filter(Sesion::vigente).count();
            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Total usuarios: " + bd.usuarios.size());
            resp.put("usuarios",         lista);
            resp.put("sesionesActivas",  sesActivas);
            return resp;
        }
    }

    /** CAMBIAR_PASS: cualquier usuario puede cambiar su propia contraseña. */
    private MensajeProtocolo cambiarPassword(MensajeProtocolo req) {
        MensajeProtocolo valResp = validarToken(req);
        if (!valResp.isOk()) return valResp;

        String username   = valResp.getString("username");
        String passActual = req.getString("passActual");
        String passNueva  = req.getString("passNueva");

        if (passActual == null || passNueva == null) {
            return MensajeProtocolo.error(req.getRequestId(), "Faltan campos: passActual, passNueva");
        }
        if (!Utils.passwordValida(passNueva)) {
            return MensajeProtocolo.error(req.getRequestId(), "BUSINESS_INVALID_REQUEST",
                    "La password debe tener entre 6 y 128 caracteres");
        }

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Optional<Usuario> optUser = bd.usuarios.stream()
                    .filter(u -> u.username.equals(username)).findFirst();

            if (optUser.isEmpty() || !Utils.verificarPassword(passActual, optUser.get().passwordHash)) {
                return MensajeProtocolo.error(req.getRequestId(), "Contraseña actual incorrecta");
            }

            optUser.get().passwordHash = Utils.hashPassword(passNueva);
            try {
                guardarReplicado(bd, req.getRequestId());
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(), "Contraseña actualizada");
        }
    }

    private ReplicadorEstado.Resultado guardarReplicado(BDSesiones bd, String requestId) throws IOException {
        ReplicadorEstado.Resultado resultado;
        try { resultado = replicador.registrarCambioLocal(bd, requestId); }
        catch (IllegalStateException e) { throw new IOException(e.getMessage(), e); }
        LOG.info("[REPL] requestId=" + requestId + " version=" + resultado.version()
                + " confirmada=" + resultado.confirmada());
        return resultado;
    }

    private void guardarReplica(BDSesiones estado) {
        try { gp.guardar(estado); }
        catch (IOException e) { throw new IllegalStateException("No se pudo aplicar replica", e); }
    }

    private static boolean archivoVacio(String path) {
        File f = new File(path);
        return !f.exists() || f.length() == 0;
    }

    private boolean loginBloqueado(String username) {
        IntentosLogin estado = intentosLogin.get(username);
        if (estado == null) return false;
        long ahora = System.currentTimeMillis();
        if (estado.bloqueadoHasta > ahora) return true;
        long ventana = Configuracion.getLong("steam.login.window.ms", 60_000L);
        if (ahora - estado.inicioVentana > ventana) intentosLogin.remove(username);
        return false;
    }

    private void registrarFalloLogin(String username) {
        long ahora = System.currentTimeMillis();
        long ventana = Configuracion.getLong("steam.login.window.ms", 60_000L);
        int maximo = Configuracion.getInt("steam.login.max.failures", 5);
        IntentosLogin estado = intentosLogin.computeIfAbsent(username, ignored -> new IntentosLogin());
        if (estado.inicioVentana == 0L || ahora - estado.inicioVentana > ventana) {
            estado.inicioVentana = ahora;
            estado.fallos = 0;
        }
        estado.fallos++;
        if (estado.fallos >= maximo) {
            estado.bloqueadoHasta = ahora
                    + Configuracion.getLong("steam.login.lockout.ms", 60_000L);
        }
    }
}
