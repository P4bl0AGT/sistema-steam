package com.steam.coordinacion;

import com.steam.common.Configuracion;
import com.steam.common.LineaJson;
import com.steam.common.RelojLamport;
import com.steam.common.Transporte;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Mutex centralizado con propietario verificable y recuperacion por lease. */
public final class GestorMutexCentralizado {
    public record LockHandle(String recurso, String requestId, int coordinadorId, long leaseUntil) {}

    private static final Logger LOG = Logger.getLogger(GestorMutexCentralizado.class.getName());

    private static final class EstadoLock {
        private int propietario = -1;
        private String requestId;
        private long leaseUntil;

        synchronized boolean adquirir(int nodo, String id, long timeoutMs, long leaseMs)
                throws InterruptedException {
            long limite = System.currentTimeMillis() + timeoutMs;
            while (ocupado()) {
                long espera = Math.min(limite - System.currentTimeMillis(),
                        Math.max(1L, leaseUntil - System.currentTimeMillis()));
                if (espera <= 0L) return false;
                wait(espera);
            }
            propietario = nodo;
            requestId = id;
            leaseUntil = System.currentTimeMillis() + leaseMs;
            return true;
        }

        synchronized boolean liberar(int nodo, String id) {
            if (!ocupado() || propietario != nodo || !java.util.Objects.equals(requestId, id)) return false;
            propietario = -1;
            requestId = null;
            leaseUntil = 0L;
            notifyAll();
            return true;
        }

        synchronized long leaseUntil() { ocupado(); return leaseUntil; }

        private boolean ocupado() {
            if (propietario != -1 && System.currentTimeMillis() >= leaseUntil) {
                propietario = -1;
                requestId = null;
                leaseUntil = 0L;
                notifyAll();
            }
            return propietario != -1;
        }
    }

    private final int miId;
    private final int miPuertoMutex;
    private final GestorBully bully;
    private final RelojLamport reloj;
    private final List<NodoInfo> peers;
    private final Map<String, EstadoLock> locks = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong mensajesEnviados = new AtomicLong();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mutex-worker-" + miIdSeguro());
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket serverSocket;

    public GestorMutexCentralizado(int miId, int miPuertoMutex, GestorBully bully,
                                   RelojLamport reloj, List<NodoInfo> peers) {
        this.miId = miId;
        this.miPuertoMutex = miPuertoMutex;
        this.bully = bully;
        this.reloj = reloj;
        this.peers = List.copyOf(peers);
    }

