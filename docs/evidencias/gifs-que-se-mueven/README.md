# Módulo AB · Evidencias

*"¿Por qué no se mueven los stickers o GIFs?"*

Son dos respuestas distintas: **el GIF estaba roto, el sticker no.**

## Cómo se comprueba que algo se mueve

Una captura es un fotograma, así que no prueba nada sobre movimiento. Todas
estas evidencias son **tres capturas de la misma región separadas por unos
cientos de milisegundos**, puestas una al lado de la otra. Si los tres cuadros
difieren, hay animación:

```
adb -s emulator-5554 exec-out screencap -p > mov1.png   (×3, con pausa)
ImageChops.difference(a, b).getbbox()
```

Vale anotarlo porque es el único modo de verificarlo desde una interfaz que
sólo devuelve imágenes fijas.

## El GIF: estaba roto, y era un defecto propio

| | Qué muestra |
|---|---|
| `01-gif-en-la-burbuja.png` | Tres fotogramas distintos de un GIF recibido en el chat, después del arreglo. |

`VistaImagen` dibujaba con `BitmapFactory.decodeFile` → `Image`. **Un `Bitmap`
es un fotograma.** Un GIF en el chat se veía congelado en el primero, que es
exactamente lo que un GIF no es. `VisorImagen` —abrirlo a pantalla completa—
tenía el mismo código y el mismo defecto, y ahí es peor: la persona entró a
mirarlo.

Lo que **no** estaba mal era el envío. `enviarAdjunto` ya tenía una guarda
explícita para no recomprimir un GIF, justamente para no aplanarlo:

```kotlin
if (clase == ClaseAdjunto.IMAGEN && original.mime != "image/gif") { ... }
```

Así que los fotogramas llegaban enteros al teléfono y se tiraban al dibujar.

Y es el **mismo defecto que se arregló para los stickers en Y.2.3**: ahí se
cambió `BitmapFactory` por Coil con una nota explicando por qué. Se arregló el
sitio donde se había visto y se dejó igual el otro sitio que tenía el mismo
código. Arreglar una instancia de un defecto no es arreglar el defecto.

De paso se corrige algo que el comentario anterior admitía a medias:
decodificar a resolución completa **dentro de la composición**, aunque fuera
una sola vez, bloquea el hilo de interfaz. Coil decodifica fuera y cachea.

> La miniatura sigue **sin** pasar por Coil, y es a propósito: esos bytes vienen
> de un sobre ajeno y pasan por `Media.miniaturaAjena`. Ver `MiniaturaSegura.kt`.
> Que la miniatura sea un fotograma está bien: es un anticipo, no el archivo.

## El sticker: no estaba roto

| | Qué muestra |
|---|---|
| `02-previa-del-sticker-animado.png` | La hoja de crear sticker con un GIF animado: *"Tiene movimiento, así que se agrega tal cual"*. |
| `03-bandeja-con-el-animado.png` | En la bandeja, con el triángulo que marca que tiene movimiento. |
| `04-sticker-animado-enviado.png` | Mandado al chat, tres fotogramas distintos. |

`VistaSticker` ya usaba Coil desde Y.2.3. Los dos stickers que había en el
emulador eran **recortes de fotos**, o sea estáticos por construcción: no había
nada animado que mirar.

El camino completo, verificado por primera vez con un archivo animado de
verdad: se detecta por la cabecera, se ofrece sin editor, y se copia **byte por
byte** —279171 bytes de entrada, 279171 de salida—, que es lo que garantiza que
no se aplana.

## Dos trampas del entorno que costaron la mitad del rato

Las dos son de la capa de Windows, no del proyecto, y las dos producen datos
corruptos sin decir nada.

**1. `adb shell cat` corrompe binarios.** Sacar el GIF con
`adb shell "run-as ... cat archivo.gif" > local.gif` mete retornos de carro:
279171 bytes se convirtieron en 280387. El archivo resultante hace que hasta el
MediaProvider de Android falle:

```
D skia    : decodeFrame: #lzw: bad code
W MediaProvider: ImageDecoder$DecodeException: Input contained an error.
```

Y en la app se veía como una vista previa en blanco, o sea **igual que un
defecto nuestro**. Lo era del método de extracción. Con `adb exec-out` sale
intacto.

**2. Git Bash reescribe las rutas del dispositivo.** `adb push origen
/sdcard/Pictures/x.gif` respondía *"1 file pushed"* y no dejaba nada, porque
MSYS convertía el destino a `C:/Program Files/Git/sdcard/...`. Se ve en el
error cuando el directorio no existe:

```
adb: error: failed to copy '...' to 'C:/Program Files/Git/data/local/tmp/t.txt'
```

Con `MSYS_NO_PATHCONV=1` y la ruta de origen en formato Windows, funciona.

## Cómo se tomaron

```
G:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 exec-out screencap -p > captura.png
```

Con los dos emuladores corriendo, los dos túneles abiertos y el APK instalado
en los dos. El GIF animado de prueba quedó en
`/sdcard/Pictures/animado-ok.gif` del emulador, para poder repetirlo.
