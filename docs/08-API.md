# La API, endpoint por endpoint

**123 rutas HTTP y un WebSocket.** Este documento se escribió leyendo
`Main.kt`, no de memoria: si una ruta está aquí, existe.

---

## Lo primero, porque cambia cómo se lee todo lo demás

**El servidor no puede leer los mensajes.** No es una promesa de política de
privacidad: es que recibe `bytea` opaco cifrado con libsignal y no tiene las
claves. Por eso no hay —y no puede haber— ningún endpoint de "buscar mensajes",
"exportar conversación" o "leer el chat de alguien". El panel de administración
tampoco los tiene.

Lo que el servidor sí sabe, porque lo necesita para autorizar y enrutar: quién
habla con quién, cuándo, y el tamaño de lo que se manda. Eso son los metadatos,
y están declarados aquí.

**Dos excepciones, las dos deliberadas:**

1. Las publicaciones de un **canal público** se guardan en claro. Un canal
   público no tiene secreto —cualquiera se suscribe y lee— y sin historial
   guardado quien se suscribe hoy no vería nada de ayer.
2. La **evidencia de una denuncia** llega en claro, porque la manda quien
   denuncia. Nadie más puede producirla: el servidor no puede descifrar y el
   denunciado no va a entregar la prueba contra sí mismo.

---

## Autenticación

Tres formas, y la diferencia importa:

| Cabecera | Qué abre | Cómo se obtiene |
|---|---|---|
| `Authorization: Bearer <token>` | Todo lo que puede hacer una persona | `POST /v1/sesion` |
| `Authorization: Consola <token>` | **Sólo** las rutas del panel | Desde el teléfono: `POST /v1/panel/consola` |
| ninguna | `/salud`, `/consola` (el HTML) | — |

Un token de **consola** no lista conversaciones, no manda mensajes, no lee el
perfil y no puede emitir otra consola. No es una regla de confianza: lo resuelve
una función distinta (`autenticarPanel`) y las rutas de mensajes ni la llaman.

**Un token de sesión vive 90 días** y se puede revocar (`/v1/sesiones`). Un 401
en cualquier ruta autenticada significa que ese token ya no sirve: el cliente
debe cerrar la sesión local, no reintentar.

### Errores

Todos con el mismo cuerpo:

```json
{ "motivo": "Texto para mostrarle a la persona, en español" }
```

| Código | Significado |
|---|---|
| 400 | La petición está mal formada o un valor está fuera de rango |
| 401 | Falta el token, o ya no sirve |
| 403 | Autenticado pero sin permiso, o contraseña incorrecta al reautenticar |
| 404 | No existe **o no se puede saber que existe** (ver más abajo) |
| 409 | Conflicto de estado: ya estaba hecho, o ya existe |
| 413 | El archivo pasa el límite de su clase |
| 429 | Límite de abuso. El motivo dice cuántos segundos esperar |

> **Por qué hay 404 donde parecería ir un 403.** Quien se ocultó de la búsqueda
> (`priv_busqueda`), un canal pendiente de aprobación y la cola de moderación
> para quien no es staff responden 404. Un 403 diría "esto existe pero no
> puedes", y eso ya es información: confirma que la persona está, que el canal
> se creó, que la cola existe.

---

## Registro y sesión

| Ruta | Qué hace |
|---|---|
| `POST /v1/registro` | Crea la cuenta **y** su primer dispositivo |
| `POST /v1/sesion` | Ingresa. Pide `hardwareHash` y, si la cuenta lo tiene, el código de dos pasos |

**Registrarse exige un `hardwareHash`**, y esa es la regla de "una cuenta por
dispositivo" del brief: el hash sale de una clave del Keystore con atestación,
así que reinstalar la app no cambia el hash y crear otra cuenta en el mismo
teléfono se rechaza. El techo de la técnica está declarado en
`docs/04-DEVICE-BINDING.md`: un restablecimiento de fábrica sí permite una
cuenta nueva.

