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

---

## Módulo Y.2 · La colección completa

| # | Captura | Qué muestra |
|---|---|---|
| 05 | `05-bandeja.png` | La bandeja: Todos · Recientes · Favoritos · packs · Nuevo pack |
| 06 | `06-acciones.png` | Mantener pulsado: emoji, favorito, mover a un pack, borrar |
| 07 | `07-favoritos.png` | El sticker etiquetado, marcado y dentro de su pack |
| 08 | `08-guardar-recibido.png` | "Guardar en mis stickers" sobre uno que me mandaron |
| 09 | `09-coleccion-del-otro.png` | La colección de `@tatiana`, con los dos que guardó |

## El ciclo entero, hecho a mano

1. Se crea el pack **"Pruebas"**.
2. Se etiqueta el sticker con ❤️ → aparece el filtro por emoji.
3. Se marca como favorito y se mueve al pack → sale en las tres pestañas.
4. Se envía desde Favoritos → pasa a **Recientes** (estaba vacío antes).
5. En el otro emulador: mantener pulsado el sticker recibido →
   **"Guardar en mis stickers"** → aparece en su colección.

Con eso la función deja de ser de una sola persona: antes, un sticker que
llegaba era un callejón sin salida — se veía una vez y no se podía volver a
usar.

## Dos defectos encontrados probándolo

**Un sticker sin pack y sin usar no aparecía en ninguna pestaña.** Ni en
Recientes —nunca se mandó—, ni en Favoritos —no se marcó—, ni en ningún pack.
Lo vi creando un pack y viendo desaparecer el único que había. Por eso existe
la pestaña **Todos**, que además es la que abre por defecto: con Recientes por
delante, quien acaba de crear su primer sticker abre la bandeja y lee "todavía
no mandaste ninguno" teniendo uno.

**La confirmación salía bajo un cartel de error.** "Guardado en tus stickers"
aparecía dentro del diálogo de `aviso`, que se titula **"No se pudo
completar"**. Ahora las confirmaciones tienen su propio aviso, que se va solo a
los dos segundos y no pide que lo cierren.

## Con movimiento

Un GIF o un WebP animado **se detecta por la cabecera del archivo, no por la
extensión** —un `.webp` puede ser las dos cosas— y se agrega **tal cual, sin
recortar**: Android no trae codificador de WebP animado ni de GIF, así que
recortarlo obligaría a aplanarlo a un fotograma, o sea a quitarle justo lo que
se venía a conservar.

Y la burbuja pasó a dibujarse con Coil en vez de `BitmapFactory`: un `Bitmap`
es **un** fotograma, así que un sticker animado se veía congelado en el
primero. El `ImageLoader` de la app ya traía el decodificador; sólo había que
dejar de esquivarlo.

