package com.steam.coordinacion;

import com.google.gson.Gson;
import com.steam.common.SeguridadMensajes;

/**
 * Mensaje del protocolo Bully intercambiado entre nodos de svJuegos
 * a través de sus puertos dedicados (9082 / 9282).
 *
 * Tipos:
 *  ELECTION       → nodo de menor ID inicia elección hacia el mayor
 *  OK             → nodo mayor responde "yo me encargo"
 *  COORDINATOR    → nodo ganador anuncia que es el coordinador
 *  HEARTBEAT_COORD→ no-coordinador verifica si el coordinador sigue vivo
 */
public class MensajeBully {

    public static final String ELECTION        = "ELECTION";
    public static final String OK              = "OK";
    public static final String COORDINATOR     = "COORDINATOR";
    public static final String HEARTBEAT_COORD = "HEARTBEAT_COORD";

    public String tipo;
    public int    emisorId;
    public int    coordinadorId; // relevante solo en COORDINATOR
    public long   lamportClock;
    public String firma;

    public MensajeBully() {}

    public MensajeBully(String tipo, int emisorId, int coordinadorId, long lamportClock) {
        this.tipo          = tipo;
        this.emisorId      = emisorId;
        this.coordinadorId = coordinadorId;
        this.lamportClock  = lamportClock;
    }

    private static final Gson GSON = new Gson();

    public void firmar() {
        firma = SeguridadMensajes.firmarTexto(baseFirma());
    }

    public boolean firmaValida() {
        return SeguridadMensajes.validarTexto(baseFirma(), firma);
    }

    private String baseFirma() {
        return tipo + "|" + emisorId + "|" + coordinadorId + "|" + lamportClock;
    }

    public String toJson()                           { return GSON.toJson(this); }
    public static MensajeBully fromJson(String json) { return GSON.fromJson(json, MensajeBully.class); }
}
