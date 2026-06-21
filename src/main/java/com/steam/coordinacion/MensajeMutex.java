package com.steam.coordinacion;

import com.google.gson.Gson;
import com.steam.common.SeguridadMensajes;

/** Mensaje autenticado del mutex centralizado con propiedad y lease. */
public class MensajeMutex {
    public static final String REQUEST = "REQUEST";
    public static final String GRANT = "GRANT";
    public static final String RELEASE = "RELEASE";
    public static final String RELEASED = "RELEASED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String REDIRECT = "REDIRECT";
    public static final String DENIED = "DENIED";

    public String tipo;
    public int solicitanteId;
    public int coordinadorId;
    public String recurso;
    public String requestId;
    public long lamportClock;
    public long leaseUntil;
    public long timestamp;
    public String firma;

    public MensajeMutex() {}

    public MensajeMutex(String tipo, int solicitanteId, String recurso,
                        String requestId, long lamportClock) {
        this.tipo = tipo;
        this.solicitanteId = solicitanteId;
        this.recurso = recurso;
        this.requestId = requestId;
        this.lamportClock = lamportClock;
        this.timestamp = System.currentTimeMillis();
    }

    public void firmar() { firma = SeguridadMensajes.firmarTexto(baseFirma()); }
    public boolean firmaValida() { return SeguridadMensajes.validarTexto(baseFirma(), firma); }
    public boolean esFresco() { return SeguridadMensajes.esTimestampFresco(timestamp); }

    private String baseFirma() {
        return tipo + "|" + solicitanteId + "|" + coordinadorId + "|" + recurso + "|"
                + requestId + "|" + lamportClock + "|" + leaseUntil + "|" + timestamp;
    }

    private static final Gson GSON = new Gson();
    public String toJson() { return GSON.toJson(this); }
    public static MensajeMutex fromJson(String json) { return GSON.fromJson(json, MensajeMutex.class); }
}
