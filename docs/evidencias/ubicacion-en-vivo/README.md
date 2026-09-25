# Ubicación en tiempo real · módulo AM

Probado de punta a punta en dos emuladores, moviendo la posición con
`adb emu geo fix` y mirando las dos pantallas.

## 1 · Elegir cuánto

![Las seis duraciones](1-elegir-cuanto.png)

15 min, 30 min, 1 h, 8 h, 12 h y 24 h. Una lista cerrada y no un campo libre:
"cuánto es demasiado" no es una pregunta de interfaz. Compartir dónde estás es
lo más sensible que hace esta app, y el tope vive en el contrato donde se puede
discutir.

**24 h es el techo a propósito.** Más que eso deja de ser "comparte mientras
llego" y pasa a ser seguimiento, que es otra cosa.

Va debajo de la nota y no arriba del todo: el caso común sigue siendo mandar
dónde estás **una vez**, y poner primero la opción rara obligaría a saltársela
siempre. Y es **un solo botón**, que cambia de texto según lo elegido: con dos
—"enviar" y "compartir en vivo"— la duración de arriba no querría decir nada
hasta tocar el segundo.

## 2 · Quien comparte

![La burbuja de quien comparte](2-quien-comparte.png)

`En tiempo real · quedan 15 min`, la posición, el margen, **cuándo se
actualizó**, y el botón de cortar.

"Actualizado hace un momento" no es decoración. Quien está quieto no genera
posiciones nuevas, así que una posición de hace veinte minutos puede ser
perfectamente correcta — y sin esa línea no habría forma de distinguir "está
parado" de "dejó de funcionar".

Mientras dura hay una notificación permanente, `ongoing`, que no se puede
descartar de un manotazo y lleva su propio botón de cortar. **Es el contrato**:
la app está leyendo dónde estás y eso no puede pasar en silencio.

## 3 · Quien recibe

![La burbuja de quien recibe](3-quien-recibe.png)

Lo mismo, **sin** el botón de cortar: sólo lo puede hacer quien comparte, y un
botón que no hace nada es peor que no tenerlo.

## 4 · Y se mueve sola

![La posición se actualiza](4-se-mueve-sola.png)

Se movió el emulador con `adb emu geo fix` y la burbuja pasó de
`-12.046400, -77.042798` a `-12.025000, -77.025000`, con el contador bajando de
15 a 14 min. La burbuja **no salta al final del chat** en cada actualización:
cada posición pisa la anterior en la misma fila.

Una posición cada medio minuto durante ocho horas serían mil burbujas para
decir siempre lo mismo, y el chat dejaría de ser un chat.

## 5 · La cara es el marcador

![El avatar como marcador](5-la-cara-es-el-marcador.png)

El punto genérico obligaba a leer el nombre para saber de quién era la
posición. Ahora el marcador **es la persona**, con una antena pequeña encima
que la distingue de una foto de perfil cualquiera — en cian mientras está en
vivo, gris cuando terminó.

Sin foto quedan las iniciales con su color derivado del nombre, que también
identifican: el color es siempre el mismo para el mismo nombre. Y eso pasa más
de lo que parece, porque la URL de un avatar necesita su `version` y este
teléfono sólo la conoce en dos casos — la propia y la del otro lado de una
directa. En un grupo haría falta una consulta de red **por burbuja**, y eso no
vale lo que cuesta para decorar un icono.

---

## Lo que el servidor sabe de todo esto

**Nada.** Y no es una forma de hablar:

- ni una tabla de compartidos;
- ni una columna con una coordenada, en ninguna tabla;
- ni siquiera la clase del contenido, que se valida en el camino y **se tira**;
- ni el vencimiento, que viaja cifrado dentro de la carga.

Lo único que cambió en el servidor fue agregar dos nombres a la lista de clases
válidas. Todo lo demás son sobres opacos por el mismo camino que un mensaje.

Es exactamente lo que el buzón tonto compra: **la función más sensible de la
app es la que menos le pide al servidor**. Hay cuatro pruebas de integración
que lo fijan, y son negativas a propósito — miran el catálogo de la base y
exigen que no haya nada.

## Quién hace cumplir el vencimiento

Los dos teléfonos, cada uno por su cuenta, porque el servidor no puede.

Eso importa en un caso concreto: si el teléfono que comparte **se queda sin
batería**, nadie manda el aviso de final. Sin la fecha dentro de la carga, la
otra pantalla se quedaría mostrando una posición de hace horas como si fuera de
ahora — que es la única forma en que esta función puede hacer daño de verdad.
Hay una prueba unitaria que fija justo eso.

---

## Tres defectos que sólo aparecieron probándolo

**La burbuja no llegaba al otro lado.** La rama que aplica las actualizaciones
se tragaba también el mensaje que **abre** el compartido, así que del otro lado
no se creaba nunca. Se distinguen sin inventar una bandera: el sobre que abre
lleva su propio id como `mensajeId` de la carga, y los de las actualizaciones
apuntan al primero.

**Se podían tener varios compartidos vivos a la vez.** Llegó a haber tres
burbujas. El servicio sólo sigue uno, así que los anteriores quedaban huérfanos:
nadie volvía a mandar su posición y nadie avisaba que habían terminado, y del
otro lado se veían "en vivo" con un punto congelado hasta que venciera su plazo
— hasta 24 horas. Ahora empezar uno cierra el anterior de verdad: *"estoy
compartiendo mi ubicación" es un estado, no una lista*, y la notificación
también es una sola.

**Un compartido a la vez, pero sólo mientras viviera el servicio.** La primera
versión de esa regla la hacía cumplir el servicio comparando con lo que
recordaba; si mataban la app, arrancaba en blanco y el compartido viejo quedaba
huérfano igual. Ahora la hace cumplir el repositorio antes de crear el nuevo:
**la verdad de que hay un compartido vivo está en la base, no en la memoria de
un proceso que pueden matar.**

Y por lo mismo, al arrancar la app se **reanuda** el que siguiera vivo. En las
llamadas, `recuperar()` cierra lo que encuentra —una sesión WebRTC murió con el
proceso y no se retoma—; aquí es al revés: quien pidió ocho horas no pidió
"ocho horas o hasta que Android mate la app", y no se perdió nada, porque la
posición se vuelve a leer del GPS y la fecha sigue guardada.

**Cortar desde la burbuja dejaba el servicio corriendo.** Llamaba al
repositorio —marcaba terminado, avisaba al otro lado— y el servicio seguía con
su notificación puesta y el GPS encendido. La barra decía "Compartiendo tu
ubicación" sin compartir nada. La notificación es el contrato de este servicio;
dejarla mintiendo lo rompe entero.
