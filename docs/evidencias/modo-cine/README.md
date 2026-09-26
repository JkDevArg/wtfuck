# Modo cine · módulo AV

Compartir la pantalla **con su sonido** dentro de una llamada, para mirar algo
entre dos. Funciona igual en una llamada de audio y en una de vídeo.

## Lo primero, porque cambia si esto sirve o no

**Con HBO, Netflix o Disney+ no va a funcionar.** Esas apps marcan su ventana
con `FLAG_SECURE` y su audio como no capturable: Android entrega **negro** y
silencio, a propósito. Es el mecanismo del DRM funcionando, y saltarlo sería
exactamente lo que está diseñado para impedir.

Lo que sí: un vídeo propio, un navegador sin DRM, YouTube, un juego, una
presentación, fotos, cualquier app que no se haya excluido.

## 1 · Lo pregunta el sistema, no la app

![El diálogo del sistema](1-lo-pregunta-el-sistema.png)

El diálogo es de Android y dice lo que hay que decir:

> *When you're sharing an app, anything shown or played in that app is visible
> to wtfuck. So be careful with things like passwords, payment details,
> messages, photos, and audio and video.*

Ahí se elige **qué** mostrar —"Share one app" abre una lista de aplicaciones, o
la pantalla entera— y esta app no ve esa elección, no puede preseleccionar nada
y no puede saltársela. Es el único punto donde se autoriza leer una pantalla, y
tiene que seguir siendo del sistema.

## 2 · Desde una llamada de audio

![Compartido en una llamada de audio](2-desde-una-llamada-de-audio.png)

El Chrome de quien presenta, en el teléfono del otro. Con la etiqueta
*"@joaquin está presentando"*: una pantalla ajena que aparece de golpe sin
decir de quién es se lee como un fallo de la app.

Que funcione **sin** que la llamada sea de vídeo no es casualidad: la pista de
vídeo existe desde que empieza la llamada, aunque no lleve nada. Añadirla
después obligaría a renegociar, y esta app no renegocia —`onRenegotiationNeeded`
está vacío a propósito, porque renegociar en malla trae el mismo problema de
choque que se resolvió en `Malla` para la oferta inicial, multiplicado por cada
cambio—. Una `m=` de más en el SDP cuesta unas líneas y ningún medio.

## 3 · Desde una videollamada

![Compartido en una videollamada](3-desde-una-videollamada.png)

La cámara se para y la pantalla ocupa su lugar en la **misma pista**: cambia
quién da los fotogramas, no lo que está negociado. Del otro lado la imagen
simplemente cambia.

Se dibuja **entera**, con bandas si hace falta, y no recortada como una cara.
Una cara aguanta el recorte —está en el medio—; una pantalla no, porque lo que
se recorta son los bordes, que es donde están los controles, la barra de
progreso y los subtítulos.

## 4 · Mientras se mira, la interfaz se aparta

| Sola, a los 3,5 s | Al tocar |
|---|---|
| ![Sin controles](4-sin-controles.png) | ![Al tocar vuelven](5-al-tocar-vuelven.png) |

La cabecera y los cuatro botones se comían justo el centro de lo que se estaba
mirando. Ahora se apartan solos y vuelven al tocar en cualquier sitio, como en
cualquier reproductor.

**Sólo cuando hay algo que mirar.** En una llamada normal los controles se
quedan puestos: no tapan nada, y esconderlos obligaría a tocar la pantalla para
poder colgar.

Y sólo a quien **mira**, no a quien presenta: quien presenta está en otra app, y
si vuelve a la llamada necesita el botón de dejar de presentar a mano.

Tres detalles que se decidieron:

- **3,5 segundos y no menos.** Da tiempo a leer quién empezó a presentar antes
  de que el aviso se vaya.
- **Se va todo, también el botón de minimizar y la ventanita propia.** Dejar uno
  solo en una esquina sería el único resto de interfaz encima de la película,
  que es peor que esconder todo o no esconder nada.
- **Vuelven solos cuando la presentación termina.** Si no, quedaría una llamada
  sin controles y sin nada que explique por qué.

El toque se escucha en el contenedor de fuera y no en una capa encima. En
Compose el evento baja primero a los hijos: un botón lo consume y ahí no llega,
y un toque en el hueco entre botones no lo consume nadie y sí llega. Una capa
propia habría tenido que elegir entre tapar los botones o no recibir nada.

## El sonido

```
AudioDeCine: Capturando audio a 8000 Hz, 1 canal(es)
```

El micrófono no servía: la cancelación de eco de una llamada existe para
**borrar** lo que sale por el altavoz, así que lo que mejor funciona en una
llamada normal es lo que destruye este caso.

