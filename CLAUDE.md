# Sistema Steam Distribuido

Proyecto universitario (ICI-4344 Computacion Paralela y Distribuida, PUCV). Sistema de gestion de activos digitales tipo Steam, implementado como arquitectura distribuida en Java 17 puro (sin Maven runtime, sin Spring). Unica dependencia: Gson 2.10.1.

## Estructura del proyecto

```
src/main/java/com/steam/
  common/         Infraestructura compartida (protocolo, persistencia, transporte, seguridad, relojes)
  models/         POJOs: Usuario, Juego, Reserva, Venta, Mensaje, BD{Sesiones,Juegos,Mensajeria}
  servidores/     svSesiones, svJuegos, svMensajeria (3 servicios x 2 nodos = 6 JVMs)
  proxy/          Proxy.java (gateway redundante, 2 instancias)
  coordinacion/   GestorBully, GestorMutexCentralizado, RegistroMembresia, mensajes Bully/Mutex
  carga/          GeneradorCarga (prueba de trafico), FallaInducida
  watchdog/       WatchdogServidor (reinicio automatico de nodos caidos)
  cliente/        ClienteJava (interfaz de consola)
src/test/java/    PruebasComponentes, PruebaIntegracion, PruebaDisponibilidad
scripts/          Scripts PS1/BAT numerados para build, arranque, pruebas
config/           steam.properties (perfil demo), steam-tls.properties
lib/              gson-2.10.1.jar
data/             Almacenamiento en runtime (Main.json/Copy.json por nodo)
```

## Compilacion y ejecucion

```powershell
# Compilar (descarga Gson si falta, javac --release 17, genera sistema-steam.jar)
.\scripts\1_build.ps1

# Levantar los 8 procesos (6 servidores + 2 proxies), verifica 18 puertos
.\scripts\start_all.ps1

# Detener todo
.\scripts\stop_all.ps1

# Prueba de trafico completa (build + reset + start + carga + falla + stop)
.\scripts\13_run_prueba_trafico.ps1 -Hilos 50 -DuracionSeg 60

# Cliente de consola
.\scripts\9_run_cliente.bat
```

Classpath manual: `java -cp "target\classes;lib\gson-2.10.1.jar" <clase> [args]`

## Arquitectura (8 JVMs, 18 puertos TCP)

### Servicios (2 nodos cada uno, leader/follower con nodo 1 como escritor por defecto)
| Servicio     | Nodo 1      | Nodo 2      | Bully       | Mutex       | Replicacion    |
|-------------|-------------|-------------|-------------|-------------|----------------|
| Sesiones    | 8081        | 8181        | -           | -           | 9483 / 9583    |
| Juegos      | 8082        | 8282        | 9082 / 9282 | 9182 / 9382 | 9482 / 9582    |
| Mensajeria  | 8083        | 8383        | -           | -           | 9484 / 9584    |
| Proxy       | 8080        | 8085        | -           | -           | -              |

### Orden de arranque
Servidores ANTES del proxy. El nodo de mayor id (nodo 2) gana la eleccion Bully al inicio. start_all.ps1 ya respeta este orden.

## Protocolo de comunicacion

Todos los componentes se comunican via TCP con JSON (una linea por mensaje). El sobre es `MensajeProtocolo`:
- `requestId` (UUID), `tipo` (REQUEST/RESPONSE), `operacion`, `token`, `payload` (Map), `status` (OK/ERROR), `lamportClock`, `emisor`, `receptor`, `timestamp`, `codigoError`

## Funcionalidades principales

### F1: Compra de juegos en 2 fases
1. COMPRAR_JUEGO: valida token, adquiere mutex distribuido sobre "stock", decrementa stock, crea Reserva (TTL 5 min)
2. CONFIRMAR_PAGO: valida reserva vigente, descuenta billetera comprador, acredita vendedor, registra Venta
- GestorLocks (daemon) limpia reservas expiradas y restaura stock

