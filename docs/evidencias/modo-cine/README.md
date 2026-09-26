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
