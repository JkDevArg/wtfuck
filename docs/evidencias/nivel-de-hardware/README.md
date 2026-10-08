# Nivel de hardware · el defecto que fallaba abierto, y lo que el servidor no verifica

2026-10-08. Dos hallazgos encontrados al analizar el cliente web, antes de
tocar nada. Contexto completo en
[`04-DEVICE-BINDING.md`](../../04-DEVICE-BINDING.md#lo-que-el-servidor-verifica-hoy)
y en la entrada correspondiente de [`06-HOJA-DE-RUTA.md`](../../06-HOJA-DE-RUTA.md).

---

## Hallazgo 1 · `WTFUCK_PERMITIR_SOFTWARE_DEV` valía `true` por defecto

**Severidad: media.** Corregido.

```kotlin
// server/.../Main.kt, antes
val permitirSoftwareDev = System.getenv("WTFUCK_PERMITIR_SOFTWARE_DEV")?.toBoolean() ?: true
```

Un servidor arrancado sin la variable aceptaba aparatos `SOFTWARE_DEV`
(emuladores) en registro, vinculación y recuperación. Producción la fijaba en
`false` en `docker-compose.produccion.yml` y `docker-compose.tras-proxy.yml`;
cualquier otro despliegue quedaba abierto, y lo único que lo decía era una
línea `INFO` en la bitácora.

### Antes: el código sin corregir, sin la variable

Instancia levantada en 8310 con el build anterior y **sin** la variable:

```
INFO  wtfuck - Base lista. permitirSoftwareDev=true
```

```
=== 2. sin la variable: el defecto CIERRA ===
  FALLA SOFTWARE_DEV sin la variable -> 403  status=200 {"token":"<redactado>","usuarioId":"…","dispositivoId":"…","username":"npvbxgo"}
  FALLA el motivo lo dice  {"token":"<redactado>", …}
  FALLA el rechazo no reserva el username (mismo 403, no 409)  status=409
  PASA  un navegador sigue sin poder crear cuentas -> 403
  PASA  un nivel desconocido -> 400

=== 5 pasan, 3 fallan ===
```

Un `SOFTWARE_DEV` con material inventado recibió **200 y un token de sesión**.
El tercer rojo es consecuencia del primero: la cuenta se creó, así que el
segundo intento chocó con el username ya tomado (409).

### El arreglo

- `Config.leerPermitirSoftwareDev`: sin la variable → `false`. Solo el texto
  `true` (sin importar mayúsculas ni espacios) abre; `1`, `si`, `yes`, vacío o
  una errata cierran.
- `pruebas/arrancar-servidor.ps1` la pone en `true` a la vista. Su nueva
  opción `-SinEmuladores` la **quita** (no la pone en `false`): lo que se
  prueba es el despliegue que se la olvidó.
- Con `true`, el servidor deja un `WARN` al arrancar.
- Los compose de producción siguen fijándola en `false`, ahora como redundancia
  legible. El `docker-compose.yml` de desarrollo no levanta el servidor, así
  que no hay nada que poner ahí; se le añadió un comentario que lo dice.
- `README.md` y `09-DESPLIEGUE.md`: el arranque local a mano incluye
  `WTFUCK_PERMITIR_SOFTWARE_DEV=true`; sin ella los AVDs ya no se registran.

### Después

```
8300 (arrancar-servidor.ps1):
INFO  wtfuck - Base lista. permitirSoftwareDev=true
WARN  wtfuck - WTFUCK_PERMITIR_SOFTWARE_DEV=true: se aceptan aparatos sin enclave seguro (emuladores). Solo para desarrollo. Ver docs/04-DEVICE-BINDING.md

8310 (arrancar-servidor.ps1 -Puerto 8310 -SinEmuladores):
INFO  wtfuck - Base lista. permitirSoftwareDev=false
```

```
=== 1. desarrollo (8300): el script la pone en true, a la vista ===
  PASA  un emulador se registra en la instancia de desarrollo

=== 2. sin la variable: el defecto CIERRA ===
  PASA  SOFTWARE_DEV sin la variable -> 403
  PASA  el motivo lo dice
  PASA  el rechazo no reserva el username (mismo 403, no 409)
  PASA  un navegador sigue sin poder crear cuentas -> 403
  PASA  un nivel desconocido -> 400

=== 3. LIMITE CONOCIDO: el nivel es declarado, no verificado ===
  PASA  un "TEE" declarado sin cadena de atestacion se ACEPTA (limite, no virtud)
  PASA  un "STRONGBOX" declarado tambien

=== 8 pasan, 0 fallan ===
```

---

## Hallazgo 2 · El servidor no verifica la atestación; el documento decía que sí

**Severidad: alta como afirmación, media como riesgo.** Documentación
corregida; verificación **no** implementada (decisión pendiente del dueño).

`docs/04-DEVICE-BINDING.md` describía que la app manda la cadena del Keystore
y el servidor la valida contra la raíz de Google. En el código:

| Dónde | Qué hace de verdad |
|---|---|
| `Repo.nivelDeTelefono` | Compara `hardwareNivel` contra una lista de strings |
| `Repo.registrar`, `Identidad.recuperarDispositivo`, `Dispositivos.vincular` | Usan ese nivel tal cual llega |
| `Hardware.generar()` (app) | El reto de atestación lo genera **el propio teléfono**; la cadena nunca se envía |

La sección 3 de la suite lo demuestra contra la API real: en la instancia
**sin** la variable (o sea, `permitirSoftwareDev=false`), un registro que
declara `TEE` con clave pública y `hardwareHash` inventados recibe 200 y token.

Consecuencia: el arreglo del hallazgo 1 frena a la app honesta en un emulador
y a nadie más. Y como el `hardwareHash` también es declarado, «una cuenta por
hardware» se cumple contra la app sin modificar, no contra un script.

**Lo que se hizo:** el documento dice la verdad, con una sección «Lo que el
servidor verifica hoy», el mecanismo completo que haría falta (reto emitido por
el servidor, cadena validada contra las raíces de Google, revocación,
`attestationApplicationId`, `rootOfTrust`) y su costo. Se corrigieron también
los comentarios de `Hardware.kt` que afirmaban lo mismo.

### Hallazgo relacionado, sin tocar

`POST /v1/registro` no tiene límite de ritmo. Con el nivel y el `hardwareHash`
declarados y el registro abierto (`WTFUCK_REGISTRO` por defecto), un script
crea cuentas sin tope. Hoy lo cierra `WTFUCK_REGISTRO=invitacion`.

---

## Pruebas que impiden que vuelva

**`server/src/test/.../NivelHardwareTest.kt` (9 pruebas, sin base):** el
defecto (`sin la variable se cierra`, `cualquier otra cosa cierra`), los
caminos principal y de vínculo con el interruptor cerrado y abierto, el
navegador, el nivel desconocido, y `un TEE declarado se acepta sin prueba`,
que fija el **límite**: el día que se implemente la atestación esta prueba se
cae y obliga a reescribir el documento en el mismo cambio.

Validado por reversión, devolviendo el defecto a `true` en el código:

```
NivelHardwareTest > sin la variable se cierra FAILED
9 tests completed, 1 failed
```

**`pruebas/nivel-hardware.mjs` (8 comprobaciones):** necesita la segunda
instancia sin la variable; si no está, se omite y lo dice.

```
.\pruebas\arrancar-servidor.ps1 -Puerto 8310 -SinEmuladores
node pruebas/nivel-hardware.mjs
```

## Resultado de las suites

- JUnit del servidor: **96 pasan, 0 fallan** (14 clases, incluida `NivelHardwareTest`).
- Integración, `node pruebas/correr.mjs` contra 8300 levantado con
  `arrancar-servidor.ps1` y 8310 sin la variable:

```
=== 49 suites · 1857 pasan, 0 fallan · 2 omitidas (bus, bus-inyeccion) ===
```

Las dos omitidas son las de siempre: necesitan Redis y una segunda instancia
con bus, que no estaban levantados.

Base de datos de esta máquina en el puerto **5434** (el 5433 lo ocupa otro
proyecto), así que todo se corrió con
`WTFUCK_DB_URL=jdbc:postgresql://localhost:5434/wtfuck`.

Sin verificar: la app Android no se recompiló; en `Hardware.kt` solo cambiaron
comentarios.
