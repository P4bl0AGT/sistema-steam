package com.steam.coordinacion;

import com.google.gson.Gson;

/**
 * Mensaje del protocolo de Exclusión Mutua Centralizada.
 *
 * Tipos:
 *  REQUEST → nodo no-coordinador pide acceso al recurso
 *  GRANT   → coordinador otorga el acceso
 *  RELEASE → nodo no-coordinador libera el recurso
 */
public class MensajeMutex {

    public static final String REQUEST = "REQUEST";
    public static final String GRANT   = "GRANT";
    public static final String RELEASE = "RELEASE";

    public String tipo;
    public int    solicitanteId;
    public String recurso;
    public String requestId;
    public long   lamportClock;

    public MensajeMutex() {}

    public MensajeMutex(String tipo, int solicitanteId, String recurso,
                        String requestId, long lamportClock) {
        this.tipo          = tipo;
        this.solicitanteId = solicitanteId;
        this.recurso       = recurso;
        this.requestId     = requestId;
        this.lamportClock  = lamportClock;
    }

    private static final Gson GSON = new Gson();

    public String toJson()                           { return GSON.toJson(this); }
    public static MensajeMutex fromJson(String json) { return GSON.fromJson(json, MensajeMutex.class); }
}
