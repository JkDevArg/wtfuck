# Llamadas y foto de perfil — cinco defectos que sólo se ven usando la app

Todos salieron de una videollamada real entre un teléfono y un emulador. Ni
las pruebas ni la revisión del código los habrían encontrado: ninguno es una
función que devuelva mal, son cosas que sólo existen cuando hay una pantalla
delante.

---

## 1. El cartel de "Toca para contestar" se quedaba toda la llamada

La notificación de llamada entrante es `ongoing`: no se va sola. Se borraba
cuando el estado pasaba a `null` — o sea **al colgar**.

Contestar no hace eso: la llamada pasa de `SONANDO` a `EN_CURSO` y el estado
sigue existiendo. Así que el cartel se quedaba encima durante toda la
conversación, con sus botones de Contestar y Rechazar puestos.

Ahora se borra al **dejar de sonar**:

```kotlin
val sonando = e != null && e.fase == Fase.SONANDO && !e.saliente
if (sonando) ultima = e!!.conversacionId
else ultima?.let { Notificaciones.quitarLlamada(this, it); ultima = null }
```

![sin cartel](1-sin-cartel-al-contestar.png)

## 2. "No se ve mi cámara" — y sí se veía

Creí que el cartel de arriba la tapaba, porque la ventanita vive justo debajo.
Me equivoqué, y la forma de saberlo fue mirar la jerarquía de vistas en vez de
razonar sobre la captura:

```
ViewFactoryHolder bounds="[755,147][1039,588]"
org.webrtc.SurfaceViewRenderer{db4886a ... 0,0-284,441}   ← la propia
org.webrtc.SurfaceViewRenderer{6e2f140 ... 0,0-1080,2424} ← la remota
```

284×441 px, arriba a la derecha: **estaba exactamente donde debía**. Lo que
pasa es que la cámara falsa del emulador da una imagen casi blanca, y el vídeo
remoto también es blanco en esa zona. El muestreo de píxeles lo confirma:
ningún borde, todo `(255,254,255)`.

En el teléfono de verdad la ventanita se ve sin problema.

No hay arreglo porque no hay defecto. Queda escrito para que la próxima vez
que alguien diga "la cámara no anda en el emulador", la respuesta no cueste
una tarde.

## 3. El nombre tapaba media videollamada

Había un recuadro al 82 % de opacidad detrás del nombre y el cronómetro, con
el nombre a 26 sp. Se puso así por una razón buena —sobre un vídeo claro el
texto desaparecía— y resolvía el contraste tapando.

Sobre una videollamada **la imagen es el contenido**, y el nombre de quien
llama no merece un bloque opaco encima durante toda la conversación.

Ahora el contraste lo pone una **sombra en el texto**, que funciona contra
cualquier fondo y ocupa cero. Es lo que hacen los subtítulos de vídeo desde
siempre, por lo mismo. El nombre además baja a 17 sp: importa mientras suena
—hay que decidir si contestar— y deja de importar en cuanto se contesta.

De paso se fue la tercera línea. En una directa decía "con joaquin" debajo de
un título que ya decía "@joaquin": dos veces lo mismo, ocupando una línea sobre
el vídeo. La lista de participantes existe para los grupos, donde sí cambia
algo.

## 4. La ventanita propia no se podía mover

Estaba fija arriba a la derecha, que es justo donde tapa cuando la otra persona
está sentada a un lado o cuando lo que muestra es una pantalla. No hay una
esquina correcta para todos los casos, así que ahora la elige quien mira.

Verificado arrastrando y leyendo los límites después:

```
antes:    bounds="[755,147][1039,588]"
después:  bounds="[171,1059][455,1500]"    ← mismo tamaño, otra esquina
```

El arrastre **consume** el evento: sin eso también llegaba a la capa de abajo,
que es la que muestra y esconde los controles, y mover la ventanita apagaba los
botones.

## 5. La foto de perfil se rechazaba en vez de comprimirse

```kotlin
if (bytes.size > limite) {
    error("La imagen pesa ${bytes.size / 1024} KB y el limite es ${limite / 1024} KB.")
}
```

Eso le pasa el problema a quien no tiene cómo resolverlo: nadie tiene a mano
una herramienta para achicar una foto, y el teléfono —que sí la tiene— se
estaba negando a usarla.

Y no era un caso raro: **una foto de la cámara de cualquier teléfono de hoy
pasa los 3 MB**, así que el camino normal —elegir la foto que uno se acaba de
sacar— fallaba siempre.

Ahora `Media.paraSubir` baja primero la **resolución** y después la calidad. En
ese orden y no al revés: una foto de 4000 px a calidad 20 se ve peor, y pesa
más, que la misma a 1280 px con calidad 85 — el tamaño de un JPEG lo manda la
cantidad de píxeles mucho antes que la calidad. `inSampleSize` decodifica ya
reducido, así que la foto entera nunca llega a memoria.

### El límite del servidor NO se tocó

Comprimir aquí es una comodidad para quien usa la app. Un servidor que acepta
subidas sin tope es otra cosa, y este lado siempre puede estar modificado. El
tope de 512 KB con su 413 sigue exactamente donde estaba.

**Y es lo que sirve de prueba**: se subió una foto de **4032×3024 y 10.4 MB**,
generada con ruido para que el JPEG no pudiera hacer trampa — 20 veces el
límite viejo. El servidor la aceptó, lo que significa que lo que le llegó pesaba
512 KB o menos. Si el cliente hubiera mandado los 10.4 MB, habría respondido
413 y la app habría mostrado el error.

![foto de 10 MB](2-foto-de-10MB-subida.png)

## 6. La foto de perfil ahora se abre

Tocarla la abre a pantalla completa, con pellizco para acercar y doble toque
para volver a 1x. En el perfil propio y en el de otra persona.

Sirve justamente para ver lo que el círculo de 92 dp no muestra: la foto se
recorta a cuadrado para el avatar, y abrirla la enseña entera.

Doble toque vuelve al inicio porque con sólo pellizcar no hay forma de volver
exactamente a 1x, y una foto que quedó torcida y no se deja enderezar se siente
rota.

![visor](3-visor-de-foto.png)

---

## Pruebas

```
=== 37 suites · 1548 pasan, 0 fallan ===   (integración)
    431 unitarias, 0 fallan                (app 344 + servidor 87)
```

Ninguno de estos seis cambios añade pruebas automáticas, y conviene decir por
qué en vez de dejarlo en silencio: son de presentación —dónde se dibuja algo,
de qué tamaño, con qué opacidad— y una prueba que afirme "el nombre mide 17 sp"
no comprueba que se lea: comprueba que alguien escribió 17. La verificación
real de los seis fue usar la app y medir lo que quedó en pantalla: jerarquía de
vistas, límites después de arrastrar, píxeles, y el 413 del servidor.

Lo que sí tiene lógica comprobable —`Media.paraSubir`— quedó verificado de
extremo a extremo por el límite del servidor, que es una comprobación más dura
que cualquier prueba que yo escribiera sobre el mismo código.
