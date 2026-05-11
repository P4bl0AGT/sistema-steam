package com.steam.models;

public class Reserva {
    public String  reservaId;
    public String  juegoId;
    public String  juegoNombre;
    public String  username;
    public double  precio;
    public long    creadoEn;
    public long    expiraEn;     // creadoEn + TTL_RESERVA_MS
    public boolean activa;

    public Reserva() {}

    public Reserva(String reservaId, String juegoId, String juegoNombre,
                   String username, double precio, long ttlMs) {
        this.reservaId   = reservaId;
        this.juegoId     = juegoId;
        this.juegoNombre = juegoNombre;
        this.username    = username;
        this.precio      = precio;
        this.creadoEn    = System.currentTimeMillis();
        this.expiraEn    = this.creadoEn + ttlMs;
        this.activa      = true;
    }

    public boolean expirada() {
        return activa && System.currentTimeMillis() > expiraEn;
    }

    public long segundosRestantes() {
        return Math.max(0, (expiraEn - System.currentTimeMillis()) / 1000);
    }
}
