# Sistema Steam Distribuido
### ICI-4344 Computación Paralela y Distribuida – PUCV

Sistema de gestión de activos digitales (tipo Steam) implementado en Java con
comunicación TCP, persistencia replicada y coordinación distribuida.

---

## Requisitos previos

| Herramienta | Versión mínima |
|-------------|----------------|
| Java        | 17 (probado con OpenJDK 25) |
| PowerShell  | 7+ |

No se requiere Maven; el script descarga Gson automáticamente.

---

## Compilar

```powershell
.\scripts\1_build.ps1
```

---

## Arrancar el sistema (orden obligatorio)

| # | Script                    | Puerto(s)              | Descripción                        |
|---|---------------------------|------------------------|------------------------------------|
| 2 | `2_run_sesiones1.bat`     | 8081                   | svSesiones Nodo 1                  |
| 3 | `3_run_sesiones2.bat`     | 8181                   | svSesiones Nodo 2 (espejo)         |
| 4 | `4_run_juegos1.bat`       | 8082 / 9082 / 9182     | svJuegos Nodo 1 + Bully + Mutex    |
| 5 | `5_run_juegos2.bat`       | 8282 / 9282 / 9382     | svJuegos Nodo 2 + Bully + Mutex    |
| 6 | `6_run_mensajeria1.bat`   | 8083                   | svMensajeria Nodo 1                |
| 7 | `7_run_mensajeria2.bat`   | 8383                   | svMensajeria Nodo 2 (espejo)       |
| 8 | `8_run_proxy.bat`         | 8080                   | Proxy (Balanceador)                |
| 9 | `9_run_cliente.bat`       | —                      | Cliente interactivo                |

> **Importante:** iniciar los servidores (2-7) **antes** del Proxy (8).

---

## Usuarios por defecto

| Usuario   | Contraseña | Rol           | Saldo |
|-----------|-----------|---------------|-------|
| admin     | admin123  | ADMINISTRADOR | $0    |
| vendedor1 | pass123   | VENDEDOR      | $0    |
| cliente1  | pass123   | COMPRADOR     | $500  |
| cliente2  | pass123   | COMPRADOR     | $200  |

---

## Resetear base de datos

```powershell
.\scripts\reset_datos.ps1   # escribe RESET cuando lo pida
```

---

## Puertos del sistema

### Servicios principales

| Componente      | Puerto |
|-----------------|--------|
| Proxy           | 8080   |
| svSesiones-1    | 8081   |
| svSesiones-2    | 8181   |
| svJuegos-1      | 8082   |
| svJuegos-2      | 8282   |
| svMensajeria-1  | 8083   |
| svMensajeria-2  | 8383   |

### Coordinación distribuida (Proyecto Final)

| Componente      | Puerto servicio | Puerto Bully | Puerto Mutex |
|-----------------|-----------------|--------------|--------------|
| svJuegos Nodo 1 | 8082            | 9082         | 9182         |
| svJuegos Nodo 2 | 8282            | 9282         | 9382         |

---

## Proyecto Final — Funcionalidades avanzadas

### 1. Relojes de Lamport

Cada componente mantiene un `RelojLamport` thread-safe con CAS-loop.
El campo `lamportClock` viaja en cada `MensajeProtocolo`.
Los logs muestran `[LAMPORT] t=N op=OPERACION` con N estrictamente creciente.
`VER_CONVERSACION` ordena mensajes por `lamportClock` (orden causal, no de sistema).

### 2. Algoritmo Bully

Al arrancar, los nodos de svJuegos ejecutan una elección automática:

```
[BULLY] t=1  Iniciando elección. id=1
[BULLY] t=2  ELECTION enviado a nodo-2
[BULLY] t=3  OK recibido de nodo-2
[BULLY] t=5  COORDINATOR recibido, nuevo coordinador=2
```

El no-coordinador envía `HEARTBEAT_COORD` cada 5 s. Si falla → re-elección:

```
[BULLY] Coordinador 2 caído, iniciando elección
[BULLY] t=N  SOY COORDINADOR (id=1)
```

### 3. Exclusión Mutua Centralizada

Nodo no-coordinador: `requestLock("stock") → GRANT → operación → releaseLock`.
Coordinador: entra directo a `synchronized(lock)` local.

```
[MUTEX] t=N REQUEST encolado para nodo-2 recurso=stock
[MUTEX] t=M GRANT recurso=stock solicitante=nodo-2
[MUTEX] t=P RELEASE recurso=stock por nodo-2
```

### 4. Prueba de Carga

```
# Terminal A
.\scripts\10_run_generador_carga.bat    # 50 hilos, 60s

# Terminal B (a los 30s)
.\scripts\11_run_falla_inducida.bat     # mata al coordinador
```

Reporte final en `logs/carga_<timestamp>.log`:

```
══════════════════════════════════════
REPORTE FINAL DE CARGA
══════════════════════════════════════
Duración          : 60s
Total peticiones  : 2450
Throughput        : 40.8 req/s
Latencia promedio : 87.3 ms
Latencia p95      : 210 ms
Peticiones error  : 38 (1.6%)
══════════════════════════════════════
```

---

## Logs

```powershell
# Seguir log en tiempo real
Get-Content logs\svJuegos-1_0.log -Tail 50 -Wait

# Menú interactivo
.\scripts\ver_logs.bat
```

Archivos: `logs/<componente>_0.log` (rotación 5 MB × 5 archivos, append).

---

## Arquitectura

```
ClienteJava ──TCP──► Proxy:8080 ──Round-Robin──► svSesiones (8081/8181)
                                              ──► svJuegos   (8082/8282)
                                              ──► svMensajeria(8083/8383)

svJuegos-1 ◄──Bully(9082/9282)──► svJuegos-2
svJuegos-1 ◄──Mutex(9182/9382)──► svJuegos-2

svJuegos/Mensajeria ──ValidarToken──► svSesiones (directo, sin Proxy)

Persistencia: data/*.txt  Main + Copy (escritura ATOMIC_MOVE)
Membresía:    data/MEMBRESIA.txt (actualizada por Proxy en health-check)
```
