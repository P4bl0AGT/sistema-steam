package com.steam.common;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Formato estable para reconstruir eventos por requestId y Lamport. */
public final class LogDistribuido {
    private LogDistribuido() {}

    public static void evento(Logger log, Level nivel, String nodo, String operacion,
                              String requestId, long lamport, String emisor,
                              String receptor, String resultado) {
        log.log(nivel, "[EVENTO] nodo=" + seguro(nodo)
                + " op=" + seguro(operacion)
                + " requestId=" + seguro(requestId)
                + " lamport=" + lamport
                + " emisor=" + seguro(emisor)
                + " receptor=" + seguro(receptor)
                + " resultado=" + seguro(resultado));
    }

    private static String seguro(String value) {
        if (value == null) return "-";
        return value.replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }
}