    public void startServidor() {
        Thread t = new Thread(this::servidorLoop, "mutex-server-" + miId);
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped.set(true);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    public long getMensajesCoordinacion() { return mensajesEnviados.get(); }

    public LockHandle requestLock(String recurso, int solicitanteId) {
        int coordinador = bully.getCoordinadorActual();
        if (coordinador < 1) throw new MutexTimeoutException("Coordinador aun no elegido");
        String requestId = UUID.randomUUID().toString();
        MensajeMutex req = mensaje(MensajeMutex.REQUEST, solicitanteId, coordinador, recurso, requestId);
        MensajeMutex resp = enviar(coordinador, req, true);
        if (!MensajeMutex.GRANT.equals(resp.tipo) || resp.coordinadorId != coordinador
                || !requestId.equals(resp.requestId)) {
            throw new MutexTimeoutException("Lock no otorgado: " + resp.tipo);
        }
        return new LockHandle(recurso, requestId, coordinador, resp.leaseUntil);
    }

    public boolean lockVigente(LockHandle handle) {
        return handle != null && System.currentTimeMillis() < handle.leaseUntil()
                && bully.getCoordinadorActual() == handle.coordinadorId();
    }

    public void releaseLock(LockHandle handle, int solicitanteId) {
        if (handle == null) return;
        MensajeMutex req = mensaje(MensajeMutex.RELEASE, solicitanteId,
                handle.coordinadorId(), handle.recurso(), handle.requestId());
        try { enviar(handle.coordinadorId(), req, false); }
        catch (MutexTimeoutException e) { LOG.warning("[MUTEX] RELEASE pendiente: " + e.getMessage()); }
    }

    private void servidorLoop() {
        try (ServerSocket server = Transporte.servidor(miPuertoMutex)) {
            serverSocket = server;
            LOG.info("[MUTEX] nodo=" + miId + " puerto=" + miPuertoMutex);
            while (!stopped.get()) {
                try {
                    Socket socket = server.accept();
                    pool.submit(() -> manejar(socket));
                } catch (IOException e) {
                    if (!stopped.get()) LOG.warning("[MUTEX] accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!stopped.get()) LOG.severe("[MUTEX] No pudo escuchar: " + e.getMessage());
        }
    }

    private void manejar(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line = LineaJson.leer(in, Configuracion.maxMessageBytes());
            MensajeMutex req = line == null ? null : MensajeMutex.fromJson(line);
            if (req == null || !req.firmaValida()) {
                out.println(respuesta(MensajeMutex.DENIED, req, 0L).toJson());
                return;
            }
            reloj.update(req.lamportClock);
            if (!bully.isCoordinador()) {
                out.println(respuesta(MensajeMutex.REDIRECT, req, 0L).toJson());
                return;
            }
            if (MensajeMutex.REQUEST.equals(req.tipo)) manejarRequest(req, out);
            else if (MensajeMutex.RELEASE.equals(req.tipo)) manejarRelease(req, out);
            else out.println(respuesta(MensajeMutex.DENIED, req, 0L).toJson());
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) LOG.warning("[MUTEX] Mensaje invalido: " + e.getMessage());
            else Thread.currentThread().interrupt();
        }
    }

    private void manejarRequest(MensajeMutex req, PrintWriter out) throws InterruptedException {
        EstadoLock estado = locks.computeIfAbsent(req.recurso, ignored -> new EstadoLock());
        boolean adquirido = estado.adquirir(req.solicitanteId, req.requestId,
                Configuracion.getLong("steam.mutex.acquire.timeout.ms", 10_000L),
                Configuracion.mutexLeaseMs());
        MensajeMutex resp = respuesta(adquirido ? MensajeMutex.GRANT : MensajeMutex.TIMEOUT,
                req, adquirido ? estado.leaseUntil() : 0L);
        out.println(resp.toJson());
        mensajesEnviados.incrementAndGet();
        LOG.info("[MUTEX] tipo=" + resp.tipo + " recurso=" + req.recurso
                + " propietario=" + req.solicitanteId + " requestId=" + req.requestId);
    }

    private void manejarRelease(MensajeMutex req, PrintWriter out) {
        EstadoLock estado = locks.get(req.recurso);
        boolean liberado = estado != null && estado.liberar(req.solicitanteId, req.requestId);
        MensajeMutex resp = respuesta(liberado ? MensajeMutex.RELEASED : MensajeMutex.DENIED, req, 0L);
        out.println(resp.toJson());
        mensajesEnviados.incrementAndGet();
    }

    private MensajeMutex enviar(int coordinador, MensajeMutex req, boolean estricto) {
        NodoInfo destino = nodo(coordinador);
        try (Socket socket = Transporte.conectar(destino.host, destino.puertoMutex);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.println(req.toJson());
            mensajesEnviados.incrementAndGet();
            String line = LineaJson.leer(in, Configuracion.maxMessageBytes());
            MensajeMutex resp = line == null ? null : MensajeMutex.fromJson(line);
            if (resp == null || !resp.firmaValida()) throw new IOException("Respuesta sin firma valida");
            reloj.update(resp.lamportClock);
            return resp;
        } catch (IOException e) {
            if (!estricto) throw new MutexTimeoutException(e.getMessage());
            throw new MutexTimeoutException("Coordinador nodo-" + coordinador + " no alcanzable: " + e.getMessage());
        }
    }

    private NodoInfo nodo(int id) {
        if (id == miId) return new NodoInfo(miId, Configuracion.advertisedHost(), 0, miPuertoMutex);
        return peers.stream().filter(p -> p.id == id).findFirst()
                .orElseThrow(() -> new MutexTimeoutException("Nodo coordinador desconocido: " + id));
    }

    private MensajeMutex mensaje(String tipo, int solicitante, int coordinador,
                                 String recurso, String requestId) {
        MensajeMutex msg = new MensajeMutex(tipo, solicitante, recurso, requestId, reloj.tick());
        msg.coordinadorId = coordinador;
        msg.firmar();
        return msg;
    }

    private MensajeMutex respuesta(String tipo, MensajeMutex req, long leaseUntil) {
        MensajeMutex resp = new MensajeMutex(tipo, req == null ? -1 : req.solicitanteId,
                req == null ? "?" : req.recurso, req == null ? "?" : req.requestId, reloj.tick());
        resp.coordinadorId = bully.getCoordinadorActual();
        resp.leaseUntil = leaseUntil;
        resp.firmar();
        return resp;
    }

    private String miIdSeguro() { return String.valueOf(miId); }
}
