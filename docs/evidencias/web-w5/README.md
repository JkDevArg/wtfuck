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
