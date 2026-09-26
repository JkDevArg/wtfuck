# La forma de onda de las notas de voz ahora es real

Antes se dibujaba una figura **inventada**, derivada del id del mensaje. Se veía
bien y no decía nada: dos notas distintas se veían distintas, pero ninguna se
parecía a lo que había dentro.

> *Captura: la onda medida. Las capturas no se publican; ver la nota de `docs/evidencias/README.md`.*

---

## Por qué estaba inventada, y por qué ya no hace falta que lo esté

El comentario original decía la verdad: dibujar las amplitudes reales exigiría
**decodificar el archivo entero en cada burbuja de la lista**, y una
conversación con veinte notas de voz haría eso veinte veces por cada scroll.

Lo que faltaba era darse cuenta de que **no hay que sacarlas del archivo**.
Quien graba ya las tiene: el micrófono las da gratis mientras graba. Sólo
había que guardarlas.

Así que viajan hechas, dentro del sobre, en **40 caracteres** — menos que el
nombre del archivo.

## Las decisiones

**Se normaliza al máximo de la propia nota.** Con niveles absolutos casi todas
se verían planas: hablar normal no satura el micrófono ni de lejos, y la
diferencia entre una sílaba y un silencio quedaría en unos pocos píxeles. Nadie
compara el volumen de dos notas mirando los dibujos; lo que la figura tiene que
decir es dónde hay voz y dónde no.

**El máximo de cada tramo, no el promedio.** Promediar borra justo lo que se
quiere ver: una sílaba corta entre dos silencios se promedia hasta desaparecer
y la figura queda lisa.

**Base 32, sin `+`, `/` ni `=`.** Para que la cadena sea legible en un volcado
y no obligue a pensar en escapes cada vez que pasa por un JSON o una URL. 32
niveles de altura son más de los que un ojo distingue en una barra de 20 dp.

**El silencio devuelve cadena vacía, no cuarenta ceros.** "No hay figura" y
"hay una figura plana" son cosas distintas, y quien dibuja tiene que poder
distinguirlas.

**Un hilo propio para muestrear, no el temporizador de la pantalla.** Si la
pantalla deja de preguntar —se va a segundo plano, se traba un fotograma— la
figura saldría con agujeros justo donde la grabación siguió.

Y de paso arregló algo que estaba mal sin que se notara: leer `maxAmplitude`
**devuelve el pico y lo pone a cero**, así que dos lectores se roban las
muestras entre sí. Antes lo leía la pantalla para mover el indicador de nivel;
ahora lo lee sólo el muestreador y la pantalla mira el resultado.

## Lo que se sigue dibujando inventado

Las notas de antes de este cambio, las que mande una versión vieja, y cualquier
cadena que no se entienda. Ahí vuelve la figura derivada del id.

Se prefiere eso a no dibujar nada porque el hueco no sería más honesto: una
barra vacía se lee como un audio roto, y el audio está perfecto — lo que falta
es su retrato.

La migración no intenta arreglarlas: la onda real de una nota vieja sólo se
podría sacar decodificándola, y ninguna migración debería abrir mil archivos de
audio.

---

## Cómo se comprobó que es real

Mirar el dibujo no alcanza: una figura aleatoria también se ve "bien". Se contó
la imagen.

**El número de barras.** La real tiene 40, la inventada 34. En la captura:

```
barras de 8 px de ancho: 32
tramo plano inicial (las mínimas, fundidas con la línea base): 81 px ÷ 13 de paso ≈ 6
TOTAL ≈ 38
```

38 con el redondeo del tramo plano — 40, no 34.

**La distribución de alturas.** Las barras miden `11, 12, 13, 14, 15, 18, 22,
39` píxeles: una cola larga de barras bajas y un pico aislado. La inventada es
`0.25 + aleatorio × 0.75`, un reparto uniforme que no puede producir veinte
barras casi iguales seguidas ni un único valor al triple del resto.

---

## Pruebas

**9 pruebas nuevas** en `OndaTest`, sobre el códec puro: el número de barras
sea cual sea la duración, la normalización, que el pico no se promedie, que el
final de la nota no se pierda, la ida y vuelta, y qué pasa con una cadena que
escribió otra persona.

**Inyección de defectos: 7 de 8 cazados, y el octavo merece explicación.**

| # | Defecto | Resultado |
|---|---|---|
| u | el silencio produce una figura plana en vez de ninguna | cazado |
| v | se deja de normalizar (las notas bajitas quedan planas) | cazado |
| w | se promedia el tramo en vez de tomar el pico | cazado |
| x | los bordes de los cubos se calculan acumulando un paso | **no lo caza nadie** |
| y | se deja de comprobar el largo al leer | cazado |
| z | un carácter desconocido se toma como cero | cazado |
| A | el extremo alto no llega a 1 | cazado |

**Sobre `x`:** la inyección cambiaba sólo el borde inicial de cada cubo, y el
último cubo seguía llegando al final de las muestras — el dibujo salía igual.
Era una mutación equivalente: no hay nada que cazar.

El defecto de verdad es acumular **los dos** bordes, y ése **sí se caza**, por
`el final de la nota no se pierde`:

```
defecto x-real puesto: los bordes se acumulan
x-real  cazado
```

Queda escrito porque una inyección que "nadie caza" invita a añadir una prueba
que no hace falta, y la conclusión correcta era la contraria.

```
=== 37 suites · 1548 pasan, 0 fallan ===   (integración)
    456 unitarias, 0 fallan
```
