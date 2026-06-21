package com.steam.proxy;

import com.google.gson.JsonParseException;
import com.steam.common.*;
import com.steam.coordinacion.EstadoNodo;
import com.steam.coordinacion.RegistroMembresia;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Gateway redundante: cada proceso mantiene su propia membresia, RR y health. */
public class Proxy {
    private static final Logger LOG = Logger.getLogger(Proxy.class.getName());

    static final class Nodo {
        final String id;
        final String nombre;
        final String cluster;
        final String host;
        final int puerto;
        final int nodoId;
        final int puertoBully;
        final int puertoMutex;
        final int puertoReplicacion;
        final AtomicBoolean activo = new AtomicBoolean(false);

        Nodo(String nombre, String cluster, String host, int puerto, int nodoId,
             int puertoBully, int puertoMutex, int puertoReplicacion) {
            this.id = cluster + "-" + host + ":" + puerto;
            this.nombre = nombre;
            this.cluster = cluster;
            this.host = host;
            this.puerto = puerto;
            this.nodoId = nodoId;
            this.puertoBully = puertoBully;
            this.puertoMutex = puertoMutex;
            this.puertoReplicacion = puertoReplicacion;
        }
    }

    private final int proxyId;
    private final int puerto;
    private final String nombre;
    private final RelojLamport reloj;
    private final RegistroMembresia membresia;
    private final CacheIdempotencia idempotencia = new CacheIdempotencia();
    private final List<Nodo> clusterSesiones = new CopyOnWriteArrayList<>();
    private final List<Nodo> clusterJuegos = new CopyOnWriteArrayList<>();
    private final List<Nodo> clusterMensajeria = new CopyOnWriteArrayList<>();
    private final AtomicInteger rrSesiones = new AtomicInteger();
    private final AtomicInteger rrJuegos = new AtomicInteger();
    private final AtomicInteger rrMensajeria = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(Constantes.POOL_SIZE);

    public Proxy(int proxyId, int puerto) {
        this.proxyId = proxyId;
        this.puerto = puerto;
        this.nombre = "Proxy-" + proxyId;
        this.reloj = new RelojLamport(nombre);
        this.membresia = new RegistroMembresia("data/proxy-" + proxyId + "/MEMBRESIA.json");
        cargarTopologiaBase();
    }

    private void cargarTopologiaBase() {
        agregarSiAusente(new Nodo("SES-1", "SESIONES", Configuracion.hostServicio("sesiones", 1),
                Constantes.PUERTO_SES_1, 1, 0, 0, Constantes.PUERTO_SES_1_REPL));
        agregarSiAusente(new Nodo("SES-2", "SESIONES", Configuracion.hostServicio("sesiones", 2),
                Constantes.PUERTO_SES_2, 2, 0, 0, Constantes.PUERTO_SES_2_REPL));
        agregarSiAusente(new Nodo("JUE-1", "JUEGOS", Configuracion.hostServicio("juegos", 1),
                Constantes.PUERTO_JUE_1, 1, Constantes.PUERTO_JUE_1_BULLY,
                Constantes.PUERTO_JUE_1_MUTEX, Constantes.PUERTO_JUE_1_REPL));
        agregarSiAusente(new Nodo("JUE-2", "JUEGOS", Configuracion.hostServicio("juegos", 2),
                Constantes.PUERTO_JUE_2, 2, Constantes.PUERTO_JUE_2_BULLY,
                Constantes.PUERTO_JUE_2_MUTEX, Constantes.PUERTO_JUE_2_REPL));
        agregarSiAusente(new Nodo("MSG-1", "MENSAJERIA", Configuracion.hostServicio("mensajeria", 1),
                Constantes.PUERTO_MSG_1, 1, 0, 0, Constantes.PUERTO_MSG_1_REPL));
        agregarSiAusente(new Nodo("MSG-2", "MENSAJERIA", Configuracion.hostServicio("mensajeria", 2),
                Constantes.PUERTO_MSG_2, 2, 0, 0, Constantes.PUERTO_MSG_2_REPL));
    }

    public static void main(String[] args) {
        int id = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int defaultPort = id == 2 ? Constantes.PUERTO_PROXY_2 : Constantes.PUERTO_PROXY;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : defaultPort;
        GestorLog.configurar("Proxy-" + id);
        Proxy proxy = new Proxy(id, port);
        proxy.iniciarHealthCheck();
        proxy.escuchar();
    }