Ingresar desde un teléfono **que no está vinculado** responde 403 diciendo que
hace falta vincularlo, no "contraseña incorrecta": son dos problemas distintos y
confundirlos manda a la gente a cambiar su contraseña por gusto.

---

## Mensajes

| Ruta | Qué hace |
|---|---|
| `POST /v1/mensajes` | Registra el **metadato** del mensaje. No lleva el cuerpo |
| `GET /v1/mensajes/{id}` | Metadatos de uno |
| `POST /v1/mensajes/{id}/retirar` | Retirar para todos |
| `POST /v1/mensajes/{id}/editar` | Editar |
| `POST /v1/mensajes/{id}/fijar` | Fijar o desfijar |
| `POST /v1/mensajes/reaccion` | Reaccionar |
| `GET /v1/conversaciones/{id}/fijados` | Los fijados |
| `GET /v1/conversaciones/{id}/leidos` | Cuáles de **mis** mensajes ya se leyeron |
| `PUT /v1/conversaciones/{id}/temporales` | Mensajes que se borran solos |

**El contenido no viaja por aquí: viaja por el WebSocket, cifrado y con una
copia por dispositivo destino.** Las acciones (retirar, editar, fijar) sí van
por HTTP, y no es incoherencia: el servidor tiene que autorizarlas, y para
autorizar necesita saber de quién era el mensaje. Si las acciones viajaran
cifradas, un administrador no podría borrar el mensaje de otro.

`GET .../leidos` existe porque el aviso de lectura viaja por el socket y se
pierde si el remitente no estaba conectado; al abrir el chat se pregunta una vez.

---

## El WebSocket

`GET /v1/ws?token=<token>` — es el transporte real de los mensajes.

**Cliente → servidor**

| Tipo | Para qué |
|---|---|
| `enviar` | Un sobre con **una copia cifrada por dispositivo** |
| `acuse` | Confirma recepción: el servidor **borra** el sobre del buzón |
| `acuse_evento` | Confirma un evento del sistema |
| `acuse_lectura` | "Leí estos mensajes" |
| `escribiendo` | "Estoy escribiendo". No se guarda en ninguna parte |
| `ping` | — |

**Servidor → cliente**

| Tipo | Para qué |
|---|---|
| `entrega` | Un sobre dirigido a este dispositivo |
| `aceptado` | El servidor lo aceptó (palomita gris). Puede traer `sinCopia` |
| `entregado` | Llegó al destinatario (palomita doble) |
| `leido` | Lo leyeron (palomita doble en tinta plena) |
| `escribiendo` | Alguien escribe en una conversación |
| `evento` | Cambio del sistema: te agregaron a un grupo, una llamada entrante, un canal aprobado… |
| `error` | Rechazo, con motivo |
| `pong` | — |

Dos detalles que explican el diseño:

- **El acuse de entrega borra el sobre.** Es lo que hace que el servidor no
  acumule historial. Por eso el acuse de **lectura** es un mensaje distinto: si
  fueran el mismo, o un mensaje sin leer se quedaría en el servidor para
  siempre, o "leído" aparecería en cuanto llega al teléfono, que es mentir.
- **`aceptado.sinCopia`** aparece cuando alguien entró a la conversación entre
  que el remitente pidió la lista de destinos y envió. El servidor no inventa
  una entrega que no puede hacer: dice qué dispositivos quedaron sin copia y el
  cliente completa.

---

## Claves (E2EE)

| Ruta | Qué hace |
|---|---|
| `PUT /v1/claves` | Publica identidad, prekey firmada, Kyber y prekeys de un solo uso |
| `GET /v1/claves/estado` | Cuántas prekeys quedan (el cliente repone solo) |
| `GET /v1/claves/dispositivo/{id}` | El paquete para abrir sesión con un dispositivo |
| `GET /v1/conversaciones/{id}/destinos` | **Todos** los dispositivos a los que hay que cifrar |

