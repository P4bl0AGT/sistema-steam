package com.steam.common;

import com.steam.models.Mensaje;

import java.util.Comparator;

/** Orden total determinista compatible con el orden causal de Lamport. */
public final class OrdenMensajes {
    private OrdenMensajes() {}

    public static final Comparator<Mensaje> COMPARATOR = Comparator
            .comparingLong((Mensaje m) -> m.lamportClock > 0 ? m.lamportClock : m.timestamp)
            .thenComparingInt(m -> m.nodoEmisor)
            .thenComparing(m -> m.id);
}