Se usa `AudioPlaybackCapture`, autorizado por la **misma** proyección que la
pantalla —una sola confirmación, no dos— y se mezcla con la voz en
`setAudioBufferCallback`, que es el único sitio donde se puede tocar el búfer
del micrófono antes de que WebRTC lo procese. Aguas abajo todo sigue igual: un
flujo, una pista, ninguna renegociación.

La media va al 70 %. Una película sale mucho más fuerte que alguien hablando, y
sumadas a volumen pleno la voz queda debajo y además satura. Se está viendo algo
*con* alguien, no en vez de alguien.

### 8000 Hz, y por qué ahora son 48000

La primera prueba dejó esto en el log:

```
AudioDeCine: Capturando audio a 8000 Hz, 1 canal(es)
```

Ocho kilohercios es calidad de teléfono. Para una voz alcanza —para eso lo
eligió el sistema— pero por ahí va la banda sonora de lo que se está mirando, y
una película a 8 kHz suena a lata.

Ahora el módulo de audio se fija en **48 kHz**, que además es lo que usa Opus de
forma nativa y evita un remuestreo. Cuesta algo de CPU y de datos en una llamada
normal, y vale: es la diferencia entre "se oye" y "se oye bien".

También se apaga el supresor de ruido **por hardware** —no el de eco, que hace
falta siempre—. El de ruido está afinado para dejar pasar una voz y borrar lo
demás, y "lo demás" incluye la música.

### El vídeo, cuando el enlace aprieta

WebRTC reparte pensando en una cara: unos 2 Mbit/s de techo y, si falta ancho de
banda, prefiere bajar los fotogramas antes que la nitidez. Para una
videollamada es correcto —una cara a 10 fps se entiende— y para una película es
exactamente al revés: borrosa y fluida se mira, nítida y a tirones no.

Mientras se presenta, el techo sube a 3 Mbit/s y la preferencia pasa a
`MAINTAIN_FRAMERATE`. Al dejar de presentar vuelve a 1,2 Mbit/s y `BALANCED`:
una cara a 640x480 no mejora por encima de 1 Mbit/s, y lo único que cambiaría es
el consumo de datos de quien llama.

En malla esto se multiplica por participante, que es el motivo de que no sean
números más altos.

---

## Un defecto que estaba desde antes, y que había que arreglar para llegar aquí

Había **un motor WebRTC por dispositivo** —tres personas son tres motores— y
cada uno abría su propia cámara. Android no da dos sesiones sobre la misma: la
primera la toma y las demás reciben `ERROR_CAMERA_IN_USE`.

O sea que en una videollamada de tres, **sólo una persona veía tu cámara**. Las
demás recibían audio y un recuadro vacío, sin ningún error visible: el
capturador falla en silencio y la llamada sigue.

No lo vio ninguna prueba porque las evidencias de llamadas de grupo son de
llamadas de **audio**; la rejilla de vídeos se había probado con dos.

`MediaProjection` tiene la misma limitación, y peor: pide permiso una vez por
captura. Con un capturador por motor, presentar a tres personas habría pedido
tres confirmaciones y funcionado para una. Compartir la captura arregla las dos
cosas, y se puede porque todas las conexiones salen de la misma
`PeerConnectionFactory`.

## Dos cosas que costaron encontrar

**Los lados tienen que ser múltiplos de 16.** Con la forma real de la pantalla
salía `570x1278`, y del otro lado no se veía nada: la captura corría, la
pantalla virtual estaba viva, y no llegaba una imagen. Los codificadores por
hardware trabajan en macrobloques de 16 y muchos rechazan en silencio lo que no
encaja. Se pierde medio por ciento de imagen y se gana que funcione.

**Y un contador que engañaba.** Durante un buen rato el diagnóstico fue
`EglRenderer: Frames received: 0`, leído como "no llega nada". No era eso: una
pantalla **estática** no genera fotogramas nuevos, y el renderizador sigue
mostrando el último. El número decía la verdad y significaba otra cosa.

Se resolvió mirando la pantalla en vez del contador. Que es lo que había que
haber hecho una hora antes.

> Una métrica que mide lo que dice medir puede seguir contestando otra
> pregunta.

## Lo que no se aisló

El cambio a un `SurfaceTextureHelper` **nuevo por capturador** —en vez de
reutilizar uno— entró durante el diagnóstico, antes de encontrar lo de los
múltiplos de 16. No se comprobó si por sí solo hacía falta. Se deja porque
reutilizar el ayudante de un capturador ya liberado es frágil de todos modos,
pero no se afirma que fuera la causa: la causa demostrada es la resolución.
