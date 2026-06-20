package com.steam.tests;

import com.steam.common.CacheIdempotencia;
import com.steam.common.MensajeProtocolo;
import com.steam.common.OrdenMensajes;
import com.steam.common.RelojLamport;
import com.steam.common.SeguridadMensajes;
import com.steam.models.Mensaje;
import com.steam.models.Sesion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PruebasComponentes {
    private static int pruebas;

    public static void main(String[] args) throws Exception {
        probarLamportConcurrente();
        probarIdempotenciaConcurrente();
        probarFirmaControl();
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
        SeguridadMensajes.firmarControl(req);
        check(SeguridadMensajes.validarControl(req), "Firma valida");
        req.setOperacion("ALTERADA");
        check(!SeguridadMensajes.validarControl(req), "Firma detecta alteracion");
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
