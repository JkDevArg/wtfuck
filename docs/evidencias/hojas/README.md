# Evidencias · Las hojas que se cortaban

| # | Captura | Qué muestra |
|---|---|---|
| 01 | `01-antes-cortada.png` | **El defecto.** La hoja de publicar una historia, cortada por el borde: el campo de texto asoma abajo y el botón "Publicar" está fuera de la pantalla |
| 02 | `02-despues-entera.png` | La misma hoja, entera, con "Publicar" a la vista |
| 03 | `03-publicada.png` | La historia publicada: la fila de arriba pasa de una línea a la franja de círculos |
| 04 | `04-encuesta.png` | "Nueva encuesta", otra de las seis, con "Crear encuesta" visible |

## La causa

`ModalBottomSheet` arranca **parcialmente expandido** por defecto —ocupando la
mitad de la pantalla— y **no desplaza su contenido**: lo que no entra
simplemente no está. En una hoja que es un formulario, lo que no entra es el
botón del final.

**Ninguna de las doce hojas de la app declaraba `skipPartiallyExpanded`.** El
defecto estaba en las seis que son formularios y sólo se notaba en las más
altas; la de historias era la más alta de todas, así que fue la que se rompió
del todo.

| Hoja | Qué quedaba fuera |
|---|---|
| Publicar una historia | "Publicar" |
| Nueva encuesta | "Crear encuesta" |
| Nuevo evento | el botón de crear |
| Ubicación, Contacto, Descubrir | el botón de enviar/agregar |

## Verificado de punta a punta

1. Se abre la hoja → "Publicar" visible.
2. Se escribe "Probando las historias" y se publica.
3. La fila de arriba cambia a la franja de círculos con "Tu historia".
4. En el **otro emulador**, la historia aparece como "Historias de joaquin" y al
   abrirla se ve el texto con las reacciones rápidas.
5. En la base: una fila nueva en `historia` para `joaquin`.

La de historias lleva además `verticalScroll` e `imePadding`: abrirse entera
resuelve la pantalla en reposo, pero al escribir el pie el teclado se come la
mitad de abajo y el botón volvía a quedar fuera.

## Y lo otro que se veía raro

No era el diseño: **el segundo emulador tenía una compilación vieja**. Las
últimas builds se habían instalado sólo en uno. Con la misma versión en los dos
—mismo tamaño y densidad de pantalla— se ven idénticos.

---

## Las historias con foto: "no me deja" (segunda parte)

Con texto funcionaba; **con foto o vídeo, no**. El mensaje era "No se pudo
subir el archivo."

| # | Captura | Qué muestra |
|---|---|---|
| 05 | `05-fallo-reintentable.png` | El fallo, ahora **dentro** de la hoja: la foto y el pie siguen ahí |
| 06 | `06-historia-con-foto.png` | La historia con foto publicada |

### La causa no estaba en el código

`POST /v1/adjuntos` respondía **200 OK**: la reserva funcionaba. Lo que fallaba
era la subida en sí, que no va al servidor sino **directo al almacén** con una
URL firmada que apunta a `127.0.0.1:9000`. Dentro del emulador, ahí no había
nada escuchando: faltaba `adb reverse tcp:9000 tcp:9000`.

El túnel del servidor (8088) estaba puesto; el del almacén no existía. Por eso
el texto funcionaba y sólo fallaban los archivos — un síntoma que se lee como
un fallo de la función y era el entorno.

Ahora lo pone [`pruebas/conectar-emuladores.ps1`](../../../pruebas/conectar-emuladores.ps1)
para todos los emuladores de una vez.

### Lo que sí se arregló en el código

**La app ya hacía lo correcto** con el fallo: al no poder subir, *retira* la
historia en lugar de dejar una que nadie puede abrir. Se comprobó en la base —
las dos publicaciones fallidas quedaron con `retirada_en`.

Lo que estaba mal era otra cosa: **la hoja se cerraba antes de saber el
resultado**, así que un error de red se llevaba por delante la foto elegida y el
pie escrito. Para reintentar había que volver a abrir, volver a buscar la foto
en la galería y volver a escribir.

Ahora la hoja se cierra sólo al terminar bien. El error aparece dentro, dice
que no se perdió nada, y el botón sigue ahí.

**Verificado de punta a punta:** se quita el túnel del almacén → falla y la
hoja se queda con todo → se restaura el túnel → se toca "Publicar" otra vez →
publica. En la base: el intento fallido `retirada_en`, y el bueno vivo.

