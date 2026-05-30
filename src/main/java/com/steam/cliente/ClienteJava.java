package com.steam.cliente;

import com.steam.common.Constantes;
import com.steam.common.MensajeProtocolo;
import com.steam.common.RelojLamport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ClienteJava – Interfaz de Consola (Capa de Presentación)
 *
 * El usuario NUNCA ve ni copia IDs internos (UUID):
 *  - Para comprar: elige un número de la lista de juegos.
 *  - Para confirmar: el cliente ofrece confirmar en el momento; si el usuario
 *    postergó, el menú muestra sus reservas pendientes numeradas.
 *
 * Los IDs (juegoId, reservaId) se guardan en mapas de sesión en memoria.
 */
public class ClienteJava {

    // ── Estado de sesión ──────────────────────────────────────────────────────
    private static String token    = null;
    private static String username = null;
    private static String rol      = null;

    // Mapas de sesión: el usuario trabaja con números, no con UUIDs
    /** Número visible → juegoId interno */
    private static final Map<Integer, String> juegoIdxMap    = new LinkedHashMap<>();
    /** Número visible → reservaId interno */
    private static final Map<Integer, String> reservaIdxMap  = new LinkedHashMap<>();

    private static final Scanner       sc           = new Scanner(System.in);
    /** Reloj de Lamport del cliente — tick al enviar, update al recibir. */
    private static final RelojLamport  reloj        = new RelojLamport();

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║       SISTEMA STEAM DISTRIBUIDO      ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Proxy: " + Constantes.HOST + ":" + Constantes.PUERTO_PROXY);

