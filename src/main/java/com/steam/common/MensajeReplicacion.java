package com.steam.common;

import com.google.gson.Gson;

/** Sobre JSON del canal de replicacion primaria-secundaria. */
public class MensajeReplicacion {
    public static final String PUSH = "PUSH";
    public static final String PULL = "PULL";
    public static final String SNAPSHOT = "SNAPSHOT";
    public static final String ACK = "ACK";
    public static final String ERROR = "ERROR";

    public String tipo;
    public String servicio;
    public int nodoOrigen;
    public long version;
    public String requestId;
    public String payloadJson;
    public long lamportClock;
    public long timestamp;
    public String firma;
    public String mensaje;
    public java.util.Map<String, String> requestIdsRecientes;

    private static final Gson GSON = new Gson();

    public String contenidoFirmable() {
        String payloadHash = payloadJson == null ? "-" : SeguridadMensajes.sha256Texto(payloadJson);
        String idsHash = requestIdsRecientes == null || requestIdsRecientes.isEmpty()
                ? "-" : SeguridadMensajes.sha256Texto(new Gson().toJson(
                        new java.util.TreeMap<>(requestIdsRecientes)));
        return tipo + "|" + servicio + "|" + nodoOrigen + "|" + version + "|"
                + requestId + "|" + lamportClock + "|" + timestamp + "|" + payloadHash
                + "|" + idsHash;
    }

    public void firmar() { firma = SeguridadMensajes.firmarTexto(contenidoFirmable()); }
    public boolean firmaValida() { return SeguridadMensajes.validarTexto(contenidoFirmable(), firma); }
    public boolean esFresco() { return SeguridadMensajes.esTimestampFresco(timestamp); }
    public String toJson() { return GSON.toJson(this); }
    public static MensajeReplicacion fromJson(String json) {
        return GSON.fromJson(json, MensajeReplicacion.class);
    }
}
