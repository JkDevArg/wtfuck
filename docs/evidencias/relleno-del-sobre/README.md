# El tamaño también dice cosas · módulo AR

El servidor no puede abrir un sobre — eso es todo el producto. Pero **sí puede
medirlo**, y hasta ahora la longitud del texto cifrado seguía de cerca a la
del claro: el cifrado de Signal usa bloques de 16 bytes y no esconde el
tamaño.

Eso es más de lo que parece. Un sobre de 40 bytes no es un párrafo: es "ok",
"sí", "ya voy". Uno de 4 KB en mitad de una conversación de sobres de 50 bytes
es alguien pegando algo. Y la secuencia de tamaños y tiempos dibuja la forma
de la charla sin leer una sola palabra — quién pregunta y quién contesta,
cuándo alguien duda, cuándo alguien pega una dirección.

No hace falta romper el cifrado para eso. Basta con `SELECT length(cuerpo)`.

## Medido desde el servidor

Cuatro mensajes, mandados desde un emulador con el otro apagado para que se
queden en el buzón, y consultados en Postgres:

| Mensaje | Caracteres |
|---|---|
| `si` | 2 |
| `dale pues` | 9 |
| `ok perfecto gracias` | 19 |
| `nos vemos alla en un rato` | 25 |

**Antes** — `SELECT length(cuerpo) FROM sobre_pendiente`:

```
266
266
250
250
```

Dos tamaños distintos, separados por exactamente un bloque AES. El contenido
se asoma.

**Después**:

```
362
362
362
362
```

Idénticos. Desde el servidor, los cuatro mensajes son el mismo mensaje.

## Cómo

Se rellena el JSON **antes de cifrar**, hasta el siguiente cubo. Así el
relleno queda **dentro** del cifrado: va autenticado como el resto, y nadie en
el camino puede quitarlo ni medir por debajo de él.

Los cubos crecen al doble hasta 8 KiB y de 8 en 8 KiB después. Las dos escalas
son por dónde está la información: los mensajes de texto viven en los primeros
cientos de bytes, y ahí hay que ser grueso —entre 40 y 256 bytes se pierde la
diferencia entre "ok" y una frase—. Arriba, el tamaño ya lo domina una
miniatura y no lo que alguien escribió, así que seguir duplicando regalaría
hasta 30 KiB por sobre para esconder algo que ya no dice nada.

**El costo**: unos 100 bytes más por mensaje corto. A cambio, el tamaño deja
de ser un canal.

## Lo que NO esconde

Que hubo un mensaje, cuándo, entre quiénes y en qué conversación. El relleno
tapa el tamaño, no la existencia. Para lo otro haría falta tráfico de
cobertura —sobres vacíos todo el tiempo—, que es otra decisión y mucho más
cara.

## Por qué ceros y no espacios

Se pensó en espacios, porque un parser de JSON los ignora al final. Depende de
que el parser sea tolerante, que es una propiedad de la biblioteca y puede
cambiar en una actualización sin que nadie lo note.

Un byte cero no aparece nunca crudo en JSON —dentro de una cadena viaja
escapado como `\u0000`, seis caracteres, ninguno de ellos cero— así que
recortarlos al final no puede comerse nada del contenido.

## Lo que se decidió no romper

Si el contenido no entra en ningún cubo, **se manda tal cual, sin rellenar**,
y no se lanza. Parece al revés de lo prudente:

- Lanzar rompería un envío que hoy funciona. El tope real lo pone el servidor
  —`manejarEnvio` rechaza más de 64 KiB— y un sobre de 62 KiB pasa hoy.
  Convertirlo en una excepción sería cambiar una mejora de privacidad por una
  regresión de funcionamiento.
- Y no deja un hueco: si de verdad es demasiado grande, el servidor lo rechaza
  igual, que es donde tiene que decidirse.

---

## Una prueba que pasaba por el motivo equivocado

Merece contarse porque casi se queda así.

Entre las pruebas había una llamada *"un texto que termina en caracteres raros
tampoco se rompe"*, que mandaba `"fin   "`, `"fin\n\n"` y similares, y pasaba.
Parecía fijar que el recorte sólo se come bytes cero.

No fijaba nada. Un JSON **siempre termina en `}`**, así que esos espacios
nunca quedan al final de los bytes — el recorte nunca los toca, haga lo que
haga. Se comprobó cambiando `quitar` para que se comiera también los espacios:
**ninguna prueba se enteró**.

La regla se comprueba ahora directamente sobre la función, con bytes escritos
a mano. Importa porque el día que alguien cambie el relleno de ceros a
espacios —la idea "obvia", precisamente porque un parser los ignora— ese
recorte se volvería capaz de comerse contenido, y no habría nada que lo
dijera.

> Una prueba que pasa por el motivo equivocado es peor que no tener prueba:
> ocupa el sitio de la que haría falta.

Los otros seis defectos que se inyectaron a mano —no rellenar, elegir el cubo
por debajo, volver a duplicar los cubos grandes, lanzar en vez de pasar tal
cual, subir el máximo hasta rozar el tope del servidor, copiar el array cuando
no hay nada que quitar— los cazan las pruebas que afirman esas propiedades.

## Una corrección

Durante la auditoría dije que **no había límite de tamaño** para el cuerpo
cifrado, después de buscarlo en la ruta HTTP y en `Mensajes.registrar`.

Estaba mal. El límite existe y vive en `manejarEnvio`, en el camino del
WebSocket: `cuerpo.size > 65536` → rechazado. Lo encontré al calcular hasta
dónde podían llegar los cubos, y obligó a bajar el máximo de 128 KiB a 60 KiB
— con los cubos originales, los sobres más grandes habrían rebotado contra un
límite que yo había dado por inexistente.
