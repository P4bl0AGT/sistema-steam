package com.steam.tests;

import com.steam.common.ClienteProxy;
import com.steam.common.Constantes;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Prepara y verifica una escritura repetida a traves de un reinicio del writer. */
public final class PruebaIdempotenciaReinicio {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Uso: preparar|verificar archivo");
        Path archivo = Path.of(args[1]);
        if ("preparar".equals(args[0])) preparar(archivo);
        else if ("verificar".equals(args[0])) verificar(archivo);
        else throw new IllegalArgumentException("Modo desconocido: " + args[0]);
    }

    private static void preparar(Path archivo) throws Exception {
        RelojLamport reloj = new RelojLamport("idem-restart-pre");
        MensajeProtocolo login = ClienteProxy.enviar(
                MensajeProtocolo.request(Constantes.LOGIN, null)
                        .put("username", "admin").put("password", "admin123"),
                reloj, "idem-restart-pre");
        check(login.isOk(), "Login para preparar idempotencia");
        MensajeProtocolo recarga = MensajeProtocolo.request(Constantes.AGREGAR_SALDO,
                login.getString("token")).put("targetUser", "cliente1").put("monto", 1.0);
        MensajeProtocolo aplicada = ClienteProxy.enviar(recarga, reloj, "idem-restart-pre");
        check(aplicada.isOk(), "Recarga inicial");
        if (archivo.getParent() != null) Files.createDirectories(archivo.getParent());
        Files.write(archivo, List.of(
                Base64.getEncoder().encodeToString(recarga.toJson().getBytes(StandardCharsets.UTF_8)),
                Double.toString(aplicada.getDouble("nuevoSaldo"))), StandardCharsets.US_ASCII);
        System.out.println("OK idempotencia_reinicio_preparada=1");
    }

    private static void verificar(Path archivo) throws Exception {
        List<String> lineas = Files.readAllLines(archivo, StandardCharsets.US_ASCII);
        MensajeProtocolo recarga = MensajeProtocolo.fromJson(new String(
                Base64.getDecoder().decode(lineas.get(0)), StandardCharsets.UTF_8));
        double esperado = Double.parseDouble(lineas.get(1));
        MensajeProtocolo repetida = ClienteProxy.enviar(recarga,
                new RelojLamport("idem-restart-post"), "idem-restart-post");
        check(repetida.isOk(), "Reintento despues del reinicio");
        check(Math.abs(repetida.getDouble("nuevoSaldo") - esperado) < 0.001,
                "El reinicio no repite la recarga");
        Files.deleteIfExists(archivo);
        System.out.println("OK idempotencia_reinicio_verificada=2");
    }

    private static void check(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }
}
