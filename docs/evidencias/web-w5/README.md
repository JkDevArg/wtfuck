# Versión web, W5: la web como app completa

Plan y razones: `docs/12-VERSION-WEB.md`, "W5 · Registrarse desde la web".
W5 va por partes; cada una con su commit.

---

# W5a: el servidor del registro web

## Qué cambió

- **`POST /v1/registro/correo`** (sin sesión). Manda el código de 6 dígitos.
  - Para `registro` exige primero una invitación web vigente: esta ruta no
    puede servir para mandarle correos a cualquiera desde nuestro remitente.
  - Para `recuperar` pide además el usuario.
  - Si el correo ya tiene cuenta (registro) o no casa con el usuario
    (recuperar), la respuesta es la misma y no sale ningún código: no es un
    oráculo. Al correo registrado le llega un aviso, no un código.
  - Límites por destino y por red, y 60 s entre códigos (los mismos que el SMS).
- **`POST /v1/registro`** con `hardwareNivel = NAVEGADOR`: exige la invitación
  web y el código del correo, canjeados dentro de la misma transacción que el
  alta. Si el alta falla, ni la invitación ni el código se gastan. El
  navegador queda de **principal**.
- **`/v1/registro/invitaciones-web`** (con sesión): crear, listar las mías y
  revocar las mías. Un solo uso, 7 días, 5 vigentes y 20 al mes por usuario.
  El staff crea las suyas desde el panel con `alcance = web`.
- **`POST /v1/cuenta/recuperar-dispositivo`** acepta `correo` + `codigoCorreo`
  en lugar de teléfono + SMS. Las otras puertas (código de recuperación, TOTP)
  no cambian. Solo por ese camino se llega a un navegador.
- **V52:** `usuario.correo_hash` (único), propósitos `registro_correo` y
  `recuperar_correo`, `invitacion_registro.alcance`, y fuera
  `navegador_nunca_principal`.
- **`Correo.kt`:** envío por API HTTP con plantilla, huella del correo con el
  pepper y prefijo `correo:`, forma, y lista de dominios desechables.
- **En producción sin servicio de correo,** el registro web queda cerrado
  (`registroWeb: false`) en vez de devolver el código en la respuesta.

## Pruebas

| Suite | Resultado |
|---|---|
| `pruebas/registro-web.mjs` (nueva) | **46 de 46** |
| — invitaciones | De un uso, 7 días, cupo de 5 (la sexta da 429), solo las propias se revocan (404 a otro), sin sesión 401, y una cuenta web también invita |
| — pedir el código | Sin invitación 400; inventada o revocada 403; correos sin arroba, sin dominio, con espacios, desechable y subdominio de desechable: 400; 60 s entre códigos (429) |
| — registro | Sin correo, con código equivocado o sin invitación: rechazado, **sin gastar** la invitación ni el código; con todo, el navegador es principal y de nivel NAVEGADOR; la invitación queda usada y ya no sirve |
| — oráculo | Con un correo ya registrado: 200 igual y sin código; registrar otra cuenta con él: rechazado |
| — la app | Sigue registrándose sin correo ni invitación; NAVEGADOR sin nada: rechazado |
| — recuperar | Con otro usuario: 200 sin código; con código de recuperación equivocado: 403; con todo: entra el navegador nuevo, el viejo queda revocado y su sesión cerrada; un código de REGISTRO no sirve para recuperar |
| `pruebas/web.mjs` | **48 de 48**. Las tres de W1 que decían "un navegador nunca es principal" se cambiaron a la regla nueva (sin invitación ni correo, rechazado) |
| `ajeno-lectura`, `cuentas`, `dispositivos`, `identidad`, `invitaciones`, `limite-registro`, `recuperacion` | Todas en verde (440 entre las ocho con `web`) |

## Lo que NO se probó

- **Un correo real:** en desarrollo no hay servicio y el código sale en la
  respuesta. La plantilla de Resend y la de Brevo están en
  `docs/09-DESPLIEGUE.md`, sin probar contra el servicio.
- **La puerta de Android:** sigue abierta a quien se haga pasar por la app.
  La cierra la Key Attestation (W5f).

---

# W5b: crear la cuenta, recuperarla e invitar, desde la web

## Qué cambió

