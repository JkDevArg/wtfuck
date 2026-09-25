# El mapa, y quién decide pagarlo · módulo AN

Probado de punta a punta en dos emuladores, moviendo la posición con
`adb emu geo fix` y mirando las dos pantallas.

Hasta aquí esta app no le había pedido **nada a ningún servidor que no fuera
el propio**. Un mapa incrustado rompe eso: para pintar las calles hay que
pedirle las imágenes de esa zona a alguien, y pedir la imagen de un lugar le
cuenta a ese alguien que hay una persona mirando ese lugar. Con un compartido
en vivo no es un momento: son hasta 24 horas de recorrido.

Así que el mapa no se agregó y ya. Se agregó con la pregunta de **quién paga
ese costo**, que resultó ser dos preguntas distintas.

## 1 · Quien comparte elige si su posición puede llegar a un tercero

![Los dos modos](1-elegir-el-modo.png)

El selector sólo aparece cuando hay una duración elegida: sin compartido en
vivo no hay nada que encuadrar, y una opción que todavía no aplica es ruido
que hay que aprender a ignorar.

**Modo oculto** por defecto. El que no le cuenta nada a nadie es el que no
hay que elegir, y el texto dice la diferencia real en lugar de dejar que
"con mapa" parezca estrictamente mejor:

> *Se ve el rastro —por dónde vas y cuánto— sin mapa. Ningún servidor de
> mapas se entera de dónde estás.*

> *Se dibuja sobre OpenStreetMap. Para pintar el mapa hay que pedirle las
> imágenes de esa zona, así que ese servidor sabrá por dónde andás mientras
> dure.*

El segundo va en ámbar, que en esta app es el color de "esto tiene un costo",
no de "esto está mal".

Esa elección **viaja dentro de la carga** (`conMapa`), cifrada como todo lo
demás. Es lo único de esta familia que tiene que cruzar: del otro lado no hay
forma de saber si se aceptó el mapa si no se lo dicen.

## 2 · Con mapa

![La burbuja con OpenStreetMap](2-con-mapa.png)

El recorrido sobre las calles de verdad, con el marcador que ya era la
persona desde AM.5, la barra de escala y el crédito de OpenStreetMap — que no
es cortesía sino lo que pide su licencia, y aparece **sólo cuando de verdad
se usaron sus baldosas**.

No hay botones de `+` y `−`. El encuadre útil ya lo sabe la app: es el que
contiene el recorrido, y `Geo.zoomPara` elige el zoom más cerrado en el que
entra entero. Hacer que la persona lo busque a mano sería pedirle trabajo
para llegar a donde la íbamos a llevar igual.

## 3 · Quien mira elige si expone su propia IP

![La oferta, una sola vez](3-quien-recibe-decide.png)

Acá está la parte que no se ve de entrada. Quien comparte decidió que su
**posición** puede llegar a un tercero. Pero la petición la hace el teléfono
que mira: es **su** dirección IP la que queda del otro lado, junto con "está
mirando este lugar a esta hora". Eso no lo puede consentir nadie más.

Entonces la primera vez no se pide nada: se ve el rastro, y debajo la oferta
con el costo escrito. Se ofrece **una sola vez** — un aviso que volviera a
aparecer en cada burbuja enseñaría a tocarlo sin leerlo, que es lo contrario
de un permiso.

Elegir "con mapa" al compartir **también** enciende el permiso de este
aparato: es la misma decisión, tomada en la hoja donde el costo está escrito,
y volver a preguntarla en la propia burbuja sería preguntar dos veces lo
mismo.

Y se puede apagar: **Privacidad → Este aparato → Cargar mapas de
OpenStreetMap**. Va en su propia sección porque todo lo demás de esa pantalla
es de la **cuenta** —viaja, lo hace cumplir el servidor, vale en todos los
aparatos— y esto es de **este teléfono**. Mezclarlo arriba haría creer que se
sincroniza. Un permiso que sólo se puede encender no es un permiso.

## 4 · Modo oculto: el rastro sin mapa

![El rastro sin mapa](4-modo-oculto-rastro.png)

Cinco posiciones a lo largo de unos 900 m, con el marcador en la punta nueva
y el trazo detrás. **Ninguna petición a ningún tercero**, ni una.

No es "una versión pobre": es exactamente la misma vista sin el fondo. El
rastro, el marcador, el encuadre y la barra de escala son los mismos, y por
eso lo que se pierde al ocultarse es el decorado de las calles — la
información (por dónde pasó, cuánto y hacia dónde va) sigue entera. Son un
solo composable y no dos, que es lo que evita que se separen al primer
arreglo.

