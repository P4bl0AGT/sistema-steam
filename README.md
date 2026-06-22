# Sistema Steam Distribuido

Proyecto de ICI-4344 Computación Paralela y Distribuida. Implementa una plataforma tipo Steam en Java 17 con múltiples JVM, proxies redundantes, topología base con membresía renovable, replicación por TCP, relojes de Lamport, elección Bully y exclusión mutua distribuida.

## Inicio rápido

Todos los comandos se ejecutan desde la raíz del repositorio:

```powershell
cd C:\ruta\al\repositorio\sistema-steam
```

La forma más rápida de comprobar que todo funciona es ejecutar:

```text
scripts\test_all.bat
```

El script:

1. Compila el proyecto para Java 17.
2. Ejecuta las pruebas de componentes.
3. Elimina datos de ejecuciones anteriores.
4. Inicia 8 JVM y 18 puertos.
5. Ejecuta las pruebas de integración y concurrencia.
6. Derriba el writer de juegos, promueve la replica y comprueba lecturas y escrituras.
7. Detiene todos los procesos al terminar.

Una ejecución correcta termina aproximadamente así:

```text
OK pruebas_componentes=69
[OK] Build Java 17: 51 fuentes, 6 pruebas.
OK writers_reconciliados=3
[OK] Sistema iniciado: 8 JVM, 2 proxies, 18 puertos.
OK pruebas_integracion=20 ultimo_stock_exitos=1
OK writer_failover=4
OK idempotencia_reinicio_verificada=2
[OK] Suite completa aprobada.
[OK] Procesos registrados detenidos.
```

> `test_all.bat` apaga el sistema al finalizar. Para usar el cliente después hay que ejecutar `start_all.bat`.

## Requisitos

- Windows 10 u 11.
- JDK 17 o superior con `java`, `javac`, `jar` y `keytool`.
- Windows PowerShell 5.1 o PowerShell 7.
- Puertos del proyecto disponibles.

No se necesita Maven. Gson está incluido en `lib/` y `scripts/1_build.ps1` compila directamente con `javac --release 17`.
El `pom.xml` es una alternativa para IDE/CI; `mvn test` también ejecuta
`PruebasComponentes`, pero la ruta oficial de demostración sigue siendo la de scripts.

Comprueba Java con:

```powershell
java -version
javac -version
```

## Compilar

Ejecuta:

```text
scripts\1_build.bat
```

Esto genera `target/classes`, `target/test-classes` y `sistema-steam.jar`. Estos archivos son regenerables y no se almacenan en Git.

## Prueba manual

### 1. Iniciar el sistema

```text
scripts\start_all.bat
```

Resultado esperado:

```text
[OK] Sistema iniciado: 8 JVM, 2 proxies, 18 puertos.
```

Los procesos se ejecutan ocultos y sus PIDs quedan en `target/run/pids.json`.

### 2. Abrir clientes

```text
scripts\9_run_cliente.bat
```

El mismo archivo puede ejecutarse varias veces. Cada ventana corresponde a un cliente independiente. Por ejemplo:

- Ventana 1: `cliente1 / pass123`
- Ventana 2: `cliente2 / pass123`
- Ventana 3: `vendedor1 / pass123`

### 3. Detener el sistema

```text
scripts\stop_all.bat
```

No cierres procesos Java al azar desde el Administrador de tareas. `stop_all.bat` termina los procesos registrados por `start_all.bat`, también los recreados por el watchdog, y deja una marca que impide reinicios durante el apagado.

## Usuarios de demostración

| Usuario | Contraseña | Rol |
|---|---|---|
| `admin` | `admin123` | Administrador |
| `vendedor1` | `pass123` | Vendedor |
| `cliente1` a `cliente50` | `pass123` | Comprador |

## Funciones que se deben probar

### Comprador

1. Iniciar sesión como `cliente1`.
2. Consultar el catálogo.
3. Reservar un juego.
4. Confirmar el pago.
5. Consultar saldo, reservas e historial.
6. Enviar un mensaje a `cliente2`.
7. Abrir otro cliente como `cliente2` y recibir el mensaje.

La lectura no marca un mensaje como entregado hasta que el cliente lo muestra y envía un ACK explícito. Si ese ACK falla, el mensaje vuelve a aparecer en la siguiente consulta.

Los mensajes cuyo contenido comienza con `carga-` provienen del generador de carga. Para comenzar sin ellos, detén y reinicia los datos:

```powershell
.\scripts\stop_all.ps1
.\scripts\reset_datos.ps1 -Force
.\scripts\start_all.ps1
```

### Vendedor

1. Iniciar sesión como `vendedor1`.
2. Publicar un juego con precio y stock.
3. Modificarlo.
4. Consultar sus juegos publicados.
5. Revisar las ventas asociadas.

