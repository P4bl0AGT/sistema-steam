# Sistema Steam Distribuido

Sistema de gestión de activos digitales distribuido, desarrollado en Java. Implementa alta disponibilidad mediante replicación, balanceo de carga Round-Robin y concurrencia controlada con exclusión mutua.

---

## Requisitos previos

| Herramienta | Versión mínima | Verificar con |
|-------------|---------------|---------------|
| Java (JDK)  | 17 o superior | `java -version` |
| PowerShell  | 5.1 o superior | `$PSVersionTable.PSVersion` |
| Conexión a internet | Solo la primera vez (descarga Gson) | — |

> **Java 17+ es obligatorio.** El proyecto fue probado con OpenJDK 25. Si tenés Java 8 o 11, actualizá primero.

---

## Estructura del proyecto

```
sistema-steam/
│
├── src/main/java/com/steam/
│   ├── common/          # Protocolo, persistencia, logging, validación de tokens
│   ├── models/          # Modelos de datos (Usuario, Juego, Reserva, Mensaje…)
│   ├── proxy/           # Proxy.java — balanceador Round-Robin + health check
│   ├── servidores/      # svSesiones, svJuegos, svMensajeria, GestorLocks
│   └── cliente/         # ClienteJava.java — interfaz de consola
│
├── data/                # Bases de datos en JSON (.txt) — Main + Copy por servicio
├── logs/                # Logs de cada componente (se crean al ejecutar)
├── lib/                 # gson-2.10.1.jar (se descarga automáticamente)
├── scripts/             # Scripts de compilación y ejecución
└── sistema-steam.jar    # JAR compilado (se genera con el build)
```

---

## Paso 1 — Compilar el proyecto

Abrí **PowerShell** en la carpeta raíz del proyecto y ejecutá:

```powershell
.\scripts\1_build.ps1
```

Este script:
1. Descarga `gson-2.10.1.jar` a `lib/` (solo la primera vez, requiere internet).
2. Compila los 21 archivos `.java`.
3. Genera `sistema-steam.jar`.

Salida esperada:
```
[OK] Gson ya existe en lib/

Compilando 21 archivos Java...
[OK] Compilacion exitosa.

Generando sistema-steam.jar...
[OK] sistema-steam.jar creado.
```

> Si ves errores de compilación, verificá que `java` y `javac` estén en el PATH (`java -version`).

---

## Paso 2 — Iniciar los servidores

Cada servidor debe abrirse en **su propia ventana** haciendo doble clic en el `.bat` correspondiente (o ejecutándolo desde la consola). El **orden importa**: los servidores deben estar corriendo **antes** de iniciar el Proxy.

### Orden de inicio

```
scripts/
  2_run_sesiones1.bat    ← Nodo 1 de Sesiones   (puerto 8081)
  3_run_sesiones2.bat    ← Nodo 2 de Sesiones   (puerto 8181)
  4_run_juegos1.bat      ← Nodo 1 de Juegos     (puerto 8082)
  5_run_juegos2.bat      ← Nodo 2 de Juegos     (puerto 8282)
  6_run_mensajeria1.bat  ← Nodo 1 de Mensajería (puerto 8083)
  7_run_mensajeria2.bat  ← Nodo 2 de Mensajería (puerto 8383)
  8_run_proxy.bat        ← Proxy / Balanceador   (puerto 8080)  ← iniciar ÚLTIMO
```

> **Mínimo funcional:** Para probar con un solo nodo por servicio podés iniciar solo los scripts `2`, `4`, `6` y `8`. Los scripts `3`, `5` y `7` son los nodos espejo para alta disponibilidad.

Cuando un servidor arranca correctamente, verás en su ventana:

```
=== svSesiones iniciado en puerto 8081 ===
```

```
=== svJuegos iniciado en puerto 8082 ===
[JUEGOS] GestorLocks daemon iniciado
```

```
=== Proxy iniciado en puerto 8080 ===
[HEALTH] Health Check iniciado (intervalo 10s)
```

---

## Paso 3 — Iniciar el cliente

Con todos los servidores corriendo, abrí otra ventana y ejecutá:

```
scripts/9_run_cliente.bat
```

