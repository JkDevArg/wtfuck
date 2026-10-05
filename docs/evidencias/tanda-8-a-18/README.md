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

## 10. Ver una vez

Se elige en *Adjuntar → Ver una vez*, y vale para foto y video. Una foto pasa
por el editor con el "1" encendido. Se puede apagar ahí, y entonces sale como
foto normal (`10a-`). Un video sale directo.

**Lo que sí hace:**

- **Sin miniatura ni pie.** La miniatura viaja dentro del sobre y es la foto en
  chico. El pie se quedaría en el chat, en la lista y en la notificación. Quien
  recibe ignora los dos aunque vengan.
- **Quien envía tampoco lo conserva.** Su copia se borra apenas el servidor
  acepta el envío (`10b-`). Verificado: en el emisor no queda ningún archivo.
- **Quien recibe no lo descarga hasta que lo abre** (`10c-`). El archivo
  descifrado no espera en el disco a que alguien decida.
- **Se marca como abierto antes de mostrarlo.** En ese momento se borran la
  llave del archivo, el pie y la miniatura. Al cerrar el visor se borra el
  archivo. Verificado: después de cerrar, el archivo ya no está y la burbuja
  dice "Abierta", sin forma de volver a abrirla (`10e-`).
- **Si la app muere con el visor abierto**, al volver a abrirla figura como
  abierta y un barrido borra el archivo. Probado con `am force-stop` mientras
  se veía el video.
- **El visor usa `FLAG_SECURE`.** El sistema no deja capturar ni grabar la
  pantalla, y en "recientes" la ventana sale en negro. La captura que se tomó
  con el visor abierto salió **negra** (`10d-`), y la ventana figura con el
  flag `SECURE` en `dumpsys`.
- **El video se reproduce dentro de la app.** Los otros videos se abren en el
  reproductor del sistema, pero abrir este afuera sería entregarle el archivo a
  otra app.
- **No se reenvía**, ni desde el menú ni por código.

**Lo que no puede hacer, y se dice en el visor:** impedir una foto a la
pantalla con otro teléfono. Tampoco puede impedir que un cliente modificado se
guarde el archivo y la llave al recibirlo: es una cortesía con barreras
técnicas, no una garantía. Lo mismo vale para WhatsApp y Signal.

**Pendiente:**

- avisarle a quien envió que se abrió;
- sincronizar el "abierto" entre los aparatos de quien recibe: hoy cada aparato
  vinculado lo deja ver una vez.

Room pasa a la versión 24 (`unaVez`, `unaVezAbierta`) y `CargaAdjunto` gana
`unaVez`. Ambos tienen valor por defecto, así que un cliente viejo no se
rompe. Ese cliente vería una foto normal sin miniatura, y por eso conviene
publicar esta versión antes de usarlo en serio.

De paso, "Copiar" ya no aparece en mensajes sin texto (una foto sin pie, un
"ver una vez"): copiaba una cadena vacía.

## 11. Mensajes programados

Se programan con una pulsación larga en enviar → *Programar envío*. Hay tres
atajos (en 1 hora, esta noche a las 20:00 si todavía falta, mañana a las 08:00)
y *Otra fecha y hora* con los selectores del sistema (`11a-`).

**Viven en el teléfono, a propósito.** El servidor no puede mandar nada en
nombre de nadie, porque solo tiene sobres cifrados. Para que lo hiciera habría
que dejarle el sobre y una hora, y eso es contarle cuándo alguien piensa
escribir y a quién. Telegram lo hace en el servidor porque ahí el servidor lee
los chats; Signal no lo tiene por lo mismo. El costo se dice en pantalla: sale
desde este teléfono, y si a esa hora está apagado o sin red, sale cuando
vuelva.

**Cómo:**

- Se guarda como cualquier mensaje, pero con estado `PROGRAMADO` y `oculto`.
  La cola solo toma `PENDIENTE`, así que no sale. `oculto` ya lo sacaba del
  chat, de la lista y del buscador, así que no hubo que tocar ninguna consulta.
  En el chat aparece un aviso: "1 mensaje programado · el próximo hoy 16:46".
  Abre la lista con *Enviar ahora* y *Cancelar* (`11b-`).
- A la hora pasa a `PENDIENTE` y lo demás lo hace la maquinaria de siempre:
  cifrado, reintentos y cola en segundo plano.
- **Qué lo despierta.** Una alarma `setAndAllowWhileIdle`, que el sistema
  respeta en reposo, y un trabajo de WorkManager como respaldo, porque las
  alarmas se pierden al reiniciar el teléfono. Al abrir la app también se
  liberan los vencidos. El cambio de estado lleva la condición en el `WHERE`,
  así que si llegan los dos no sale dos veces.
