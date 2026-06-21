package com.steam.common;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Pools acotados con backpressure para canales TCP. */
public final class Ejecutores {
    private Ejecutores() {}

    public static ExecutorService acotado(String prefijo, int hilos, boolean daemon) {
        AtomicInteger secuencia = new AtomicInteger();
        ThreadFactory factory = tarea -> {
            Thread thread = new Thread(tarea, prefijo + "-" + secuencia.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
        return new ThreadPoolExecutor(hilos, hilos, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(hilos * 4), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
