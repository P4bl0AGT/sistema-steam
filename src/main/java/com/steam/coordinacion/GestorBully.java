package com.steam.coordinacion;

import com.steam.common.Constantes;
import com.steam.common.RelojLamport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * GestorBully – Algoritmo de Elección de Coordinador (Bully).
 *
 * Cada nodo de svJuegos instancia un GestorBully con su propio id y la
 * lista de peers. El nodo con mayor id gana la elección y se convierte
 * en coordinador del mutex distribuido.
 *
 * Protocolo (puertos dedicados 9082/9282):
 *  ELECTION     → enviado a nodos con id > miId; espera OK (3 s)
 *  OK           → respuesta a ELECTION; indica que un nodo mayor tomará el control
 *  COORDINATOR  → broadcast al ganar la elección
 *  HEARTBEAT    → enviado por no-coordinador al coordinador cada 5 s
 *
 * Detección de fallo del coordinador: si HEARTBEAT_COORD no recibe respuesta
 * en 3 s → iniciarEleccion().
 */
public class GestorBully {

    private static final Logger LOG = Logger.getLogger(GestorBully.class.getName());

    private final int            miId;
    private final int            miPuertoBully;
    private final List<NodoInfo> peers;
    private final RelojLamport   reloj;

    /** Id del coordinador actual; -1 = desconocido. */
    volatile int coordinadorActual = -1;

    private final AtomicBoolean eleccionEnCurso = new AtomicBoolean(false);
    private final AtomicBoolean stopped         = new AtomicBoolean(false);

    /**
     * Métrica de coordinación (rúbrica 3.2): cuenta los mensajes Bully que
     * este nodo EMITE (ELECTION, OK, COORDINATOR, HEARTBEAT). Se cuentan los
     * enviados —no los recibidos— para que la suma entre nodos no duplique.
     */
    private final AtomicLong mensajesEnviados = new AtomicLong(0);

