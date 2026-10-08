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

---

# W4c: avisos con el navegador cerrado (Web Push)

`server/.../WebPush.kt`, `web/app/public/sw.js`, y "Avisos con el navegador
cerrado" en `web/app/src/datos/motor.ts`.

## Qué viaja: nada

El servidor le hace al servicio de push del navegador un **POST sin cuerpo**,
firmado con VAPID (RFC 8292). No usa el cifrado de payload de RFC 8291 porque
no hay nada que cifrar:

- El servicio de push (Google, Mozilla, Apple o Microsoft) aprende que este
  navegador recibió un aviso a esta hora. No sabe quién escribió, ni dónde, ni
  cuánto.
- El service worker muestra un texto fijo, "wtfuck · Tienes algo nuevo". La
  página, al abrirse, baja y descifra lo pendiente.

Es la misma regla que FCM en la app (`data: {"w":"1"}`), llevada al extremo.

## Decisiones

- **Contra la SSRF:** el endpoint lo manda el cliente y el servidor le hace un
  POST. Por eso:
  - solo se aceptan HTTPS, puerto 443, de los cuatro servicios de push
    conocidos;
  - se valida al registrar y al enviar;
  - no se siguen redirecciones.
- **`Topic: avisos`:** el servicio de push reemplaza el aviso que no entregó.
  Veinte mensajes con el navegador apagado son un aviso al prenderlo. Se suma
  a la ventana de 12 s que ya tenía `Push.despertar`.
- **`Urgency: high` y TTL de un día**, por el mismo criterio que
  `priority: high` en FCM.
- **El service worker no tiene `fetch` ni caché.** Así la página viene siempre
  del servidor, y una copia vieja del código que maneja las claves no
  sobrevive a una actualización.
- **Con la web a la vista no avisa:** ya avisa la página.
- **Claves VAPID mal copiadas:** se comprueba que sean pareja al arrancar
  (firma y verificación). Sin eso, el síntoma sería un 403 mudo de todos los
  servicios.
- **Cambio de par VAPID:** la web se resuscribe sola al abrirse; compara la
  clave de su suscripción con la del servidor.
- **Suscripción vencida (404/410):** se borra.
- **Desvincular o perder el vínculo:** la web borra su suscripción.
- **Migración:** V51 agrega `webpush` a `push_proveedor_valido`. La V50 la
  tomó en paralelo la rama `limite-registro` (`contador_por_red`), que ya
  estaba aplicada en la base de desarrollo; por eso esta es la 51.

## Pruebas

| Qué | Resultado |
|---|---|
| `pruebas/webpush.mjs` (nueva), con `-ConPushDeMentira` y el stub | **41 de 41** |
| — configuración | Trae solo la clave pública, un punto P-256 de 65 bytes; sin sesión, 401. |
| — SSRF | Rechaza 9 endpoints: metadatos de la nube, IP interna, host cualquiera, `fcm.googleapis.com.ejemplo-malo.test`, `malonotify.windows.com`, http, otro puerto, usuario en la URL, una no-URL. Acepta los de Chrome, Firefox, Safari y Edge. |
| — el aviso | **0 bytes de cuerpo**, sin `Content-Encoding`. Ni el usuario, ni el grupo, ni quién escribe en ninguna cabecera. `Urgency: high`, `TTL: 86400`, `Topic: avisos`. |
| — VAPID | `vapid t=…, k=…` con k igual a la clave servida. ES256 con `aud` = origen del servicio, `exp` menor a 24 h y `sub` = contacto. La firma R‖S de 64 bytes **verifica en Node** con la clave pública. |
| — suscripción vencida | Un 410 borra el endpoint. |
| `pruebas/push.mjs` (FCM, sin cambios) | **36 de 36**: el camino de la app sigue igual. |
| `pruebas/web.mjs` | **49 de 49**. Nuevas: `sw.js` como JS, `no-cache`, sin `fetch` ni `caches`, y `worker-src 'self'`. |
| vitest | **37 de 37**. Nuevas: `sw.js` corrido en una caja con un `self` de mentira. Escucha solo install/activate/push/notificationclick; el aviso es fijo; con la web visible no avisa y en otra pestaña sí; al tocarlo enfoca la web o la abre. |
| Navegador del panel | El service worker se registra y se activa en `/web/`. |

## Lo que NO se probó

- **Un aviso real de punta a punta** (Chrome → FCM → service worker). El panel
  se comporta como incógnito: Chrome lo dice en la consola, "does not support
  the Push API in incognito mode", y además deniega las notificaciones.
  Hace falta un Chrome normal contra `localhost` o contra el despliegue.
- **Firefox, Safari y Edge.** Safari en iOS solo da Web Push a una web
  instalada en la pantalla de inicio, y esta web no tiene manifiesto todavía.
- **Que los servicios de verdad acepten la firma.** Está verificada con la
  clave pública en Node, que es la misma comprobación que hacen ellos.
