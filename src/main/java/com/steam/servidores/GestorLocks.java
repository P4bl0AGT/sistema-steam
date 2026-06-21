package com.steam.servidores;

import com.steam.common.Constantes;
import com.steam.common.GestorPersistencia;
import com.steam.coordinacion.GestorMutexCentralizado;
import com.steam.coordinacion.MutexTimeoutException;
import com.steam.models.BDJuegos;
import com.steam.models.Juego;
import com.steam.models.Reserva;

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
 * CONCURRENCIA: adquiere primero el mutex distribuido de stock y después el
 * mismo monitor local de svJuegos, respetando el orden usado por las compras.
 *
 * MODELO DE FALLOS cubierto:
 *  - Fallo de concurrencia: mutex distribuido más sección local sincronizada.
 *  - Reservas huérfanas (si el cliente se desconecta): el daemon las limpia.
 */
public class GestorLocks implements Runnable {

    private static final Logger LOG            = Logger.getLogger(GestorLocks.class.getName());
    private static final long   INTERVALO_MS   = 30_000L; // chequear cada 30 s

    private final GestorPersistencia<BDJuegos> gp;
    private final Object                       lock;
    private final BooleanSupplier              activo;
    private final Consumer<BDJuegos>           guardador;
    private final GestorMutexCentralizado       mutex;
    private final int                           nodoId;

    public GestorLocks(GestorPersistencia<BDJuegos> gp, Object lock,
                       BooleanSupplier activo, Consumer<BDJuegos> guardador,
                       GestorMutexCentralizado mutex, int nodoId) {
        this.gp   = gp;
        this.lock = lock;
        this.activo = activo;
        this.guardador = guardador;
        this.mutex = mutex;
        this.nodoId = nodoId;
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
        GestorMutexCentralizado.LockHandle lockStock;
        try {
            lockStock = mutex.requestLock("stock", nodoId);
        } catch (MutexTimeoutException e) {
            LOG.warning("[LOCKS] No se pudo adquirir mutex distribuido: " + e.getMessage());
            return;
        }
        try { synchronized (lock) {
            if (!mutex.lockVigente(lockStock)) {
                LOG.warning("[LOCKS] Lease de stock expirado antes de limpiar reservas");
                return;
            }
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
        } } finally {
            mutex.releaseLock(lockStock, nodoId);
        }
    }
}