### F2: Mensajeria 1-a-1
- ENVIAR_MENSAJE: almacena con reloj Lamport para orden causal
- VER_MENSAJES: entrega pendientes (offline recovery), marca como entregados
- VER_CONVERSACION: historial ordenado por Lamport

## Mecanismos distribuidos

### Relojes de Lamport
Cada servicio y cliente mantiene un RelojLamport. tick() al enviar, update() al recibir. Los mensajes de chat se ordenan causalmente por lamportClock.

### Algoritmo Bully (solo cluster Juegos)
- Puertos dedicados 9082/9282. Eleccion: ELECTION -> OK -> COORDINATOR
- Heartbeat cada 5s del no-coordinador al coordinador
- Si heartbeat falla -> nueva eleccion automatica (loop iterativo, max 5 intentos, sin recursion)

### Mutex centralizado
- El coordinador elegido por Bully gestiona locks con lease (15s por defecto)
- REQUEST -> GRANT/TIMEOUT, RELEASE -> RELEASED
- Si el coordinador cae, tras nueva eleccion Bully los locks se reasignan

### Replicacion de estado
- Modelo leader-follower con failover: nodo escritor (configurable, default=1) hace PUSH al peer tras cada escritura
- Si el escritor cae, el secundario acepta escrituras automaticamente (detectado via isPeerAlcanzable)
- Al recuperarse el primario, el sync bidireccional reconcilia: el nodo con version mayor prevalece
- Nodo secundario hace PULL periodico (cada 5s) para sincronizar
- Versionado con secuencia monotonica (version = secuencia << 8 | nodoId)
- registrarCambioLocal: version y snapshot serializados dentro de synchronized, push de red fuera del lock
- Respuestas de escritura incluyen campo replicaConfirmada (true/false)

### Persistencia
- GestorPersistencia: escritura atomica (write temp + rename) en Main.json
- GestorSnapshot: lee Main con readAllBytes (atomico) y escribe Copy cada 30s (escalonado entre nodos)
- Failover: si Main corrupto, promueve Copy automaticamente
- Cada nodo tiene su propio directorio: data/{servicio}-{nodo}/
- Pruning automatico: sesiones expiradas se limpian en LOGIN, mensajes entregados >7 dias se eliminan en VER_MENSAJES

## Seguridad
- Passwords: PBKDF2-HMAC-SHA256 con sal aleatoria (60k iteraciones)
- Tokens: UUID aleatorio, TTL 30 min
- Operaciones de control (SHUTDOWN, REGISTRAR_NODO, QUIEN_ES_COORDINADOR, VER_METRICAS_COORD, ESTADO_REPLICACION): firmadas con HMAC-SHA256 usando steam.control.secret
- Validacion de frescura: timestamp con ventana configurable (60s default)
- Mensajes Bully/Mutex/Replicacion: firmados con HMAC (firma validada al recibir)
- Contenido de mensajes de chat limitado a 4096 caracteres
- LineaJson limita payloads por bytes UTF-8 estimados (no por chars)
- getDouble/getInt manejan NumberFormatException sin cerrar la conexion
- Codigos de error auto-inferidos por inferirCodigo() para llamadas legacy al factory 2-param

## Roles
- COMPRADOR: comprar juegos, ver saldo, enviar/recibir mensajes
- VENDEDOR: publicar/modificar/eliminar juegos propios
- ADMINISTRADOR: todo lo anterior + gestionar usuarios, agregar saldo, ver estadisticas

## Datos sembrados (al primer arranque con data/ vacio)
- admin/admin123 (ADMINISTRADOR), vendedor1/pass123 (VENDEDOR)
- cliente1..cliente50/pass123 (COMPRADORES, para prueba de carga)
- 5 juegos con stock=200, billeteras cliente1..50 con $1000

## Prueba de trafico (rubrica 3)
GeneradorCarga: N hilos durante M segundos, mezcla de operaciones (50% listar, 20% saldo, 12% mensajes, 13% enviar msg, 5% comprar+confirmar). Tras COMPRAR_JUEGO exitoso, ejecuta CONFIRMAR_PAGO automaticamente (prueba compras completas). Login fallido se reintenta cada 500ms y se contabiliza como indisponibilidad+loginsFallidos. FallaInducida mata al coordinador Bully a los T segundos (busca coordinador por soyCoordinador o coordinadorActual). El reporte (JSON+CSV+SVG) se guarda en evidencia/carga/.

