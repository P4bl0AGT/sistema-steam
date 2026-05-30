package com.steam.servidores;

import com.steam.common.*;
import com.steam.coordinacion.*;
import com.steam.models.*;

// ReplicadorServidor y ReplicadorCliente ya están en com.steam.coordinacion.*

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.Collectors;

/**
 * svJuegos – Motor Transaccional de Juegos
 *
 * Puertos: Nodo 1 → 8082 | Nodo 2 → 8282
 * Uso: java ... svJuegos [1|2]
 *
 * Operaciones soportadas:
 *  LISTAR_JUEGOS, VER_JUEGO, COMPRAR_JUEGO, CONFIRMAR_PAGO, CANCELAR_RESERVA,
 *  PUBLICAR_JUEGO, MODIFICAR_JUEGO, ELIMINAR_JUEGO, VER_SALDO, AGREGAR_SALDO,
 *  VER_HISTORIAL, VER_ESTADISTICAS, VER_MIS_COMPRAS, VER_MIS_JUEGOS, HEALTH_CHECK
 *
 * CONCURRENCIA:
 *  - ExecutorService atiende múltiples clientes en paralelo.
 *  - Bloque synchronized(lock) protege TODA lectura/escritura de la BD.
 *  - GestorLocks (daemon) limpia reservas expiradas usando el mismo lock.
 *
 * SEGURIDAD:
 *  - Toda operación (excepto LISTAR_JUEGOS y HEALTH_CHECK) valida el token
 *    llamando a svSesiones vía ValidadorToken.
 *  - Los roles son verificados por cada operación privilegiada.
 *
 * MODELO DE FALLOS:
 *  - Fallo de concurrencia: stock finito protegido por exclusión mutua.
 *  - Reserva con TTL 5 min; si no confirma → stock restaurado por GestorLocks.
 *  - Fallo de persistencia: escritura atómica y replicación Main→Copy.
 */
public class svJuegos {

    private static final Logger      LOG          = Logger.getLogger(svJuegos.class.getName());
    /** Reloj de Lamport compartido por todos los hilos de esta instancia. */
    private static final RelojLamport relojLamport = new RelojLamport();

    private final int                          puerto;
    private final int                          miId;   // nodo 1 o 2
    private final GestorPersistencia<BDJuegos> gp;
    private final ExecutorService              pool = Executors.newFixedThreadPool(Constantes.POOL_SIZE);
    private final Object                       lock = new Object(); // monitor compartido con GestorLocks

    // ── Coordinación distribuida ──────────────────────────────────────────────
    private GestorBully               bully;
    private GestorMutexCentralizado   mutex;

    // ── Constructor ───────────────────────────────────────────────────────────

