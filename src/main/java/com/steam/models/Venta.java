package com.steam.models;

public class Venta {
    public String ventaId;
    public String juegoId;
    public String juegoNombre;
    public String comprador;
    public String vendedor;
    public double precio;
    public long   timestamp;

    public Venta() {}

    public Venta(String ventaId, String juegoId, String juegoNombre,
                 String comprador, String vendedor, double precio) {
        this.ventaId     = ventaId;
        this.juegoId     = juegoId;
        this.juegoNombre = juegoNombre;
        this.comprador   = comprador;
        this.vendedor    = vendedor;
        this.precio      = precio;
        this.timestamp   = System.currentTimeMillis();
    }
}
