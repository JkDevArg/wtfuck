# Tanda 1 a 7: lo rápido que se nota todos los días

Las siete mejoras de bajo esfuerzo de la comparación con WhatsApp, Telegram y
Signal. Todas probadas en dos emuladores.

## 1. Responder y marcar como leído desde la notificación

La notificación de un mensaje trae **Responder** (con campo de texto) y
**Marcar como leído**. Un receptor interno, sin exportar, atiende las dos:

- Responder escribe por la cola de envío normal, con cifrado y reintentos, y
  marca el chat como leído.
- Marcar como leído hace lo mismo que abrir el chat, acuse incluido.

Con el **bloqueo de la app** activo no se ofrecen: contestar desde la cortina
sería entrar a la conversación sin desbloquear.

Probado en `1-...`: "respondido desde la cortina" llegó al otro teléfono.

**Un defecto que solo apareció en el aparato.** Tras responder, la
notificación no se iba. Android 15 marca la notificación con
`LIFETIME_EXTENDED_BY_DIRECT_REPLY` e ignora el `cancel`. Tampoco funcionaba
publicar una nueva y cancelar: el sistema, con la respuesta todavía en curso,
volvía a poner la original. Lo que espera es una **actualización**. Ahora se
publica "Respuesta enviada" en silencio, que caduca sola en 1,5 s. Verificado:
a los 2 s ya no queda ninguna notificación activa.

## 2. Formato de texto

`*negrita*`, `_cursiva_`, `~tachado~`, `` `mono` `` y `||spoiler||`, como en
WhatsApp. El mensaje viaja tal cual se escribió: un cliente viejo ve los
asteriscos, que se leen igual.

Reglas pensadas para no formatear por accidente:

- una marca solo abre si delante no hay letra ni número, así que `2*3*4` y
  `@juan_perez_x` quedan como están;
- no cruza saltos de línea;
- lo monoespaciado no se formatea por dentro.

El spoiler va tapado y se destapa tocándolo (`2b-`). En la lista de chats, la
vista previa no muestra las marcas y deja el spoiler como `▒▒▒`. El analizador
es una función pura con 15 pruebas.

## 3. Borradores por chat

Lo que quedó escrito sin enviar se guarda por chat **en la base cifrada**, no
en las preferencias, que se guardan en claro. La lista lo muestra como
"Borrador: ..." en ámbar. Se guarda medio segundo después de dejar de escribir
y también al salir. No se guarda mientras editas un mensaje ya enviado. Room
pasa a la versión 22; la migración no tocó los chats existentes (verificado).

## 4. El editor también para las fotos del chat

Al elegir una foto se abre el editor de los estados con otra salida: recorte
Original, 1:1, 4:5, 9:16 o 16:9, más giro, filtros, texto y stickers
(`4-`).

- Si no se toca nada, se manda la **original**, sin recodificar.
- **Defecto encontrado midiendo:** un recorte 1:1 de una foto de 2400×1500
  salía a 2400×2400, es decir, la agrandaba. Ahora el tamaño se limita a los
  píxeles reales del recorte y del zoom (`Proporcion.ladoSinAgrandar`, con
  pruebas). Medido: **1500×1500**.

## 5. Teclado incógnito

Le pide al teclado que no aprenda lo que escribes
(`IME_FLAG_NO_PERSONALIZED_LEARNING`). Está en la raíz de la app, así que
cubre **todos** los campos. Viene encendido: es una app privada, y el valor por
defecto tiene que ser el seguro.

Verificado de dos formas: el campo pide `imeOptions=0x43000000`, que incluye
`0x01000000`, y Gboard muestra su icono de incógnito (`5-`). La pantalla dice
"pide" y no "impide": un teclado de terceros puede no respetarlo.

## 6. @todos

- Lo ofrece el selector de menciones solo en grupos y solo a quien puede fijar
  mensajes. El servidor aplica la misma regla y, sin permiso, lo ignora.
- Una mención ahora **suena aunque el grupo esté silenciado**. Por eso lo
  decide el servidor, no el texto: cada entrega trae `mencionado`. Si lo
  decidiera el teléfono, cualquiera se saltaría el silencio de todos escribiendo
  "@todos".
- Nadie puede registrarse como "todos".

`todos.mjs` (15 casos): acepta `@todos` de quien puede, lo ignora de quien no,
marca la entrega en vivo y también la que esperaba en el buzón, y una mención
normal marca solo a esa persona. En el emulador quedó registrada para los dos
miembros (`6-`).

## 7. Enviar sin sonido

Pulsación larga en el botón de enviar (`7-`). La marca viaja **dentro** del
sobre cifrado, así que el servidor no sabe ni eso. La notificación de quien
recibe llegó con el flag `SILENT`.

## Números

- Unitarias: 555 → **580**.
- Integración: **1672**, 0 fallos (suite nueva `todos.mjs`).
