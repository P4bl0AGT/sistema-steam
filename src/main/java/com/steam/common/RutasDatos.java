package com.steam.common;

import java.nio.file.Path;
import java.util.Locale;

/** Rutas de almacenamiento independientes para cada JVM de servicio. */
public final class RutasDatos {
    private RutasDatos() {}

    public static String main(String servicio, int nodo) {
        return base(servicio, nodo).resolve("Main.json").toString();
    }

    public static String copy(String servicio, int nodo) {
        return base(servicio, nodo).resolve("Copy.json").toString();
    }

    public static String version(String servicio, int nodo) {
        return base(servicio, nodo).resolve("VERSION.txt").toString();
    }

    private static Path base(String servicio, int nodo) {
        String nombre = servicio.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return Path.of(Constantes.DATA_DIR, nombre + "-" + nodo);
    }
}