    public svJuegos(int nodo, int puerto) {
        this.miId   = nodo;
        this.puerto = puerto;
        this.gp     = new GestorPersistencia<>(
                Constantes.GME_MAIN, Constantes.GME_COPY, BDJuegos.class);

        // Sembrar solo si AMBOS archivos (Main y Copy) están ausentes o vacíos.
        // Si el Nodo 1 ya escribió Main+Copy, el Nodo 2 verá al menos uno no vacío
        // y no re-sembrará, evitando entradas duplicadas por arranque simultáneo.
        BDJuegos bd = gp.leer();
        if (archivoVacio(Constantes.GME_MAIN) && archivoVacio(Constantes.GME_COPY)
                && (bd == null || bd.catalogo.isEmpty())) {
            if (bd == null) bd = new BDJuegos();
            bd.catalogo.add(new Juego(uuid(), "Counter-Strike 2",
                    "FPS táctico multijugador", 29.99, 50, "vendedor1"));
            bd.catalogo.add(new Juego(uuid(), "Cyberpunk 2077",
                    "RPG de mundo abierto futurista", 59.99, 20, "vendedor1"));
            bd.catalogo.add(new Juego(uuid(), "Stardew Valley",
                    "Simulador de granja relajante", 14.99, 100, "vendedor1"));
            bd.billeteras.put("cliente1",  500.0);
            bd.billeteras.put("cliente2",  200.0);
            bd.billeteras.put("vendedor1", 0.0);
            bd.billeteras.put("admin",     0.0);
            try { gp.guardar(bd); } catch (IOException e) { LOG.severe("No se pudo sembrar BD: " + e.getMessage()); }
        }
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        int nodo   = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int puerto = (nodo == 2) ? Constantes.PUERTO_JUE_2 : Constantes.PUERTO_JUE_1;
        GestorLog.configurar("svJuegos-" + nodo);

        svJuegos sv = new svJuegos(nodo, puerto);

        // ── Configurar Bully y Mutex ──────────────────────────────────────────
        int   puertoBully = (nodo == 2) ? Constantes.PUERTO_JUE_2_BULLY : Constantes.PUERTO_JUE_1_BULLY;
        int   puertoMutex = (nodo == 2) ? Constantes.PUERTO_JUE_2_MUTEX : Constantes.PUERTO_JUE_1_MUTEX;

        // Peer es el otro nodo
        int          peerId      = (nodo == 1) ? 2 : 1;
        int          peerBully   = (nodo == 1) ? Constantes.PUERTO_JUE_2_BULLY : Constantes.PUERTO_JUE_1_BULLY;
        int          peerMutex   = (nodo == 1) ? Constantes.PUERTO_JUE_2_MUTEX : Constantes.PUERTO_JUE_1_MUTEX;
        List<NodoInfo> peers     = List.of(new NodoInfo(peerId, Constantes.HOST, peerBully, peerMutex));

        sv.bully = new GestorBully(nodo, puertoBully, peers, relojLamport);
        sv.mutex = new GestorMutexCentralizado(nodo, puertoMutex, sv.bully, relojLamport, peers);

        // ── Snapshot periódico: Main → Copy cada 30s (solo nodo 1) ──────────
        if (nodo == 1) {
            new GestorSnapshot(Constantes.GME_MAIN, Constantes.GME_COPY,
                    "svJuegos-" + nodo, 30).start();
        }

        sv.mutex.startServidor();
        sv.bully.start();          // lanza elección automáticamente
        sv.iniciarGestorLocks();
        sv.escuchar();
    }

