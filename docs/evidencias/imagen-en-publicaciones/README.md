# Módulo AC · Evidencias

Un canal sólo podía publicar **texto**. Para una página de anuncios ese es el
hueco grande: un aviso con imagen es lo normal, no la excepción.

| | Qué muestra |
|---|---|
| `01-compositor-con-imagen.png` | El canal nuevo, con el botón de imagen a la izquierda del campo. Antes ahí no había nada. |
| `02-previa-antes-de-publicar.png` | La imagen elegida, con su miniatura y la X para quitarla. El botón de enviar está **activo sin texto**: una foto sola es una publicación. |
| `04-imagen-texto-y-reacciones.png` | Publicado: la imagen, el texto debajo y la fila de reacciones. |
| `05-visor.png` | A pantalla completa, sin recortar, sobre negro. |

## El primer intento tapaba el texto

| | Qué muestra |
|---|---|
| `03-primer-intento-tapaba-el-texto.png` | La misma publicación con el tope de proporción en `0.6`, copiado del chat: la foto vertical se come la pantalla y **el texto queda fuera**. |

La tarjeta del canal ocupa el **ancho completo**, así que una vertical a `0.6`
mide 1.66 veces el ancho de la pantalla. En el chat la burbuja es más angosta y
el mismo número da una altura razonable; copiarlo tal cual fue el error.

Ahora el suelo es `0.8`. Recorta una vertical, sí — pero una publicación donde
no se ve el texto que la acompaña no es una publicación, es una foto.

## Por qué la imagen va sin cifrar, y por qué aquí eso es una ventaja

El adjunto normal se cifra en el teléfono y **la clave viaja dentro del
sobre**. Un canal público no reparte sobres —es lo que le permite escalar— así
que no hay dónde meterla: quien se suscriba mañana tendría el archivo y no la
llave.

Va en claro, bajo la misma excepción declarada que el cuerpo de la publicación,
con los mismos tres motivos de `V10__canales.sql`.

Y eso habilita algo que el brief pedía y el cifrado hacía imposible: **validar
el tipo de archivo en el servidor**. El documento de cobertura dice que las dos
cosas eran incompatibles y que se resolvió partiendo por clase —fotos de perfil
validadas, adjuntos cifrados no—. **Esta es la tercera clase** y cae del lado
validable, porque su contenido ya es público por definición.

`Adjuntos.confirmar` lee los **doce primeros bytes** del almacén y comprueba la
firma real. La suite sube un ejecutable con nombre `.png` y
`Content-Type: image/png`, y lo rechaza:

```
PASA  confirmar RECHAZA un archivo que no es una imagen
PASA  y el adjunto rechazado deja de existir
```

## Y esa segunda línea encontró un defecto que ya estaba

La primera versión devolvía **200** ahí: el adjunto rechazado seguía
existiendo.

El rechazo borraba la fila y **lanzaba dentro de la misma transacción**, así
que `Db.tx` hacía rollback y el `DELETE` se deshacía. Quedaba una fila
apuntando a un objeto que **sí** se había borrado del almacén —esa parte no es
transaccional— o sea un adjunto que existe para la base, no existe en el disco,
y del que `leer` devolvía tranquilamente una URL de descarga.

**Pasaba con el tope de tamaño desde el módulo D**, con exactamente el mismo
código. Afirmar el 413 no lo veía, porque el 413 llegaba igual; hizo falta
preguntar si la fila había desaparecido de verdad.

Es la misma familia que la lección del `SAVEPOINT` que ya está anotada en este
proyecto: **una sentencia y una excepción en la misma transacción no son dos
cosas independientes.** Ahora la transacción decide y la limpieza pasa después,
cada cosa en su sitio.

## Y otro contador que contaba metadatos

`cuenta publicaciones` empezó a fallar con `estadistica=6 muro=5`: la
estadística contaba `mensaje_meta WHERE responde_a IS NULL`, así que un mensaje
registrado cuyo cuerpo nunca se guardó —porque la subida falló, o porque el
cliente se cortó en el medio— subía el número sin que hubiera nada en el muro.

Es **el mismo arreglo que se le hizo al contador de comentarios en AA**, en la
consulta de al lado, y no se hizo entonces. Otra vez: arreglar una instancia de
un defecto no es arreglar el defecto.

## Lo que no se puede colgar de una publicación

```
PASA  no se puede publicar la imagen que subio OTRA persona
PASA  una publicacion sin texto y sin imagen se rechaza
PASA  pero con imagen y sin texto si se publica
PASA  un canal privado rechaza guardar contenido, con imagen o sin ella
PASA  quien NO esta suscrito obtiene la URL de la imagen
```

La última es la misma regla que ya usa el muro: un canal público se lee sin
estar suscrito. Si la imagen exigiera pertenencia, quien todavía no sigue el
canal vería las publicaciones con un hueco donde va la foto — o sea justo quien
está decidiendo si seguirlo.

## Cómo se tomaron

```
G:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 exec-out screencap -p > captura.png
```

Con los dos emuladores corriendo, los dos túneles abiertos y el APK en los dos.
El canal `@avisos_centusec` se creó desde la app para tener uno donde `@joaquin`
pueda publicar; queda pendiente de aprobación, que es lo correcto.