`destinos` es el endpoint que hace visible el precio del cifrado: el servidor ya
no puede copiar un cuerpo a N buzones, así que dice quién está en la
conversación y con qué aparatos, y el cliente produce una copia por cada uno.

---

## Adjuntos

| Ruta | Qué hace |
|---|---|
| `POST /v1/adjuntos` | **Reserva**: valida permiso, clase y cuota; devuelve una URL firmada |
| `POST /v1/adjuntos/{id}/confirmar` | Cierra la subida y **mide el tamaño real** |
| `GET /v1/adjuntos/{id}` | URL firmada de descarga |
| `GET /v1/adjuntos/uso` | Cuánto espacio ocupo |

Tres pasos y no uno porque el archivo **no pasa por el servidor**: se sube
directo al almacén con una URL firmada. Y se mide al confirmar porque el tamaño
declarado por el cliente es una intención, no un hecho.

El archivo va cifrado con una clave por archivo que viaja dentro del sobre.
Consecuencia declarada: **el servidor no puede validar que un JPEG sea un
JPEG**, porque ve ruido. Eso lo valida el cliente al descifrar.

### De quién cuelga un adjunto

Un adjunto pertenece **o** a una conversación (`conversacionId`) **o** a una
historia (`historiaId`), y exactamente a uno: hay un CHECK en la base que lo
exige —ver la migración V30—. De ahí sale su autorización, y son dos preguntas
distintas:

| Dueño | Reservar | Descargar |
|---|---|---|
| Conversación | permiso por clase (`media.imagen`, `media.video`…) | ser participante |
| Historia | ser **su autor** | ser el autor **o** estar en `historia_destino` |

En el camino de la historia se respetan también la caducidad y la retirada: una
historia vencida deja de existir y su archivo también. Y responde **404 y no
403**, porque confirmar que el archivo existe ya dice algo de una historia que
no te tocaba ver.

Sin la comprobación de audiencia, el archivo de una historia sería público para
cualquiera que adivinara su id, y no serviría de nada haber cifrado el sobre.

---

## Tipos de cuenta

| Ruta | Qué hace |
|---|---|
| `GET /v1/cuenta/tipo` | Qué es esta cuenta y qué puede. **Responde a todos** |
| `PUT /v1/cuenta/tipo` | Elegir el tipo propio. Solo `normal` y `empresa` |
| `PUT /v1/cuenta/empresa` | Guardar la ficha. Exige que la cuenta ya sea empresa |
| `PUT /v1/panel/cuentas/tipo` | Staff asigna cualquiera de los tres |
| `PUT /v1/panel/cuentas/{username}/verificar` | Staff pone o quita el distintivo |

Tres tipos excluyentes —`normal`, `desarrollador`, `empresa`— en una columna con
CHECK. Es un eje **distinto** de `staff_nivel`: un moderador puede tener ficha de
empresa, y una empresa no modera nada por serlo.

### Las tres autorizaciones no son la misma

| Operación | Quién | Por qué |
|---|---|---|
| `normal` ⇄ `empresa` | la propia persona | es una declaración sobre uno mismo |
| `desarrollador` | staff (80) | auto-otorgárselo es una escalada de privilegio con otro nombre |
| verificar | staff (80) | declarar no es lo mismo que haberlo comprobado |

La ficha **nace sin verificar** y **editarla borra la verificación**: si no,
bastaría verificarse con datos limpios y cambiar el nombre después. Sin esa
separación, la ficha de empresa sería una herramienta de suplantación con la
credibilidad de la plataforma detrás.

### En beta cerrada

`WTFUCK_CUENTAS_BETA` (por defecto, `WTFUCK_PROPIETARIO`). Quien no está en la
lista recibe **404** en las rutas de escritura, no un botón escondido: la ruta se
descubre leyendo el APK. 404 y no 403 porque un 403 confirma que existe.

