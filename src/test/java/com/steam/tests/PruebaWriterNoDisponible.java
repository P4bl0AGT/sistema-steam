package com.steam.tests;

import com.steam.common.ClienteProxy;
import com.steam.common.Constantes;
import com.steam.common.Configuracion;
import com.steam.common.Endpoint;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

/** Comprueba que la caida del writer promueve la replica y mantiene lecturas/escrituras. */
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

        MensajeProtocolo escritura = null;
        long limite = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < limite) {
            escritura = ClienteProxy.enviar(
                    MensajeProtocolo.request(Constantes.AGREGAR_SALDO, token)
                            .put("targetUser", "cliente1").put("monto", 1.0),
                    reloj, "writer-failure");
            if (escritura.isOk()) break;
            Thread.sleep(500L);
        }
        check(escritura != null && escritura.isOk(), "Replica promovida acepta escritura");

        MensajeProtocolo directa = MensajeProtocolo.request(Constantes.AGREGAR_SALDO, token)
                .put("targetUser", "cliente1").put("monto", 1.0);
        MensajeProtocolo promovida = ClienteProxy.enviarA(directa,
                new Endpoint(Configuracion.hostServicio("juegos", secundario),
                        secundario == 1 ? Constantes.PUERTO_JUE_1 : Constantes.PUERTO_JUE_2));
        check(promovida.isOk(), "Secundario promovido atiende escritura directa");

        MensajeProtocolo lectura = ClienteProxy.enviar(
                MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, null),
                reloj, "writer-failure");
        check(lectura.isOk(), "Lectura continua desde replica");
        System.out.println("OK writer_failover=4");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