- La alarma es **inexacta a propósito**. La exacta pide un permiso que Android
  14 ya no da por defecto, y unos minutos de diferencia no justifican mandar a
  la persona a los ajustes del sistema.

**Probado en el emulador:**

- Se programó para las 16:46, con la app **en segundo plano**.
- `dumpsys alarm` mostraba la alarma con su ventana.
- Salió a las **16:48:11**, el final de la ventana inexacta. Llegó al otro
  emulador con esa hora (`11c-`), que es cuando salió y no cuando se programó.
- `creadoEn` queda en la hora en que se escribió y no en la futura. Una hora
  futura en una fila oculta habría empujado hacia adelante la hora de todo lo
  que se escriba después (`horaParaMio`).

`MomentoProgramadoTest`: 5 pruebas de los atajos, de las etiquetas ("hoy",
"mañana", "lun 12 oct") y de qué horas valen.

Room 25 (`programadoPara`).

## 12. Proteger un chat con huella

Se activa desde el menú del chat o desde la lista (*Proteger con huella*). Se
llama así y no "bloquear" porque "bloquear a @fulano" ya existe y es otra cosa
(`12a-`).

- **La puerta está en el chat, no en la lista.** A un chat se llega también
  desde una notificación, el buscador, un contacto o un enlace, y la puerta
  tiene que estar en todos. Mientras no se sabe si está protegido no se dibuja
  nada: ni un instante con los mensajes a la vista.
- **Qué pide.** Huella, rostro o el PIN del teléfono, con el mismo diálogo que
  el bloqueo de la app. Si se abrió hace menos de un minuto, ir a la lista y
  volver no la pide otra vez. Después de más de un minuto fuera de la app, sí.
  No al instante: elegir una foto o abrir la cámara también "sale" de la app, y
  cerrar el chat ahí perdería lo que se estaba eligiendo.
- **Lo que no se asoma:**
  - la lista dice "Chat protegido" en vez del último mensaje o del borrador;
  - el lector de pantalla tampoco lo lee;
  - el buscador lo salta;
  - la notificación dice "Mensaje nuevo en un chat protegido", sin quién y sin
    *Responder*, porque responder desde la cortina sería entrar sin la huella.
- **Quitar la protección.** Desde la lista pide la huella. Desde adentro del
  chat no hace falta, porque ya se verificó.
- **Sin huella ni PIN configurados no se ofrece.** Encenderlo sería un candado
  que no cierra. La app lo dice: "Primero configura una huella o un PIN en el
  teléfono" (`12b-`).
- **Solo vive en este teléfono.** El servidor no tiene por qué saber qué chats
  protege alguien.

**Lo que no se probó de punta a punta:** los emuladores no tienen huella ni
PIN, y configurarlos es cambiar la seguridad del aparato. Por eso no lo hice.
Se probó el camino sin credenciales y todo lo que no depende del diálogo. Falta
abrir un chat protegido en un teléfono real con huella.

Room 26 (`conversacion.protegido`).

### Un defecto del punto 3, encontrado aquí

`guardarConversacion` **reemplaza la fila entera**, y lo que llega del servidor
en cada sincronización no traía las columnas que solo viven en el teléfono. Eso
pasaba cada vez que se abría la app, y **borraba todos los borradores** y la
marca de "no leído". Con `protegido` habría pasado lo mismo. Ahora esas columnas
se copian de la fila anterior. Verificado: un borrador sobrevivió a matar la app
y a la sincronización al volver (`12c-`).

## 13. Carpetas de chats

Las carpetas son "Trabajo", "Familia" o lo que cada quien quiera. Se crean y
editan en *⋮ → Carpetas*, con nombre y casillas de chats (`13a-`). Aparecen
como pestañas después de Todos, No leídos y Grupos (`13b-`). Un chat se agrega
desde su menú con *Añadir a carpeta*, que marca y desmarca al momento y deja
crear una carpeta nueva con ese chat ya adentro (`13c-`, `13d-`).

- **Solo en este teléfono.** Telegram las sincroniza por su servidor. Aquí no:
  el nombre de una carpeta y quién está en ella dicen mucho de con quién habla
  alguien y para qué. El costo es que en otro aparato vinculado hay que
  armarlas de nuevo, y la pantalla lo dice.
