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
import java.util.stream.Collectors;

/**
 * svMensajeria – Servidor de Chat 1 a 1
 *
 * Puertos: Nodo 1 → 8083 | Nodo 2 → 8383
 * Uso: java ... svMensajeria [1|2]
 *
 * Operaciones soportadas:
 *  ENVIAR_MENSAJE, VER_MENSAJES, VER_CONVERSACION, HEALTH_CHECK
 *
 * FLUJO (Diagrama de Secuencia 2):
 *  Fase 1 – Envío: validar token emisor → guardar en buzón receptor.
 *  Fase 2 – Real-Time: el receptor hace polling con VER_MENSAJES y recoge
 *            los mensajes pendientes (mensajes no entregados).
 *  Fase 3 – Offline Recovery: cuando el usuario offline se conecta,
 *            VER_MENSAJES entrega todos los mensajes pendientes.
 *
 * CONCURRENCIA: synchronized(lock) protege lectura/escritura de la BD.
 * PERSISTENCIA: GestorPersistencia garantiza replicación Main → Copy.
 *
 * SEGURIDAD: todo envío y lectura requiere token válido de svSesiones.
 */
public class svMensajeria {

    private static final Logger LOG = Logger.getLogger(svMensajeria.class.getName());

    private final int                            nodo;
    private final int                            puerto;
    private final RelojLamport                   relojLamport;
    private final GestorPersistencia<BDMensajeria> gp;
    private final ReplicadorEstado<BDMensajeria> replicador;
    private final CacheIdempotencia               idempotencia = new CacheIdempotencia();
    private final ExecutorService                pool = Executors.newFixedThreadPool(Constantes.POOL_SIZE);
    private final Object                         lock = new Object();

    // ── Constructor ───────────────────────────────────────────────────────────

    public svMensajeria(int puerto) {
        this(puerto == Constantes.PUERTO_MSG_2 ? 2 : 1, puerto);
    }

