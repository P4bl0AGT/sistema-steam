package com.steam.models;

import com.steam.common.EstadoVersionado;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Raiz del JSON persistido en data/juegos-N/Main.json. */
public class BDJuegos implements EstadoVersionado {
    public long replicationVersion;
    public List<Juego>   catalogo    = new ArrayList<>();
    public List<Reserva> reservas    = new ArrayList<>();
    public List<Venta>   ventas      = new ArrayList<>();
    /** Mapa username → saldo de billetera */
    public Map<String, Double> billeteras = new HashMap<>();

    @Override public long getReplicationVersion() { return replicationVersion; }
    @Override public void setReplicationVersion(long version) { replicationVersion = version; }
}
