package com.steam.carga;

/** Conserva máximos de contadores acumulativos aunque una JVM reinicie en cero. */
public final class AcumuladorCoordinacion {
    public record Snapshot(long bully, long mutex) {
        public long total() { return bully + mutex; }
    }

    private boolean inicializado;
    private long baseBully;
    private long baseMutex;
    private long ultimoBully;
    private long ultimoMutex;

    public synchronized void observar(long bully, long mutex) {
        if (bully < 0 || mutex < 0) throw new IllegalArgumentException("Contador negativo");
        if (inicializado) {
            if (bully < ultimoBully) baseBully += ultimoBully;
            if (mutex < ultimoMutex) baseMutex += ultimoMutex;
        }
        ultimoBully = bully;
        ultimoMutex = mutex;
        inicializado = true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(baseBully + ultimoBully, baseMutex + ultimoMutex);
    }
}