Verás la pantalla de inicio:

```
╔══════════════════════════════════════╗
║       SISTEMA STEAM DISTRIBUIDO      ║
╚══════════════════════════════════════╝
Proxy: localhost:8080

──── ACCESO ────
1. Iniciar sesión
2. Ver catálogo (público)
0. Salir
Opción:
```

---

## Usuarios por defecto

Al iniciar por primera vez, los servidores crean automáticamente estos usuarios:

| Usuario | Contraseña | Rol | Saldo inicial |
|---------|-----------|-----|--------------|
| `admin` | `admin123` | ADMINISTRADOR | $0 |
| `vendedor1` | `pass123` | VENDEDOR | $0 |
| `cliente1` | `pass123` | COMPRADOR | $500 |
| `cliente2` | `pass123` | COMPRADOR | $200 |

### Catálogo inicial

| Juego | Precio | Stock |
|-------|--------|-------|
| Counter-Strike 2 | $29.99 | 50 |
| Cyberpunk 2077 | $59.99 | 20 |
| Stardew Valley | $14.99 | 100 |

---

## Guía de uso por rol

### COMPRADOR (`cliente1` / `cliente2`)

```
──── [COMPRADOR: cliente1] ────
1. Ver catálogo y comprar
2. Mis reservas pendientes
3. Cancelar una reserva
4. Ver saldo de billetera
5. Mis compras
6. Enviar mensaje
7. Ver mensajes recibidos
8. Ver conversación
9. Cambiar contraseña
0. Cerrar sesión
```

**Flujo de compra:**
1. Elegí `1` → Ver catálogo y comprar.
2. El sistema muestra la lista numerada de juegos disponibles.
3. Ingresá el número del juego que querés.
4. El sistema crea una reserva con **5 minutos de vigencia**.
5. El sistema pregunta `¿Pagar ahora? (s/n)`.
   - `s` → confirma el pago de inmediato.
   - `n` → la reserva queda pendiente; podés confirmarla desde la opción `2`.

```
╔══════════════════════════════════════════════════╗
║ #   Nombre                 Precio   Stock        ║
╠══════════════════════════════════════════════════╣
║ 1   Counter-Strike 2       $29.99   50           ║
║ 2   Cyberpunk 2077         $59.99   20           ║
║ 3   Stardew Valley         $14.99   100          ║
╚══════════════════════════════════════════════════╝

Elige un número (0 para cancelar): 1
[✓] Reserva creada para 'Counter-Strike 2'
    Precio : $29.99
    Tienes : 299s para confirmar el pago
¿Pagar ahora? (s/n): s
[✓] Compra Exitosa
    Juego adquirido : Counter-Strike 2
    Pagado          : $29.99
    Saldo restante  : $470.01
```

> Si no confirmás en 5 minutos, la reserva expira y el stock se libera automáticamente.

---

### VENDEDOR (`vendedor1`)

```
1. Ver catálogo
2. Publicar juego
3. Mis juegos publicados
4. Modificar juego
5. Eliminar juego
6. Ver saldo (ingresos)
7. Ver historial de ventas
...
```

**Publicar un juego:**
1. Elegí `2` → Publicar juego.
2. Ingresá nombre, descripción, precio y stock.
3. El juego queda disponible en el catálogo de inmediato.

Cada vez que alguien compra un juego tuyo, el precio se acredita en tu billetera.

---

### ADMINISTRADOR (`admin`)

```
1. Ver catálogo
2. Registrar usuario
3. Listar usuarios
4. Agregar saldo a usuario
5. Ver estadísticas del sistema
6. Ver historial de ventas
7. Eliminar juego
...
```

**Registrar un nuevo usuario:**
1. Elegí `2` → Registrar usuario.
2. Ingresá username, contraseña y rol (`COMPRADOR`, `VENDEDOR` o `ADMINISTRADOR`).

**Agregar saldo:**
1. Elegí `4` → Agregar saldo a usuario.
2. Ingresá el username destino y el monto.

**Ver estadísticas:**
Muestra total de juegos, ventas, ingresos acumulados, reservas activas y saldo de todas las billeteras.

---