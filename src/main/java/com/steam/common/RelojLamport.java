package com.steam.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reloj de Lamport – implementación thread-safe con AtomicLong.
 *
 * Reglas:
 *  - Evento local  → tick(): LC = LC + 1
 *  - Evento envío  → tick(): LC = LC + 1  (estampar el mensaje antes de enviarlo)
 *  - Evento recep. → update(received): LC = max(LC, received) + 1
 *
 * Al usar compareAndSet en un loop, update() es atómico sin synchronized.
 */
public class RelojLamport {

    private final AtomicLong reloj = new AtomicLong(0);
    private final String nombreNodo;

    public RelojLamport() { this("nodo"); }

    public RelojLamport(String nombreNodo) {
        this.nombreNodo = nombreNodo == null ? "nodo" : nombreNodo;
    }

    /** Evento local o de envío: incrementa y retorna el nuevo valor. */
    public long tick() {
        return reloj.incrementAndGet();
    }

    /**
     * Evento de recepción: LC = max(local, received) + 1.
     * CAS-loop garantiza atomicidad sin bloqueo.
     */
    public long update(long received) {
        long actual, actualizado;
        do {
            actual     = reloj.get();
            actualizado = Math.max(actual, received) + 1;
        } while (!reloj.compareAndSet(actual, actualizado));
        return actualizado;
    }

    /** Retorna el valor actual sin modificarlo. */
    public long get() {
        return reloj.get();
    }

    public String getNombreNodo() { return nombreNodo; }
}
