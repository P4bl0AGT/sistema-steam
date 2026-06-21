# Auditoria inicial contra la pauta final ICI-4344

> **Documento historico de linea base.** Sus estados no describen la version
> actual; se conserva para mostrar la evolucion del proyecto.

Fecha de linea base: 20-06-2026. Commit inspeccionado:
`f5ee219cea4dcc3e6179b68a5f5ec4cff820e0e7`.

Fuentes leidas antes de modificar codigo: las 35 clases de `src/main/java`, todos
los scripts, `data`, logs, `README.md`, `pom.xml`, los tres `.tex`, el PDF,
`pauta_proyecto.md` y `revision.md`. El proyecto Netflix indicado por el usuario se
reviso solo como referencia de separacion, gateway, configuracion, timeout, TLS y
Lamport; no se copio codigo y se descarto su transporte `ObjectStream` por ser
incompatible con la verdad JSON declarada para Steam.

Estados permitidos: **CUMPLE**, **CUMPLE PARCIALMENTE**, **NO CUMPLE** y
**NO VERIFICABLE**.

| Requisito de la pauta | Estado actual | Evidencia real | Archivo y metodo | Problema | Accion necesaria |
|---|---|---|---|---|---|
| Dos funciones principales | CUMPLE | marketplace y mensajeria ejecutables | `svJuegos.procesar`, `svMensajeria.procesar` | documentos mezclan biblioteca/chat con otras etiquetas antiguas | fijar oficialmente las dos funciones y alinear documentos |
| Marketplace completo | CUMPLE PARCIALMENTE | login, catalogo, reserva, pago, saldo, historial | `svSesiones.login`, `svJuegos.comprarJuego`, `confirmarPago` | consistencia y mutex interproceso defectuosos | primario/replica, mutex correcto e idempotencia |
| Mensajeria y conversaciones | CUMPLE PARCIALMENTE | envio, polling e historial | `svMensajeria.enviarMensaje`, `verConversacion` | no valida receptor; orden sin desempate determinista | validar usuario y ordenar Lamport+nodo+UUID |
| Tres o mas procesos/JVM | CUMPLE | Proxy y seis nodos de servicio | metodos `main` de `Proxy` y `sv*` | todos dependen de localhost/archivos comunes | externalizar topologia y separar almacenamiento |
| Topologia multinodo | CUMPLE PARCIALMENTE | puertos independientes y sockets TCP | `Constantes`, `sv*.escuchar` | una sola maquina y datos compartidos | hosts configurables y datos por nodo |
| Transparencia de acceso | CUMPLE | cliente usa `MensajeProtocolo` JSON para toda operacion | `ClienteJava.enviar` | documentacion no lo vincula con codigo | documentar flujo y mantener protocolo unico |
| Transparencia de ubicacion | CUMPLE PARCIALMENTE | cliente solo conoce Proxy 8080 | `Proxy.rutear`, `Utils.clusterParaOperacion` | Proxy unico; cliente hardcodeado | proxies configurables con failover cliente |
| Proxy integrado | CUMPLE | todas las operaciones del cliente pasan por 8080 | `ClienteJava.enviar` | punto unico de falla | dos proxies y cliente multiproxy |
| Membresia | CUMPLE PARCIALMENTE | registro, baja, health y archivo | `RegistradorProxy`, `RegistroMembresia` | solo un Proxy, campos incompletos, estado puede quedar obsoleto | membresia por Proxy, renovacion y validacion |
| Marshalling estructurado | CUMPLE | Gson serializa POJO+payload | `MensajeProtocolo.toJson/fromJson` | sin version ni limites | validacion y limite de trama |
| Coherencia TCP/JSON | CUMPLE | `BufferedReader`, `PrintWriter`, una linea JSON | todos los metodos `manejarCliente/enviar` | comentarios antiguos prometen otras cosas | eliminar referencias obsoletas y documentar una verdad |
| Lamport por proceso | CUMPLE PARCIALMENTE | `AtomicLong`, `tick`, CAS `update` | `RelojLamport` | Proxy sobreescribe marca sin update de recepcion | corregir todos los limites de comunicacion |
| Orden causal | CUMPLE PARCIALMENTE | conversacion ordena por escalar | `svMensajeria.verConversacion` | sin nodeId/UUID; mezcla timestamp y Lamport | comparador determinista y prueba concurrente |
| Logs logicos | CUMPLE PARCIALMENTE | lineas `[LAMPORT]`, `[BULLY]`, `[MUTEX]` | `GestorLog`, servicios | no incluyen siempre emisor/receptor/resultado/requestId | logger estructurado comun |
| Bully | CUMPLE PARCIALMENTE | ELECTION, OK, COORDINATOR, heartbeat y metricas | `GestorBully` | races, stop incompleto, split brain con particion | cooldown, epochs/validacion, cierre y pruebas de ciclo |
| Exclusion mutua | NO CUMPLE | REQUEST/GRANT/RELEASE existe | `GestorMutexCentralizado` | coordinador no toma su Semaphore; RELEASE sin ownership/lease | redisenar propietario, requestId, lease e idempotencia |
| Concurrencia multihilo | CUMPLE PARCIALMENTE | pools, atomicos, concurrent collections y monitores | Proxy, servicios y coordinacion | monitores no cruzan JVM, colas no acotadas | primario de escritura, locks correctos y cierre |
| Crash | CUMPLE PARCIALMENTE | connect error, health, heartbeat, watchdog | `Proxy.iniciarHealthCheck`, `WatchdogServidor` | no cubre Proxy; recuperacion parcial | Proxy redundante y pruebas de failover |
| Omision | CUMPLE PARCIALMENTE | SO_TIMEOUT y retry del Proxy | `Proxy.reenviar` | timeout ambiguo y request no idempotente | retries limitados + idempotencia persistente |
| Heartbeats | CUMPLE | Proxy health y Bully heartbeat | `Proxy.iniciarHealthCheck`, `GestorBully.iniciarHeartbeatDaemon` | secuenciales/solo juegos | medir deteccion y documentar limites |
| Timeouts | CUMPLE PARCIALMENTE | SO_TIMEOUT 5 s, Bully/mutex | `Constantes`, sockets | `new Socket` sin connect timeout | `Socket.connect(address, timeout)` comun |
| Recuperacion | CUMPLE PARCIALMENTE | failover, reeleccion y watchdog | Proxy, Bully, Watchdog | sin Proxy redundante ni estado replicado real | dos proxies y replica por TCP |
| Replicacion/consistencia | NO CUMPLE | `Main -> Copy` cada 30 s | `GestorSnapshot` | backup local compartido; lost updates entre JVM | archivos por nodo y replicacion versionada por TCP |
| 50 clientes | CUMPLE | default 50 workers | `GeneradorCarga.main` | no hay test automatizado que valide parametro | script oficial y evidencia nueva |
| 60 segundos | CUMPLE | default 60 s | `GeneradorCarga.main` | divide por duracion configurada, no real | medir tiempo real monotonicamente |
| Throughput | CUMPLE PARCIALMENTE | total/duracion y parciales | `imprimirReporteFinal` | clasificacion de errores distorsiona | metricas tipadas y CSV/JSON |
| Latencia promedio | CUMPLE | suma/servidas | `GeneradorCarga.enviar` | excluye perdidas sin explicitar en salida estructurada | documentar universo y exportar |
| p95 | CUMPLE PARCIALMENTE | lista ordenada | `imprimirReporteFinal` | indice no nearest-rank | `ceil(0.95*n)-1` |
| Mensajes coordinacion | CUMPLE PARCIALMENTE | contadores Bully/mutex | `verMetricasCoord`, `recolectarCoord` | nodo caido se omite; metrica incompleta | snapshot de metricas y distincion por nodo |
| Tasa de perdida | NO CUMPLE | cuenta IO/EOF | `GeneradorCarga.enviar` | indisponibilidad respondida se cuenta como negocio | codigos tipados: timeout/connect/corrupta/servicio |
| Falla inducida | CUMPLE PARCIALMENTE | shutdown coordinador al segundo 30 | `FallaInducida` | mide solo rol, no servicio; sin falla Proxy | tiempos separados y dos modalidades |
| Tiempo recuperacion | CUMPLE PARCIALMENTE | sondeo `soyCoordinador` | `FallaInducida.medirRecuperacion` | no mide deteccion/eleccion/end-to-end | instrumentar tres tiempos y smoke final |
| Seguridad replay | NO CUMPLE | requestId/timestamp solo son campos | `MensajeProtocolo` | no hay antiguedad, cache ni idempotencia | validacion TTL y cache de requestId/respuestas |
| Seguridad MITM | NO CUMPLE | TCP plano | todas las fabricas de sockets | password/token/control observables | TLS configurable y certificados demo |
| Autorizacion y tokens | CUMPLE PARCIALMENTE | roles en varias operaciones y UUID | servicios | compra no exige comprador; token no expira; cache tras logout | roles servidor, expiracion y revocacion coherente |
| Payload/JSON malformado | NO CUMPLE | Gson directo | handlers `fromJson` | runtime no capturada, linea ilimitada | limite, parse seguro y error controlado |
| Consistencia codigo-documentos | NO CUMPLE | README dice replica inmediata y datos antiguos | README/Javadocs/scripts | puertos Repl sin listener, saldos/usuarios obsoletos | reescribir docs/scripts tras codigo final |
| Tabla y grafico de carga | NO CUMPLE | solo logs de texto historicos | `logs/carga_*.log` | sin CSV/JSON/grafico reproducible | exportador y script SVG/PNG |
| Pruebas automatizadas | NO CUMPLE | no existe `src/test` | arbol del repositorio | algoritmos no tienen regresion | pruebas Lamport, orden, Bully, mutex e integracion |
| Presentacion/video/formato oficial | NO VERIFICABLE | no hay presentacion/video/formato completo en el repo | `.tex` actual | depende de entregables humanos y plantilla oficial | dejar contenido tecnico coherente; el grupo completa portada/video |

## Diagnostico de la revision parcial

Las observaciones del profesor siguen siendo criterios de aceptacion explicitos:

1. Formalizar ausencia de reloj global con clases y eventos reales.
2. Vincular transparencias con `ClienteJava`, `Proxy` y membresia.
3. Mantener una sola verdad de transporte: TCP + JSON + Gson, nunca ObjectStream.
4. Hacer que el gateway sea obligatorio y tolerante a fallos.
5. Profundizar el modelo de amenazas, especialmente replay y MITM.
6. Evitar partes “declaradas” que el equipo no pueda demostrar en codigo/demo.

Esta matriz es deliberadamente previa a cualquier correccion. Los estados finales se
volveran a evaluar solo con compilacion, pruebas y logs nuevos.
