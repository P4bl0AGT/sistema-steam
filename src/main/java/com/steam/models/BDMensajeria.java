package com.steam.models;

import com.steam.common.EstadoVersionado;

import java.util.ArrayList;
import java.util.List;

/** Raiz del JSON persistido en data/mensajeria-N/Main.json. */
public class BDMensajeria implements EstadoVersionado {
    public long replicationVersion;
    public List<Mensaje> mensajes = new ArrayList<>();

    @Override public long getReplicationVersion() { return replicationVersion; }
    @Override public void setReplicationVersion(long version) { replicationVersion = version; }
}
