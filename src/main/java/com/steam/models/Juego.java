package com.steam.models;

public class Juego {
    public String  id;
    public String  nombre;
    public String  descripcion;
    public double  precio;
    public int     stock;
    public int     stockOriginal;
    public String  vendedor;
    public boolean activo;
    public long    creadoEn;
    public int     totalVentas;

    public Juego() { this.activo = true; this.creadoEn = System.currentTimeMillis(); }

    public Juego(String id, String nombre, String descripcion,
                 double precio, int stock, String vendedor) {
        this();
        this.id            = id;
        this.nombre        = nombre;
        this.descripcion   = descripcion;
        this.precio        = precio;
        this.stock         = stock;
        this.stockOriginal = stock;
        this.vendedor      = vendedor;
    }
}
