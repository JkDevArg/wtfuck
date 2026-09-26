# La galería se mira deslizando, y la llamada se devuelve tocándola

Dos cosas que faltaban y que ya se podían hacer con piezas que existían.

---

## 1. El visor de la galería

Antes, tocar una miniatura **saltaba al mensaje dentro del chat**. Está bien y
sigue estando —es el botón de arriba del visor— pero no es lo que uno quiere
hacer en una galería: mirar las fotos, una detrás de otra. Con el salto había
que volver atrás, buscar la siguiente y volver a saltar.

![el visor](1-visor-de-galeria.png)
![deslizando](2-deslizando-y-descargando.png)

### Lo que se ve mientras se descarga

La miniatura, estirada. Ya está en la base y se ve **sin red**, así que la foto
aparece al instante —borrosa— y se cambia sola por la buena cuando llega. Un
recuadro vacío con una rueda girando sería más honesto sobre lo que falta y
peor para quien está mirando: la miniatura ya dice qué foto es.

### Se descarga sola, y sólo la que se está mirando

Abrir la galería no descarga nada. Deslizar hasta una foto descarga esa. Es la
diferencia entre mirar diez fotos y bajarse las trescientas que hay en la
conversación sin haberlo pedido.

Verificado en el emulador: se abrió en "1 de 7", se deslizó hasta "3 de 7" y la
tercera llegó completa, a resolución entera, sin haberla pedido antes.

## 2. Devolver la llamada

Tocar el resumen de una llamada vuelve a llamar, **con el mismo tipo que la de
antes**: quien toca una videollamada perdida quiere una videollamada, no una de
voz. Preguntarlo con un menú de dos opciones sería un paso de más en la acción
más obvia que hay ahí.

![devolver](3-devolver-la-llamada.png)

En el commit anterior esto estaba declarado como lo que faltaba, con el motivo
de no haberlo hecho a medias: *"una burbuja que parece un botón y no lo es
sería peor que una que no lo parece"*. Ahora lo es.

Verificado tocando una "Llamada perdida" de voz: arrancó una llamada de voz.

---

```
=== 37 suites · 1548 pasan, 0 fallan ===   (integración)
    456 unitarias, 0 fallan
```

Sin pruebas nuevas, y por lo mismo de siempre: las dos son de presentación y
navegación. Lo que se podía comprobar era que **hacen lo que dicen**, y eso se
comprobó usándolas.
