package com.steam.proxy;

import com.steam.common.*;
import com.steam.coordinacion.EstadoNodo;
import com.steam.coordinacion.RegistroMembresia;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;

/**
 * Proxy.java – Capa de Intermediación (Gateway)
 *
 * Responsabilidades:
 *  1. Escucha conexiones de clientes en el puerto 8080.
 *  2. Acepta registros dinámicos de nodos (REGISTRAR_NODO / DESREGISTRAR_NODO).
 *     Los servidores se registran al arrancar usando RegistradorProxy.
 *  3. Inspecciona la operación del mensaje para determinar el clúster destino.
 *  4. Aplica Round-Robin por clúster eligiendo el nodo activo.
 *  5. Reenvía el mensaje al nodo seleccionado y devuelve la respuesta al cliente.
 *  6. Un hilo daemon realiza Health Checks cada 10 s y marca nodos caídos.
 *     Si el nodo primario cae, las peticiones se redirigen automáticamente al espejo.
 *
 * INTEGRACIÓN CON LOS SERVIDORES:
 *  - Los servidores (svSesiones, svJuegos, svMensajeria) envían REGISTRAR_NODO
 *    al arrancar. El Proxy los registra dinámicamente en su tabla de ruteo.
 *  - Al apagarse (graceful), los servidores envían DESREGISTRAR_NODO.
 *  - El health-check sigue ejecutándose como mecanismo de detección complementario.
 *
 * MODELO DE FALLOS cubierto:
 *  - Crash de nodo: detectado por timeout en health check → failover al espejo.
 *  - Omisión de envío: si la conexión al servidor falla, se intenta el otro nodo.
 *  - Fallo de temporización: socket con SO_TIMEOUT=5 s.
 */
public class Proxy {

    private static final Logger          LOG          = Logger.getLogger(Proxy.class.getName());
    private static final RelojLamport    relojLamport = new RelojLamport();
    private static final RegistroMembresia membresia  = new RegistroMembresia();

    // ── Representación de un nodo ─────────────────────────────────────────────

    static class Nodo {
        final String nombre;
        final int    puerto;
        final AtomicBoolean activo = new AtomicBoolean(true);

        Nodo(String nombre, int puerto) {
            this.nombre = nombre;
            this.puerto = puerto;
        }
    }

    // ── Clústeres y contadores Round-Robin ────────────────────────────────────
    // CopyOnWriteArrayList: permite iteración concurrente sin locks en rutear()
    // mientras procesarRegistro() puede añadir nodos sin ConcurrentModificationException.

    private final List<Nodo> clusterSesiones   = new CopyOnWriteArrayList<>();
    private final List<Nodo> clusterJuegos     = new CopyOnWriteArrayList<>();
    private final List<Nodo> clusterMensajeria = new CopyOnWriteArrayList<>();

    // Contadores atómicos por clúster (Round-Robin sin estado compartido problemático)
    private final AtomicInteger rrSesiones   = new AtomicInteger(0);
    private final AtomicInteger rrJuegos     = new AtomicInteger(0);
    private final AtomicInteger rrMensajeria = new AtomicInteger(0);

    // Pool de hilos para atender clientes
    private final ExecutorService pool =
            Executors.newFixedThreadPool(Constantes.POOL_SIZE);

    // ── Constructor ───────────────────────────────────────────────────────────

    public Proxy() {
        clusterSesiones.add(new Nodo("SES-1", Constantes.PUERTO_SES_1));
        clusterSesiones.add(new Nodo("SES-2", Constantes.PUERTO_SES_2));

        clusterJuegos.add(new Nodo("JUE-1", Constantes.PUERTO_JUE_1));
        clusterJuegos.add(new Nodo("JUE-2", Constantes.PUERTO_JUE_2));

        clusterMensajeria.add(new Nodo("MSG-1", Constantes.PUERTO_MSG_1));
        clusterMensajeria.add(new Nodo("MSG-2", Constantes.PUERTO_MSG_2));
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        GestorLog.configurar("Proxy");
        Proxy proxy = new Proxy();
        proxy.iniciarHealthCheck();
        proxy.escuchar();
    }