## Tests
- PruebasComponentes: unit tests de modelos y utilidades
- PruebaIntegracion: flujo completo con servidores levantados
- PruebaDisponibilidad: prueba de failover

## Configuracion
Todo configurable via `config/steam.properties`, system properties (`-Dsteam.xxx`), o env vars (`STEAM_XXX`). Prioridad: system prop > env var > properties file.

Clave importante: `steam.demo.mode=true` permite usar el secret de demo. En produccion se requiere `STEAM_CONTROL_SECRET` como env var.

## Rubricas del curso

Las instrucciones y rubricas de evaluacion estan en la raiz del proyecto:

- `instruparcialparalela.md` — Proyecto Parcial (evaluacion I: 40% informe, 30% codigo, 30% presentacion)
- `instrufinalparalela.md` — Proyecto Final (evaluacion I: 35% informe, 35% codigo, 30% presentacion)

### Requisitos parcial (ya cumplidos)
- Informe: fundamentacion teorica (concurrencia, fallos, transparencia), modelado (fisico, arquitectonico, UML), analisis de seguridad y fallos
- Codigo: sockets TCP con marshalling complejo, servidor multihilo con sincronizacion, 2 funciones principales resilientes
- Presentacion: video <=3min, demo en vivo, defensa tecnica

### Requisitos final (sobre el parcial)
- 2.1 Topologia multinodo: >=3 nodos, sin centralizar toda la coordinacion en uno solo, registro de membresia
- 2.2 Ordenamiento de eventos: relojes Lamport o vectoriales, al menos 1 funcion con orden causal correcto, log con marcas logicas
- 2.3 Coordinacion distribuida (al menos 1): exclusion mutua distribuida (anillo/Ricart-Agrawala/Maekawa) O eleccion de coordinador (Bully/anillo). Consenso opcional con puntaje extra
- 2.4 Tolerancia a fallos: deteccion por heartbeats/timeouts, recuperacion efectiva (re-eleccion, redistribucion) sin caida total
- 3.1 Generador de carga: >=50 hilos, >=60s sostenidos, ejercitar funciones principales y recurso con mutex
- 3.2 Metricas: throughput, latencia promedio y p95, mensajes de coordinacion, tasa de error/perdida
- 3.3 Falla inducida: derribar coordinador/nodo en plena carga, medir recuperacion
- 3.4 Evidencia: tabla + grafico de metricas, logs de la corrida

### Como cumple el sistema cada requisito
| Requisito | Implementacion |
|-----------|---------------|
| 2.1 Multinodo | 8 JVMs (6 servidores + 2 proxies), RegistroMembresia, registro dinamico en Proxy |
| 2.2 Lamport | RelojLamport en cada servicio/cliente, orden causal en mensajeria (VER_CONVERSACION) |
| 2.3 Bully | GestorBully en cluster Juegos (puertos 9082/9282), heartbeat cada 5s, re-eleccion automatica |
| 2.3 Mutex | GestorMutexCentralizado con lease, coordinador elegido por Bully, protege stock en compras |
| 2.4 Tolerancia | Heartbeats Bully, WatchdogServidor (con gracia 60s), failover escritor automatico, GestorPersistencia Main->Copy, ReplicadorEstado bidireccional |
| 3.1 Carga | GeneradorCarga: 50 hilos/60s, mezcla LISTAR/SALDO/MENSAJES/COMPRAR+CONFIRMAR, reintento de login |
| 3.2 Metricas | Throughput, p95, mensajes Bully+Mutex (VER_METRICAS_COORD), tasa perdida |
| 3.3 Falla | FallaInducida + run_coordinator_failure.ps1, mata coordinador a los N segundos |
| 3.4 Evidencia | reporte-carga.json, resumen.csv, throughput.svg, throughput-por-segundo.csv |
