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

    private static final Logger       LOG          = Logger.getLogger(svMensajeria.class.getName());
    private static final RelojLamport relojLamport = new RelojLamport();

    private final int                            puerto;
    private final GestorPersistencia<BDMensajeria> gp;
    private final ExecutorService                pool = Executors.newFixedThreadPool(Constantes.POOL_SIZE);
    private final Object                         lock = new Object();

    // ── Constructor ───────────────────────────────────────────────────────────

    public svMensajeria(int puerto) {
        this.puerto = puerto;
        this.gp     = new GestorPersistencia<>(
                Constantes.MSG_MAIN, Constantes.MSG_COPY, BDMensajeria.class);
        gp.inicializarSiVacio(new BDMensajeria());
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        int nodo   = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int puerto = (nodo == 2) ? Constantes.PUERTO_MSG_2 : Constantes.PUERTO_MSG_1;
        GestorLog.configurar("svMensajeria-" + nodo);

        svMensajeria sv = new svMensajeria(puerto);

        // ── Snapshot periódico: Main → Copy cada 30s (ambos nodos) ─────────
        GestorSnapshot snap = new GestorSnapshot(
                Constantes.MSG_MAIN, Constantes.MSG_COPY, "svMensajeria-" + nodo, 30);
        snap.start(nodo == 1 ? 30 : 45);

        // ── Registro dinámico en el Proxy ────────────────────────────────────
        RegistradorProxy.registrarAsync("MENSAJERIA", puerto, "MSG-" + nodo);

        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> RegistradorProxy.desregistrar("MENSAJERIA", puerto),
            "shutdown-mensajeria-" + nodo
        ));

        sv.escuchar();
    }

    public void escuchar() {
        LOG.info("=== svMensajeria iniciado en puerto " + puerto + " ===");
        try (ServerSocket server = new ServerSocket(puerto)) {
            server.setReuseAddress(true);
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

            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            String linea = in.readLine();
            if (linea == null) return;

            MensajeProtocolo req = MensajeProtocolo.fromJson(linea);
            relojLamport.update(req.getLamportClock());

            MensajeProtocolo resp = procesar(req);
            resp.setLamportClock(relojLamport.tick());
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[MSG] Timeout en cliente");
        } catch (IOException e) {
            LOG.warning("[MSG] IO: " + e.getMessage());
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private MensajeProtocolo procesar(MensajeProtocolo req) {
        if (req == null) return MensajeProtocolo.error("?", "Mensaje inválido");
        boolean esHC = Constantes.HEALTH_CHECK.equals(req.getOperacion());
        LOG.log(esHC ? Level.FINE : Level.INFO,
                "[MSG] op=" + req.getOperacion() + " rId=" + req.getRequestId());
        if (!esHC) LOG.info("[LAMPORT] t=" + relojLamport.get() + " op=" + req.getOperacion());

        return switch (req.getOperacion()) {
            case Constantes.HEALTH_CHECK    -> healthCheck(req);
            case Constantes.ENVIAR_MENSAJE  -> enviarMensaje(req);
            case Constantes.VER_MENSAJES    -> verMensajes(req);
            case Constantes.VER_CONVERSACION -> verConversacion(req);
            default -> MensajeProtocolo.error(req.getRequestId(),
                    "Operación no soportada: " + req.getOperacion());
        };
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private MensajeProtocolo healthCheck(MensajeProtocolo req) {
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "svMensajeria OK");
        resp.put("puerto", puerto);
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
            return MensajeProtocolo.error(req.getRequestId(),
                    "Token inválido: " + auth.mensaje());
        }

        String receptor  = req.getString("receptor");
        String contenido = req.getString("contenido");

        if (receptor == null || contenido == null || contenido.isBlank()) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "Faltan campos: receptor, contenido");
        }
        if (receptor.equals(auth.username())) {
            return MensajeProtocolo.error(req.getRequestId(),
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
            bd.mensajes.add(msg);

            try {
                gp.guardar(bd);  // Main + Copy (Alta Disponibilidad)
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
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
            return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());
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
                    gp.guardar(bd);
                } catch (IOException e) {
                    LOG.warning("[MSG] Error guardando ACKs: " + e.getMessage());
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
            return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());
        }

        String conUsuario = req.getString("conUsuario");
        if (conUsuario == null) {
            return MensajeProtocolo.error(req.getRequestId(), "Falta campo: conUsuario");
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
                    .sorted(Comparator.comparingLong((com.steam.models.Mensaje m) ->
                            m.lamportClock > 0 ? m.lamportClock : m.timestamp))
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
}
