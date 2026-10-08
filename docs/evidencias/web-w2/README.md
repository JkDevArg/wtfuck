# Versión web, W2: la primera web usable

Plan: `docs/12-VERSION-WEB.md`. Fases anteriores: `../web-w0/` y `../web-w1/`.

## Qué hay

Una app en React + TypeScript (Vite) en `web/app`, servida en `/web` desde el
mismo origen que la API.

| Pieza | Qué hace |
|---|---|
| `web/cripto/src/almacen.rs` | El almacén de Signal **se puede guardar**: el de libsignal (`InMemSignalProtocolStore`) no deja recorrer sus sesiones. Está partido en una parte por trait (identidades, únicas, firmadas, Kyber, sesiones, emisores) porque `message_decrypt` las pide como préstamos separados. Exporta e importa JSON. |
| `src/datos/boveda.ts` | IndexedDB **cifrado**: AES-256-GCM con una clave de WebCrypto **no exportable**, guardada como objeto en la propia base. Almacén de Signal, sesión, chats y mensajes. |
| `src/datos/cripto.ts` | El WebAssembly, en serie (el ratchet no admite dos operaciones a la vez, igual que el candado de `CifradorSignal`), guardando el almacén después de cada cambio. |
| `src/datos/protocolo.ts` | El contrato en TS: `Carga` con el discriminador de Kotlin, relleno por cubos, menciones, `Subida`/`Bajada`. |
| `src/datos/motor.ts` | Lo que hace el `Repositorio` de la app para **texto**: vincular, publicar y reponer claves, WebSocket con reconexión, recibir (2, 3 y 7, `ConClaveGrupo`, `Historial`), enviar (por pares o con clave de emisor y rotación), acuses de entrega y lectura, y pedido de historial. |
| `src/App.tsx` | Vincular, lista de chats, chat. Dos columnas en escritorio y una en el teléfono. Un aviso, donde se decide usarla, de qué cambia en la web. |
| `despliegue/publicar-web.sh` | Arma el WebAssembly y la app, corre las pruebas, calcula `HUELLAS.txt` y empaqueta para subir. |

**Diferencias a propósito con la app** (marcadas `DIFERENCIA` en `motor.ts`):

- **La clave de emisor** se da por repartida cuando el servidor **acepta** el
  envío, no cuando el socket lo escribe.
- **Con `sinCopia`** no se reenvía el mensaje entero: puede ser un aparato sin
  claves, y eso daría un bucle. Se refrescan los destinos para el siguiente.
- **"Salir" es desvincular**: revoca este aparato en el servidor y borra todo
  lo guardado.
- **Un socket entre todas las pestañas** (Web Locks). Las demás dicen
  "Abierta en otra pestaña" y toman el relevo cuando la primera se cierra.

## Arreglos en el servidor que salieron de aquí

- **Revocar un aparato no cortaba su socket abierto.** El token dejaba de
  servir, pero la conexión que ya estaba viva seguía hasta reconectar.
  - **Arreglo:** `Hub.expulsar` cierra el canal y el socket se cierra con 1008.
    Lo hace al revocar desde "Mis dispositivos" y también al recuperar la
    cuenta, que es el caso del teléfono robado.
  - **Afectaba también a los teléfonos.**
  - **Prueba:** `pruebas/web.mjs` abre el socket, revoca y exige el 1008.
- **`/web` dependía de la carpeta al arrancar.** Si estaba vacía, quedaba en 404
  hasta reiniciar. Ahora se lee en cada pedido, y publicar es solo reemplazar
  los archivos.

## Pruebas

| Qué | Resultado |
|---|---|
| Pruebas nativas del envoltorio (`cargo test`) | **5 de 5**. Incluye exportar e importar el almacén y seguir hablando, también en un grupo, y que un almacén roto no se importe. |
| JVM contra WebAssembly (`:interop-web`) | **6 de 6**, con el almacén nuevo. |
| Pruebas de oro (`OroTest` + `protocolo.test.ts`) | **21 de 21**. Kotlin escribe los JSON de referencia y la web los produce byte a byte: textos (con ñ, emoji, comillas y saltos), `ConClaveGrupo`, `Historial`, los 12 casos de relleno, un texto rellenado y las menciones. |
| `pruebas/web.mjs` | **44 de 44**. Los 41 de W1, el cierre con 1008 al revocar, y que `/web` sirva la app armada leyendo el script y el `.wasm` desde `index.html`. |

### De punta a punta: la web contra el teléfono del emulador

Una cuenta de prueba con un principal falso (sin claves), un chat directo y un
grupo con **@xampl3** en el emulador 5554 (app 0.6.4 de depuración).

| Paso | Resultado |
|---|---|
| Vincular con el código (`1-vincular.png`) | En línea, con los dos chats. Aviso correcto: "Ningún otro aparato tuyo está en línea para mandarte el historial". |
| Web → teléfono, directo | Llega (`2-telefono-recibe.png`). En la web pasa a **entregado** y, al abrirlo en el teléfono, a **leído**. |
| Teléfono → web, directo | Llega (`3`, `4-web-directa.png`). |
| Web → grupo, 2 mensajes | El teléfono registra la clave de emisor de la web con el primero (`ConClaveGrupo`) y descifra el segundo como tipo 7. Los dos pasan a **leído** (`5`, `6`). |
| Teléfono → grupo, 2 mensajes | La web descifra la clave de emisor del teléfono y los dos mensajes, y muestra al autor (`7`, `8-web-grupo.png`). |
| Recargar la página | Vuelve todo: chats, mensajes y sesión. Un mensaje nuevo después de recargar se entrega y el teléfono lo descifra. |
| Lo guardado en IndexedDB | Solo `iv` y bytes cifrados, sin texto legible. La llave es `extractable: false` y `exportKey` la rechaza con `InvalidAccessError`. |
| Revocar con la página abierta | Aviso en el acto, y IndexedDB queda con 0 filas en sus cuatro tablas (`9-revocada-en-vivo.png`). Si se recarga, el aviso sigue ahí. |
| Dos pestañas | La segunda dice "Abierta en otra pestaña"; al cerrar la primera, la segunda queda en línea. |
| Ancho de teléfono (375 px) | Una columna. Se mandó y se entregó con la identidad nueva tras volver a vincular (`10-web-ancho-telefono.png`). |

### Regresión

`node pruebas/correr.mjs` con los cambios de este commit: ver el resultado en
el mensaje del commit.

## Lo que NO se probó o no está

- **Historial de verdad.** El principal de la cuenta de prueba es falso y no
  puede responder. El código lo pide, lo guarda y está cubierto por las pruebas
  de oro del JSON, pero falta verlo con el teléfono real del usuario.
- **Firefox y Safari.** Solo Chromium.
- **Fotos, archivos, notas de voz, llamadas, reacciones, ediciones, respuestas
  y canales.** Se acusan y no se muestran (W3).
- **La pestaña que no tiene el socket no se actualiza sola.** Hay que pasar a
  la que lo tiene. W3: avisarse entre pestañas con `BroadcastChannel`.
- **Avisos con la página cerrada** (Web Push), W3.
- **La web no responde pedidos de historial** de otro aparato: eso lo hace el
  teléfono.
- **El aviso de cambio de identidad** está en el WebAssembly
  (`identidadCambio`) pero todavía no se muestra.
