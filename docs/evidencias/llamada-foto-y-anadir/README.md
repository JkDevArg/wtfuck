# Foto en la llamada y "añadir persona", probadas en dos emuladores

La foto y el botón se escribieron con el teléfono desconectado del ADB, así
que nunca se habían visto funcionar. Esta es la primera prueba de punta a
punta, con dos aparatos y audio real por el relevo.

**La prueba encontró cinco defectos, y cuatro rompían la función entera.**
Ninguno salía en las 508 pruebas unitarias ni en la suite de integración,
porque todos dependen del tiempo o de cuánto vive una pantalla: dos llamadas
seguidas, una pantalla que se cierra a media corrutina, un estado que dura
1,5 segundos.

## Montaje

| | emulador | AVD | cuenta | foto |
|---|---|---|---|---|
| quien llama | `emulator-5554` | Pixel10_API37 | `xampl3` | cian |
| quien recibe | `emulator-5556` | Pixel10b_API37 (nuevo) | `goblin2026` | naranja |
| el tercero | ningún aparato | cuenta de la suite | `probador` | rosa |

- Las fotos son PNG de un solo color, de 96×96, hechos a mano. Un solo color a
  propósito: así, en una captura, no hay duda de qué foto es de quién.
- `Pixel9_API36` sigue roto: pantalla negra fija y `START_CLASS_NOT_FOUND`
  (código -92), incluso después de desinstalar y reinstalar limpio. Se creó
  `Pixel10b_API37` desde la misma imagen que funciona.
