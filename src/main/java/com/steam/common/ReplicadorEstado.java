package com.steam.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Replica snapshots versionados entre dos almacenamientos independientes. */
public final class ReplicadorEstado<T> {
    public record Resultado(long version, boolean confirmada, String detalle) {}

    private static final Logger LOG = Logger.getLogger(ReplicadorEstado.class.getName());
    private static final Gson GSON = new GsonBuilder().create();

    private final String servicio;
    private final int nodoId;
    private final int writerNodeId;
    private final int puertoLocal;
    private final Endpoint peer;
    private final Path archivoVersion;
    private final Class<T> tipo;
    private final Supplier<T> lectorLocal;
    private final Consumer<T> aplicadorLocal;
    private final RelojLamport reloj;
    private final AtomicLong secuencia = new AtomicLong();
    private final AtomicLong versionActual = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "replica-worker-" + servicioSeguro());
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "replica-sync-" + servicioSeguro());
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket serverSocket;

    public ReplicadorEstado(String servicio, int nodoId, int puertoLocal, Endpoint peer,
                            String pathVersion, Class<T> tipo, Supplier<T> lectorLocal,
                            Consumer<T> aplicadorLocal, RelojLamport reloj) {
        this.servicio = servicio;
        this.nodoId = nodoId;
        this.writerNodeId = Configuracion.writerNodeId(servicio);
        this.puertoLocal = puertoLocal;
        this.peer = peer;
        this.archivoVersion = Path.of(pathVersion);
        this.tipo = tipo;
        this.lectorLocal = lectorLocal;
        this.aplicadorLocal = aplicadorLocal;
        this.reloj = reloj;
        long persisted = leerVersion();
        versionActual.set(persisted);
        secuencia.set(persisted >>> 8);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread server = new Thread(this::servidorLoop, "replica-server-" + servicioSeguro());
        server.setDaemon(true);
        server.start();
        scheduler.scheduleWithFixedDelay(this::sincronizarSeguro, 2_000,
                Configuracion.replicationIntervalMs(), TimeUnit.MILLISECONDS);
        LOG.info("[REPL] servicio=" + servicio + " nodo=" + nodoId + " local=" + puertoLocal
                + " peer=" + peer + " version=" + versionActual.get());
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        workers.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    public Resultado registrarCambioLocal(T estado, String requestId) {
        if (nodoId != writerNodeId) {
            throw new IllegalStateException("Nodo " + nodoId + " no es escritor de " + servicio
                    + "; escritor configurado=" + writerNodeId);
        }
        long version = siguienteVersion(versionActual.get());
        versionActual.set(version);
        persistirVersion(version);
        boolean ack = enviarPush(estado, requestId, version);
        return new Resultado(version, ack, ack ? "ACK de replica" : "Replica pendiente");
    }

    public long getVersionActual() { return versionActual.get(); }
    public Endpoint getPeer() { return peer; }

    private void servidorLoop() {
        try (ServerSocket server = Transporte.servidor(puertoLocal)) {
            serverSocket = server;
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    workers.submit(() -> manejar(socket));
                } catch (IOException e) {
                    if (running.get()) LOG.warning("[REPL] accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running.get()) LOG.severe("[REPL] No se pudo escuchar " + puertoLocal + ": " + e.getMessage());
        }
    }

    private void manejar(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line = LineaJson.leer(in, Configuracion.maxReplicationBytes());
            MensajeReplicacion msg = MensajeReplicacion.fromJson(line);
            if (msg == null || !servicio.equals(msg.servicio) || !msg.firmaValida()) {
                out.println(respuesta(MensajeReplicacion.ERROR, "Firma/servicio invalido").toJson());
                return;
            }
            reloj.update(msg.lamportClock);
            if (MensajeReplicacion.PULL.equals(msg.tipo)) {
                MensajeReplicacion snapshot = respuesta(MensajeReplicacion.SNAPSHOT, "Estado actual");
                snapshot.version = versionActual.get();
                T estado = lectorLocal.get();
                snapshot.payloadJson = estado == null ? null : GSON.toJson(estado);
                snapshot.firmar();
                out.println(snapshot.toJson());
                return;
            }
            if (MensajeReplicacion.PUSH.equals(msg.tipo)) {
                if (msg.nodoOrigen != writerNodeId) {
                    out.println(respuesta(MensajeReplicacion.ERROR,
                            "PUSH rechazado: origen no escritor").toJson());
                    return;
                }
                aplicarSiNueva(msg);
                MensajeReplicacion ack = respuesta(MensajeReplicacion.ACK, "version=" + versionActual.get());
                ack.version = versionActual.get();
                ack.requestId = msg.requestId;
                ack.firmar();
                out.println(ack.toJson());
                return;
            }
            out.println(respuesta(MensajeReplicacion.ERROR, "Tipo desconocido").toJson());
        } catch (Exception e) {
            LOG.warning("[REPL] Mensaje invalido: " + e.getMessage());
        }
    }

    private synchronized void aplicarSiNueva(MensajeReplicacion msg) {
        if (msg.nodoOrigen != writerNodeId) {
            LOG.warning("[REPL] Snapshot rechazado de nodo no escritor=" + msg.nodoOrigen);
            return;
        }
        long actual = versionActual.get();
        if (msg.version <= actual || msg.payloadJson == null) return;
        T estado = GSON.fromJson(msg.payloadJson, tipo);
        aplicadorLocal.accept(estado);
        versionActual.set(msg.version);
        secuencia.accumulateAndGet(msg.version >>> 8, Math::max);
        persistirVersion(msg.version);
        LOG.info("[REPL] APPLY servicio=" + servicio + " nodo=" + nodoId
                + " version=" + msg.version + " requestId=" + msg.requestId);
    }

    private boolean enviarPush(T estado, String requestId, long version) {
        if (estado == null) return false;
        if (nodoId != writerNodeId) {
            LOG.warning("[REPL] Nodo secundario intento publicar estado servicio=" + servicio);
            return false;
        }
        MensajeReplicacion push = nuevo(MensajeReplicacion.PUSH);
        push.version = version;
        push.requestId = requestId;
        push.payloadJson = GSON.toJson(estado);
        push.firmar();
        try {
            MensajeReplicacion resp = enviar(push);
            return resp != null && MensajeReplicacion.ACK.equals(resp.tipo)
                    && resp.firmaValida() && resp.version >= version;
        } catch (Exception e) {
            LOG.warning("[REPL] Replica no disponible " + peer + ": " + e.getMessage());
            return false;
        }
    }

    private void sincronizarSeguro() {
        try { sincronizar(); }
        catch (Exception e) { LOG.fine("[REPL] Sync pendiente con " + peer + ": " + e.getMessage()); }
    }

    private void sincronizar() throws Exception {
        MensajeReplicacion pull = nuevo(MensajeReplicacion.PULL);
        pull.version = versionActual.get();
        pull.requestId = UUID.randomUUID().toString();
        pull.firmar();
        MensajeReplicacion remoto = enviar(pull);
        if (remoto == null || !remoto.firmaValida()) return;
        long local = versionActual.get();
        if (nodoId == writerNodeId) {
            if (local == remoto.version && local != 0L) return;
            long version = local;
            if (remoto.version >= local) {
                version = siguienteVersion(remoto.version);
                versionActual.set(version);
                persistirVersion(version);
            }
            enviarPush(lectorLocal.get(), "sync-" + UUID.randomUUID(), version);
        } else if (remoto.nodoOrigen == writerNodeId && remoto.version > local) {
            aplicarSiNueva(remoto);
        }
    }

    private long siguienteVersion(long piso) {
        long secuenciaMinima = piso >>> 8;
        long siguiente = secuencia.updateAndGet(actual -> Math.max(actual, secuenciaMinima) + 1L);
        return (siguiente << 8) | (writerNodeId & 0xffL);
    }

    private MensajeReplicacion enviar(MensajeReplicacion msg) throws Exception {
        try (Socket socket = Transporte.conectar(peer.host(), peer.puerto());
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.println(msg.toJson());
            String line = LineaJson.leer(in, Configuracion.maxReplicationBytes());
            return line == null ? null : MensajeReplicacion.fromJson(line);
        }
    }

    private MensajeReplicacion nuevo(String tipoMensaje) {
        MensajeReplicacion m = new MensajeReplicacion();
        m.tipo = tipoMensaje;
        m.servicio = servicio;
        m.nodoOrigen = nodoId;
        m.version = versionActual.get();
        m.requestId = UUID.randomUUID().toString();
        m.lamportClock = reloj.tick();
        return m;
    }

    private MensajeReplicacion respuesta(String tipoMensaje, String texto) {
        MensajeReplicacion m = nuevo(tipoMensaje);
        m.mensaje = texto;
        m.firmar();
        return m;
    }

    private long leerVersion() {
        try { return Files.exists(archivoVersion)
                ? Long.parseLong(Files.readString(archivoVersion).trim()) : 0L; }
        catch (Exception e) { return 0L; }
    }

    private synchronized void persistirVersion(long version) {
        try {
            if (archivoVersion.getParent() != null) Files.createDirectories(archivoVersion.getParent());
            Path tmp = Path.of(archivoVersion + ".tmp");
            Files.writeString(tmp, Long.toString(version), StandardCharsets.US_ASCII);
            try {
                Files.move(tmp, archivoVersion, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, archivoVersion, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("[REPL] No se pudo persistir version: " + e.getMessage());
        }
    }

    private String servicioSeguro() { return servicio == null ? "init" : servicio + "-" + nodoId; }
}