- **La entrada de la web tiene tres pestañas:**
  - "Crear cuenta": solo si el servidor tiene `registroWeb`. Se abre sola
    cuando se llega con un enlace `?invitacion=`.
  - "Ya tengo la app": vincular, como hasta ahora.
  - "Recuperar".
- **Crear cuenta:**
  1. Invitación y correo, y llega el código.
  2. Código, usuario, contraseña (10 caracteres como mínimo, el mismo piso que
     la recuperación) y nombre del navegador.
  3. **Código de recuperación**, que se muestra una vez, encima de la app, y
     no se cierra sin marcar "Ya lo guardé". El servidor recibe solo su
     verificador.
- **En iPhone sin instalar,** un aviso pide primero "Agregar a pantalla de
  inicio": en una pestaña normal, Safari borra los datos de un sitio sin uso
  en 7 días, y ahí viven las claves.
- **Recuperar:**
  1. Usuario y correo, y llega el código.
  2. Código del correo, código de recuperación, contraseña nueva y TOTP si lo
     hay.

  Se avisa que la clave de seguridad cambia y que los mensajes viejos no
  vuelven.
- **"Invitar a alguien"** en la lista:
  - crea invitaciones web, con el cupo a la vista;
  - copia el enlace `…/web/?invitacion=CODIGO`;
  - revoca las propias.
- **`CodigoRecuperacion` pasó de la app al protocolo:** el formato y la
  derivación del verificador son contrato entre app, web y servidor. La web
  tiene su versión en `recuperacion.ts`, fijada con una prueba de oro que
  escribe Kotlin.

## Pruebas

| Qué | Resultado |
|---|---|
| vitest | **40 de 40**. Nuevas: el código de recuperación se normaliza y deriva igual que en Kotlin (verificador y clave de identidad de 3 códigos), las mismas 7 variantes valen o no, y 50 códigos generados en la web son válidos |
| **El oro encontró un defecto** | La primera versión TypeScript aceptaba `…86Y1` donde Kotlin no: los 4 bits de relleno del último símbolo tienen que ser cero, o casi la mitad de las erratas en el último carácter pasarían. Corregido antes del commit |
| App Android | Compila con `CodigoRecuperacion` desde el protocolo; `CodigoRecuperacionTest`, `CodigoEnLaPantallaTest` e `IdentidadEnLaCopiaTest`: **34 de 34** |
| De punta a punta, en el panel a 375 px (celular) | Con una invitación de un usuario de prueba, desde `127.0.0.1:8300/web/?invitacion=…` (otro origen, sin datos): la pestaña "Crear cuenta" se abre sola con la invitación puesta; correo, código, usuario y contraseña; aparece el código de recuperación y no deja seguir sin confirmar; la app queda "En línea". En la base: `correo_hash` y `recuperacion_hash` presentes, el navegador `NAVEGADOR` y principal, y el correo no está en claro en ninguna columna |

## Lo que falta (W5c)

- Una cuenta nueva no tiene chats y la web todavía no puede **empezar uno**:
  falta buscar personas, chats nuevos, grupos y solicitudes.
- "Desvincular" en una cuenta que nació en la web deja la cuenta sin
  aparatos: el texto tiene que decirlo (se vuelve con correo + código de
  recuperación).

---

# W5c: buscar personas y empezar chats

## Qué cambió

- **"Nuevo chat"** en la lista (`web/app/src/Contactos.tsx`):
  - **Buscar:** por directorio y por usuario exacto. El exacto encuentra
    también a quien no sale en el directorio por su privacidad, pero no a
    quien no existe.
  - **Nuevo chat:** tocar a alguien abre (o reutiliza) el chat directo.
  - **Nuevo grupo:** nombre y miembros elegidos de la búsqueda.

  Lo que se puede lo decide el servidor con las reglas de la app: privacidad,
  bloqueos y quién te agrega a grupos.
- **Solicitudes de mensaje:**
  - van en su propia sección arriba de los chats;
  - en el chat, el compositor se reemplaza por "Aceptar / Rechazar";
  - rechazar la saca de la lista.
- **"Desvincular"** pregunta antes si este navegador es el único aparato de
  la cuenta (cuenta nacida en la web). Si lo es, avisa que solo se vuelve con
  correo + código de recuperación y sin los mensajes.

## Pruebas, de punta a punta en el panel