    public void escuchar() {
        LOG.info("=== svJuegos iniciado en puerto " + puerto + " ===");
        try (ServerSocket server = new ServerSocket(puerto)) {
            server.setReuseAddress(true);
            while (true) {
                Socket cliente = server.accept();
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (IOException e) {
            LOG.severe("Error en svJuegos:" + puerto + " → " + e.getMessage());
        }
    }

    /** Inicia el GestorLocks como hilo daemon. */
    private void iniciarGestorLocks() {
        Thread t = new Thread(new GestorLocks(gp, lock), "gestor-locks");
        t.setDaemon(true);
        t.start();
        LOG.info("[JUEGOS] GestorLocks daemon iniciado");
    }

    // ── Manejo de conexión ────────────────────────────────────────────────────

    private void manejarCliente(Socket socket) {
        try (socket;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter    out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(Constantes.TIMEOUT_MS);
            String linea = in.readLine();
            if (linea == null) return;

            MensajeProtocolo req = MensajeProtocolo.fromJson(linea);
            // Evento de recepción: actualizar reloj de Lamport
            relojLamport.update(req.getLamportClock());

            MensajeProtocolo resp = procesar(req);
            // Estampar reloj de Lamport en la respuesta
            resp.setLamportClock(relojLamport.tick());
            out.println(resp.toJson());

        } catch (SocketTimeoutException e) {
            LOG.warning("[JUEGOS] Timeout en cliente");
        } catch (IOException e) {
            LOG.warning("[JUEGOS] IO: " + e.getMessage());
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private MensajeProtocolo procesar(MensajeProtocolo req) {
        if (req == null) return MensajeProtocolo.error("?", "Mensaje inválido");
        // HEALTH_CHECK en FINE; resto en INFO con marca Lamport
        boolean esHC = Constantes.HEALTH_CHECK.equals(req.getOperacion());
        LOG.log(esHC ? java.util.logging.Level.FINE : java.util.logging.Level.INFO,
                "[JUEGOS] op=" + req.getOperacion() + " rId=" + req.getRequestId());
        if (!esHC) LOG.info("[LAMPORT] t=" + relojLamport.get() + " op=" + req.getOperacion());

        return switch (req.getOperacion()) {
            case Constantes.HEALTH_CHECK          -> healthCheck(req);
            case Constantes.QUIEN_ES_COORDINADOR  -> quienEsCoordinador(req);
            case Constantes.SHUTDOWN_GRACEFUL     -> shutdownGraceful(req);
            case Constantes.LISTAR_JUEGOS     -> listarJuegos(req);
            case Constantes.VER_JUEGO         -> verJuego(req);
            case Constantes.COMPRAR_JUEGO     -> comprarJuego(req);
            case Constantes.CONFIRMAR_PAGO    -> confirmarPago(req);
            case Constantes.CANCELAR_RESERVA  -> cancelarReserva(req);
            case Constantes.PUBLICAR_JUEGO    -> publicarJuego(req);
            case Constantes.MODIFICAR_JUEGO   -> modificarJuego(req);
            case Constantes.ELIMINAR_JUEGO    -> eliminarJuego(req);
            case Constantes.VER_SALDO         -> verSaldo(req);
            case Constantes.AGREGAR_SALDO     -> agregarSaldo(req);
            case Constantes.VER_HISTORIAL     -> verHistorial(req);
            case Constantes.VER_MIS_COMPRAS   -> verMisCompras(req);
            case Constantes.VER_MIS_JUEGOS    -> verMisJuegos(req);
            case Constantes.VER_MIS_RESERVAS  -> verMisReservas(req);
            case Constantes.VER_ESTADISTICAS  -> verEstadisticas(req);
            default -> MensajeProtocolo.error(req.getRequestId(),
                    "Operación no soportada: " + req.getOperacion());
        };
    }

    // ── Autenticación interna ─────────────────────────────────────────────────

    /** Valida token y retorna el resultado; si inválido, retorna mensaje de error. */
    private ValidadorToken.ResultadoValidacion autenticar(MensajeProtocolo req) {
        return ValidadorToken.validar(req.getToken());
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private MensajeProtocolo healthCheck(MensajeProtocolo req) {
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "svJuegos OK");
        resp.put("puerto", puerto);
        return resp;
    }

    private MensajeProtocolo quienEsCoordinador(MensajeProtocolo req) {
        int coord = (bully != null) ? bully.getCoordinadorActual() : -1;
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Coordinador actual");
        resp.put("coordinadorActual", coord);
        resp.put("soyCoordinador",    bully != null && bully.isCoordinador());
        resp.put("miId",              miId);
        return resp;
    }

    private MensajeProtocolo shutdownGraceful(MensajeProtocolo req) {
        LOG.warning("[FALLA] t=" + relojLamport.get()
                + " Nodo " + miId + " recibió SHUTDOWN, cerrando...");
        MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Cerrando nodo-" + miId);
        // Responder antes de salir para que el emisor reciba ACK
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            System.exit(0);
        }, "shutdown").start();
        return resp;
    }

    /** LISTAR_JUEGOS: público, no requiere token. */
    private MensajeProtocolo listarJuegos(MensajeProtocolo req) {
        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            List<Map<String, Object>> lista = bd.catalogo.stream()
                    .filter(j -> j.activo && j.stock > 0)
                    .map(this::juegoToMap)
                    .collect(Collectors.toList());

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Juegos disponibles: " + lista.size());
            resp.put("juegos", lista);
            return resp;
        }
    }

    /** VER_JUEGO: detalle de un juego específico por id. */
    private MensajeProtocolo verJuego(MensajeProtocolo req) {
        String juegoId = req.getString("juegoId");
        if (juegoId == null) return MensajeProtocolo.error(req.getRequestId(), "Falta juegoId");

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            return bd.catalogo.stream()
                    .filter(j -> j.id.equals(juegoId) && j.activo)
                    .findFirst()
                    .map(j -> {
                        MensajeProtocolo r = MensajeProtocolo.ok(req.getRequestId(), j.nombre);
                        r.put("juego", juegoToMap(j));
                        return r;
                    })
                    .orElse(MensajeProtocolo.error(req.getRequestId(), "Juego no encontrado"));
        }
    }

    /**
     * COMPRAR_JUEGO – Fase 1: Reserva temporal (TTL 5 min)
     *
     * Región Crítica: stock finito.
     * synchronized garantiza que dos compradores simultáneos no reserven
     * el mismo último ejemplar (exclusión mutua explícita).
     */
    private MensajeProtocolo comprarJuego(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) {
            return MensajeProtocolo.error(req.getRequestId(), "Token inválido: " + auth.mensaje());
        }