`GET /v1/cuenta/tipo` sí responde a todos, con `puedeElegirTipo: false`. Cerrarla
obligaría al cliente a distinguir "no puedo" de "falló la red".

El sitio web se valida: tiene que ser `https://`. Ese texto acaba siendo un
enlace en el perfil de alguien.

---

## Privacidad

`GET`/`PUT /v1/perfil/privacidad` — nueve ajustes, **aplicados en el servidor**:

| Ajuste | Niveles | Nota |
|---|---|---|
| `foto`, `estado`, `nombre` | todos / conocidos / nadie | Con el nombre oculto se devuelve vacío y la app cae al `@usuario` |
| `escribe` | todos / conocidos | `nadie` no existe: una cuenta a la que nadie puede escribir no es mensajería |
| `grupos` | todos / conocidos / nadie | Quién me agrega a grupos |
| `llamadas` | todos / conocidos / nadie | Empieza en `conocidos`: una llamada suena e interrumpe |
| `busqueda` | todos / conocidos / nadie | Con `nadie`, quien ya habla contigo sigue alcanzándote |
| `ultimaVez` | todos / conocidos / nadie | **Recíproco** |
| `lectura` | sí / no | **Recíproco**, en las dos mitades |
| `escribiendo` | sí / no | Recíproco; lo aplican los clientes porque no se guarda nada |

> **"Conocidos" no es la agenda del teléfono.** wtfuck no la lee. Cuenta como
> conocida quien ya tiene una conversación directa contigo o a quien guardaste
> en tus contactos de la app.
>
> **Recíproco** significa que quien oculta un dato tampoco lo ve. Sin esa regla,
> el ajuste sería un espejo de una sola dirección: ver sin ser visto.

---

## Grupos, canales, llamadas, cuenta y dispositivos

Las rutas completas, agrupadas. El detalle de cada decisión está en
`docs/06-HOJA-DE-RUTA.md`, módulo por módulo.

**Conversaciones y grupos** — `GET /v1/conversaciones`,
`POST /v1/conversaciones/directa`, `POST /v1/conversaciones/grupo`,
`GET`/`PUT .../config`, `.../miembros`, `.../miembros/{usuario}/rol`,
`.../expulsar`, `.../silenciar`, `.../roles`, `.../invitaciones`,
`.../solicitudes`, `.../preferencias`, `.../salir`, `/v1/invitaciones/{codigo}`,
`/v1/bloqueos/{username}`.

**Canales** — `POST /v1/canales`, `GET /v1/canales/directorio` *(la lista
curada; es la puerta normal)*, `GET /v1/canales/buscar` *(el filtro)*,
`/v1/canales/alias/{alias}`, `GET`/`PUT /v1/canales/{id}`, `.../suscribir`,
`.../desuscribir`, `.../publicaciones`, `.../estadisticas`.

> Un canal nace **pendiente** y lo aprueba el propietario de la plataforma.
> Mientras lo esté no se lista, no se busca, **no responde ni por su alias
> exacto** y nadie se puede suscribir.

**Llamadas** — `POST /v1/llamadas`, `.../contestar`, `.../terminar`,
`/v1/llamadas/en-curso`, `/v1/llamadas/historial`, `/v1/llamadas/turn`.

> Aquí sólo viajan **metadatos**. El SDP y los candidatos ICE van dentro de
> sobres cifrados, porque el SDP lleva la huella del certificado DTLS y un
> servidor que pudiera cambiarla montaría dos llamadas y escucharía todo. **No
> existe ninguna columna para SDP**, y hay una prueba negativa que falla si
> alguien la agrega.

**Cuenta e identidad** — `/v1/cuenta/codigo`, `/v1/cuenta/telefono`,
`/v1/cuenta/recuperar`, `GET`/`PUT /v1/cuenta`, `/v1/cuenta/totp`,
`/v1/cuenta/eliminar`, `/v1/sesiones` *(listar, cerrar una, cerrar las otras)*,
`/v1/contactos`, `/v1/contactos/descubrir`.