    public void escuchar() {
        LOG.info("[PROXY] " + nombre + " iniciado en " + Configuracion.bindHost() + ":" + puerto
                + " tls=" + Configuracion.tlsEnabled());
        try (ServerSocket server = Transporte.servidor(puerto)) {
            while (!Thread.currentThread().isInterrupted()) {
                Socket cliente = server.accept();
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (Exception e) {
            LOG.severe("[PROXY] Error critico: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private void manejarCliente(Socket cliente) {
        try (cliente;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     cliente.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     cliente.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String linea = LineaJson.leer(in);
            if (linea == null || linea.isBlank()) return;
            MensajeProtocolo req;
            try { req = MensajeProtocolo.fromJson(linea); }
            catch (JsonParseException | IllegalStateException e) {
                out.println(error("?", "JSON_MALFORMADO", "Mensaje JSON mal formado").toJson());
                return;
            }
            String seguridad = SeguridadMensajes.validarSolicitud(req);
            if (seguridad != null) {
                out.println(error(req != null ? req.getRequestId() : "?",
                        "SECURITY", seguridad).toJson());
                return;
            }

            long recibido = reloj.update(req.getLamportClock());
            MensajeProtocolo resp = idempotencia.ejecutar(req.getRequestId(), () -> procesar(req));
            resp.setLamportClock(reloj.tick());
            resp.setEmisor(nombre);
            resp.setReceptor(req.getEmisor());
            LogDistribuido.evento(LOG, Level.INFO, nombre, req.getOperacion(),
                    req.getRequestId(), recibido, req.getEmisor(), nombre,
                    resp.isOk() ? "OK" : resp.getCodigoError());
            out.println(resp.toJson());
        } catch (SocketTimeoutException e) {
            LOG.warning("[PROXY] Timeout leyendo cliente: " + e.getMessage());
        } catch (Exception e) {
            LOG.warning("[PROXY] Error manejando cliente: " + e.getMessage());
        }
    }

    private MensajeProtocolo procesar(MensajeProtocolo req) {
        return switch (req.getOperacion()) {
            case Constantes.REGISTRAR_NODO -> procesarRegistro(req);
            case Constantes.DESREGISTRAR_NODO -> procesarDesregistro(req);
            case Constantes.ESTADO_MEMBRESIA -> estadoMembresia(req);
            case Constantes.SHUTDOWN_PROXY -> shutdown(req);
            default -> rutear(req);
        };
    }

    private MensajeProtocolo procesarRegistro(MensajeProtocolo req) {
        if (!SeguridadMensajes.validarControl(req))
            return error(req.getRequestId(), "UNAUTHORIZED", "Firma de registro invalida");
        String cluster = req.getString("cluster");
        String host = req.getString("host");
        String nombreNodo = req.getString("nombre");
        int port = req.getInt("puerto");
        int nodoId = req.getInt("nodoId");
        if (!List.of("SESIONES", "JUEGOS", "MENSAJERIA").contains(cluster)
                || host == null || host.isBlank() || host.length() > 253
                || nombreNodo == null || !nombreNodo.matches("[A-Z]{3}-[1-9][0-9]*")
                || port < 1 || port > 65_535 || nodoId < 1) {
            return error(req.getRequestId(), "INVALID_REGISTRATION", "Registro de nodo malformado");
        }
        Nodo nodo = new Nodo(nombreNodo, cluster, host, port, nodoId,
                req.getInt("puertoBully"), req.getInt("puertoMutex"),
                req.getInt("puertoReplicacion"));
        Nodo real = agregarSiAusente(nodo);
        real.activo.set(true);
        registrarMembresia(real, reloj.tick());
        return MensajeProtocolo.ok(req.getRequestId(), nombreNodo + " registrado en " + nombre);
    }

    private MensajeProtocolo procesarDesregistro(MensajeProtocolo req) {
        if (!SeguridadMensajes.validarControl(req))
            return error(req.getRequestId(), "UNAUTHORIZED", "Firma de desregistro invalida");
        String cluster = req.getString("cluster");
        String host = req.getString("host");
        int port = req.getInt("puerto");
        List<Nodo> nodos = obtenerCluster(cluster);
        if (nodos != null) nodos.stream()
                .filter(n -> n.puerto == port && n.host.equals(host)).findFirst()
                .ifPresent(n -> { n.activo.set(false); membresia.marcarCaido(n.id); });
        return MensajeProtocolo.ok(req.getRequestId(), "Nodo desregistrado");
    }

    private MensajeProtocolo estadoMembresia(MensajeProtocolo req) {
        if (!SeguridadMensajes.validarControl(req))
            return error(req.getRequestId(), "UNAUTHORIZED", "Firma de control invalida");
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Membresia de " + nombre);
        resp.put("proxyId", proxyId).put("miembros", membresia.getTodos());
        return resp;
    }

    private MensajeProtocolo shutdown(MensajeProtocolo req) {
        if (!SeguridadMensajes.validarControl(req))
            return error(req.getRequestId(), "UNAUTHORIZED", "Firma de apagado invalida");
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), nombre + " cerrando");
        new Thread(() -> {
            try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            System.exit(0);
        }, "shutdown-proxy").start();
        return resp;
    }

    private MensajeProtocolo rutear(MensajeProtocolo req) {
        String cluster = Utils.clusterParaOperacion(req.getOperacion());
        List<Nodo> nodos = obtenerCluster(cluster);
        if (nodos == null) return error(req.getRequestId(), "UNKNOWN_OPERATION",
                "Operacion desconocida: " + req.getOperacion());
        AtomicInteger rr = contador(cluster);
        boolean escritura = Utils.esOperacionEscritura(req.getOperacion());
        int inicio = escritura ? indiceEscritor(nodos, cluster)
                : Math.floorMod(rr.getAndIncrement(), nodos.size());
        if (inicio < 0 || (escritura && !nodos.get(inicio).activo.get())) {
            return error(req.getRequestId(), "SERVICE_UNAVAILABLE",
                    "Escritor configurado de " + cluster + " no disponible");
        }
        boolean algunoActivo = false;
        for (int i = 0; i < nodos.size(); i++) {
            Nodo nodo = nodos.get((inicio + i) % nodos.size());
            if (!nodo.activo.get()) continue;
            algunoActivo = true;
            try {
                req.setEmisor(nombre);
                req.setReceptor(nodo.nombre);
                req.setLamportClock(reloj.tick());
                MensajeProtocolo resp = reenviar(req, nodo);
                reloj.update(resp.getLamportClock());
                return resp;
            } catch (Exception e) {
                nodo.activo.set(false);
                membresia.marcarCaido(nodo.id);
                LOG.warning("[PROXY] " + nodo.nombre + " fallo en " + req.getOperacion()
                        + ": " + e.getMessage());
                if (escritura) break;
            }
        }
        return error(req.getRequestId(), "SERVICE_UNAVAILABLE",
                algunoActivo ? "Los nodos activos no respondieron" : "Cluster " + cluster + " sin nodos activos");
    }

    private int indiceEscritor(List<Nodo> nodos, String cluster) {
        int writerNodeId = Configuracion.writerNodeId(cluster);
        for (int i = 0; i < nodos.size(); i++) {
            Nodo n = nodos.get(i);
            if (n.nodoId == writerNodeId) return i;
        }
        return -1;
    }

    private MensajeProtocolo reenviar(MensajeProtocolo req, Nodo nodo) throws Exception {
        try (Socket socket = Transporte.conectar(nodo.host, nodo.puerto);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.println(req.toJson());
            String linea = LineaJson.leer(in);
            if (linea == null) throw new java.io.EOFException("Respuesta vacia");
            return MensajeProtocolo.fromJson(linea);
        }
    }

    private void iniciarHealthCheck() {
        Thread hc = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(Constantes.HEALTH_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                for (List<Nodo> cluster : List.of(clusterSesiones, clusterJuegos, clusterMensajeria)) {
                    for (Nodo nodo : cluster) verificarNodo(nodo);
                }
            }
        }, "health-check-" + nombre);
        hc.setDaemon(true);
        hc.start();
    }