### Administrador

1. Iniciar sesión como `admin`.
2. Registrar usuarios y listar cuentas.
3. Agregar saldo a un comprador.
4. Consultar estadísticas e historial global.

## Pruebas automáticas

### Pruebas de componentes e integración

```text
scripts\test_all.bat
```

La suite comprueba, entre otros puntos:

- Incrementos concurrentes del reloj de Lamport.
- Deduplicación concurrente por `requestId`.
- Rechazo de un `requestId` reutilizado con otro payload.
- Firmas HMAC y detección de alteraciones.
- Integridad del payload completo y SHA-256 para snapshots replicados.
- Rechazo de escrituras directas en nodos secundarios.
- Idempotencia durable tras reconstruir la caché en una JVM nueva.
- Persistencia de la versión dentro del mismo snapshot de estado.
- Conservación de métricas acumuladas tras reiniciar una JVM.
- Expiración de sesiones.
- Entrega de mensajes con ACK explícito.
- Orden determinista `(Lamport, nodo, id)`.
- Login y operaciones a través de proxies redundantes.
- Replicación byte a byte entre almacenamientos independientes.
- Dos compradores compitiendo por el último ejemplar: sólo uno puede reservarlo.
- Cancelación de reserva y restauración de stock bajo el mutex distribuido.

### Caída de un proxy

Con el sistema iniciado mediante `start_all.bat`:

```powershell
.\scripts\run_proxy_failover.ps1
```

La prueba apaga `Proxy-1`, envía solicitudes por el endpoint lógico, comprueba continuidad mediante `Proxy-2` y vuelve a insertar `Proxy-1`.

Resultado esperado:

```text
[OK] Failover de proxy: 30 solicitudes sin perdida; Proxy-1 reingreso.
```

### Caída del coordinador Bully

Con el sistema iniciado:

```text
scripts\11_run_falla_inducida.bat
```

También puede ejecutarse directamente:

```powershell
.\scripts\run_coordinator_failure.ps1 -DelaySec 0
```

La prueba identifica el coordinador, envía un apagado autenticado, mide la reelección del sobreviviente y reinicia el nodo caído. Las corridas validadas recuperan coordinador en aproximadamente 3,5 segundos.

## Prueba de carga

Primero inicia el sistema:

```text
scripts\start_all.bat
```

Después ejecuta:

```text
scripts\10_run_generador_carga.bat
```

Equivale a:

```powershell
.\scripts\10_run_generador_carga.ps1 -Hilos 50 -DuracionSeg 60
```

El generador realiza login con 50 compradores y mezcla operaciones de catálogo, saldo, mensajes, recepción y compras completas (COMPRAR_JUEGO + CONFIRMAR_PAGO) a través de ambos proxies. Los logins fallidos se reintentan cada 500ms y se contabilizan como indisponibilidad (`loginsFallidos`). Separa éxitos, errores de negocio, errores de sistema, indisponibilidad y pérdidas de transporte mediante `codigoError`. También muestrea periódicamente Bully y mutex (con firma HMAC) para conservar el último contador conocido si un nodo cae o reinicia.

Produce:

- `reporte-carga.json` (metricas completas incluyendo loginsFallidos y desglose por codigoError)
- `resumen.csv`
- `metricas-por-fase.csv` (normal, falla y recuperacion)
- `throughput-por-segundo.csv`
- `throughput.svg`

Los archivos quedan bajo `evidencia/carga/<fecha>/`.

## Consistencia de escrituras

Cada servicio tiene un escritor preferido configurado (`steam.<servicio>.writer.node`, nodo 1 por defecto). Si deja de responder durante `steam.writer.failover.ms` (default 8s), el secundario se promueve (con incremento de epoca) y el Proxy redirige allí las escrituras. Por defecto, las lecturas prefieren el writer para conservar read-your-writes y pueden degradar a la replica.

### Fencing y reconciliacion

Al arrancar o recuperarse, el writer permanece sin aceptar escrituras (`listoParaEscrituras=false`) hasta reconciliar su versión con el peer. Los ACK de réplica deben coincidir exactamente en servicio, peer, `requestId` y versión. Cuando el preferido reconecta y detecta que el peer tiene versión mayor (FENCE), aplica el estado remoto inmediatamente y se habilita para escrituras (RECONCILED). El secundario se despromueve (DEMOTE) al detectar que el preferido esta de vuelta.

El Proxy consulta el campo `writerReady` del health check y no envía escrituras a nodos que estan reconciliando. Esto evita lecturas obsoletas durante la transición.

### Idempotencia cross-nodo

