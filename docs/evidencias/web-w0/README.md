# Versión web, W0: libsignal en el navegador

**Resultado: sigue adelante.** El WebAssembly del navegador y la libsignal
del teléfono hablan el mismo Signal. El plan está en `docs/12-VERSION-WEB.md`.

## Qué se probó

### 1. El envoltorio, nativo

`cargo test --release` en `web/cripto`: **3 de 3**.

- Una ida y vuelta por pares con PQXDH: el primer mensaje es tipo 3 y la
  respuesta tipo 2.
- Un grupo con clave de emisor.
- La huella sale igual de los dos lados.

### 2. El navegador contra el teléfono

`./gradlew :interop-web:test`: **6 de 6, ninguno omitido**.

Del lado del teléfono, `libsignal-client` 0.86.5 en la JVM, usada como
`CifradorSignal` (mismas llamadas, deviceId 1, mismo armado del paquete). Del
lado de la web, el `.wasm` de `web/cripto/pkg` en Node.

| Caso | Qué demuestra |
|---|---|
| La web abre sesión con el teléfono y se hablan | PQXDH desde la web con el paquete que publica el teléfono. Texto con ñ y emoji. Tipo 3 de ida y tipo 2 de vuelta. |
| El teléfono abre sesión con la web usando lo que la web publica | El JSON de la web se lee con `PublicarClavesReq` del contrato. Los ids de las únicas son los pedidos. PQXDH desde el teléfono. |
| El ratchet aguanta mensajes fuera de orden | 3 rondas de 4 mensajes en cada sentido, entregados en otro orden. |
| Un mensaje de la app viaja tal cual | `Relleno.poner(Carga.Texto)` de 5 KB, cifrado en la web y abierto y leído en la JVM. |
| Grupos en los dos sentidos | Clave de emisor del teléfono a la web y de la web al teléfono, tipo 7, 3 mensajes en cada sentido. |
| La huella es la misma | Los 60 dígitos de la web son los de `NumericFingerprintGenerator(5200)` del teléfono, y el teléfono valida el código escaneable de la web. |

### 3. Dentro de un navegador

`web/interop/navegador.html` en Chromium 152 (el navegador del panel):
**7 de 7, sin errores en consola** (`1-navegador.png`).

| Dato | Valor |
|---|---|
| libsignal | v0.86.5 |
| Tamaño del `.wasm` | 1084 KB, sin comprimir ni pasar por `wasm-opt` |
| Carga e inicio del WebAssembly | 270 ms, servido en local |
| Identidad, firmada, Kyber1024 y una única | 4 ms. La Kyber pública mide 1569 bytes |
| Abrir sesión (PQXDH) | 8 ms |
| 100 mensajes de ida y vuelta, la mitad fuera de orden | 39 ms |
| Un mensaje alterado | se rechaza |
| 20 mensajes de grupo | 11 ms |
| Huella | 13 ms, igual de los dos lados |

## Lo que NO se probó aquí

- **Firefox y Safari.** Solo hubo Chromium a mano. La prueba independiente
  citada en el plan lo corrió en 6 navegadores, pero conviene repetirlo con
  este `.wasm` antes de W2.
- **Un teléfono de verdad.** El otro lado fue la libsignal de la JVM, que es el
  mismo código Rust que `libsignal-android`, pero no es el teléfono. Con W2 el
  primer mensaje real va de la web a la app.
- **Guardar el almacén.** Hoy vive en memoria; IndexedDB va en W2.
- **El tamaño en producción.** Con gzip o brotli y `wasm-opt` debería bajar
  bastante, pero no se midió.
