package com.steam.models;

public class Usuario {
    public String username;
    public String passwordHash;
    public String rol;       // COMPRADOR | VENDEDOR | ADMINISTRADOR
    public String email;
    public long   creadoEn;
    public boolean activo;

    public Usuario() { this.activo = true; this.creadoEn = System.currentTimeMillis(); }

    public Usuario(String username, String passwordHash, String rol) {
        this();
        this.username     = username;
        this.passwordHash = passwordHash;
        this.rol          = rol;
    }
}
