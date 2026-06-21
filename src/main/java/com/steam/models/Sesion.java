package com.steam.models;

import com.steam.common.SeguridadMensajes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class Sesion {
    public String  token;
    public String  username;
    public String  rol;
    public long    creadoEn;
    public long    ultimaActividad;
    public boolean activa;

    public Sesion() {}

    public Sesion(String token, String username, String rol) {
        this.token           = SeguridadMensajes.sha256Texto(token);
        this.username        = username;
        this.rol             = rol;
        this.creadoEn        = System.currentTimeMillis();
        this.ultimaActividad = this.creadoEn;
        this.activa          = true;
    }

    public boolean vigente() {
        // Vida absoluta: validar un token no extiende su vencimiento.
        return vigente(com.steam.common.Configuracion.tokenTtlMs());
    }

    public boolean vigente(long ttlMs) {
        return activa && System.currentTimeMillis() - creadoEn <= ttlMs;
    }

    public boolean coincideToken(String presentado) {
        if (token == null || presentado == null) return false;
        String esperado = token.matches("[0-9a-fA-F]{64}")
                ? SeguridadMensajes.sha256Texto(presentado) : presentado;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                esperado.getBytes(StandardCharsets.US_ASCII));
    }
}
