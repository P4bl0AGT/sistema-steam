package com.steam.common;

import java.io.IOException;

/** Error de red tipado para metricas y diagnostico. */
public class ExcepcionTransporte extends IOException {
    public enum Tipo { TIMEOUT, CONEXION_RECHAZADA, RESPUESTA_VACIA, RESPUESTA_CORRUPTA, IO }
    private final Tipo tipo;

    public ExcepcionTransporte(Tipo tipo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.tipo = tipo;
    }
    public ExcepcionTransporte(Tipo tipo, String mensaje) { this(tipo, mensaje, null); }
    public Tipo getTipo() { return tipo; }
}