**La barra de escala no es adorno.** Sin ella un paseo de cinco metros y un
viaje de cinco kilómetros se dibujan **idénticos**, porque el trazo siempre se
estira para llenar la caja.

Y la línea de abajo —*"Compartido en modo oculto: sólo el rastro"*— existe
porque sin ella la lectura obvia de un dibujo sin mapa es "se rompió".

---

## El rastro no viaja

Cada teléfono arma el suyo con lo que le fue llegando. No se manda.

Serían hasta 60 puntos en **cada** actualización, una cada medio minuto
durante hasta 24 horas, repitiendo toda la historia dentro de cada sobre para
redibujar algo que del otro lado ya está.

Tiene una consecuencia honesta: quien entra tarde, o a quien le mataron la app
un rato, ve un rastro más corto. Es lo correcto — el rastro dice *"esto vi
moverse"*, y afirmar más sería inventar.

### Un defecto que apareció leyendo, no probando

`recibidaEn` estaba documentado desde AM como *"no viaja: sale siempre en 0"*.
Y **viajaba**. Las actualizaciones no se arman de cero: se arman copiando la
carga guardada, que ya tiene ese campo escrito. Nadie lo notó porque del otro
lado se pisa al guardar, así que el efecto era invisible.

Con el rastro habría dejado de ser invisible: de un `Long` de sobra a 60
puntos por sobre. La regla se mudó al contrato, `Carga.UbicacionEnVivo.paraLaRed()`,
al lado de los campos que recorta, y hay una prueba que la fija.

> Un invariante que se cumple porque nadie lo rompió todavía no es un
> invariante.

### Y uno latente en el servicio

`onStartCommand` escribía la conversación, el id y la fecha **y recién después**
miraba si servían. Un arranque sin extras borraba el estado de un compartido
que estaba andando bien y lo dejaba sin seguimiento, con la fila todavía
"viva" en la base: del otro lado, un punto congelado presentado como si fuera
de ahora. Es el único modo en que esta función puede mentir de verdad. Ahora
se valida primero, y un arranque inútil no se lleva puesto al que funciona.

---

## Las cuentas se prueban en la JVM, no en el emulador

`Geo` es todo funciones puras: proyección, distancias, el recorte del rastro,
el encuadre y la escala. La parte que dibuja no calcula nada.

No es estético. La proyección de Mercator tiene un error clásico —tratar los
grados de latitud como una distancia constante— que **en Lima se nota poco y
en Oslo parte el mapa a la mitad**. Un teléfono en un escritorio de Lima nunca
lo iba a mostrar; una prueba de una línea sí:

```
assertTrue(a60 > a30 * 2.2)   // 60 grados se estira mas del doble que 30
```

23 pruebas nuevas, y los 10 defectos que se les metieron a mano —quitar el
recorte de los polos, linealizar la proyección, sacar el módulo de la vuelta
al mundo, dejar de recortar el rastro, mandar la estela por la red— los cazan
los 10, cada uno por la prueba que afirma esa propiedad.

## El token no puede llegar a OpenStreetMap

El `ImageLoader` de la app agrega `Authorization` sólo a nuestro host: la
regla estaba desde el módulo D y hasta hoy era teórica, porque no había ningún
otro host. Ahora es de carga.

Las baldosas igual **no** usan ese cargador. Usan uno propio, que no tiene el
interceptor: no es que no mande el token, es que **no conoce el token**. La
diferencia importa cuando alguien toque el otro archivo dentro de un año.
También tiene su propia caché en disco, para que un rato de mapa no desaloje
los avatares del chat.

Y hay una prueba que mira la URL de una baldosa y exige que no lleve nada de
la cuenta: si algún día alguien mete una clave de API o un identificador en la
plantilla, ese identificador ataría cada petición a esta persona.

## Lo que el servidor sigue sabiendo

**Nada.** Como en AM: ni tabla, ni columna, ni vencimiento, ni la clase del
contenido. El modo tampoco — `conMapa` es un campo dentro de la carga
cifrada. El único cambio del módulo en el servidor es **ninguno**.

## La letra chica de OpenStreetMap

El servidor de baldosas es configuración (`MAPA_BALDOSAS`), no una constante
enterrada en la pantalla: quien despliegue esto tiene que poder cambiarlo por
el suyo sin tocar código, y vacío apaga el mapa entero.

El default es OSM, que es gratis y público. Su política de uso exige un
`User-Agent` que identifique a la app —está puesto, con la versión— y **no
está pensada para tráfico pesado**: un despliegue de verdad va con baldosas
propias o pagas. Está dicho acá y en el `build.gradle.kts` para que no se
descubra el día que corten.
