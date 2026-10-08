# La versión web

## TL;DR

- **Se hace:** primero en el navegador y después el mismo código como app de
  escritorio firmada (Tauri). La interfaz va en React con TypeScript.
  Decidido el 2026-10-07.
- **El cifrado es la misma libsignal que el teléfono.** Es el crate oficial
  `libsignal-protocol` v0.86.5 compilado a WebAssembly, con un envoltorio
  propio (`web/cripto`).
- **W0 está hecha y salió bien.** Lo que cifra la web lo abre la libsignal
  del teléfono, y al revés:
  - las sesiones (PQXDH con Kyber1024);
  - el ratchet, también con mensajes fuera de orden;
  - los grupos;
  - la huella.

  Además corre dentro de Chromium. Ver `docs/evidencias/web-w0/`.
- **La web es un aparato vinculado más.** Se entra con el código o QR del
  teléfono, no con usuario y contraseña, porque el inicio de sesión no crea
  aparatos.
- **El precio, declarado:** en el navegador, el código lo entrega el servidor
  cada vez que se abre la página. Quien controle el servidor, o lo comprometa,
  podría servir una versión que robe las claves. Por eso Signal no tiene
  versión web. La app de escritorio lo resuelve: su código va firmado, como el
  APK.

## Por qué no hay un libsignal oficial para el navegador

`@signalapp/libsignal-client` es nativo de Node y Electron. No hay una
versión para navegador.

