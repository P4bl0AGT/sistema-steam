package com.steam.common;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Registro y renovacion periodica en todas las instancias de Proxy. */
public final class RegistradorProxy {
    private static final Logger LOG = Logger.getLogger(RegistradorProxy.class.getName());
    private static final RelojLamport RELOJ = new RelojLamport("RegistradorProxy");
    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "registrador-proxies");
                t.setDaemon(true);
                return t;
            });

    private RegistradorProxy() {}

    public static void registrarAsync(String cluster, int nodoId, int puerto, String nombre,
                                      int puertoBully, int puertoMutex, int puertoReplicacion) {
        Runnable tarea = () -> registrarEnTodos(cluster, nodoId, puerto, nombre,
                puertoBully, puertoMutex, puertoReplicacion);
        SCHED.scheduleAtFixedRate(tarea, 0, 15, TimeUnit.SECONDS);
    }

    /** Compatibilidad con servidores sin canales adicionales. */
    public static void registrarAsync(String cluster, int puerto, String nombre) {
        int nodoId = nombre.endsWith("-2") ? 2 : 1;
        registrarAsync(cluster, nodoId, puerto, nombre, 0, 0, 0);
    }

    private static void registrarEnTodos(String cluster, int nodoId, int puerto, String nombre,
                                         int puertoBully, int puertoMutex, int puertoReplicacion) {
        for (Endpoint proxy : Configuracion.proxies()) {
            try {
                MensajeProtocolo req = MensajeProtocolo.request(Constantes.REGISTRAR_NODO, null);
                req.setEmisor(nombre);
                req.setReceptor("Proxy@" + proxy);
                req.setLamportClock(RELOJ.tick());
                req.put("cluster", cluster).put("host", Configuracion.advertisedHost())
                        .put("puerto", puerto).put("nombre", nombre).put("nodoId", nodoId)
                        .put("puertoBully", puertoBully).put("puertoMutex", puertoMutex)
                        .put("puertoReplicacion", puertoReplicacion);
                SeguridadMensajes.firmarControl(req);
                MensajeProtocolo resp = ClienteProxy.enviarA(req, proxy);
                RELOJ.update(resp.getLamportClock());
                if (!resp.isOk()) LOG.warning("[REGISTRO] " + proxy + ": " + resp.getMensaje());
            } catch (Exception e) {
                LOG.fine("[REGISTRO] Proxy no disponible " + proxy + ": " + e.getMessage());
            }
        }
    }

    public static void desregistrar(String cluster, int puerto) {
        for (Endpoint proxy : Configuracion.proxies()) {
            try {
                MensajeProtocolo req = MensajeProtocolo.request(Constantes.DESREGISTRAR_NODO, null);
                req.setEmisor("nodo:" + puerto);
                req.setReceptor("Proxy@" + proxy);
                req.setLamportClock(RELOJ.tick());
                req.put("cluster", cluster).put("host", Configuracion.advertisedHost())
                        .put("puerto", puerto);
                SeguridadMensajes.firmarControl(req);
                ClienteProxy.enviarA(req, proxy);
            } catch (Exception e) {
                LOG.fine("[REGISTRO] No se pudo desregistrar en " + proxy + ": " + e.getMessage());
            }
        }
    }
}