- coturn de desarrollo en Docker. Los relevos van en **55000-55040** y no en
  el rango habitual: Windows reserva 49152-49651 para Hyper-V y el contenedor
  no arranca ("bind: An attempt was made to access a socket in a way
  forbidden").
- La BD en el **5434**, porque `pokeworld-db-1` (otro proyecto) arrancó solo
  al reiniciarse Docker y se quedó con el 5433. Se usó un override de compose
  fuera del repo y `WTFUCK_DB_URL`; no se tocó el otro proyecto.
- `pruebas/ui.sh` (nuevo): toca por **texto** con `uiautomator` en vez de por
  píxel fijo. Tocar por píxel se rompe con cualquier cambio de diseño, y el
  fallo parece del código.

## Lo que funcionó a la primera

**La foto, en las dos pantallas completas.** Quien llama ve la naranja de
`goblin2026` (`1-llamando-caller.png`); quien recibe, la cian de `xampl3`
(`2-entrante-callee-sin-aviso.png`), y siguen ahí con la llamada en curso
(`3-en-curso-*.png`). `ICE: CONNECTED`: el audio viajaba de verdad.

**El botón**, en los dos lados de una llamada de dos y solo ahí: en la de grupo
no aparece (`9-grupo-en-curso-*.png`), que es lo diseñado.

## Los cinco defectos

### 1. Un contacto sin claves frenaba TODA la cola de envío

No era de la llamada: salió al preparar la prueba. El mensaje a `goblin2026`,
que estaba en línea, no salía. Delante tenía dos mensajes a `probador`, cuya
cuenta no tiene prekeys:

```
GET /v1/claves/dispositivo/<probador>  -> 409 Conflict
(y nada más: el de goblin2026 nunca llegaba a pedir claves)
```

`despacharSinCandado` hacía `return` cuando una conversación no podía cifrar,
y con eso **frenaba también las demás**. El caso 4xx ya se había arreglado con
`continue` (el comentario lo dice), pero quedaban tres caminos: sin destinos,
fallo al cifrar y "nadie con sesión".

**En producción:** un contacto cuya cuenta nunca terminó de arrancar te deja
sin poder escribirle a nadie, con la barra en "Enviando 2 pendientes..." para
siempre.

Ahora se marca **esa conversación** como atascada y se sigue con las otras. El
orden se respeta dentro de cada conversación, que es donde importa. Los fallos
de red siguen cortando todo, porque no son de una conversación. Después del
arreglo:

```
GET .../claves/dispositivo/<probador>    -> 409   (se salta)
GET .../claves/dispositivo/<goblin2026>  -> 200   (sale)
```

### 2. El selector salía vacío

"No tienes contactos guardados" (`4a-selector-vacio-ANTES.png`), con dos chats
abiertos. Miraba solo la libreta, y en esta app a la gente se llega por su
usuario, no agendándola. "Solo la libreta" es "casi nunca nadie": la función
parecía rota.

Además no excluía a quien ya estaba en la llamada: si la tenías agendada,
"añadirla" creaba un grupo de dos consigo misma.

Ahora `candidatosParaLlamada` junta los chats directos (del más reciente al
más viejo) y la libreta, sin duplicados, sin quien está en la llamada y sin
uno mismo, y con la foto del chat (`4b-selector-con-chats-DESPUES.png`). Es
una función pura: **10 pruebas nuevas**, una por regla, empezando por el caso
que falló de verdad.

### 3. La llamada nueva no se pedía nunca, y sin aviso

El grupo se creaba y la llamada vieja se colgaba. Después, nada:

```
POST /v1/conversaciones/grupo           -> 200
POST /v1/llamadas/<vieja>/terminar      -> 204
(ningún POST /v1/llamadas)
```

La secuencia colgar → esperar 600 ms → llamar corría en el
`rememberCoroutineScope` de la pantalla de la llamada. **Colgar saca esa
pantalla de la composición**: su ámbito se cancela durante la espera y
`llamar()` no llega a ejecutarse. Tampoco salía el error, porque la
cancelación no es un fallo y la pantalla que lo habría mostrado ya no existía.
Para quien toca el botón, "añadir persona" cortaba la llamada sin más.

Ahora la secuencia corre en el ámbito de la app, y un fallo **después** de
colgar sale por `rechazos`, que lo muestra el chat al que se vuelve
(`5a-grupo-no-arranca-ANTES-caller.png` es ese aviso, visto en el intento
siguiente).

### 4. La llamada vieja, ya terminada, seguía contando como "en curso"

Con el 3 arreglado, el aviso decía por qué:

```
W Repo: Grupo creado pero la llamada no arranco: Ya hay una llamada en curso.
```

Al terminar, el estado se queda **1,5 s en `TERMINADA`** para que la pantalla
muestre "llamada finalizada". El guardia de `llamar()` preguntaba "¿hay
estado?" en vez de "¿hay una llamada viva?", y los 600 ms de espera caían
dentro de esa ventana.

Debajo apareció un problema más grave: el guardia estaba **dentro** del
`runCatching` cuyo `onFailure` hace `limpiar()`. Pulsar "llamar" en medio de
una llamada de verdad **la colgaba**. Desde un chat no se pudo reproducir,
porque durante una llamada el chat cambia los botones por la píldora de la
llamada (`6-chat-durante-llamada-sin-boton-llamar.png`). Pero cualquier otra
forma de llegar a `llamar()` lo provocaba. El guardia ya va fuera.

Por la misma razón, `limpiar()` corría dos veces y pisaba el motivo bueno
("colgada") con `null`. Ahora corre una sola vez: solo esa función pone
`TERMINADA`, así que si ya lo está, la limpieza ya se hizo.

### 5. El mismo defecto, del lado de quien recibe

Con el 4 arreglado, la llamada de grupo salía (`POST /v1/llamadas` 200, 700 ms
después), pero en el otro emulador no sonaba nada y en el primero decía
"goblin2026 no entró" (`5b-grupo-no-suena-ANTES-callee.png`).

`ofertaEntrante` veía la llamada vieja en `TERMINADA`, con otro id, y la
tomaba por **ocupado**: rechazaba la nueva en el acto, sin sonar y sin dejar
log. Es el gemelo del 4. Ahora `TERMINADA` cuenta como ninguna llamada, y el
rechazo por ocupado deja una línea en el log.

> Arreglar el guardia de un lado no es arreglar el guardia. Es la misma
> lección que ya dejó AF.8 con el servidor y el cliente.

### Uno más, de la foto: la ventana minimizada

La foto se había puesto en la pantalla completa, y la ventana de minimizar
seguía con `url = null`: salía "GO" junto a la cabecera del chat, que sí tenía
la foto naranja. Ahora usa la misma (`7-minimizada-con-foto.png`).

## El flujo completo, después de todo

```
llamada de dos                        ICE: CONNECTED
"Añadir persona" -> probador
POST /v1/conversaciones/grupo         200
POST /v1/llamadas/<vieja>/terminar    204      ICE: CLOSED
POST /v1/llamadas                     200      (+700 ms)
GET  /v1/llamadas/turn                200      <- el otro lado arma su estado
contesta goblin2026                            ICE: CONNECTED en los dos
"con goblin2026 · llamando a probador"
```

`8a` y `8b`: suena como llamada de grupo. `9-*`: en curso, con el ícono de
grupo, sin la foto de nadie y sin el botón de añadir.

## Lo que queda

- `probador` no tiene aparato, así que se probó que al tercero le **suena**,
  no que **entra**. Una malla de tres con audio necesita un tercer aparato.
- En la llamada de grupo, quien recibe ve de título `@xampl3` (quien llama)
  con el ícono de grupo. El subtítulo dice que es de grupo, pero el título y
  el ícono dicen cosas distintas. No se tocó.
- La notificación de llamada entrante usa un ícono genérico, no la foto.
- No se probó en un teléfono. La foto ya se había comprobado en el Huawei; el
  resto, no.

## Números

- Pruebas unitarias: 508 -> **518**, 0 fallos.
- Integración: no se volvió a correr, porque no cambió nada del servidor.
