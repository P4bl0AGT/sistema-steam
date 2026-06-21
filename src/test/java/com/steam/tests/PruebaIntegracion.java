package com.steam.tests;

import com.steam.common.ClienteProxy;
import com.steam.common.Constantes;
import com.steam.common.Endpoint;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PruebaIntegracion {
    private static int pruebas;

    public static void main(String[] args) throws Exception {
        String vendedor = login("vendedor1", "pass123");
        String comprador1 = login("cliente2", "pass123");
        String comprador2 = login("cliente3", "pass123");
        String admin = login("admin", "admin123");
        check(enviar(request(Constantes.ESTADO_REPLICACION, null), "control-proxy").isOk(),
                "Control firmado atraviesa el proxy sin alterar su prueba");

        String nombre = "Juego mutex " + System.currentTimeMillis();
        MensajeProtocolo publicar = request(Constantes.PUBLICAR_JUEGO, vendedor)
                .put("nombre", nombre).put("descripcion", "Prueba ultimo ejemplar")
                .put("precio", 1.0).put("stock", 1);
        MensajeProtocolo publicado = enviar(publicar, "test-vendedor");
        check(publicado.isOk(), "Publicacion de juego");
        String juegoId = publicado.getString("juegoId");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        Future<MensajeProtocolo> a = pool.submit(() -> comprar(inicio, comprador1, juegoId, "c1"));
        Future<MensajeProtocolo> b = pool.submit(() -> comprar(inicio, comprador2, juegoId, "c2"));
        inicio.countDown();
        List<MensajeProtocolo> compras = List.of(a.get(), b.get());
        long exitos = compras.stream().filter(MensajeProtocolo::isOk).count();
        check(exitos == 1L, "Solo una reserva obtiene el ultimo ejemplar");
        pool.shutdownNow();

        int ganador = compras.get(0).isOk() ? 0 : 1;
        String tokenGanador = ganador == 0 ? comprador1 : comprador2;
        MensajeProtocolo cancelar = request(Constantes.CANCELAR_RESERVA, tokenGanador)
                .put("reservaId", compras.get(ganador).getString("reservaId"));
        check(enviar(cancelar, "cancelar").isOk(), "Cancelar restaura stock bajo mutex");

        ExecutorService segundaCompra = Executors.newFixedThreadPool(2);
        CountDownLatch segundoInicio = new CountDownLatch(1);
        Future<MensajeProtocolo> c = segundaCompra.submit(
                () -> comprar(segundoInicio, comprador1, juegoId, "c3"));
        Future<MensajeProtocolo> d = segundaCompra.submit(
                () -> comprar(segundoInicio, comprador2, juegoId, "c4"));
        segundoInicio.countDown();
        List<MensajeProtocolo> segundaRonda = List.of(c.get(), d.get());
        check(segundaRonda.stream().filter(MensajeProtocolo::isOk).count() == 1L,
                "Stock cancelado se vende una sola vez");
        segundaCompra.shutdownNow();

        int segundoGanador = segundaRonda.get(0).isOk() ? 0 : 1;
        String segundoToken = segundoGanador == 0 ? comprador1 : comprador2;
        MensajeProtocolo pago = request(Constantes.CONFIRMAR_PAGO, segundoToken)
                .put("reservaId", segundaRonda.get(segundoGanador).getString("reservaId"));
        check(enviar(pago, "pago").isOk(), "Pago confirma la venta");

        String contenido = "integracion-" + System.currentTimeMillis();
        MensajeProtocolo mensaje = request(Constantes.ENVIAR_MENSAJE, comprador1)
                .put("receptor", "cliente3").put("contenido", contenido);
        check(enviar(mensaje, "mensaje").isOk(), "Envio de mensaje");
        MensajeProtocolo recibidos = enviar(
                request(Constantes.VER_MENSAJES, comprador2), "recibir");
        check(recibidos.isOk() && contieneMensaje(recibidos.get("mensajes"), contenido),
                "Recepcion de mensaje pendiente");
        List<String> idsRecibidos = idsMensajes(recibidos.get("mensajes"));
        check(!idsRecibidos.isEmpty() && enviar(request(
                Constantes.CONFIRMAR_ENTREGA_MENSAJES, comprador2)
                .put("mensajeIds", idsRecibidos), "ack-mensaje").isOk(),
                "Confirmacion explicita de entrega");
        MensajeProtocolo despuesAck = enviar(
                request(Constantes.VER_MENSAJES, comprador2), "despues-ack");
        check(despuesAck.isOk() && !contieneMensaje(despuesAck.get("mensajes"), contenido),
                "Mensaje confirmado no se vuelve a entregar");

        MensajeProtocolo saldoInicial = enviar(request(Constantes.VER_SALDO, comprador2), "saldo");
        double antes = saldoInicial.getDouble("saldo");
        MensajeProtocolo recarga = request(Constantes.AGREGAR_SALDO, admin)
                .put("targetUser", "cliente3").put("monto", 7.0);
        String repetido = recarga.getRequestId();
        ExecutorService duplicados = Executors.newFixedThreadPool(2);
        Future<MensajeProtocolo> d1 = duplicados.submit(() -> enviar(clonar(recarga, repetido), "dup-1"));
        Future<MensajeProtocolo> d2 = duplicados.submit(() -> enviar(clonar(recarga, repetido), "dup-2"));
        check(d1.get().isOk() && d2.get().isOk(), "Reintentos obtienen respuesta estable");
        duplicados.shutdownNow();
        MensajeProtocolo saldoFinal = enviar(request(Constantes.VER_SALDO, comprador2), "saldo");
        check(Math.abs(saldoFinal.getDouble("saldo") - (antes + 7.0)) < 0.001,
                "requestId duplicado aplica una sola recarga");

        MensajeProtocolo escrituraSecundaria = request(Constantes.AGREGAR_SALDO, admin)
                .put("targetUser", "cliente3").put("monto", 1.0);
        escrituraSecundaria.setLamportClock(1L);
        MensajeProtocolo rechazoSecundario = ClienteProxy.enviarA(escrituraSecundaria,
                new Endpoint("localhost", Constantes.PUERTO_JUE_2));
        check(!rechazoSecundario.isOk()
                        && "NOT_PRIMARY".equals(rechazoSecundario.getCodigoError()),
                "Nodo secundario rechaza escritura directa");

        Thread.sleep(500L);
        check(hash("data/juegos-1/Main.json").equals(hash("data/juegos-2/Main.json")),
                "Replica de juegos converge byte a byte");
        check(hash("data/sesiones-1/Main.json").equals(hash("data/sesiones-2/Main.json")),
                "Replica de sesiones converge byte a byte");
        check(hash("data/mensajeria-1/Main.json").equals(hash("data/mensajeria-2/Main.json")),
                "Replica de mensajeria converge byte a byte");

        System.out.println("OK pruebas_integracion=" + pruebas + " ultimo_stock_exitos=" + exitos);
    }

    private static MensajeProtocolo comprar(CountDownLatch inicio, String token,
                                             String juegoId, String emisor) throws Exception {
        inicio.await();
        return enviar(request(Constantes.COMPRAR_JUEGO, token).put("juegoId", juegoId), emisor);
    }

    private static String login(String usuario, String password) throws Exception {
        MensajeProtocolo resp = enviar(request(Constantes.LOGIN, null)
                .put("username", usuario).put("password", password), "login-" + usuario);
        check(resp.isOk(), "Login " + usuario);
        return resp.getString("token");
    }

    private static MensajeProtocolo request(String op, String token) {
        return MensajeProtocolo.request(op, token);
    }

    private static MensajeProtocolo enviar(MensajeProtocolo req, String emisor) throws Exception {
        return ClienteProxy.enviar(req, new RelojLamport(emisor), emisor);
    }

    private static MensajeProtocolo clonar(MensajeProtocolo original, String requestId) {
        MensajeProtocolo copia = MensajeProtocolo.fromJson(original.toJson());
        copia.setRequestId(requestId);
        return copia;
    }

    private static boolean contieneMensaje(Object raw, String contenido) {
        if (!(raw instanceof List<?> mensajes)) return false;
        return mensajes.stream().anyMatch(item -> item instanceof java.util.Map<?, ?> mapa
                && contenido.equals(String.valueOf(mapa.get("contenido"))));
    }

    private static List<String> idsMensajes(Object raw) {
        if (!(raw instanceof List<?> mensajes)) return List.of();
        return mensajes.stream().filter(java.util.Map.class::isInstance)
                .map(java.util.Map.class::cast)
                .map(m -> String.valueOf(m.get("id")))
                .filter(id -> !"null".equals(id)).toList();
    }

    private static String hash(String archivo) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(archivo))));
    }

    private static void check(boolean condicion, String mensaje) {
        pruebas++;
        if (!condicion) throw new AssertionError(mensaje);
    }
}
