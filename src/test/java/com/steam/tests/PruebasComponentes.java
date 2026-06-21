package com.steam.tests;

import com.steam.common.CacheIdempotencia;
import com.steam.common.Endpoint;
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
        probarFirmaControl();
        probarFirmaReplicacionFuerte();
        probarEscritorUnicoReplica();
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
        sesion.ultimaActividad = System.currentTimeMillis() - 10_000L;
        check(!sesion.vigente(1_000L), "Sesion expira");
        sesion.ultimaActividad = System.currentTimeMillis();
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