> El teléfono se guarda como **HMAC con un pepper que sólo vive en el
> servidor**. Permite el cruce de agendas y una copia de la base no permite
> revertirlo.

**Dispositivos** — `GET /v1/dispositivos`, `/v1/dispositivos/codigo`,
`/v1/dispositivos/vincular`, `DELETE /v1/dispositivos/{id}`,
`.../principal`, `/v1/dispositivos/historial`.

> El historial **no lo tiene el servidor**: al vincular un aparato nuevo, es
> otro aparato tuyo el que le manda los mensajes recientes, cifrados. Si no hay
> ninguno encendido, el aparato nuevo empieza vacío y la app lo dice.

---

## Panel de administración

Todas aceptan sesión o token de consola (`autenticarPanel`), salvo las tres de
`/v1/panel/consola`, que exigen la sesión del teléfono.

| Ruta | Nivel mínimo |
|---|---|
| `GET /v1/panel/resumen` | moderador (50) |
| `GET /v1/moderacion/cola`, `.../denuncias/{id}`, `.../tomar`, `.../resolver` | moderador |

`GET /v1/moderacion/cola` acepta `estado`, `limite` (tope 200) y `despues`.

`despues` es el **id** de la última denuncia recibida, no su fecha. El servidor
resuelve la fecha exacta de esa fila, porque `creadaEn` viaja en milisegundos y
la columna es de microsegundos: un corte armado con la fecha del cliente vuelve
a incluir la misma fila. No es `OFFSET` a propósito —sobre una cola viva,
`OFFSET` se salta filas en cuanto alguien resuelve una de la página anterior—.
Un `despues` ilegible se ignora y se empieza por el principio; uno que apunta a
una denuncia que no existe **termina** la cola, para que un cliente que pagina
no entre en bucle.
| `GET /v1/panel/usuarios`, `.../suspender`, `.../restaurar`, `.../eventos` | moderador |
| `PUT /v1/panel/usuarios/{username}/staff` | administrador (80) |
| `GET /v1/panel/conversaciones`, `.../cerrar`, `.../reabrir` | administrador |
| `GET`/`PUT`/`DELETE /v1/panel/limites` | administrador |
| `GET /v1/panel/bitacora` | administrador |
| `GET /v1/panel/canales`, `POST /v1/panel/canales/{id}` | **propietario (100)** |
| `POST`/`GET`/`DELETE /v1/panel/consola` | moderador, con contraseña |

**Por qué cada nivel donde está:** un moderador decide sobre personas y
contenidos. Los límites de abuso y el cierre de conversaciones son
infraestructura de toda la plataforma, y la bitácora dice lo que hizo cada
moderador —la vigilancia entre pares del mismo nivel es una forma rápida de que
un equipo deje de escribir cosas—. Aprobar canales es del propietario porque es
quien responde por lo que se publica.

---

## Límites de abuso

Un 429 trae el motivo con los segundos que faltan. Los quince límites se
consultan y se ajustan en `/v1/panel/limites`; el valor de fábrica sigue en el
código con su explicación al lado, y la tabla sólo lo sobreescribe —borrar la
fila devuelve el valor probado, no "sin límite"—.

Dos que conviene entender porque su forma es una lección aprendida:

- **Ingreso**: se limitan los **fallos**, no los intentos, y con dos reglas —8
  por cuenta y 50 por IP cada 15 minutos—. Contar intentos por IP dejaba fuera a
  un campus entero detrás de un NAT, y contar los ingresos correctos no protege
  de nada.
- **Códigos por SMS**: el límite fuerte es **por destino** (10/hora), no por IP.
  Cinco códigos a cinco personas desde una red compartida es un martes normal;
  cinco al mismo número es acosar a alguien.

---

## Variables de entorno