- **Límites:** hasta 10 carpetas y nombres de hasta 20 caracteres. El nombre no
  puede repetirse, aunque cambien las mayúsculas, ni llamarse como una pestaña
  fija: dos "Grupos" en la barra no se distinguirían.
- **La barra.** Sin carpetas es la de siempre, con tres pestañas repartidas. Con
  carpetas se desplaza.
- **Borrar** una carpeta no borra los chats: solo salen de ella.
- **Al cambiar de cuenta o cerrar sesión** se borran con el resto de lo local,
  porque sus nombres dicen de quién eran.

`CarpetasTest`: 7 pruebas de nombres y límites. Room 27 (tablas `carpeta` y
`carpeta_chat`).

## 14. Info del mensaje en grupos

En un mensaje propio de un grupo, *pulsación larga → Info* muestra quién lo
leyó, a quién le llegó y a quién todavía no, con la hora (`14-`). En el
emulador: "Leído · @goblin2026 hoy 17:02" y "Todavía no le llegó · @probador",
que no está en ningún aparato.

**Lo nuevo en el servidor (V46).** La lectura ya se guardaba (tabla `lectura`).
La **entrega** no: acusar un sobre lo borra del buzón, y con él se iba el dato.
Ahora el acuse anota en `entrega` quién recibió qué mensaje y cuándo, en la
misma transacción que borra el sobre.

- **Solo en grupos.** En una directa el doble check ya lo dice todo, y no
  guardar es la forma más segura de no tener un dato.
- **Solo el hecho:** quién, qué mensaje y cuándo. Dura lo que dura
  `mensaje_meta`, y se borra con él.
- Lo que llega a mis otros aparatos no cuenta: a mí no me "llega" lo que
  escribí.

**`GET /v1/mensajes/{id}/info`:**

- **Solo para quien lo escribió.** A cualquier otro le responde 404 y no 403,
  porque que el mensaje exista ya es un dato.
- **Solo cuenta a quienes estaban** en el grupo cuando se mandó. Quien entró
  después saldría para siempre como "pendiente".
- **Lecturas recíprocas, como siempre.**
  - Quien no comparte confirmaciones sale como *entregado* aunque haya leído, y
    su lectura ni se guarda.
  - Si quien pregunta las apagó, no ve ninguna, y la pantalla dice por qué en
    vez de mostrar a todos como "no leído".

`info.mjs` (**23 casos**) cubre:

- entregas y lecturas;
- a quien no comparte;
- el 404 a otros miembros y a gente de afuera;
- a quien entró después;
- la reciprocidad;
- que en una directa no se guarda nada y que lo propio no cuenta.

`ajeno-lectura.mjs` exige que toda ruta de lectura nueva esté probada contra
otra cuenta: avisó de esta y quedó cubierta (no eximida).

## 15. Videonotas

Las videonotas son como las de Telegram: *Adjuntar → Videonota* abre la
cámara en un círculo. Se toca para grabar y otra vez para terminar, hasta 60
segundos, y después *Enviar* o *Repetir* (`15a-`, `15b-`). En el chat van
sueltas, en un círculo y sin burbuja, con su miniatura y su duración. Al
tocarlas suenan ahí mismo (`15c-`, `15d-`).

- **Con la misma CameraX** del escáner de QR. Solo se sumó `camera-video` con
  el mismo `version.ref` (1.6.2), sin otra biblioteca.
- **En calidad SD.** Se ve en un círculo de 220 dp, y un minuto en alta
  definición serían decenas de megas cifrados y subidos para nada. Medido: 6
  segundos ocupan **581 KB**.
- **Viajan como un video**, con `CargaAdjunto.forma = "circulo"`. Un cliente
  viejo lo ignora y la ve como lo que es, un video normal. Quien recibe solo
  acepta esa forma en un video. Al reenviarla conserva la forma.
- **Detalles de Android que hubo que resolver:**
  - la vista previa usa un `TextureView` (modo `COMPATIBLE`), porque un
    `SurfaceView` no se deja recortar en círculo y se veía cuadrado;
  - lo mismo el reproductor de la burbuja, con un recorte al centro para no
    deformar el video;
  - la grabación sale espejada como la vista previa.

**Dos defectos encontrados probando:**

- **Sin cámara frontal la pantalla quedaba negra.** El emulador tiene solo la
  trasera, y hay tablets y teléfonos así. Ahora usa la frontal si existe y, si
  no, la trasera.
- **Al terminar decía "Lista: 0:00".** El reloj se ponía en cero también al
  parar. Ahora dice "Lista: 0:04".

Room 28 (`adjuntoForma`).

## 16. Transcribir notas de voz y traducir mensajes, en el teléfono

