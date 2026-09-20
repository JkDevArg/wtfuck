# wtfuck — plan completo

Este documento responde al brief de "mensajería tipo WhatsApp + Telegram".
Se escribe **antes** de codificar, como pide el propio brief.

---

## 1. Dónde estamos

Ya funcionando y verificado en emuladores:

| Bloque del brief | Estado |
|---|---|
| Usuarios, sesiones, dispositivos | ✅ con vínculo a hardware (una cuenta por aparato) |
| Chats 1:1 y grupos | ✅ |
| Mensajería en tiempo real (WebSocket) | ✅ buzón con entrega offline |
| Perfil: foto, portada, nombre, bio | ✅ |
| Privacidad (4 ajustes, 3 niveles) | ✅ aplicada en servidor, 28 pruebas |
| Notificaciones push locales | ✅ aviso de "te agregaron a un grupo" |
| Búsqueda en lista de chats | ✅ |
| Arquitectura modular + WebSockets | ✅ |
| Cifrado en tránsito | ⚠️ TLS en producción; hoy local sin TLS |
| **E2EE** | ❌ pendiente (fase 4, `CifradorPlano` hoy) |

Lo que el brief pide y **no existe todavía** es la mayor parte: RBAC, canales,
multimedia, llamadas, moderación, panel admin, reacciones, respuestas, mensajes
fijados, temporales, y sincronización multi-dispositivo.

---

## 2. Tres conflictos con el brief que hay que resolver ahora

### 2.1 Teléfono/correo vs. solo usuario

El brief pide "número de teléfono o correo". wtfuck se diseñó **sin ninguno de
los dos**, por decisión previa. Eso tiene una consecuencia que ya apareció: sin
identificador externo **no se puede descubrir contactos** como hace WhatsApp.

**Propuesta:** correo institucional **opcional y verificado**
(`@cientifica.edu.pe` / `@sise.edu.pe`). Con eso:

- El usuario sigue siendo la identidad pública; el correo nunca se muestra.
- Se guarda **solo el hash** del correo, para poder cruzar sin almacenarlo.
- Habilita: recuperación de cuenta, descubrimiento dentro de EducaD, y
  "Mis contactos" de verdad.

Sin esto, "recuperación de cuenta" del brief es **imposible**: si se pierde la
contraseña y el dispositivo, no hay canal para probar quién eres.

### 2.2 "Mis contactos"

El brief usa "Mis contactos" en todos los ajustes de privacidad. Hoy wtfuck usa
**"conocidos"** (gente con conversación abierta). Al agregar 2.1, pasa a existir
una tabla `contacto` real y los tres niveles quedan como pide el brief:
`todos` / `contactos` / `nadie`, más `personalizado` (listas explícitas).

### 2.3 Llamadas de audio y video

No es un módulo más: es WebRTC + servidor SFU + TURN + señalización. Es un
proyecto del tamaño del resto junto. Va al final y con su propio plan.

---

## 3. Modelo de autorización (RBAC)

El corazón del brief. La regla es una sola:

> **El cliente nunca decide.** El servidor resuelve permisos en cada acción.

### Cadena de resolución

```
  ¿Sesión válida?               -> si no: 401
  ¿Usuario suspendido?          -> si si: 403
  ¿Bloqueo entre las partes?    -> si si: 403   (corta antes que todo lo demás)
  ¿Pertenece al recurso?        -> si no: 403
  ¿Restricción activa?          -> silenciado / expulsado: 403
  Override individual del permiso
        |-- DENEGAR  -> 403      (un deny explícito gana siempre)
        |-- PERMITIR -> continuar
        `-- sin override -> permisos del rol en ese recurso
  ¿Rol tiene el permiso?        -> si no: 403
  Ejecutar
  Registrar en auditoría si es acción administrativa