Pero el núcleo es Rust puro, y `libsignal-protocol` compila a
`wasm32-unknown-unknown` sin tocar una línea. Ya lo había mostrado una prueba
independiente ([jaredwray/signal-web](https://github.com/jaredwray/signal-web/pull/1))
y aquí se confirmó. Hay un envoltorio comunitario
([getmaapp/signal-wasm](https://github.com/getmaapp/signal-wasm)), pero va
en la v0.101 y es de otra gente. Se prefirió uno propio y mínimo, en **la
misma versión que la app Android**, para que los dos lados no puedan
divergir.

Tres detalles que costaron o pueden costar:

- **El reloj.** En `wasm32-unknown-unknown`, `SystemTime::now()` entra en
  pánico. Toda función que necesita la hora la recibe de JavaScript, y las
  prekeys Kyber se arman a mano en vez de con `KyberPreKeyRecord::generate`,
  que lee el reloj por dentro.
- **El azar.** Sale de `crypto.getRandomValues`, a través de getrandom 0.3
  con el backend `wasm_js` (`web/cripto/.cargo/config.toml`) y de getrandom
  0.2 con `js`.
- **El parche de curve25519.** El workspace de libsignal usa el fork de
  Signal de `curve25519-dalek`. Un parche de workspace no llega a quien depende
  del crate, así que se repite en `web/cripto/Cargo.toml`.

## Lo que el servidor ya tiene y lo que le falta

**Ya está:**

- JSON por WebSocket con token Bearer.
- Multi-dispositivo, donde la dirección de Signal es el aparato.
- Historial pedido a otro aparato propio.
- No verifica atestación ni plataforma.
- Los adjuntos van en AES-256-GCM, que WebCrypto maneja.
- Las llamadas usan WebRTC estándar, con la señalización cifrada por Signal.

**Falta (W1 en adelante):**

| Qué | Por qué |
|---|---|
| Un nivel de hardware para navegador | Producción solo acepta `STRONGBOX`/`TEE` (rechaza `SOFTWARE_DEV`), y la base solo admitía tres valores. Ojo: el nivel lo **declara** el aparato y el servidor no lo verifica; ver `docs/04-DEVICE-BINDING.md`, "Lo que el servidor verifica hoy". El nivel nuevo se acepta **solo al vincular**, nunca para registrarse ni recuperar la cuenta: un navegador no puede ser el aparato principal. |
| Servir la web desde el mismo origen que la API | No hay CORS en Ktor (`ktor-server-cors` está en el catálogo pero no en el servidor), en Caddy ni en el almacén. Igual que `/consola`, la web va bajo el dominio de la API y así no hay que abrir CORS. |
| CORS en el almacén de adjuntos | Resuelto en W3: SeaweedFS 4.47 ya refleja el origen; solo hizo falta que la CSP de `/web` nombre el almacén. En producción, verificar que Caddy deje pasar el preflight. |
| Varias pestañas | Resuelto en W2/W3: el servidor admite un socket por aparato; una pestaña lo tiene (Web Locks) y las demás le hablan por `BroadcastChannel`. |
| Avisos con la página cerrada | Resuelto en W4c: Web Push con VAPID, aviso vacío. Con la página abierta, la web ya avisaba (W3). |

**Formato de `Carga`, a cuidar en TypeScript:**

- El discriminador es el nombre completo de la clase:
  `"type":"com.wtfuck.protocol.Carga.Texto"`.
- Un `ByteArray` viaja como un arreglo de números.
- El relleno es con ceros (`Relleno.kt`).

En W2 se fija con pruebas de oro: JSON que genera Kotlin y que TypeScript
tiene que leer y producir igual.

## Fases

| Fase | Qué | Estado |
|---|---|---|
| **W0** | libsignal en WebAssembly + prueba contra la JVM + prueba en el navegador | **Hecha** (2026-10-07) |
| W1 | Servidor: nivel `NAVEGADOR` solo al vincular (nunca principal, sesión de 30 días), la web servida en `/web` con CSP estricta, y pruebas | **Hecha** (2026-10-08): `docs/evidencias/web-w1/` |
| W2 | Web mínima: vincular con el código, publicar claves, chats directos y grupos de texto, historial pedido al teléfono, almacén cifrado en IndexedDB, una pestaña con el socket | **Hecha** (2026-10-08): `docs/evidencias/web-w2/`. Probada de punta a punta contra el teléfono del emulador |
| W3 | Fotos y archivos (directo al almacén; SeaweedFS ya responde CORS), respuestas, ediciones, eliminar para todos, reacciones, avisos del navegador, aviso de cambio de identidad y huella, avisos entre pestañas | **Hecha** (2026-10-08): `docs/evidencias/web-w3/`. Probada contra el teléfono, huella incluida |
| W4 | Escritorio con Tauri (el mismo código, firmado), llamadas WebRTC, Web Push, notas de voz y miniatura de video, canales | **En curso**: W4a notas de voz y video, W4b canales públicos y W4c Web Push hechas (2026-10-08), `docs/evidencias/web-w4/`. Faltan llamadas y Tauri |

**Fuera de la web:** el modo cerca, porque el navegador no puede anunciar ni
escuchar por Bluetooth así.

## El modelo de confianza, escrito

Es la **tercera excepción declarada** al "el servidor no es de confianza".
Las otras dos son los canales públicos y el texto de una denuncia.

- **En el navegador**, quien sirve el código puede cambiarlo. Lo que se puede
  hacer, y se hará en W1 y W2:
  - una CSP estricta;
  - el `.wasm` con su huella publicada;
  - builds reproducibles;
  - sesiones web que el teléfono ve y revoca;
  - un aviso en la propia web de que su código lo entrega el servidor.
  - (W4c) el service worker de Web Push **no intercepta peticiones ni guarda
    caché**: si lo hiciera, una copia vieja del código que maneja las claves
    podría sobrevivir a una actualización del servidor. Solo muestra un aviso
    fijo; `pruebas/web.mjs` comprueba que no tenga `fetch`.
- **En escritorio** (Tauri), el código va firmado y se actualiza como el APK.
  El servidor no puede cambiarlo.
- **Las claves en el navegador** viven en IndexedDB, cifradas con una clave
  de WebCrypto no exportable. Protege contra quien copie el perfil del
  navegador, pero no contra código malicioso que corra en la página, que es el
  punto de arriba.
- **Licencia:** libsignal es AGPL-3.0. El envoltorio hereda la licencia, igual
  que la app Android que ya la usa.

## Cómo reproducir W0

Ver `web/README.md`.
