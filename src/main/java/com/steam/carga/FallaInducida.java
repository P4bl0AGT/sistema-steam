package com.steam.carga;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.steam.common.ClienteProxy;
import com.steam.common.Constantes;
import com.steam.common.Endpoint;
import com.steam.common.MensajeProtocolo;
import com.steam.common.SeguridadMensajes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Derriba de forma autenticada al coordinador y mide la reeleccion Bully. */
public final class FallaInducida {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        int espera = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        Path salida = args.length > 1 ? Path.of(args[1]) : Path.of("evidencia", "falla-coordinador.json");
        Path marcadorInicio = args.length > 2 && !args[2].isBlank() ? Path.of(args[2]) : null;
        Path marcadorRecuperacion = args.length > 3 && !args[3].isBlank() ? Path.of(args[3]) : null;
        if (espera > 0) Thread.sleep(espera * 1_000L);
        int coordinador = encontrarCoordinador();
        if (coordinador < 1) throw new IllegalStateException("No se pudo determinar coordinador");
        int puertoCaido = coordinador == 1 ? Constantes.PUERTO_JUE_1 : Constantes.PUERTO_JUE_2;
        int sobreviviente = coordinador == 1 ? 2 : 1;
        int puertoSobreviviente = sobreviviente == 1 ? Constantes.PUERTO_JUE_1 : Constantes.PUERTO_JUE_2;

        long inicio = System.currentTimeMillis();
        escribirMarcador(marcadorInicio, inicio);
        MensajeProtocolo shutdown = MensajeProtocolo.request(Constantes.SHUTDOWN_GRACEFUL, null);
        shutdown.setEmisor("FallaInducida");
        shutdown.setLamportClock(1L);
        SeguridadMensajes.firmarControl(shutdown);
        MensajeProtocolo ack = enviar(shutdown, puertoCaido);
        if (ack == null || !ack.isOk()) throw new IllegalStateException("Apagado no autorizado o sin ACK");

        boolean recuperado = false;
        int nuevoCoordinador = -1;
        long limite = inicio + 30_000L;
        while (System.currentTimeMillis() < limite) {
            Thread.sleep(200L);
            MensajeProtocolo estado = enviar(
                    MensajeProtocolo.request(Constantes.QUIEN_ES_COORDINADOR, null), puertoSobreviviente);
            if (estado != null && estado.isOk() && Boolean.TRUE.equals(estado.get("soyCoordinador"))) {
                recuperado = true;
                nuevoCoordinador = ((Number) estado.get("coordinadorActual")).intValue();
                break;
            }
        }
        long recuperacionMs = System.currentTimeMillis() - inicio;
        if (recuperado) escribirMarcador(marcadorRecuperacion, System.currentTimeMillis());
        Map<String, Object> evidencia = new LinkedHashMap<>();
        evidencia.put("fechaUtc", Instant.now().toString());
        evidencia.put("coordinadorCaido", coordinador);
        evidencia.put("puertoCaido", puertoCaido);
        evidencia.put("nodoSobreviviente", sobreviviente);
        evidencia.put("recuperado", recuperado);
        evidencia.put("nuevoCoordinador", nuevoCoordinador);
        evidencia.put("recuperacionMs", recuperacionMs);
        evidencia.put("inicioFallaEpochMs", inicio);
        evidencia.put("finRecuperacionEpochMs", recuperado ? inicio + recuperacionMs : null);
        Files.createDirectories(salida.toAbsolutePath().getParent());
        Files.writeString(salida, GSON.toJson(evidencia), StandardCharsets.UTF_8);
        System.out.println(GSON.toJson(evidencia));
        if (!recuperado) throw new IllegalStateException("Bully no recupero coordinador en 30s");
    }

    private static void escribirMarcador(Path marcador, long epochMs) throws Exception {
        if (marcador == null) return;
        Path padre = marcador.toAbsolutePath().getParent();
        if (padre != null) Files.createDirectories(padre);
        Files.writeString(marcador, Long.toString(epochMs), StandardCharsets.US_ASCII);
    }

    private static int encontrarCoordinador() {
        int candidato = -1;
        for (int nodo = 1; nodo <= 2; nodo++) {
            int port = nodo == 1 ? Constantes.PUERTO_JUE_1 : Constantes.PUERTO_JUE_2;
            MensajeProtocolo resp = enviar(
                    MensajeProtocolo.request(Constantes.QUIEN_ES_COORDINADOR, null), port);
            if (resp == null || !resp.isOk()) continue;
            if (Boolean.TRUE.equals(resp.get("soyCoordinador"))) return nodo;
            Object coord = resp.get("coordinadorActual");
            if (coord instanceof Number n && n.intValue() > 0) candidato = n.intValue();
        }
        return candidato;
    }

    private static MensajeProtocolo enviar(MensajeProtocolo req, int puerto) {
        try {
            if (req.getEmisor() == null) req.setEmisor("FallaInducida");
            if (req.getLamportClock() == 0L) req.setLamportClock(1L);
            SeguridadMensajes.firmarControl(req);
            return ClienteProxy.enviarA(req, new Endpoint("localhost", puerto));
        } catch (Exception e) { return null; }
    }
}