```

Orden deliberado: lo más barato y lo más restrictivo primero. Un bloqueo se
comprueba antes que los roles porque ningún rol debe poder saltárselo.

### Jerarquía

Cada rol tiene un número de `jerarquia`. Un usuario **no puede actuar sobre
alguien de jerarquía igual o mayor**. Eso resuelve de un golpe varios de los
casos límite del brief: un moderador no expulsa a un administrador, y un
administrador degradado pierde el poder en la siguiente petición porque los
permisos se resuelven por petición, nunca se cachean en el token.

### Entidades

```
permiso            catálogo de claves ("mensaje.enviar", "miembro.expulsar", …)
rol                plantilla de sistema, o rol propio de una conversación
rol_permiso        qué puede cada rol
participante.rol   qué rol tiene cada quien en cada conversación
permiso_override   excepción individual: permitir/denegar a una persona
restriccion        silenciado / expulsado, con vencimiento
bloqueo            usuario ↔ usuario
usuario.rol_global usuario | moderador | administrador | superadministrador
```

---

## 4. Módulos y orden de construcción

Cada módulo se cierra con pruebas antes de pasar al siguiente. El orden no es
arbitrario: cada uno depende del anterior.

| # | Módulo | Por qué va aquí |
|---|---|---|
| **A** | **RBAC + bloqueos + auditoría** | Todo lo demás se autoriza contra esto. Si va después, hay que reescribir cada endpoint |
| **B** | Grupos avanzados: config, invitaciones, solicitudes, roles propios | Primer consumidor real del RBAC |
| **C** | Mensajes ricos: responder, reaccionar, editar, borrar, reenviar, fijar, mencionar | Toca el modelo de mensaje; mejor antes de meter multimedia |
| **D** | Multimedia: imágenes, video, audio, documentos, notas de voz | Necesita almacenamiento de objetos + cifrado por archivo |
| **E** | **E2EE (libsignal)** | Va aquí y no antes: mueve el formato de mensaje. Hacerlo después de D obligaría a migrar los archivos ya subidos |
| **F** | Canales y comunidades | Reusa conversación + RBAC; añade suscriptores y publicación |
| **G** | Moderación y reportes | Necesita que exista todo lo reportable |
| **H** | Panel administrativo | Consume los datos de A–G |
| **I** | Multi-dispositivo | El más difícil después de E2EE. Hoy la regla es 1 dispositivo |
| **J** | Llamadas (WebRTC + SFU + TURN) | Subsistema aparte |

Mensajes temporales, almacenamiento y notificaciones granulares se reparten
dentro de C, D y H.

---

## 5. Catálogo de permisos

Claves en `recurso.accion`, para que se lean solas y se puedan agrupar.

```
mensaje.enviar            mensaje.responder        mensaje.reaccionar
mensaje.editar            mensaje.borrar_propio    mensaje.borrar_ajeno
mensaje.fijar             mensaje.reenviar         mensaje.enviar_enlace

media.imagen              media.video              media.audio
media.documento           media.nota_voz           media.sticker_gif

encuesta.crear            evento.crear

miembro.ver               miembro.invitar           miembro.aprobar
miembro.expulsar          miembro.bloquear          miembro.silenciar

grupo.ver_info            grupo.editar_info         grupo.cambiar_foto
grupo.cambiar_nombre      grupo.administrar_roles   grupo.eliminar

invitacion.crear          invitacion.revocar

canal.publicar            canal.comentar            canal.estadisticas
```

### Roles de sistema

| Rol | Jerarquía | Resumen |
|---|---|---|
| `propietario` | 100 | Todo, incluido eliminar el grupo. Uno solo |
| `administrador` | 80 | Todo menos eliminar el grupo y tocar al propietario |
| `moderador` | 50 | Expulsar, silenciar, borrar mensajes ajenos, fijar |
| `miembro` | 10 | Enviar, responder, reaccionar, multimedia |
| `restringido` | 5 | Solo leer |

Los roles propios de cada grupo se crean copiando uno de estos y ajustando
permisos, con jerarquía entre 1 y 79.

---

## 6. Casos límite que las pruebas deben cubrir

Salen del brief y son los que de verdad rompen un RBAC mal hecho:

1. Expulsado intentando enviar → 403
2. Bloqueado intentando abrir conversación → 403
3. Moderador intentando una acción de administrador → 403
4. Administrador **degradado en mitad de su sesión** → pierde el permiso en la
   siguiente petición (por eso los permisos nunca viajan en el token)
5. Alguien modificando un recurso ajeno → 403
6. **Sesión revocada** → 401 aunque el token no haya expirado
7. Moderador intentando expulsar a un administrador → 403 por jerarquía
8. Silenciado con vencimiento → 403 hasta que vence, luego pasa solo
9. Override `denegar` sobre alguien cuyo rol sí tiene el permiso → 403
10. El propietario no puede ser expulsado ni degradado por nadie

---

## 7. Lo que no voy a prometer

- **Llamadas** no entran en este ciclo. Son WebRTC + SFU + TURN.
- **Multi-dispositivo con E2EE** es donde WhatsApp tardó años. El esquema ya lo
  admite; la regla de negocio lo prohíbe hasta después de E.
- **`msg off` (malla P2P)** sigue sin poder verificarse en emulador.
- Un producto con todo el brief no sale en una tanda. Sale por módulos, con
  pruebas, que es exactamente lo que el propio brief pide al final.
