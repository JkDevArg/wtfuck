# Evidencias · Stickers a partir de una foto

| # | Captura | Qué muestra |
|---|---|---|
| 01 | `01-hoja.png` | La hoja, con "Mis stickers" y "Crear de una foto" arriba del buscador de GIFs |
| 02 | `02-editor.png` | El editor: ventana cuadrada, arrastrar y pellizcar |
| 03 | `03-recibido.png` | El sticker **en el otro aparato**, sin burbuja ni marco |
| 04 | `04-mis-stickers.png` | Guardado en la galería propia, listo para reusar |

## Qué había antes

La clase de adjunto `sticker` existe desde el módulo D —con su tope de 2 MB y
su burbuja sin fondo— pero **no había forma de crear uno**. El botón se llama
"Sticker o GIF" y sólo buscaba GIFs en un servicio externo que además necesita
una clave que no está configurada: las dos mitades del nombre llevaban a la
misma pantalla vacía.

## Verificado de punta a punta

1. Adjuntar → Sticker o GIF → **Crear de una foto**.
2. Se elige una foto de la galería y se recorta.
3. "Crear y enviar" → el archivo queda en la app:

   ```
   -rw------- 1 u0_a217 u0_a217 48958 2026-09-24 16:54 1790268888031.webp
   ```

   **48 KB.** El mismo recorte en PNG ronda los 400. Como cada sticker viaja
   cifrado a cada aparato de cada destinatario, en un grupo de veinte eso es la
   diferencia entre 1 MB y 8 MB por sticker enviado.

4. Llega al otro emulador y se dibuja **sin burbuja** (captura 03).
5. Vuelve a aparecer en "Mis stickers" para reusarlo (captura 04).

## Lo que NO hace, y por qué

**No quita el fondo.** Hacerlo bien sobre una foto cualquiera necesita un
modelo de segmentación —en Android, ML Kit sobre Play Services—: una
dependencia de Google, una descarga de modelo en el primer uso y un servicio
más del que depender. En una app cuyo argumento es que el servidor no puede
leer nada, eso es **una decisión de producto** y no la tomo solo.

La alternativa sin dependencias —quitar por color, tipo croma— funciona con un
fondo liso y deja bordes sucios en cualquier foto real. Un recorte cuadrado
bien hecho se usa; uno con halos se usa una vez.

Lo que sí hace: **si la foto de origen ya tiene transparencia, se conserva**.

## Lo que aprendí escribiendo las pruebas

`cuadradoCentrado` devolvía un `android.graphics.Rect`, y cinco pruebas se
cayeron con "Rect.width not mocked": en una prueba de JVM los métodos del
framework no existen. La aritmética del recorte —lo único que de verdad se
puede equivocar aquí— pasó a una clase propia, `Recorte`, y se convierte a
`Rect` sólo al dibujar. 13 pruebas en
[`StickersTest`](../../../app/src/test/java/com/wtfuck/app/StickersTest.kt).