    public svMensajeria(int nodo, int puerto) {
        this.nodo = nodo;
        this.puerto = puerto;
        this.relojLamport = new RelojLamport("MSG-" + nodo);
        this.gp     = new GestorPersistencia<>(
                RutasDatos.main("mensajeria", nodo), RutasDatos.copy("mensajeria", nodo),
                BDMensajeria.class);
        gp.inicializarSiVacio(new BDMensajeria());
        int otroNodo = nodo == 1 ? 2 : 1;
        int localRepl = nodo == 1 ? Constantes.PUERTO_MSG_1_REPL : Constantes.PUERTO_MSG_2_REPL;
        int peerRepl = nodo == 1 ? Constantes.PUERTO_MSG_2_REPL : Constantes.PUERTO_MSG_1_REPL;
        this.replicador = new ReplicadorEstado<>("MENSAJERIA", nodo, localRepl,
                new Endpoint(Configuracion.hostServicio("mensajeria", otroNodo), peerRepl),
                RutasDatos.version("mensajeria", nodo), BDMensajeria.class,
                () -> { synchronized (lock) { return gp.leer(); } },
                estado -> { synchronized (lock) { guardarReplica(estado); } }, relojLamport);
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        int nodo   = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int puerto = (nodo == 2) ? Constantes.PUERTO_MSG_2 : Constantes.PUERTO_MSG_1;
        GestorLog.configurar("svMensajeria-" + nodo);

        svMensajeria sv = new svMensajeria(nodo, puerto);
        sv.replicador.start();

        // ── Snapshot periódico: Main → Copy cada 30s (ambos nodos) ─────────
        GestorSnapshot snap = new GestorSnapshot(
                RutasDatos.main("mensajeria", nodo), RutasDatos.copy("mensajeria", nodo),
                "svMensajeria-" + nodo, 30);
        snap.start(nodo == 1 ? 30 : 45);

        // ── Registro dinámico en el Proxy ────────────────────────────────────
        int puertoRepl = nodo == 1 ? Constantes.PUERTO_MSG_1_REPL : Constantes.PUERTO_MSG_2_REPL;
        RegistradorProxy.registrarAsync("MENSAJERIA", nodo, puerto, "MSG-" + nodo,
                0, 0, puertoRepl);

        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> { sv.replicador.stop(); RegistradorProxy.desregistrar("MENSAJERIA", puerto); },
            "shutdown-mensajeria-" + nodo
        ));

        sv.escuchar();
    }

    public void escuchar() {
        LOG.info("=== svMensajeria iniciado en puerto " + puerto + " ===");
        try (ServerSocket server = Transporte.servidor(puerto)) {
            while (true) {
                Socket cliente = server.accept();
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (IOException e) {
            LOG.severe("Error en svMensajeria:" + puerto + " → " + e.getMessage());
        }
    }

    // ── Manejo de conexión ────────────────────────────────────────────────────

    private void manejarCliente(Socket socket) {
        try (socket;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String linea = LineaJson.leer(in, Configuracion.maxMessageBytes());
            if (linea == null) return;

            MensajeProtocolo req = MensajeProtocolo.fromJson(linea);
            String error = SeguridadMensajes.validarSolicitud(req);
            if (error != null) {
                out.println(MensajeProtocolo.error(req == null ? "?" : req.getRequestId(),
                        "SECURITY_ERROR", error).toJson());
                return;
            }
            relojLamport.update(req.getLamportClock());

            MensajeProtocolo resp = idempotencia.ejecutar(req.getRequestId(), () -> procesar(req));
            resp.setLamportClock(relojLamport.tick());
            resp.setEmisor("MSG-" + nodo);
            resp.setReceptor(req.getEmisor());
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[MSG] Timeout en cliente");
        } catch (IOException | RuntimeException e) {
            LOG.warning("[MSG] IO: " + e.getMessage());
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private MensajeProtocolo procesar(MensajeProtocolo req) {
        if (req == null) return MensajeProtocolo.error("?", "INVALID_REQUEST", "Mensaje inválido");
        if (Utils.esOperacionEscritura(req.getOperacion())
                && nodo != Configuracion.writerNodeId("MENSAJERIA")) {
            return MensajeProtocolo.error(req.getRequestId(), "NOT_PRIMARY",
                    "Nodo secundario de mensajeria; escritor=" + Configuracion.writerNodeId("MENSAJERIA"));
        }
        boolean esHC = Constantes.HEALTH_CHECK.equals(req.getOperacion());
        LOG.log(esHC ? Level.FINE : Level.INFO,
                "[MSG] op=" + req.getOperacion() + " rId=" + req.getRequestId());
        if (!esHC) LOG.info("[LAMPORT] t=" + relojLamport.get() + " op=" + req.getOperacion());

        return switch (req.getOperacion()) {
            case Constantes.HEALTH_CHECK    -> healthCheck(req);
            case Constantes.ESTADO_REPLICACION -> estadoReplicacion(req);
            case Constantes.ENVIAR_MENSAJE  -> enviarMensaje(req);
            case Constantes.VER_MENSAJES    -> verMensajes(req);
            case Constantes.VER_CONVERSACION -> verConversacion(req);
            default -> MensajeProtocolo.error(req.getRequestId(), "UNKNOWN_OPERATION",
                    "Operación no soportada: " + req.getOperacion());
        };
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private MensajeProtocolo healthCheck(MensajeProtocolo req) {
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "svMensajeria OK");
        resp.put("puerto", puerto);
        return resp;
    }

    private MensajeProtocolo estadoReplicacion(MensajeProtocolo req) {
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Replica de mensajeria");
        resp.put("nodo", nodo).put("version", replicador.getVersionActual())
                .put("peer", replicador.getPeer().toString());
        return resp;
    }

    /**
     * ENVIAR_MENSAJE – Fase 1 del flujo de mensajería.
     *
     * Valida el token del emisor, guarda el mensaje en la BD del receptor.
     * Si el receptor está offline, el mensaje queda en el buzón (offline storage).
     * REGIÓN CRÍTICA: synchronized para evitar mensajes duplicados o perdidos.
     */
    private MensajeProtocolo enviarMensaje(MensajeProtocolo req) {
        // Validar token del emisor (Seguridad de Canales)
        ValidadorToken.ResultadoValidacion auth = ValidadorToken.validar(req.getToken());
        if (!auth.valido()) {
            return MensajeProtocolo.error(req.getRequestId(), "AUTHENTICATION_FAILED",
                    "Token inválido: " + auth.mensaje());
        }

        String receptor  = req.getString("receptor");
        String contenido = req.getString("contenido");

        if (receptor == null || contenido == null || contenido.isBlank()) {
            return MensajeProtocolo.error(req.getRequestId(), "BUSINESS_INVALID_REQUEST",
                    "Faltan campos: receptor, contenido");
        }
        if (receptor.equals(auth.username())) {
            return MensajeProtocolo.error(req.getRequestId(), "BUSINESS_INVALID_REQUEST",
                    "No puedes enviarte mensajes a ti mismo");
        }

        // ── REGIÓN CRÍTICA: Persistencia de Mensaje Offline ──
        synchronized (lock) {
            BDMensajeria bd = gp.leer();
            if (bd == null) bd = new BDMensajeria();

            Mensaje msg = new Mensaje(UUID.randomUUID().toString(),
                    auth.username(), receptor, contenido);
            // Guardar el reloj de Lamport del momento de envío para ordenamiento causal
            msg.lamportClock = relojLamport.tick();
            msg.nodoEmisor = nodo;
            bd.mensajes.add(msg);

            try {
                guardarReplicado(bd, req.getRequestId());
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(),
                        "PERSISTENCE_ERROR", "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Mensaje enviado a " + receptor);
            resp.put("mensajeId", msg.id);
            resp.put("timestamp", msg.timestamp);
            return resp;
        }
    }

    /**
     * VER_MENSAJES – Fase 2 y 3: entrega de mensajes pendientes.
     *
     * Retorna todos los mensajes no entregados dirigidos al usuario autenticado
     * y los marca como entregados (ACK implícito).
     * Implementa la recuperación offline: cuando el usuario se conecta,
     * recibe todos sus mensajes pendientes.
     */
    private MensajeProtocolo verMensajes(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = ValidadorToken.validar(req.getToken());
        if (!auth.valido()) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "AUTHENTICATION_FAILED", auth.mensaje());
        }

        synchronized (lock) {
            BDMensajeria bd = gp.leer();
            if (bd == null) bd = new BDMensajeria();

            List<Mensaje> pendientes = bd.mensajes.stream()
                    .filter(m -> m.receptor.equals(auth.username()) && !m.entregado)
                    .collect(Collectors.toList());

            // Marcar como entregados (ACK)
            boolean cambios = !pendientes.isEmpty();
            pendientes.forEach(m -> { m.entregado = true; m.leido = true; });

            if (cambios) {
                try {
                    guardarReplicado(bd, req.getRequestId());
                } catch (IOException e) {
                    return MensajeProtocolo.error(req.getRequestId(),
                            "PERSISTENCE_ERROR", "No se pudo persistir la entrega");
                }
            }

            List<Map<String, Object>> lista = pendientes.stream()
                    .map(m -> {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("de",        m.emisor);
                        info.put("contenido", m.contenido);
                        info.put("fecha",     new java.util.Date(m.timestamp).toString());
                        return info;
                    })
                    .collect(Collectors.toList());

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Mensajes pendientes: " + lista.size());
            resp.put("mensajes", lista);
            return resp;
        }
    }

    /**
     * VER_CONVERSACION: historial completo entre el usuario autenticado y otro usuario.
     */
    private MensajeProtocolo verConversacion(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = ValidadorToken.validar(req.getToken());
        if (!auth.valido()) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "AUTHENTICATION_FAILED", auth.mensaje());
        }

        String conUsuario = req.getString("conUsuario");
        if (conUsuario == null) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "BUSINESS_INVALID_REQUEST", "Falta campo: conUsuario");
        }

        synchronized (lock) {
            BDMensajeria bd = gp.leer();
            if (bd == null) bd = new BDMensajeria();

            String yo = auth.username();
            List<Map<String, Object>> conversacion = bd.mensajes.stream()
                    .filter(m -> (m.emisor.equals(yo)  && m.receptor.equals(conUsuario)) ||
                                 (m.emisor.equals(conUsuario) && m.receptor.equals(yo)))
                    // Ordenar por Lamport para orden causal correcto
                    // (fallback a timestamp si lamportClock == 0 por mensajes anteriores)
                    .sorted(OrdenMensajes.COMPARATOR)
                    .map(m -> {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("de",          m.emisor);
                        info.put("para",        m.receptor);
                        info.put("contenido",   m.contenido);
                        info.put("fecha",       new java.util.Date(m.timestamp).toString());
                        info.put("lamportClock", m.lamportClock);
                        info.put("entregado",   m.entregado);
                        return info;
                    })
                    .collect(Collectors.toList());

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Conversación con " + conUsuario + ": " + conversacion.size() + " mensajes");
            resp.put("conversacion", conversacion);
            return resp;
        }
    }
    private void guardarReplicado(BDMensajeria bd, String requestId) throws IOException {
        if (nodo != Configuracion.writerNodeId("MENSAJERIA")) {
            throw new IOException("Escritura rechazada en nodo secundario de mensajeria");
        }
        gp.guardar(bd);
        ReplicadorEstado.Resultado resultado = replicador.registrarCambioLocal(bd, requestId);
        LOG.info("[REPL] requestId=" + requestId + " version=" + resultado.version()
                + " confirmada=" + resultado.confirmada());
    }

    private void guardarReplica(BDMensajeria estado) {
        try { gp.guardar(estado); }
        catch (IOException e) { throw new IllegalStateException("No se pudo aplicar replica", e); }
    }
}