        String juegoId  = req.getString("juegoId");
        if (juegoId == null) return MensajeProtocolo.error(req.getRequestId(), "Falta juegoId");

        // ── Mutex distribuido (solo si soy no-coordinador) ────────────────────
        boolean usaMutex = bully != null && !bully.isCoordinador()
                           && bully.getCoordinadorActual() != -1;
        if (usaMutex) {
            try { mutex.requestLock("stock", miId); }
            catch (MutexTimeoutException e) {
                LOG.warning("[MUTEX] Timeout en COMPRAR_JUEGO: " + e.getMessage());
                return MensajeProtocolo.error(req.getRequestId(),
                        "Coordinador no disponible temporalmente. Reintenta.");
            }
        }
        // ── REGIÓN CRÍTICA: Gestión de Stock Finito ──
        try { synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Juego juego = bd.catalogo.stream()
                    .filter(j -> j.id.equals(juegoId) && j.activo)
                    .findFirst().orElse(null);

            if (juego == null) {
                return MensajeProtocolo.error(req.getRequestId(), "Juego no encontrado");
            }
            if (juego.stock <= 0) {
                return MensajeProtocolo.error(req.getRequestId(), "Sin stock: Juego no disponible");
            }

            // Verificar si el usuario ya tiene una reserva activa para este juego
            boolean yaReservado = bd.reservas.stream()
                    .anyMatch(r -> r.activa && r.username.equals(auth.username())
                            && r.juegoId.equals(juegoId));
            if (yaReservado) {
                return MensajeProtocolo.error(req.getRequestId(),
                        "Ya tienes una reserva activa para este juego");
            }

            // Verificar si el usuario ya compró este juego
            boolean yaComprado = bd.ventas.stream()
                    .anyMatch(v -> v.comprador.equals(auth.username()) && v.juegoId.equals(juegoId));
            if (yaComprado) {
                return MensajeProtocolo.error(req.getRequestId(), "Ya posees este juego");
            }

            // Reducir stock y crear reserva
            juego.stock--;

            Reserva reserva = new Reserva(uuid(), juegoId, juego.nombre,
                    auth.username(), juego.precio, Constantes.TTL_RESERVA_MS);
            bd.reservas.add(reserva);

            try {
                gp.guardar(bd);  // escritura Main + replicación Copy
            } catch (IOException e) {
                // Rollback en memoria (no llegó a guardarse)
                juego.stock++;
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Reserva lista. Tienes " + reserva.segundosRestantes() + "s para pagar.");
            resp.put("reservaId",       reserva.reservaId);
            resp.put("juegoNombre",     juego.nombre);
            resp.put("precio",          juego.precio);
            resp.put("expiraEn",        reserva.expiraEn);
            resp.put("segundosRestantes", reserva.segundosRestantes());
            return resp;
        } } finally {
            // Liberar mutex distribuido siempre, incluso si hubo excepción
            if (usaMutex) mutex.releaseLock("stock", miId);
        }
    }

    /**
     * CONFIRMAR_PAGO – Fase 2: Finalización de Venta
     *
     * Verifica que la reserva esté vigente, descuenta saldo de billetera,
     * registra la venta y libera el lock.
     */
    private MensajeProtocolo confirmarPago(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) {
            return MensajeProtocolo.error(req.getRequestId(), "Token inválido: " + auth.mensaje());
        }

        String reservaId = req.getString("reservaId");
        if (reservaId == null) return MensajeProtocolo.error(req.getRequestId(), "Falta reservaId");

        // ── Mutex distribuido (solo si soy no-coordinador) ────────────────────
        boolean usaMutexPago = bully != null && !bully.isCoordinador()
                               && bully.getCoordinadorActual() != -1;
        if (usaMutexPago) {
            try { mutex.requestLock("stock", miId); }
            catch (MutexTimeoutException e) {
                return MensajeProtocolo.error(req.getRequestId(),
                        "Coordinador no disponible. Reintenta.");
            }
        }
        // ── REGIÓN CRÍTICA: Finalización de Venta ──
        try { synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Reserva reserva = bd.reservas.stream()
                    .filter(r -> r.reservaId.equals(reservaId) && r.activa
                            && r.username.equals(auth.username()))
                    .findFirst().orElse(null);

            if (reserva == null) {
                return MensajeProtocolo.error(req.getRequestId(), "Reserva no encontrada");
            }
            if (reserva.expirada()) {
                reserva.activa = false;
                // Restaurar stock (el daemon puede no haber corrido aún)
                bd.catalogo.stream().filter(j -> j.id.equals(reserva.juegoId))
                        .findFirst().ifPresent(j -> j.stock++);
                try { gp.guardar(bd); } catch (IOException ignored) {}
                return MensajeProtocolo.error(req.getRequestId(),
                        "Tiempo agotado. La reserva expiró y el stock fue liberado.");
            }

            // Inicialización defensiva: usuarios creados por el admin pueden no tener
            // entrada explícita en billeteras hasta su primera transacción.
            bd.billeteras.putIfAbsent(auth.username(), 0.0);

            // Verificar saldo
            double saldo = bd.billeteras.getOrDefault(auth.username(), 0.0);
            if (saldo < reserva.precio) {
                return MensajeProtocolo.error(req.getRequestId(),
                        "Saldo insuficiente. Tienes $" + saldo +
                        ", necesitas $" + reserva.precio);
            }

            // Descontar saldo comprador
            bd.billeteras.put(auth.username(), saldo - reserva.precio);

            // Acreditar al vendedor
            Juego juego = bd.catalogo.stream()
                    .filter(j -> j.id.equals(reserva.juegoId)).findFirst().orElse(null);
            if (juego != null) {
                double saldoVendedor = bd.billeteras.getOrDefault(juego.vendedor, 0.0);
                bd.billeteras.put(juego.vendedor, saldoVendedor + reserva.precio);
                juego.totalVentas++;
            }

            // Registrar venta
            String vendedor = juego != null ? juego.vendedor : "desconocido";
            bd.ventas.add(new Venta(uuid(), reserva.juegoId, reserva.juegoNombre,
                    auth.username(), vendedor, reserva.precio));

            // Cerrar reserva (liberar lock)
            reserva.activa = false;

            try {
                gp.guardar(bd);  // Main + Copy
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Compra Exitosa");
            resp.put("juegoNombre",  reserva.juegoNombre);
            resp.put("precio",       reserva.precio);
            resp.put("saldoRestante", bd.billeteras.get(auth.username()));
            return resp;
        } } finally {
            if (usaMutexPago) mutex.releaseLock("stock", miId);
        }
    }

    /** CANCELAR_RESERVA: el usuario puede cancelar una reserva activa propia. */
    private MensajeProtocolo cancelarReserva(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        String reservaId = req.getString("reservaId");
        if (reservaId == null) return MensajeProtocolo.error(req.getRequestId(), "Falta reservaId");

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Reserva reserva = bd.reservas.stream()
                    .filter(r -> r.reservaId.equals(reservaId) && r.activa
                            && r.username.equals(auth.username()))
                    .findFirst().orElse(null);

            if (reserva == null) {
                return MensajeProtocolo.error(req.getRequestId(), "Reserva no encontrada");
            }

            reserva.activa = false;
            bd.catalogo.stream().filter(j -> j.id.equals(reserva.juegoId))
                    .findFirst().ifPresent(j -> j.stock++);

            try {
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(),
                    "Reserva cancelada. Stock de '" + reserva.juegoNombre + "' restaurado.");
        }
    }

    /**
     * PUBLICAR_JUEGO: VENDEDOR o ADMINISTRADOR.
     * SEGURIDAD: rol verificado antes de crear el juego.
     */
    private MensajeProtocolo publicarJuego(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());
        if (!List.of(Constantes.ROL_VENDEDOR, Constantes.ROL_ADMIN).contains(auth.rol())) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "Acceso denegado: se requiere rol VENDEDOR o ADMINISTRADOR");
        }

        String nombre = req.getString("nombre");
        String desc   = req.getString("descripcion");
        double precio = req.getDouble("precio");
        int    stock  = req.getInt("stock");

        if (nombre == null || desc == null || precio <= 0 || stock <= 0) {
            return MensajeProtocolo.error(req.getRequestId(),
                    "Campos requeridos: nombre, descripcion, precio (>0), stock (>0)");
        }

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Juego nuevo = new Juego(uuid(), nombre, desc, precio, stock, auth.username());
            bd.catalogo.add(nuevo);
            // Asegurar billetera del vendedor
            bd.billeteras.putIfAbsent(auth.username(), 0.0);

            try {
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Juego '" + nombre + "' publicado");
            resp.put("juegoId", nuevo.id);
            return resp;
        }
    }

    /** MODIFICAR_JUEGO: el vendedor dueño o ADMINISTRADOR pueden modificarlo. */
    private MensajeProtocolo modificarJuego(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        String juegoId = req.getString("juegoId");
        if (juegoId == null) return MensajeProtocolo.error(req.getRequestId(), "Falta juegoId");

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Juego juego = bd.catalogo.stream()
                    .filter(j -> j.id.equals(juegoId) && j.activo).findFirst().orElse(null);
            if (juego == null) return MensajeProtocolo.error(req.getRequestId(), "Juego no encontrado");

            if (!auth.username().equals(juego.vendedor) && !Constantes.ROL_ADMIN.equals(auth.rol())) {
                return MensajeProtocolo.error(req.getRequestId(), "No eres el propietario del juego");
            }

            if (req.getString("nombre")      != null) juego.nombre      = req.getString("nombre");
            if (req.getString("descripcion") != null) juego.descripcion = req.getString("descripcion");
            if (req.getDouble("precio")      >  0)    juego.precio      = req.getDouble("precio");
            if (req.getInt("stockExtra")     >  0)    juego.stock      += req.getInt("stockExtra");

            try {
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(), "Juego actualizado");
        }
    }

    /** ELIMINAR_JUEGO: solo el dueño o ADMINISTRADOR. */
    private MensajeProtocolo eliminarJuego(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        String juegoId = req.getString("juegoId");
        if (juegoId == null) return MensajeProtocolo.error(req.getRequestId(), "Falta juegoId");

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            Juego juego = bd.catalogo.stream()
                    .filter(j -> j.id.equals(juegoId) && j.activo).findFirst().orElse(null);
            if (juego == null) return MensajeProtocolo.error(req.getRequestId(), "Juego no encontrado");
            if (!auth.username().equals(juego.vendedor) && !Constantes.ROL_ADMIN.equals(auth.rol())) {
                return MensajeProtocolo.error(req.getRequestId(), "Acceso denegado");
            }

            juego.activo = false;
            try {
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }
            return MensajeProtocolo.ok(req.getRequestId(), "Juego '" + juego.nombre + "' eliminado");
        }
    }

    /** VER_SALDO: consulta la billetera del usuario autenticado. */
    private MensajeProtocolo verSaldo(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            double saldo = bd.billeteras.getOrDefault(auth.username(), 0.0);
            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Saldo de " + auth.username());
            resp.put("saldo",    saldo);
            resp.put("username", auth.username());
            return resp;
        }
    }

    /** AGREGAR_SALDO: solo ADMINISTRADOR puede recargar saldo a cualquier usuario. */
    private MensajeProtocolo agregarSaldo(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());
        if (!Constantes.ROL_ADMIN.equals(auth.rol())) {
            return MensajeProtocolo.error(req.getRequestId(), "Acceso denegado");
        }

        String targetUser = req.getString("targetUser");
        double monto      = req.getDouble("monto");

        if (targetUser == null || monto <= 0) {
            return MensajeProtocolo.error(req.getRequestId(), "Faltan campos: targetUser, monto (>0)");
        }

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            double actual = bd.billeteras.getOrDefault(targetUser, 0.0);
            bd.billeteras.put(targetUser, actual + monto);

            try {
                gp.guardar(bd);
            } catch (IOException e) {
                return MensajeProtocolo.error(req.getRequestId(), "Error de persistencia");
            }

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Saldo agregado a " + targetUser);
            resp.put("nuevoSaldo", actual + monto);
            return resp;
        }
    }

    /** VER_HISTORIAL: historial de ventas (ADMINISTRADOR) o del usuario autenticado. */
    private MensajeProtocolo verHistorial(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            List<Map<String, Object>> lista = bd.ventas.stream()
                    .filter(v -> Constantes.ROL_ADMIN.equals(auth.rol())
                            || v.comprador.equals(auth.username())
                            || v.vendedor.equals(auth.username()))
                    .map(v -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("juegoNombre", v.juegoNombre);
                        m.put("comprador",   v.comprador);
                        m.put("vendedor",    v.vendedor);
                        m.put("precio",      v.precio);
                        m.put("fecha",       new java.util.Date(v.timestamp).toString());
                        return m;
                    })
                    .collect(Collectors.toList());

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Transacciones: " + lista.size());
            resp.put("historial", lista);
            return resp;
        }
    }

    /** VER_MIS_COMPRAS: juegos comprados por el usuario autenticado. */
    private MensajeProtocolo verMisCompras(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            List<String> juegos = bd.ventas.stream()
                    .filter(v -> v.comprador.equals(auth.username()))
                    .map(v -> v.juegoNombre + " ($" + v.precio + ")")
                    .collect(Collectors.toList());

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Tus juegos: " + juegos.size());
            resp.put("misCompras", juegos);
            return resp;
        }
    }

    /** VER_MIS_RESERVAS: reservas activas del usuario autenticado. */
    private MensajeProtocolo verMisReservas(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            List<Map<String, Object>> lista = new ArrayList<>();
            for (Reserva r : bd.reservas) {
                if (!r.activa || !r.username.equals(auth.username())) continue;
                if (r.expirada()) continue; // el GestorLocks la limpiará
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reservaId",        r.reservaId);
                m.put("juegoNombre",      r.juegoNombre);
                m.put("precio",           r.precio);
                m.put("segundosRestantes", r.segundosRestantes());
                lista.add(m);
            }
            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Reservas activas: " + lista.size());
            resp.put("reservas", lista);
            return resp;
        }
    }

    /** VER_MIS_JUEGOS: juegos publicados por el vendedor autenticado. */
    private MensajeProtocolo verMisJuegos(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            List<Map<String, Object>> lista = bd.catalogo.stream()
                    .filter(j -> j.vendedor.equals(auth.username()) && j.activo)
                    .map(this::juegoToMap)
                    .collect(Collectors.toList());

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(),
                    "Juegos publicados: " + lista.size());
            resp.put("misJuegos", lista);
            return resp;
        }
    }

    /** VER_ESTADISTICAS: solo ADMINISTRADOR. */
    private MensajeProtocolo verEstadisticas(MensajeProtocolo req) {
        ValidadorToken.ResultadoValidacion auth = autenticar(req);
        if (!auth.valido()) return MensajeProtocolo.error(req.getRequestId(), auth.mensaje());
        if (!Constantes.ROL_ADMIN.equals(auth.rol())) {
            return MensajeProtocolo.error(req.getRequestId(), "Acceso denegado");
        }

        synchronized (lock) {
            BDJuegos bd = gp.leer();
            if (bd == null) return MensajeProtocolo.error(req.getRequestId(), "BD no disponible");

            double ingresos = bd.ventas.stream().mapToDouble(v -> v.precio).sum();
            long reservasActivas = bd.reservas.stream().filter(r -> r.activa).count();

            MensajeProtocolo resp = MensajeProtocolo.ok(req.getRequestId(), "Estadísticas del sistema");
            resp.put("totalJuegos",       bd.catalogo.stream().filter(j -> j.activo).count());
            resp.put("totalVentas",       bd.ventas.size());
            resp.put("ingresosTotal",     ingresos);
            resp.put("reservasActivas",   reservasActivas);
            resp.put("billeteras",        bd.billeteras);
            return resp;
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private Map<String, Object> juegoToMap(Juego j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          j.id);
        m.put("nombre",      j.nombre);
        m.put("descripcion", j.descripcion);
        m.put("precio",      j.precio);
        m.put("stock",       j.stock);
        m.put("vendedor",    j.vendedor);
        return m;
    }

    private static String uuid() { return UUID.randomUUID().toString(); }

    /** Retorna true si el archivo no existe o tiene tamaño 0. */
    private static boolean archivoVacio(String path) {
        File f = new File(path);
        return !f.exists() || f.length() == 0;
    }
}
