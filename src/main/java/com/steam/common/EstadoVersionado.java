package com.steam.common;

/** Estado persistente cuya version de replicacion viaja dentro del snapshot. */
public interface EstadoVersionado {
    long getReplicationVersion();
    void setReplicationVersion(long version);
}
