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
 * PERSISTENCIA: GestorPersistencia garantiza escritura atómica Main + Copy.
 *
 * SEGURIDAD:
 *  - Contraseñas almacenadas con SHA-256.
 *  - Tokens generados con UUID (aleatorio criptográfico).
 *  - Ninguna operación privilegiada se ejecuta sin verificar el rol.
 */
public class svSesiones {

    private static final Logger       LOG          = Logger.getLogger(svSesiones.class.getName());
    private static final RelojLamport relojLamport = new RelojLamport();

    private final int                          puerto;
    private final GestorPersistencia<BDSesiones> gp;
    private final ExecutorService              pool   = Executors.newFixedThreadPool(Constantes.POOL_SIZE);
    private final Object                       lock   = new Object(); // monitor de acceso a BD

    // ── Constructor ───────────────────────────────────────────────────────────

    public svSesiones(int puerto) {
        this.puerto = puerto;
        this.gp     = new GestorPersistencia<>(
                Constantes.SES_MAIN, Constantes.SES_COPY, BDSesiones.class);

        // Sembrar solo si AMBOS archivos (Main y Copy) están ausentes o vacíos.
        // Evita que el Nodo 2 re-siembre usuarios cuando el Nodo 1 ya escribió.
        BDSesiones bd = gp.leer();
        if (archivoVacio(Constantes.SES_MAIN) && archivoVacio(Constantes.SES_COPY)
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
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        int nodo   = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int puerto = (nodo == 2) ? Constantes.PUERTO_SES_2 : Constantes.PUERTO_SES_1;
        GestorLog.configurar("svSesiones-" + nodo);

        svSesiones sv = new svSesiones(puerto);

        // ── Snapshot periódico: Main → Copy cada 30s (ambos nodos) ─────────
        // Nodo 1: primer snapshot a los 30s. Nodo 2: primer snapshot a los 45s.
        // El escalonamiento evita que ambos nodos escriban al mismo .snap.tmp
        // simultáneamente. Si Nodo 1 cae, Nodo 2 sigue actualizando la Copy.
        GestorSnapshot snap = new GestorSnapshot(
                Constantes.SES_MAIN, Constantes.SES_COPY, "svSesiones-" + nodo, 30);
        snap.start(nodo == 1 ? 30 : 45);

        // ── Registro dinámico en el Proxy ────────────────────────────────────
        // RegistradorProxy intenta contactar al Proxy con reintentos en un hilo
        // daemon, por lo que no bloquea el arranque del servidor.
        RegistradorProxy.registrarAsync("SESIONES", puerto, "SES-" + nodo);

        // ── Desregistrar del Proxy al apagarse ───────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> RegistradorProxy.desregistrar("SESIONES", puerto),
            "shutdown-sesiones-" + nodo
        ));

