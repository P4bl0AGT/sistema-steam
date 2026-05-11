package com.steam.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.logging.*;

/**
 * GestorLog – Configuración centralizada de logging para todos los componentes.
 *
 * Cada componente (Proxy, svSesiones, svJuegos, etc.) llama a
 * GestorLog.configurar("nombre") en su main() y desde ese momento:
 *
 *  - Todos los logs van a  logs/<nombre>_0.log  (rotación automática cada 5 MB).
 *  - Los logs también se muestran en consola (nivel INFO+).
 *  - Las excepciones no capturadas (crashes) quedan registradas en el archivo
 *    antes de que la JVM muera, con stack trace completo.
 *
 * Uso:
 *   GestorLog.configurar("svSesiones-1");
 *   Logger LOG = Logger.getLogger(MiClase.class.getName());
 *   LOG.info("mensaje");
 *   LOG.severe("error grave");
 */
public final class GestorLog {

    private GestorLog() {}

    /**
     * Inicializa los handlers del root logger.
     * @param componente  Nombre del componente (usado en el nombre del archivo).
     */
    public static void configurar(String componente) {
        try {
            Files.createDirectories(Path.of("logs"));
        } catch (IOException e) {
            System.err.println("[LOG] No se pudo crear directorio logs/: " + e.getMessage());
        }

        Formatter fmt = crearFormatter(componente);

        // ── Handler de archivo (5 MB × 5 archivos, con append) ───────────────
        FileHandler fileHandler = null;
        try {
            fileHandler = new FileHandler(
                    "logs/" + componente + "_%g.log",
                    5 * 1024 * 1024,  // 5 MB por archivo
                    5,                // hasta 5 archivos rotados
                    true              // append = true (no borra al reiniciar)
            );
            fileHandler.setFormatter(fmt);
            fileHandler.setLevel(Level.ALL);
        } catch (IOException e) {
            System.err.println("[LOG] No se pudo crear FileHandler: " + e.getMessage());
        }

        // ── Handler de consola (solo INFO+) ──────────────────────────────────
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(fmt);
        consoleHandler.setLevel(Level.INFO);

        // ── Reemplazar handlers del root logger ───────────────────────────────
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        if (fileHandler != null) root.addHandler(fileHandler);
        root.addHandler(consoleHandler);
        root.setLevel(Level.ALL);

        // ── Capturar excepciones no manejadas (crashes) ───────────────────────
        final FileHandler fhFinal = fileHandler;
        Thread.setDefaultUncaughtExceptionHandler((hilo, ex) -> {
            String msg = "═══ CRASH NO CAPTURADO en hilo [" + hilo.getName() + "] ═══\n"
                    + stackTrace(ex);
            Logger crashLog = Logger.getLogger(componente);
            crashLog.severe(msg);
            // Forzar flush antes de que la JVM muera
            if (fhFinal != null) fhFinal.flush();
        });

        Logger.getLogger(componente).info(
                "════════════════════════════════════\n" +
                "  Componente  : " + componente + "\n" +
                "  Log archivo : logs/" + componente + "_0.log\n" +
                "════════════════════════════════════");
    }

    // ── Formatter personalizado ───────────────────────────────────────────────

    private static Formatter crearFormatter(String componente) {
        return new Formatter() {
            @Override
            public synchronized String format(LogRecord r) {
                String thrown = r.getThrown() != null
                        ? "\n" + stackTrace(r.getThrown())
                        : "";
                // Índices explícitos para evitar ambigüedad al mezclar %n$ con %-s
                return String.format("[%1$tF %1$tT.%1$tL] [%2$-7s] [%3$s] %4$s%5$s%n",
                        new Date(r.getMillis()),  // 1 → fecha
                        r.getLevel().getName(),   // 2 → nivel (INFO, SEVERE…)
                        componente,               // 3 → nombre del componente
                        formatMessage(r),         // 4 → mensaje
                        thrown);                  // 5 → stack trace si hay
            }
        };
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
