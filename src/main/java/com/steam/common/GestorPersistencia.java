package com.steam.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;

/**
 * Gestor de persistencia genérico. Escribe solo en Main por transacción.
 * Copy se actualiza periódicamente por GestorSnapshot (cada 30s).
 *
 * CONCURRENCIA: todos los métodos públicos son synchronized sobre 'this'.
 *
 * MODELO DE FALLOS:
 *  - Si Main falla al leer → promueve Copy a Main (failover automático).
 *  - El próximo ciclo de GestorSnapshot re-crea Copy desde el Main promovido.
 */
public class GestorPersistencia<T> {

    private static final Logger LOG  = Logger.getLogger(GestorPersistencia.class.getName());
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Sufijo único POR PROCESO para los archivos temporales.
     * Dos nodos (JVMs distintas) que comparten el mismo Main NO deben usar el
     * mismo .tmp: si ambos escriben a la vez sobre "Main.tmp" se corrompen el
     * temporal mutuamente y luego promueven JSON inválido (MalformedJsonException).
     * Con un sufijo por PID, cada proceso escribe su propio temporal y el
     * rename atómico deja Main siempre como un JSON completo y válido.
     */
    private static final String TMP_SUFIJO = "." + ProcessHandle.current().pid() + ".tmp";

    private final String   pathMain;
    private final String   pathCopy;
    private final Class<T> tipo;

    public GestorPersistencia(String pathMain, String pathCopy, Class<T> tipo) {
        this.pathMain = pathMain;
        this.pathCopy = pathCopy;
        this.tipo     = tipo;
        asegurarDirectorio();
    }

    // ── Leer ─────────────────────────────────────────────────────────────────

    /**
     * Lee el estado desde Main.
     * Si Main falla, promueve Copy a Main (failover) y reintenta la lectura.
     * Así Copy actúa como copia de seguridad funcional: si Main cae,
     * el sistema continúa sin intervención manual.
     */
    public synchronized T leer() {
        T resultado = leerDesde(pathMain);
        if (resultado == null) {
            LOG.warning("[PERSISTENCIA] Main no disponible. Promoviendo Copy → Main: " + pathCopy + " → " + pathMain);
            if (promoverCopiaAMain()) {
                resultado = leerDesde(pathMain);
                if (resultado != null) {
                    LOG.warning("[PERSISTENCIA] Failover completado. Operando desde Copy promovida a Main.");
                }
            }
            if (resultado == null) {
                LOG.severe("[PERSISTENCIA] Main y Copy no disponibles en " + pathMain + ". Retornando null.");
            }
        }
        return resultado;
    }

    /**
     * Promueve Copy → Main atómicamente.
     * Se invoca cuando Main está ausente o corrompido.
     * El próximo ciclo de GestorSnapshot (30s) regenerará la copia.
     */
    private boolean promoverCopiaAMain() {
        try {
            Path copy = Path.of(pathCopy);
            Path main = Path.of(pathMain);
            if (!Files.exists(copy) || Files.size(copy) == 0) {
                LOG.severe("[PERSISTENCIA] Copy también no disponible: " + pathCopy);
                return false;
            }
            Path tmp = Path.of(pathMain + ".recover" + TMP_SUFIJO);
            Files.copy(copy, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.warning("[PERSISTENCIA] Copy promovida a Main exitosamente: " + pathMain);
            return true;
        } catch (IOException e) {
            LOG.severe("[PERSISTENCIA] Error promoviendo Copy a Main: " + e.getMessage());
            return false;
        }
    }

    private T leerDesde(String path) {
        try {
            Path p = Path.of(path);
            if (!Files.exists(p) || Files.size(p) == 0) return null;
            String json = Files.readString(p, StandardCharsets.UTF_8);
            return GSON.fromJson(json, tipo);
        } catch (Exception e) {
            LOG.warning("Error leyendo " + path + ": " + e.getMessage());
            return null;
        }
    }

    // ── Escribir ──────────────────────────────────────────────────────────────

    /**
     * Escribe en Main. Copy se actualiza periódicamente por GestorSnapshot.
     * Si la escritura falla, lanza excepción para que el servidor la maneje.
     */
    public synchronized void guardar(T datos) throws IOException {
        String json = GSON.toJson(datos);
        escribirEnArchivo(pathMain, json);
        // Copy se actualiza cada 30s por GestorSnapshot — no aquí.
    }

    private void escribirEnArchivo(String path, String json) throws IOException {
        // Escritura atómica: escribir en temporal y luego renombrar
        Path destino  = Path.of(path);
        Path temporal = Path.of(path + TMP_SUFIJO);
        Files.writeString(temporal, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // ATOMIC_MOVE no está disponible entre filesystems distintos (ej. Docker, algunas
            // distribuciones Linux). Se hace el move sin atomicidad como fallback.
            LOG.warning("ATOMIC_MOVE no soportado en " + path + "; usando REPLACE_EXISTING sin atomicidad.");
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── Inicialización ────────────────────────────────────────────────────────

    /**
     * Si no existe ningún archivo, guarda el objeto inicial provisto.
     * Útil para sembrar datos de arranque (usuarios por defecto, catálogo, etc.).
     */
    public synchronized void inicializarSiVacio(T valorInicial) {
        if (leer() == null) {
            try {
                guardar(valorInicial);
                LOG.info("Datos iniciales guardados en " + pathMain);
            } catch (IOException e) {
                LOG.severe("No se pudo inicializar datos: " + e.getMessage());
            }
        }
    }

    private void asegurarDirectorio() {
        try {
            crearPadre(pathMain);
            crearPadre(pathCopy);
        } catch (IOException e) {
            LOG.severe("No se pudo crear directorio de datos: " + e.getMessage());
        }
    }

    private static void crearPadre(String archivo) throws IOException {
        Path padre = Path.of(archivo).toAbsolutePath().getParent();
        if (padre != null) Files.createDirectories(padre);
    }
}
