package com.steam.carga;

import com.steam.common.Constantes;
import com.steam.common.MensajeProtocolo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * FallaInducida – Simula la caída del nodo coordinador durante una prueba de carga.
 *
 * Uso: java ... FallaInducida
 *
 * Espera 30 segundos, luego:
 *  1. Pregunta a cada nodo de svJuegos quién es el coordinador.
 *  2. Envía SHUTDOWN_GRACEFUL al coordinador.
 *  3. El nodo coordinador registra el evento y llama System.exit(0).
 *  4. El otro nodo detecta la caída vía heartbeat y lanza re-elección.
 */
public class FallaInducida {

    public static void main(String[] args) throws Exception {
        int espera = args.length > 0 ? Integer.parseInt(args[0]) : 30;

        System.out.println("[FALLA] Esperando " + espera + "s antes de inducir falla...");
        Thread.sleep(espera * 1_000L);

        int coordPuerto = encontrarCoordinador();
        if (coordPuerto <= 0) {
            System.out.println("[FALLA] No se pudo determinar el coordinador. ¿Están los nodos activos?");
            return;
        }

        System.out.println("[FALLA] Enviando SHUTDOWN_GRACEFUL al coordinador (puerto " + coordPuerto + ")");
        enviarShutdown(coordPuerto);
        System.out.println("[FALLA] Señal enviada. El otro nodo debería iniciar re-elección en ~"
                + (Constantes.HEARTBEAT_COORD_MS / 1_000) + "s.");
    }

    /** Consulta QUIEN_ES_COORDINADOR a ambos nodos y retorna el puerto del coordinador. */
    private static int encontrarCoordinador() {
        int[] puertos = { Constantes.PUERTO_JUE_1, Constantes.PUERTO_JUE_2 };
        for (int puerto : puertos) {
            try {
                MensajeProtocolo req  = MensajeProtocolo.request(Constantes.QUIEN_ES_COORDINADOR, null);
                MensajeProtocolo resp = enviar(req, puerto);
                if (resp != null && resp.isOk()) {
                    boolean esCoor = Boolean.TRUE.equals(resp.get("soyCoordinador"));
                    if (esCoor) {
                        System.out.println("[FALLA] Coordinador encontrado en puerto " + puerto);
                        return puerto;
                    }
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static void enviarShutdown(int puerto) {
        try {
            MensajeProtocolo req  = MensajeProtocolo.request(Constantes.SHUTDOWN_GRACEFUL, null);
            MensajeProtocolo resp = enviar(req, puerto);
            if (resp != null) System.out.println("[FALLA] Respuesta: " + resp.getMensaje());
        } catch (Exception e) {
            // El nodo puede cerrarse antes de enviar respuesta — es normal
            System.out.println("[FALLA] Nodo cerrado (conexión cortada): esperado.");
        }
    }

    private static MensajeProtocolo enviar(MensajeProtocolo req, int puerto) {
        try (Socket socket = new Socket(Constantes.HOST, puerto)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String resp = in.readLine();
            return resp != null ? MensajeProtocolo.fromJson(resp) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
