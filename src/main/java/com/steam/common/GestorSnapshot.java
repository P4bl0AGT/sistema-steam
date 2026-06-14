package com.steam.common;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * GestorSnapshot — daemon que copia Main → Copy cada intervaloSeg segundos.
 *
 * Copy es un respaldo periódico de Main, NO una escritura simultánea.
 * Esto garantiza que si Main se corrompe, Copy tiene un estado
 * consistente de hace máximo intervaloSeg segundos.
 *
 * Copia atómica: Main → Copy.snap.tmp → Copy (rename atómico).
 * Si Main no existe o está vacío, omite silenciosamente.
 * Si la copia falla, loggea WARNING y reintenta en el próximo ciclo.
 */
public class GestorSnapshot {

    private static final Logger LOG =
            Logger.getLogger(GestorSnapshot.class.getName());

    private final String pathMain;
    private final String pathCopy;
    private final String componente;
    private final int    intervaloSeg;

    private ScheduledExecutorService scheduler;

    public GestorSnapshot(String pathMain, String pathCopy,
                          String componente, int intervaloSeg) {
        this.pathMain     = pathMain;
        this.pathCopy     = pathCopy;
        this.componente   = componente;
        this.intervaloSeg = intervaloSeg;
    }

    /**
     * Inicia el daemon de snapshot.
     * El primer snapshot ocurre después de {@code intervaloSeg} segundos.
     */
    public void start() {
        start(intervaloSeg);
    }

    /**
     * Inicia el daemon con un retardo inicial personalizado.
     * Útil para escalonar dos nodos que comparten los mismos archivos Main/Copy,
     * evitando que ambos escriban al mismo archivo temporal simultáneamente.
     *
     * Ejemplo: Nodo 1 → start(30), Nodo 2 → start(45); nunca coinciden.
     */
    public void start(int initialDelaySeg) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-" + componente);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::tomarSnapshot,
                initialDelaySeg,
                intervaloSeg,
                TimeUnit.SECONDS
        );

        LOG.info("[SNAPSHOT] " + componente
                + " daemon iniciado (delay=" + initialDelaySeg + "s, intervalo=" + intervaloSeg + "s)"
                + " | " + pathMain + " → " + pathCopy);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            LOG.info("[SNAPSHOT] " + componente + " daemon detenido.");
        }
    }

    private void tomarSnapshot() {
        try {
            Path main = Path.of(pathMain);
            Path copy = Path.of(pathCopy);
            Path tmp  = Path.of(pathCopy + ".snap.tmp");

            if (!Files.exists(main) || Files.size(main) == 0) {
                LOG.fine("[SNAPSHOT] " + componente
                        + " Main no disponible, omitiendo.");
                return;
            }

            Path parent = copy.getParent();
            if (parent != null) Files.createDirectories(parent);

            Files.copy(main, tmp,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);

            try {
                Files.move(tmp, copy,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING);
            }

            LOG.info("[SNAPSHOT] " + componente
                    + " Copy actualizado desde Main ("
                    + Files.size(copy) + " bytes)");

        } catch (IOException e) {
            LOG.warning("[SNAPSHOT] " + componente
                    + " error tomando snapshot: " + e.getMessage());
        }
    }

    /** Fuerza un snapshot inmediato fuera del ciclo programado. */
    public void snapshotInmediato() {
        LOG.info("[SNAPSHOT] " + componente + " snapshot inmediato solicitado.");
        tomarSnapshot();
    }
}
