package com.steam.common;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * RegistradorProxy — utilidad para que los servidores se registren
 * dinámicamente en el Proxy al arrancar.
 *
 * El registro se intenta en un hilo daemon con reintentos cada 3s,
 * ya que el Proxy puede no estar corriendo cuando el servidor arranca.
 * Si tras N intentos el Proxy no responde, el health-check del Proxy
 * sincronizará el estado cuando éste levante.
 *
 * Al cerrar, los servidores invocan desregistrar() para que el Proxy
 * marque el nodo como inactivo sin esperar al próximo health-check.
 */
public final class RegistradorProxy {

    private static final Logger LOG      = Logger.getLogger(RegistradorProxy.class.getName());
    private static final int    REINTENTOS = 10;
    private static final int    ESPERA_MS  = 3_000;

    private RegistradorProxy() {}

    /**
     * Lanza un hilo daemon que intenta registrar el nodo en el Proxy.
     * No bloquea el arranque del servidor.
     */
    public static void registrarAsync(String cluster, int puerto, String nombre) {
        Thread t = new Thread(
            () -> intentarRegistrar(cluster, puerto, nombre),
            "registrador-proxy-" + nombre
        );
        t.setDaemon(true);
        t.start();
    }

    /**
     * Notifica al Proxy que el nodo se está apagando.
     * Llamar desde shutdown hooks antes de cerrar el ServerSocket.
     */
    public static void desregistrar(String cluster, int puerto) {
        try {
            MensajeProtocolo req = MensajeProtocolo.request(Constantes.DESREGISTRAR_NODO, null);
            req.put("cluster", cluster);
            req.put("puerto",  puerto);
            enviar(req);
            LOG.info("[REGISTRADOR] Nodo " + cluster + ":" + puerto + " desregistrado del Proxy.");
        } catch (Exception e) {
            LOG.fine("[REGISTRADOR] No se pudo desregistrar del Proxy: " + e.getMessage());
        }
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private static void intentarRegistrar(String cluster, int puerto, String nombre) {
        for (int i = 1; i <= REINTENTOS; i++) {
            try { Thread.sleep(ESPERA_MS); } catch (InterruptedException e) { return; }
            try {
                MensajeProtocolo req = MensajeProtocolo.request(Constantes.REGISTRAR_NODO, null);
                req.put("cluster", cluster);
                req.put("host",    Constantes.HOST);
                req.put("puerto",  puerto);
                req.put("nombre",  nombre);

                MensajeProtocolo resp = enviar(req);
                if (resp != null && resp.isOk()) {
                    LOG.info("[REGISTRADOR] " + nombre + " registrado en Proxy: " + resp.getMensaje());
                    return;
                }
            } catch (Exception e) {
                LOG.fine("[REGISTRADOR] Proxy no disponible, intento " + i + "/" + REINTENTOS);
            }
        }
        LOG.warning("[REGISTRADOR] No se pudo contactar al Proxy para " + nombre
                + ". El health-check sincronizará cuando el Proxy levante.");
    }

    private static MensajeProtocolo enviar(MensajeProtocolo req) throws IOException {
        try (Socket s = new Socket(Constantes.HOST, Constantes.PUERTO_PROXY)) {
            s.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter   pw = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            pw.println(req.toJson());
            String linea = br.readLine();
            return linea != null ? MensajeProtocolo.fromJson(linea) : null;
        }
    }
}
