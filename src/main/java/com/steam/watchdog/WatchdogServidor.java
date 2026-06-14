package com.steam.watchdog;

import com.steam.common.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * WatchdogServidor — proceso de supervisión y recuperación automática.
 *
 * Monitorea los puertos de los 6 nodos servidores. Si un nodo no responde
 * al HEALTH_CHECK durante MAX_FALLOS ciclos consecutivos, lanza un nuevo
 * proceso JVM para ese servidor con ProcessBuilder.
 *
 * El servidor reiniciado lee el estado desde Main (o Copy si Main falló)
 * y se re-registra automáticamente en el Proxy vía RegistradorProxy.
 *
 * Uso: java -cp "sistema-steam.jar;lib/gson-2.10.1.jar" com.steam.watchdog.WatchdogServidor
 *
 * MODELO DE FALLOS cubierto:
 *  - Crash de nodo (fallo tipo Crash): detectado por ausencia de respuesta → reinicio.
 *  - Reinicio continuo evitado: el contador de fallos se resetea tras un reinicio
 *    exitoso; si el servidor sigue sin arrancar, el watchdog lo intenta cada ciclo.
 *  - El Proxy detecta la vuelta del nodo vía health-check o registro explícito.
 */
public class WatchdogServidor {

    private static final Logger LOG = Logger.getLogger(WatchdogServidor.class.getName());

    /**
     * Descriptor de un servidor supervisado.
     * clase  → nombre completo de la clase main
     * nodo   → argumento "1" o "2" pasado al main
     */
    private record ServidorInfo(String nombre, int puerto, String clase, String nodo) {
        /** Construye el comando de arranque idéntico al de los scripts .bat. */
        String[] comando() {
            return new String[]{
                "java",
                "-cp", "sistema-steam.jar" + File.pathSeparator + "lib" + File.separator + "gson-2.10.1.jar",
                clase, nodo
            };
        }
    }

    private final List<ServidorInfo> servidores = List.of(
        new ServidorInfo("SES-1", Constantes.PUERTO_SES_1, "com.steam.servidores.svSesiones",   "1"),
        new ServidorInfo("SES-2", Constantes.PUERTO_SES_2, "com.steam.servidores.svSesiones",   "2"),
        new ServidorInfo("JUE-1", Constantes.PUERTO_JUE_1, "com.steam.servidores.svJuegos",     "1"),
        new ServidorInfo("JUE-2", Constantes.PUERTO_JUE_2, "com.steam.servidores.svJuegos",     "2"),
        new ServidorInfo("MSG-1", Constantes.PUERTO_MSG_1, "com.steam.servidores.svMensajeria", "1"),
        new ServidorInfo("MSG-2", Constantes.PUERTO_MSG_2, "com.steam.servidores.svMensajeria", "2")
    );

    // Fallos consecutivos por nombre de nodo
    private final Map<String, Integer> fallosConsecutivos = new ConcurrentHashMap<>();

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        GestorLog.configurar("WatchdogServidor");
        new WatchdogServidor().iniciar();
    }

    // ── Bucle de supervisión ──────────────────────────────────────────────────

    private void iniciar() {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog-main");
            t.setDaemon(false); // proceso principal del watchdog
            return t;
        });

        LOG.info("[WATCHDOG] Iniciado. Supervisando " + servidores.size()
                + " nodos, intervalo=" + Constantes.WATCHDOG_INTERVALO_SEG + "s"
                + ", max_fallos=" + Constantes.WATCHDOG_MAX_FALLOS);

        sched.scheduleAtFixedRate(
            this::verificarTodos,
            Constantes.WATCHDOG_INTERVALO_SEG,
            Constantes.WATCHDOG_INTERVALO_SEG,
            TimeUnit.SECONDS
        );
    }

    private void verificarTodos() {
        for (ServidorInfo sv : servidores) {
            verificar(sv);
        }
    }

    private void verificar(ServidorInfo sv) {
        if (estaVivo(sv.puerto())) {
            int prev = fallosConsecutivos.getOrDefault(sv.nombre(), 0);
            if (prev > 0) {
                LOG.info("[WATCHDOG] " + sv.nombre() + " respondió. Fallos reseteados.");
            }
            fallosConsecutivos.put(sv.nombre(), 0);
            return;
        }

        int fallos = fallosConsecutivos.merge(sv.nombre(), 1, Integer::sum);
        LOG.warning("[WATCHDOG] " + sv.nombre() + " (puerto=" + sv.puerto()
                + ") no responde. Fallos consecutivos: " + fallos + "/" + Constantes.WATCHDOG_MAX_FALLOS);

        if (fallos >= Constantes.WATCHDOG_MAX_FALLOS) {
            LOG.warning("[WATCHDOG] Umbral alcanzado. Reiniciando " + sv.nombre() + "...");
            reiniciar(sv);
            fallosConsecutivos.put(sv.nombre(), 0);
        }
    }

    // ── Health check ──────────────────────────────────────────────────────────

    private boolean estaVivo(int puerto) {
        try {
            MensajeProtocolo ping = MensajeProtocolo.request(Constantes.HEALTH_CHECK, null);
            try (Socket s = new Socket(Constantes.HOST, puerto)) {
                s.setSoTimeout(Constantes.TIMEOUT_MS);
                PrintWriter   pw = new PrintWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                pw.println(ping.toJson());
                String resp = br.readLine();
                MensajeProtocolo r = MensajeProtocolo.fromJson(resp);
                return r != null && r.isOk();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ── Reinicio de proceso ───────────────────────────────────────────────────

    private void reiniciar(ServidorInfo sv) {
        try {
            // Logs del servidor reiniciado a un archivo propio
            new File("logs").mkdirs();
            File logOut = new File("logs/" + sv.nombre() + "_watchdog.log");
            File logErr = new File("logs/" + sv.nombre() + "_watchdog.err");

            ProcessBuilder pb = new ProcessBuilder(sv.comando());
            pb.directory(new File(".")); // ejecutar desde la raíz del proyecto
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logOut));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logErr));

            Process p = pb.start();
            LOG.info("[WATCHDOG] " + sv.nombre() + " reiniciado. PID=" + p.pid()
                    + " | log=" + logOut.getPath());
        } catch (IOException e) {
            LOG.severe("[WATCHDOG] Error al reiniciar " + sv.nombre() + ": " + e.getMessage());
        }
    }
}
