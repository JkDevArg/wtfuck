# Versión web, W1: el servidor

El plan está en `docs/12-VERSION-WEB.md`; la prueba del cifrado, en `../web-w0/`.

## Qué se hizo

### Un aparato de nivel `NAVEGADOR`

- **Contrato:** `NivelHardware` y `DIAS_SESION_NAVEGADOR = 30`, en
  `protocol/Dispositivos.kt`.
- **Migración V49:**
  - `nivel_valido` acepta `NAVEGADOR`;
  - una restricción nueva, `navegador_nunca_principal`, hace que **la base**
    impida que un navegador sea el principal aunque el código fallara.
- **`Repo.nivelParaVincular` y `Repo.nivelParaPrincipal`** reemplazan a
  `nivelPermitido` y a dos copias de la misma validación:
  - **Vincular** acepta `NAVEGADOR`. Es la única puerta por la que entra.
  - **Registro y recuperación** lo rechazan con 403: *"Un navegador no puede
    ser tu aparato principal. Crea la cuenta (o recupérala) en el teléfono y
    vincula el navegador desde ahí."*
- **Promover** un navegador a principal responde 409 con el motivo.
- **La sesión de un navegador dura 30 días**, y no 90. Se decide en
  `emitirToken` por el nivel del aparato, así que vale igual al vincular y al
  volver a entrar con la contraseña.

### `/web`, en el mismo origen que la API

`server/Web.kt` sirve los archivos de `WTFUCK_WEB_DIR`.

- **`/web` redirige a `/web/`.**
- **Sin la carpeta**, responde 404 con el motivo.
- **Cabeceras en cada archivo:**
  - `Content-Security-Policy`:
    - `default-src 'none'`
    - `script-src 'self' 'wasm-unsafe-eval'`
    - `style-src 'self'`
    - `connect-src 'self'`, más el almacén de adjuntos si está configurado
    - `frame-ancestors 'none'`
    - `base-uri 'none'`
    - `form-action 'none'`
  - `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
    `X-Frame-Options: DENY`.
  - `Cross-Origin-Opener-Policy` y `Cross-Origin-Resource-Policy` en
    `same-origin`.
  - `Permissions-Policy`: cámara y micrófono solo para la propia página, y
    nada de ubicación, pagos, USB ni Bluetooth.
- **Caché:** `assets/` es `immutable` por un año, y `index.html` va con
  `no-cache`.
- **El `.wasm`** sale como `application/wasm`, que es lo que el navegador
  necesita para compilarlo en streaming.

### Despliegue

- Los dos `docker-compose` de producción montan `./web-publicada` en
  `/app/web` (solo lectura) y fijan `WTFUCK_WEB_DIR`.
- `pruebas/arrancar-servidor.ps1` sirve `web/app/dist` si existe; si no,
  `pruebas/web-de-prueba`.

### App Android

En "Mis dispositivos", un navegador vinculado:

- aparece con un globo y el aviso *"Navegador web: sus claves viven en el
  navegador. Revócalo si no lo reconoces."*;
- **no** ofrece "Hacer principal".

## Pruebas

### `pruebas/web.mjs`: 41 de 41

| Grupo | Qué comprueba |
|---|---|
| Un navegador no crea cuentas | Registrarse con `NAVEGADOR` da 403, con un motivo que nombra al teléfono. |
| Duración de las sesiones | Un teléfono: 90 días, medido en la base. |
| Entra vinculándose | El código del principal sirve, el token funciona y la lista lo muestra como `NAVEGADOR`, no principal, con 30 días. |
| Nunca es el principal | No puede emitir códigos (403). El teléfono no lo puede promover (409). Un `UPDATE` directo en la base lo rechaza `navegador_nunca_principal`. |
| No recupera la cuenta | `recuperar-dispositivo` con `NAVEGADOR` da 403. |
| Vuelve a entrar | Con usuario, contraseña y el mismo `hardwareHash` entra a **su** aparato, de nuevo con 30 días. Desde otro navegador sin vincular, 403. |
| Revocar | Desde el teléfono, el token del navegador pasa a 401. |
| `/web` | Redirección, HTML, las cabeceras de seguridad una por una, la caché de `assets/` y de `index.html`, `application/wasm`, 404 en lo que no existe, y tres intentos de salir de la carpeta con `%2e%2e` (ninguno sirve nada). |

### En un navegador (Chromium, `http://localhost:8300/web`)

| Prueba | Resultado |
|---|---|
| `/web` | Redirige a `/web/`, y el script de `assets/` corre. |
| Script en línea | Bloqueado por la CSP. |
| `eval` | Bloqueado. |
| `WebAssembly.compileStreaming` | Compila. |
| WebSocket a `/v1/ws` (mismo origen) | Abre. |
| `fetch` a otro dominio | Bloqueado, con la violación `connect-src` registrada. |

### En el emulador

| Paso | Resultado |
|---|---|
| Un navegador vinculado a @xampl3 (fila insertada en la base local) | Aparece en "Mis dispositivos" con el globo, el aviso y solo "Revocar" (`1-navegador-en-mis-dispositivos.png`). |
| Tocar "Revocar" | *"Chrome en Windows" ya no tiene acceso*, y queda revocado en la base (`2-revocado.png`). |

### Regresión

- `node pruebas/correr.mjs`: **48 suites, 1846 pasan, 0 fallan**. Se omiten las 2 del bus, que necesitan Redis.
- JUnit del servidor y compilación de la app: sin fallos.

## Lo que queda para W2

- La aplicación en React: vincular, publicar claves con `web/cripto`, el
  WebSocket, chats y grupos de texto, y el almacén cifrado en IndexedDB.
- `despliegue/publicar-web.sh`: armar, calcular las huellas y subir a
  `web-publicada/`, igual que el APK.
- Que la propia web avise que su código lo entrega el servidor.