En la pulsación larga hay dos opciones nuevas:

- **Transcribir**, en notas de voz y audios. El texto queda guardado debajo de
  la nota y se hace una sola vez.
- **Traducir**, en mensajes de texto ajenos. La traducción aparece debajo, con
  la marca "Traducido en el teléfono", y no se guarda.

**Nunca en un servidor.** Mandar el audio o el texto a un servicio de internet
es mandarle el mensaje descifrado a un tercero: el cifrado de punta a punta
dejaría de significar algo justo al leer. Se usan las piezas de Android que lo
hacen dentro del aparato, y **no hay plan B en la nube**. Si el teléfono no
puede, lo dice.

- **Transcripción.**
  - Se usa `SpeechRecognizer.createOnDeviceSpeechRecognizer` (Android 13+) con
    el audio de la nota entrando por un tubo (`EXTRA_AUDIO_SOURCE`).
  - La nota se decodifica con MediaCodec a PCM de 16 bits, mono y 16 kHz. La
    baja de tasa es una función pura con 4 pruebas (`RemuestreoTest`).
  - La sesión va por segmentos: una nota de voz tiene pausas, y sin eso el
    reconocedor se cortaría en el primer silencio.
- **Traducción.**
  - El idioma de origen se detecta en el aparato (`TextClassifier`) y la
    traducción la hace `TranslationManager.createOnDeviceTranslator`
    (Android 12+).
  - Solo se usa si la capacidad del par de idiomas es `STATE_ON_DEVICE`.
- **Idiomas sin descargar.** Si el idioma se puede bajar, para transcribir se
  le pide al sistema que lo baje; para traducir, se indica a la persona dónde
  hacerlo. La descarga es del sistema y lleva solo el modelo, nada de nadie.

**Lo que se probó en el emulador**, que trae Android System Intelligence, el
mismo servicio de los Pixel:

- **Traducir.** Un "Mañana nos vemos a las diez en la biblioteca…" se detectó
  como español y el traductor del aparato respondió que el par español→inglés
  está "para descargar". La app no salió a internet y lo dijo (`16a-`).
- **Transcribir.** Se cambió el audio de una nota por una voz en inglés,
  generada con el TTS de Windows. La nota se decodificó y el reconocedor del
  aparato encontró el paquete "English (US)" sin instalar. La app le pidió al
  sistema que lo bajara, y el sistema mostró su propio diálogo: "Download
  English (US) update (62.06 MB)" (`16b-`).

**Lo que falta:** ver una transcripción y una traducción terminadas. Hace
falta descargar esos modelos (62 MB el de voz), y no los descargué sin
preguntar. En un teléfono que ya los tiene, el camino es el mismo.

**Defectos de texto encontrados:**

- Decía "del Spanish", con el nombre del idioma en el idioma del teléfono.
  Ahora dice "del español".
- Decía "está descargando", pero el sistema pregunta antes de bajar. Ahora
  dice que, si se acepta la descarga, se intente cuando termine.

Room 29 (`transcripcion`).

## 17. Compartir desde otras apps, atajos y widget

**Compartir hacia wtfuck.** Desde cualquier app, *Compartir → wtfuck* abre
"Enviar a…": el mismo selector de reenviar, con "Nota para mí" primero y un
resumen de lo que llega ("Texto", "1 archivo") (`17a-`). El texto sale como
mensaje y cada archivo como adjunto. Si se eligió un solo chat, se abre.

- **Los archivos se copian al llegar.** El permiso para leer lo que manda otra
  app dura lo que dura la pantalla, y un video tarda más en subir. La subida va
  en el ámbito del repositorio, así que cerrar la hoja no la corta.
- **Solo `content://` de OTRAS apps.** Un `file://` o una URI del propio
  proveedor se leerían con los permisos de wtfuck: otra app podría "compartir"
  `file:///data/data/com.wtfuck.app/databases/wtfuck.db` y hacernos mandar
  nuestra propia base. Probado con los dos ataques: **no se ofrece mandar
  nada**.

**La fila de compartir del sistema** muestra los 3 chats recientes (`17b-`).
Si se toca uno, la hoja se abre con ese chat ya marcado (`17c-`). Probado
desde Google Fotos: la foto llegó al chat (`17d-`).

**Atajos del icono:**

- "Nota para mí" es fija.
- Los chats recientes son dinámicos.

Un atajo es un nombre que **el sistema** guarda y muestra fuera de la app, así
que:

- nunca aparece un chat protegido;
- con el bloqueo de la app encendido no se publica ninguno, porque el menú del
  icono se ve sin desbloquear;