        sv.escuchar();
    }

    public void escuchar() {
        LOG.info("=== svSesiones iniciado en puerto " + puerto + " ===");
        try (ServerSocket server = new ServerSocket(puerto)) {
            server.setReuseAddress(true);
            while (true) {
                Socket cliente = server.accept();
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (IOException e) {
            LOG.severe("Error en svSesiones:" + puerto + " → " + e.getMessage());
        }
    }

    // ── Manejo de conexión ────────────────────────────────────────────────────

    private void manejarCliente(Socket socket) {
        try (socket;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            String linea = in.readLine();
            if (linea == null) return;

            MensajeProtocolo req = MensajeProtocolo.fromJson(linea);
            relojLamport.update(req.getLamportClock());

            MensajeProtocolo resp = procesar(req);
            resp.setLamportClock(relojLamport.tick());
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[SES] Timeout en cliente");
        } catch (IOException e) {
            LOG.warning("[SES] IO: " + e.getMessage());
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private MensajeProtocolo procesar(MensajeProtocolo req) {
        if (req == null) return MensajeProtocolo.error("?", "Mensaje inválido");

        // HEALTH_CHECK se logea en FINE para no inundar el log (~6 entradas/min por nodo)
        LOG.log(Constantes.HEALTH_CHECK.equals(req.getOperacion()) ? Level.FINE : Level.INFO,
                "[SES] op=" + req.getOperacion() + " rId=" + req.getRequestId());

        return switch (req.getOperacion()) {
            case Constantes.HEALTH_CHECK      -> healthCheck(req);
            case Constantes.LOGIN             -> login(req);
            case Constantes.LOGOUT            -> logout(req);
            case Constantes.VALIDAR_TOKEN     -> validarToken(req);
            case Constantes.REGISTRAR_USUARIO -> registrarUsuario(req);
            case Constantes.LISTAR_USUARIOS   -> listarUsuarios(req);
            case Constantes.CAMBIAR_PASS      -> cambiarPassword(req);
            default -> MensajeProtocolo.error(req.getRequestId(),
                    "Operación no soportada: " + req.getOperacion());
        };
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private MensajeProtocolo healthCheck(MensajeProtocolo req) {
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "svSesiones OK");
        resp.put("puerto", puerto);
        return resp;
    }

    /**
     * LOGIN: verifica usuario/contraseña y genera un token de sesión único.
     * SEGURIDAD: contraseña comparada contra su hash SHA-256.
     */
    private MensajeProtocolo login(MensajeProtocolo req) {
        String username = req.getString("username");
        String password = req.getString("password");

        if (username == null || password == null) {
            return MensajeProtocolo.error(req.getRequestId(), "Faltan credenciales");
        }

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Usuario usuario = bd.usuarios.stream()
                    .filter(u -> u.username.equals(username) && u.activo)
                    .findFirst().orElse(null);

            if (usuario == null || !Utils.verificarPassword(password, usuario.passwordHash)) {
                return MensajeProtocolo.error(req.getRequestId(), "Credenciales incorrectas");
            }

            // Invalidar sesión anterior si existe
            bd.sesiones.stream()
                    .filter(s -> s.username.equals(username) && s.activa)
                    .forEach(s -> s.activa = false);

            // Crear nueva sesión
            String  token = UUID.randomUUID().toString();
            Sesion  sesion = new Sesion(token, username, usuario.rol);
            bd.sesiones.add(sesion);

            try {
                gp.guardar(bd);  // Main + Copy
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Login exitoso. Bienvenido, " + username);
            resp.put("token",    token);
            resp.put("rol",      usuario.rol);
            resp.put("username", username);
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
                    .filter(s -> s.token.equals(token) && s.activa)
                    .findFirst();

            if (sesion.isEmpty()) {
                return MensajeProtocolo.error(req.getRequestId(), "Sesión no encontrada");
            }

            sesion.get().activa = false;
            try {
                gp.guardar(bd);
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
                    .filter(s -> s.token.equals(token) && s.activa)
                    .findFirst();

            if (sesion.isEmpty()) {
                return MensajeProtocolo.error(req.getRequestId(), "Token inválido o expirado");
            }

            Sesion s = sesion.get();
            // No persistimos ultimaActividad: evitar una escritura por cada validación
            // reduce drásticamente la contención sobre el archivo compartido bajo carga.
            // (La validación es una operación de lectura; no debe reescribir la BD.)

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Token válido");
            resp.put("username", s.username);
            resp.put("rol",      s.rol);
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
        if (!List.of(Constantes.ROL_COMPRADOR, Constantes.ROL_VENDEDOR, Constantes.ROL_ADMIN)
                .contains(nuevoRol)) {
            return MensajeProtocolo.error(req.getRequestId(), "Rol inválido: " + nuevoRol);
        }

        synchronized (lock) {
            BDSesiones bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            if (bd.usuarios.stream().anyMatch(u -> u.username.equals(nuevoUser))) {
                return MensajeProtocolo.error(req.getRequestId(), "El usuario ya existe");
            }

            bd.usuarios.add(new Usuario(nuevoUser, Utils.hashPassword(nuevaPass), nuevoRol));
            try {
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(),
                    "Usuario '" + nuevoUser + "' creado con rol " + nuevoRol);
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
            long sesActivas = bd.sesiones.stream().filter(s -> s.activa).count();
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
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(), "Contraseña actualizada");
        }
    }

    /** Retorna true si el archivo no existe o tiene tamaño 0. */
    private static boolean archivoVacio(String path) {
        File f = new File(path);
        return !f.exists() || f.length() == 0;
    }
}
