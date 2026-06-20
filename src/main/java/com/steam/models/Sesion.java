package com.steam.models;

public class Sesion {
    public String  token;
    public String  username;
    public String  rol;
    public long    creadoEn;
    public long    ultimaActividad;
    public boolean activa;

    public Sesion() {}

    public Sesion(String token, String username, String rol) {
        this.token           = token;
        this.username        = username;
        this.rol             = rol;
        this.creadoEn        = System.currentTimeMillis();
        this.ultimaActividad = this.creadoEn;
        this.activa          = true;
    }

    public boolean vigente() {
        // Las sesiones no expiran automáticamente; se invalidan en logout
        return vigente(com.steam.common.Configuracion.tokenTtlMs());
    }

    public boolean vigente(long ttlMs) {
        return activa && System.currentTimeMillis() - ultimaActividad <= ttlMs;
    }
}
