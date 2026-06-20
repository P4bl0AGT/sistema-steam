package com.steam.tests;

import com.steam.common.ClienteProxy;
import com.steam.common.Constantes;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

/** Verifica continuidad a traves del endpoint logico con uno de los proxies caido. */
public final class PruebaDisponibilidad {
    public static void main(String[] args) throws Exception {
        int repeticiones = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        RelojLamport reloj = new RelojLamport("disponibilidad");
        for (int i = 0; i < repeticiones; i++) {
            MensajeProtocolo resp = ClienteProxy.enviar(
                    MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, null),
                    reloj, "disponibilidad");
            if (!resp.isOk()) throw new AssertionError("Fallo solicitud " + i + ": " + resp.getMensaje());
        }
        System.out.println("OK disponibilidad_solicitudes=" + repeticiones);
    }
}