    private void verificarNodo(Nodo nodo) {
        boolean ok = false;
        try {
            MensajeProtocolo ping = MensajeProtocolo.request(Constantes.HEALTH_CHECK, null);
            ping.setEmisor(nombre);
            ping.setReceptor(nodo.nombre);
            ping.setLamportClock(reloj.tick());
            MensajeProtocolo resp = reenviar(ping, nodo);
            reloj.update(resp.getLamportClock());
            ok = resp.isOk();
        } catch (Exception ignored) {}
        boolean anterior = nodo.activo.getAndSet(ok);
        if (ok) registrarMembresia(nodo, reloj.tick());
        else membresia.marcarCaido(nodo.id);
        if (anterior != ok) LOG.info("[HEALTH] " + nodo.nombre + (ok ? " ACTIVO" : " CAIDO"));
    }

    private Nodo agregarSiAusente(Nodo candidato) {
        List<Nodo> lista = obtenerCluster(candidato.cluster);
        Nodo existente = lista.stream().filter(n -> n.id.equals(candidato.id)).findFirst().orElse(null);
        if (existente != null) return existente;
        lista.add(candidato);
        return candidato;
    }

    private void registrarMembresia(Nodo n, long lamport) {
        membresia.registrar(new EstadoNodo(n.id, n.cluster, n.nombre, n.host, n.puerto,
                n.puertoBully, n.puertoMutex, n.puertoReplicacion, lamport));
    }

    private List<Nodo> obtenerCluster(String cluster) {
        if (cluster == null) return null;
        return switch (cluster) {
            case "SESIONES" -> clusterSesiones;
            case "JUEGOS" -> clusterJuegos;
            case "MENSAJERIA" -> clusterMensajeria;
            default -> null;
        };
    }

    private AtomicInteger contador(String cluster) {
        return switch (cluster) {
            case "SESIONES" -> rrSesiones;
            case "JUEGOS" -> rrJuegos;
            default -> rrMensajeria;
        };
    }

    private static MensajeProtocolo error(String requestId, String codigo, String mensaje) {
        return MensajeProtocolo.error(requestId, mensaje).setCodigoErrorFluent(codigo);
    }
}