        while (true) {
            if (token == null) menuSinSesion();
            else               menuPorRol();
        }
    }

    // ── Menús ─────────────────────────────────────────────────────────────────

    private static void menuSinSesion() {
        System.out.println("\n──── ACCESO ────");
        System.out.println("1. Iniciar sesión");
        System.out.println("2. Ver catálogo (público)");
        System.out.println("0. Salir");
        System.out.print("Opción: ");
        switch (sc.nextLine().trim()) {
            case "1" -> login();
            case "2" -> listarJuegos();
            case "0" -> { System.out.println("Hasta luego."); System.exit(0); }
            default  -> System.out.println("[!] Opción inválida");
        }
    }

    private static void menuPorRol() {
        System.out.println("\n──── [" + rol + ": " + username + "] ────");
        switch (rol) {
            case Constantes.ROL_COMPRADOR -> menuComprador();
            case Constantes.ROL_VENDEDOR  -> menuVendedor();
            case Constantes.ROL_ADMIN     -> menuAdmin();
        }
    }

    private static void menuComprador() {
        System.out.println("1. Ver catálogo y comprar");
        System.out.println("2. Mis reservas pendientes");
        System.out.println("3. Cancelar una reserva");
        System.out.println("4. Ver saldo de billetera");
        System.out.println("5. Mis compras");
        System.out.println("6. Enviar mensaje");
        System.out.println("7. Ver mensajes recibidos");
        System.out.println("8. Ver conversación");
        System.out.println("9. Cambiar contraseña");
        System.out.println("0. Cerrar sesión");
        System.out.print("Opción: ");
        switch (sc.nextLine().trim()) {
            case "1" -> flujoCompra();
            case "2" -> mostrarYConfirmarReservas();
            case "3" -> cancelarReserva();
            case "4" -> verSaldo();
            case "5" -> verMisCompras();
            case "6" -> enviarMensaje();
            case "7" -> verMensajes();
            case "8" -> verConversacion();
            case "9" -> cambiarPassword();
            case "0" -> logout();
            default  -> System.out.println("[!] Opción inválida");
        }
    }

    private static void menuVendedor() {
        System.out.println("1. Ver catálogo");
        System.out.println("2. Publicar juego");
        System.out.println("3. Mis juegos publicados");
        System.out.println("4. Modificar juego");
        System.out.println("5. Eliminar juego");
        System.out.println("6. Ver saldo (ingresos)");
        System.out.println("7. Ver historial de ventas");
        System.out.println("8. Enviar mensaje");
        System.out.println("9. Ver mensajes recibidos");
        System.out.println("10. Cambiar contraseña");
        System.out.println("0. Cerrar sesión");
        System.out.print("Opción: ");
        switch (sc.nextLine().trim()) {
            case "1"  -> listarJuegos();
            case "2"  -> publicarJuego();
            case "3"  -> verMisJuegos();
            case "4"  -> modificarJuego();
            case "5"  -> eliminarJuego();
            case "6"  -> verSaldo();
            case "7"  -> verHistorial();
            case "8"  -> enviarMensaje();
            case "9"  -> verMensajes();
            case "10" -> cambiarPassword();
            case "0"  -> logout();
            default   -> System.out.println("[!] Opción inválida");
        }
    }

    private static void menuAdmin() {
        System.out.println("1. Ver catálogo");
        System.out.println("2. Registrar usuario");
        System.out.println("3. Listar usuarios");
        System.out.println("4. Agregar saldo a usuario");
        System.out.println("5. Ver estadísticas del sistema");
        System.out.println("6. Ver historial de ventas");
        System.out.println("7. Eliminar juego");
        System.out.println("8. Enviar mensaje");
        System.out.println("9. Ver mensajes recibidos");
        System.out.println("10. Cambiar contraseña");
        System.out.println("0. Cerrar sesión");
        System.out.print("Opción: ");
        switch (sc.nextLine().trim()) {
            case "1"  -> listarJuegos();
            case "2"  -> registrarUsuario();
            case "3"  -> listarUsuarios();
            case "4"  -> agregarSaldo();
            case "5"  -> verEstadisticas();
            case "6"  -> verHistorial();
            case "7"  -> eliminarJuego();
            case "8"  -> enviarMensaje();
            case "9"  -> verMensajes();
            case "10" -> cambiarPassword();
            case "0"  -> logout();
            default   -> System.out.println("[!] Opción inválida");
        }
    }

    // ── Operaciones – Sesiones ────────────────────────────────────────────────

    private static void login() {
        System.out.print("Usuario: ");     String user = sc.nextLine().trim();
        System.out.print("Contraseña: "); String pass = sc.nextLine().trim();

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.LOGIN, null);
        req.put("username", user);
        req.put("password", pass);

        MensajeProtocolo resp = enviar(req);
        if (resp == null) return;

        if (resp.isOk()) {
            token    = resp.getString("token");
            username = resp.getString("username");
            rol      = resp.getString("rol");
            juegoIdxMap.clear();
            reservaIdxMap.clear();
            System.out.println("[✓] " + resp.getMensaje());
        } else {
            System.out.println("[✗] " + resp.getMensaje());
        }
    }

    private static void logout() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.LOGOUT, token));
        System.out.println(resp != null && resp.isOk() ? "[✓] Sesión cerrada" : "[✗] " + (resp != null ? resp.getMensaje() : "Sin respuesta"));
        token = null; username = null; rol = null;
        juegoIdxMap.clear(); reservaIdxMap.clear();
    }

    private static void registrarUsuario() {
        System.out.print("Nuevo username: ");
        String nu = sc.nextLine().trim();
        System.out.print("Contraseña: ");
        String np = sc.nextLine().trim();
        System.out.print("Rol (COMPRADOR/VENDEDOR/ADMINISTRADOR): ");
        String nr = sc.nextLine().trim().toUpperCase();

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.REGISTRAR_USUARIO, token);
        req.put("nuevoUsername", nu);
        req.put("nuevaPassword", np);
        req.put("nuevoRol",      nr);
        imprimirRespuesta(enviar(req));
    }

    private static void listarUsuarios() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.LISTAR_USUARIOS, token));
        if (!okOImprime(resp)) return;
        System.out.println("\n[USUARIOS]");
        imprimirLista(resp.get("usuarios"));
        System.out.println("Sesiones activas: " + resp.get("sesionesActivas"));
    }

    private static void cambiarPassword() {
        System.out.print("Contraseña actual: "); String ca = sc.nextLine().trim();
        System.out.print("Nueva contraseña: ");  String cn = sc.nextLine().trim();
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.CAMBIAR_PASS, token);
        req.put("passActual", ca);
        req.put("passNueva",  cn);
        imprimirRespuesta(enviar(req));
    }

    // ── Flujo de compra simplificado ──────────────────────────────────────────

    /**
     * Flujo unificado: lista juegos → usuario elige número → se reserva →
     * se pregunta si pagar ahora (sin copiar IDs).
     */
    private static void flujoCompra() {
        // 1. Mostrar catálogo numerado
        List<Map<String, Object>> juegos = fetchJuegos();
        if (juegos == null || juegos.isEmpty()) return;

        // 2. Elegir juego por número
        System.out.print("\nElige un número (0 para cancelar): ");
        int opcion;
        try { opcion = Integer.parseInt(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("[!] Número inválido"); return; }

        if (opcion == 0) return;
        String juegoId = juegoIdxMap.get(opcion);
        if (juegoId == null) { System.out.println("[!] Número fuera de rango"); return; }

        // 3. Crear reserva
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.COMPRAR_JUEGO, token);
        req.put("juegoId", juegoId);
        MensajeProtocolo resp = enviar(req);

        if (resp == null || !resp.isOk()) {
            System.out.println("[✗] " + (resp != null ? resp.getMensaje() : "Sin respuesta"));
            return;
        }

        String reservaId    = resp.getString("reservaId");
        String juegoNombre  = resp.getString("juegoNombre");
        double precio       = resp.getDouble("precio");
        long   segs         = (long) resp.getDouble("segundosRestantes");

        System.out.println("[✓] Reserva creada para '" + juegoNombre + "'");
        System.out.printf("    Precio : $%.2f%n", precio);
        System.out.println("    Tienes : " + segs + "s para confirmar el pago");

        // 4. Preguntar si paga ahora (sin que el usuario vea la reservaId)
        System.out.print("¿Pagar ahora? (s/n): ");
        if (sc.nextLine().trim().equalsIgnoreCase("s")) {
            confirmarConId(reservaId);
        } else {
            // Guardar en el mapa para el menú "Mis reservas pendientes"
            System.out.println("[i] Puedes confirmar más tarde desde 'Mis reservas pendientes'.");
        }
    }

    /**
     * Muestra las reservas activas del usuario (numeradas) y permite confirmar
     * una de ellas sin ver ningún UUID.
     */
    private static void mostrarYConfirmarReservas() {
        List<Map<String, Object>> reservas = fetchReservas();
        if (reservas == null) return;
        if (reservas.isEmpty()) {
            System.out.println("[i] No tienes reservas activas.");
            return;
        }

        System.out.print("\nElige el número a confirmar (0 para cancelar): ");
        int opcion;
        try { opcion = Integer.parseInt(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("[!] Número inválido"); return; }

        if (opcion == 0) return;
        String reservaId = reservaIdxMap.get(opcion);
        if (reservaId == null) { System.out.println("[!] Número fuera de rango"); return; }

        confirmarConId(reservaId);
    }

    /** Cancela una reserva eligiendo de la lista numerada (sin IDs visibles). */
    private static void cancelarReserva() {
        List<Map<String, Object>> reservas = fetchReservas();
        if (reservas == null) return;
        if (reservas.isEmpty()) { System.out.println("[i] No tienes reservas activas."); return; }

        System.out.print("\nElige el número a cancelar (0 para volver): ");
        int opcion;
        try { opcion = Integer.parseInt(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("[!] Número inválido"); return; }

        if (opcion == 0) return;
        String reservaId = reservaIdxMap.get(opcion);
        if (reservaId == null) { System.out.println("[!] Número fuera de rango"); return; }

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.CANCELAR_RESERVA, token);
        req.put("reservaId", reservaId);
        imprimirRespuesta(enviar(req));
    }

    // ── Helpers de compra ─────────────────────────────────────────────────────

    /** Llama LISTAR_JUEGOS, muestra tabla numerada y actualiza juegoIdxMap. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fetchJuegos() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.LISTAR_JUEGOS, token));
        if (!okOImprime(resp)) return null;

        List<Map<String, Object>> juegos = (List<Map<String, Object>>) resp.get("juegos");
        if (juegos == null || juegos.isEmpty()) {
            System.out.println("[i] No hay juegos disponibles en este momento.");
            return null;
        }

        juegoIdxMap.clear();
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.printf("║ %-3s %-22s %-8s %-6s ║%n", "#", "Nombre", "Precio", "Stock");
        System.out.println("╠══════════════════════════════════════════════════╣");
        int i = 1;
        for (Map<String, Object> j : juegos) {
            juegoIdxMap.put(i, j.get("id").toString());
            System.out.printf("║ %-3d %-22s $%-7.2f %-6s ║%n",
                    i,
                    truncar(j.get("nombre").toString(), 22),
                    toDouble(j.get("precio")),
                    j.get("stock"));
            i++;
        }
        System.out.println("╚══════════════════════════════════════════════════╝");
        return juegos;
    }

    /** Llama VER_MIS_RESERVAS, muestra lista numerada y actualiza reservaIdxMap. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fetchReservas() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_MIS_RESERVAS, token));
        if (!okOImprime(resp)) return null;

        List<Map<String, Object>> reservas = (List<Map<String, Object>>) resp.get("reservas");
        if (reservas == null) return Collections.emptyList();

        reservaIdxMap.clear();
        if (!reservas.isEmpty()) {
            System.out.println("\n╔══════════════════════════════════════════════════╗");
            System.out.printf("║ %-3s %-22s %-8s %-8s ║%n", "#", "Juego", "Precio", "Expira");
            System.out.println("╠══════════════════════════════════════════════════╣");
            int i = 1;
            for (Map<String, Object> r : reservas) {
                reservaIdxMap.put(i, r.get("reservaId").toString());
                System.out.printf("║ %-3d %-22s $%-7.2f %-7ss ║%n",
                        i,
                        truncar(r.get("juegoNombre").toString(), 22),
                        toDouble(r.get("precio")),
                        r.get("segundosRestantes"));
                i++;
            }
            System.out.println("╚══════════════════════════════════════════════════╝");
        }
        return reservas;
    }

    /** Envía CONFIRMAR_PAGO con el reservaId dado (siempre interno, invisible al usuario). */
    private static void confirmarConId(String reservaId) {
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.CONFIRMAR_PAGO, token);
        req.put("reservaId", reservaId);
        MensajeProtocolo resp = enviar(req);

        if (resp == null) { System.out.println("[RED] Sin respuesta"); return; }
        if (resp.isOk()) {
            System.out.println("[✓] " + resp.getMensaje());
            System.out.printf("    Juego adquirido : %s%n",  resp.get("juegoNombre"));
            System.out.printf("    Pagado          : $%.2f%n", resp.getDouble("precio"));
            System.out.printf("    Saldo restante  : $%.2f%n", resp.getDouble("saldoRestante"));
        } else {
            System.out.println("[✗] " + resp.getMensaje());
        }
    }

    // ── Operaciones – Juegos ──────────────────────────────────────────────────

    private static void listarJuegos() {
        fetchJuegos();
    }

    private static void publicarJuego() {
        System.out.print("Nombre del juego: ");  String nombre = sc.nextLine().trim();
        System.out.print("Descripción: ");        String desc   = sc.nextLine().trim();
        System.out.print("Precio (ej: 29.99): "); double precio;
        try { precio = Double.parseDouble(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("[!] Precio inválido"); return; }
        System.out.print("Stock (unidades): ");   int stock;
        try { stock = Integer.parseInt(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("[!] Stock inválido"); return; }

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.PUBLICAR_JUEGO, token);
        req.put("nombre", nombre); req.put("descripcion", desc);
        req.put("precio", precio); req.put("stock", stock);

        MensajeProtocolo resp = enviar(req);
        if (resp != null && resp.isOk()) System.out.println("[✓] " + resp.getMensaje());
        else System.out.println("[✗] " + (resp != null ? resp.getMensaje() : "Sin respuesta"));
    }

    @SuppressWarnings("unchecked")
    private static void modificarJuego() {
        // Siempre re-fetchar para evitar usar un mapa vacío o desactualizado
        if (fetchJuegos() == null) return;

        System.out.print("Número del juego a modificar (0 = cancelar): ");
        int op;
        try { op = Integer.parseInt(sc.nextLine().trim()); } catch (NumberFormatException e) { return; }
        if (op == 0) return;
        String id = juegoIdxMap.get(op);
        if (id == null) { System.out.println("[!] Número fuera de rango"); return; }

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.MODIFICAR_JUEGO, token);
        req.put("juegoId", id);

        System.out.print("Nuevo nombre (Enter para omitir): ");
        String n = sc.nextLine().trim(); if (!n.isEmpty()) req.put("nombre", n);
        System.out.print("Nueva descripción (Enter para omitir): ");
        String d = sc.nextLine().trim(); if (!d.isEmpty()) req.put("descripcion", d);
        System.out.print("Nuevo precio (Enter para omitir): ");
        String p = sc.nextLine().trim();
        try { if (!p.isEmpty()) req.put("precio", Double.parseDouble(p)); } catch (NumberFormatException ignored) {}
        System.out.print("Stock extra a añadir (Enter para omitir): ");
        String s = sc.nextLine().trim();
        try { if (!s.isEmpty()) req.put("stockExtra", Integer.parseInt(s)); } catch (NumberFormatException ignored) {}

        imprimirRespuesta(enviar(req));
    }

    private static void eliminarJuego() {
        // Siempre re-fetchar para evitar usar un mapa vacío o desactualizado
        if (fetchJuegos() == null) return;
        System.out.print("Número del juego a eliminar (0 = cancelar): ");
        int op;
        try { op = Integer.parseInt(sc.nextLine().trim()); } catch (NumberFormatException e) { return; }
        if (op == 0) return;
        String id = juegoIdxMap.get(op);
        if (id == null) { System.out.println("[!] Número fuera de rango"); return; }

        System.out.print("¿Confirmar eliminación? (s/n): ");
        if (!sc.nextLine().trim().equalsIgnoreCase("s")) { System.out.println("[i] Cancelado"); return; }

        MensajeProtocolo req = MensajeProtocolo.request(Constantes.ELIMINAR_JUEGO, token);
        req.put("juegoId", id);
        imprimirRespuesta(enviar(req));
    }

    private static void verSaldo() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_SALDO, token));
        if (resp != null && resp.isOk())
            System.out.printf("[✓] Saldo de %s: $%.2f%n", resp.get("username"), resp.getDouble("saldo"));
        else System.out.println("[✗] " + (resp != null ? resp.getMensaje() : "Sin respuesta"));
    }

    private static void agregarSaldo() {
        System.out.print("Usuario destino: "); String target = sc.nextLine().trim();
        System.out.print("Monto a agregar: ");
        double monto;
        try { monto = Double.parseDouble(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("[!] Monto inválido"); return; }
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.AGREGAR_SALDO, token);
        req.put("targetUser", target);
        req.put("monto",      monto);
        imprimirRespuesta(enviar(req));
    }

    private static void verHistorial() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_HISTORIAL, token));
        if (!okOImprime(resp)) return;
        System.out.println("\n[HISTORIAL DE VENTAS]");
        imprimirLista(resp.get("historial"));
    }

    private static void verMisCompras() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_MIS_COMPRAS, token));
        if (!okOImprime(resp)) return;
        System.out.println("\n[MIS COMPRAS] " + resp.getMensaje());
        imprimirLista(resp.get("misCompras"));
    }

    private static void verMisJuegos() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_MIS_JUEGOS, token));
        if (!okOImprime(resp)) return;
        System.out.println("\n[MIS JUEGOS PUBLICADOS]");
        imprimirLista(resp.get("misJuegos"));
    }

    private static void verEstadisticas() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_ESTADISTICAS, token));
        if (!okOImprime(resp)) return;
        System.out.println("\n[ESTADÍSTICAS]");
        System.out.println("  Juegos en catálogo : " + resp.get("totalJuegos"));
        System.out.println("  Ventas totales     : " + resp.get("totalVentas"));
        System.out.printf( "  Ingresos totales   : $%.2f%n", resp.getDouble("ingresosTotal"));
        System.out.println("  Reservas activas   : " + resp.get("reservasActivas"));
        System.out.println("  Billeteras         : " + resp.get("billeteras"));
    }

    // ── Operaciones – Mensajería ──────────────────────────────────────────────

    private static void enviarMensaje() {
        System.out.print("Destinatario: "); String dest     = sc.nextLine().trim();
        System.out.print("Mensaje: ");      String contenido = sc.nextLine().trim();
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.ENVIAR_MENSAJE, token);
        req.put("receptor",  dest);
        req.put("contenido", contenido);
        imprimirRespuesta(enviar(req));
    }

    private static void verMensajes() {
        MensajeProtocolo resp = enviar(MensajeProtocolo.request(Constantes.VER_MENSAJES, token));
        if (!okOImprime(resp)) return;
        System.out.println("\n[MENSAJES RECIBIDOS] " + resp.getMensaje());
        imprimirLista(resp.get("mensajes"));
    }

    private static void verConversacion() {
        System.out.print("Conversación con: "); String con = sc.nextLine().trim();
        MensajeProtocolo req = MensajeProtocolo.request(Constantes.VER_CONVERSACION, token);
        req.put("conUsuario", con);
        MensajeProtocolo resp = enviar(req);
        if (!okOImprime(resp)) return;
        System.out.println("\n[CONVERSACIÓN con " + con + "]");
        imprimirLista(resp.get("conversacion"));
    }

    // ── Transporte TCP ────────────────────────────────────────────────────────

    private static MensajeProtocolo enviar(MensajeProtocolo req) {
        // Evento de envío: estampar reloj Lamport antes de enviar
        req.setLamportClock(reloj.tick());
        try (Socket socket = new Socket(Constantes.HOST, Constantes.PUERTO_PROXY)) {
            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            PrintWriter   out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(req.toJson());
            String respJson = in.readLine();
            if (respJson != null) {
                MensajeProtocolo resp = MensajeProtocolo.fromJson(respJson);
                // Evento de recepción: actualizar reloj
                if (resp != null) reloj.update(resp.getLamportClock());
                return resp;
            }
            return null;
        } catch (ConnectException e) {
            System.out.println("[RED] No se puede conectar al Proxy. ¿Está iniciado?");
        } catch (SocketTimeoutException e) {
            System.out.println("[RED] Timeout: el servidor no respondió a tiempo.");
        } catch (IOException e) {
            System.out.println("[RED] Error de red: " + e.getMessage());
        }
        return null;
    }

    // ── Helpers de presentación ───────────────────────────────────────────────

    private static boolean okOImprime(MensajeProtocolo resp) {
        if (resp == null) { System.out.println("[RED] Sin respuesta del servidor"); return false; }
        if (!resp.isOk())  { System.out.println("[✗] " + resp.getMensaje()); return false; }
        return true;
    }

    private static void imprimirRespuesta(MensajeProtocolo resp) {
        if (resp == null) { System.out.println("[RED] Sin respuesta"); return; }
        System.out.println((resp.isOk() ? "[✓] " : "[✗] ") + resp.getMensaje());
    }

    @SuppressWarnings("unchecked")
    private static void imprimirLista(Object obj) {
        if (obj == null) { System.out.println("  (vacío)"); return; }
        if (obj instanceof List<?> list) {
            if (list.isEmpty()) { System.out.println("  (vacío)"); return; }
            for (int i = 0; i < list.size(); i++)
                System.out.println("  " + (i + 1) + ". " + list.get(i));
        } else {
            System.out.println("  " + obj);
        }
    }

    private static String truncar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }
}
