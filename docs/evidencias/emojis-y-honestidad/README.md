# Módulo Z · Evidencias

Ocho capturas de los dos emuladores (1080×2424 @420 dpi, API 37), tomadas a
mano. Cuatro son de lo nuevo, dos son de un defecto que salió al mirar y su
arreglo, y dos son de lo que la app dice cuando **no pudo preguntar**.

## Lo nuevo en el panel de emojis

| | Qué muestra |
|---|---|
| `01-buscar-emoji.png` | Buscar **"corazon"**, sin tilde, trae los veinte corazones y las dos caras con corazones. Antes no había buscador: con 250 emojis, encontrar uno era recorrer cuatro grupos con el pulgar. |
| `02-recientes.png` | La pestaña **Recientes**, que ahora abre primera. ❤️ va antes que 🥰 porque se usó dos veces contra una: ordena por **veces** y desempata por **cuándo**. |
| `03-tono-de-piel.png` | Las seis opciones de tono, sobre el emoji que se mantuvo pulsado —no sobre un 👍 genérico— para ver cómo va a quedar. El amarillo lleva el círculo de seleccionado: es una opción más, no "ninguna". |

## Un defecto que sólo se ve mirando

| | Qué muestra |
|---|---|
| `04-defecto-caras-con-tono.png` | 🫡 🫢 🫣 con el tono aplicado: **la carita amarilla y un rectángulo de color suelto debajo**. Están en la fila de gestos, así que las puse en la lista de los que admiten tono. Son **caras**, y Unicode no les da modificador aunque tengan una mano dibujada encima. |
| `05-caras-arregladas.png` | Las mismas tres, limpias, después de sacarlas de la lista. Al lado, 🤝 sigue amarillo y está bien: no admite tono en esta fuente, y ahora la app lo comprueba antes de ofrecerlo. |

El código hacía exactamente lo que le pedí. Lo que estaba mal era lo que le
pedí, y **ninguna prueba de lógica podía verlo**: la lista era coherente consigo
misma. Salió de tomar una captura después de elegir un tono.

Y el comentario que había sobre esa lista decía, literalmente, "no es los que
tienen una mano" —advirtiendo del error que la lista cometía tres líneas más
abajo—. Un comentario no valida nada.

El arreglo tiene dos partes, y la segunda importa más que la primera: se sacan
las tres, y además se le **pregunta a la fuente del aparato** si sabe dibujar la
secuencia (`PaintCompat.hasGlyph`) antes de ofrecer el tono. Unicode dice qué
secuencias existen; la fuente decide cuáles puede dibujar. Una lista escrita a
mano contesta la primera y aparenta contestar las dos.

## Stickers desde el compositor

| | Qué muestra |
|---|---|
| `06-stickers-sugeridos.png` | Con ❤️ escrito y nada más, aparece la tira con el sticker etiquetado ❤️, justo encima del compositor. Tres toques pasan a ser uno. |
| `07-sticker-enviado.png` | Enviado a las 18:19, y **el ❤️ se fue del compositor**: se eligió el sticker *en vez* del emoji, no además de él. Dejarlo escrito mandaría las dos cosas. |

Esto es lo que le da sentido a la etiqueta del sticker. Hasta ahora el emoji
sólo filtraba **dentro** de la bandeja, o sea servía a quien ya había decidido
mandar un sticker. La tira lo usa en el momento anterior: cuando la persona
todavía está eligiendo *qué* mandar.

## Cuando no se pudo preguntar

| | Qué muestra |
|---|---|
| `08-no-se-pudo-comprobar.png` | La pantalla **Mi cuenta** con el servidor caído a propósito. Abajo, donde antes decía "Todavia no hay nada registrado", dice **"No se pudo comprobar"** con el motivo y un botón de reintentar. |

Para provocarlo de verdad hubo que **reiniciar el proceso de la app** además de
cerrar el túnel: `adb reverse --remove` deja de aceptar conexiones nuevas y
**no corta las establecidas**, así que la primera vez la pantalla se cargó
igual, por la conexión que OkHttp tenía viva. Vale anotarlo porque parece que
la prueba falló y lo que falló fue la manera de provocar el fallo.

Y esa misma captura muestra el defecto que quedaba: arriba dice **"No tienes
advertencias. Nada que corregir."** con el servidor caído. El `if` decía
`e == null || e.advertencias.isEmpty()` —las dos cosas en una sola rama—, y lo
encontré en esta captura, seis líneas más arriba del bloque que acababa de
corregir. Ya está partido en tres ramas.

Es la peor versión del error, por lo que este proyecto ya dice de las
advertencias: no se pueden silenciar porque una advertencia que no llega no
cumple su única función, que es dar la oportunidad de corregir antes de la
sanción. Una advertencia que la pantalla **niega** es lo mismo con un paso más.

## Cómo se tomaron

```
G:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 exec-out screencap -p > captura.png
```

Con los dos emuladores corriendo, los dos túneles abiertos
(`pruebas\conectar-emuladores.ps1`) y el APK instalado **en los dos** —una vez
se reportó un "se ve diferente" que era un APK viejo en el segundo emulador—.
Para `08` el túnel del servidor estaba cortado a propósito.
