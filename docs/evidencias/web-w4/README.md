# Versión web, W4: notas de voz y video (W4a)

Plan: `docs/12-VERSION-WEB.md`. W4 va por partes; esta es la primera.

## Notas de voz (`web/app/src/datos/grabadora.ts`)

- **Formato:** MP4 con AAC a 64 kbps, el mismo que graba la app. Chrome y
  Safari lo saben hacer con MediaRecorder (`audio/mp4;codecs=mp4a.40.2`). El
  WebM de Chrome se usa solo si no hay otra opción: no trae duración, y en
  Android la barra no avanzaría ni se podría saltar.
- **Onda:** cada 80 ms se toma el pico de la señal, en la escala de la app
  (`maxAmplitude / 12000`). Al final se arman 40 barras con el mismo algoritmo
  que `Onda.codificar`, redondeo en `Float` incluido. Una prueba de oro lo
  compara con lo que escribe Kotlin.
- **Topes como la app:** 10 minutos, 12 MB, y se descarta lo que dure menos de
  un segundo. Se toca para grabar y se toca para mandar.
- **Al recibir:** la app manda sus notas como `application/octet-stream`,
  porque graba a un archivo sin tipo. La web lo deduce por la extensión del
  nombre (`.m4a` → `audio/mp4`).

## Video

- **Miniatura:** el fotograma de los 0,1 s, a 240 px en JPEG de 20 KB como
  mucho, como `Media.miniaturaDe`.
- **Medidas:** se guardan el ancho, el alto y la duración.

## Pruebas

| Qué | Resultado |
|---|---|
| vitest | **32 de 32**. Nuevas: la onda es igual a la de Kotlin (137 muestras, 3 muestras y vacía), una onda rota no se lee, y una nota de la app sin tipo se reproduce como `audio/mp4`. |
| Nota web → teléfono | 4 s grabados con la fuente de prueba de desarrollo (`?microfono-de-prueba`, un tono; el panel bloquea el micrófono). En el teléfono: onda de 40 barras, 0:04, reproduce y avanza (`1-telefono-nota-de-voz.png`). |
| Nota teléfono → web | 10 s con el micrófono del emulador. En la web: onda, 0:10, y al tocar se baja, se descifra y se reproduce sin error (10,1 s) (`2-web-notas-de-voz.png`). |
| Video web → teléfono | 3 s en MP4 H.264 desde un lienzo animado. En el teléfono, con miniatura y botón de descarga (`3-telefono-video.png`). |

**Arreglos de paso:**

- La duración se superponía al reproductor.
- La lista decía `voz-….m4a` en vez de "Nota de voz", y ahora también
  "Video" y "Audio".

## Lo que NO se probó

- **Un micrófono de verdad en el navegador:** el panel lo bloquea. La fuente
  de prueba recorre el mismo camino desde el `MediaStream`.
- **Firefox:** no graba `audio/mp4` y caería en WebM, con el problema de
  duración en Android que se explica arriba.

---

# W4b: canales

`web/app/src/datos/canales.ts` y `web/app/src/Canal.tsx`.

- **Público:** el contenido vive en claro en el servidor (es una de las dos
  excepciones declaradas). Se lee y se publica por HTTP, sin sobres, con una
  barra ámbar que lo dice. Publicar y comentar es primero el metadato
  (`POST /v1/mensajes`, donde el servidor autoriza) y después el cuerpo,
  igual que la app.
- **Muro:** del más nuevo al más viejo, paginado con "Ver anteriores".
  - Las imágenes de una publicación se bajan con `fetch` y se muestran como
    blob: la CSP no deja cargarlas directo de otro dominio, y así no hace
    falta abrirla.
  - Reacciones y comentarios solo si el canal los tiene encendidos.
  - "Dejar de seguir".
- **Descubrir:** el directorio y el buscador; tocar un canal suscribe y lo
  abre.
- **Evento `canal_publicacion`:** sube la lista, avisa ("Publicación nueva",
  nunca el texto) y recarga el muro si está abierto.
- **Privado:** la web no lo ofrece y lo dice. La app Android todavía no lo
  termina: el servidor rechaza publicar en él y la pantalla no muestra los
  mensajes.

**Una corrección a la especificación:** para texto, la clase de contenido es
vacía (`ClaseContenido.TEXTO = ""`), no `"texto"`. El servidor respondía
"Clase de contenido desconocida: texto".

| Prueba (contra el servidor local) | Resultado |
|---|---|
| Directorio | Lista los canales públicos aprobados. |
| Suscribirse a uno | Pasa de 3 a 4 suscriptores; barra ámbar; publicación con reacción y 2 comentarios. Como suscriptora no puede publicar, y no se ofrece. |
| Comentar | Aparece "@webpruebaadd727 Comentario desde la web". |
| Reaccionar | ❤️ 1, junto al 👍 que ya tenía. |
| Canal propio, creado por API | Barras de "no cifrado" y "pendiente de aprobación". Publicar desde la web aparece en el muro (`4-web-canal.png`). |

**Sin probar:**

- **Recibir el evento `canal_publicacion` en vivo:** hace falta otro que
  publique en un canal que la web siga.
- **Una publicación con imagen.**