- se publican tres como máximo, y solo cuando cambian, porque el sistema limita
  cuántas veces por día se pueden tocar.

**El widget** muestra cuántos mensajes hay sin leer y tiene un botón a la nota
(`17f-`). **Nunca nombres ni mensajes:** está en la pantalla de inicio, sin
pasar por el bloqueo. Se actualiza solo con la app en segundo plano: pasó de
"1" a "2 mensajes sin leer" al llegar otro mensaje (`17g-`).

**Un defecto anterior, encontrado aquí:** tocar una notificación abría la app
**en la lista y no en el chat**. Las notificaciones mandaban el chat en el
Intent y `MainActivity` nunca lo leía. Ahora hay un solo camino para todo lo
que pide algo desde afuera (`Pedidos`): notificaciones, atajos, widget y
compartir. Verificado: la notificación abre el chat (`17e-`).

**Otro, encontrado probando el atajo:** el pedido vivía en un objeto global.
Un atajo creó una segunda pantalla, la que quedó en segundo plano se llevó el
pedido y navegó donde nadie la veía. Ahora el pedido es de cada pantalla.

`AtajosTest`: 5 pruebas sobre qué chats se vuelven atajo y qué dice el widget.

## 18. Proxy

En *Privacidad → Este aparato → Proxy* se elige SOCKS5 o HTTP, con dirección y
puerto. *Probar* se conecta al servidor a través del proxy antes de guardar
(`18a-`). Sirve el de Orbot (Tor), que corre en el propio teléfono, o el de
Psiphon.

- **Cubre todo lo que la app le pide a la red:** la API, el socket de mensajes,
  los archivos, las vistas previas, las fotos de perfil y el mapa. Todos los
  clientes se construyen en un solo lugar (`Red`), que elige el proxy en cada
  conexión.
- **No cubre las llamadas** (WebRTC va por su propio camino) **ni el push**, que
  lo entrega el sistema. La pantalla lo dice.
- **El proxy no puede leer nada.** La conexión con el servidor va cifrada
  dentro del túnel y con el certificado fijado, así que el proxy ve que hablas
  con el servidor pero no qué dices. Además, los mensajes van cifrados de
  punta a punta.
- **El DNS lo resuelve el proxy.** Con SOCKS5 el nombre del servidor se manda
  sin resolver, así que un DNS bloqueado en la red no molesta. Hay una prueba
  unitaria de eso.
- **Al cambiarlo se cierran las conexiones abiertas** y el socket se rehace en
  el momento. Si no, el cliente seguiría usando las conexiones directas hasta
  que se cayeran solas, y el proxy parecería no hacer nada.
- **Sin usuario ni contraseña de proxy**, a propósito: los de Tor y Psiphon no
  los piden, y guardarlos sería guardar una credencial más.

**Probado en el emulador** con un SOCKS5 mínimo hecho para esto
(`pruebas/stub-proxy-socks.mjs`, con `stub-` para que la suite no lo corra):

- *Probar*: "Conectó con el servidor en 22 ms".
- Al guardar, todas las conexiones pasaron a ir por el proxy: el registro del
  proxy muestra cada `CONNECT` (`18-proxy.log`). Se mandó un mensaje por él
  (`18b-`).
- **Con el proxy caído, la app quedó "sin conexión"** (`18c-`). No vuelve sola
  a salir directo: si la red bloquea la app, salir directo es justo lo que no
  hay que hacer.
- Al quitarlo, reconectó directo. El servidor registró "@xampl3 conectado".

Un detalle del emulador, no de la app: las apps salen por su WiFi virtual,
donde `10.0.2.2` no es la PC. El proxy se alcanzó como el servidor, con
`adb reverse`.

`RedTest`: 4 pruebas.

## Novedades 0.6.3

Están en la pantalla de Novedades: los 18 puntos de las dos tandas y los
defectos que se encontraron en el camino.

## Para publicar

- **El servidor cambió** y hay que redesplegarlo antes de la app: V44
  (directorio), V45 (nota), V46 (entregas en grupos), y @todos/mencionado de la
  tanda anterior. Las migraciones se aplican solas al arrancar.
- **La app:** versionCode 16 o más (lo calcula `lanzar.sh`), versión 0.6.3.
  Room pasó de 23 a **29**, con todas las migraciones escritas.

## Números

- Unitarias: 590 → **615**, 0 fallos.
- Integración: 1672 → **1733**, 0 fallos (suites nuevas `notas.mjs` e `info.mjs`).