| Variable | Por defecto | Para qué |
|---|---|---|
| `WTFUCK_PUERTO` | `8300` | En Windows, 8081-8180 suelen estar reservados por Hyper-V |
| `WTFUCK_DB_URL` / `_USER` / `_PASS` | `localhost:5433`, `wtfuck` | Postgres |
| `WTFUCK_S3_URL` / `_USER` / `_PASS` | `127.0.0.1:9000` | MinIO |
| `WTFUCK_PROPIETARIO` | — | Username que arranca como propietario |
| `WTFUCK_PEPPER_TELEFONO` | uno de desarrollo, **con advertencia en el log** | Obligatoria en producción |
| `WTFUCK_SMS_URL` / `_TOKEN` / `_CUERPO` / `_REMITENTE` | — | Sin ellas los códigos salen en el log marcados como de prueba |
| `WTFUCK_TURN_URL` / `_SECRETO` / `_VIDA_S` | — | Sin TURN, una llamada sólo conecta si ICE logra ruta directa |
| `WTFUCK_GIPHY_KEY` | — | Sin ella, la búsqueda de GIFs responde que no está configurada |
| `WTFUCK_PERMITIR_SOFTWARE_DEV` | `true` | **Poner en `false` en producción**: permite emuladores sin enclave seguro |

---

## Lo que esta API no tiene, a propósito

- **Buscar, leer o exportar mensajes.** El servidor no los tiene.
- **Un endpoint para "hazme administrador".** El primer propietario se siembra
  por variable de entorno: una ruta protegida por un secreto es la clase de cosa
  que termina abierta en producción.
- **Desactivar un límite.** El mínimo es 1 y la ventana máxima 24 horas.
- **Ingreso web.** Un navegador no tiene hardware que atestiguar; la consola se
  abre con un token que emite el teléfono.


---

## Módulo M · lo que cambió en el contrato

No hay rutas nuevas: el contenido con estructura viaja **dentro del sobre**, que
el servidor no puede leer. Lo que cambió son dos campos.

### `POST /v1/mensajes` · el campo `clase`

```json
{
  "mensajeId": "uuid-v4 generado por el cliente",
  "conversacionId": "uuid",
  "clase": ""
}
```

`clase` vale `""` (texto o adjunto, el caso normal), `"ubicacion"`,
`"contacto"`, `"encuesta"`, `"evento"` o `"voto"`. Cualquier otra cosa es un
**400**: un valor inventado tiene que fallar y no pasar de largo como si fuera
texto, o la declaración no serviría para autorizar nada.

Sólo dos clases piden permiso propio:

| `clase` | Permiso exigido | Lo tiene |
|---|---|---|
| `encuesta` | `encuesta.crear` | miembro y arriba |
| `evento` | `evento.crear` | moderador (50) y arriba |
| resto | sólo `mensaje.enviar` | — |

`mensaje.enviar` se comprueba **antes** que la clase, así que un silenciado no
puede colar contenido por la puerta de la encuesta.

> **Por qué el cliente declara esto y qué garantiza.** El brief pide permisos
> independientes para crear encuestas y eventos, y un permiso que el servidor no
> puede comprobar es un botón escondido. El servidor no puede comprobarlo
> mirando el sobre —está cifrado—, así que la clase viaja en el metadato en
> claro, donde ya viajaban el autor, la conversación y las menciones. Un cliente
> modificado puede declarar `""` y mandar una encuesta igual: la garantía es la
> misma que da `mensaje.enviar`, ni más ni menos.

### `entrega` por WebSocket · el campo `mensajeId`

```json
{
  "type": "entrega",
  "sobreId": "id de la FILA del buzón: es lo que se acusa",
  "mensajeId": "id del MENSAJE: el mismo para todos los destinos",
  "conversacionId": "uuid",
  "cuerpo": "base64 opaco",
  "tipo": 3
}
```

