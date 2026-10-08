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
