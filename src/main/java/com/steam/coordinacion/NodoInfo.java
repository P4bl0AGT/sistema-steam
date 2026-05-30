package com.steam.coordinacion;

/** Referencia mínima a un nodo par usado por GestorBully. */
public class NodoInfo {
    public final int    id;
    public final String host;
    public final int    puertoBully;
    public final int    puertoMutex;

    public NodoInfo(int id, String host, int puertoBully, int puertoMutex) {
        this.id          = id;
        this.host        = host;
        this.puertoBully = puertoBully;
        this.puertoMutex = puertoMutex;
    }
}