Son dos cosas y hubo que separarlas (V26). El id de la fila se deriva por
destino —`base` para el primero, `base + i` para los demás— porque con E2EE cada
destino recibe bytes propios y necesita su propia fila. Usar ese id como
identidad del mensaje hacía que en un grupo de tres el mismo mensaje quedara
guardado con un id distinto en cada teléfono, y con eso se rompía todo lo que
apunta a un mensaje por su id: los votos de una encuesta, el acuse de entrega
—que volvía al emisor con un id que no tenía— y responder o reaccionar.

El acuse (`type: "acuse"`) sigue llevando el **`sobreId`**, que es la fila a
borrar. El `entregado` que vuelve al emisor lleva el **`mensajeId`**, que es el
único id que el emisor conoce.

En una conversación directa los dos valores coinciden: hay un solo destino y
`derivar(base, 0) == base`.


---

## Módulo N · las tres rutas del push

### `GET /v1/push/config`

Lo que el cliente necesita para inicializar Firebase. Exige sesión.

```json
{
  "disponible": true,
  "proyectoId": "mi-proyecto",
  "appId": "1:123:android:abc",
  "apiKey": "AIza...",
  "remitenteId": "123456789"
}
```

`disponible: false` significa que el servidor no tiene push configurado, y el
cliente **no hace nada**: no es un error, es una función apagada. Los otros
campos vienen vacíos.

> **Nada de esto es secreto.** Identifican el proyecto y no autorizan nada por
> sí solos. El único secreto es la clave privada de la cuenta de servicio, y no
> sale del servidor: el servidor es el que firma.

> **Por qué existe esta ruta en vez de un `google-services.json` en el APK.**
> Así el push se habilita poniendo variables en el servidor, sin recompilar ni
> publicar una versión nueva, y no hay credenciales por entorno dentro del
> repositorio.

### `PUT /v1/push`

```json
{ "token": "<el token del servicio de mensajeria>", "proveedor": "fcm" }
```

`204`. El dispositivo sale de la **sesión**, no del cuerpo: si se confiara en el
cuerpo, cualquiera podría colgarle su token al aparato de otro y enterarse de
cuándo recibe mensajes. El aviso no lleva contenido, pero saber cuándo le llegan
mensajes a alguien ya es bastante.

Un token de menos de 10 o más de 4096 caracteres es `400`. Un proveedor que no
sea `fcm` es `400`. El **mismo** token registrado en otro aparato limpia el
anterior: pasa de verdad al reinstalar, porque Android puede devolver el token
que ya tenía, y dos filas con el mismo token despertarían al aparato equivocado.

### `DELETE /v1/push`

`204`. Lo llama la app al cerrar sesión, y hace falta de verdad: sin esto, un
teléfono del que alguien se fue seguiría recibiendo el aviso de que esa cuenta
tiene algo nuevo.

### Lo que el servidor le manda al proveedor

No es una ruta de esta API, pero es la parte que importa:

```json
{
  "message": {
    "token": "<token del aparato>",
    "data": { "w": "1" },
    "android": { "priority": "high" }
  }
}
```

Eso es **todo**. No hay bloque `notification` —así el sistema no dibuja nada por
su cuenta— y `data` tiene una clave que no significa nada. Ni texto, ni quién
escribe, ni conversación, ni ids. `priority: high` es lo que permite despertar
un proceso muerto; sin eso el aviso espera a la próxima ventana de
mantenimiento, que para una llamada es inservible.

El teléfono despierta, abre el socket, baja sus sobres y **recién ahí** —ya
descifrado— decide si hay notificación que publicar y con qué texto.

### `DispositivoInfo.recibeAvisos`

`GET /v1/dispositivos` ahora dice, por aparato, si tiene token registrado. Se
muestra porque explica una diferencia real de comportamiento: sin token, ese
aparato **no se entera de nada con la app cerrada**. Es el mismo criterio que
`tieneClaves`.
