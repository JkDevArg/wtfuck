# Tanda 8 a 18: lo que falta frente a WhatsApp, Telegram y Signal

Continúa la tanda 1 a 7. Cada punto se probó en los dos emuladores (5554 =
`xampl3`, 5556 = `goblin2026`) y, si tocó el servidor, en la suite de
integración.

## 8. Vista previa de enlaces

Al escribir un enlace aparece una tarjeta sobre el campo de texto, con
miniatura, título y dominio. La X la quita para ese enlace. Lo que se envía
lleva la tarjeta y lo que se recibe la muestra encima del texto. Los enlaces del
texto ahora se pueden tocar y van subrayados (`8-vista-previa.png`: a la
izquierda quien escribe, a la derecha quien recibe).

**Quién arma la tarjeta.** El teléfono que **envía**, y la tarjeta viaja dentro
del sobre cifrado, como en Signal. Las alternativas eran peores:

- **Que la arme quien recibe:** con solo abrir el chat, el sitio vería la IP de
  cada persona que lo lee, sin que nadie toque nada.
- **Que la arme nuestro servidor, como los GIFs:** vería qué enlaces se mandan
  dentro de conversaciones cifradas. Con los GIFs se aceptó que viera
  búsquedas, pero un enlace dice mucho más de una charla.

El costo, que el ajuste dice tal cual, es que el sitio ve la IP de quien envía,
igual que si lo abriera. Se apaga en *Privacidad → Este aparato → Vista previa
de enlaces*. Viene encendida, como en Signal y WhatsApp.

**Lo que no se le cree a quien envía.** La tarjeta la arma otra persona, así
que podría mentir:

- Una tarjeta solo se guarda si **su URL está en el texto** del mensaje. Si no,
  alguien podría mandar una tarjeta que dice "banco.com" y que abre otro sitio.
- El dominio que se muestra sale de la URL, **no** del campo `sitio` que mandó
  quien escribió. `https://banco.com@malo.com/` se muestra como `malo.com`
  (está probado).
- Cada campo y la miniatura tienen un tope.

**Al armarla:**

- solo se leen 512 KB de HTML y solo la cabecera;
- la miniatura se baja a 360 px en JPEG;
- se usa un cliente HTTP aparte, sin cookies, con tiempos cortos y sin el
  pinning de nuestro servidor;
- espera 0,7 s tras dejar de escribir para no pedir la página por cada letra.

El lector de metadatos es una función pura sin librerías, con 10 pruebas: Open
Graph en cualquier orden de atributos, entidades HTML, rutas relativas y
recortes.

Room pasa a la versión 23 (`mensaje.previaJson`). El servidor no cambió: la
tarjeta es parte del sobre.

**Dos defectos de este punto, encontrados en el punto 9:**

- **Contraste.** En tu propia burbuja (cian), el título de la tarjeta salía en
  el color del tema (claro) y casi no se leía. Ahora la tarjeta usa el color de
  texto de la burbuja.
- **Pulsación larga.** Mantener el dedo sobre un enlace para responder o
  reenviar abría el navegador. Se cuenta abajo, en el punto 9.

## 9. Nota para mí

Es la conversación donde solo estás tú, como "Mensajes guardados" de Telegram
o "Nota personal" de Signal. Está en *Nuevo → Nota para mí*, con un marcador
como avatar y "solo tú · en todos tus aparatos" debajo del nombre (`9a-`, `9b-`).

**Vive en el servidor, y no solo en el teléfono, para que llegue a tus otros
aparatos.** No hizo falta nada nuevo para eso. Los destinos de un envío son
los aparatos de los participantes menos el que envía, así que en una
conversación donde solo estás tú, los destinos son **tus otros aparatos**. El
servidor sigue viendo solo sobres cifrados. Con un solo aparato, el envío sale
sin copias y el servidor lo acepta igual.

**En el servidor (V45):**

- Nuevo tipo de conversación `notas`, con `clave_directa = notas:<usuario>`.
  Como esa columna es UNIQUE, no puede haber dos por persona aunque dos
  aparatos la pidan a la vez. Está probado con 6 pedidos simultáneos.
- `POST /v1/conversaciones/notas` la crea la primera vez y después devuelve la
  misma.
- Quien la crea entra con el rol `miembro`, el de una directa. Ese rol no tiene
  permisos para agregar gente, invitar ni crear roles. Las llamadas ya
  fallaban solas ("no hay a quién llamar").
