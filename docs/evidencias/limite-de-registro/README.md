# Límite de ritmo en el registro · y la IP que cualquiera podía elegir

2026-10-08. Cierra el hallazgo que quedó anotado como "sin tocar" en la entrada
de nivel de hardware de [`06-HOJA-DE-RUTA.md`](../../06-HOJA-DE-RUTA.md): con
el registro abierto y el nivel y el `hardwareHash` **declarados** (ver
[`04-DEVICE-BINDING.md`](../../04-DEVICE-BINDING.md#lo-que-el-servidor-verifica-hoy)),
`POST /v1/registro` creaba cuentas sin tope.

---

## 1 · Antes de fijar un número: cuánto registra el uso legítimo

Tres veces en este proyecto un límite por IP dejó fuera a un campus entero
(ingreso, SMS, vinculación), y las tres lo destapó la regresión **después**.
Esta vez se midió antes.

**Una corrida de la regresión, sola**, contra una instancia propia (8320) con
el código anterior, contando las filas `registro` de `evento_seguridad` antes y
después de cada suite:

| Suite | Altas |
|---|---|
| `ajeno-lectura` | 58 |
| `ajeno` | 51 |
| `historias` | 13 |
| `llamadas` | 13 |
| `privacidad-fina` | 7 |
| las otras 46 | entre 0 y 6 cada una |
| **total (51 suites)** | **277**, en 4 min y medio |

Pico en un minuto (ventana deslizante): **127**. `bus` y `bus-inyeccion` no
entraron en esa medición (necesitan Redis y una segunda instancia) y suman
unas pocas más.

**El historial de la máquina de desarrollo**, donde varias sesiones comparten
`::1` —que es exactamente lo que pasa detrás de la salida a internet de un
campus—: 20 corridas en cuatro días, la mayor con **784 altas en 13 minutos**,
y un pico de **164 en un minuto**.

Los números por defecto salen de ahí: **300 por minuto** (casi el doble del
pico) y **2000 por día** (2,5 veces la corrida mayor). `LimiteDeRegistroTest`
fija los dos márgenes, para que nadie los baje sin ver esta tabla.

## 2 · Lo que hubo que arreglar primero: de dónde sale la IP

```kotlin
// Main.kt, antes
private fun ApplicationCall.ipCliente(): String =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim() ...
```

La **primera** entrada de `X-Forwarded-For`, venga de donde venga. Esa entrada
la escribe el cliente: nginx con `$proxy_add_x_forwarded_for` (lo que trae
`despliegue/nginx-tras-panel.conf`) agrega la IP real **al final**. El
comentario decía que "en el peor caso alguien se regala su propio cupo, no el
de otro", y las dos mitades eran falsas:

- Con una cabecera inventada por petición, cada intento estrenaba cupo:
  **ningún límite por IP limitaba nada** (ingreso, SMS, vinculación, y el del
  registro que se estaba por poner).
- Y sí se gasta el de otro: poner la IP de salida de un campus delante llena
  **su** contador. Con los fallos de ingreso, eso deja sin entrar a todo el
  campus sin haber tocado ninguna de sus cuentas.

Ahora (`Seguridad.ipDeCliente`): la cabecera cuenta **sólo** si la conexión
viene de loopback o de una red privada (donde están Caddy y nginx en los dos
despliegues documentados), y de ella se toma la **última** entrada. Reversión 3,
abajo, reproduce el ataque.

## 3 · Lo que se puso

| | Ráfaga `registro_red` | Cupo diario `registro_red_dia` |
|---|---|---|
| Dónde vive | memoria (`Limitador`) | base (`contador_red`, migración V50) |
| Por defecto | 300 por minuto | 2000 por día |
| Para qué | cortar la avalancha sin tocar la base ni calcular Argon2 | el techo de verdad; un reinicio no lo perdona |
| Ajustable desde el panel | sí | sí |

- **Por red**: la IPv4, o el **/64** de una IPv6. Un /64 entero se le da a un
  hogar o a una VM barata; contar por dirección regalaría trillones de cupos.
- **Cuentan intentos**, también los 409. Si "ese usuario ya existe" no gastara,
  la ruta sería un oráculo gratis para enumerar usernames, sin importar lo que
  cada persona eligió en privacidad.
- **Orden**: leer el cuerpo → puerta de invitación → ráfaga → cupo diario →
  validar y dar de alta. La puerta va **antes** para que quien prueba códigos
  inventados no gaste el cupo de su red (detrás de la cual está su campus); los
  límites van **antes** de validar para que el 400 y el 409 no salgan gratis.
  Leer el JSON va primero porque la puerta necesita el código, y rechazar uno
  ilegible no cuesta nada ni dice nada de nadie.
- **Ajustables desde el panel, los dos**, por el NAT: el día que una
  institución anuncia la app, toda su gente se registra desde la misma salida,
  y subir el tope ese día no puede esperar a un despliegue. Con un abuso en
  curso, bajarlo tampoco.
- `pruebas/correr.mjs` vacía `contador_red` al empezar, igual que vacía los
  límites de Redis: si no, la tercera corrida del día se queda sin altas.

**Lo que no hace:** frenar a quien reparte el script entre muchas IPs. Ningún
límite por IP lo hace. Convierte "sin tope" en "un tope por sitio"; contra lo
otro están `WTFUCK_REGISTRO=invitacion` y verificar la atestación.

## 4 · La suite y las reversiones

`pruebas/limite-registro.mjs`, **34 pruebas**. Simula varias IPs desde
localhost con `X-Forwarded-For` (localhost es "un proxy propio", como Caddy), con
IPs al azar de rangos reservados para pruebas (198.18.0.0/15 y 2001:db8::/32).
Los límites se **bajan desde el panel** para verlos saltar y se restauran al
final pase lo que pase. La sección 4 necesita una instancia con
`WTFUCK_REGISTRO=invitacion` (`WTFUCK_BASE_INV`); sin ella, lo dice y no la
cuenta.

Con el arreglo: **34 pasan, 0 fallan**. Cada reversión, por separado, contra
las instancias 8320 (abierta) y 8322 (con invitación):

| Reversión | Rojas | Lo que se ve |
|---|---|---|
| R1 · la ruta sin los dos límites (el código anterior) | **13** | `[200,200,200,200,200]`; el 409 y el 400 salen gratis; el cupo diario no cuenta nada |
| R2 · la puerta de invitación **después** del límite | **3** | seis intentos sin invitación agotan la red: `[429,429,429]` para quien sí tiene una |
| R3 · la IP de la **primera** entrada de `X-Forwarded-For` | **2** | tres altas que ponen la IP de D delante le gastan el cupo a D: `[429,429,429]` |
| R4 · IPv6 contada por dirección, sin /64 | **1** | cuatro direcciones del mismo /64: `[200,200,200,200]` |
| R5 · los límites dentro de `Repo.registrar`, después de validar | **4** | `400` y `409` donde debía ir `429`; tres 409 seguidos y el alta siguiente pasa |

Salidas de R1 (los tokens, redactados):

```
=== 2 · la rafaga, por red ===
  FALLA desde una red: 3 altas y despues 429  [200,200,200,200,200]
  FALLA el 429 dice cuanto esperar  {"token":"<redactado>","usuarioId":"…","dispositivoId":"…","username":"lrp2pob7"}
  FALLA con la red agotada, un username invalido da 429 y no 400  400 {"motivo":"El usuario debe tener 3-24 caracteres: letras, numeros o guion bajo."}
  FALLA y uno que ya existe da 429 y no 409: el oraculo tambien se agota  409 {"motivo":"Ese usuario ya existe."}
  FALLA gastan la rafaga: el alta valida que sigue da 429  200 {"token":"<redactado>", …}
  FALLA cuatro direcciones del mismo /64 comparten rafaga  [200,200,200,200]
  FALLA anteponer una IP inventada no estrena cupo  200 {"token":"<redactado>", …}
=== 3 · el cupo diario, en la base ===
  FALLA 2 altas al dia desde una red, la tercera 429  [200,200,200]
  FALLA y el motivo dice que es el del dia y por red  {"token":"<redactado>", …}
  FALLA el contador esta en la base y conto tambien el rechazado  0
  FALLA y el exceso queda anotado con la IP  0
  FALLA dos 409 gastan el cupo del dia: el alta valida da 429  [409,409,200]
=== 4 · la puerta de invitacion va ANTES del limite ===
  FALLA [inv] los rechazados no gastaron: 2 altas con invitacion y despues 429  [200,200,200]
=== 21 pasan, 13 fallan ===
```

R2:

```
=== 4 · la puerta de invitacion va ANTES del limite ===
  FALLA [inv] sin codigo: 400, nunca 429  [400,400,429]
  FALLA [inv] con uno inventado: 403, nunca 429  [429,429,429]
  FALLA [inv] los rechazados no gastaron: 2 altas con invitacion y despues 429  [429,429,429]
=== 31 pasan, 3 fallan ===
```

R3:

```
=== 2 · la rafaga, por red ===
  FALLA anteponer una IP inventada no estrena cupo  200 {"token":"<redactado>", …}
  FALLA no le gastaron nada a D: sus 3 altas pasan  [429,429,429]
=== 32 pasan, 2 fallan ===
```

R4:

```
  FALLA cuatro direcciones del mismo /64 comparten rafaga  [200,200,200,200]
=== 33 pasan, 1 fallan ===
```

R5:

```
  FALLA con la red agotada, un username invalido da 429 y no 400  400 {"motivo":"El usuario debe tener 3-24 caracteres: letras, numeros o guion bajo."}
  FALLA y uno que ya existe da 429 y no 409: el oraculo tambien se agota  409 {"motivo":"Ese usuario ya existe."}
  FALLA gastan la rafaga: el alta valida que sigue da 429  200 {"token":"<redactado>", …}
  FALLA dos 409 gastan el cupo del dia: el alta valida da 429  [409,409,200]
=== 30 pasan, 4 fallan ===
```

## 5 · JUnit

`LimiteDeRegistroTest`, 15 pruebas. Lo que una prueba por HTTP no alcanza
desde localhost —una conexión desde una IP **pública** ignora la cabecera— y
los números de fábrica, que en la suite se ajustan desde el panel y por eso no
se ven: el cupo diario más estricto que la ráfaga extrapolada (si no, el cupo
sería decorativo), y los dos por encima de lo medido en la sección 1. Más el
contador en la base: suma por red y por acción, sigue contando pasado el tope,
el evento sobrevive y el barrido se lleva las ventanas viejas (es una IP: dato
personal).

Servidor completo: **111 pruebas, 0 fallos** (96 anteriores + 15).

## 6 · Regresión

`node pruebas/correr.mjs` completo, **52 suites: 1886 pasan, 0 fallan**, más
`privacidad` (28/28) corrida aparte por lo que sigue. Total **1914**.

Se corrió contra instancias propias y no contra 8300, porque en 8300 y 8310
había otra sesión trabajando con su propio build: 8320 y 8321 con Redis
(`WTFUCK_BASE_B`, para `bus`), 8330 sin emuladores (`WTFUCK_BASE_PROD`, para
`nivel-hardware`) y 8322 con invitación (`WTFUCK_BASE_INV`). La nueva suite
corrió completa, con la sección de invitación.

`privacidad` quedó ROTA en esa corrida (`timeout evento`) y no por este
cambio: tiene `ws://localhost:8300` escrito a mano, así que su socket se abría
contra el servidor de la otra sesión y el aviso que emitía 8320 no le llegaba.
Ya fallaba igual con el código anterior en la medición de la sección 1. Con
una copia temporal que apunta el socket a 8320: **28 pasan, 0 fallan**. Otras
cinco suites tienen la misma URL fija (`copias`, `e2e`, `expulsado`, `msgoff`,
`reloj`) y pasaron igual, porque su recorrido no depende de un aviso cruzado
entre instancias.

## Hallazgo sin tocar

`Cupos.exigir` —el de las denuncias y la ficha de empresa— anota el evento
`limite_excedido` **dentro** de la transacción y después lanza; el ROLLBACK se
lo lleva. En la base no hay **ni uno** de esos eventos, aunque
`limites-cuenta.mjs` choca contra el cupo de la ficha en cada corrida. El nuevo
`Cupos.exigirPorRed` lo anota en su propia transacción, y una JUnit lo fija;
el viejo queda para un cambio aparte.
