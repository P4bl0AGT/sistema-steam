package com.steam.common;

import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Canal de acceso logico con failover transparente entre proxies. */
public final class ClienteProxy {
    private static final AtomicInteger inicio = new AtomicInteger(0);

    private ClienteProxy() {}

    public static MensajeProtocolo enviar(MensajeProtocolo req, RelojLamport reloj, String emisor)
            throws ExcepcionTransporte {
        List<Endpoint> proxies = Configuracion.proxies();
        int base = Math.floorMod(inicio.getAndIncrement(), proxies.size());
        ExcepcionTransporte ultima = null;
        for (int i = 0; i < proxies.size(); i++) {
            Endpoint proxy = proxies.get((base + i) % proxies.size());
            try {
                req.setEmisor(emisor);
                req.setReceptor("Proxy@" + proxy);
                req.setLamportClock(reloj.tick());
                if (Utils.esOperacionControl(req.getOperacion())) SeguridadMensajes.firmarControl(req);
                MensajeProtocolo resp = enviarA(req, proxy);
                reloj.update(resp.getLamportClock());
                return resp;
            } catch (ExcepcionTransporte e) {
                ultima = e;
            }
        }
        throw ultima != null ? ultima : new ExcepcionTransporte(
                ExcepcionTransporte.Tipo.IO, "No hay proxies configurados");
    }

    public static MensajeProtocolo enviarA(MensajeProtocolo req, Endpoint endpoint)
            throws ExcepcionTransporte {
        try (Socket socket = Transporte.conectar(endpoint.host(), endpoint.puerto());
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.println(req.toJson());
            String json = LineaJson.leer(in);
            if (json == null) throw new ExcepcionTransporte(
                    ExcepcionTransporte.Tipo.RESPUESTA_VACIA, "Respuesta vacia de " + endpoint);
            try {
                MensajeProtocolo respuesta = MensajeProtocolo.fromJson(json);
                if (respuesta == null) throw new JsonParseException("null");
                return respuesta;
            } catch (RuntimeException e) {
                throw new ExcepcionTransporte(ExcepcionTransporte.Tipo.RESPUESTA_CORRUPTA,
                        "Respuesta JSON invalida de " + endpoint, e);
            }
        } catch (SocketTimeoutException e) {
            throw new ExcepcionTransporte(ExcepcionTransporte.Tipo.TIMEOUT,
                    "Timeout en " + endpoint, e);
        } catch (ConnectException e) {
            throw new ExcepcionTransporte(ExcepcionTransporte.Tipo.CONEXION_RECHAZADA,
                    "Conexion rechazada en " + endpoint, e);
        } catch (ExcepcionTransporte e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ExcepcionTransporte(ExcepcionTransporte.Tipo.IO,
                    "Error IO en " + endpoint + ": " + e.getMessage(), e);
        }
    }
}
