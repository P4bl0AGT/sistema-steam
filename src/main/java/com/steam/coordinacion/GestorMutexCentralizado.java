package com.steam.coordinacion;

import com.steam.common.Constantes;
import com.steam.common.RelojLamport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * GestorMutexCentralizado – Exclusión Mutua con Coordinador Central.
 *
 * ARQUITECTURA:
 *  - El coordinador (elegido por Bully) abre un ServerSocket en su puerto
 *    de mutex (9182 o 9382) y procesa REQUEST / RELEASE.
 *  - El no-coordinador llama requestLock() → abre TCP al coordinador,
 *    envía REQUEST y queda bloqueado esperando GRANT en la misma conexión.
 *  - Tras terminar, llama releaseLock() → envía RELEASE en una nueva conexión.
 *
 * SEMÁFORO POR RECURSO:
 *  El coordinador usa un Semaphore(1) por nombre de recurso. La hebra del
 *  handler del REQUEST hace tryAcquire(timeout); cuando lo obtiene, envía
 *  GRANT al solicitante. El RELEASE hace release() al semáforo.
 *
 * Si el coordinador cae durante requestLock(), el timeout expira y se lanza
 * MutexTimeoutException, que svJuegos captura para retornar un error al cliente.
 */
public class GestorMutexCentralizado {

    private static final Logger LOG = Logger.getLogger(GestorMutexCentralizado.class.getName());

    private final int          miId;
    private final int          miPuertoMutex;
    private final GestorBully  bully;
    private final RelojLamport reloj;
    private final List<NodoInfo> peers;

