package com.steam.tests;

import com.steam.common.ClienteProxy;
import com.steam.common.Constantes;
import com.steam.common.Configuracion;
import com.steam.common.Endpoint;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

/** Comprueba que la caida del writer degrada lecturas sin promover escrituras. */
public final class PruebaWriterNoDisponible {
    public static void main(String[] args) throws Exception {
        int writer = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int secundario = writer == 1 ? 2 : 1;
        RelojLamport reloj = new RelojLamport("writer-failure");
        MensajeProtocolo login = ClienteProxy.enviar(
                MensajeProtocolo.request(Constantes.LOGIN, null)
                        .put("username", "admin").put("password", "admin123"),
                reloj, "writer-failure");
        check(login.isOk(), "Login administrativo");
        String token = login.getString("token");

        MensajeProtocolo escritura = ClienteProxy.enviar(
                MensajeProtocolo.request(Constantes.AGREGAR_SALDO, token)
                        .put("targetUser", "cliente1").put("monto", 1.0),
                reloj, "writer-failure");
        check(!escritura.isOk() && "SERVICE_UNAVAILABLE".equals(escritura.getCodigoError()),
                "Proxy rechaza escritura sin writer");

        MensajeProtocolo directa = MensajeProtocolo.request(Constantes.AGREGAR_SALDO, token)
                .put("targetUser", "cliente1").put("monto", 1.0);
        MensajeProtocolo rechazo = ClienteProxy.enviarA(directa,
                new Endpoint(Configuracion.hostServicio("juegos", secundario),
                        secundario == 1 ? Constantes.PUERTO_JUE_1 : Constantes.PUERTO_JUE_2));
        check(!rechazo.isOk() && "NOT_PRIMARY".equals(rechazo.getCodigoError()),
                "Secundario no se promueve por aislamiento");

        MensajeProtocolo lectura = ClienteProxy.enviar(
                MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, null),
                reloj, "writer-failure");
        check(lectura.isOk(), "Lectura continua desde replica");
        System.out.println("OK writer_caido=4");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
