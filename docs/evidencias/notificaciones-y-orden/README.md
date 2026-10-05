# Notificaciones en segundo plano y el orden del chat

Dos quejas de uso real:

1. "La app queda en segundo plano y no me llegan notificaciones, aun con el
   celular suspendido."
2. "Abro el chat y me lleva a mensajes anteriores, no al último que me mandó
   la persona."

Ninguna de las dos tenía una sola causa. Se encontraron **seis**, cinco
reproducidas en dos emuladores antes de tocar nada.

## Notificaciones: cuatro causas

### 1. La notificación dependía de que existiera la pantalla

Las notificaciones se publicaban desde un colector en el `lifecycleScope` de
`MainActivity`. Con la app cerrada, el push despierta el proceso,
`ServicioPush` baja el sobre, lo descifra y emite en `notificables`... y no
hay nadie escuchando, porque sin interfaz no existe ninguna Activity. **El
mensaje se guardaba y la notificación se perdía**, justo en el caso para el
que existe el push.

Los colectores pasaron a `WtfuckApp`, que vive lo que vive el proceso, y se
instalan antes de `repo.iniciar()`: `notificables` no tiene replay.

> No se pudo probar de punta a punta, porque el servidor local no tiene
> Firebase. El arreglo se comprueba leyendo el código: el colector ya no
> depende de ninguna Activity.

### 2. "Chat abierto" no es "mirando el chat"

Reproducido: `goblin2026` abre el chat con `xampl3` y toca Inicio. `xampl3` le
escribe. Resultado: **cero notificaciones**, y un `GET .../leidos` desde el
aparato que estaba en el bolsillo.

`chatAbierto` decía qué pantalla estaba arriba, no si alguien la miraba. Todo
lo que llegaba a ese chat con la app en segundo plano:

- no notificaba ("ya lo está viendo");
- no sumaba no leídos;
- y mandaba un **acuse de lectura falso**: el otro veía la palomita cian de un
  mensaje que nadie había leído.

Ahora cuenta `chatVisible`: abierto **y** con la app en pantalla
(`MainActivity.onStart/onStop`). Al volver a un chat que quedó abierto, se lee
en ese momento. Después del arreglo: notificación publicada 22 s antes de la
consulta (la disparó el mensaje de prueba) y ningún `/leidos`.

### 3. El servidor le entregaba el mensaje a un proceso congelado

Con la app en segundo plano, Android **congela** el proceso, pero el socket
sigue abierto. Para el servidor el aparato estaba conectado: metía el sobre en
el canal, `empujar` devolvía true y, como "estaba conectado", **nunca pedía el
push**. El ping lo detectaba recién a los 60 s, y el mensaje ya se había
"entregado" a nadie.

Ahora `Hub.vigilarAcuse`: si a los 8 s el sobre sigue en el buzón sin acusar,
se pide el push igual. Se consulta la base, no la memoria, porque el acuse
puede entrar por otra instancia. Arreglo solo de servidor: **funciona sin
actualizar la app**.

Cubierto en `push.mjs` contra el stub de FCM:

```
=== conectada pero sin acusar: el proceso dormido ===
  PASA  el sobre entra por su socket abierto
  PASA  y al principio no se la despierta: para el servidor esta conectada
  PASA  como no acusa, a los pocos segundos se pide el aviso igual
  PASA  y el aviso sigue sin contenido
=== conectada y acusando: no hace falta despertarla ===
  PASA  recibe el sobre y lo acusa
  PASA  no se le manda ningun aviso: no hacia falta
```

### 4. Lo que el código no controla: el teléfono

En Honor, Huawei, Xiaomi y otras marcas, un gestor propio impide que el push
despierte una app cerrada si está en "gestionar automáticamente". No hay API
para saberlo.

Nueva sección **Ajustes > Notificaciones > Con la app cerrada**
(`5-diagnostico-app-cerrada.png`). Revisa, en orden: permiso, servidor con
push, servicios de Google, token registrado, optimización de batería y, en
esas marcas, los pasos a mano de cada una. Ofrece el ajuste que corresponde:
"Quitar la restricción" abre el diálogo del sistema, y al volver la sección se
vuelve a medir sola (probado: el aviso de batería desaparece). La regla es una
función pura con 11 pruebas.

Y un aviso que molestaba: "no se pudo establecer el cifrado con esa persona"
se emitía en **cada** pasada del despacho y salía en el chat abierto, aunque
fuera por mensajes atascados a otra persona. Ahora sale una vez por
conversación y dice con quién.

## Orden del chat: dos causas

### 5. El orden dependía del reloj de quien escribe

Reproducido con 5554 atrasado 15 minutos: la respuesta "A1", enviada la
última, apareció **arriba**, entre mensajes viejos
(`1-ANTES-respuesta-enterrada.png`).

El protocolo decía que `creadoEn` "es una pista; el orden real lo da `id`".
Pero `id` es un UUIDv7 generado por el **mismo** cliente con el **mismo**
reloj. La garantía no existía.

**Primer intento, revertido:** usar la hora del servidor. Ordenaba bien, pero
la suite de integración lo atrapó: `msgoff.mjs` falló en tres pruebas, que
existen a propósito desde el 16 de septiembre. Un mensaje escrito sin red a
las 8 y entregado a las 18 debe mostrarse **de las 8**: es la promesa de msg
off. La hora del servidor la rompía.

**Solución:** separar los dos casos, que desde el servidor son el mismo número.

- El **teléfono** corrige su reloj. El servidor manda su hora en cada
  respuesta (`X-Hora`) y en cada aviso de aceptado (`servidorEn`). `Reloj`
  mide el desfase, ignora menos de 2 s (latencia) y fecha los mensajes propios
  con la hora corregida. Sigue siendo hora de autoría: msg off intacto.
- El **servidor** recorta el futuro: nadie escribe un mensaje después de que
  llega. Esto no toca msg off, que siempre trae horas pasadas.
- Un mensaje propio nunca nace por debajo del último de la conversación.

Resultado (`2-DESPUES-...`): con 5554 marcando 03:05, su mensaje sale con
**03:21** y queda abajo del todo en los dos teléfonos.

### 6. La lista estaba anclada arriba

`3-ANTES-teclado-tapa-lo-nuevo.png`: al abrirse el teclado, la lista se
encoge y se queda anclada arriba, y lo nuevo queda escondido debajo. Pasa lo
mismo cuando cargan fotos o stickers.

Ahora la lista es `reverseLayout`: se ancla abajo, en lo más nuevo, y lo que
cambia de tamaño empuja hacia arriba lo viejo. Abrir un chat ya muestra el
final, sin animar desde el primer mensaje. Los cinco saltos a un mensaje
(buscador, fijado, ir a mensaje) traducen el índice. Y un mensaje nuevo solo
baja la lista si estabas cerca del final o si es tuyo: antes arrastraba
siempre, aunque estuvieras releyendo historial.

## Números

- Unitarias: 518 → **536** (Reloj 7, diagnóstico 11), 0 fallos.
- Integración: **1624**, 0 fallos (suite nueva `reloj.mjs` con 10 pruebas, y
  6 casos nuevos en `push.mjs` contra el stub).

## Lo que necesita el servidor de producción

Los arreglos 3 y 5 son de servidor y valen apenas se despliegue. Para que
funcione el push con la app cerrada, el servidor necesita `WTFUCK_FCM_*`: si
el log dice "Push sin configurar", ninguna corrección de la app puede
compensarlo.