    /** Semáforos por recurso — solo el coordinador los usa activamente. */
    private final Map<String, Semaphore> semaforos = new ConcurrentHashMap<>();

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final ExecutorService pool  = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mutex-worker");
        t.setDaemon(true);
        return t;
    });

    // ── Constructor ───────────────────────────────────────────────────────────

    public GestorMutexCentralizado(int miId, int miPuertoMutex,
                                   GestorBully bully, RelojLamport reloj,
                                   List<NodoInfo> peers) {
        this.miId          = miId;
        this.miPuertoMutex = miPuertoMutex;
        this.bully         = bully;
        this.reloj         = reloj;
        this.peers         = peers;
    }

    // ── Servidor (coordinador) ────────────────────────────────────────────────

    /** Arranca el servidor de mutex. Cada nodo lo lanza; solo el coordinador otorga. */
    public void startServidor() {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(miPuertoMutex)) {
                server.setReuseAddress(true);
                LOG.info("[MUTEX] Servidor escuchando en puerto " + miPuertoMutex);
                while (!stopped.get()) {
                    try {
                        Socket cliente = server.accept();
                        pool.submit(() -> manejarPeticion(cliente));
                    } catch (IOException e) {
                        if (!stopped.get())
                            LOG.warning("[MUTEX] Error accept: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.severe("[MUTEX] No se pudo abrir servidor en " + miPuertoMutex);
            }
        }, "mutex-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped.set(true);
        pool.shutdownNow();
    }

    // ── Manejo de peticiones (ejecutado solo en el coordinador en la práctica) ─

    private void manejarPeticion(Socket socket) {
        try (socket;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(Constantes.TIMEOUT_MUTEX_MS + 2_000);
            String linea = in.readLine();
            if (linea == null) return;

            MensajeMutex msg = MensajeMutex.fromJson(linea);
            reloj.update(msg.lamportClock);

            switch (msg.tipo) {
                case MensajeMutex.REQUEST -> {
                    LOG.info("[MUTEX] t=" + reloj.get()
                            + " REQUEST encolado para nodo-" + msg.solicitanteId
                            + " recurso=" + msg.recurso);

                    Semaphore sem = semaforos.computeIfAbsent(
                            msg.recurso, k -> new Semaphore(1));

                    // Bloqueamos hasta que el recurso esté libre (o timeout)
                    boolean acquired = sem.tryAcquire(
                            Constantes.TIMEOUT_MUTEX_MS, TimeUnit.MILLISECONDS);

                    if (acquired) {
                        long t = reloj.tick();
                        LOG.info("[MUTEX] t=" + t + " GRANT recurso=" + msg.recurso
                                + " solicitante=nodo-" + msg.solicitanteId);
                        out.println(new MensajeMutex(
                                MensajeMutex.GRANT, miId, msg.recurso, msg.requestId, t)
                                .toJson());
                    } else {
                        // Timeout: el coordinador no pudo otorgar
                        LOG.warning("[MUTEX] Timeout otorgando recurso=" + msg.recurso);
                        out.println(new MensajeMutex(
                                "TIMEOUT", miId, msg.recurso, msg.requestId, reloj.tick())
                                .toJson());
                    }
                }
                case MensajeMutex.RELEASE -> {
                    LOG.info("[MUTEX] t=" + reloj.get()
                            + " RELEASE recurso=" + msg.recurso
                            + " por nodo-" + msg.solicitanteId);
                    Semaphore sem = semaforos.get(msg.recurso);
                    if (sem != null) sem.release();
                    // No se envía respuesta al RELEASE
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warning("[MUTEX] Error: " + e.getMessage());
        }
    }

    // ── Cliente (no-coordinador) ──────────────────────────────────────────────

    /**
     * Envía REQUEST al coordinador y espera GRANT.
     * Lanza MutexTimeoutException si el coordinador no responde a tiempo.
     */
    public void requestLock(String recurso, int solicitanteId) {
        int coordId        = bully.getCoordinadorActual();
        int coordMutexPort = puertoMutexDeNodo(coordId);
        String reqId       = UUID.randomUUID().toString();
        long   t           = reloj.tick();

        LOG.info("[MUTEX] t=" + t + " enviando REQUEST recurso=" + recurso
                + " → coordinador nodo-" + coordId);

        try (Socket socket = new Socket(Constantes.HOST, coordMutexPort)) {
            // Timeout ligeramente mayor que el del coordinador
            socket.setSoTimeout(Constantes.TIMEOUT_MUTEX_MS + 3_000);

            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            out.println(new MensajeMutex(
                    MensajeMutex.REQUEST, solicitanteId, recurso, reqId, t).toJson());

            String respLine = in.readLine();
            if (respLine == null)
                throw new MutexTimeoutException("Sin respuesta del coordinador (recurso=" + recurso + ")");

            MensajeMutex resp = MensajeMutex.fromJson(respLine);
            reloj.update(resp.lamportClock);

            if (!MensajeMutex.GRANT.equals(resp.tipo))
                throw new MutexTimeoutException("Lock no otorgado: " + resp.tipo);

        } catch (IOException e) {
            throw new MutexTimeoutException(
                    "Coordinador nodo-" + coordId + " no alcanzable: " + e.getMessage());
        }
    }

    /** Envía RELEASE al coordinador (fire-and-forget). */
    public void releaseLock(String recurso, int solicitanteId) {
        int coordId        = bully.getCoordinadorActual();
        int coordMutexPort = puertoMutexDeNodo(coordId);

        long t = reloj.tick();
        LOG.info("[MUTEX] t=" + t + " enviando RELEASE recurso=" + recurso
                + " → coordinador nodo-" + coordId);

        try (Socket socket = new Socket(Constantes.HOST, coordMutexPort)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(new MensajeMutex(
                    MensajeMutex.RELEASE, solicitanteId, recurso,
                    UUID.randomUUID().toString(), t).toJson());
        } catch (IOException e) {
            LOG.warning("[MUTEX] Error enviando RELEASE: " + e.getMessage());
        }
    }

    // ── Utilidad ──────────────────────────────────────────────────────────────

    private int puertoMutexDeNodo(int id) {
        return (id == 1) ? Constantes.PUERTO_JUE_1_MUTEX : Constantes.PUERTO_JUE_2_MUTEX;
    }
}