- El temporizador se pone como en una directa.
- No se puede denunciar como si fuera un grupo, ni cerrarla desde el panel: lo
  que alguien se escribe a sí mismo no le llega a nadie.
- Una directa contigo mismo sigue rechazada.

`notas.mjs` tiene **36 casos**, entre ellos:

- se vincula una tablet y lo apuntado en el teléfono le llega a la tablet, con
  el cuerpo intacto;
- una copia dirigida a otra persona se descarta y no queda en su buzón;
- nadie de afuera puede leerla, escribir en ella ni mandar sobres por el socket;
- no se le puede agregar gente, crear invitaciones, crear roles ni llamar.

**En la app:**

- sin ficha de contacto, "Verificar cifrado" ni indicador de "escribiendo";
- con temporizador, búsqueda, exportar y vaciar;
- con un estado vacío que explica para qué sirve;
- se abre sin red si este aparato ya la conoce.

### Reenviar ahora es reenviar

Lo que más se hace con una nota es reenviarse cosas, y al probarlo apareció que
**"Reenviar" nunca dejó elegir a dónde**. Volvía a mandar el texto **al mismo
chat**, y de una foto mandaba solo el pie.

- Ahora abre **"Reenviar a…"** (`9c-`). "Nota para mí" va primero, aunque no
  exista todavía. Tiene buscador y deja elegir hasta 5 chats, el mismo tope de
  WhatsApp y por lo mismo: así corren las cadenas falsas. Los canales aparecen
  solo si puedes publicar en ellos.
- **Un texto** sale con su vista previa (`9d-`). Su enlace está en el texto, así
  que quien lo recibe la acepta igual que la primera vez.
- **Un archivo** se cifra con una **llave nueva** y se sube otra vez. Reusar el
  adjunto no funciona, porque el servidor solo deja bajarlo a quien está en el
  chat donde se subió. Tampoco convendría: una misma llave en dos chats los
  ataría para siempre. Sale de la copia local tal cual, sin volver a
  comprimirlo. Si todavía no se descargó, se dice. Verificado: la foto llegó al
  otro emulador, se bajó, se descifró y muestra "Reenviado de @xampl3" (`9e-`).
- `CargaAdjunto` ganó `reenviadoDe`. Antes una foto reenviada llegaba sin la
  marca de reenvío. El campo tiene valor por defecto, así que un cliente viejo
  lo ignora.
- Encuestas y eventos no se reenvían: sus votos y asistentes viven en su chat,
  y una copia quedaría muerta.

### La pulsación larga abría el enlace, o la foto

Al reenviar desde el emulador aparecieron dos defectos con la misma causa:

- mantener el dedo sobre un **enlace** abría Chrome;
- mantenerlo sobre una **foto** abría el visor.

En ninguno de los dos aparecía el menú. Los enlaces, el spoiler, la tarjeta y
los adjuntos solo saben de toques. Para Compose, un toque sin acción de
pulsación larga es "bajar y subir el dedo", tarde lo que tarde. Y como se
quedan con el dedo, la burbuja nunca se enteraba de la pulsación larga.

`sinAbrirConPulsacionLarga` mira el gesto en dos pasadas:

- en `Main`, para saber si algo de adentro se quedó con el dedo. Si fue así,
  abre el menú. Si no, lo abre la burbuja como siempre, y nunca se abre dos
  veces;
- en `Initial`, para consumir el levantar del dedo. Para el enlace eso es un
  gesto cancelado.

Un toque corto sigue abriendo el enlace. Moverse más que el umbral es
desplazar la lista y no se toca (`9f-`).

### Lo que llega de tu otro aparato es tuyo

Al pensar cómo se vería la nota en una tablet vinculada apareció un defecto
anterior que afecta a **todo chat** con varios aparatos. Lo que escribías en un
aparato llegaba a los otros como si lo hubiera escrito otra persona con tu
nombre: a la izquierda, sumando "no leídos" y con notificación. Ahora se
reconoce por el autor y se guarda como tuyo y ya enviado, sin notificación ni
contador.

**No está verificado en un aparato.** Haría falta vincular uno de los
emuladores como segundo aparato de la misma cuenta. El camino del servidor sí
está probado (`notas.mjs`, "dos aparatos").

## Números

- Unitarias: **590**, 0 fallos.
- Integración: 1672 → **1708**, 0 fallos (suite nueva `notas.mjs`).