    /** Arranca el servidor en el puerto del Proxy. */
    public void escuchar() {
        LOG.info("=== Proxy iniciado en puerto " + Constantes.PUERTO_PROXY + " ===");
        try (ServerSocket server = new ServerSocket(Constantes.PUERTO_PROXY)) {
            server.setReuseAddress(true);
            while (true) {
                Socket cliente = server.accept();
                // Cada conexión de cliente se atiende en un hilo del pool
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (IOException e) {
            LOG.severe("Error crítico en Proxy: " + e.getMessage());
        }
    }

    // ── Manejo de cliente ─────────────────────────────────────────────────────

    /**
     * Lee el mensaje JSON del cliente.
     * - REGISTRAR_NODO / DESREGISTRAR_NODO: gestionados internamente por el Proxy
     *   (los servidores usan RegistradorProxy para integrarse al arrancar/parar).
     * - Resto de operaciones: ruteadas al clúster destino con Round-Robin.
     */
    private void manejarCliente(Socket cliente) {
        try (cliente;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(cliente.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(cliente.getOutputStream(), StandardCharsets.UTF_8), true)) {

            cliente.setSoTimeout(Constantes.TIMEOUT_MS);
            String linea = in.readLine();
            if (linea == null || linea.isBlank()) return;

            MensajeProtocolo req = MensajeProtocolo.fromJson(linea);
            if (req == null) {
                out.println(MensajeProtocolo.error("?", "Mensaje mal formado").toJson());
                return;
            }

            // Operaciones de control: el Proxy las gestiona directamente
            // sin reenviar al backend (patrón integración bidireccional).
            if (Constantes.REGISTRAR_NODO.equals(req.getOperacion())) {
                out.println(procesarRegistro(req).toJson());
                return;
            }
            if (Constantes.DESREGISTRAR_NODO.equals(req.getOperacion())) {
                out.println(procesarDesregistro(req).toJson());
                return;
            }

            LOG.info("[PROXY] " + req.getOperacion() + " → " + req.getRequestId());

            MensajeProtocolo resp = rutear(req);
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[PROXY] Timeout leyendo del cliente");
        } catch (IOException e) {
            LOG.warning("[PROXY] IO cliente: " + e.getMessage());
        }
    }

    // ── Registro dinámico de nodos ────────────────────────────────────────────

    /**
     * Registra o reactiva un nodo en el clúster correspondiente.
     * Invocado por los servidores al arrancar a través de RegistradorProxy.
     */
    private MensajeProtocolo procesarRegistro(MensajeProtocolo req) {
        String cluster = req.getString("cluster");
        int    puerto  = req.getInt("puerto");
        String nombre  = req.getString("nombre");

        if (cluster == null || puerto == 0 || nombre == null) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "REGISTRAR_NODO requiere: cluster, puerto, nombre");
        }

        List<Nodo> nodos = obtenerCluster(cluster);
        if (nodos == null) {
            return MensajeProtocolo.error(req.getRequestId(), "Cluster desconocido: " + cluster);
        }

        synchronized (nodos) {
            Nodo existente = nodos.stream()
                    .filter(n -> n.puerto == puerto).findFirst().orElse(null);
            if (existente != null) {
                existente.activo.set(true);
                LOG.info("[REGISTRO] Nodo " + nombre + " re-registrado activo (cluster=" + cluster + " puerto=" + puerto + ")");
            } else {
                nodos.add(new Nodo(nombre, puerto));
                LOG.info("[REGISTRO] Nuevo nodo: " + nombre + " cluster=" + cluster + " puerto=" + puerto);
            }
        }

        // Actualizar membresía
        long t = relojLamport.tick();
        EstadoNodo estado = new EstadoNodo(puerto, Constantes.HOST, puerto, 0, 0, t);
        estado.activo = true;
        membresia.registrar(estado);

        return MensajeProtocolo.ok(req.getRequestId(),
                "Nodo " + nombre + " registrado en cluster " + cluster);
    }

    /**
     * Marca un nodo como inactivo cuando el servidor avisa que se va a detener.
     */
    private MensajeProtocolo procesarDesregistro(MensajeProtocolo req) {
        String cluster = req.getString("cluster");
        int    puerto  = req.getInt("puerto");

        List<Nodo> nodos = obtenerCluster(cluster);
        if (nodos != null) {
            synchronized (nodos) {
                nodos.stream().filter(n -> n.puerto == puerto).findFirst()
                        .ifPresent(n -> {
                            n.activo.set(false);
                            LOG.info("[REGISTRO] Nodo " + n.nombre + " (puerto=" + puerto + ") desregistrado.");
                        });
            }
            membresia.marcarCaido(puerto);
        }

        return MensajeProtocolo.ok(req.getRequestId(), "Nodo desregistrado");
    }

    /** Retorna la lista de nodos del clúster, o null si el nombre es inválido. */
    private List<Nodo> obtenerCluster(String cluster) {
        return switch (cluster) {
            case "SESIONES"   -> clusterSesiones;
            case "JUEGOS"     -> clusterJuegos;
            case "MENSAJERIA" -> clusterMensajeria;
            default           -> null;
        };
    }

    // ── Ruteo Round-Robin ─────────────────────────────────────────────────────

