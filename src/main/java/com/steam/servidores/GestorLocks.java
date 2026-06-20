package com.steam.servidores;

import com.steam.common.Constantes;
import com.steam.common.GestorPersistencia;
import com.steam.models.BDJuegos;
import com.steam.models.Juego;
import com.steam.models.Reserva;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * GestorLocks – Hilo Daemon de Control de Reservas (TTL 5 minutos)
 *
 * Actúa como un proceso Daemon independiente dentro de svJuegos.
 * Cada 30 segundos revisa todas las reservas activas. Si una reserva
 * superó su tiempo de expiración (TTL 5 min), libera el stock
 * automáticamente y notifica en log.
 *
 * CONCURRENCIA: sincroniza sobre el mismo objeto 'lock' que usa svJuegos,
 * garantizando exclusión mutua con las operaciones de compra.
 *
 * MODELO DE FALLOS cubierto:
 *  - Fallo de concurrencia: la restauración de stock es atómica (synchronized).
 *  - Reservas huérfanas (si el cliente se desconecta): el daemon las limpia.
 */
public class GestorLocks implements Runnable {

    private static final Logger LOG            = Logger.getLogger(GestorLocks.class.getName());
    private static final long   INTERVALO_MS   = 30_000L; // chequear cada 30 s

    private final GestorPersistencia<BDJuegos> gp;
    private final Object                       lock;
    private final BooleanSupplier              activo;
    private final Consumer<BDJuegos>           guardador;

    public GestorLocks(GestorPersistencia<BDJuegos> gp, Object lock) {
        this(gp, lock, () -> true, bd -> {
            try { gp.guardar(bd); }
            catch (IOException e) { throw new IllegalStateException(e); }
        });
    }

    public GestorLocks(GestorPersistencia<BDJuegos> gp, Object lock,
                       BooleanSupplier activo, Consumer<BDJuegos> guardador) {
        this.gp   = gp;
        this.lock = lock;
        this.activo = activo;
        this.guardador = guardador;
    }

    @Override
    public void run() {
        LOG.info("[LOCKS] Gestor de Locks (daemon) iniciado. TTL=" +
                Constantes.TTL_RESERVA_MS / 1000 + "s");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(INTERVALO_MS);
                liberarReservasExpiradas();
            } catch (InterruptedException e) {
                // Restaurar el flag para que llamadores puedan detectarlo;
                // break sale del while y run() termina normalmente.
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("[LOCKS] Gestor de Locks detenido.");
    }

    /**
     * Recorre las reservas activas; por cada una expirada:
     *  1. Restaura el stock del juego correspondiente.
     *  2. Marca la reserva como inactiva.
     *  3. Guarda Main + Copy (replicación inmediata).
     *
     * Sincronizado con el mismo lock que usan las transacciones de svJuegos,
     * evitando condiciones de carrera sobre el stock.
     */
    private void liberarReservasExpiradas() {
        if (!activo.getAsBoolean()) return;
        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return;

            boolean cambios = false;

            for (Reserva r : bd.reservas) {
                if (!r.activa || !r.expirada()) continue;

                // Restaurar stock en el juego
                for (Juego j : bd.catalogo) {
                    if (j.id.equals(r.juegoId)) {
                        j.stock++;
                        LOG.info("[LOCKS] Stock restaurado. Juego='" + j.nombre +
                                "' Stock=" + j.stock + " | Reserva=" + r.reservaId +
                                " Usuario=" + r.username);
                        break;
                    }
                }

                r.activa = false;
                cambios  = true;
                LOG.warning("[LOCKS] Reserva expirada liberada: " + r.reservaId +
                        " (usuario=" + r.username + ", juego=" + r.juegoNombre + ")");
            }

            if (cambios) {
                try {
                    guardador.accept(bd);
                } catch (RuntimeException e) {
                    LOG.severe("[LOCKS] Error guardando tras liberar reservas: " + e.getMessage());
                }
            }
        }
    }
}
