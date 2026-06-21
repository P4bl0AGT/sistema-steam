package com.steam.tests;

import com.steam.common.ClienteProxy;
import com.steam.common.Configuracion;
import com.steam.common.Constantes;
import com.steam.common.Endpoint;
import com.steam.common.MensajeProtocolo;

import java.util.List;

/** Espera que los writers hayan reconciliado su estado antes de ejecutar pruebas. */
public final class PruebaPreparacion {
    private record Destino(String servicio, Endpoint endpoint) {}

    public static void main(String[] args) throws Exception {
        int timeoutSeg = args.length > 0 ? Integer.parseInt(args[0]) : 35;
        int sesiones = Configuracion.writerNodeId("SESIONES");
        int juegos = Configuracion.writerNodeId("JUEGOS");
        int mensajeria = Configuracion.writerNodeId("MENSAJERIA");
        List<Destino> writers = List.of(
                new Destino("sesiones", new Endpoint(Configuracion.hostServicio("sesiones", sesiones),
                        sesiones == 1 ? Constantes.PUERTO_SES_1 : Constantes.PUERTO_SES_2)),
                new Destino("juegos", new Endpoint(Configuracion.hostServicio("juegos", juegos),
                        juegos == 1 ? Constantes.PUERTO_JUE_1 : Constantes.PUERTO_JUE_2)),
                new Destino("mensajeria", new Endpoint(Configuracion.hostServicio("mensajeria", mensajeria),
                        mensajeria == 1 ? Constantes.PUERTO_MSG_1 : Constantes.PUERTO_MSG_2)));
        long limite = System.currentTimeMillis() + timeoutSeg * 1_000L;
        for (Destino writer : writers) {
            boolean listo = false;
            while (System.currentTimeMillis() < limite && !listo) {
                try {
                    MensajeProtocolo resp = ClienteProxy.enviarA(
                            MensajeProtocolo.request(Constantes.HEALTH_CHECK, null), writer.endpoint());
                    listo = resp.isOk() && Boolean.TRUE.equals(resp.get("writerReady"));
                } catch (Exception ignored) {
                    // El proceso puede estar abriendo sus canales internos.
                }
                if (!listo) Thread.sleep(250L);
            }
            if (!listo) throw new AssertionError("Writer no reconciliado: " + writer.servicio());
        }
        System.out.println("OK writers_reconciliados=3");
    }
}
