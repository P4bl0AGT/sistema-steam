package com.steam.common;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Utilidad para que svJuegos y svMensajeria validen tokens llamando
 * directamente a svSesiones, sin pasar por el Proxy.
 *
 * MODELO DE FALLOS: intenta svSesiones nodo 1 (8081) primero;
 * si no responde en TIMEOUT_MS, conmuta al nodo 2 (8181).
 */
public class ValidadorToken {

    private static final Logger LOG = Logger.getLogger(ValidadorToken.class.getName());

    /** Resultado de la validación */
    public record ResultadoValidacion(boolean valido, String username, String rol, String mensaje) {}

    /**
     * Envía VALIDAR_TOKEN a svSesiones y retorna el resultado.
     * Si ambos nodos fallan, devuelve inválido.
     */
    public static ResultadoValidacion validar(String token) {
        if (token == null || token.isBlank()) {
            return new ResultadoValidacion(false, null, null, "Token vacío");
        }

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.VALIDAR_TOKEN, token);
        req.setTipo(MensajeProtocolo.TIPO_REQUEST);

        // Intentar nodo 1, luego nodo 2
        int[] puertos = { Constantes.PUERTO_SES_1, Constantes.PUERTO_SES_2 };
        for (int puerto : puertos) {
            try {
                MensajeProtocolo resp = enviarYRecibir(req, puerto);
                if (resp != null && resp.isOk()) {
                    String user = resp.getString("username");
                    String rol  = resp.getString("rol");
                    return new ResultadoValidacion(true, user, rol, "Token válido");
                }
                if (resp != null) {
                    return new ResultadoValidacion(false, null, null, resp.getMensaje());
                }
            } catch (Exception e) {
                LOG.warning("svSesiones:" + puerto + " no disponible. " + e.getMessage());
            }
        }
        return new ResultadoValidacion(false, null, null, "Servicio de sesiones no disponible");
    }

    private static MensajeProtocolo enviarYRecibir(MensajeProtocolo req, int puerto)
            throws IOException {
        try (Socket socket = new Socket(Constantes.HOST, puerto)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter  out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String respJson = in.readLine();
            return respJson != null ? MensajeProtocolo.fromJson(respJson) : null;
        }
    }
}
