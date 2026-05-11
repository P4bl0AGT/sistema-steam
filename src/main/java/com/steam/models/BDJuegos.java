package com.steam.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Raíz del JSON persistido en GME_Main.txt / GME_Copy.txt */
public class BDJuegos {
    public List<Juego>   catalogo    = new ArrayList<>();
    public List<Reserva> reservas    = new ArrayList<>();
    public List<Venta>   ventas      = new ArrayList<>();
    /** Mapa username → saldo de billetera */
    public Map<String, Double> billeteras = new HashMap<>();
}
