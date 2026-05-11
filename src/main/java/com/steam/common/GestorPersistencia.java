package com.steam.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;

/**
 * Gestor de persistencia genérico con replicación Main → Copy.
 *
 * CONCURRENCIA: todos los métodos públicos son synchronized sobre 'this'
 * para garantizar que ningún hilo lea un estado parcialmente escrito y que
 * la replicación sea atómica desde el punto de vista del servidor.
 *
 * MODELO DE FALLOS:
 *  - Si Main falla al leer → intenta Copy (fallo de persistencia).
 *  - Toda escritura en Main se replica inmediatamente en Copy.
 */
public class GestorPersistencia<T> {

    private static final Logger LOG  = Logger.getLogger(GestorPersistencia.class.getName());
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String  pathMain;
    private final String  pathCopy;
    private final Class<T> tipo;

    public GestorPersistencia(String pathMain, String pathCopy, Class<T> tipo) {
        this.pathMain = pathMain;
        this.pathCopy = pathCopy;
        this.tipo     = tipo;
        asegurarDirectorio();
    }

    // ── Leer ─────────────────────────────────────────────────────────────────

    /**
     * Lee el estado desde Main; si falla, intenta desde Copy.
     * Si ambas fallan o no existen, retorna null.
     */
    public synchronized T leer() {
        T resultado = leerDesde(pathMain);
        if (resultado == null) {
            LOG.warning("Main no disponible. Leyendo desde Copy: " + pathCopy);
            resultado = leerDesde(pathCopy);
        }
        return resultado;
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
     * Escribe en Main y replica inmediatamente en Copy.
     * Si la escritura en Main falla, lanza excepción para que el servidor
     * pueda manejar el error; Copy se actualiza solo si Main tuvo éxito.
     */
    public synchronized void guardar(T datos) throws IOException {
        String json = GSON.toJson(datos);
        escribirEnArchivo(pathMain, json);   // primero Main
        escribirEnArchivo(pathCopy, json);   // replicación inmediata
    }

    private void escribirEnArchivo(String path, String json) throws IOException {
        // Escritura atómica: escribir en temporal y luego renombrar
        Path destino  = Path.of(path);
        Path temporal = Path.of(path + ".tmp");
        Files.writeString(temporal, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
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
            Files.createDirectories(Path.of(Constantes.DATA_DIR));
        } catch (IOException e) {
            LOG.severe("No se pudo crear directorio de datos: " + e.getMessage());
        }
    }
}
