package com.steam.models;

public class Mensaje {
    public String  id;
    public String  emisor;
    public String  receptor;
    public String  contenido;
    public long    timestamp;
    public long    lamportClock; // reloj de Lamport del emisor al momento del envío
    public int     nodoEmisor;
    public boolean entregado;
    public boolean leido;

    public Mensaje() {}

    public Mensaje(String id, String emisor, String receptor, String contenido) {
        this.id        = id;
        this.emisor    = emisor;
        this.receptor  = receptor;
        this.contenido = contenido;
        this.timestamp = System.currentTimeMillis();
        this.entregado = false;
        this.leido     = false;
    }
}
