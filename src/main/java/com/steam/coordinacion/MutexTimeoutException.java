package com.steam.coordinacion;

/**
 * Lanzada cuando requestLock() no recibe GRANT del coordinador
 * dentro del tiempo TIMEOUT_MUTEX_MS (ej. coordinador caído).
 */
public class MutexTimeoutException extends RuntimeException {
    public MutexTimeoutException(String message) {
        super(message);
    }
}
