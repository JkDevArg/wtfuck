# Versión web, W3: archivos, respuestas y lo demás

Plan: `docs/12-VERSION-WEB.md`. Fases anteriores: `../web-w0/` a `../web-w2/`.

## Qué se agregó

| Qué | Cómo |
|---|---|
| **Fotos y archivos, enviar** | Imagen reducida a 2560 px en JPEG al 88, como la calidad "alta" de la app; un GIF va tal cual. Miniatura de 240 px y 20 KB como mucho. Se cifra con AES-256-GCM (clave y nonce al azar por archivo) y se sube **directo al almacén** con la URL firmada: los bytes no pasan por el servidor, igual que en la app. Recién después sale el mensaje con la clave. |
| **Fotos y archivos, recibir** | Miniatura al instante, que viaja dentro del mensaje cifrado. Las fotos se bajan solas al verse, y video, audio y documentos al tocar. Se verifica la etiqueta GCM ("pudo ser alterado"). Lo descifrado vive solo en memoria: nunca toca la bóveda en claro. "Ver una vez" no se abre en el navegador. |
| **Respuestas** | Con la cita escrita (`respondeA`, `respondeTexto` de 140, `respondeAutor`), como la app. |
| **Editar** | `POST /mensajes/{id}/editar` más un sobre `Carga.Edicion` cifrado. **DIFERENCIA:** la web solo acepta una edición si la manda el **autor** del mensaje. La app no lo comprueba. |
| **Eliminar para todos** | `POST /mensajes/{id}/retirar` y el evento `mensaje_retirado`. |
| **Reacciones** | `POST /mensajes/reaccion` y el evento `mensaje_reaccion`, que se relee con `GET /mensajes/{id}`. Las mismas seis rápidas de la app. |
| **Avisos del navegador** | Con la página abierta. Igual que la app, **nunca el texto**; solo, si se quiere, quién escribió. |
| **Cambio de identidad** | Si la clave de un aparato cambia, aparece una banda en el chat, como en la app. "Verificar cifrado" muestra los 60 dígitos con cada aparato del otro. |
| **Entre pestañas** | `BroadcastChannel`: la que tiene el socket avisa cada cambio, y las demás le piden enviar y marcar como leído. Ninguna toca el ratchet a la vez. |

### CORS: no hizo falta tocar nada

**SeaweedFS 4.47 ya responde CORS por su cuenta**: refleja el origen y
permite `GET` y `PUT` (comprobado con un preflight). Lo que faltaba era la CSP
de `/web`. `Web.kt` ahora nombra el almacén con la **misma regla que firma
`Almacen`**: `WTFUCK_S3_PUBLICO`, si no `WTFUCK_S3_URL`, y si no
`127.0.0.1:9000`. Así funciona igual en desarrollo y en producción.

**Para verificar en producción:** que Caddy deje pasar el preflight `OPTIONS`
y las cabeceras de SeaweedFS en el dominio de media. No se probó.

## Pruebas

| Qué | Resultado |
|---|---|
| Pruebas de oro (vitest) | **29 de 29**. Las nuevas: `CargaAdjunto` con `@SerialName("adjunto")` y todos sus campos, `Edicion`, una respuesta, y **un archivo cifrado por la JVM igual que `CifradorArchivo`, que la web abre**. También: un byte cambiado da "pudo ser alterado", y nombres con U+202E y saltos de línea se limpian. |
| Interoperabilidad JVM (`:interop-web`) | 6 de 6, con el WebAssembly nuevo (`identidadVista`). |
| Rust nativo | 5 de 5. |
| `pruebas/web.mjs` | **45 de 45**. Nuevo: la CSP de `/web` nombra el almacén. |

### De punta a punta: la web contra el teléfono del emulador (@xampl3)

| Paso | Resultado |
|---|---|
| Huella | La web y el teléfono muestran **los mismos 60 dígitos** para el aparato "Chrome en Windows": `41612 57539 01277 39508 61752 68437 43140 35212 72228 34453 79394 36609` (`1`, `2`). |
| Foto web → teléfono | Se generó en el navegador, se redujo y cifró, y se subió directo al almacén. El teléfono la baja, la descifra y muestra la foto completa con su pie (`3`, `4`). |
| Foto teléfono → web | Una foto de la galería, de 2400×1500. La web la baja del almacén (CORS en orden), la verifica, la descifra y la muestra (`5`). |
| Responder desde la web | El teléfono muestra la cita con `@xampl3` y el texto citado (`6`). |
| Reaccionar desde la web | ❤️ aparece en el teléfono. |
| Reaccionar desde el teléfono | 👍 aparece en la web (evento `mensaje_reaccion` y relectura). |
| Editar desde la web | "editado" en el teléfono. |
| Editar desde el teléfono | "editado" en la web: aceptada porque la manda el autor. |
| Eliminar desde la web y desde el teléfono | "Mensaje eliminado" en los dos lados, y también en la vista previa de la lista (`7`). |
| Dos pestañas | Lo escrito en la pestaña sin socket sale por la titular y se ve "entregado". La respuesta del teléfono aparece en las dos. |

**Arreglos que salieron de probar:**

- **La lista de chats desbordaba** con textos largos: a los hijos de flex y
  grid les faltaba `min-width: 0`.
- **Al terminar de cargar una foto**, el chat no seguía al final.
- **Editar o eliminar el último mensaje** no actualizaba la vista previa de la
  lista.
- **"Abierta en otra pestaña: úsala allí"** era falso desde que las pestañas se
  hablan: ahora dice "En línea, a través de otra pestaña".

## Lo que NO se probó o no está

- **Un aviso del navegador de verdad.** El navegador del panel tiene los
  permisos de avisos bloqueados (`denied`), y la web oculta el control en ese
  caso, que es lo correcto. Falta verlo en Chrome o Firefox normal.
- **La banda de cambio de identidad, en vivo.** Hace falta que el otro
  reinstale o cambie de aparato. El WebAssembly marca y desmarca el cambio, y
  la interfaz lo muestra, pero no se forzó el caso.
- **Video, audio y documentos de punta a punta.** El código es el mismo de las
  fotos, sin la reducción; solo se probaron fotos.
- **Web Push** (avisos con el navegador cerrado), **miniatura de video**, **notas
  de voz grabadas desde la web**, **canales** y **llamadas**: quedan para W4.
- **CORS en producción**: ver arriba.
