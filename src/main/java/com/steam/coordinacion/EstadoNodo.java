package com.steam.coordinacion;

/** Estado de un nodo en el registro de membresía. */
public class EstadoNodo {
    public int     id;
    public String  host;
    public int     puertoServicio;
    public int     puertoBully;
    public int     puertoMutex;
    public boolean activo;
    public long    ultimoHeartbeat;
    public long    lamportJoin;      // valor Lamport cuando el nodo se unió

    public EstadoNodo() {}

    public EstadoNodo(int id, String host, int puertoServicio,
                      int puertoBully, int puertoMutex, long lamportJoin) {
        this.id              = id;
        this.host            = host;
        this.puertoServicio  = puertoServicio;
        this.puertoBully     = puertoBully;
        this.puertoMutex     = puertoMutex;
        this.activo          = true;
        this.ultimoHeartbeat = System.currentTimeMillis();
        this.lamportJoin     = lamportJoin;
    }
}
