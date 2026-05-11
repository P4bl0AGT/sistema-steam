package com.steam.proxy;

import com.steam.common.Constantes;
import com.steam.common.GestorLog;
import com.steam.common.MensajeProtocolo;
import com.steam.common.Utils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

/**
 * Proxy.java – Capa de Intermediación (Gateway)
 *
 * Responsabilidades:
 *  1. Escucha conexiones de clientes en el puerto 8080.
 *  2. Inspecciona la operación del mensaje para determinar el clúster destino.
 *  3. Aplica Round-Robin por clúster eligiendo el nodo activo.
 *  4. Reenvía el mensaje al nodo seleccionado y devuelve la respuesta al cliente.
 *  5. Un hilo daemon realiza Health Checks cada 10 s y marca nodos caídos.
 *     Si el nodo primario cae, las peticiones se redirigen automáticamente al espejo.
 *
 * MODELO DE FALLOS cubierto:
 *  - Crash de nodo: detectado por timeout en health check → failover al espejo.
 *  - Omisión de envío: si la conexión al servidor falla, se intenta el otro nodo.
 *  - Fallo de temporización: socket con SO_TIMEOUT=5 s.
 */
public class Proxy {

    private static final Logger LOG = Logger.getLogger(Proxy.class.getName());

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

    private final List<Nodo> clusterSesiones   = new ArrayList<>();
    private final List<Nodo> clusterJuegos     = new ArrayList<>();
    private final List<Nodo> clusterMensajeria = new ArrayList<>();

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
     * Lee el mensaje JSON del cliente, determina el clúster, reenvía,
     * y devuelve la respuesta.
     * Los errores de red se convierten en MensajeProtocolo de error para
     * no dejar al cliente colgado (omisión de recepción mitigada).
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

            LOG.info("[PROXY] " + req.getOperacion() + " → " + req.getRequestId());

            MensajeProtocolo resp = rutear(req);
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[PROXY] Timeout leyendo del cliente");
        } catch (IOException e) {
            LOG.warning("[PROXY] IO cliente: " + e.getMessage());
        }
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

        // Intentar cada nodo empezando por el elegido por Round-Robin
        int inicio = Math.abs(rr.getAndIncrement()) % nodos.size();
        for (int i = 0; i < nodos.size(); i++) {
            Nodo nodo = nodos.get((inicio + i) % nodos.size());
            if (!nodo.activo.get()) continue;

            try {
                MensajeProtocolo resp = reenviar(req, nodo.puerto);
                if (resp != null) return resp;
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
     */
    private void iniciarHealthCheck() {
        List<Nodo> todos = new ArrayList<>();
        todos.addAll(clusterSesiones);
        todos.addAll(clusterJuegos);
        todos.addAll(clusterMensajeria);

        Thread hc = new Thread(() -> {
            MensajeProtocolo ping = MensajeProtocolo.request(Constantes.HEALTH_CHECK, null);
            while (true) {
                // Esperar primero; los nodos arrancan como activos y se marcan caídos
                // solo DESPUÉS de la primera ronda, evitando falsos negativos al inicio.
                try { Thread.sleep(Constantes.HEALTH_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

                for (Nodo nodo : todos) {
                    boolean ok = false;
                    try {
                        MensajeProtocolo resp = reenviar(ping, nodo.puerto);
                        ok = resp != null && resp.isOk();
                    } catch (Exception ignored) {}

                    if (nodo.activo.get() != ok) {
                        nodo.activo.set(ok);
                        LOG.info("[HEALTH] Nodo " + nodo.nombre +
                                (ok ? " ACTIVO" : " CAÍDO"));
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