Los tres servicios (sesiones, juegos, mensajería) persisten un marcador idempotente local (`IDEMPOTENCIA.json`) y conservan la respuesta durante 30 días. Los requestIds procesados se replican entre nodos via el campo `requestIdsRecientes` del PUSH, protegido por HMAC. Si una JVM se reinicia con una operación en curso, el reintento devuelve `REQUEST_OUTCOME_UNKNOWN`. Si el requestId ya fue procesado en el otro nodo, retorna `REQUEST_ALREADY_PROCESSED`.

### Replicacion sincrona (opcional)

Con `steam.replication.sync.required=true`, las escrituras fallan si la réplica no confirma (solo cuando el peer esta alcanzable). Si el peer esta caido, las escrituras se permiten sin confirmación para no bloquear el failover.

### Limitaciones conocidas

La promoción mantiene el servicio disponible ante un crash observable y la replicación converge mediante snapshots completos versionados. No es consenso: con solo dos réplicas una partición simétrica no permite garantizar simultáneamente disponibilidad y ausencia absoluta de split-brain. Bully recupera al coordinador ante un crash observable; la limitación de particiones de una topología de dos nodos debe explicarse en el informe.

La evidencia compacta validada está en `evidencia/final-50x60-validada/`. La corrida registrada obtuvo:

| Métrica | Resultado |
|---|---:|
| Clientes concurrentes | 50 |
| Duración | 60 s |
| Intentos | 13.423 |
| Éxitos | 13.417 |
| Respuestas no-OK (campo histórico `erroresNegocio`) | 6 |
| Pérdidas de transporte | 0 |
| Throughput exitoso | 223,62 solicitudes/s |
| Latencia promedio | 225,72 ms |
| Latencia p95 | 1.312,12 ms |

Esa evidencia fue generada antes de incorporar `codigoError` a la clasificación de carga; por ello no permite afirmar que las seis respuestas no-OK fueran realmente rechazos de negocio. Las corridas nuevas exportan categorías separadas y un mapa `codigosError`.

### Escenario completo de evaluación

Para compilar, reiniciar datos, ejecutar carga 50×60, derribar el coordinador durante la carga, probar el proxy y recolectar evidencia:

```powershell
.\scripts\13_run_prueba_trafico.ps1 `
  -Hilos 50 `
  -DuracionSeg 60 `
  -FallaEnSeg 20
```

## Seguridad

| Mecanismo | Detalle |
|-----------|---------|
| Passwords | PBKDF2-HMAC-SHA256 con sal aleatoria (60k iteraciones) |
| Tokens | UUID aleatorio, TTL configurable (default 30 min) |
| Operaciones de control | Firmadas con HMAC-SHA256 (`steam.control.secret`) |
| Endpoints protegidos | SHUTDOWN, REGISTRAR_NODO, QUIEN_ES_COORDINADOR, VER_METRICAS_COORD, ESTADO_REPLICACION |
| Mensajes Bully/Mutex/Replicacion | Firmados con HMAC + validacion de frescura |
| Idempotencia | requestId + huella; replicada entre nodos via PUSH firmado |
| Mensajes de chat | Limitados a 4096 caracteres |
| Payloads TCP | Limitados por bytes UTF-8 estimados (`steam.max.message.bytes`) |
| Parsing numerico | getDouble/getInt capturan NumberFormatException sin cerrar conexion |
| Codigos de error | Auto-inferidos por `inferirCodigo()` para llamadas legacy |

## TLS y mTLS

El perfil normal usa TCP limitado a `127.0.0.1` para facilitar la demostración. Existe un perfil TLS con autenticación mutua y verificación del hostname. Para distribuir procesos entre máquinas hay que cambiar explícitamente `steam.bind.host`, `steam.advertised.host` y los hosts de cada servicio.

Genera certificados locales:

```powershell
.\scripts\generar_certificados_tls.ps1
```

Inicia la topología TLS:

```powershell
.\scripts\start_all.ps1 -Config config/steam-tls.properties
```

Abre un cliente TLS:

```powershell
java '-Dsteam.config=config/steam-tls.properties' `
  -cp "target\classes;lib\gson-2.10.1.jar" `
  com.steam.cliente.ClienteJava
