package com.steam.tests;

import com.steam.common.CacheIdempotencia;
import com.steam.common.Endpoint;
import com.steam.common.EstadoVersionado;
import com.steam.common.MensajeReplicacion;
import com.steam.common.MensajeProtocolo;
import com.steam.common.OrdenMensajes;
import com.steam.common.RelojLamport;
import com.steam.common.ReplicadorEstado;
import com.steam.common.SeguridadMensajes;
import com.steam.carga.AcumuladorCoordinacion;
import com.steam.models.Mensaje;
import com.steam.models.Sesion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;

public final class PruebasComponentes {
    private static int pruebas;

    public static void main(String[] args) throws Exception {
        probarLamportConcurrente();
        probarIdempotenciaConcurrente();
        probarIdempotenciaPersistente();
        probarConflictoRequestId();
        probarFirmaControl();
        probarFirmaReplicacionFuerte();
        probarEscritorUnicoReplica();
        probarVersionEmbebida();
        probarAcumuladorCoordinacion();
        probarExpiracionSesion();
        probarOrdenDeterminista();
        System.out.println("OK pruebas_componentes=" + pruebas);
    }

    private static void probarLamportConcurrente() throws Exception {
        RelojLamport reloj = new RelojLamport("test");
        int hilos = 20;
        int ticks = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch inicio = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(hilos);
        for (int i = 0; i < hilos; i++) pool.submit(() -> {
            try { inicio.await(); for (int j = 0; j < ticks; j++) reloj.tick(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { fin.countDown(); }
        });
        inicio.countDown();
        check(fin.await(10, TimeUnit.SECONDS), "Lamport termino");
        check(reloj.get() == (long) hilos * ticks, "Lamport no pierde incrementos");
        check(reloj.update(100_000L) == 100_001L, "Lamport update=max+1");
        pool.shutdownNow();
    }

    private static void probarIdempotenciaConcurrente() throws Exception {
        CacheIdempotencia cache = new CacheIdempotencia();
        AtomicInteger ejecuciones = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<java.util.concurrent.Future<MensajeProtocolo>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) futures.add(pool.submit(() -> cache.ejecutar("igual", () -> {
            ejecuciones.incrementAndGet();
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return MensajeProtocolo.ok("igual", "una vez");
        })));
        for (var future : futures) check(future.get().isOk(), "Respuesta deduplicada OK");
        check(ejecuciones.get() == 1, "requestId se ejecuta una sola vez");
        pool.shutdownNow();
    }

    private static void probarIdempotenciaPersistente() throws Exception {
        var archivo = Files.createTempDirectory("steam-idempotencia-test").resolve("cache.json");
        MensajeProtocolo req = MensajeProtocolo.request("COBRO", "token").put("monto", 10);
        AtomicInteger ejecuciones = new AtomicInteger();
        CacheIdempotencia primeraJvm = new CacheIdempotencia(archivo.toString());
        check(primeraJvm.ejecutar(req, () -> {
            ejecuciones.incrementAndGet();
            return MensajeProtocolo.ok(req.getRequestId(), "aplicado");
        }).isOk(), "Operacion durable inicial OK");
        CacheIdempotencia segundaJvm = new CacheIdempotencia(archivo.toString());
        check(segundaJvm.ejecutar(req, () -> {
            ejecuciones.incrementAndGet();
            return MensajeProtocolo.ok(req.getRequestId(), "duplicado");
        }).isOk(), "Respuesta durable recuperada");
        check(ejecuciones.get() == 1, "Reinicio no repite operacion durable");
    }

    private static void probarFirmaControl() {
        MensajeProtocolo req = MensajeProtocolo.request("CONTROL_TEST", null);
        req.setEmisor("test");
        req.setReceptor("proxy");
        req.setLamportClock(3L);
        req.put("host", "localhost").put("puerto", 8080).put("cluster", "JUEGOS");
        SeguridadMensajes.firmarControl(req);
        MensajeProtocolo recibido = MensajeProtocolo.fromJson(req.toJson());
        check(SeguridadMensajes.validarControl(recibido), "Firma valida tras serializar");
        recibido.put("puerto", 9999);
        check(!SeguridadMensajes.validarControl(recibido), "Firma detecta payload alterado");
        recibido = MensajeProtocolo.fromJson(req.toJson());
        recibido.setOperacion("ALTERADA");
        check(!SeguridadMensajes.validarControl(recibido), "Firma detecta sobre alterado");
        recibido = MensajeProtocolo.fromJson(req.toJson());
        check(!SeguridadMensajes.validarControl(recibido), "Replay de control rechazado");
    }

    private static void probarConflictoRequestId() {
        CacheIdempotencia cache = new CacheIdempotencia();
        AtomicInteger ejecuciones = new AtomicInteger();
        MensajeProtocolo a = MensajeProtocolo.request("A", null);
        MensajeProtocolo b = MensajeProtocolo.request("B", null);
        b.setRequestId(a.getRequestId());
        check(cache.ejecutar(a, () -> {
            ejecuciones.incrementAndGet();
            return MensajeProtocolo.ok(a.getRequestId(), "ok");
        }).isOk(), "Primera solicitud se ejecuta");
        MensajeProtocolo conflicto = cache.ejecutar(b, () -> {
            ejecuciones.incrementAndGet();
            return MensajeProtocolo.ok(b.getRequestId(), "no");
        });
        check("REQUEST_ID_CONFLICT".equals(conflicto.getCodigoError()),
                "requestId no admite contenido distinto");
        check(ejecuciones.get() == 1, "Conflicto no ejecuta la operacion");
    }

    private static void probarFirmaReplicacionFuerte() {
        MensajeReplicacion a = new MensajeReplicacion();
        MensajeReplicacion b = new MensajeReplicacion();
        a.tipo = b.tipo = MensajeReplicacion.PUSH;
        a.servicio = b.servicio = "TEST";
        a.nodoOrigen = b.nodoOrigen = 1;
        a.version = b.version = 257L;
        a.requestId = b.requestId = "r";
        a.lamportClock = b.lamportClock = 1L;
        a.payloadJson = "FB";
        b.payloadJson = "Ea"; // Colisión conocida de String.hashCode().
        check(a.payloadJson.hashCode() == b.payloadJson.hashCode(), "Precondicion colision Java");
        check(!a.contenidoFirmable().equals(b.contenidoFirmable()),
                "SHA-256 distingue payloads con igual hashCode");
        a.timestamp = System.currentTimeMillis();
        a.firmar();
        check(a.firmaValida() && a.esFresco(), "Sobre de replica vigente");
        a.timestamp = System.currentTimeMillis() - 120_000L;
        a.firmar();
        check(!a.esFresco(), "Sobre de replica expirado");
    }

    private static void probarEscritorUnicoReplica() throws Exception {
        var dir = Files.createTempDirectory("steam-repl-test");
        ReplicadorEstado<String> replica = new ReplicadorEstado<>("JUEGOS", 2, 0,
                new Endpoint("localhost", 1), dir.resolve("version.txt").toString(),
                String.class, () -> "estado", ignored -> {}, new RelojLamport("repl-test"));
        boolean rechazada = false;
        try { replica.registrarCambioLocal("estado", "r"); }
        catch (IllegalStateException e) { rechazada = true; }
        finally { replica.stop(); }
        check(rechazada, "Replica secundaria no publica escrituras");
    }

    private static void probarVersionEmbebida() throws Exception {
        var dir = Files.createTempDirectory("steam-version-test");
        EstadoPrueba estado = new EstadoPrueba();
        java.util.concurrent.atomic.AtomicReference<EstadoPrueba> guardado =
                new java.util.concurrent.atomic.AtomicReference<>(estado);
        ReplicadorEstado<EstadoPrueba> replica = new ReplicadorEstado<>("JUEGOS", 1, 0,
                new Endpoint("localhost", 1), dir.resolve("version.txt").toString(),
                EstadoPrueba.class, guardado::get, guardado::set, new RelojLamport("version-test"));
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(ReplicadorEstado.class.getName());
        java.util.logging.Level nivelAnterior = log.getLevel();
        try {
            log.setLevel(java.util.logging.Level.OFF);
            ReplicadorEstado.Resultado resultado = replica.registrarCambioLocal(estado, "version-r");
            check(resultado.version() > 0L, "Writer genera version");
            check(estado.getReplicationVersion() == resultado.version(),
                    "Version viaja dentro del snapshot");
        } finally {
            log.setLevel(nivelAnterior);
            replica.stop();
        }
    }

    public static final class EstadoPrueba implements EstadoVersionado {
        private long replicationVersion;
        @Override public long getReplicationVersion() { return replicationVersion; }
        @Override public void setReplicationVersion(long version) { replicationVersion = version; }
    }

    private static void probarAcumuladorCoordinacion() {
        AcumuladorCoordinacion acumulador = new AcumuladorCoordinacion();
        acumulador.observar(10, 20);
        acumulador.observar(13, 25);
        acumulador.observar(2, 3); // Reinicio de la JVM.
        AcumuladorCoordinacion.Snapshot snapshot = acumulador.snapshot();
        check(snapshot.bully() == 15, "Bully conserva contador previo al reinicio");
        check(snapshot.mutex() == 28, "Mutex conserva contador previo al reinicio");
    }

    private static void probarExpiracionSesion() {
        Sesion sesion = new Sesion("t", "u", "r");
        check(!"t".equals(sesion.token), "Token no se persiste en claro");
        check(sesion.coincideToken("t"), "Hash de token valida el bearer original");
        sesion.token = "legacy";
        check(sesion.coincideToken("legacy"), "Token legado sigue siendo migrable");
        sesion.creadoEn = System.currentTimeMillis() - 10_000L;
        check(!sesion.vigente(1_000L), "Sesion expira");
        sesion.creadoEn = System.currentTimeMillis();
        check(sesion.vigente(1_000L), "Sesion reciente vigente");
    }

    private static void probarOrdenDeterminista() {
        Mensaje b = new Mensaje("b", "u", "v", "b");
        Mensaje a = new Mensaje("a", "u", "v", "a");
        a.lamportClock = b.lamportClock = 7;
        a.nodoEmisor = 1;
        b.nodoEmisor = 2;
        List<Mensaje> mensajes = new ArrayList<>(List.of(b, a));
        mensajes.sort(OrdenMensajes.COMPARATOR);
        check("a".equals(mensajes.get(0).id), "Desempate Lamport estable");
    }

    private static void check(boolean condicion, String mensaje) {
        pruebas++;
        if (!condicion) throw new AssertionError(mensaje);
    }
}
