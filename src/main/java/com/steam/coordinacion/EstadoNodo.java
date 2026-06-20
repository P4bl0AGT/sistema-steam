package com.steam.coordinacion;

/** Estado completo de un miembro observado por una instancia de Proxy. */
public class EstadoNodo {
    public String id;
    public String tipoServicio;
    public String nombre;
    public String host;
    public int puertoServicio;
    public int puertoBully;
    public int puertoMutex;
    public int puertoReplicacion;
    public boolean activo;
    public long ultimoHeartbeat;
    public long lamportJoin;

    public EstadoNodo() {}

    public EstadoNodo(String id, String tipoServicio, String nombre, String host,
                      int puertoServicio, int puertoBully, int puertoMutex,
                      int puertoReplicacion, long lamportJoin) {
        this.id = id;
        this.tipoServicio = tipoServicio;
        this.nombre = nombre;
        this.host = host;
        this.puertoServicio = puertoServicio;
        this.puertoBully = puertoBully;
        this.puertoMutex = puertoMutex;
        this.puertoReplicacion = puertoReplicacion;
        this.activo = true;
        this.ultimoHeartbeat = System.currentTimeMillis();
        this.lamportJoin = lamportJoin;
    }
}