    /**
     * Determina el clúster según la operación, elige el nodo por Round-Robin
     * y reenvía. Si el nodo primario falla, intenta el espejo.
     */
    private MensajeProtocolo rutear(MensajeProtocolo req) {
        String cluster = Utils.clusterParaOperacion(req.getOperacion());

        List<Nodo>    nodos;
        AtomicInteger rr;

        switch (cluster) {
            case "SESIONES"   -> { nodos = clusterSesiones;   rr = rrSesiones;   }
            case "JUEGOS"     -> { nodos = clusterJuegos;     rr = rrJuegos;     }
            case "MENSAJERIA" -> { nodos = clusterMensajeria; rr = rrMensajeria; }
            default -> {
                return MensajeProtocolo.error(req.getRequestId(),
                        "Operación desconocida: " + req.getOperacion());
            }
        }

        // Round-Robin seguro: Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (negativo),
        // por lo que usar solo Math.abs puede producir índice negativo al desbordarse.
        // La expresión (x % n + n) % n garantiza resultado positivo para cualquier x.
        int inicio = ((rr.getAndIncrement() % nodos.size()) + nodos.size()) % nodos.size();
        for (int i = 0; i < nodos.size(); i++) {
            Nodo nodo = nodos.get((inicio + i) % nodos.size());
            if (!nodo.activo.get()) continue;

            try {
                // Evento de envío: tick Lamport antes de reenviar al servidor
                req.setLamportClock(relojLamport.tick());
                LOG.fine("[LAMPORT-PROXY] t=" + req.getLamportClock() + " → nodo=" + nodo.nombre);
                MensajeProtocolo resp = reenviar(req, nodo.puerto);
                if (resp != null) {
                    relojLamport.update(resp.getLamportClock());
                    return resp;
                }
            } catch (Exception e) {
                LOG.warning("[PROXY] Nodo " + nodo.nombre + " falló: " + e.getMessage());
                nodo.activo.set(false); // marcado caído hasta el próximo health check
            }
        }

        return MensajeProtocolo.error(req.getRequestId(),
                "Todos los nodos del clúster " + cluster + " están caídos");
    }

    /** Abre conexión TCP al servidor, envía req y retorna la respuesta. */
    private MensajeProtocolo reenviar(MensajeProtocolo req, int puerto) throws IOException {
        try (Socket socket = new Socket(Constantes.HOST, puerto)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);

            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            out.println(req.toJson());
            String linea = in.readLine();
            return linea != null ? MensajeProtocolo.fromJson(linea) : null;
        }
    }

    // ── Health Check (hilo daemon) ────────────────────────────────────────────

    /**
     * Cada HEALTH_INTERVAL_MS envía HEALTH_CHECK a cada nodo.
     * Si no responde → nodo marcado inactivo (failover automático).
     * Si responde → nodo marcado activo (auto-recuperación).
     *
     * Itera las listas VIVAS (no una copia estática) para que los nodos
     * registrados dinámicamente también sean monitoreados.
     */
    private void iniciarHealthCheck() {
        Thread hc = new Thread(() -> {
            MensajeProtocolo ping = MensajeProtocolo.request(Constantes.HEALTH_CHECK, null);
            while (true) {
                // Esperar primero; los nodos arrancan como activos y se marcan caídos
                // solo DESPUÉS de la primera ronda, evitando falsos negativos al inicio.
                try { Thread.sleep(Constantes.HEALTH_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

                // Iterar sobre las listas vivas: CopyOnWriteArrayList garantiza
                // iteración segura aunque procesarRegistro() agregue nodos en paralelo.
                List<List<Nodo>> clusters = List.of(clusterSesiones, clusterJuegos, clusterMensajeria);
                for (List<Nodo> cluster : clusters) {
                    for (Nodo nodo : cluster) {
                        boolean ok = false;
                        try {
                            MensajeProtocolo resp = reenviar(ping, nodo.puerto);
                            ok = resp != null && resp.isOk();
                        } catch (Exception ignored) {}

                        if (nodo.activo.get() != ok) {
                            nodo.activo.set(ok);
                            long t = relojLamport.tick();
                            LOG.info("[HEALTH] Nodo " + nodo.nombre + (ok ? " ACTIVO" : " CAÍDO"));
                            LOG.info("[MEMBRESIA] t=" + t + " Nodo " + nodo.nombre
                                    + (ok ? " JOINED" : " LEFT"));
                            EstadoNodo estado = new EstadoNodo(
                                    nodo.puerto, Constantes.HOST, nodo.puerto, 0, 0, t);
                            estado.activo = ok;
                            if (ok) membresia.registrar(estado);
                            else    membresia.marcarCaido(nodo.puerto);
                        }
                    }
                }
            }
        }, "health-check");
        hc.setDaemon(true);
        hc.start();
        LOG.info("[HEALTH] Health Check iniciado (intervalo " +
                Constantes.HEALTH_INTERVAL_MS / 1000 + "s)");
    }
}