| Qué | Resultado |
|---|---|
| Cuenta 1 (nacida en la web, `127.0.0.1:8300`, 375 px) busca a `@webpruebaadd727` y le escribe | Lo encuentra; el chat se crea y el mensaje sale |
| Cuenta 1 crea una invitación con "Invitar a alguien" | Código, vencimiento a 7 días, "Copiar enlace" y "Revocar" |
| **Cuenta 2** se crea en `localhost:8300` con el enlace de esa invitación | Pestaña "Crear cuenta" con la invitación puesta; queda creada, con su propio código de recuperación |
| Cuenta 2 busca a cuenta 1 y le escribe | Le llega a cuenta 1, descifrado, con 1 sin leer |
| Cuenta 1 contesta | Le llega a cuenta 2; en cuenta 1 su mensaje queda "leído" |
| Diseño a 375 × 812 | La app ocupa la pantalla entera, el compositor abajo y sin scroll horizontal (medido en la página; la captura del panel escalado engaña) |

Es el caso **iPhone con iPhone**: dos cuentas sin ninguna app instalada, con
Signal de punta a punta entre dos navegadores.

## Lo que NO se probó

- **El flujo de una solicitud en la web:** las dos cuentas tienen la
  privacidad por defecto, que deja escribir a cualquiera. Las reglas de
  solicitud las cubren `privacidad.mjs` y `privacidad-fina.mjs` en el
  servidor; la pantalla no se ejerció.
- **Crear un grupo desde la web:** solo la ruta. La suite `grupos.mjs` del
  servidor la cubre.

---

# W5d: la web instalable (PWA), para iPhone y Android

## Qué cambió

- **`manifest.webmanifest`:**
  - nombre "wtfuck";
  - se abre en `/web/` y en pantalla completa (`standalone`);
  - colores de la app;
  - iconos de 192 y 512, y uno `maskable` para Android.
- **Iconos:** se dibujan del mismo vector que el ícono de la app
  (`ic_launcher_foreground.xml`), con `web/app/generar-iconos.py` (Pillow).
  Los PNG van al repo.
- **`index.html`** con lo que iPhone lee al "Agregar a pantalla de inicio":
  - `apple-touch-icon`;
  - `apple-mobile-web-app-capable`;
  - barra de estado translúcida y el título.
  - Además, `viewport-fit=cover` y `theme-color`.
- **Zona segura:** con la barra translúcida y `viewport-fit=cover`, en iPhone
  la app llega hasta el notch y la barra de inicio. `env(safe-area-inset-*)`
  devuelve la cabecera, el compositor, la lista y los paneles a la zona
  segura. Sin notch vale 0.
- **Campos a 16 px en pantallas táctiles:** Safari hace zoom al enfocar un
  campo con letra menor, y la web usa 15 px.
- **El service worker se registra siempre al arrancar,** con o sin cuenta.
  Sigue sin interceptar peticiones (`web.mjs` lo comprueba).
- **El servidor** sirve el manifiesto como `application/manifest+json`.

## Pruebas

| Qué | Resultado |
|---|---|
| `pruebas/web.mjs` | **55 de 55**. Nuevas: manifiesto con su tipo; `standalone` en `/web/`; iconos 192, 512 y maskable, que existen y son PNG; el HTML enlaza el manifiesto y el `apple-touch-icon`; `viewport-fit=cover`; la CSP lee el manifiesto solo del mismo origen |
| En el panel | El manifiesto se lee (nombre, `standalone`, 3 iconos) y la app registra sola el service worker al arrancar, que queda `activated` en `/web/` |
| vitest y tipos | 40 de 40, sin errores |

**Un falso positivo, anotado para la próxima:** el panel del navegador no
dejaba registrar el service worker en `localhost:8300` ("An unknown error
occurred when fetching the script"), y el pedido ni llegaba al servidor. No era
el servidor: con el 8300 registrado en `.claude/launch.json` como vista previa
(sin comando), se registra y se activa. Con Vite (5180) siempre funcionó por
la misma razón.

## Lo que NO se probó

- **Instalarla en un iPhone o un Android de verdad.** El panel no instala
  PWAs. Lo que se comprueba es lo que cada sistema lee: el manifiesto,
  `apple-touch-icon` y las etiquetas `apple-mobile-web-app-*`.
- **Avisos con la app cerrada en iPhone:** iOS 16.4 o más nuevo, y solo
  instalada. Pendiente de un aparato real, igual que el Web Push de W4c.
- **La zona segura en un iPhone con notch:** sin aparato, solo el CSS.