```

Los `.p12` contienen claves privadas de demostración, están ignorados por Git y deben regenerarse localmente. No uses la contraseña demo ni el secreto HMAC incluido fuera de un entorno académico.

El modo demo permite el secreto publicado sólo cuando `steam.demo.mode=true`. Para cualquier despliegue no demostrativo, define un secreto externo y desactiva el modo demo antes de iniciar todos los procesos:

```powershell
$env:STEAM_CONTROL_SECRET = '<secreto-aleatorio-largo>'
$env:STEAM_DEMO_MODE = 'false'
.\scripts\start_all.ps1 -Config config/steam-tls.properties
```

Con modo demo desactivado, el sistema no arranca sin `STEAM_CONTROL_SECRET` (o la propiedad JVM equivalente). La firma de control cubre el sobre y el payload canónico completos; Bully, mutex y replicación conservan firmas específicas.
El modo demo rechaza además cualquier `steam.bind.host` que no sea loopback.
Las sesiones tienen vida absoluta configurada por `steam.token.ttl.ms`; validarlas no renueva el vencimiento. Los tokens nuevos se persisten como SHA-256 y el login bloquea temporalmente un usuario después de cinco fallos en un minuto.

## Tolerancia a fallos

| Mecanismo | Implementacion |
|-----------|---------------|
| Heartbeat Bully | Cada 5s; timeout 3s dispara re-eleccion iterativa (max 5 intentos) |
| Failover de escritor | Secundario se promueve tras 8s sin contacto; fencing por epoca |
| Reconciliacion | Primario aplica estado remoto inmediatamente al reconectar (FENCE → RECONCILED) |
| Watchdog | Health check cada 15s; reinicia tras 3 fallos consecutivos; 60s de gracia post-reinicio |
| Persistencia | Escritura atomica (temp + rename); failover Main → Copy automatico |
| Snapshot | readAllBytes atomico cada 30s (escalonado entre nodos) |
| Proxy | Health check cada 10s; failover de escrituras a nodos activos; respeta writerReady |
| ValidadorToken | Intenta ambos nodos de sesiones antes de rechazar un token |
| Pruning automatico | Sesiones expiradas limpiadas en LOGIN; mensajes entregados >7 dias en VER_MENSAJES |

## Arranque manual por ventanas

La opción recomendada es `start_all.bat`. Para observar cada proceso en su propia consola, compila y abre los archivos en este orden:

1. `2_run_sesiones1.bat`
2. `3_run_sesiones2.bat`
3. `4_run_juegos1.bat`
4. `5_run_juegos2.bat`
5. `6_run_mensajeria1.bat`
6. `7_run_mensajeria2.bat`
7. `8_run_proxy.bat`
8. `8b_run_proxy2.bat`
9. `9_run_cliente.bat`

No abras `start_all.bat` y los scripts individuales al mismo tiempo: intentarían ocupar los mismos puertos.

## Puertos

| Componente | Servicio | Bully | Mutex | Réplica |
|---|---:|---:|---:|---:|
| Proxy 1 | 8080 | - | - | - |
| Proxy 2 | 8085 | - | - | - |
| Sesiones 1 | 8081 | - | - | 9483 |
| Sesiones 2 | 8181 | - | - | 9583 |
| Juegos 1 | 8082 | 9082 | 9182 | 9482 |
| Juegos 2 | 8282 | 9282 | 9382 | 9582 |
| Mensajería 1 | 8083 | - | - | 9484 |
| Mensajería 2 | 8383 | - | - | 9584 |

## Archivos generados

Estas rutas se regeneran y están excluidas de Git:

- `target/`: clases compiladas, pruebas y PIDs.
- `out/`: salida histórica de compilación.
- `data/`: estado independiente de cada nodo.
- `logs/`: logs estructurados por proceso.
- `certs/`: certificados y claves TLS locales.
- `sistema-steam.jar`: JAR creado por el build.

Para reiniciar únicamente los datos:

```powershell
.\scripts\stop_all.ps1
.\scripts\reset_datos.ps1 -Force
```

## Solución de problemas

### Falta `sistema-steam.jar` o `target/classes`

Ejecuta `scripts\1_build.bat`. Es normal que estos artefactos no existan después de clonar.

### Puerto ocupado

Primero ejecuta:

```text
scripts\stop_all.bat
```

Si el proceso fue iniciado manualmente, cierra la ventana correspondiente. No ejecutes simultáneamente el arranque completo y el arranque por nodos.

### El cliente no conecta

Confirma que `start_all.bat` terminó con el mensaje de 8 JVM y 18 puertos. La suite `test_all.bat` apaga los procesos cuando termina.

### PowerShell bloquea scripts

Usa:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test_all.ps1
```

### Quiero una ejecución limpia

```powershell
.\scripts\stop_all.ps1
.\scripts\reset_datos.ps1 -Force
.\scripts\start_all.ps1
```

## Estructura principal

```text
config/       configuración TCP y TLS
docs/         auditorías y documentación técnica
evidencia/    evidencia compacta validada
lib/          Gson para build sin Maven
scripts/      build, arranque, pruebas, fallos y carga
src/main/     implementación
src/test/     pruebas de componentes e integración
```