    // Latches reutilizados por iniciarEleccion(); volatile para visibilidad cross-thread
    private volatile CountDownLatch okLatch;
    private volatile CountDownLatch coordLatch;

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bully-worker");
        t.setDaemon(true);
        return t;
    });

    // ── Constructor ───────────────────────────────────────────────────────────

    public GestorBully(int miId, int miPuertoBully, List<NodoInfo> peers, RelojLamport reloj) {
        this.miId          = miId;
        this.miPuertoBully = miPuertoBully;
        this.peers         = peers;
        this.reloj         = reloj;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /** Arranca el servidor Bully, el daemon de heartbeat y lanza la primera elección. */
    public void start() {
        iniciarServidorBully();
        iniciarHeartbeatDaemon();
        // Pequeña pausa para que el servidor del otro nodo (si ya arrancó) esté listo
        pool.submit(() -> {
            try { Thread.sleep(1_500); } catch (InterruptedException ignored) {}
            iniciarEleccion();
        });
        LOG.info("[BULLY] GestorBully iniciado. id=" + miId + " puerto=" + miPuertoBully);
    }

    public void stop() {
        stopped.set(true);
        pool.shutdownNow();
    }

    public boolean isCoordinador()       { return coordinadorActual == miId; }
    public int     getCoordinadorActual(){ return coordinadorActual; }

    /** Total de mensajes Bully emitidos por este nodo (métrica de coordinación). */
    public long    getMensajesCoordinacion(){ return mensajesEnviados.get(); }

    // ── Servidor Bully (hilo daemon) ─────────────────────────────────────────

    private void iniciarServidorBully() {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(miPuertoBully)) {
                server.setReuseAddress(true);
                LOG.info("[BULLY] Escuchando en puerto " + miPuertoBully);
                while (!stopped.get()) {
                    try {
                        Socket cliente = server.accept();
                        pool.submit(() -> procesarMensaje(cliente));
                    } catch (IOException e) {
                        if (!stopped.get())
                            LOG.warning("[BULLY] Error accept: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.severe("[BULLY] No se pudo abrir servidor en " + miPuertoBully + ": " + e.getMessage());
            }
        }, "bully-server");
        t.setDaemon(true);
        t.start();
    }

    // ── Procesamiento de mensajes entrantes ───────────────────────────────────

    private void procesarMensaje(Socket socket) {
        try (socket;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            String linea = in.readLine();
            if (linea == null) return;

            MensajeBully msg = MensajeBully.fromJson(linea);
            reloj.update(msg.lamportClock);

            switch (msg.tipo) {
                case MensajeBully.ELECTION -> {
                    if (miId > msg.emisorId) {
                        // Respondo OK en la misma conexión
                        MensajeBully ok = new MensajeBully(
                                MensajeBully.OK, miId, -1, reloj.tick());
                        out.println(ok.toJson());
                        mensajesEnviados.incrementAndGet();
                        LOG.info("[BULLY] t=" + ok.lamportClock
                                + " OK enviado a nodo-" + msg.emisorId);
                        // Si yo no tengo elección en curso, inicio la mía
                        if (!eleccionEnCurso.get()) {
                            pool.submit(this::iniciarEleccion);
                        }
                    }
                }
                case MensajeBully.COORDINATOR -> {
                    coordinadorActual = msg.coordinadorId;
                    LOG.info("[BULLY] t=" + reloj.get()
                            + " COORDINATOR recibido, nuevo coordinador=" + msg.coordinadorId);
                    CountDownLatch latch = coordLatch;
                    if (latch != null) latch.countDown();
                }
                case MensajeBully.HEARTBEAT_COORD -> {
                    // Respondemos OK para confirmar que seguimos vivos
                    MensajeBully resp = new MensajeBully(
                            MensajeBully.OK, miId, coordinadorActual, reloj.tick());
                    out.println(resp.toJson());
                    mensajesEnviados.incrementAndGet();
                }
                // OK llega como RESPUESTA en el canal de enviarEleccion(), no aquí
            }
        } catch (IOException e) {
            LOG.fine("[BULLY] IO en mensaje: " + e.getMessage());
        }
    }

    // ── Algoritmo Bully ───────────────────────────────────────────────────────

    /**
     * Implementación estándar del Bully:
     * 1. Envía ELECTION en paralelo a todos los peers con id > miId.
     * 2. Espera OK durante TIMEOUT_BULLY_OK_MS.
     * 3. Si no hay OK → me proclamo COORDINATOR y hago broadcast.
     * 4. Si hay OK → espero COORDINATOR durante TIMEOUT_BULLY_COORD_MS.
     * 5. Si no llega COORDINATOR → reinicio la elección.
     */
    public void iniciarEleccion() {
        // Evitar elecciones simultáneas
        if (!eleccionEnCurso.compareAndSet(false, true)) return;

        LOG.info("[BULLY] t=" + reloj.tick() + " Iniciando elección. id=" + miId);

        List<NodoInfo> mayores = peers.stream()
                .filter(p -> p.id > miId)
                .toList();

        if (mayores.isEmpty()) {
            // Soy el nodo de mayor id → coordinador sin negociación
            convertirseEnCoordinador();
            eleccionEnCurso.set(false);
            return;
        }

        // Pre-inicializar latches ANTES de enviar (evita NPE si llega respuesta rápida)
        okLatch    = new CountDownLatch(1);
        coordLatch = new CountDownLatch(1);

        // Enviar ELECTION en paralelo a todos los mayores
        CountDownLatch fin = new CountDownLatch(mayores.size());
        AtomicBoolean  okRecibido = new AtomicBoolean(false);

        for (NodoInfo peer : mayores) {
            pool.submit(() -> {
                try {
                    if (enviarEleccionYEsperarOk(peer)) {
                        okRecibido.set(true);
                        okLatch.countDown(); // despierta al waiter
                    }
                } finally {
                    fin.countDown();
                }
            });
        }

        // Esperar hasta que alguno responda OK o todos fallen
        try {
            okLatch.await(Constantes.TIMEOUT_BULLY_OK_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            eleccionEnCurso.set(false);
            return;
        }

        if (!okRecibido.get()) {
            // Nadie respondió OK → soy el coordinador
            convertirseEnCoordinador();
        } else {
            // Alguno respondió OK → espero que él anuncie COORDINATOR
            try {
                boolean llego = coordLatch.await(Constantes.TIMEOUT_BULLY_COORD_MS,
                        TimeUnit.MILLISECONDS);
                if (!llego) {
                    LOG.warning("[BULLY] COORDINATOR no llegó en "
                            + Constantes.TIMEOUT_BULLY_COORD_MS + "ms. Reintentando.");
                    eleccionEnCurso.set(false);
                    iniciarEleccion(); // reintentar
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        eleccionEnCurso.set(false);
    }

    /** Envía ELECTION y espera OK en la misma conexión TCP. */
    private boolean enviarEleccionYEsperarOk(NodoInfo peer) {
        try (Socket socket = new Socket(peer.host, peer.puertoBully)) {
            socket.setSoTimeout(Constantes.TIMEOUT_BULLY_OK_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            long t = reloj.tick();
            MensajeBully msg = new MensajeBully(MensajeBully.ELECTION, miId, -1, t);
            LOG.info("[BULLY] t=" + t + " ELECTION enviado a nodo-" + peer.id);
            out.println(msg.toJson());
            mensajesEnviados.incrementAndGet();

            String respLine = in.readLine();
            if (respLine == null) return false;

            MensajeBully resp = MensajeBully.fromJson(respLine);
            reloj.update(resp.lamportClock);
            boolean esOk = MensajeBully.OK.equals(resp.tipo);
            if (esOk) LOG.info("[BULLY] t=" + reloj.get() + " OK recibido de nodo-" + peer.id);
            return esOk;
        } catch (IOException e) {
            LOG.fine("[BULLY] Nodo-" + peer.id + " no alcanzable para ELECTION: " + e.getMessage());
            return false;
        }
    }

    /** Proclama este nodo como coordinador y hace broadcast de COORDINATOR. */
    private void convertirseEnCoordinador() {
        coordinadorActual = miId;
        long t = reloj.tick();
        LOG.info("[BULLY] t=" + t + " SOY COORDINADOR (id=" + miId + ")");

        for (NodoInfo peer : peers) {
            enviarUnidireccional(
                    new MensajeBully(MensajeBully.COORDINATOR, miId, miId, reloj.tick()),
                    peer.puertoBully);
        }
    }

    /** Envía un mensaje sin esperar respuesta (fire-and-forget). */
    private void enviarUnidireccional(MensajeBully msg, int puerto) {
        try (Socket socket = new Socket(Constantes.HOST, puerto)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(msg.toJson());
            mensajesEnviados.incrementAndGet();
        } catch (IOException e) {
            LOG.fine("[BULLY] No se pudo enviar " + msg.tipo + " a puerto " + puerto);
        }
    }

    // ── Heartbeat daemon ──────────────────────────────────────────────────────

    private void iniciarHeartbeatDaemon() {
        Thread t = new Thread(() -> {
            while (!stopped.get()) {
                try {
                    Thread.sleep(Constantes.HEARTBEAT_COORD_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                int coord = coordinadorActual;
                if (coord == miId || coord == -1) continue; // soy coordinador o aún sin elegir

                NodoInfo coordNodo = peers.stream()
                        .filter(p -> p.id == coord).findFirst().orElse(null);
                if (coordNodo == null) continue;

                boolean vivo = checkHeartbeat(coordNodo);
                if (!vivo) {
                    LOG.warning("[BULLY] Coordinador " + coord
                            + " caído, iniciando elección");
                    iniciarEleccion();
                }
            }
        }, "bully-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private boolean checkHeartbeat(NodoInfo coord) {
        try (Socket socket = new Socket(coord.host, coord.puertoBully)) {
            socket.setSoTimeout(Constantes.TIMEOUT_BULLY_OK_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            MensajeBully hb = new MensajeBully(
                    MensajeBully.HEARTBEAT_COORD, miId, coord.id, reloj.tick());
            out.println(hb.toJson());
            mensajesEnviados.incrementAndGet();

            String resp = in.readLine();
            if (resp != null) {
                reloj.update(MensajeBully.fromJson(resp).lamportClock);
                return true;
            }
        } catch (IOException ignored) {}
        return false;
    }
}
