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
    private final int peerNodeId;
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
    private final AtomicBoolean listoParaEscrituras = new AtomicBoolean();
    private volatile boolean peerAlcanzable;
    private final ExecutorService workers = Ejecutores.acotado(
            "replica-worker-" + servicioSeguro(), 16, true);
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
        this.peerNodeId = nodoId == 1 ? 2 : 1;
        this.puertoLocal = puertoLocal;
        this.peer = peer;
        this.archivoVersion = Path.of(pathVersion);
        this.tipo = tipo;
        this.lectorLocal = lectorLocal;
        this.aplicadorLocal = aplicadorLocal;
        this.reloj = reloj;
        long persisted = cargarVersionLocal();
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
            throw new IllegalStateException("Nodo " + nodoId + " no es writer de " + servicio);
        }
        if (running.get() && !listoParaEscrituras.get()) {
            throw new IllegalStateException("Writer aun no reconciliado con su replica");
        }
        long version;
        String payloadJson;
        synchronized (this) {
            long versionEstado = versionEstado(estado);
            if (versionEstado > 0L && versionEstado != versionActual.get()) {
                throw new IllegalStateException("Snapshot local desfasado: estado=" + versionEstado
                        + " version=" + versionActual.get());
            }
            version = siguienteVersion(versionActual.get());
            asignarVersion(estado, version);
            aplicadorLocal.accept(estado);
            versionActual.set(version);
            persistirVersion(version);
            payloadJson = estado != null ? GSON.toJson(estado) : null;
        }
        boolean ack = enviarPushPreparado(payloadJson, requestId, version);
        if (ack) peerAlcanzable = true;
        return new Resultado(version, ack, ack ? "ACK de replica" : "Replica pendiente");
    }

    public long getVersionActual() { return versionActual.get(); }
    public Endpoint getPeer() { return peer; }
    public boolean isPeerAlcanzable() { return peerAlcanzable; }
    public boolean isListoParaEscrituras() {
        return nodoId != writerNodeId || !running.get() || listoParaEscrituras.get();
    }

    private void servidorLoop() {
        try (ServerSocket server = Transporte.servidor(puertoLocal)) {
            serverSocket = server;
            while (running.get()) {
                try {
                    Socket socket = Transporte.aceptar(server);
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
            if (msg == null || !servicio.equals(msg.servicio) || !msg.firmaValida()
                    || msg.nodoOrigen != peerNodeId || !msg.esFresco()
                    || !SeguridadMensajes.aceptarUnaVez("replica-" + servicio,
                    msg.tipo + "|" + msg.nodoOrigen + "|" + msg.requestId, msg.timestamp)) {
                out.println(respuesta(MensajeReplicacion.ERROR, "Firma/servicio invalido").toJson());
                return;
            }
            reloj.update(msg.lamportClock);
            if (MensajeReplicacion.PULL.equals(msg.tipo)) {
                MensajeReplicacion snapshot = respuesta(MensajeReplicacion.SNAPSHOT, "Estado actual");
                snapshot.requestId = msg.requestId;
                snapshot.version = versionActual.get();
                T estado = lectorLocal.get();
                snapshot.payloadJson = estado == null ? null : GSON.toJson(estado);
                snapshot.firmar();
                out.println(snapshot.toJson());
                return;
            }
            if (MensajeReplicacion.PUSH.equals(msg.tipo)) {
                if (msg.nodoOrigen != writerNodeId || !versionPerteneceAlWriter(msg.version)) {
                    out.println(respuesta(MensajeReplicacion.ERROR, "Origen no autorizado").toJson());
                    return;
                }
                if (!aplicarPush(msg)) {
                    out.println(respuesta(MensajeReplicacion.ERROR,
                            "Conflicto de version o snapshot").toJson());
                    return;
                }
                MensajeReplicacion ack = respuesta(MensajeReplicacion.ACK, "version=" + msg.version);
                ack.version = msg.version;
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

    private synchronized boolean aplicarPush(MensajeReplicacion msg) {
        long actual = versionActual.get();
        if (msg.payloadJson == null) return false;
        if (msg.version < actual) return false;
        if (msg.version == actual) {
            T local = lectorLocal.get();
            return local != null && SeguridadMensajes.sha256Texto(GSON.toJson(local))
                    .equals(SeguridadMensajes.sha256Texto(msg.payloadJson));
        }
        T estado = estadoRemotoValido(msg);
        if (estado == null) return false;
        aplicadorLocal.accept(estado);
        versionActual.set(msg.version);
        secuencia.accumulateAndGet(msg.version >>> 8, Math::max);
        persistirVersion(msg.version);
        LOG.info("[REPL] APPLY servicio=" + servicio + " nodo=" + nodoId
                + " version=" + msg.version + " requestId=" + msg.requestId);
        return true;
    }

    private boolean enviarPush(T estado, String requestId, long version) {
        if (estado == null) return false;
        return enviarPushPreparado(GSON.toJson(estado), requestId, version);
    }

    private boolean enviarPushPreparado(String payloadJson, String requestId, long version) {
        if (payloadJson == null) return false;
        MensajeReplicacion push = nuevo(MensajeReplicacion.PUSH);
        push.version = version;
        push.requestId = requestId;
        push.payloadJson = payloadJson;
        push.firmar();
        try {
            MensajeReplicacion resp = enviar(push);
            return resp != null && MensajeReplicacion.ACK.equals(resp.tipo)
                    && servicio.equals(resp.servicio) && resp.nodoOrigen == peerNodeId
                    && resp.firmaValida() && resp.esFresco() && resp.version == version
                    && java.util.Objects.equals(resp.requestId, requestId);
        } catch (Exception e) {
            LOG.warning("[REPL] Replica no disponible " + peer + ": " + e.getMessage());
            return false;
        }
    }

    private void sincronizarSeguro() {
        try { sincronizar(); }
        catch (Exception e) { peerAlcanzable = false; LOG.fine("[REPL] Sync pendiente con " + peer + ": " + e.getMessage()); }
    }

    private void sincronizar() throws Exception {
        MensajeReplicacion pull = nuevo(MensajeReplicacion.PULL);
        pull.version = versionActual.get();
        pull.requestId = UUID.randomUUID().toString();
        pull.firmar();
        MensajeReplicacion remoto = enviar(pull);
        if (remoto == null || !MensajeReplicacion.SNAPSHOT.equals(remoto.tipo)
                || !servicio.equals(remoto.servicio) || !remoto.firmaValida() || !remoto.esFresco()
                || remoto.nodoOrigen != peerNodeId || !java.util.Objects.equals(remoto.requestId, pull.requestId)
                || !versionAceptable(remoto.version)) {
            peerAlcanzable = false;
            return;
        }
        peerAlcanzable = true;

        long local;
        String snapshotJson = null;
        long versionPush = 0;
        int accion = 0; // 0=nada, 1=aplicar nuevo, 2=push local, 3=push init, 4=reparar local

        synchronized (this) {
            local = versionActual.get();
            T estadoLocal = lectorLocal.get();
            boolean snapshotDesfasado = estadoLocal == null
                    || (estadoLocal instanceof EstadoVersionado
                    && versionEstado(estadoLocal) != local);
            if (snapshotDesfasado && remoto.version >= local && remoto.payloadJson != null) {
                accion = 4;
            } else if (remoto.version > local && remoto.payloadJson != null) {
                accion = 1;
            } else if (local > remoto.version && nodoId == writerNodeId) {
                snapshotJson = GSON.toJson(lectorLocal.get());
                versionPush = local;
                accion = 2;
            } else if (local == remoto.version && local == 0L && nodoId == writerNodeId) {
                T estado = lectorLocal.get();
                if (estado != null) {
                    versionPush = siguienteVersion(0L);
                    asignarVersion(estado, versionPush);
                    aplicadorLocal.accept(estado);
                    versionActual.set(versionPush);
                    persistirVersion(versionPush);
                    snapshotJson = GSON.toJson(estado);
                    accion = 3;
                }
            }
        }

        boolean reconciliado = true;
        switch (accion) {
            case 1 -> reconciliado = aplicarRemotoSiNuevo(remoto);
            case 2 -> reconciliado = enviarPushPreparado(snapshotJson,
                    "sync-" + UUID.randomUUID(), versionPush);
            case 3 -> reconciliado = enviarPushPreparado(snapshotJson,
                    "sync-init-" + UUID.randomUUID(), versionPush);
            case 4 -> reconciliado = aplicarRecuperacion(remoto);
            default -> reconciliado = snapshotsIguales(remoto);
        }
        if (nodoId == writerNodeId) {
            listoParaEscrituras.set(reconciliado);
        }
    }

    private synchronized boolean aplicarRecuperacion(MensajeReplicacion msg) {
        if (msg.version < versionActual.get() || msg.payloadJson == null) return false;
        T estado = estadoRemotoValido(msg);
        if (estado == null) return false;
        aplicadorLocal.accept(estado);
        versionActual.set(msg.version);
        secuencia.accumulateAndGet(msg.version >>> 8, Math::max);
        persistirVersion(msg.version);
        LOG.warning("[REPL] Snapshot local reparado desde peer version=" + msg.version);
        return true;
    }

    private synchronized boolean aplicarRemotoSiNuevo(MensajeReplicacion msg) {
        if (msg.version <= versionActual.get()) return snapshotsIguales(msg);
        T estado = estadoRemotoValido(msg);
        if (estado == null) return false;
        aplicadorLocal.accept(estado);
        versionActual.set(msg.version);
        secuencia.accumulateAndGet(msg.version >>> 8, Math::max);
        persistirVersion(msg.version);
        return true;
    }

    private T estadoRemotoValido(MensajeReplicacion msg) {
        if (!versionPerteneceAlWriter(msg.version) || msg.payloadJson == null) return null;
        T estado = GSON.fromJson(msg.payloadJson, tipo);
        if (estado instanceof EstadoVersionado && versionEstado(estado) != msg.version) return null;
        asignarVersion(estado, msg.version);
        return estado;
    }

    private boolean snapshotsIguales(MensajeReplicacion remoto) {
        if (remoto.version != versionActual.get()) return false;
        T local = lectorLocal.get();
        if (local == null || remoto.payloadJson == null) return local == null && remoto.payloadJson == null;
        return SeguridadMensajes.sha256Texto(GSON.toJson(local))
                .equals(SeguridadMensajes.sha256Texto(remoto.payloadJson));
    }

    private boolean versionAceptable(long version) {
        return version == 0L || versionPerteneceAlWriter(version);
    }

    private boolean versionPerteneceAlWriter(long version) {
        return version > 0L && (int) (version & 0xffL) == writerNodeId;
    }

    private long siguienteVersion(long piso) {
        long secuenciaMinima = piso >>> 8;
        long siguiente = secuencia.updateAndGet(actual -> Math.max(actual, secuenciaMinima) + 1L);
        return (siguiente << 8) | (nodoId & 0xffL);
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
        m.timestamp = System.currentTimeMillis();
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

    private long cargarVersionLocal() {
        T estado = lectorLocal.get();
        long embebida = versionEstado(estado);
        // Los estados nuevos llevan la version dentro de Main.json. Un VERSION.txt
        // legado no puede demostrar que corresponda al mismo snapshot y se ignora.
        if (estado instanceof EstadoVersionado) return embebida;
        return leerVersion();
    }

    private static long versionEstado(Object estado) {
        return estado instanceof EstadoVersionado versionado
                ? versionado.getReplicationVersion() : 0L;
    }

    private static void asignarVersion(Object estado, long version) {
        if (estado instanceof EstadoVersionado versionado) {
            versionado.setReplicationVersion(version);
        }
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
