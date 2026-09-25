# wtfuck — hoja de ruta

Paso a paso del brief completo. Cada paso tiene un entregable y una forma de
comprobar que quedó. **Ningún paso se da por hecho sin su prueba en verde.**

Convención: `[x]` hecho y verificado · `[ ]` pendiente · `[~]` parcial

---

## Leyenda de estado global

| Módulo | Pasos | Estado |
|---|---|---|
| 0 · Base | 6/6 | ✅ |
| A · RBAC | 5/5 | ✅ |
| B · Grupos avanzados | 9/9 | ✅ completo |
| C · Mensajes ricos | 10/10 | ✅ servidor + interfaz |
| D · Multimedia | 8/8 | ✅ servidor + interfaz |
| E · E2EE | 6/6 | ✅ completo |
| F · Canales | 6/6 | ✅ servidor + interfaz |
| G · Moderación | 6/6 | ✅ servidor + interfaz |
| H · Panel admin | 6/6 | ✅ cola, personas, canales, grupos, límites, bitácora |
| I · Identidad y cuenta | 7/7 | ✅ servidor + interfaz |
| J · Multi-dispositivo | 5/5 | ✅ servidor + interfaz |
| K · Llamadas | 7/8 | ✅ audio, vídeo, TURN, ventana flotante, servicio en primer plano · falta K.5 (SFU) |
| L · Interfaz completa | 8/8 | ✅ privacidad, presencia, notificaciones, consola web |
| M · Contenido con estructura | 6/6 | ✅ ubicación, contacto, encuesta, evento · buscar en el chat |
| N · Lo que faltaba del brief | 13/13 | ✅ push construido, tema claro, tablet, bus firmado, barridos §16 |
| O · Historias | 10/10 | ✅ texto, foto y vídeo · responder · quién la vio · 24 h |
| P · Tipos de cuenta | 6/6 | ✅ personal, desarrollador y empresa con ficha · **en beta cerrada** |

**1313 pruebas en verde**: 1175 de integración en 31 suites, 32 de JUnit en el
servidor y 221 en la app. Todos los módulos verificados de punta a punta en dos
emuladores; lo único abierto del plan es `K.5` (SFU), declarado fuera de alcance.

> **Estado del cifrado:** desde el módulo E el cuerpo de los mensajes es
> ilegible para el servidor. Comprobado leyendo `sobre_pendiente`
> directamente en Postgres: `convert_from(cuerpo, 'LATIN1')` falla con
> *invalid byte sequence*. Antes del módulo E esa misma consulta devolvía
> el texto del mensaje.

---

## Módulo 0 · Base ✅

- [x] 0.1 Esquema inicial (usuario, dispositivo, sesión, conversación, buzón) — *11 reglas probadas*
- [x] 0.2 Registro y login por usuario, con vínculo a hardware — *27 pruebas*
- [x] 0.3 Chat 1:1 en tiempo real por WebSocket
- [x] 0.4 Cola de salida y entrega offline
- [x] 0.5 Grupos básicos
- [x] 0.6 Perfil: foto, portada, nombre, estado — *11 pruebas*

## Módulo A · RBAC ✅

- [x] A.1 Catálogo de 34 permisos `recurso.accion`
- [x] A.2 Roles de sistema con jerarquía (100 → 5)
- [x] A.3 Overrides por persona, restricciones con vencimiento
- [x] A.4 Bloqueos usuario↔usuario y audit log
- [x] A.5 Motor `Autz` + los 10 casos límite del brief — *17 pruebas*

---

## Módulo B · Grupos avanzados ✅

*44 pruebas de servidor + administración verificada en emulador.*

> Brief §5, §6 · Primer consumidor real del RBAC.

- [x] B.1 Esquema V5: config de grupo, invitaciones, solicitudes, preferencias
- [x] B.2 Configuración del grupo (público/privado, modo anuncio, aprobación, media, enlaces, alias)
- [x] B.3 Gestión de miembros: cambiar rol, expulsar, vetar, silenciar con vencimiento
- [x] B.4 Roles personalizados: crear, editar, eliminar, asignar
- [x] B.5 Enlaces de invitación: crear, revocar, vista previa, usar
- [x] B.6 Solicitudes de ingreso: enviar, listar, aprobar, rechazar
- [x] B.7 Preferencias por persona: silenciar, archivar, fijar chat
- [x] B.8 UI: menú al mantener pulsado (silenciar, fijar, archivar, bloquear, eliminar) + sección de archivados
- [x] B.9 UI: pantalla de administración del grupo (config, invitaciones, solicitudes, miembros, roles)

**Verificación:** un moderador no cambia la configuración; un enlace vencido no
deja entrar; silenciar un grupo no lo silencia para los demás.

## Módulo C · Mensajes ricos ✅

*33 pruebas de servidor + flujo completo verificado en los dos emuladores.*

> Brief §8 · Toca el modelo de mensaje; va antes de multimedia.

- [x] C.1 Esquema: `responde_a`, `editado_en`, `borrado_en`, `fijado_en`, `reenviado_de`
- [x] C.2 Responder a un mensaje
- [x] C.3 Reacciones (tabla `reaccion`, agregadas por emoji)
- [x] C.4 Editar con marca visible de "editado"
- [x] C.5 Eliminar: para mí / **para todos** (evento de retracción)
- [x] C.6 Reenviar con marca de origen
- [x] C.7 Fijar mensajes
- [x] C.8 Menciones `@usuario` + notificación
- [x] C.9 Mensajes temporales con vencimiento configurable
- [x] C.10 UI: menú de mensaje, citas, reacciones, marca de editado, hueco de eliminado, banner de fijados

**Verificación:** "borrar para todos" quita el mensaje en el otro dispositivo;
quien no tiene `mensaje.borrar_ajeno` no borra lo de otros.

## Módulo D · Multimedia ✅

*Servidor y interfaz. Verificado con dos emuladores: foto, nota de voz,
documento y emojis enviados y recibidos.*

> Brief §8, §12

- [x] D.1 Almacenamiento de objetos (MinIO local / S3)
- [x] D.2 Subida con clave por archivo; solo la URL y la clave viajan en el mensaje
- [x] D.3 Imágenes y video con miniatura, visor a pantalla completa
- [x] D.4 Documentos: el servidor valida permiso, tamaño y cuota. **La firma del archivo la valida el cliente al descifrar** — con E2EE el servidor no puede leerla
- [x] D.5 Notas de voz: grabar, forma de onda, reproducir
- [x] D.6 Stickers y GIFs **por intermediario propio**, no contra el proveedor
- [x] D.7 Ajustes de almacenamiento: descarga automática, calidad, caché
- [x] D.8 Cuota por usuario y barrido de subidas abandonadas

### El orden del envío, y por qué es ese

```
fila local (con miniatura)  →  cifrar  →  reservar  →  subir  →  confirmar  →  cola
```

1. **La fila local primero.** La burbuja aparece al instante con su miniatura.
   Quien envía no espera la red para ver que su foto entró al chat.
2. **Reservar antes de subir** deja que el servidor rechace por permiso, clase,
   tamaño o cuota *sin* haber transferido un byte.
3. **El mensaje entra a la cola al final**, porque el sobre lleva el id del
   adjunto y su clave. Mandarlo antes sería mandar una referencia a algo que
   todavía no existe. Mientras dura la subida el mensaje queda en `SUBIENDO` y
   la cola lo saltea.

El enlace `adjunto → mensaje` se hace **al registrar el mensaje**, no al
confirmar el adjunto: al confirmar, ese mensaje aún no existe en el servidor.
El primer intento lo hacía al confirmar y reventaba con una violación de clave
ajena — lo encontró la prueba en los emuladores, no las pruebas de servidor.

### La miniatura viaja en el sobre

Unos pocos KB de JPEG a 240 px dentro del mensaje, no en el almacén. Quien
recibe ve la foto al instante, sin pedir nada y sin gastar datos; el archivo
completo se descarga solo si lo abre. En 3G es la diferencia entre un chat que
responde y uno que no.

### Descarga automática: lo que se baja solo y lo que no

| Clase | Por defecto | Motivo |
|---|---|---|
| Sticker | siempre | pesa nada y se consume al instante |
| Foto | sí, con WiFi | es lo que se mira |
| Nota de voz / audio | sí, con WiFi | pesa poco |
| Video | no | 40 MB sin pedirlos no |
| Documento | no | se abre cuando importa |

Con datos móviles no se descarga nada solo. Verificado en vivo: las dos fotos y
la nota de voz llegaron descargadas; el PDF quedó con su botón de descarga.

### Cifrado por archivo

AES-256-GCM, **una clave nueva por archivo**, que viaja dentro del sobre. El
almacén guarda bytes que no puede interpretar: tiene el candado y nunca la
llave. Al no reutilizarse nunca la clave, tampoco hay riesgo de repetir el
nonce, que es la forma clásica de romper GCM.

Se trabaja en *streaming* a disco y no en memoria: un video de 64 MB cifrado de
golpe son más de 128 MB de heap entre claro y cifrado, y eso mata el proceso en
un teléfono de gama media.

La prueba `media.mjs` reproduce el formato exacto del cliente desde Node y
comprueba el ciclo completo contra MinIO, incluido que **cambiar un solo byte
hace que GCM rechace el archivo**.

### GIFs: por qué pasan por nuestro servidor

La app no habla con el proveedor de GIFs. Si lo hiciera, este vería qué busca
cada persona —"renuncia", "hospital", "te extraño"— y la IP de quien busca; y al
mostrarse la vista previa, también la IP de **quien recibe** el mensaje, sin que
esa persona haya pedido nada.

El costo es que nuestro servidor sí ve las búsquedas. Es un costo real y menor:
ese servidor ya sabe que existe un adjunto, y a diferencia del proveedor es
nuestro, con nuestras políticas de retención. La pantalla lo dice en una línea,
porque quien manda un GIF tiene derecho a saberlo.

**Configurado el 2026-09-24.** La clave vive en `.env` en la raíz —que está
en `.gitignore`— y la carga `pruebas/arrancar-servidor.ps1`. Hay plantilla en
`.env.ejemplo`. Una variable que ya esté en el entorno gana sobre el archivo,
así que en producción no hace falta un `.env`: la inyecta el despliegue.

> Los valores de desarrollo que **no protegen nada** —el secreto del bus, el
> pepper de pruebas, el TURN local— siguen a la vista dentro del script, y eso
> es deliberado. Una clave real de un proveedor sí protege algo, aunque sea
> poco, y **una clave que entra al historial de git sigue ahí aunque después se
> borre el archivo**.

Sin la variable el buscador sigue respondiendo "no está configurado" en vez de
fallar, y el resto de multimedia funciona igual.

**Y faltaba la atribución a GIPHY**, que sus términos exigen. No se había
notado porque sin clave no se veía ningún GIF: el defecto estaba desde que se
construyó el buscador y sólo se pudo ver el día que empezó a funcionar. Está
en el pie del panel, junto a la nota de privacidad.

> Queda una cosa por comprobar contra sus términos actuales: si exigen su
> **marca oficial** (el logo que ellos distribuyen) o si alcanza con el texto.
> Puse texto, que es lo que puedo poner sin descargar arte de terceros.

### Defecto de fondo encontrado al probar

`sobre_pendiente` no guardaba la hora del autor: al entregar, el servidor usaba
la hora en que *él* había recibido el sobre. Eso rompía justamente `msg off` —un
mensaje escrito a las 8am y entregado a las 6pm se veía como de las 6pm, y
quedaba mal ordenado. Se detectó porque el emisor mostraba 03:16 y el receptor
03:21 para el mismo mensaje. Corregido en `V8__hora_origen.sql`, con las dos
horas conservadas porque responden preguntas distintas: `creado_en_origen` es la
que se muestra, `recibido_en` es para retención y barrido. Cubierto por
`msgoff.mjs`.

## Módulo E · E2EE ✅

*Cifrado real con libsignal 0.86.5 (PQXDH + Double Ratchet + Sender Keys),
verificado entre dos emuladores leyendo la base del servidor.*

> Brief §9 · El más delicado.

- [x] E.1 Publicación y consumo de prekeys — 23 pruebas en `claves.mjs`
- [x] E.2 `CifradorSignal` con libsignal (PQXDH + Double Ratchet)
- [x] E.3 Almacén de sesiones criptográficas en la base local cifrada
- [x] E.4 Sender Keys para grupos — 13 pruebas en `copias.mjs`
- [x] E.5 Verificación de identidad: huella de 60 dígitos y QR
- [x] E.6 Aviso de "cambió la clave de seguridad"

### E.5 y E.6, verificados

Los dos emuladores calcularon **la misma huella**, cada uno desde su propio
material de claves local:

```
71752  92578  32289  00277
13438  71688  88998  92794
58773  89206  65086  99150
```

Que coincidan es lo que hace que compararlas signifique algo. Marcar como
verificado persiste al reiniciar la app, y el chip pasa de "Cifrado, sin
verificar" (ámbar) a "Verificado" (cian).

El QR se dibuja con el núcleo de ZXing, claro sobre oscuro. **Escanear todavía
no está**: hoy el QR sirve para comparar a ojo o fotografiar. El flujo que de
verdad funciona entre dos personas —leerse los dígitos por teléfono— está
completo, y decirlo es mejor que poner un botón de cámara que no hace nada.

El aviso de E.6 va **dentro del chat**, en coral, sobre la lista de mensajes y
tocable, no escondido en una pantalla que nadie abre. El texto no exagera ni
minimiza: *"Suele pasar porque @X reinstaló la app o cambió de teléfono. Pero
también es lo que se vería si alguien se estuviera poniendo en medio."*

Del lado del servidor hay 6 pruebas nuevas en `claves.mjs`: republicar otra
identidad con el mismo dispositivo se acepta, y `/destinos` y el paquete de
claves sirven **la identidad nueva** —que es exactamente lo que el cliente
necesita para detectar el cambio.

### Verificación cumplida

El criterio era: *el cuerpo en `sobre_pendiente` deja de ser legible para el
administrador de la base*. Medido con la app real, con la destinataria offline
para que el sobre quedara en el buzón:

```
 tipo_cifrado | bytes |   de    |  para
--------------+-------+---------+---------
            3 |  1921 | joaquin | tatiana

SELECT convert_from(cuerpo,'LATIN1') ...
ERROR:  invalid byte sequence for encoding "LATIN1": 0x00
```

`tipo_cifrado = 3` es un *PreKeySignalMessage*, el que abre la sesión. Los 1921
bytes para un texto corto son el encapsulado Kyber-1024 viajando con el
handshake. Al reconectar, la destinataria lo abrió y leyó el texto.

### El cambio de fondo: ya no hay UN cuerpo

Esto es lo que E2EE le cuesta al transporte, y conviene tenerlo claro:

| | Antes | Con E2EE |
|---|---|---|
| Qué manda el cliente | un cuerpo | **una copia por dispositivo** |
| Qué hace el servidor | copiar a N buzones | enrutar cada copia a su buzón |
| Puede el servidor rehacer una copia | sí | **no**, no tiene las claves |

De ahí salen tres cosas nuevas:

- `GET /v1/conversaciones/{id}/destinos` — el remitente pide a quién cifrar
  *antes* de cifrar. Se cachea 30 s: si alguien sale del grupo, seguir cifrando
  para su dispositivo sería entregarle mensajes que ya no le corresponden.
- `Bajada.Aceptado.sinCopia` — si alguien entró a la conversación entre que se
  pidió la lista y se envió, el servidor **dice qué copias faltaron** en vez de
  inventar una entrega que no puede hacer. El cliente refresca y completa; el
  id derivado por destino evita duplicados.
- Los destinos legítimos los decide el servidor. Una copia dirigida a un
  dispositivo que no está en la conversación se descarta: si no, el canal
  serviría para meter bytes en el buzón de un desconocido.

### E.4 · Clave de emisor en grupos

Medido en la base del servidor, con la destinataria desconectada:

| Camino | Tipo | Bytes |
|---|---|---|
| Primer mensaje 1:1 (abre sesión, PQXDH) | 3 | 1921 |
| Mensaje de grupo con clave de emisor | **7** | **253** |

Y lo que de verdad importa: esos 253 bytes son **uno solo para todo el grupo**.
Antes de E.4 un grupo de veinte subía veinte cuerpos; ahora sube uno y una
lista de veinte destinos. Por eso `CopiaCifrada` pasó de tener un
`destinoDispositivo` a tener `destinos: List<String>`.

**La clave de emisor viaja pegada al mensaje.** Para abrir los mensajes de
grupo de alguien hay que tener antes su clave, y esa clave solo puede viajar
cifrada por pares. Lo obvio sería mandar dos sobres —primero la clave, después
el mensaje— pero entonces habría que garantizar que llegan en ese orden, y con
un buzón que reentrega eso no se puede garantizar. Así que van juntos en un
`Carga.ConClaveGrupo`: quien recibe procesa la clave y abre el interior. No hay
orden que respetar porque no hay dos cosas.

**La clave cuenta como repartida cuando el servidor acepta, no antes.** Si se
marcara al cifrar y el envío fallara, el mensaje siguiente iría sin la clave
—porque constaría como entregada— y el otro lado no podría abrir nada nunca.
De ahí `Cifrador.confirmarEnvio`.

**Rotación al salir alguien.** Quien se fue del grupo se quedó con la clave
anterior, y con ella podría seguir abriendo lo que se hable de ahora en
adelante. Así que al detectar que alguien de `repartidaA` ya no está entre los
destinos, se estrena un `distributionId`. Es la única desventaja real de las
claves de emisor frente al cifrado por pares, y se paga ahí.

> **Ventana conocida:** la lista de destinos se cachea 30 s. Si alguien sale y
> se envía dentro de esa ventana, la rotación ocurre en el envío siguiente. No
> es un agujero: el servidor ya descarta la copia dirigida a quien no pertenece,
> así que quien salió no recibe nada mientras tanto.

Si crear o usar la clave de emisor falla por cualquier motivo, se cae a cifrado
por pares en vez de no enviar. Un grupo que funciona peor es mejor que un grupo
que no funciona.

### Decisiones que conviene no re-discutir

- **La dirección criptográfica es el DISPOSITIVO, no la persona.** El `name` del
  `SignalProtocolAddress` es el id del dispositivo y el `deviceId` siempre 1.
  Suena raro viniendo de Signal, pero encaja: un usuario por dispositivo es la
  regla del proyecto, los ids son UUID y no enteros, y el día que haya varios
  dispositivos por persona (módulo J) cada uno ya es una sesión aparte.
- **Confianza al primer uso (TOFU).** Rechazar una identidad desconocida haría
  que la app pareciera rota. Se confía y se AVISA, como Signal y WhatsApp.
- **Las prekeys de un solo uso se borran al entregarse**, no se marcan. Una
  consumida no sirve para nada, y la cuenta de consumidas sería, en la
  práctica, la cuenta de con cuánta gente distinta empezó a hablar alguien.
- **El servidor no valida las firmas** del material que reparte, y no debe
  poder: validarlas exigiría entender lo que reparte. Eso lo comprueba el
  cliente al procesar el paquete.
- **Un cuerpo que no se puede descifrar NO se acusa.** Acusar lo borraría del
  servidor y se perdería para siempre; se deja pendiente y se reintenta.

### El límite honesto

El servidor decide *qué clave entrega*. Uno malicioso podría entregar la suya y
ponerse en medio. Contra eso no hay criptografía que alcance: hay que comparar
la huella fuera del canal. Por eso existen la huella numérica y el registro de
cambios de identidad, y por eso E.5 y E.6 no son adornos.

### Defecto de entrega duplicada (corregido)

Al probar E2EE en dos emuladores apareció un defecto que venía de antes y que
el cifrado hizo visible: **un mensaje contaba 2 en el globo de no leídos**.

Tres causas encadenadas:

1. `Repositorio.iniciar()` se llama desde `WtfuckApp.onCreate` **y** desde
   `MainActivity.onStart`, o sea en cada vuelta al primer plano. Cada llamada
   lanzaba otro colector sobre el mismo `SharedFlow`.
2. `Socket.abrir()` creaba un WebSocket nuevo sin cerrar el anterior.
3. `sumarNoLeido` se llamaba sin comprobar si el `INSERT` había insertado algo.

El cifrado lo delató: el duplicado intentaba abrir un sobre cuyo ratchet ya
había avanzado y aparecía `message with old counter 1 / 0` en los registros.
Sin E2EE el duplicado se descifraba igual y solo se veía el contador mal.

La corrección de fondo es la tercera: **el contador sube solo si el mensaje de
verdad es nuevo**. Room devuelve -1 cuando `IGNORE` descartó la fila, y eso pasa
por diseño, no por accidente: el buzón reentrega todo lo que no se acusó.

### Costo de tamaño, medido

libsignal trae su motor en Rust compilado para cuatro ABIs y el AAR pesa 128 MB.
Sin tocar nada, el APK salió en **503 MB**. Con exclusiones y un solo ABI queda
en **95 MB** de depuración:

| Qué se saca | Cuánto |
|---|---|
| `libsignal_jni_testing.so` (×3 ABI) | 229 MB |
| Binarios de escritorio (`.dylib`, `.dll`) | 121 MB |
| ABIs que no se usan en desarrollo | ~135 MB |

También hace falta `isCoreLibraryDesugaringEnabled`: libsignal usa
`java.time.Instant`, y sin desugaring habría que subir el minSdk y dejar afuera
teléfonos que sí pueden correr la app.


### E.7 · Una huella por APARATO, y la pantalla ya no lo esconde ✅

La huella de Signal se calcula entre dos **claves de identidad**, y cada
dispositivo tiene la suya: quien tiene teléfono y tablet tiene dos huellas
distintas, las dos legítimas. Hasta el módulo J daba igual porque había un
aparato por cuenta. Después, no: la pantalla mostraba la huella del **primer
dispositivo de la lista** y, al verificarla, declaraba la conversación
"verificado" mientras el otro aparato seguía sin comprobar.

Era una respuesta tranquilizadora y falsa, que es lo peor que puede dar una
pantalla de seguridad. Ahora hay una tarjeta por aparato, con su nombre —el que
puso su dueño al vincularlo, porque nadie verifica un UUID— y el resumen dice
**cuántos de cuántos**: "Verificado 1 de 2 aparatos". La tentación es redondear
hacia el verde; es exactamente lo que esta pantalla no puede hacer.

**En grupos se agrupa por persona.** Verificar es comparar números *con
alguien*, y una lista plana de ocho tarjetas no dice con quién se compara cada
una. Va con su aviso: no hay un número del grupo entero, porque no existe. Y de
paso se cumplió una promesa que la propia pantalla hacía y nadie cumplía —el
texto decía "en grupos se verifica desde la lista de miembros" y no había forma
de llegar ahí desde un grupo—.

Un aparato **sin sesión todavía** dice que no hay código que comparar en lugar
de ofrecer un botón que marcaría como verificado algo que nadie miró.


## Módulo F · Canales ✅

*Servidor e interfaz. Verificado en dos emuladores: crear, publicar, buscar por
alias, previsualizar sin seguir, suscribirse.*

> Brief §7

- [x] F.1 Tipo `canal` en conversación + suscriptores
- [x] F.2 Publicar (unidireccional) con permisos propios
- [x] F.3 Comentarios opcionales
- [x] F.4 Reacciones en publicaciones
- [x] F.5 Alias público y descubrimiento
- [x] F.6 Estadísticas del canal
- [x] F.7 **Aprobación del dueño de la plataforma + directorio** *(sección
      aparte más abajo)*

74 pruebas en `canales.mjs`.

### Un canal no es un grupo con menos permisos

Es otra cosa: **publicar y leer son papeles distintos**. De ahí sale todo lo
demás sin necesidad de inventar un "modo canal":

- Publicar exige `canal.publicar`. El rol `suscriptor` (jerarquía 8) no lo
  tiene, y con eso el canal ya es unidireccional.
- Comentar exige `canal.comentar` **y** que el canal tenga los comentarios
  encendidos. Lo segundo es lo que hace que apagarlos sirva de algo: si
  dependiera solo del permiso, habría que tocarle el rol a cada suscriptor.
- Lo mismo con las reacciones.
- La interfaz no muestra un campo de texto a quien no puede publicar. Enseñar
  un campo que el servidor va a rechazar es una promesa falsa.

Un canal reusa `participante`, `rol`, `permiso`, `mensaje_meta`, `reaccion` y
`adjunto` sin cambiar nada de eso. Lo único propio son dos cosas: quién habla,
y cómo se lo encuentra.

### La excepción declarada: el servidor guarda las publicaciones públicas

Hasta el módulo F la regla era absoluta: el servidor guarda metadatos y jamás
contenido. Un canal **público** la rompe, a propósito:

1. **No tiene secreto que guardar.** Cualquiera se suscribe y lee; el "extremo
   a extremo" no protege nada cuando uno de los extremos es todo el mundo.
2. **Un canal sin historial no es un canal.** Quien se suscribe hoy espera ver
   lo de ayer, y con E2EE puro no hay nadie que pueda haberlo guardado: el
   remitente no puede cifrar para un suscriptor que todavía no existe.
3. **No escala.** Diez mil suscriptores serían diez mil copias por publicación.

Es lo mismo que hacen Telegram y WhatsApp, y por los mismos motivos. Lo que
**no** se hace es esconderlo:

- La tabla se llama `publicacion_contenido` y tiene la razón escrita encima.
- La ruta que recibe texto en claro es `POST /v1/canales/{id}/publicaciones`,
  aparte y con ese nombre, para que la excepción se vea en el mapa de rutas en
  vez de esconderse dentro de algo que normalmente no guarda contenido.
- `ConfigCanal.cifrado` viaja en la respuesta, y la pantalla del canal muestra
  un aviso ámbar permanente: *"Canal público: el contenido no va cifrado de
  extremo a extremo"*.
- El diálogo de creación lo dice **antes** de elegir, no después.

Un canal **privado** sigue cifrado como un grupo. El precio, declarado: no
puede mostrar historial a quien se suscriba después, porque el servidor no lo
tiene. La pantalla lo explica en vez de mostrar una lista vacía sin motivo.

### Un canal público no reparte sobres

Ahí está lo que lo hace escalar. Al publicar, el servidor guarda el contenido y
emite **un** aviso (`canal_publicacion`); cada cliente se trae el historial
cuando quiere. No hay una fila de buzón por dispositivo.

Eso obligó a que `publicaciones` sea legible **sin estar suscrito**, y no es
una concesión: es lo que significa público. Sin eso, descubrir un canal serviría
para ver su nombre y nada más, y habría que seguirlo a ciegas para saber de qué
trata. Se detectó probando en el emulador: la pantalla decía "este canal todavía
no tiene publicaciones" cuando sí las tenía.

### Restricciones que impone la base, no el código

- Un canal público **sin alias** es imposible: `CHECK (NOT publico OR alias IS
  NOT NULL)`. Un canal público que no se puede encontrar es privado con otro
  nombre.
- El alias cumple `^[a-z0-9_]{4,32}$` y es único.
- Los tipos de evento siguen siendo una lista cerrada en la base. Agregar
  `canal_publicacion` requirió una migración (V11), y esa molestia es
  deliberada: un tipo de evento es contrato con todos los clientes, y la base es
  donde ese contrato no se puede saltear por descuido.

### Lo que no hay, y por qué

**Cuántos leyeron cada publicación.** Saberlo exigiría que cada suscriptor
reporte lectura por publicación: es un problema de privacidad antes que de
escala. Las estadísticas cuentan lo que ya está guardado por otros motivos
—suscriptores, altas de la semana, publicaciones, comentarios, reacciones— y la
pantalla dice explícitamente que no hay cuenta de lecturas.


## Módulo F.7 · Los canales los aprueba el dueño de la plataforma ✅

*Servidor, interfaz y cola de revisión. Verificado en dos emuladores: canal
creado → pendiente e invisible → aprobado desde el panel → aparece en el
directorio, y la pantalla de quien lo creó se actualiza sola.*

### Por qué un canal necesita permiso y un grupo no

Un grupo es privado: existe entre quienes se invitaron. Un canal público es lo
contrario —una tribuna abierta a toda la plataforma, con historial que el
servidor **sí guarda en claro** (ver V10)— y eso lo convierte en la superficie
con más alcance y menos control de todo el sistema. Quien responde por lo que
se publica ahí es el dueño de la plataforma, así que es el dueño quien decide
qué canales existen.

### El cambio de fondo: la lista reemplaza al buscador

Antes un canal se encontraba escribiendo en un buscador, lo que obliga a
**adivinar el nombre de algo que no sabés que existe**; si esa es la única
puerta, un canal nuevo es invisible salvo para quien ya sabe que está. Ahora
hay un **directorio**: la lista de los canales aprobados, sin escribir nada. La
búsqueda pasa a ser lo que siempre debió ser —un filtro sobre esa lista para
cuando sea larga.

Y sólo hay lista **porque hay aprobación**: un directorio de todo lo que
cualquiera creó hace cinco minutos no sería un directorio, sería un basurero
con el logo de la plataforma encima.

### Qué significa "pendiente", exactamente

No aparece en el directorio, no aparece en la búsqueda, **no responde ni por su
alias exacto** —un 404, el mismo que un canal inexistente: si dijera "existe
pero no está aprobado", el alias se volvería un oráculo para enterarse de lo
que hay en la cola del dueño— y nadie se puede suscribir. Quien lo creó sí
entra y lo prepara: bloquearlo del todo sólo agregaría una espera sin ningún
beneficio.

La comprobación está en **dos lugares a propósito**: el filtro del directorio
decide lo que se ve, y la comprobación al suscribirse decide lo que se puede
hacer. Quien tenga el id de un canal pendiente porque se lo pasaron tampoco
entra.

### También los privados, y por qué

Un canal privado no aparece en ninguna lista, así que podría auto-aprobarse sin
dañar a nadie. Se decidió que no: tener una regla —"los canales los aprueba el
dueño"— es más fácil de sostener y de explicar que tener una regla con una
excepción que depende de un booleano **que además se puede cambiar después** con
`PUT /v1/canales/{id}`. Un canal privado aprobado que luego se hace público no
se cuela por esa grieta.

### Propietario, no administrador

Aprobar exige nivel **100**. Un administrador (80) recibe el mismo 404 que
cualquiera: ni ve la cola ni puede decidir. Es lo que se pidió —el dueño decide
qué canales existen— y delegarlo después es cambiar una constante, que está
sola y a la vista por eso.

Rechazar **exige motivo**, y lo exige la base con un CHECK además del servidor.
Un rechazo sin motivo es un muro sin salida: quien creó el canal no tiene nada
que corregir ni a qué apelar.

### Detalles de la migración

Los canales que ya existían quedan **aprobados**: la regla es para lo que
venga, no un castigo retroactivo. El truco son dos sentencias —crear la columna
con `DEFAULT 'aprobado'` y cambiar el default a `'pendiente'` después—, que
consigue las dos cosas sin un UPDATE aparte.


## Módulo G · Moderación ✅

*Servidor e interfaz. Verificado en dos emuladores: denunciar un mensaje
entregando su texto, revisar la denuncia con la prueba delante, advertir, y ver
la advertencia del otro lado.*

> Brief §13

- [x] G.1 Reportes: usuario, mensaje, grupo, canal
- [x] G.2 Cola de revisión para moderadores
- [x] G.3 Acciones: advertir, restringir, suspender
- [x] G.4 Sistema de advertencias acumulativas
- [x] G.5 Antispam y límite de frecuencia
- [x] G.6 Registro de eventos de seguridad

99 pruebas en `moderacion.mjs`.

### El problema de fondo: no se puede moderar lo que no se puede leer

Todo el módulo sale de esta frase. Una denuncia sobre un mensaje le llega al
moderador como un uuid y unos bytes opacos: no hay nada que revisar, y decidir
creyéndole al que denunció primero no es moderar. Las salidas posibles eran tres:

1. **No moderar contenido**, lo que hace Signal. Honesto, y deja a la víctima de
   acoso sin más herramienta que bloquear.
2. **Descifrar en el servidor.** Rompe el producto entero.
3. **Que el texto lo entregue quien denuncia.** Su teléfono ya lo descifró y es
   el único que puede.

Se eligió la 3, que es lo que hace WhatsApp. Es la **segunda excepción
declarada** al buzón tonto, después de los canales públicos, y la regla que la
vuelve defendible es que **el usuario lo sepa antes de denunciar**: entregar el
texto es parte de denunciar, no un efecto secundario. Denunciar sin saberlo
sería un engaño, aunque el texto haga falta.

Lo que se hace para que no sea un agujero encubierto:

- La tabla se llama `denuncia_evidencia` y tiene la razón escrita encima.
- El texto solo llega por `POST /v1/moderacion/denuncias`, nunca como efecto de
  otra cosa.
- La pantalla muestra un aviso ámbar **antes** de confirmar, y el interruptor de
  "incluir los mensajes anteriores" arranca **apagado**: entregar más contexto
  casi siempre ayuda, pero es decisión del denunciante, no una casilla
  premarcada.
- Va una ventana de 6 mensajes, no la conversación: más allá de eso deja de ser
  una prueba y se vuelve una filtración, y un moderador con cien mensajes
  delante no lee ninguno.
- **Se borra al cerrar el caso.** Sirvió para decidir, no para archivar.

### Dos ámbitos de permiso, no uno

Hasta el módulo F todos los permisos eran **por conversación**. Eso alcanza
mientras el problema está dentro de una conversación, y deja de alcanzar cuando
alguien denuncia a una *persona*: esa denuncia no pertenece a ninguna
conversación y no hay a quién pedirle permiso.

De ahí el nivel de **plataforma**: 0 nadie, 50 moderador, 80 administrador,
100 propietario. Se reusan los números de la jerarquía de roles para no tener
dos escalas que signifiquen lo mismo, pero los ámbitos **no se mezclan**: ser
staff no da nada dentro de un grupo, y ser dueño de un grupo no hace staff.

Quien no es staff recibe **404 y no 403** en las rutas de moderación: un 403
confirmaría que la cola existe, y probar rutas no debería servir para mapear el
sistema.

### El arranque del staff no es una ruta

Solo un administrador puede nombrar staff, y al principio no hay ninguno. Se
resuelve con `WTFUCK_PROPIETARIO=usuario` al levantar el servidor, una sola vez.
No es una ruta a propósito: una ruta de "hazme administrador" protegida por un
secreto es la clase de cosa que termina abierta en producción.

Y nadie puede dar un nivel igual o mayor al propio, ni cambiar el suyo. Sin esa
regla, el primer administrador fabrica propietarios y el nivel 100 deja de
significar algo.

### La escalada la decide el contador, no el moderador

Tres advertencias vivas suspenden la cuenta 72 horas. Tres y no una: una
advertencia es para corregir, y un sistema que sanciona en la primera no
advierte, castiga en dos pasos. Caducan a los 90 días, así que hacen falta tres
en tres meses; sin vencimiento, alguien quedaría marcado para siempre por algo
de hace tres años y la escalada sería una condena diferida.

La suspensión automática es **temporal**, nunca permanente: la decide un
contador, y un contador no debería poder expulsar a nadie para siempre. Lo
permanente lo firma un administrador.

### Una suspensión no corta la sesión

Corta todo lo que produce contenido, y deja entrar a ver **por qué**. Si cortara
la sesión, la persona vería la pantalla de ingreso sin explicación y volvería a
intentar. Una sanción que no se explica no corrige nada, que es justamente lo
único que la advertencia pretende.

Por eso `GET /v1/moderacion/mi-estado` es la única ruta que un suspendido puede
usar de verdad, y por eso "Mi cuenta" muestra el motivo, la fecha de fin y
cuántas advertencias van sobre el tope.

### Dos limitadores, divididos por duración y no por importancia

- **En memoria**, ventana deslizante, para ráfagas. Reiniciar perdona la ráfaga
  en curso, y está bien: el castigo dura segundos.
- **En la base**, ventanas redondeadas, para lo que se cuenta por hora o por día.
  Aquí reiniciar **no** debe perdonar: si el límite de denuncias diarias se
  olvidara en cada despliegue, bastaría con esperar uno.

Meter todo en la base sería una escritura extra por mensaje enviado; meter todo
en memoria regalaría los límites largos en cada reinicio.

El cupo de 20 denuncias por día **protege la cola, no al denunciado**: sin él,
una persona abre cincuenta denuncias en una tarde y nadie revisa ninguna.

### El límite de ingreso cuenta fallos, no intentos

La primera versión contaba intentos por IP, 10 cada 15 minutos, y estaba mal por
dos motivos distintos:

1. Detrás de un NAT —un campus, una oficina— comparten IP cientos de personas.
   Diez ingresos cada quince minutos dejaba afuera a una facultad entera por
   usar bien la app.
2. Contar los ingresos **correctos** no protege de nada. Quien prueba
   contraseñas falla; el que acierta no reintenta.

Ahora son dos reglas sobre fallos: 8 por usuario cada 15 minutos —nadie se
equivoca ocho veces en su propia contraseña— y 50 por IP, holgada porque tiene
que tolerar el NAT. Lo destapó la regresión: las suites de prueba se estrellaban
contra el límite, que es exactamente lo que le pasaría a un campus.

### `auditoria` y `evento_seguridad` son dos tablas a propósito

- `auditoria` responde *"¿quién expulsó a Ana?"*. La consulta un administrador.
- `evento_seguridad` responde *"¿desde dónde entraron a mi cuenta?"*. La consulta
  el dueño.

Juntas, para ver tus propios accesos habría que dejarte leer filas de
moderación, o filtrar por tipo en cada consulta y no equivocarse nunca.

Tres tipos declarados todavía no tienen quien los escriba: `sesion_cerrada`,
`dispositivo_nuevo` y `dispositivo_revocado`. Hoy cerrar sesión es solo del lado
del cliente y no hay revocación en el servidor; eso llega con el módulo I. Está
anotado en la migración, porque agregar un tipo cuesta una migración.

### Un defecto que encontró esta prueba, no el código

Al registrarse, la app manda en `dispositivo.identidad_pub` la clave atestada
del Keystore, que sirve para probar que el teléfono tiene enclave seguro. La
identidad de libsignal es **otra clave** y llega después, por la ruta de claves,
sobreescribiendo esa columna.

Comparar a ciegas hacía que la **primera** publicación de cada dispositivo
pareciera un cambio de identidad. Un aviso de seguridad que salta siempre es un
aviso que nadie lee, así que el falso positivo no era cosmético: vaciaba de
sentido la advertencia de verdad. Se arregló usando `registration_id`, que vale
0 hasta la primera publicación y responde exactamente la pregunta correcta.

## Módulo H · Panel administrativo ✅

*Cola, sanciones, métricas y búsqueda de personas. Verificado en emulador:
suspender desde la ficha de una persona y restaurarla.*

> Brief §10

- [x] H.1 Métricas: denuncias, suspendidos, advertencias, límites excedidos
- [x] H.2 Buscar y gestionar usuarios; suspender y reactivar
- [x] H.3 Gestión de grupos y canales
- [x] H.4 Bandeja de reportes
- [x] H.5 Consulta del audit log *(la bitácora, con filtro)*
- [x] H.6 Límites de plataforma configurables

### Lo que este panel no puede hacer, y por qué importa

No muestra mensajes. No los busca, no los lee, no los exporta. En una app normal
un panel administrativo se llena de eso; aquí es **imposible por construcción**,
porque el servidor solo guarda bytes opacos. No hay una versión del panel con más
permisos que sí pueda leer.

Tampoco ve la libreta de nadie, ni sus conversaciones, ni con quién habla. Se
puede contar cuántas tiene, no cuáles.

### La cola muestra los contadores del denunciado

Cuántas denuncias abiertas acumula y cuántas advertencias vivas tiene, en la
**lista** y no escondido en el detalle. Es lo que separa un caso de un patrón: un
moderador que solo ve la denuncia de adelante trata las diez denuncias de la
misma persona como diez incidentes sueltos, y decide diez veces lo mismo mal.

### Tomar una denuncia antes de resolverla

No es burocracia: impide que dos moderadores trabajen la misma denuncia y
lleguen a decisiones distintas. El `WHERE estado = 'pendiente'` del UPDATE es lo
que lo hace seguro — el segundo no actualiza ninguna fila y se lo dice.

### La ficha de una persona muestra las denuncias que HIZO

No solo las que recibió. Existe porque **denunciar es un arma**: alguien con
cuarenta denuncias hechas y ninguna recibida no es una víctima frecuente, y sin
ese dato el patrón es invisible.

### Un defecto de disposición que encontró el emulador

El tablero de métricas estaba arriba de las dos pestañas, así que buscar una
persona dejaba el resultado a 1800 px — detrás del teclado. Además el tablero
mide la carga de trabajo de **la cola**, así que en "Personas" no solo
estorbaba: no venía al caso. Ahora solo se dibuja en la pestaña de la cola.

### Dónde está la línea entre moderador y administrador

Moderador (50) trabaja la cola: lee, advierte, silencia, expulsa.
Administrador (80) es el único que suspende cuentas por mano propia y el único
que puede nombrar staff. La línea está ahí porque suspender una cuenta y
repartir poder son las dos cosas que no se deshacen del todo.

### El módulo está completo

`H.3`, `H.5` y `H.6` cerraron en la última vuelta: gobierno de grupos y canales,
la bitácora en pantalla y los límites ajustables sin recompilar. Las secciones
de abajo cuentan cada uno, incluidos los dos defectos que encontraron sus
propias pruebas.


### H.3 · Aprobar no puede ser una puerta de un solo sentido

Con F.7 el dueño decide qué canales existen; sin esto, decidía **una vez y para
siempre**. Un canal que se descarrila un mes después de aprobado no tenía cómo
cerrarse sin entrar a la base a mano.

La vista de canales del panel tiene entonces dos lados —*por aprobar* y *en el
directorio*—, y la revisión es **reversible en los dos sentidos**: lo aprobado
se retira, y lo rechazado que corrigió lo que se le pidió se aprueba. Lo único
que se rechaza es el no-op, que sólo puede venir de un doble toque o de dos
personas decidiendo a la vez.

Y hay algo que el diálogo de retirar dice en voz alta en lugar de esconder:
**retirar no borra lo que la gente ya leyó.** Sale del directorio y no admite
suscriptores nuevos, pero lo que ya se entregó está en teléfonos ajenos.
Prometer lo contrario sería mentir, y es la misma honestidad que ya estaba en
el paso de público a privado.

Los **grupos** son un problema distinto —no hay directorio que curar— y se
resolvieron aparte, con el cierre de conversaciones: la sección "H.3 (grupos)"
más abajo.



### H.3 (grupos) · Cerrar no es borrar, y la pantalla lo dice

Un grupo usado para coordinar abuso necesitaba una herramienta; la que había
era suspender a sus miembros uno por uno. Ahora una conversación se puede
**cerrar**: nadie escribe más, **ni sus administradores**.

Eso último es el punto entero. El cierre se evalúa **antes** de la pertenencia y
del rol: si se evaluara después, el administrador del grupo —que suele ser quien
causó el problema— podría seguir publicando en el grupo que la plataforma acaba
de cerrar.

**Lo que no hace, dicho en voz alta en el diálogo:** no borra lo que ya se
entregó. Esos mensajes están cifrados en teléfonos ajenos y el servidor no los
tiene ni podría leerlos. Un botón de "eliminar el grupo" que en realidad solo
esconde una fila sería mentir en la pantalla de moderación, que es el peor sitio
para mentir.

**Se puede leer lo cerrado.** El historial ya está en los teléfonos; bloquear la
lectura no borraría nada y solo volvería la app inútil para consultar lo que ya
se dijo.

Los miembros se enteran con un **mensaje dentro del chat** y no con una
notificación que se va: es la explicación de por qué a partir de ahí no se puede
escribir, y tiene que seguir ahí cuando alguien abra el chat tres días después.
Cerrar exige motivo, queda en la bitácora con nombre y fecha, y se puede
reabrir.

Una **directa** no se cierra desde el panel: eso sería decidir que dos personas
no pueden hablar entre sí, y para eso está la suspensión de una cuenta, que al
menos tiene nombre y plazo.

### H.6 · Los límites se ajustan sin recompilar, y queda anotado

Los límites son la única defensa contra el abuso automatizado y el número
correcto no se sabe de antemano: se descubre mirando la plataforma real. Con los
números compilados, ajustar uno era un despliegue —y un despliegue en medio de
un ataque es lo último que uno quiere estar haciendo—.

Los **catorce** límites ajustables siguen teniendo su número y su explicación en
el código; la tabla solo **sobreescribe**. Así una base vacía se comporta igual
que antes y **borrar una fila es volver al valor probado** en vez de quedarse sin
límite. Y no hay "sin límite": tope y ventana son mayores que cero por CHECK,
con techo de 24 horas. Un límite configurable que admite apagarse no es un
límite, es un interruptor para la defensa, y tarde o temprano alguien lo usa "un
momento" y se olvida.

Encaja **sin tocar ni un punto de uso**: cada regla dejó de ser un valor y pasó
a ser un `get()` que consulta los overrides. Las rutas siguen escribiendo
`Limitador.ENVIAR_MENSAJE`.

**El defecto que encontró su propia prueba.** `exigir` corre en cada petición,
así que hay 30 segundos de caché. `porDefecto` leía el getter para obtener el
valor de fábrica, el getter llamaba a `efectiva`, y `efectiva` **refrescaba la
caché**… y `ajustarLimite` llama a `porDefecto` *dentro de su transacción*. Ese
refresco consultaba la base con el INSERT sin confirmar, leía el estado anterior
y lo dejaba cacheado medio minuto. El síntoma era el peor posible: la pantalla
guardaba el límite nuevo, contestaba "guardado", y el limitador seguía aplicando
el viejo. Lo destapó la prueba que comprueba el límite **en la ruta de verdad**
en vez de creerle a la respuesta; ahora la caché se invalida después del commit
y leer un valor de fábrica no tiene efectos.

**La bitácora**, además, existía desde el módulo A y no había forma de leerla sin
entrar a la base. Una bitácora que nadie puede leer no disuade a nadie ni
resuelve ninguna discusión, que son sus dos únicas funciones. Exige
administrador: dice lo que hizo cada moderador, y la vigilancia entre pares del
mismo nivel es una forma rápida de que un equipo deje de escribir cosas.


## Módulo I · Identidad y cuenta ✅

*Servidor e interfaz. Verificado en dos emuladores: verificar el número,
descubrir a alguien escribiendo su teléfono con guiones y sin prefijo, activar
2FA, cerrar sesiones a distancia, y pedir la eliminación con periodo de gracia.*

> Brief §2, §9

- [x] I.1 Teléfono opcional, verificado, guardado solo como hash
- [x] I.2 Verificación por código
- [x] I.3 **Recuperación de cuenta**
- [x] I.4 Contactos reales + descubrimiento por teléfono
- [x] I.5 Gestión de sesiones y cierre remoto
- [x] I.6 2FA (TOTP)
- [x] I.7 Eliminar cuenta con periodo de gracia

99 pruebas en `identidad.mjs` y 5 de JUnit en `SeguridadTest`.

### El número se guarda solo como hash, y aun así recupera cuentas

Parece contradictorio —no se le puede mandar un SMS a un hash— y se resuelve
mirando **cuándo** hace falta el número: siempre está delante en el momento de
usarlo. Al verificar lo escribe el usuario; al recuperar lo vuelve a escribir.
El servidor lo normaliza, lo hashea, compara con lo guardado y manda el código
al número que acaba de recibir. Nunca necesita tenerlo.

Una fuga de la base no entrega ni un número. Lo único en claro es el prefijo de
país, que comparten millones de personas.

### El pepper es lo que hace que ese hash sirva de algo

Un teléfono son nueve dígitos útiles: mil millones de candidatos, que para un
hash rápido son minutos. Sin pepper, una fuga de la base entrega la agenda
entera de la plataforma.

El hash se calcula con **HMAC** y una clave que vive en
`WTFUCK_PEPPER_TELEFONO`, **fuera de la base**. HMAC y no `sha256(pepper||dato)`
porque lo segundo es vulnerable a extensión de longitud, y la versión correcta
cuesta lo mismo.

Es también la razón por la que alcanza un hash rápido: con Argon2 cada hash
llevaría su propia sal y no se podría **buscar** por hash, que es justo lo que
hace falta para verificar unicidad y para descubrir.

### El teléfono NO es una credencial

El ingreso sigue siendo por usuario y contraseña. Si el número sirviera para
entrar, volvería a ser el identificador de la cuenta y se perdería lo que el
registro por username resolvió: que nadie necesite dar su número para hablar.

Quien no quiera darlo, no lo da. El precio, declarado en la pantalla: no podrá
recuperar la cuenta ni ser encontrado.

### La normalización a E.164 es el riesgo real del módulo

El mismo teléfono se escribe de seis maneras: `987 654 321`, `987-654-321`,
`(51) 987654321`, `+51 987 654 321`, `0051987654321`, `51987654321`. Si cada
una produjera un hash distinto, **el descubrimiento fallaría en silencio**: dos
personas con el mismo número en la agenda no se encontrarían y nadie sabría por
qué.

Por eso hay **un solo punto** donde se normaliza (`SmsFactory.exigirTelefono`) y
`hashTelefono` recibe el número ya canónico —es imposible hashear uno sin
canonizar—. La pantalla además muestra el resultado mientras se escribe
("Se enviará a +51987654321"), para que la persona lo vea en vez de confiar.

Cuatro pruebas lo fijan: pedir el código con el número escrito de cuatro formas
distintas cae en el mismo destino, y canjearlo funciona escribiéndolo de otra.

### El oráculo que había que evitar

Pedir un código de recuperación con un usuario que no existe, o con un número
que no es el de esa cuenta, responde **exactamente igual** que si coincidieran:
200 y sin código. Distinguir los casos convertiría la ruta en un oráculo para
averiguar de quién es un teléfono.

### Recuperar cierra todas las sesiones, y no devuelve el acceso desde otro teléfono

Lo primero porque quien recupera una cuenta o se olvidó la contraseña, o se la
robaron; en el segundo caso dejar las sesiones vivas haría que el cambio no
sirviera de nada.

Lo segundo es el **techo declarado** del device binding: el vínculo con el
hardware se comprueba al ingresar y no cambia al recuperar. Quien perdió el
teléfono recupera la contraseña y sigue sin poder entrar. La pantalla lo dice
**arriba y en ámbar**, antes de los tres pasos, en vez de hacerle llegar hasta
el final para un "no". Cambiar de teléfono es el módulo J.

### El 2FA y su salida de emergencia

Ocho códigos de respaldo, en claro **una sola vez**: en la base están hasheados
y no se pueden volver a mostrar. Existen porque un segundo factor sin salida
convierte perder el teléfono en perder la cuenta, que es el problema que este
módulo vino a resolver. Sin los caracteres que se confunden (0/O, 1/I/L).

Apagarlo exige la contraseña: sin eso, una sesión robada podría quitar el
segundo factor y quedarse con la cuenta, que es exactamente lo que el segundo
factor venía a impedir.

Un 401 por falta de segundo factor **no** gasta el cupo de intentos fallidos de
contraseña: la contraseña estuvo bien, y contarlo dejaría a alguien fuera de su
cuenta por teclear mal el código de su autenticador ocho veces.

### Eliminar la cuenta: qué se puede borrar y qué no

Treinta días de gracia, y **entrar de nuevo la cancela**. No hay botón de
cancelar a propósito: quien se arrepiente intenta entrar, y así se evita el caso
absurdo de una cuenta que no deja ingresar para poder cancelar la eliminación
que impide ingresar.

Lo interesante de este producto es lo poco que hay que borrar: **el servidor
nunca tuvo el historial**. Lo que se va son metadatos. Lo que **no** se puede
borrar se dice antes de pedir la contraseña, no después:

- los mensajes ya enviados viven en los teléfonos de los demás;
- las publicaciones en canales públicos son de la audiencia del canal;
- una denuncia abierta sigue su curso.

Prometer un borrado total sería mentir.

### Sesiones: qué faltaba para poder gestionarlas

`sesion` existía desde V1 con lo mínimo para autenticar. Una lista de "sesión 1,
sesión 2, sesión 3" no permite decidir cuál cerrar, así que se agregó de dónde
vino y **último uso** —lo que distingue una sesión viva de una que quedó abierta
hace meses—.

Y cerrar sesión ahora existe **en el servidor**. Hasta este módulo, "salir" solo
tiraba el token local y el servidor no se enteraba: cerrar sesión en un teléfono
prestado no cerraba nada y el token seguía sirviendo noventa días. Con eso
quedan escritos dos de los tres tipos de evento de seguridad que G.6 declaró
sin escritor.

### El cierre remoto estaba a medias, y se vio usándolo

I.5 le enseñó al servidor a revocar sesiones, pero no le enseñó a la app a
enterarse. El resultado era el peor de los dos mundos: la sesión muerta en el
servidor y **viva en la pantalla**. El aparato revocado seguía mostrando la
lista de chats, dejaba abrir una conversación y dejaba escribir; cada envío
moría en la cola con *"Sesión inválida o expirada"* y el WebSocket reintentaba
con backoff indefinidamente. La persona veía "sesión terminada" sin saber por
qué ni cómo salir, porque la app no ofrecía ninguna salida. Cerrar sesión a
distancia no sirve de nada si el aparato cerrado no se da por enterado.

El arreglo va en el **único punto por donde pasan todas las llamadas**, no en
cada pantalla: un 401 en una petición *que llevaba token* invalida la sesión
local, y la raíz de navegación observa el token —no la pantalla— para sacar de
la app. La condición "que llevaba token" no es decorativa: en el login un 401
es "contraseña incorrecta" o "falta el código de dos pasos", y tratarlo como
sesión muerta borraría la sesión de quien todavía no tiene ninguna. El
WebSocket hace lo propio: un 401 en el handshake no es una caída de red, así
que deja de reintentar en vez de girar cada 30 segundos.

**Lo que se conserva y lo que no.** Un cierre voluntario borra todo, incluido
quién era. Un cierre involuntario **no lo pidió quien está delante**, así que
borra el token y deja los mensajes locales: si ahí se borrara la base, un falso
positivo se llevaría todo el historial del aparato, y ese historial no está en
el servidor —el buzón es tonto y solo guarda lo no entregado—. Al volver a
entrar en el mismo aparato, los mensajes siguen ahí.

Eso obliga a su contraparte: **si entra otra cuenta en ese aparato, la base
local se va entera.** Conservar es correcto mientras vuelva la misma persona;
si vuelve otra, esos mensajes son de alguien más, y mostrárselos sería filtrar
conversaciones ajenas dentro de una app cuyo argumento entero es el cifrado de
punta a punta. Sirve de poco cifrar en la red si la app se los enseña a quien
entra después en el mismo teléfono. Antes del módulo J esto no podía pasar —un
hardware era una cuenta, y salir borraba todo—; ahora un aparato puede pasar de
una cuenta a otra, así que el cambio se detecta comparando el `usuarioId` de
antes con el de después, y no se supone.

El motivo del cierre se guarda **en disco**, porque el cierre remoto se
descubre casi siempre con la app en segundo plano y Android mata procesos en
segundo plano sin avisar: en memoria, el aviso se perdía y la persona aparecía
en la pantalla de entrada sin explicación. Con el usuario recordado, esa
pantalla arranca en "entrar" y no en "crear cuenta": ofrecer registro sobre un
aparato que ya tiene cuenta es empujar a un error garantizado por la regla de
una cuenta por hardware, con un mensaje que no dice que lo que hacía falta era
entrar.

### Contactos: "conocido" tiene ahora dos formas, y una dirección

Hasta aquí "conocido" significaba "compartimos una conversación directa". Con
una libreta hay dos formas de no ser un desconocido, y es la **unión**: si
hablamos, no sos un extraño; si te guardé, tampoco.

El alias es **mío**: cómo yo llamo a esa persona, no cómo se llama. Es lo que
separa una libreta de una lista de usuarios, y no viaja a ningún lado.

## Módulo J · Multi-dispositivo ✅

*Servidor e interfaz. Verificado en dos emuladores: vincular el segundo aparato
con un código, recibir el historial, y ver aparecer en la tablet un mensaje
escrito en el teléfono sin tocarla.*

> Brief §1

- [x] J.1 Levantar la regla de "un dispositivo por cuenta"
- [x] J.2 Vincular dispositivo (por código, no por QR)
- [x] J.3 Fan-out a N dispositivos
- [x] J.4 Sincronización de historial entre dispositivos
- [x] J.5 Sesiones criptográficas por dispositivo

70 pruebas en `dispositivos.mjs`.

### Casi nada del reparto hubo que construirlo

Este es el módulo que paga una decisión tomada en el E. Desde V1 la dirección
de una sesión de Signal es **el dispositivo y no la persona**, `Claves.destinos`
siempre devolvió una *lista*, y `CopiaCifrada` existe porque con E2EE no hay un
cuerpo único. El fan-out a N dispositivos no hubo que escribirlo: hubo que
**dejar de prohibirlo**.

Lo verdaderamente nuevo son dos cosas: cómo entra un aparato, y qué ve.

### Las dos reglas de V1 parecían una, y eran distintas

V1 puso dos índices únicos parciales y los llamó "REGLA 1" y "REGLA 2":

1. un solo dispositivo activo por **usuario**;
2. un solo dispositivo activo por **hardware**.

Se leían como una sola idea — "una cuenta, un teléfono" — y no lo eran:

- La 2 impide **duplicar cuentas**: un teléfono, una identidad. Es la que
  sostiene el device binding y **se queda**.
- La 1 impedía tener la cuenta en el teléfono y en la tablet. Nunca fue un
  requisito de seguridad: era la consecuencia de no haber construido la
  vinculación. **Se fue.**

Confundirlas habría costado caro: levantar las dos juntas abría la puerta a
varias cuentas por teléfono, que es exactamente lo que el proyecto no quiere.

### La dirección de la vinculación no es arbitraria

El código lo genera **el dispositivo que ya tiene la cuenta**, y la persona lo
teclea en el nuevo. Al revés — que el aparato nuevo genere un código y pida que
se lo aprueben — es el patrón exacto de la estafa de WhatsApp Web: el atacante
manda su código y convence a la víctima de aprobarlo.

Con esta dirección, para meter un dispositivo hay que tener en la mano el que ya
está dentro. No hay nada que aprobar a distancia.

Y solo el **principal** autoriza. Si cualquiera pudiera, robar un secundario
alcanzaría para vincular más, y la cuenta no se recuperaría nunca: cada
revocación se contestaría con una vinculación nueva. El principal tampoco se
puede revocar a sí mismo — dejaría la cuenta sin quien autorice, y eso no tiene
vuelta.

Emitir exige la contraseña y el segundo factor. Es la acción que más acceso
reparte, así que está en la misma categoría que eliminar la cuenta.

### Un código para teclear, no un QR

Ocho caracteres de un alfabeto sin los que se confunden (0/O, 1/I/L), en dos
grupos de cuatro. No hay lector de QR implementado, y un código se copia a mano
entre dos pantallas que uno tiene delante. El QR es más cómodo pero **no más
seguro**, y se puede agregar después sin tocar nada de esto.

Lo que sí es defensa: 5 intentos por código y 5 minutos de vida. Contra 8.5e11
combinaciones, la fuerza bruta deja de ser un camino. El límite por IP es
higiene, no defensa — y confundir esas dos cosas produjo el error que se cuenta
más abajo.

### J.4 · El historial no lo tiene el servidor, y nunca lo tuvo

Un dispositivo nuevo arranca vacío. No es una carencia que se pueda arreglar en
el servidor: los sobres se borran al confirmarse la entrega y el historial vive
cifrado en los teléfonos.

La única forma de que el aparato nuevo vea algo de antes es que **otro
dispositivo de la misma persona se lo mande**, cifrado, por el buzón normal. El
servidor solo reparte el aviso, porque es el único que sabe quién está
conectado.

`Carga.Historial` es un tipo aparte y no un `Texto` porque el receptor tiene que
tratarlo distinto en tres cosas, y ninguna se deduce mirando el contenido:

1. **No notifica.** Un aparato recién vinculado que suena cuarenta veces al
   terminar de sincronizar es un defecto.
2. **No cuenta como no leído.** Ya los leíste, en el otro aparato.
3. **Conserva autor y fecha originales.** Si se guardara con el nombre de quien
   reenvía y la hora del reenvío, el historial quedaría falsificado — y eso es
   peor que no tener historial. Verificado: la tablet muestra el mensaje con su
   hora original, no la del reenvío.

Van 50 mensajes por conversación. Tres razones en orden de peso: cada mensaje
reenviado es un sobre cifrado más; quien reenvía es un teléfono, no un
servidor; y lo que la gente necesita al cambiar de aparato es el contexto
reciente, no el archivo.

### Dos defectos que solo aparecen con dos dispositivos

**El primero se encontró probando.** Al vincular, el pedido de historial salía
*antes* de que el aparato nuevo hubiera publicado sus claves, así que el otro no
tenía con qué cifrarle y los mensajes se perdían en el camino. El síntoma era el
peor posible: vinculación "exitosa", lista de chats vacía, y ningún error en
ninguna de las dos pantallas. Un dispositivo sin claves publicadas **no puede
recibir nada**, así que publicarlas es parte de vincular y no una tarea de
fondo.

Queda además el botón de "pedir historial otra vez": la sincronización al
vincular es un tiro único y depende de que el otro aparato esté encendido. Sin
reintento, quien vincule con el otro teléfono apagado se queda vacío para
siempre sin que nada lo explique.

**El segundo era peor y estaba escondido a la vista.** La lista de
participantes hacía `JOIN dispositivo`, de cuando un usuario tenía exactamente
uno. Con varios, ese join **multiplica filas**: la misma persona aparecía dos
veces en cada grupo. Y con cero — alguien que revocó su único aparato y aún no
vinculó otro — desaparecía del todo y el chat quedaba "(sin participantes)".

Las dos se arreglan igual: `LEFT JOIN LATERAL ... LIMIT 1`, prefiriendo el
principal. El `LATERAL` con límite evita el duplicado; el `LEFT` evita la
desaparición.

### El límite por IP no era la defensa, y creerlo salió caro

La primera versión puso 20 vinculaciones por hora **por IP** pensando que eso
frenaba la fuerza bruta. No la frena: eso lo hacen los 5 intentos por código y
los 5 minutos de vida. Y contarlo bajo por IP repite el error del NAT que este
proyecto ya cometió dos veces — en el ingreso y en los códigos por SMS —:
detrás de una red compartida, veinte vinculaciones por hora son de toda la
oficina, no de una persona.

Tercera vez la misma señal, y las tres las destapó la regresión.

### Dos constantes que se contradecían

`MAX_DISPOSITIVOS = 8` y `EMITIR_VINCULACION = 5/hora`: con menos códigos por
hora que dispositivos permitidos, **el tope era inalcanzable** y el síntoma
habría sido un 429 sin explicación a mitad de configurar los aparatos. Lo
destapó la prueba del tope. Ahora la relación está escrita en los dos sitios.

### Lo que sigue faltando, declarado

- **La huella es por dispositivo.** Verificar a alguien verifica *uno* de sus
  aparatos, y con varios hay varias huellas. La pantalla de verificación
  todavía muestra una sola.
- **No hay aviso al otro lado.** Que un contacto agregue un dispositivo no te
  llega como notificación. WhatsApp y Signal tampoco lo hacen, pero conviene
  decir que es una decisión y no un olvido.

## Módulo K · Llamadas ✅

> Brief §1 · Subsistema aparte: no es "un módulo más".

*Servidor e interfaz. Verificado entre dos emuladores: timbre, contestar,
`ICE: CONNECTED` —o sea medio real, no solo señalización—, silenciar, altavoz,
colgar, rechazar, y el historial con su duración en los dos lados.*

- [x] K.1 Señalización **cifrada**, no por WebSocket en claro
- [x] K.2 WebRTC 1:1 audio
- [x] K.3 Video *(pantalla y pistas listas; la cámara del emulador no sirve
      para juzgar la calidad)*
- [x] K.4 Servidor TURN *(credenciales REST de coturn; sin TURN configurado la
      llamada solo funciona si ICE logra conexión directa, y eso se dice en el
      contrato en vez de fingir)*
- [ ] K.5 SFU para llamadas grupales *(malla con techo de 4; declarado fuera
      de alcance)*
- [x] K.6 Privacidad de llamadas (quién puede llamarme)
- [x] K.7 Historial de llamadas *(cierra L.5)*

### La señalización NO va por una ruta del servidor, y eso es el módulo entero

El SDP lleva la **huella del certificado DTLS** de cada lado, y esa huella es
lo único que hace que el cifrado del medio signifique algo. Si la señalización
pasara en claro por el servidor, el servidor podría cambiar las huellas, montar
dos llamadas —una con cada lado— y escuchar todo, con cada tramo perfectamente
cifrado *contra él*. Así que el SDP y los candidatos ICE viajan **dentro de
sobres cifrados**, por el mismo camino y con las mismas sesiones de Signal que
un mensaje.

Lo que sí va por HTTP son los metadatos: iniciar, contestar, terminar. El
servidor necesita saber quién llama a quién para autorizar y para frenar el
abuso, y eso ya lo sabe de todas formas. **No hay ni una columna para SDP**, y
hay una prueba que lo verifica —una prueba negativa: falla si alguien agrega esa
columna—.

Una llamada vive en una conversación, así que los permisos son **los mismos que
para escribir**: quien no puede mandarte un mensaje no puede hacerte sonar el
teléfono. Sale gratis en vez de necesitar un sistema de permisos paralelo.

### El defecto que se llevaba la app entera al colgar

Colgar cerraba el proceso. No una excepción: un **SIGSEGV** en el hilo de
señalización de WebRTC, sin una sola línea en el log de la app, con los seis
`runCatching` del método de colgado intactos —porque una caída nativa no es una
excepción de Kotlin y ningún `runCatching` la atrapa—.

Eran dos causas encadenadas, y ninguna se ve leyendo el método:

1. **`close()` avisa.** Cerrar la PeerConnection dispara
   `onIceConnectionChange(CLOSED)` de forma sincrónica en el hilo de
   señalización. El servicio interpretaba "terminado" como "se cayó la red" y
   volvía a colgar **ese mismo motor** desde dentro del callback, mientras el
   colgado original seguía en curso: dos `dispose()` sobre el mismo objeto
   nativo. Ahora `CLOSED` no se avisa —solo ocurre porque cerramos nosotros—, y
   `FAILED` sigue siendo la única caída de verdad.
2. **El orden de liberación importa.** Soltar una fuente de audio o video
   mientras la conexión sigue viva deja al track nativo apuntando a memoria
   liberada. `dispose()` de la conexión va primero, las fuentes después.

Y colgar es idempotente, porque colgar a mano y una caída de red pueden llegar
a la vez desde hilos distintos.

### Rechazar mientras suena no avisaba a nadie

Mientras suena **no hay ningún motor**: crear la conexión antes de contestar
conectaría el audio solo, así que la oferta se guarda y no se responde. Pero el
colgado mandaba el "fin" cifrado a los motores, y ahí no había ninguno: el que
llamaba se quedaba con *"Llamando..."* hasta que el servidor cortara por tiempo,
**45 segundos** después. Ahora el fin va a los motores *más* las ofertas sin
contestar.

Con eso hay dos caminos para el fin de una llamada, y los dos hacen falta:

- El **sobre cifrado** es el rápido: corta en el otro teléfono al instante.
- El **aviso del servidor** es la red de seguridad: si el otro teléfono se
  queda sin batería o el timbre se agota por tiempo —que lo decide el servidor,
  nunca el cliente—, nadie manda el sobre, y sin este aviso la pantalla
  seguiría sonando para siempre.

### La llamada es una capa, no un destino de navegación

Una llamada entrante tiene que aparecer sobre lo que sea que haya en pantalla
—incluido un borrador a medio escribir, que no debe perderse— y desaparecer sin
dejar nada en el historial de navegación. Como destino, "atrás" desde la
llamada devolvería a la pantalla anterior o sacaría de la app.

### La videollamada en una ventanita, no a pantalla completa

Una videollamada a pantalla completa secuestra la app: mientras hablas no
podés mirar un mensaje, buscar un dato ni copiar una dirección. Ahora se
minimiza a una ventana movible —como la de WhatsApp—, se arrastra a donde
estorbe menos, se toca para volver a pantalla completa y se puede colgar desde
ahí.

**Por qué no es un `Dialog` ni una ventana del sistema.** Un `Dialog` de
Compose vive en su propia ventana y se traga los toques de todo lo que tiene
debajo: con él abierto la app deja de responder, que es exactamente lo
contrario de lo que se busca. Una ventana flotante del sistema
—`TYPE_APPLICATION_OVERLAY`— funcionaría incluso fuera de la app, pero exige el
permiso `SYSTEM_ALERT_WINDOW`, de los que asustan y de los que Google revisa.
Así que es una capa dentro de la propia jerarquía: un `Box` que ocupa toda la
pantalla pero **no dibuja ni escucha nada** fuera de la ventanita, y Compose
sólo entrega los toques a quien los pide.

Tres detalles que se ven sólo al usarla:

- **Mientras suena no se puede minimizar, y al terminar se vuelve a abrir.**
  Las dos pantallas existen para decir algo —"contestá" y "te rechazaron"— y en
  una ventanita de una esquina no se dice.
- El estado de minimizado se recuerda **por llamada**. Con una sola variable
  para todas, minimizar una dejaba la *siguiente* entrando en miniatura, y una
  llamada entrante de 128 dp en una esquina es una llamada que nadie ve.
- El arrastre se registra **antes** del clic. Al revés, mover la ventana se
  leía como un toque y la llamada se abría a pantalla completa cada vez que se
  la intentaba correr.

El botón de minimizar necesitó `statusBarsPadding()`, y eso no es cosmética: la
capa va de borde a borde, así que sin él el botón queda **debajo** de la barra
de estado, que se traga el toque. Se veía como un botón que está ahí y no hace
nada.

### `recuperar()` existía y no la llamaba nadie

Si la app muere en medio de una llamada —y morir en medio de una llamada es
fácil: no hay servicio en primer plano—, el servidor la sigue creyendo viva y
deja a esa persona **ocupada para siempre**: no puede llamar ni recibir. El
módulo K traía la función que cierra esa llamada al arrancar, y no estaba
cableada en ningún lado. Apareció probando la ventana flotante: la segunda
videollamada no arrancaba y no había ningún error a la vista.

Es la única forma de que un arreglo no arregle nada: escribirlo y no llamarlo.

### K.8 · La llamada vive en un servicio en primer plano ✅

Era la deuda más grande del módulo: una llamada vivía mientras viviera la
Activity, así que salir de la app la cortaba y Android mata procesos en segundo
plano sin avisar. Ahora una llamada en curso **sobrevive a salir de la app**, se
puede colgar desde la notificación sin volver a entrar, y una llamada entrante
con la app de fondo se contesta desde ahí.

**Por qué un servicio y no una corrutina más.** No es dónde corre el código: es
lo que Android **promete**. Un proceso con un servicio en primer plano y su
notificación visible es candidato de última instancia para el asesino de
memoria; sin él, de los primeros. La notificación no es burocracia, es el
contrato: el sistema protege el proceso porque la persona puede ver que algo
pasa y cortarlo de un toque.

**El tipo de servicio no es opcional.** Desde Android 14 hay que declarar para
qué es —`microphone`, y también `camera` si hay vídeo— y el sistema comprueba
que la app tenga ese permiso **en ese momento**. Tipo equivocado o permiso
ausente no es una advertencia: es una excepción y el servicio no arranca.

**El defecto que me costó la app entera, dos veces.** El servicio se detenía a
sí mismo en el primer instante y Android mataba el proceso con
`ForegroundServiceDidNotStartInTime`, un error que no dice nada de la causa. Las
dos causas:

1. Se arrancaba **antes** de fijar el estado de la llamada. El servicio se apaga
   solo cuando la llamada termina y "terminada" lo lee del estado; al
   suscribirse, un `StateFlow` entrega su valor actual, que era `null` porque la
   llamada aún se estaba creando. Se apagaba antes de pasar a primer plano.
2. El `runCatching` alrededor de `startForeground` **se comía la causa**. Con la
   excepción tragada, el único síntoma era la app cerrándose unos segundos
   después. Ahora eso se registra y el servicio se detiene solo.

Y hay una guarda explícita (`huboLlamada`) más un temporizador de 20 segundos:
si la llamada nunca llega a existir, el servicio no puede quedarse con una
notificación de "Llamada" para siempre.

**La notificación entrante tiene botones de verdad.** Con `CallStyle` el sistema
la trata como lo que es y le pone *Contestar* y *Rechazar* grandes. No es
estética: es la **única** cosa que se ve cuando la app está en segundo plano y
el servicio no pudo arrancar —Android no permite arrancarlo desde el fondo—, y
si desde ahí no se pudiera contestar, una llamada entrante sería una
notificación decorativa. Tocar una acción de notificación sí da permiso temporal
para pasar a primer plano, así que desde ahí sí se puede.

### Lo que sigue faltando, declarado

Una llamada entrante con la app **cerrada** no suena, y K.8 no lo arregla: hace
falta un empujón del sistema (FCM o equivalente) que despierte el proceso, y eso
es infraestructura aparte. Lo que sí queda arreglado es el caso que se da todo
el rato: la llamada en curso ya no se corta por salir de la app.

El historial dice **que hubo una llamada**, nunca qué se dijo. Eso no es una
limitación: es el punto.

## Módulo L · Interfaz completa ✅

> Brief §15

- [x] L.1 Centro de privacidad completo, presencia, lectura y "escribiendo"
- [x] L.2 Centro de seguridad *(sesiones, 2FA y accesos; dispositivos con J)*
- [x] L.3 Pantalla de contactos
- [x] L.4 Pantalla de grupos y canales *("Mis canales" + directorio; grupos por el filtro de Chats)*
- [x] L.5 Historial de llamadas *(con K.7)*
- [x] L.6 Ajustes de notificaciones granulares
- [x] L.7 Ajustes de almacenamiento
- [x] L.8 **Consola web** de administración, responsive *(no un cliente de mensajería: ver abajo)*


### L.6 · Notificaciones: qué avisa, qué se ve, y qué no se puede apagar

Antes se notificaban dos cosas —que te agregaron a un grupo y las decisiones de
moderación— y nada más: **un mensaje nuevo no avisaba**. Ahora hay cinco
categorías, cada una con su canal de Android y su importancia: mensajes
directos (alta), grupos (normal), canales (**baja**, porque un canal publica a
mucha gente y no espera respuesta; tratarlo como un mensaje directo es la forma
más rápida de que alguien apague todo), llamadas (alta) y moderación (alta).

**El texto del mensaje no aparece nunca.** No es una omisión: va cifrado de
extremo a extremo, y copiarlo a la notificación lo dejaría legible justo en la
pantalla de bloqueo, que es pública. Lo único que puede ir es de quién es, y
hasta eso se puede apagar —con "mostrar quién escribe" en off, la notificación
sólo dice que hay algo nuevo—.

**Los ajustes son del aparato, no de la cuenta.** Una notificación la recibe un
teléfono, no una persona: con multidispositivo, querer que suene el teléfono y
no la tablet es lo normal, y guardarlo en el servidor obligaría a que las dos se
comporten igual. Además el servidor no tiene por qué saber qué te molesta.

**Por qué hay interruptores si Android ya tiene canales.** Son dos cosas y las
dos hacen falta. El sistema manda sobre el sonido, la vibración y la importancia
—y hay un acceso directo a sus ajustes para eso—; lo de la app es más fuerte:
con una categoría apagada **no se publica** la notificación. No es bajarle el
volumen, es no decirlo. Verificado en el emulador: con "mensajes directos" en
off, el mensaje llega, el contador de no leídos sube y la bandeja del sistema no
se mueve.

Las **advertencias y sanciones no tienen interruptor**, y la pantalla dice por
qué: una advertencia que no llega no cumple su única función, que es dar la
oportunidad de corregir antes de la sanción. Si se pudiera silenciar, sancionar
después sería una emboscada.

Tres cosas se callan solas, además: el chat abierto en pantalla —notificar lo
que la persona está leyendo es ruido—, la conversación silenciada, y el mensaje
repetido. Lo último usa la misma comprobación que el contador de no leídos
(`filas != -1L`): el buzón reentrega lo que no se acusó, así que sin eso un
mensaje sonaba dos veces por diseño.

### Un canal se llamaba "(sin participantes)"

Apareció mirando la lista de chats de una cuenta con canales propios. El
servidor armaba el nombre de la conversación con un `if` de dos ramas —grupo o
directa— y un canal caía en la de las directas: "el username del otro", que en
un canal recién creado no existe. El nombre del canal estaba ahí al lado, sin
usar.

El arreglo trajo su pareja en el cliente: el "@" es de las personas. Un grupo y
un canal tienen nombre propio, y "@Prueba en vivo" se lee como un usuario que no
existe.


### Navegación por pestañas

Dos niveles, y cada uno resuelve un problema distinto.

**Abajo, barra de navegación: Chats · Canales · Perfil.** Antes todo colgaba de
la lista de chats y los canales vivían escondidos en el menú de tres puntos. Un
canal no es una opción de configuración: es un lugar al que se entra, igual que
un chat, y el menú lo enterraba.

Va abajo y no arriba porque arriba ya están el buscador y los filtros, y el
pulgar no llega. WhatsApp hizo el mismo cambio por el mismo motivo cuando los
teléfonos crecieron.

Tres detalles que no son decorativos:

- El globo del icono de Chats cuenta **conversaciones** con algo sin leer, no
  mensajes: lo que se necesita saber desde la barra es a cuánta gente le debes
  respuesta. Los silenciados no cuentan; para eso los silenciaste.
- Cambiar de pestaña **no es navegar**: las tres viven en un solo destino, no
  apilan historial, y "atrás" desde Canales vuelve a Chats en vez de sacarte de
  la app.
- Cada pestaña guarda su estado —el scroll, lo escrito en el buscador, el filtro
  elegido—. Sin eso, mirar tu perfil un segundo te devolvía al principio de la
  lista.

La barra tiene cuatro destinos desde el módulo I: **Chats · Contactos ·
Canales · Perfil**. Contactos es una pestaña y no una pantalla escondida porque
la libreta y la lista de chats son dos cosas distintas —una es *a quién
conoces*, la otra *con quién hablaste*— y porque guardar a alguien decide quién
puede escribirte.

**Arriba, pestañas de la lista: Todos · No leídos · Grupos**, al estilo de las
carpetas de Telegram, con el número dentro de la pestaña. El número va ahí y no
en un globo aparte para saber si vale la pena tocarla **antes** de tocarla.

Son tres y no seis a propósito: una pestaña que nunca se toca ocupa el mismo
ancho que una que sí, y "no leídos" y "grupos" son las dos preguntas que la gente
le hace de verdad a una lista de chats. Dentro de Archivados no hay filtros,
porque ya es una lista aparte.

El panel de moderación **no** es una pestaña: casi nadie es staff, y una pestaña
que la mayoría no puede usar solo estorba. Vive en el perfil, y solo aparece si
el servidor dice que tienes nivel de plataforma.

---


### L.1 · La cabecera del chat decía "conectado" siempre

Y no había un solo dato de presencia detrás: era un adorno con forma de
información. Peor que no decir nada, porque la gente lo lee y decide cosas con
eso ("está en línea y no me contesta"). Ahora hay presencia de verdad —se marca
al conectar y al desconectar el socket, no en cada mensaje— y por eso mismo hay
que poder apagarla: saber cuándo alguien estuvo conectado es justo el dato que
más se usa para vigilar a una pareja o a un hijo.

**La reciprocidad no es un detalle.** Quien oculta su última conexión tampoco la
ve de los demás, y se aplica **en el servidor**, no escondiendo el texto en la
pantalla. Sin esa regla el ajuste sería un espejo de una sola dirección: ver sin
ser visto, que es exactamente para lo que se usaría.

Los ajustes nuevos del §3, con la consecuencia que cada uno tiene y que se dice
en el momento de elegir:

- **Quién ve mi nombre.** El username no se puede ocultar: es la dirección con la
  que existís en la plataforma. Quien no vea el nombre ve `@usuario`, que es lo
  que la app ya hacía con quien no puso nombre.
- **Quién me encuentra por mi usuario.** Con `nadie` no dejás de ser alcanzable:
  quien ya habla con vos sigue escribiéndote y los enlaces de invitación siguen
  funcionando. Y responde **404, no 403**: un "no puedo decirte nada de esta
  persona" ya confirma que la persona está.
- **Confirmaciones de lectura.** Booleano y no de tres niveles, porque es
  recíproco. Y aquí la regla sí vive en el servidor —las lecturas se guardan— con
  sus **dos mitades**: si quien lee las tiene apagadas no se anota nada, y si
  quien escribió las tiene apagadas tampoco se le avisa. Sin la segunda, apagarlas
  sólo te dejaría de mostrar las de los demás mientras los demás siguen viendo las
  tuyas.
- **Avisar cuando escribo.** La señal más efímera del sistema: vale tres segundos
  y después es mentira. **No se guarda en ninguna parte** —el servidor la reenvía
  y la olvida—, y aquí el ajuste lo aplican los dos clientes: quien lo apaga no la
  manda y no la muestra. Poner esa comprobación en el servidor obligaría a leer la
  base en cada ráfaga de tecleo para decidir algo que no se persiste, y lo único
  que un cliente modificado conseguiría saltándose la regla es dar **su propia**
  información.

Detalles que sólo se ven al construirlo: no hay aviso de "dejó de escribir" —se
puede perder y dejaría el indicador encendido para siempre—, así que el receptor
lo apaga solo a los seis segundos y el cliente manda uno cada cuatro. El peor
caso es que se apague tarde, no que mienta.

### El check de leído era cian sobre una burbuja cian

Las confirmaciones de lectura existían en el contrato desde el módulo C y
**nadie las enviaba**: `LEIDO` no ocurría nunca. En cuanto empezaron a llegar,
las burbujas propias se quedaron sin ningún check: el icono estaba ahí, del
mismo color que el fondo. Sobre la burbuja de acento lo que distingue es el
**peso** —entregado en tinta a medias, leído en tinta plena—, no el color.

Es el patrón que se repite en este proyecto: un caso imposible no se ve roto
hasta que deja de ser imposible.

### J.6 · Escanear el QR de vinculación

Teclear `K7M2-9QXF` mirando otra pantalla es donde la gente se equivoca, y cada
equivocación gasta uno de los cinco intentos del código. El QR lleva
**exactamente** el mismo código, con su misma vida de cinco minutos: no cambia
la seguridad, cambia la tasa de error. El código sigue visible al lado, y eso no
es redundancia —una cámara tapada o un aparato sin cámara dejan el QR inservible
y entonces se teclea—.

**CameraX + el núcleo de zxing, y no una biblioteca de escaneo.** Las que hay
traen su pantalla, su tema y su forma de pedir permisos; en una app cuya promesa
es que nada sale del aparato, una dependencia que abre cámara por su cuenta es
justo lo que no se quiere auditar. ML Kit tampoco: exige Play Services y no
funciona sin Google. No se guarda ni una imagen: el análisis es cuadro por
cuadro en memoria y lo único que sale de ahí es el texto.

El decodificador se extrajo a una función **pura** —sin una clase de Android— y
tiene cuatro pruebas de JVM, incluida la del `rowStride` con relleno. Eso último
no es pedantería: la cámara alinea cada fila a un múltiplo y pasar el ancho como
stride es el error clásico, produce una imagen inclinada que nunca decodifica, y
sin la función pura sólo se habría probado a mano.

Escanear **rellena el campo** en vez de enviar solo: un escaneo que leyó mal se
corrige sin volver a empezar.

### L.8 · Una consola web, y por qué no un cliente de mensajería web

Un cliente web de mensajería tendría que descifrar, y para descifrar hace falta
ser un **dispositivo con claves de identidad propias** —lo que el módulo J hizo
posible—. Llevar libsignal al navegador es un proyecto aparte: WASM, el almacén
de claves en IndexedDB, y la pregunta seria de si un navegador es sitio para
claves de largo plazo. Prometerlo a medias sería peor que no tenerlo.

Lo que **sí** se puede llevar a la web sin mentir es el panel: todo lo que toca
son metadatos que el servidor ya conoce porque los necesita para autorizar.
Denuncias, cuentas, canales por aprobar, grupos, límites, bitácora. Ningún
mensaje. Y moderar desde una pantalla grande es de verdad más útil que moderar
desde un teléfono.

**El teléfono sigue siendo la raíz de confianza.** No hay ingreso con usuario y
contraseña desde el navegador, y no por pereza: ingresar crearía un
**dispositivo**, y ahí se rompen dos reglas a la vez —una cuenta por hardware, y
la promesa de que cada dispositivo es una copia más de tus mensajes—. Un
navegador no puede ser eso. Así que quien ya está dentro, con su contraseña,
emite un token y lo pega en el navegador.

El token está **acotado por construcción**, no por confianza: la cabecera es
`Consola` y no `Bearer`, y sólo la resuelve la función que protege el panel. Una
consola no lista conversaciones, no manda mensajes, no lee el perfil y **no
puede emitir otra consola**: si pudiera, la raíz de confianza dejaría de ser el
aparato en el primer encadenamiento. Dura ocho horas —una jornada—, se ve y se
cierra desde el teléfono, y revocarla corta en el acto.

La consola es **un archivo**: HTML, CSS y JavaScript servidos tal cual, sin npm,
sin bundler y sin framework. La razón no es purismo: cada dependencia de
terceros ahí es código que corre con un token capaz de suspender cuentas.
Auditar un archivo es posible; auditar un árbol de `node_modules`, no. Y el token
vive **en memoria y no en `localStorage`**: tener que pegarlo otra vez al recargar
es el precio de que no sobreviva al cierre de la pestaña.



### El nivel `personalizado`, y las dos listas que hacen falta

`todos`, `conocidos` y `nadie` son **valores**: caben en una columna. "Todos
menos Fulano" y "sólo Mengano" no son valores, son **listas**, y una lista por
persona y por ajuste no cabe en un `text`. Por eso el nivel se llama
`personalizado` y significa "mirá la tabla de excepciones de este ajuste".

**Los dos modos existen porque son las dos maneras opuestas en que la gente
piensa la privacidad**, y las dos son legítimas: lista negra ("que lo vean todos
menos estos") y lista blanca ("que lo vean sólo estos"). Con un solo modo, el
otro caso se vuelve absurdo: una lista negra no puede expresar "sólo mi familia"
sin enumerar la plataforma entera.

Aplica a siete ajustes y **no** a tres, a propósito: `escribe` no, porque una
lista blanca de quién puede escribirte convierte la cuenta en un club cerrado y
para eso están los bloqueos; `lectura` y `escribiendo` tampoco, porque son
booleanos recíprocos y una lista de "a quién sí le aviso" es el espejo de una
sola dirección que la reciprocidad evita.

**Una lista blanca vacía no muestra el dato a nadie**, y ese es el defecto
seguro elegido: si algo fallara al leer las excepciones, el resultado es ocultar
el dato, nunca mostrárselo a todos. La versión de la regla que no recibe la
lista resuelve *no* por lo mismo.

Se resuelve con **una consulta por petición**, no una por fila: se pregunta al
revés —"dónde estoy yo listado"— en lugar de "quién tiene excepciones", que
obligaría a consultar por cada persona de una lista de participantes.

**Dos defectos propios, los dos encontrados por sus pruebas:**

1. Los `CHECK` de la base seguían admitiendo sólo tres valores. El servidor
   aceptaba `personalizado`, Postgres lo rechazaba, y el síntoma era un 500 en un
   `PUT` que la pantalla ya había dado por bueno.
2. `priv_modo` es la columna **14** y se leía la 13 —la marca de tiempo de
   `ultima_vez`—. `modoDe` no encontraba nada en un número y devolvía su defecto,
   así que una lista **blanca se comportaba como una negra**: exactamente al
   revés. Lo destapó la prueba del modo `solo`, que es la que no se habría
   escrito si sólo se probara "que guarde".


## Módulo M · Contenido con estructura ✅

Hasta aquí un mensaje era texto, un archivo o señalización. Este módulo agrega
las cuatro cosas que el receptor no sólo **lee** sino que **usa**: una ubicación
se abre en el mapa, un contacto abre un chat, una encuesta y un evento se votan.
Más dos cosas que el brief pedía y no estaban: buscar dentro de la conversación
y marcar un chat como no leído.

| # | Qué | Estado |
|---|---|---|
| M.1 | Ubicación, contacto, encuesta y evento como cargas propias | ✅ |
| M.2 | Voto E2EE con recuento en el cliente | ✅ |
| M.3 | Buscar dentro de la conversación · marcar como no leída | ✅ |
| M.4 | Una reacción por persona (defecto reportado) | ✅ |
| M.5 | El id del mensaje, separado del id de la fila del buzón | ✅ |
| M.6 | Rediseño de la pestaña Perfil | ✅ |

### Una columna para todas las clases, y no una por clase

`MensajeEnt` guarda `especial` (qué clase es) y `especialJson` (la carga tal
como viajó). No se desarma en columnas —latitud, longitud, pregunta,
opciones…— porque el contenido ya tiene forma definida en el contrato, y
copiarla campo por campo a la base local obligaría a una migración de Room por
cada clase nueva. Al enviar sale exactamente lo que entró; si se rearmara, un
campo que la pantalla no hubiera copiado se perdería en silencio.

`oculto` existe por los votos: un voto **tiene** que pasar por la cola —para
reintentarse sin red y respetar el orden, igual que un mensaje— pero no es algo
que nadie haya dicho en la conversación. Sin esa columna, un voto aparecía como
una burbuja vacía.

### Quién cuenta los votos, y el precio que se declara

Los clientes. No hay alternativa: el servidor no puede leer un voto, así que no
se le puede pedir el recuento. Cada aparato recibe los votos de todos y suma.

**Consecuencia directa: una encuesta cifrada de extremo a extremo no puede ser
anónima**, porque el voto llega dentro de un sobre firmado por la sesión de
quien vota. Por eso esta app **no ofrece la casilla "encuesta anónima"** y lo
dice en la burbuja, antes de votar y no después: sería una etiqueta falsa.

`cierraEn` tampoco es un candado, y se dice: es una fecha que cada cliente
respeta al pintar y al votar. Un candado de verdad exigiría que el servidor
supiera que ese sobre es un voto y de qué encuesta, que es exactamente lo que no
queremos que sepa.

### `encuesta.crear` y `evento.crear` dejan de ser botones escondidos

Estaban en el catálogo desde V4 **sin nada detrás**. Un permiso que el servidor
no puede comprobar no es un permiso.

Y el servidor no puede comprobarlo mirando el sobre, porque está cifrado. La
salida es la misma que ya usaban los adjuntos: el cliente **declara la clase**
en el metadato en claro —donde ya viajan el autor, la conversación y las
menciones— y el servidor autoriza contra eso. No es información de otra
naturaleza: ya sabía que se envió un sobre a esa conversación; ahora sabe que
decía ser una encuesta.

**El límite, dicho claro:** un cliente modificado puede declarar `TEXTO` y
mandar una encuesta igual. Eso no se puede evitar con E2EE y no se pretende: la
garantía es la misma que da `mensaje.enviar`, ni más ni menos. Quien puede
escribir texto siempre pudo escribir "1) sí 2) no" a mano.

El reparto de V4 se respetó tal como estaba y la prueba lo fija: el rol
`miembro` trae `encuesta.crear` pero **no** `evento.crear`, porque un evento le
pide asistencia a todo el grupo. Y el orden importa: `mensaje.enviar` se
comprueba **antes** que la clase, así que un silenciado no puede colar contenido
por la puerta de la encuesta.

### Por qué no hay mapa incrustado ni "ubicación en vivo"

Un mapa embebido obligaría a pedirle las baldosas a un tercero: contarle a ese
tercero dónde está la persona **justo cuando** está compartiendo dónde está. Van
las coordenadas y la etiqueta que escribió quien la manda, y el teléfono decide
con qué app abrirla (`geo:`). El margen de error del GPS se muestra siempre, y
en ámbar si es grande: "aquí, con 2 km de margen" y "aquí, con 5 m" son dos
mensajes distintos.

"Compartir en vivo" no está, y no por tiempo: exige un servicio en primer plano
con su propio tipo de permiso, una sesión que caduque sola y una manera visible
de cortarla desde cualquier pantalla. Es un módulo, no un botón, y ofrecerlo a
medias deja algo encendido que la persona cree apagado.

La tarjeta de contacto comparte un `@usuario` de esta plataforma y **nunca un
teléfono**: mandar el número de un tercero es entregar el dato personal de
alguien que no está en la conversación y no dio permiso.

### Buscar dentro del chat: en el teléfono, porque es el único lugar

El texto sólo existe en claro en este aparato. El servidor no puede ofrecer esta
búsqueda —guarda sobres opacos—, y es la misma razón por la que el panel de
administración no busca mensajes de nadie. `LIKE` y no FTS: el historial de un
teléfono se mide en decenas de miles de filas, y una tabla FTS aparte —con su
índice, sus triggers y su tamaño, todo dentro de la base cifrada— se paga para
buscar en millones. Los comodines `%` y `_` se escapan, o buscar "100%"
devolvería cualquier cosa.

### Una reacción por persona (defecto reportado)

La llave primaria de `reaccion` era `(mensaje_id, usuario_id, emoji)`, así que
una misma persona podía sostener varias reacciones sobre el mismo mensaje. La
cabecera de V6 decía "cambiar de emoji es borrar y poner otra", dando por
sentado que el cliente borraría la anterior. **El cliente nunca lo hizo**: manda
`poner=true` y nada más. Resultado: reaccionar dos veces dejaba las dos marcas
una al lado de la otra, que no es lo que hace ningún mensajero conocido ni lo
que la gente espera. Lo que se acumula es la **misma** reacción de varias
personas, no las de una.

Se arregló en la **base** y no sólo en la ruta (V25): con la llave en
`(mensaje_id, usuario_id)`, el segundo emoji de la misma persona no es un caso
que haya que acordarse de tratar, es un conflicto que `ON CONFLICT … DO UPDATE`
convierte en reemplazo. Dos toques rápidos seguidos son dos peticiones, y un
borrar-más-insertar podía cruzarse y dejar el mensaje sin ninguna reacción.

### El id del mensaje no es el id de la fila del buzón (V26)

El defecto más profundo que apareció en este módulo, y **no era nuevo**.

El id de una fila del buzón se deriva por destino: `derivar(base, i)` es `base`
para el primer destino y `base + i` para los demás. Eso es correcto y necesario
—con E2EE cada destino recibe bytes distintos y necesita su propia fila, y
derivar el id del que generó el cliente es lo que hace que un reintento choque
con `ON CONFLICT` en vez de duplicar el mensaje—. El problema es que ese id
derivado era **lo único** que el cliente recibía, y lo guardaba como el id del
mensaje.

En un grupo de tres o más dispositivos, **el mismo mensaje quedaba guardado con
un id distinto en cada teléfono**. Tres síntomas, un defecto:

1. Los votos de una encuesta de grupo no se contaban: el voto decía "esto es
   sobre la consulta X", y X era el id que conocía quien votaba y nadie más.
2. Un mensaje de grupo se quedaba en **un solo check**: el acuse devolvía el id
   derivado al emisor, que no encontraba esa fila y no marcaba "entregado".
3. Responder o reaccionar a un mensaje ajeno en un grupo apuntaba a un id que no
   existe en `mensaje_meta`.

En conversaciones **directas** los dos valores coinciden —hay un solo destino y
`derivar(base, 0) == base`—, y por eso el defecto sobrevivió a veinte suites de
pruebas: casi todas prueban chats de dos.

La corrección: la fila del buzón guarda también el id original y es ése el que
viaja al cliente como identidad del mensaje. El derivado sigue existiendo y
sigue siendo el que se acusa. Son dos cosas y ahora se llaman distinto.

### El rediseño del perfil

Cuatro problemas concretos, ninguno de gusto:

1. **El nombre estaba debajo de los ajustes.** Se entraba al perfil y lo primero
   tras la foto era "Cuenta y seguridad", "Notificaciones", "Llamadas"; el
   nombre, el usuario y el estado aparecían recién después de desplazar. En una
   pantalla que se llama "perfil", lo primero tiene que ser de quién es.
2. **Siete tarjetas flotando con el mismo hueco entre todas.** La separación,
   que es lo que agrupa, estaba repartida en partes iguales entre cosas
   relacionadas y cosas que no, así que no agrupaba nada. Ahora hay grupos con
   título y divisores internos, y el aire vuelve a significar algo.
3. **La huella del hardware en crudo, en la pantalla principal.** Es un dato de
   auditoría: se mira una vez y después nunca más. Arriba quedó una línea que
   dice si el vínculo es fuerte; el detalle se mudó a Cuenta y seguridad.
4. **Cerrar sesión era un icono coral, sin texto, en la esquina, y sin
   preguntar.** Un toque accidental cerraba la sesión. Ahora es una fila con su
   nombre, abajo, y pregunta — y el diálogo dice lo que nadie sabe: que el
   historial se queda en el teléfono, y que entrar con **otra** cuenta en este
   aparato sí lo borra.

Las piezas del grupo (`SeccionAjustes`, `FilaAjuste`) quedaron en `Comunes.kt`
para que el resto de las pantallas de ajustes puedan converger.

### Las pruebas se mudaron al repositorio

Las 786 pruebas de integración vivían en la carpeta temporal de la sesión de
trabajo. Un `%TEMP%` limpio se las llevaba. Ahora están en `pruebas/`, con un
`correr.mjs` que las corre todas y distingue **tres** estados, no dos:

- **ROTA**: salió con código 0 pero **sin línea de resumen**. Murió a mitad sin
  fallar ninguna aserción, que es el falso verde clásico. Lo encontró en su
  primera ejecución: nueve suites marcaban "0 pruebas" porque usaban otro
  formato de resumen, y después pilló al propio stub de FCM intentando correr
  como suite.
- **OMIT**: salió bien sin comprobar **nada**, porque le faltaba algo del
  entorno y lo dijo (un stub, una segunda instancia). Marcarla OK sería el mismo
  falso verde un nivel más arriba.
- **OK**: comprobó algo y pasó.


## Módulo N · Lo que faltaba del brief ✅

Las cuatro cosas que quedaban abiertas y no dependían de nadie más.

| # | Qué | Estado |
|---|---|---|
| N.1 | Push: despertar la app **cerrada** | ✅ camino completo; falta la credencial |
| N.2 | Tema claro, con su propia escala medida | ✅ |
| N.3 | Dos paneles en tablet/escritorio · atajos de teclado | ✅ |
| N.4 | El `Hub` fuera del proceso: segunda instancia | ✅ |
| N.5 | Accesibilidad: lo que oye quien no ve la pantalla | ✅ |
| N.6 | Revisión adversaria de M y N · bus firmado | ✅ |
| N.7 | El §16 de punta a punta: barrido de rutas con id ajeno | ✅ |
| N.8 | La otra mitad: barrido de LECTURA ajena y escalera de staff | ✅ |
| N.9 | El par hostil: lo que llega dentro de un sobre que no escribió esta app | ✅ |
| N.10 | Adjuntos, miniaturas y el intermediario de GIFs | ✅ |
| N.11 | Notas de voz: grabar con techo y reproducir lo ajeno sin bloquear | ✅ |
| N.12 | Notas de voz: adelantar tocando la onda y velocidad 1x/1.5x/2x | ✅ |
| N.13 | Deslizar una burbuja para responderla | ✅ |

### N.1 · El aviso no lleva nada, y eso es el diseño

El servicio en primer plano (K.8) resolvió que una llamada **en curso**
sobreviva a salir de la app. No resolvía la app **cerrada**: ahí no hay proceso
al que avisar. Eso no se arregla con más código adentro —Android mata procesos
y tiene razón— sino con alguien de afuera que despierte.

**El payload es `{"w":"1"}` y nada más.** Ni texto, ni quién escribe, ni
conversación. El teléfono se despierta, abre el socket, baja sus sobres y
**recién ahí** —ya descifrado— el camino normal decide si hay notificación que
publicar. Es un paso más de trabajo a cambio de lo único que hace que el E2EE
signifique algo en la pantalla de bloqueo: el proveedor nunca vio el contenido
porque nunca lo tuvo.

Lo que Google aprende es que este aparato recibió un aviso a esta hora, que es
metadato que ya tiene de cualquier app instalada. **El precio, dicho:** depender
de FCM es depender de los servicios de Google en el aparato. La alternativa —un
socket permanente con servicio en primer plano— gasta batería y Android la corta
cada vez más. Es el mismo balance que eligió Signal.

**La configuración la sirve nuestro servidor**, no un `google-services.json` en
el APK (`GET /v1/push/config`, y Firebase se inicializa a mano). Así el push se
habilita poniendo variables en el servidor, **sin recompilar ni publicar**, y no
hay un JSON de credenciales por entorno dentro del repositorio. Lo único secreto
—la clave privada de la cuenta de servicio— no sale del servidor.

**Tres decisiones del envío:**

- **Se coalesce.** Veinte mensajes seguidos no son veinte avisos: como el aviso
  no lleva contenido, el segundo no consigue nada que el primero no haya
  conseguido ya. Ventana de 12 s por aparato. Sin eso, un grupo activo produce
  una petición HTTP a un tercero por mensaje y por aparato.
- **Un push que falla no rompe la entrega.** El sobre ya está en la base; el
  push solo adelanta el momento. Va en un hilo aparte para no meterse en el
  tiempo de respuesta de quien envió.
- **Un token muerto se borra, no se reintenta.** `UNREGISTERED` o 404 significan
  app desinstalada: se limpia la columna. Los demás fallos suman a `push_fallos`
  y a los diez se deja de intentar, porque si no el servidor gasta una petición
  por cada mensaje que le llegue a esa cuenta, para nada.

**Qué está probado y qué no.** El camino entero está verificado contra un FCM de
mentira (`pruebas/stub-fcm.mjs`): el JWT RS256 con una clave RSA real, el
intercambio OAuth2, el envío, el formato del payload y la coalescencia. Lo que
**no** se puede probar sin credenciales es que Google acepte la firma. El
cliente compila con el SDK, inicializa Firebase y falla al pedir el token con
*"Please set a valid API key"* —exactamente lo esperado con una clave de
mentira— y la app sigue funcionando igual.

### N.2 · El tema claro no es el oscuro invertido

Estuvo declarado fuera de alcance todo el proyecto, y la razón era real: el cian
de marca `#6CF8F6` da **1.28:1 sobre blanco**. Como texto es invisible; como
relleno con texto blanco encima, también.

Lo que hizo falta fue **rederivar los acentos midiendo**. Los valores elegidos
son los primeros que pasan 4.5:1 contra las tres superficies claras **y** 4.5:1
con texto blanco encima cuando son relleno:

| Token | Claro | Peor contraste como texto | Con blanco encima |
|---|---|---|---|
| Cian | `#0B6E6D` | 5.11:1 | 6.06:1 |
| Ámbar | `#8A5300` | 5.33:1 | 6.33:1 |
| Coral | `#B3301A` | 5.28:1 | 6.26:1 |
| Slate (borde) | `#7E8E8E` | 3.02:1 | — (umbral 3:1 de componente) |
| Texto primario | `#0F1717` | 15.32:1 | — |
| Texto terciario | `#566565` | 5.14:1 | — |

Dos detalles que no son obvios:

1. **`TextoSobreAcento` se invierte.** En oscuro el acento es luminoso y pide
   tinta oscura; en claro es oscuro y pide tinta blanca. Un solo valor para los
   dos temas dejaría ilegible la mitad de los botones.
2. **El fondo claro no es blanco** (`#E8EFEF`). En oscuro las tarjetas se
   distinguen porque son más claras; en claro, una tarjeta blanca sobre fondo
   blanco y sin sombra es invisible, y este diseño no usa sombras.

El primer gris de borde probado (`#A8B8B8`) daba 2.06:1 y al sol las tarjetas
desaparecían. Por eso el borde terminó en 3.42.

**Por qué los tokens pasaron a ser getters.** Hay del orden de mil usos de
`Cian`, `BgBase` y compañía en cincuenta archivos, escritos como nombres
sueltos. Convertirlos a un `CompositionLocal` —lo canónico— es un cambio
mecánico en mil sitios. Un getter que lee un `mutableStateOf` **sí** participa
de la observación de Compose: leerlo en composición registra la lectura y
cambiar el modo recompone todo. Funciona igual sin tocar ni una línea de las
pantallas. El precio, dicho: dejan de ser constantes de compilación.

La preferencia es **del aparato** y tiene tres valores, no dos: el sistema ya
sabe cuándo es de noche, y una app que lo ignora obliga a cambiarla a mano dos
veces al día. El defecto sigue siendo oscuro: es el tema con el que se diseñó
todo, y cambiarle el aspecto a quien ya tiene la app instalada sin que lo pida
no es una mejora.

### N.3 · Tablet y escritorio

Lista a la izquierda con ancho **fijo** (360 dp) y conversación a la derecha con
el resto. Fijo y no por pesos: una lista de chats no gana nada con ser más ancha
—los títulos ya entran— y una conversación sí; con pesos, en un monitor la lista
terminaría ocupando 600 dp de nombres cortos.

**La selección no es navegación.** Con dos paneles, tocar un chat no apila un
destino: la lista nunca desapareció, así que el "atrás" del sistema no tendría
nada que deshacer y el historial se llenaría de un destino por chat mirado. En
pantalla angosta sigue siendo navegación, porque ahí sí desaparece la lista.

**El umbral mira el alto y no solo el ancho, y eso se aprendió probando:** un
teléfono acostado tiene de sobra los 720 dp de ancho pero sólo unos 415 de alto,
y ahí la cabecera de la lista —título, aviso de conexión, buscador y filtros— se
come casi todo, dejando dos paneles apretados en vez de uno usable. Con el
mínimo de 480 dp de alto, un teléfono horizontal se queda con un panel y una
tablet entra en dos. Verificado en una geometría de tablet (1066 × 1706 dp).

**Atajos: dos, no diez.** `Esc` cierra el buscador del chat y `Ctrl+F` lo abre.
Son los que cualquiera prueba sin que se los digan; un atajo que nadie descubre
es código muerto. Van en `onPreviewKeyEvent` porque si esperaran el turno normal
el campo de texto se comería el Escape.

### N.4 · El `Hub` fuera del proceso

Los sockets viven en la memoria del proceso. Con una instancia eso es correcto y
es lo más rápido que hay. Con dos, **un mensaje para alguien conectado a la
instancia B no llega** si lo manda alguien conectado a la A: el `Hub` de A mira
su mapa, no lo encuentra y deja el sobre en la base, que la persona toma recién
al reconectar.

Estuvo declarado pendiente desde el módulo 0 y con razón: no hacía falta hasta
que hubiera una segunda instancia.

**Lo que pasa por Redis son los mismos bytes opacos que pasaban por Postgres.**
Se publica el `Bajada` tal como iba a salir por el socket, o sea el sobre ya
cifrado. No se agrega un sitio donde el contenido esté en claro: quien
administre el Redis ve lo mismo que quien administre la base, que es nada.

**La presencia vence, y ése es el punto.** Es la trampa que el propio comentario
del `Hub` ya nombraba: una marca de "está conectado" que un proceso deja puesta
al morir se queda en `true` para siempre, y entonces la plataforma miente justo
sobre el dato que la gente usa para decidir si la están ignorando. Así que cada
instancia renueva las marcas de **sus** sockets cada 30 s y duran 90. Si una
instancia muere, sus marcas se apagan solas en minuto y medio: peor que
instantáneo, muchísimo mejor que para siempre, y el límite está dicho.

La presencia por **persona** es un conjunto de ids de instancia y no una clave,
porque con multi-dispositivo la misma cuenta puede tener el teléfono en una
instancia y la tablet en otra, y una clave de un solo valor haría que la segunda
en escribir borrara la marca de la primera.

**Sin `WTFUCK_REDIS_URL` todo es un no-op**: exactamente el comportamiento de
una sola instancia, que es el caso normal. No hace falta levantar Redis para
usar wtfuck.

Verificado con **dos instancias de verdad** (8300 y 8301) en `pruebas/bus.mjs`:
el sobre cruza, el acuse vuelve cruzando al revés, la presencia se ve desde la
otra instancia y se apaga al cerrar el socket.

### N.5 · Accesibilidad

El último punto del §15 que quedaba. Los botones de icono ya tenían su
`contentDescription` desde el principio; **el hueco estaba en las filas
compuestas**, y era peor.

**Una fila de la lista de chats eran seis paradas**: avatar, nombre, quién
habló, el último mensaje, la hora, el globo de no leídos. Para enterarse de que
"@tatiana escribió y hay tres sin leer" había que recorrer las seis y armar la
frase uno mismo. Con la vista esa fila es **una** cosa que se lee de un golpe;
sin la vista debería serlo también. Ahora es un nodo:

```
Grupo Equipo seguridad, Tu: atajos y tema claro ok, leido, 18/09/26
```

**El check de estado no se anunciaba en absoluto.** Tenía
`contentDescription = null`, que es correcto mientras el color y la forma lo
expliquen a la vista e inservible cuando no hay vista: `DoneAll` es "entregado"
**y** "leído", y lo único que los separa es el tinte. Ahora va en
`stateDescription` y no pegado al texto, porque TalkBack vuelve a anunciar un
`stateDescription` cuando **cambia**: "enviado" se convierte en "entregado" sin
tener que salir y volver a enfocar la burbuja.

**Las encuestas eran `Box` con `clickable`**, así que se leía el texto y nada
más: ni que se podía elegir, ni si estaba elegida, ni que elegir una desmarca
las otras. Ahora son `selectable` con rol de radio o casilla según la encuesta
admita una respuesta o varias. Y la descripción lleva el **porcentaje** porque a
la vista la barra de fondo es exactamente esa proporción: con los votos crudos
se tienen los números pero no la comparación, que es lo que la barra comunica de
un vistazo.

```
6pm, 2 votos, 100 por ciento     [radio, marcada, 276x48dp]
```

**Lo que NO se dice.** Un mensaje retirado se anuncia como eliminado y **no se
lee su texto**: la fila se conserva para que el hueco se vea, y leer en voz alta
lo que decía sería deshacer el borrado justo para quien no puede comprobar que
ya no está. La accesibilidad no es una puerta lateral a la privacidad; es la
misma regla por la que la notificación tampoco muestra el texto.

#### Lo que sólo se vio volcando el árbol de accesibilidad

Dos cosas que ninguna prueba de texto podía encontrar, y que salieron al mirar
lo que el emulador realmente expone (`uiautomator dump`):

1. **Fusionar la burbuja se tragaba los controles de la encuesta.** El nodo de
   "Sala 3" salía con `clickable=false`: al fusionar, las opciones dejaban de
   ser nodos propios y perdían su rol y su acción. Fusionar está bien para un
   bloque que sólo se **lee** y mal para uno que se **opera**, así que una
   encuesta o un evento ya no se fusionan.
2. **Dos blancos táctiles por debajo del mínimo**: las opciones de encuesta
   medían 34 dp y las pastillas de reacción 32, contra los 48 que pide la guía.
   Se arreglan con `minimumInteractiveComponentSize`, que sube el **área que
   responde al dedo** sin agrandar el dibujo: una encuesta de doce opciones de
   48 dp no entra en la pantalla, y una fila de reacciones de 48 debajo de cada
   burbuja es un muro.

Medido después del arreglo, todo lo tocable del chat está en 48 dp o más.

**Cómo se prueba, y por qué en dos sitios.** Las frases son texto puro —entra un
estado, sale una oración— y se fijan con 21 pruebas de JUnit. Que el nodo se
fusione, que el rol sea el correcto y que el área llegue a 48 dp **no se puede
ver desde JUnit**: eso se comprueba volcando el árbol del emulador, que es
exactamente donde aparecieron los dos defectos de arriba.

## Módulo O · Historias ✅

*Los "estados" de WhatsApp, las "historias" de Telegram: contenido que se
publica a una audiencia y caduca a las 24 horas.*

> Brief §3 (*"permitir respuestas a historias/estados"*) — el único apartado de
> privacidad que quedaba sin implementar.

- [x] O.1 Esquema: historia, audiencia congelada, vistas, ajuste de privacidad
- [x] O.2 Resolución de audiencia con los cuatro niveles
- [x] O.3 Publicar, listar, retirar, caducar
- [x] O.4 Vistas con reciprocidad de confirmaciones de lectura
- [x] O.5 Autorización y pruebas — 91 en `pruebas/historias.mjs`
- [x] O.6 Interfaz
- [x] O.7 Foto y video
- [x] O.8 Responder a una historia — cierra el §3 del brief
- [x] O.9 Sin sobre no hay historia
- [x] O.10 Reaccionar con un toque

**Se siguió la regla de trabajo del proyecto**: diseño y esquema primero,
servidor con autorización y pruebas después, y la interfaz al final.

### O.6 · La interfaz, y tres cosas que rompió al llegar

Tres piezas: la fila de anillos arriba de la lista de chats, el visor a pantalla
completa y el compositor.

**La fila agrupa por autor.** Alguien que publica cinco cosas es *una* entrada,
no cinco: la fila no es una lista de historias, es una lista de personas que
tienen algo que contar. El anillo lleno es lo único que separa "hay algo nuevo"
de "ya lo viste", y por eso lo no visto va primero — en una fila larga, lo nuevo
al final es lo mismo que no estar.

**El visor** lleva una barra por historia, y la actual se llena con el tiempo.
No es decoración: es lo único que dice cuántas quedan y cuánto falta en una
pantalla sin ninguna otra referencia. Tocar a la izquierda va atrás, a la
derecha adelante, y mantener pulsado pausa — con `tryAwaitRelease`, para que la
pausa también termine si el gesto se cancela y no deje la historia congelada.

#### Llevar esto al cliente destapó tres cosas

**1. Un sobre no podía existir sin conversación.** Toda la mensajería cifrada de
este proyecto vivía dentro de un chat. Una historia no: se publica a gente con
la que puede no haber ninguno. Obligar a abrir una conversación con cada persona
de la audiencia sería crear chats vacíos en la pantalla de otro sólo para que el
buzón tenga dónde apoyarse. V29 hace la columna opcional —nunca tuvo clave
foránea, así que no era una garantía de integridad sino un dato de enrutado— y
la ruta `/v1/historias/{id}/sobres` valida los destinos contra la audiencia
congelada. Sin esa validación sería una forma de meterle bytes en el buzón a
cualquiera saltándose las conversaciones **y los bloqueos**.

**2. Room encontró dos derivas que yo no vi.** La migración creaba la tabla con
`DEFAULT` en SQL, pero los valores por defecto de la entidad son del constructor
de Kotlin, que es otra cosa: un `DEFAULT` de columna vale para cualquier
`INSERT`. Y el índice existía en la base pero no estaba declarado en `@Entity`.
Room abortó con "Migration didn't properly handle", ruidosamente y en
desarrollo, que es exactamente para lo que existe esa validación.

Corregir la migración arregla a quien no la corrió; a quien ya la corrió, no,
porque el runner no repite una versión aplicada. De ahí la 11→12, que **borra y
rehace** la tabla. Se puede hacer sin pensarlo dos veces porque lo único que hay
dentro son historias, que duran 24 horas y se vuelven a pedir: es la única tabla
de esta base de la que se puede decir eso.

**3. El contenido que llegaba antes que el metadato se borraba solo.** Éste es
el bueno. Contenido y metadato llegan por caminos distintos —uno por el buzón
cifrado, otro por HTTP— y quedó documentado que cualquiera puede llegar primero.
Después se manejó mal: al guardar contenido huérfano se dejaba `expiraEn = 0`, y
el limpiador borra lo que tenga `expiraEn < ahora`. Cero es menor que ahora, así
que **el limpiador se llevaba justo la fila que traía el texto descifrado**, y
el metadato la recreaba vacía. La pantalla decía "no se pudo descifrar" sobre
algo que sí se había descifrado.

Cuesta encontrarlo porque todo lo demás funciona: el sobre llega, se descifra,
se guarda, y el log lo confirma. Lo que falla es una condición de borrado a tres
funciones de distancia. Se vio poniendo trazas en el receptor y comprobando que
el contenido **sí** entraba — o sea, dejando de suponer y midiendo.

Ahora el contenido huérfano nace con caducidad estimada —el sobre acaba de
llegar, así que la historia es de ahora— y el limpiador no toca lo que todavía
no tiene metadato.

### O.7 · Foto y video

Una historia de solo texto es media funcionalidad. Esta entrega añade el archivo,
y lo interesante no es subirlo: es **de quién cuelga**.

#### Un adjunto tenía un solo dueño posible, y no servía

Hasta aquí todo adjunto colgaba de una conversación, y de ahí salía su
autorización: quien pertenece a la conversación puede bajarlo. Una historia no
tiene conversación —se publica a una audiencia, que es otra cosa—, así que sin
tocar el esquema la única salida habría sido inventarle un chat a cada historia:
exactamente lo que se evitó en V29 con los sobres.

La migración **V30** añade `adjunto.historia_id`, hace `conversacion_id` opcional
y mete un CHECK que exige **exactamente uno** de los dos. El CHECK no es
decoración: un adjunto sin dueño es un archivo que nadie puede autorizar —y al
que, por tanto, podría cualquiera—; con los dos, son dos caminos de
autorización distintos sobre el mismo objeto y **gana el más permisivo**. Los dos
casos son agujeros y los dos los para esa línea.

Dos columnas y no una reutilizada porque son dos preguntas distintas: en un chat
se mira `participante`, en una historia se mira `historia_destino` —la misma
lista congelada a la que se le cifró el sobre—. Meter los dos ids en el mismo
campo obligaría a adivinar cuál es en cada consulta, y adivinar en una
comprobación de acceso es cómo se filtran archivos.

Reservar contra una historia es solo del autor. Leer, del autor **o** de quien
estaba en la audiencia, y respetando caducidad y retirada: una historia vencida
deja de existir y su archivo también, o quedaría una URL viva de algo que la
pantalla ya no muestra, que es la peor clase de sobra: invisible. Y **404, no
403**: confirmar que el archivo existe ya dice algo de una historia que no te
tocaba ver.

Las tres comprobaciones se validaron **revirtiéndolas**. Con el chequeo anulado,
tres pruebas pasaron a rojo —ajeno que baja, vencida que baja, retirada que
baja— y volvieron a verde al restaurarlo. Una prueba de seguridad que no falla
contra el código vulnerable no prueba nada.

#### Lo que la suite en verde no vio

La barrida reservaba y leía, pero **no confirmaba**. Con eso, las 74 pruebas
pasaban y la app reventaba en la mano: `confirmar` leía `conversacion_id` —que
ahora puede ser nulo— en un tipo de Kotlin que no admite nulos, y devolvía un
500 justo después de subir el archivo.

Salió bien en un sentido: la publicación **retira la historia** si la subida
falla, así que lo que quedó no fue una historia anunciada que nadie puede abrir,
sino ninguna historia. El fallo barato, que es para lo que está ese rollback.
Pero el hueco era de la prueba, y ahora `pruebas/historias.mjs` sube los bytes
al almacén y confirma, que es el paso que faltaba.

#### La miniatura primero, el archivo después

Lo que viaja en el sobre es la referencia al archivo, su clave AES y una
miniatura de unos pocos KB. El archivo entero se pide **al abrir la historia**,
no al recibirla: bajar de oficio lo que quizá nadie mire es gastar los datos de
otra persona por una decisión que no tomó. Mientras baja se ve la miniatura, no
un rectángulo gris.

Un video dura lo que dura el video —pasarlo a los cinco segundos sería no
dejarlo ver— pero esa duración **la declara quien lo subió** y nadie la comprueba
contra el archivo, así que va acotada por los dos lados: sin tope de arriba, un
número inventado deja la pantalla clavada sin ninguna pista de por qué.

#### Room 12→13 y la migración que sí puede borrar

Once columnas nuevas en `historia`. Se **borra y rehace** la tabla, como la
11→12, y se puede decir eso de esta tabla y de ninguna otra: lo único que hay
dentro son historias, que duran 24 horas y se vuelven a pedir al servidor. Con
`mensaje` —la única copia del historial— no habría más remedio que copiar fila
por fila.

#### Dos cosas que solo se vieron con la app en la mano

El medio va **a sangre**, detrás de la cabecera: una foto con márgenes dentro de
una pantalla negra se lee como un error, no como una decisión. Pero entonces el
nombre y la hora en blanco caen sobre lo que haya, y sobre una foto clara no se
leen. Un velo plano apagaría la foto entera, que es justo lo que se vino a ver:
va un degradado que se come el fondo donde estorba y desaparece donde no. Y
`statusBarsPadding`, porque la altura de la barra de estado la decide el
aparato y no este código.

Verificado en los dos emuladores: foto y video publicados desde `@joaquin`,
recibidos, descifrados y reproducidos en `@tatiana`.

### O.8 · Responder a una historia

El §3 del brief pedía *"permitir respuestas a historias/estados"*. Es el último
apartado que quedaba, y lo interesante es cuánto código NO hizo falta.

#### No hay ruta nueva, y eso es la decisión

Responder a una historia **es** mandar un mensaje directo a quien la publicó. La
única parte propia es la cita, y la cita es contenido: viaja dentro del sobre
cifrado, donde el servidor no la ve. Una ruta `/v1/historias/{id}/responder`
habría obligado al servidor a saber que ese mensaje contesta a una historia
—metadato nuevo que nadie necesita— y a volver a resolver permisos, cola de
salida y reintentos que el camino de los mensajes ya resuelve.

Del lado del servidor, entonces, **cero código nuevo**. Lo que sí hizo falta fue
comprobar que la autorización existente cubre el caso, y ahí apareció algo que
no era obvio.

#### Ver una historia no es poder contestarla

Los dos ajustes no significan lo mismo por "conocido":

| Ajuste | Quién entra |
|---|---|
| `historias: conocidos` | solo con quien hay **conversación directa** abierta |
| `historias: todos` | todo el conjunto de quienes me conocen, **incluida la libreta** |
| `escribe: conocidos` | conversación directa |

Medido, no supuesto: con `historias: todos` + `escribe: conocidos`, alguien que
solo me tiene agendada **ve mi historia y recibe 403 al intentar contestarla**.
Está bien que sea así —publicar para todos no debería obligar a aceptar mensajes
de todos—, pero significa que el botón de responder puede fallar, y entonces
tiene que **decirlo**. `responderHistoria` devuelve `Result` y el motivo del
servidor llega tal cual a la pantalla.

La primera versión de esa prueba daba por hecho que `conocidos` significaba lo
mismo en los dos ajustes. Falló, se midió, y la sección quedó escrita sobre lo
que el servidor hace de verdad. También queda fijado que **bloquear corta las
dos cosas a la vez**: deja de ver las historias y deja de poder escribir en el
chat que ya tenía. Si cortara solo una, bloquear no serviría de nada.

#### La cita va copiada, y no se le cree

Una historia dura 24 horas; la respuesta se queda en el chat para siempre. Con
solo un id, al día siguiente el hilo quedaría contestando a nada: por eso viajan
también el texto y la miniatura.

Y como el servidor no ve la cita, **quien responde puede inventarla**. No es
grave —quien la recibe es el autor de la historia y sabe cuáles son las suyas—
pero de ahí sale una regla del cliente: antes de guardar la cita se comprueba
que esa historia exista en este teléfono y sea **propia**. Si no cuadra, se
descarta la cita y el mensaje se guarda igual, porque el texto es de quien
escribe y no hay motivo para perderlo.

Eso se vio funcionando: una respuesta a una historia que el receptor ya no
tenía llegó como mensaje suelto, sin cita, exactamente como está escrito.

#### Room 13→14, con `ALTER TABLE` y no borrando

Cuatro columnas en `mensaje`. Aquí **no** se puede borrar y rehacer como con
`historia`: `mensaje` es la única copia del historial y una migración
destructiva le borraría las conversaciones a la persona sin avisar.

#### El dialógo y el teclado

El campo de responder va dentro del visor, que es un `Dialog` a pantalla
completa. Con los valores por defecto, abrir el teclado **empuja la ventana
entera** hacia arriba: la cabecera de la historia se iba fuera de la pantalla y
el campo quedaba flotando en mitad de la foto. `imePadding` no bastaba, porque
el diálogo ni siquiera recibía ese inset. Lo arregla
`DialogProperties(decorFitsSystemWindows = false)`, que le devuelve al contenido
el control de los insets.

Escribir también **pausa** la historia. Sin eso avanza sola mientras se teclea y
se termina contestando a otra cosa.

Verificado en los dos emuladores: `@tatiana` respondió una historia con foto de
`@joaquin`, y en el teléfono de `@joaquin` la burbuja llegó con la miniatura, el
rótulo "Respuesta a tu historia" y el texto.

### O.9 · Sin sobre no hay historia

Un defecto que estuvo a la vista en todas las capturas de los emuladores y que
tardé en mirar: una historia que decía **"no se pudo descifrar"**, ocupaba una
barra en el visor y encendía el anillo como si hubiera algo nuevo. Durante 24
horas.

#### El código declaraba una intención que no cumplía

El metadato se registra **antes** que los sobres —el servidor lo exige para
validar los destinos—, así que si el cifrado falla para alguien, esa persona se
queda con una historia que aparece en su lista y no se puede abrir. Eso ya
estaba escrito como la intención:

> "lo honesto es que su historia no exista para él en vez de que exista y no
> abra"

...pero no lo hacía nadie. El servidor calculaba `sinCopia`, lo devolvía, y el
cliente lo apuntaba en un `Log.w`. Fin.

Ahora, al cerrar el reparto, quien no recibió **ninguna** copia sale de
`historia_destino`. Por persona y no por aparato: quien tiene dos teléfonos y
recibió copia en uno la sigue viendo, porque sí puede abrirla.

El cálculo se hace sobre las copias de esa petición y no consultando la cola,
porque **un sobre se borra al entregarse**: mirar `sobre_pendiente` daría por no
cubierto a quien ya lo recogió.

`ultimoLote` existe para el día en que el reparto se parta en tandas. Hoy el
cliente manda todas las copias de una vez; una tanda intermedia con `true`
borraría de la audiencia a todo aquel a quien todavía no le tocaba, y eso hay
que poder decirlo antes de que pase.

Validado por reversión: con la limpieza anulada, las dos pruebas que la fijan se
ponen en rojo.

#### Y una red de seguridad en el cliente

El sobre y el metadato llegan por caminos distintos y cualquiera puede llegar
primero, así que un hueco de medio segundo es normal. La fila ahora **no
anuncia** a un autor cuyas historias no tienen ninguna con contenido, y el visor
usa el mismo filtro: si no se anuncia, tampoco se abre.

Se oculta en vez de mostrar en gris porque encender la entrada cuando llega el
sobre se ve bien; apagarla después de haberla anunciado, no. La regla vive en
`autoresConHistorias`, fuera del composable, con cinco pruebas: es facil de
romper sin darse cuenta al tocar el orden.

### O.10 · Reaccionar con un toque

Seis emojis encima del campo de responder. Lo interesante es lo que **no** se
construyó: una reacción **es** una respuesta cuyo texto es un emoji. Ni ruta
nueva, ni tabla nueva, ni tipo de carga nuevo. Llega al chat citando la historia
como cualquier otra respuesta, y eso es justo lo que hace falta que pase: quien
publicó tiene que poder contestar a la reacción, y para eso tiene que ser un
mensaje.

Van **encima** del campo y no dentro de un menú porque son un toque; a dos
toques se vuelven lo mismo que escribir, que ya está justo debajo. Seis y no una
rejilla entera: esto es para contestar sin pensar, y cien emojis obligan a
elegir.

> **Trampa de herramienta que costó un archivo entero.** El parche que añadió
> esto se escribió con un script de Python que abría el `.kt` en modo `'w'` y
> escribía al final. `open(..., 'w')` **trunca el archivo al abrirlo**: cuando el
> `write` falló —los emojis iban como pares suplentes `\ud83d\ude02`, que UTF-8
> no admite— el archivo quedó en cero bytes, y este proyecto no está en git. Se
> reconstruyó a mano desde los comentarios y las pruebas.
>
> Dos reglas que salen de ahí: escribir a un temporal y renombrar, y poner los
> caracteres no-ASCII **literales** o con `\U0001F602`, nunca como suplentes
> sueltos.

---

## Módulo P · Tipos de cuenta ✅

*Tres clases de cuenta —personal, desarrollador y empresa— con ficha pública para
la última. En beta cerrada.*

- [x] P.1 Esquema: `usuario.tipo_cuenta` + `perfil_empresa`
- [x] P.2 Las tres autorizaciones, que no son la misma
- [x] P.3 La puerta de la beta, cerrada en el servidor
- [x] P.4 Pruebas — 51 en `pruebas/cuentas.mjs`
- [x] P.5 Interfaz: elegir tipo, ficha y tarjeta en el perfil
- [x] P.6 Diagnóstico para el modo desarrollador

### Por qué es un eje nuevo y no más `staff_nivel`

La plataforma tenía un solo tipo de cuenta y un eje aparte, `staff_nivel`, para
el poder sobre la plataforma. Faltaba responder otra pregunta: **qué clase de
cuenta es esta**. Mezclarlos habría significado que declararse empresa diera
poder de moderación, o que un moderador no pudiera tener ficha. Son dos ejes y
van en dos columnas: un moderador puede tener ficha de empresa; una empresa no
modera nada por serlo.

Una columna con CHECK de tres valores y no dos banderas, porque con
`es_desarrollador` + `es_empresa` el estado "las dos a la vez" sería
representable y habría que decidir qué significa en cada pantalla.

### Las tres autorizaciones

Es tentador resolverlo con un solo "¿puede cambiar su tipo?". No alcanza:

| Operación | Quién | Por qué |
|---|---|---|
| Elegir `normal` ⇄ `empresa` | la propia persona | es una declaración sobre uno mismo, como el estado |
| Otorgar `desarrollador` | **staff (80)** | un modo con capacidades técnicas que uno se activa solo es una escalada de privilegio con otro nombre |
| Verificar una empresa | **staff (80)** | cualquiera escribe el nombre de un banco en su ficha; el distintivo dice que alguien lo comprobó |

La tercera es la delicada. **Sin separar declarar de verificar, la ficha de
empresa sería una herramienta de suplantación** —y de las buenas, porque vendría
con la credibilidad de la plataforma detrás—. Por eso:

- La ficha nace **sin verificar** y la pantalla lo dice con esas palabras.
- **Editar la ficha borra la verificación.** Si no, bastaría verificarse con
  datos limpios y cambiar el nombre después.
- Queda en la bitácora **quién la firmó**: un distintivo puesto por error sin
  rastro no tiene a quién preguntarle.

Y un detalle que parece menor: quien es `desarrollador` **no se quita el modo
solo**. Si pudiera, el registro de quién lo tiene dejaría de ser fiable —bastaría
apagarlo un momento para que una revisión no lo viera—.

### La beta se cierra en el servidor

Por ahora el módulo solo lo ve `@joaquin` (`WTFUCK_CUENTAS_BETA`, que por
defecto toma `WTFUCK_PROPIETARIO`). Y se cierra **en el servidor**: las rutas
responden 404 a quien no está en la lista.

Esconder el botón y dejar la ruta abierta es exactamente lo que el §16 del brief
prohíbe —*"nunca confiar únicamente en permisos enviados por el cliente"*— y
además no funcionaría: la ruta se descubre leyendo el APK. 404 y no 403 porque
un 403 confirma que la funcionalidad existe, y en una beta eso ya es
información.

La lectura de capacidades **sí** responde a todo el mundo, con
`puedeElegirTipo: false`. Cerrarla también obligaría al cliente a distinguir "no
puedo" de "falló la red", que son dos cosas y se dibujan distinto.

Validado por reversión: con la puerta abierta, cinco pruebas se ponen en rojo.

### Lo que la ficha no acepta

Todo esto acaba en un perfil público que lee otra gente:

- **Categoría y tamaño son listas cerradas.** Con texto libre habría cuarenta
  formas de escribir "educación" y el directorio dejaría de servir para lo único
  que sirve una categoría: encontrar a alguien sin saber su nombre.
- **El sitio web tiene que ser `https://`.** Ese texto acaba siendo un enlace en
  el perfil de alguien, y un `javascript:` ahí es una trampa, no una página.
- El nombre, la ubicación y el año se rechazan si no son creíbles; la
  **descripción se recorta** en vez de rechazarse, porque es contenido y tirar
  el guardado entero por pasarse de largo sería perder lo que la persona
  escribió.

Verificado en el emulador con la cuenta `@joaquin`: cuenta cambiada a empresa,
ficha guardada, tarjeta dibujada en el perfil con el aviso de "sin verificar", y
el distintivo apareciendo al verificarla.

### P.6 · Qué hace el modo desarrollador

Una pantalla de diagnóstico: versión, a qué servidor apunta, el id de este
aparato, cuánto hay en la cola de salida, cuántos envíos fallaron y cuánto ocupan
los archivos locales. Más dos acciones —resincronizar y empujar la cola— y un
botón de copiar todo.

**Por qué existe.** Hasta ahora, para saber por qué un mensaje no salía había que
enchufar el teléfono y leer `logcat`. Eso funciona en un escritorio con el SDK
al lado; no funciona cuando el aparato que falla está en otra ciudad. Son los
seis números que se miran primero, siempre, y ahora se pueden pedir por chat.

**Por qué la puerta puede ser un tipo de cuenta.** Todo lo que muestra es estado
**de este teléfono**: nada sale del aparato y nada es de otra persona. Es lo
mismo que se vería con el teléfono en la mano.

Eso es justo lo que hace que el modo sea seguro de repartir. Si la pantalla
mostrara datos de otras cuentas, la puerta no podría ser un tipo de cuenta:
tendría que ser `staff_nivel` y una consulta autorizada en el servidor, como el
panel de moderación. La línea está escrita en la propia pantalla, donde se lee.

**Las dos puertas son independientes, y se comprobó.** `@tatiana`, que **no**
está en la beta, es desarrolladora: ve "Diagnóstico" y **no** ve "Tipo de
cuenta". Son dos preguntas distintas —qué soy, y si puedo cambiarlo— y se
contestan por separado.

### Qué significa "todos" cuando hay cifrado de por medio

Este es el punto que decidió el diseño entero, y merece decirse antes que nada.

En los otros ocho ajustes de privacidad, `todos` significa "cualquiera que
pregunte": alguien pide tu foto y el servidor decide. **Para las historias no
puede significar eso.** Una historia va cifrada contra dispositivos concretos, y
publicar "para todos" en el sentido literal sería cifrar contra los aparatos de
cuarenta mil personas que no saben que existís. No es que sea caro: es que no es
lo que nadie quiere decir al elegir esa opción.

Así que la audiencia sale siempre de un **conjunto acotado** —la gente con la
que ya hay una relación en la plataforma— y el nivel decide sobre él:

| Nivel | Quién |
|---|---|
| `todos` | Todo el conjunto: con quien hablás **y** quien te tiene agendado |
| `conocidos` | Sólo con quien hay una conversación directa abierta |
| `nadie` | Nadie |
| `personalizado` | El conjunto filtrado por la lista, con sus modos `solo` y `salvo` |

La diferencia entre los dos primeros es real y útil: *"cualquiera que me tenga
agendado"* no es lo mismo que *"con quien hablo"*.

### La audiencia se congela al publicar

Se resuelve una vez y se guarda. Quien abra una conversación conmigo mañana no
verá lo de hoy.

No es una simplificación pendiente de arreglo: es lo que significa cifrar contra
destinatarios concretos. La historia se cifró contra los aparatos que existían
entonces, y quien no estaba no tiene con qué abrirla. Mostrársela en la lista
sería ofrecerle algo que no puede leer. Es también lo que hace WhatsApp, y por
el mismo motivo.

### La resolución de privacidad va al revés que todo lo demás

Todos los ajustes de este proyecto se resuelven preguntando *"¿puede este
observador ver lo mío?"* — `buscable`, `permiteCon`, y compañía. Las historias
preguntan lo contrario: *"¿a quiénes les toca lo mío?"*.

Esa inversión no cabe en las funciones existentes, así que la resolución vive en
`Historias` con su propia consulta. Lo que sí se reutiliza es lo que de verdad
importa que no se duplique: el conjunto acotado, la lista de excepciones y sus
modos.

### Ver una historia es leer un mensaje

Si las vistas no respetaran el ajuste de confirmaciones de lectura, **las
historias serían la puerta de atrás del interruptor que alguien apagó a
propósito**: apagás las confirmaciones, y la otra persona igual se entera de que
estuviste.

Así que quien lo tiene apagado no registra la vista —la fila no se crea, no hay
nada que esconder después— y por la misma reciprocidad tampoco ve quién vio las
suyas. Y se **dice** por qué: la lista vacía viene con un motivo, porque un
vacío sin explicación se lee como "no le interesó a nadie".

Por eso `vistas` es `null` y no `0` cuando no se puede saber. Cero es una
afirmación distinta, y es falsa.

### Caduca sin barrendero

Todas las consultas filtran por `expira_en > now()`. Una historia vencida deja
de existir para quien pregunta **en el instante en que vence**, sin depender de
que un proceso haya pasado. El borrado físico es higiene y va aparte, sin prisa.

La prueba envejece una historia por SQL en vez de esperar: lo que se comprueba
es que la consulta filtra, no que el reloj avanza.

### Un bloqueo tapa también lo de antes

El filtro de bloqueo está en la consulta de lectura y no sólo al publicar. Si
sólo estuviera al publicar, bloquear a alguien dejaría vivas sus historias
anteriores —que es justamente lo que quien bloquea quiere que desaparezca.

### Dos cosas que salieron mal y lo que enseñan

**El mismo campo, dos listas.** `Privacidad.PERSONALIZABLES` en el protocolo y
el `CHECK ajuste_valido` en la base son la misma lista escrita dos veces. Se
separaron en cuanto se agregó un ajuste: el protocolo ya ofrecía `historias`
mientras la base seguía rechazándolo, y guardar una excepción daba **500**. El
error no es el CHECK —es el último sitio donde una lista mal escrita se detiene
antes de quedar guardada— sino la duplicación, y queda anotada en la migración.

**Construir por posición.** Agregar `historias` en medio de `Privacidad` hizo
que una construcción posicional de diez argumentos empezara a pasar un `Boolean`
donde iba un `String`. Ahora va con nombres: un campo nuevo da un error que
señala exactamente lo que falta, en vez de correr los demás un lugar.

De paso, una prueba que fijaba el número **7** de ajustes personalizables pasó a
comprobar **cuáles**. Un "8" no dice cuál sobra ni cuál falta.

### N.13 · Deslizar para responder

El gesto más característico de un mensajero moderno, y el que más decisiones
pequeñas tiene detrás.

#### Hacia la derecha, siempre

Este gesto **no tiene afordancia visible**: nadie lo descubre leyendo la
pantalla, se descubre porque ya lo conoce de otra app. Eso deja una sola
decisión razonable sobre la dirección —la que la gente ya tiene en los dedos— y
es hacia la derecha, para mensajes propios y ajenos por igual.

Hacer que los propios vayan a la izquierda «para acompañar el lado de la
burbuja» suena simétrico y es peor: obliga a pensar antes de cada gesto.

#### La resistencia es la mitad del gesto

Hasta el umbral la burbuja sigue al dedo uno a uno: ahí el gesto todavía se está
decidiendo, y cualquier retardo se siente como lentitud, no como resistencia.
Pasado el umbral avanza a la mitad, y nunca más allá del máximo.

Eso es lo que hace que el dedo **sienta** que llegó. Un tope que se nota vale
más que un icono que hay que mirar, y por eso el máximo es mayor que el umbral:
si fueran iguales, la burbuja se pararía justo donde se activa y las dos señales
se confundirían en una. Hay una prueba que fija exactamente eso.

Y una vibración corta al cruzar, **una sola vez**. Repetirla mientras se
arrastra de un lado a otro del umbral convierte una señal en un zumbido.

#### Un gesto invisible es invisible para quien no ve

Además del arrastre hay una **acción de accesibilidad** con el mismo nombre. Sin
ella, responder deslizando sería una función que existe sólo para quien puede
arrastrar: el menú de mantener pulsado seguiría estando, pero el atajo no, y un
atajo que excluye no es un atajo.

#### Detalles que se ven al usarlo

- **Un mensaje retirado no se responde.** No queda nada a qué responder, y
  ofrecerlo sería citar un hueco.
- **El desplazamiento se reinicia al reciclarse la fila.** Una `LazyColumn`
  reutiliza las filas; sin esto, una burbuja podría aparecer ya desplazada
  heredando el gesto de otro mensaje.
- **El arrastre se consume** para que la lista no lea el mismo movimiento como
  desplazamiento. Verificado que el desplazamiento vertical sigue funcionando:
  los dos gestos conviven.
- **La onda de una nota de voz gana** cuando está sonando, porque su arrastre
  es interior y se consume antes. Es lo correcto: ahí el gesto horizontal
  significa adelantar, no responder.

### N.12 · Adelantar y velocidad en las notas de voz

Dos cosas que en un mensajero de hoy no son extras: saltar a un punto de la
nota, y escucharla más rápido.

#### Adelantar

Se toca —o se arrastra— sobre la forma de onda y salta a ese punto. Van los dos
gestos a propósito: el toque es lo que se intenta primero, y el arrastre es lo
que se espera *después* de descubrir que el toque funciona.

La fracción se acota dentro del reproductor y no en quien llama, porque viene de
un dedo sobre una barra: un valor fuera de rango no es un caso raro sino el caso
normal cuando alguien arrastra hasta el borde.

Se usa `SEEK_CLOSEST` y no el salto al punto de sincronización más cercano: en
un audio de voz esos puntos están lejos, y caer un segundo antes de donde se
tocó se nota.

Y no hace nada si no hay nada sonando. Adelantar una nota que no empezó no
significa nada, y arrancarla desde el medio por un roce sería peor.

#### Velocidad

Una insignia que rota entre **1x, 1.5x y 2x**. Tres y no seis: es un toque que
rota, no un menú, y un menú para esto cuesta más gestos que el beneficio que da.

Sólo aparece en **notas de voz**. En una canción o un audio que alguien mandó
como archivo, cambiar la velocidad no es lo que se quiere.

**Se recuerda entre notas y entre sesiones.** Quien escucha a 2x no lo hace para
una nota suelta: lo hace porque prefiere escuchar así. Volver a 1x en cada
burbuja obliga a repetir el gesto una y otra vez, que es exactamente la clase de
detalle que separa una función de una función usable.

Dos detalles de `MediaPlayer` que hay que saber:

- **La velocidad se aplica después de `start()`.** Ponerle `playbackParams` a un
  reproductor que todavía no arrancó **lo arranca** —es el comportamiento
  documentado—, y entonces el orden de dos llamadas decidiría si suena o no.
  Eso es una dependencia que no se ve al leer.
- **`setSpeed` puede lanzar** si el aparato no admite ese factor. Va envuelto y
  cae a 1x: que una nota suene a velocidad normal es mucho mejor que que no
  suene.

#### El área táctil y la píldora son dos cosas distintas

La primera versión pintaba el fondo sobre los 48 dp del área mínima y salía un
recuadro que **pesaba más que el botón de reproducir**, justo al lado del cual
está.

La corrección no es achicar el área —eso deja un blanco de 22 dp que no se
acierta— sino separarlas: la caja de fuera manda en el tacto y no se pinta, la
de dentro se pinta y no manda en el tacto. Es la tensión de siempre entre lo que
se ve y lo que se toca, y tiene una solución, no un punto medio.

#### Un arreglo de paso en el perfil

Al revisar la pestaña de perfil apareció algo de la misma familia: los botones
de **cambiar portada** y **cambiar foto** se dibujaban idénticos —mismo icono,
mismo color, mismo tamaño— y el de la portada, flotando sobre la franja sin
estar pegado a nada, se leía como el mismo botón repetido por error.

Que las descripciones para el lector de pantalla sí los distinguieran no arregla
nada para quien mira: **dos controles que hacen cosas distintas tienen que verse
distintos**.

La diferencia que se les dio no es decorativa, es de jerarquía. La foto es la
acción principal: acento sólido y pegada al avatar, que es lo que deja claro
sobre qué actúa. La portada es secundaria: chip oscuro translúcido —que además
es lo único legible encima de una imagen cualquiera— y con icono de imagen en
vez de cámara, porque no se hace una foto, se elige una.

#### Lo que se anuncia

Las dos son controles nuevos, así que los dos hablan: la insignia dice qué
velocidad hay **y a cuál se pasa al tocarla**, y la onda dice que se puede tocar
en un punto para adelantar o retroceder. Un control que sólo lee «1.5x» no dice
ni que es un botón ni qué hace.

### N.11 · Notas de voz

Último trozo de la zona de adjuntos, y el que más equilibrado salió: dos cosas
estaban bien pensadas, dos no.

#### Lo que estaba bien

**La forma de onda no se dibuja con datos ajenos.** Se deriva del id del
mensaje con un `Random` sembrado, así que da una figura estable y distinta por
mensaje. Es una decisión deliberada y está escrita: dibujar las amplitudes
reales obligaría a decodificar el archivo entero por cada burbuja de la lista.
Y de paso cierra el agujero que uno va a buscar — `fillMaxHeight` exige una
fracción entre 0 y 1, y ahí no entra nada de fuera.

**El avance de la reproducción sale del reproductor, no del metadato.** Usa la
duración real del `MediaPlayer` y va con `coerceIn(0f, 1f)`.

También se descartó una carrera que parecía haber: el reloj de la onda y
`detener()` corren los dos en el hilo principal —`rememberCoroutineScope`—, así
que no pueden entrelazarse. Aun así las lecturas de posición quedaron envueltas,
porque *corren en el mismo hilo* no es lo mismo que *corren en el mismo turno*:
si la liberación cae entre dos vueltas del bucle, preguntarle la posición a un
reproductor liberado lanza.

#### Grabar no tenía techo

Se podía dejar la grabación andando y sólo paraba al soltar. El límite del
servidor para una nota de voz son 16 MB, que a 64 kbps son unos **treinta y
cinco minutos**, así que lo que pasaba de verdad era que alguien grababa media
hora y **recién al subir** se enteraba de que no entraba.

Perder media hora de grabación al final es la peor forma posible de aplicar un
límite: el límite existía, pero se cobraba en el momento más caro.

Ahora `setMaxDuration` corta a los diez minutos —decisión de producto, no la
división de arriba; deja el archivo en torno a 5 MB— con `setMaxFileSize`
detrás como segunda red. Y hay una prueba que **fija la aritmética**: si algún
día el bitrate o el límite del servidor cambian de forma que una nota al tope ya
no entre, esa prueba se pone en rojo antes de que lo descubra alguien grabando.

Lo que faltaba además del tope: **avisar**. `setMaxDuration` detiene la
grabación, pero la pantalla no se entera sola, así que la barra seguiría
contando segundos sobre un archivo que ya no crece. Con el `OnInfoListener`, al
llegar al tope la nota se cierra y **se manda sola**, y se dice por qué.

#### Reproducir lo ajeno bloqueaba la interfaz

`MediaPlayer.prepare()` parsea el archivo **en el hilo que la llama**, y se
llamaba desde el `onClick` de una burbuja: el hilo de la interfaz.

Ese archivo lo grabó otra persona. Nada obliga a que sea una nota de voz de esta
app —es el mismo argumento de N.9 y N.10—, y un contenedor mal formado puede
tener al extractor del sistema trabajando mientras la pantalla no responde.

Ahora es `prepareAsync()` con `setOnPreparedListener`, que es la API que la
documentación recomienda para cualquier fuente que no sea trivial, y aquí la
fuente es ajena por definición. Se agregó también `setOnErrorListener`: un
archivo que el decodificador no entiende no puede dejar la burbuja marcada como
sonando para siempre.

#### La duración declarada

Mismo tratamiento que las fechas de N.9. Ese número lo declara quien sube el
archivo y nadie lo comprueba contra el audio —habría que decodificarlo, y el
servidor ni siquiera puede abrirlo—. Sin guarda, un negativo salía como `0:-5` y
un `Int.MAX_VALUE` como `35791:23`. Ninguno revienta nada; los dos son basura
donde debería haber un dato.

### N.10 · Adjuntos, miniaturas y la única salida a internet

N.9 miró el contenido estructurado de un sobre. Faltaba lo que viaja **al lado**
de un mensaje: el archivo, sus metadatos y la miniatura. Y de paso apareció el
único sitio donde este servidor no responde sino que **pide**.

#### Lo que ya estaba bien, que conviene decir

Antes de los hallazgos: varias cosas de esta zona estaban bien pensadas y no
hubo que tocarlas. El nombre con el que un archivo se guarda en disco es el id
del mensaje, no el nombre original —con una extensión filtrada a
alfanuméricos—, así que no hay recorrido de rutas. La proporción de una imagen
está acotada con `coerceIn`. Los índices de un voto se comprueban contra el
número real de opciones. El servidor ata un adjunto a un mensaje con un `WHERE`
que exige que sea de quien envía **y** de esa conversación.

Un barrido que sólo encuentra problemas está mirando mal.

#### El id de un GIF no se validaba en ningún sitio

Tres defectos con una sola raíz. `GET /v1/gifs/{id}/bytes` acepta cualquier
cosa, y ese id acaba en tres lugares:

1. **Interpolado en una URL hacia api.giphy.com con nuestra clave detrás.** Con
   un `../` se convierte en recorrido de rutas sobre ese host, llevando la
   clave; con un `#`, la clave se queda fuera de la petición.
2. **Interpolado en un nombre de archivo** en el teléfono —`gif-<id>.gif`—, y
   un `../` ahí escribe fuera del directorio temporal de la app, donde un poco
   más allá están sus bases de datos.
3. En la **ingesta** de los resultados del buscador, que es de donde el cliente
   saca los ids que luego pide.

Lo que lo hace fácil de pasar por alto es que uno piensa «ese id lo da Giphy».
No: lo da **quien llama a la ruta**. Ahora `FORMA_GIF_ID` vive en `protocol` y
se comprueba en los tres sitios. Validado al revés: sin la comprobación en la
ruta, doce filas de `gifs.mjs` se ponen en rojo.

#### El servidor bajaba de donde le dijeran

El GIF no se baja de una URL nuestra: se baja de **la URL que viene dentro de la
respuesta de Giphy**. O sea que era el tercero quien elegía a dónde iba este
servidor, sin lista blanca y con `followRedirects(NORMAL)`.

Para que esto se explote hace falta que Giphy devuelva algo manipulado, que no
es el escenario más probable del mundo. Pero es el patrón clásico de petición
del lado del servidor, la red interna está al alcance —el propio Postgres, el
propio Redis, la dirección de metadatos de un proveedor de nube— y el arreglo
son veinte líneas.

Ahora hay lista blanca de host —`giphy.com` y sus subdominios, sólo HTTPS— y un
cliente HTTP **aparte que no sigue redirecciones**, porque seguir una deja sin
efecto la comprobación: el salto ya no pasa por ella.

La comparación es por sufijo `.giphy.com` y no por `contains`, con una prueba
que lo fija: `giphy.com.evil.example` contiene la cadena y no es Giphy.

#### La miniatura, que es una bomba de descompresión esperando

Una miniatura llega dentro del sobre y se dibujaba pasándola directamente por
`BitmapFactory.decodeByteArray`. Un PNG de 30.000 × 30.000 de un solo color
ocupa unos pocos KB —entra de sobra en un sobre— y al decodificarlo pide
**3,6 GB**.

Envolverlo en `runCatching` no alcanza, y esa es la parte que importa entender:
captura el `OutOfMemoryError`, sí, pero para cuando salta, el proceso ya intentó
reservar esa memoria. Lo que hay que evitar no es la excepción, **es la
reserva**.

Y la técnica correcta ya estaba escrita en el proyecto: `Media.imagenReducida`
mide con `inJustDecodeBounds` y decodifica con `inSampleSize`. Estaba aplicada
en el lado que **crea** una miniatura de una foto propia, que es justamente el
que no hace falta proteger. El que recibe decodificaba a pelo. Mismo patrón que
los topes de N.9, que estaban en un campo de seis.

Detalle que merece su propia prueba: la comprobación de área se hace en `Long`.
En `Int`, `65536 * 65536` es exactamente **0** y `46341 * 46341` da negativo, así
que una comprobación escrita con `Int` daría por buenas las dos. El
desbordamiento silencioso es la clase de detalle que convierte una defensa en un
adorno.

#### El nombre de un archivo, que es donde el truco de dirección tiene su uso

Un documento llamado `factura\u202Egpj.exe` se **dibuja** como
`factura exe.jpg`. Es el uso canónico del override de dirección, y el nombre de
un adjunto es justo donde sirve: quien lee decide si abrir o reenviar mirando
ese nombre.

Aquí el archivo se guarda como `<idDelMensaje>.<ext>`, así que el disfraz no
cambia lo que se ejecuta. Pero sí cambia lo que una persona **cree** que está
recibiendo, y lo siguiente que hace es reenviarlo a alguien con otro cliente.

Se sanea en la ingesta y no al dibujar, porque tiene cuatro consumidores —la
burbuja, la insignia de extensión, la línea de la lista y la notificación— y
hacerlo en cada uno es pedir que alguno se olvide. Es la lección de `resumenDe`
en N.9, aplicada antes de tropezar con ella.

#### Y una función correcta que nadie llamaba

Al verificar todo esto en el emulador apareció algo que no era de seguridad: una
foto sin pie dejaba **la línea de la lista de chats en blanco**. Se veía una
fila con hora y contador de no leídos y ningún texto.

La causa tiene gracia: `ultimoAdjuntoClase` ya se seleccionaba en la consulta,
con un comentario que decía para qué era —*«para escribir "Foto" y no el pie»*—,
y `Media.resumen` estaba escrita para exactamente eso. Sólo faltaba unirlas.

Es la misma forma del error que cometí yo mismo en N.9 —funciones nuevas con sus
pruebas en verde mientras la app seguía sin llamarlas—, con la diferencia de que
esta llevaba tiempo ahí. Una función correcta que nadie llama no es código
muerto inofensivo: es una decisión que alguien creyó tomada.

Con lector de pantalla era peor que una línea en blanco: la conversación no
decía nada.

#### Dos cosas que se anotan y no se cambian

- **`Carga.Media` es código muerto y lleva una URL dentro.** Nadie la construye
  ni la consume; el camino real es el `adjuntoId` del metadato en claro. Se deja
  anotado porque un subtipo muerto que transporta una URL elegida por el par es
  una trampa para quien lo cablee en el futuro sin mirar de dónde sale.
- **Atar un adjunto a un mensaje es un no-op silencioso si no corresponde.** El
  `UPDATE` exige que el adjunto sea de quien envía y de esa conversación, que
  está bien; pero si afecta a cero filas, el mensaje se registra igual y sin
  adjunto, y quien lo envió recibe un 200. La autorización funciona; lo que
  falta es decirlo.

### N.9 · El par hostil, que es donde el E2EE se cobra su precio

N.7 y N.8 barrieron el servidor entero. Pero hay una entrada que **ninguna de
las dos podía tocar**, y es la más incómoda de las tres: el contenido de un
sobre.

El servidor es un buzón tonto: guarda bytes que no puede abrir. Eso es la
garantía central del producto y también su consecuencia. Un servidor normal
filtra lo que entra —longitudes, formatos, caracteres raros—; aquí, **por
diseño, no puede**. Así que entre quien escribe un sobre y quien lo dibuja no
hay absolutamente nadie.

De ahí se sigue lo que cuesta interiorizar: **el emisor de un sobre es una
entrada no confiable**, igual que una petición HTTP lo es para el servidor. No
porque las personas del otro lado sean sospechosas, sino porque nada obliga a
que del otro lado haya esta app. Quien tenga las claves de la conversación arma
el JSON a mano.

#### La regla que estaba en un campo de seis

N.6 le puso techo a las opciones de una encuesta ajena, y escribió el motivo:
*«el creador se limita solo, pero quien recibe no puede suponer que del otro
lado había esta app»*. Ese razonamiento no tiene nada de particular de las
opciones. Vale igual para la etiqueta de una ubicación, la pregunta de la
encuesta, el título de un evento, su lugar, su nota y el nombre de un contacto.

Estaban los seis sin tope. En los 64 KB de un sobre entra un título de sesenta
mil caracteres igual de bien que dos mil opciones, y el resultado es el mismo:
una fila de la lista que deja la pantalla clavada.

Es el mismo patrón que apareció en el servidor con la visibilidad de los
canales —una regla escrita en dos sitios y olvidada en el tercero—, y por eso
ahora los topes están **todos juntos** en `TopesConsulta`.

#### El `@` es una afirmación sobre identidad

Este es el hallazgo que más importa de los tres. Una tarjeta de contacto pintaba
`@` + lo que viniera en el sobre, y lo que viene en el sobre lo elige quien lo
manda. Con `username = "soporte · Administrador"`, la tarjeta se lee como una
cuenta oficial que no existe, y el botón «Abrir conversación» le da el último
gramo de credibilidad.

Ahora la forma de un username vive en `protocol` —`FORMA_USERNAME`, la misma
que usa el servidor al registrar, en vez de dos copias que se separan sin que
nadie se entere— y el cliente comprueba antes de pintar. Si no tiene forma de
cuenta: sin `@`, sin botón, y **se dice** que no es una cuenta válida.

#### Y el mismo campo, por un segundo camino

Aquí es donde el barrido se ganó el sueldo. `Repositorio.resumenDe` arma otra
vez la misma cadena, y no parece dibujo —es un texto que se guarda en la base—
pero termina en la lista de chats, en el buscador, en lo que anuncia el lector
de pantalla y **en el texto de una notificación**.

Esa última es peor que la tarjeta: un `"Contacto: @soporte · Administrador"` en
una notificación, fuera de la app y sin el resto de la conversación alrededor,
es bastante más creíble. Si el trabajo se hubiera dado por terminado al arreglar
las burbujas, este camino quedaba abierto.

Las reglas de contenido salieron de `ui` a un paquete propio para que la capa de
datos pueda usarlas sin importar de la interfaz.

#### Lo que se hace y lo que no

Se recorta lo largo y se comprueba la forma de lo que afirma ser una identidad.
**No se «limpia» el texto**: quitarle caracteres a lo que alguien escribió es la
app editando a una persona.

La única excepción son los **controles de dirección bidireccional** —`U+202E` y
compañía—, y sólo en los campos cortos. Son caracteres invisibles que reordenan
lo que se ve sin cambiar lo que dice la cadena: con uno delante, `gnp.exe` se
dibuja como `exe.png`. En un nombre o un título no hay ningún motivo legítimo
para usarlos.

**No rompe el árabe ni el hebreo**, y hay una prueba que lo fija: esos idiomas
se escriben de derecha a izquierda por la dirección propia de sus caracteres,
que es un atributo Unicode de cada letra. Lo que se quita son las marcas que
*fuerzan* una dirección distinta de la natural.

El **cuerpo de un mensaje** no lleva tope ni limpieza. Un mensaje es texto
libre: eso es el producto, no un descuido.

#### Dos cosas de método

**Las pruebas pasaron antes de que el arreglo existiera.** Al escribir las
funciones nuevas y sus 32 pruebas, todo dio verde mientras la app **seguía
dibujando los campos crudos**: las funciones existían y nadie las llamaba. Es el
mismo verde falso de siempre con otra cara — pruebas sobre código que el
producto no ejecuta.

La salida no fue otra prueba sino **hacer imposible lo incorrecto**: las
burbujas dejaron de recibir `Carga.*` y reciben el tipo ya recortado. Dibujar un
campo crudo no es un descuido posible porque no está ahí para dibujarlo, y el
único sitio donde se convierte es el despachador. Un tipo que no se puede usar
mal vale más que una prueba que comprueba que no se usó mal.

**El fuente tenía caracteres invisibles.** El script que escribió la lista de
controles los puso literales en vez de escapados: en el archivo se veían
comillas vacías y en un diff no se veía nada. Un fuente con overrides de
dirección escondidos dentro es exactamente el problema que esa lista existe para
resolver. Van escapados, y hay una comprobación de que no vuelven.

### N.8 · La otra mitad: lo que se puede leer

`ajeno.mjs` barre las rutas que **escriben** y deja dicho lo que no cubre: las
lecturas. En una app de mensajería privada esa es la mitad donde duele. Una
escritura ajena rompe algo y se nota; **una lectura ajena no deja rastro**, y es
exactamente lo que el §3 entero —quién puede ver tu perfil, tu actividad, tus
grupos— existe para impedir.

El mapa da **49 rutas de lectura**: 20 con un `{id}` y 29 sin parámetro. Y ahí
apareció un punto ciego de N.7 que conviene nombrar: `ajeno.mjs` comprobó que
una persona de a pie no puede **suspender** a nadie, pero nunca que no puede
**leer la bitácora de auditoría**. Barrer escrituras y dar el módulo por
cerrado es justo el error que esta sección corrige.

#### Tres preguntas que no son la misma

1. **¿Se niega?** Para lo ajeno: mensajes, adjuntos, miembros, denuncias,
   destinos, canales privados.
2. **¿Se niega al que no es staff?** Para las ocho lecturas del panel.
3. **¿Qué devuelve cuando sí contesta?** Varias rutas le contestan 200 a un
   desconocido **a propósito**: un canal público es público, una invitación se
   abre con el código, un perfil se consulta para poder escribirle. Ahí la
   pregunta deja de ser el código de estado y pasa a ser **qué campos viajan**.
   Un 200 correcto que arrastra la lista de suscriptores es una fuga igual.

La tercera es la que justifica la suite: un barrido que sólo mirara códigos de
estado habría dado verde entero sin probar nada sobre privacidad.

#### Lo que salió: un canal privado se leía entero

`GET /v1/canales/{id}` **no comprobaba visibilidad**. La consulta filtra por
`conversacion_id` y nada más, y eso está bien para los cuatro sitios internos
que la usan —crear, configurar, suscribirse y buscar por alias llaman después
de haber autorizado—. Pero la función que expone la ruta no comprobaba nada.

Con el id —que es el id de la conversación, o sea un valor que circula—
cualquiera leía de un canal **privado o pendiente**: nombre, alias,
descripción, cuántos suscriptores, cuántas publicaciones, el estado y el
**motivo de rechazo**. Ese último campo es texto que escribió un moderador
sobre el canal de otra persona.

Lo llamativo es que era una incoherencia **dentro del propio módulo**:
`suscribir` exige público y aprobado, y `porAlias` esconde los pendientes
diciendo por qué —*«el alias se volvería un oráculo para enterarse de lo que
hay en la cola»*—. Sólo este camino se lo saltaba. Una regla escrita en dos
sitios y olvidada en el tercero es el patrón, no la excepción; por eso el
barrido va por la lista de rutas y no por lo que uno recuerda haber hecho.

La regla ahora: si estás suscrito lees —incluido el dueño de un canal pendiente
o rechazado, que necesita ver su estado—; si no, sólo público **y** aprobado; y
si no, 404 y no 403, por el mismo motivo que `porAlias`. Validado al revés.

#### La escalera del panel, que no es un solo permiso

El panel son tres escalones —moderador 50, administrador 80, propietario 100— y
cada lectura pide el suyo: el resumen y la cola de denuncias son de moderador,
la bitácora y los límites de administrador, la cola de canales de propietario.

Comprobar «hay que ser staff» no es lo mismo que «hay que ser **este** staff».
Sin el segundo, un cambio que convirtiera la bitácora en lectura de moderador
pasaría sin que nada se pusiera rojo, porque quien ataca en la otra sección no
es staff de ningún nivel. Ahora cada fila se prueba con el nivel exacto **y**
con el de abajo, que debe quedar afuera.

Un moderador trabaja la cola y ve el historial de una persona, pero no lee la
bitácora entera —que incluye lo que hicieron los otros moderadores— ni toca los
límites del sistema.

#### Un tercer test que dependía del historial de la base

La corrida completa después de todo esto sacó una falla que no estaba antes:
`canales.mjs` dejó de encontrar su propio canal en la búsqueda. La búsqueda
devuelve los **30 con más suscriptores**, y cada corrida de esa suite deja un
`auditoria_<sufijo>` más en la base de desarrollo. Llegó a 32 y el canal recién
creado —con un suscriptor— se cayó del tope, **entre dos corridas del mismo
día**.

Es el tercero del mismo patrón en dos sesiones, después de la cola de
moderación y de `bus-inyeccion`: **pruebas que pasan por la cantidad de datos
que hay, no por lo que el servidor hace**. Ahora busca por el sufijo único de
su corrida, que recorre el mismo `LIKE` sin depender del corpus.

El tope sin cursor se deja como está, y aquí la diferencia con la cola de
moderación importa: un directorio que devuelve los treinta canales más seguidos
y espera que afines la búsqueda es una decisión razonable; una cola de
denuncias donde no se puede llegar a la número 31 deja trabajo sin hacer.

#### Tres expectativas mías que estaban mal

Vale anotarlas porque las tres eran la prueba equivocándose, no el servidor:

- **`/leidos` contesta 200 con lista vacía a un desconocido, y está bien.** La
  consulta es «de MIS mensajes aquí, cuáles están leídos», acotada por autoría
  en el propio SQL. Un id inventado devuelve lo mismo, así que tampoco sirve de
  oráculo. Lo que hay que comprobar no es el estado sino que venga **vacía**.
- **El canal de la siembra no estaba aprobado**, así que el 404 por alias era
  correcto. Un barrido sobre un canal pendiente prueba el camino equivocado.
- **`dispositivoId` e `identidadPub` sí viajan en un perfil, a propósito**: en
  este diseño la dirección de Signal es el aparato, y sin esos dos campos no se
  puede cifrar el primer mensaje a alguien. Lo que no puede salir es el
  teléfono, el nivel de staff y el hash de hardware — y no salen.

### N.7 · El §16, recorrido entero en vez de por módulos

El brief cierra el §16 con una frase que no es decorativa: **«Nunca confiar
únicamente en permisos enviados por el cliente»**, y con siete pasos —autenticar,
identificar el recurso, obtener el rol, resolver permisos, comprobar
restricciones, ejecutar, auditar—. Cada suite de módulo comprueba eso en el
camino que le toca y lo hace bien. Lo que ninguna hacía era preguntarse **si
quedó alguna ruta afuera**.

Un extractor sobre `Main.kt` —que resuelve las constantes `RUTA_*`, porque casi
ninguna ruta está escrita como literal— da el mapa: **127 rutas, 78 de ellas
mutantes, 37 con un `{id}` en el camino**. Esas 37 son la superficie real: si
una sola resuelve el id sin mirar quién lo pide, hay acceso a un objeto ajeno.

Seis rutas no autentican, y las seis tienen que ser así: salud, el HTML de la
consola, registro, ingreso, vinculación de dispositivo —que se autoriza con un
código— y recuperación de cuenta. Ninguna sorpresa ahí.

#### El barrido, y por qué la mitad importante es el control

`pruebas/ajeno.mjs` siembra un mundo entero —grupo, chat directo, mensaje,
canal, llamada, invitación, rol propio, adjunto, consola, denuncia— y lo ataca
con un tercero que no tiene ninguna relación con nada de eso.

Comprobar «el tercero recibe algo que no es 2xx» **no prueba nada por sí solo**:
un 400 porque el cuerpo estaba mal da exactamente el mismo verde que un 403 por
autorización. Y no es teórico: **la primera versión pasó en verde con nueve
filas falsas**, porque varios objetos no se habían sembrado —el alias del canal
llevaba un guion que el servidor no admite, el adjunto devuelve `adjuntoId` y no
`id`— y el camino se armaba con `undefined`.

Por eso cada fila se corre dos veces: el ataque, y un **control** con el dueño
legítimo haciendo la misma petición con el mismo cuerpo sobre un objeto
equivalente. Si el control no da 2xx, la fila cuenta como falla. Y cada control
va contra un mundo recién sembrado: con uno compartido, `salir` dejaba a la
dueña fuera del grupo y las tres filas de mensajes siguientes fallaban por el
orden de la tabla.

Un **409 no cuenta como negarse**. La distinción encontró el único hallazgo del
barrido.

#### Lo que salió: contestar una llamada ajena

`POST /v1/llamadas/{id}/contestar` nunca comprobaba la pertenencia a la
conversación. Un extraño con el id de una llamada recibía **409 «Esta llamada no
te está sonando»** —que confirma que la llamada existe y sigue viva— frente al
404 de un id inventado. No da acceso a nada: la llamada no se contesta, el
`UPDATE` está acotado por `usuario_id`. Pero responde preguntas sobre un objeto
ajeno, y el paso 3 del §16 —«obtener el rol del usuario en el recurso»— no
llegaba a ejecutarse nunca.

Ahora la pertenencia se comprueba **primero** y quien no es del grupo recibe
404. A quien sí lo es se le sigue contestando 409 con el motivo: ahí el detalle
es útil y no revela nada que esa persona no pudiera ver igual.

Validado al revés: con el arreglo revertido la suite **falla**, y el control
pasa en los dos casos —el arreglo no toca el uso legítimo—.

Las otras 36 rutas aguantaron. Vale la pena decir *cómo*, porque no es con un
`Permisos.X` en todas: la mayoría acota por propiedad dentro del `WHERE`
(`AND usuario_id = ?`) o exige membresía antes de tocar nada. Un auditor que
busque solo llamadas a la capa de permisos da 35 falsos positivos.

#### Dos defectos de prueba que el barrido destapó de paso

**La cola de moderación no tenía cursor.** Ordena por fecha ascendente —lo más
viejo primero, que es el orden correcto para triar— con tope de 200. Con 125
denuncias pendientes acumuladas de muchas corridas, `moderacion.mjs` dejó de
encontrar la suya: fallaba por la **profundidad de la cola** y no por el
servidor. Pedir `limite=500` no lo arregla, se recorta a 200 en silencio. Y
detrás del test había un hueco de producto: **quien modera no podía alcanzar la
denuncia 201** más que resolviendo las 200 de adelante.

El cursor es de teclado, no `OFFSET`: sobre una cola viva, `OFFSET` se salta
filas en cuanto alguien resuelve una de la página anterior, y en una cola de
moderación eso es una denuncia que nadie mira jamás.

**Y el cursor salió mal a la primera.** La versión inicial mandaba
`(creadaEn, id)` desde el cliente y **repetía filas**: `creada_en` es
`timestamptz` —microsegundos— y `creadaEn` viaja como milisegundos truncados,
así que el corte quedaba unos microsegundos *antes* de la fila que se quería
dejar atrás. Con páginas de tres salieron 163 filas donde había 139. Ahora el
cursor es el **id** de la última fila y la fecha la resuelve el servidor con la
precisión real de la columna.

Eso también se vio solo por forzarlo: con páginas de 200 sobre una cola de 125
todo entra en una página, el bucle corta en la primera vuelta y las
comprobaciones de «no repite» y «sale ordenada» dan verde **sin haber paginado
nunca**. La pasada que prueba el cursor va de tres en tres a propósito.

#### Una suite que fallaba donde debía omitirse

Medir la configuración mínima —una instancia, sin Redis— destapó que
`bus-inyeccion.mjs` daba **rojo** cuando el contenedor de Redis estaba arriba
pero el servidor arrancado sin `WTFUCK_REDIS_URL`: `PUBLISH` devuelve `:0`, cero
suscriptores, y el ataque no llega a ninguna parte.

Un rojo ahí no significa que el arreglo se haya roto sino que falta levantar la
segunda instancia, y **un rojo que no distingue «falta entorno» de «hay un
agujero» es un rojo al que se deja de hacer caso**. Ahora sondea y se omite,
que es lo que el runner sabe contar aparte.

### N.6 · Revisión de lo nuevo, y un agujero de verdad

Los módulos M y N son unas 3.000 líneas escritas en dos días, así que en vez de
seguir agregando superficie se les pasó una revisión adversaria. Salió una cosa
seria.

#### El bus entre instancias era un camino de entrada

Un `Bajada.Evento` viaja **en claro** y el cliente lo **obedece**:
`mensaje_retirado` borra un mensaje del teléfono, `expulsado` lo marca fuera de
un grupo, `dispositivo_revocado` y `sancion` cambian el estado de la cuenta. Eso
es correcto —el servidor es quien tiene autoridad para decir esas cosas— y por
eso mismo el bus que reparte esos avisos es un límite de confianza.

**Y no autenticaba nada.** La primera versión publicaba `instancia|json`. Quien
pudiera publicar en `wtfuck:disp:<uuid>` le metía a un cliente conectado un
evento que nunca ocurrió. Dos cosas lo hacían alcanzable:

1. El id de un dispositivo **no es secreto**: se le da a cualquier participante
   de la conversación para poder cifrarle.
2. El `docker-compose.yml` publicaba el puerto de Redis en **todas** las
   interfaces (`"6380:6379"`).

**La corrección.** Cada mensaje lleva ahora un **HMAC-SHA256** con un secreto
compartido (`WTFUCK_BUS_SECRETO`), verificado con comparación en tiempo
constante. El formato es `instancia|firma|cuerpo` y la firma va **antes** del
cuerpo a propósito: así se rechaza sin haber parseado nada de lo que mandó un
desconocido — deserializar primero y verificar después es el orden que convierte
un parser en superficie de ataque. El origen entra en la firma para que una
instancia no pueda republicar el mensaje de otra cambiando el remitente.

Y se **falla cerrado**: con `WTFUCK_REDIS_URL` y sin secreto, el bus no arranca
y lo dice. Un bus a medio autenticar es peor que ninguno, porque se despliega
creyendo que está protegido.

Lo que esto arregla y lo que no, dicho: **arregla la inyección**, que es la
mitad grave. **No oculta los metadatos** —quien lea ese Redis ve que el
dispositivo X recibió algo a tal hora, aunque el contenido siga siendo un sobre
que no puede abrir— ni **impide la denegación**, porque quien escriba ahí puede
borrar claves de presencia. Por eso el Redis va en red privada y con contraseña;
el HMAC es defensa en profundidad, no el único control. El puerto ahora se ata a
`127.0.0.1`.

#### La prueba que casi no prueba nada

`pruebas/bus-inyeccion.mjs` hace el ataque de verdad: habla RESP a mano —para no
meter una dependencia de cliente de Redis que sólo usaría esta suite— y publica
un `expulsado` falso en el canal de la víctima.

La primera versión **pasaba en verde contra el código vulnerable**, que es el
peor resultado posible para una prueba de seguridad. Dos errores, los dos míos:

1. Daba por bueno cualquier respuesta `:N` de `PUBLISH`, y `:0` significa **cero
   suscriptores**: el ataque no llegaba a ninguna instancia.
2. El payload falso no coincidía con el esquema real de `Bajada.Evento`
   (`eventoId`/`tipo`, no `id`/`clase`), así que la deserialización fallaba y el
   mensaje se descartaba por accidente y no por el control.

Corregida, la suite **falla contra el build de ayer** —se ve el `expulsado`
falso llegando al cliente— y pasa contra el arreglado. Una prueba de seguridad
que no falla contra el código vulnerable no prueba nada, y la única forma de
saberlo fue revertir el arreglo dos minutos y volver a correrla.

#### Dos cosas más de la misma revisión

- **El push creaba un hilo por aviso.** `Thread {}` por dispositivo escala con
  la peor variable posible: un mensaje a un grupo de cincuenta personas
  desconectadas son cincuenta hilos a la vez, cada uno esperando hasta diez
  segundos a un servidor ajeno. Ahora es un pool de 4 con cola de 256 que
  **descarta** al desbordar — perder un aviso cuesta que un teléfono se entere
  al reconectar; no perderlo nunca cuesta memoria sin techo.
- **Una encuesta ajena se dibujaba entera.** El creador se limita a doce
  opciones, pero eso lo comprueba el cliente que la crea; el que la recibe no
  puede suponer que del otro lado había esta app, y el servidor no puede mirar
  dentro del sobre para ayudar. Una encuesta fabricada con dos mil opciones
  —entran de sobra en los 64 KB de un sobre— dejaba la pantalla clavada. Ahora
  hay tope al pintar, y se **dice** que se recortó en vez de esconderlo.

#### Dos hallazgos que se declaran en vez de cambiarse

- **El token de push: gana el último en registrar.** Quien conozca el token de
  otro aparato puede dejarlo sin avisos. Es una denegación, no una lectura —el
  aviso no lleva contenido—, y la alternativa es peor: que gane el primero
  significa que un token que quedó en un aparato perdido bloquea para siempre al
  mismo token en el nuevo, y Android reutiliza tokens al reinstalar. El control
  real está aguas arriba: el token no aparece en ninguna respuesta de la API.
- **El servidor elige el proyecto de Firebase del cliente.** Uno comprometido
  podría quedarse con el token y despertar el aparato, nada más. No agrega poder
  que no tuviera: ya decide qué sobres entrega y ya conoce el token. Lo que no
  puede hacer —leer un mensaje— sigue sin poder hacerlo.

### Dos defectos que aparecieron de paso

1. **El reenvío estuvo roto desde el módulo C.** El cliente manda el **username**
   del autor original —es lo que guarda de cada mensaje— y el servidor hacía
   `UUID.fromString` con él, así que **todo reenvío se rechazaba con 400**. No lo
   vio ninguna prueba porque ninguna probaba `reenviadoDe`, y la única señal era
   una burbuja coral que decía "Identificador de reenvío inválido". Lo encontré
   mirando una captura del tema claro. Ahora el servidor resuelve el username
   contra `usuario`, igual que ya hacía con las menciones, y si no resuelve
   guarda NULL: perder el "reenviado de @fulano" es infinitamente mejor que
   rechazar el mensaje.

2. **El buscador del chat cerraba la app.** `ESCAPE '\\'` dentro de un string
   *raw* de Kotlin son dos caracteres, y SQLite exige uno: *"ESCAPE expression
   must be a single character"*. Reventaba en el hilo principal. Se corrigió el
   escape **y** se envolvió la consulta: buscar es una comodidad, y una consulta
   que falla tiene que devolver "sin resultados", no cerrar la app.


## Qué falta del brief y por qué

| Punto del brief | Situación |
|---|---|
| Push real | El camino está entero y probado contra un FCM de mentira. Falta **una credencial de Firebase**: proyecto, cuenta de servicio y clave. Se pone en el servidor, sin recompilar |
| Cliente web de **mensajería** | Exige libsignal en el navegador (WASM, claves en IndexedDB) y la pregunta seria de si un navegador es sitio para claves de largo plazo. En web está la consola de **administración**, que es otra cosa |
| SFU para llamadas de más de 4 | La malla tiene techo declarado: con N participantes son N-1 conexiones por aparato |
| Bots e integraciones | No está planificado. Requiere su propio modelo de seguridad |

Lo que **salió** de esta tabla en el módulo N: el tema claro (se rederivó la
escala y está medido), la interfaz de tablet y escritorio (dos paneles), y el
`Hub` fuera del proceso (segunda instancia verificada). Y en el módulo O, las
**historias**: texto, foto y vídeo, con respuesta, quién la vio y caducidad a
las 24 horas.

---

## Regla de trabajo

1. Diseño y esquema primero.
2. Servidor con autorización y pruebas.
3. Recién después, la interfaz.
4. No se abre un módulo con el anterior en rojo.


---

## Módulo Q · Los cuatro ajustes finos y el panel completo ✅

*Lo que faltaba del §3 y del §10 del brief, revisado contra el código.*

- [x] Q.1 Dashboard de plataforma (§10)
- [x] Q.2 `biografia` — la bio no es el estado
- [x] Q.3 `videollamadas` — el vídeo no es el audio
- [x] Q.4 `grabando` — grabar no es escribir
- [x] Q.5 `solicitudes` — la alternativa al portazo
- [x] Q.6 Correcciones de la auditoría del módulo P

### Q.1 · El dashboard tenía solo media cara

El panel medía **moderación** —denuncias, suspendidos, advertencias— y nada de
**plataforma**. Ahora trae usuarios registrados y activos en 7 días, mensajes,
grupos, canales y almacenamiento.

Dos decisiones que merecen decirse:

**`mensaje_meta` se aproxima por encima del millón de filas**, leyendo
`pg_class.reltuples`, y la tarjeta lo dice: "Mensajes (aprox.)". Un `count(*)`
sobre esa tabla no tiene atajo por la visibilidad MVCC y bloquearía el panel. Lo
que **no** se aproxima es `sum(bytes)` de los adjuntos: `reltuples` estima filas,
no lo que suman, así que aproximarlo habría sido inventar el número.

**"Uso de servidores" no está, y está dicho en la pantalla.** El brief lo pide,
pero este servidor no tiene telemetría de CPU ni memoria. Un hueco explicado vale
más que un número inventado en un panel de administración.

La tarjeta de mensajes lleva el pie **"metadatos, sin contenido"**: el servidor no
guarda mensajes, y un panel que dice "5.173 mensajes" sin esa aclaración hace
pensar lo contrario.

### Q.2–Q.5 · Cada ajuste separa dos cosas que estaban pegadas

| Ajuste | Qué separa | Dónde se aplica |
|---|---|---|
| `biografia` | la bio del estado | `Repo.leerPublico` |
| `videollamadas` | el vídeo del audio | `Llamadas.exigirPuedeLlamar` |
| `grabando` | grabar de escribir | `Repo.destinosDeEscritura` |
| `solicitudes` | el portazo de la puerta entornada | `Repo.crearDirecta` |

**La biografía no viajaba en el perfil público**, así que el ajuste no habría
tenido dónde aplicarse: habría sido un interruptor sin nada detrás. Ahora viaja,
filtrada, y vacía cuando no se puede ver —no null: distinguir "no tiene" de "no
te deja verla" sería un dato deducible sobre sus ajustes—.

**El vídeo se comprueba ADEMÁS del audio, nunca en su lugar.** Si el audio está
cerrado, el vídeo también: no hay puerta trasera. Y el mensaje de rechazo dice
**cuál de los dos** lo impidió, porque un "no puedes llamar" genérico hace que
alguien reintente en vídeo para nada.

#### Un defecto del §16 que apareció de paso

`priv_escribiendo` **lo miraba solo el cliente**: si no quería avisar, no mandaba
el mensaje. Eso deja la privacidad de una persona en manos del programa que tenga
instalado, y el §16 lo prohíbe con todas las letras. Un cliente modificado —o
simplemente viejo— seguía anunciando.

Ahora lo comprueba `destinosDeEscritura`: si está apagado devuelve la lista vacía
y el servidor no reenvía nada. El cliente sigue sin mandarlo —correcto por ancho
de banda— pero ya no es lo único que lo impide.

#### Las solicitudes, y el defecto que las pruebas cazaron

Una solicitud **es** una conversación en otro estado, no una tabla aparte: con
tabla habría que copiar la conversación y sus mensajes al aceptar, y mover
mensajes entre tablas se rompe una vez y se nota un mes después. Es una columna
`conversacion.solicitud_de` con índice **parcial**, porque casi todas las filas
son NULL.

Tres reglas: **solo quien la recibió decide** (quien la mandó no se la acepta a
sí mismo —sería saltarse el ajuste con una petición más—), **rechazar borra la
conversación** —una lista de rechazados no le sirve a nadie y el otro lado podría
sondearla— y **rechazar no bloquea**, que son dos decisiones distintas.

> **Nace APAGADO, y eso lo decidieron las pruebas.** Lo puse encendido por
> defecto, y siete afirmaciones que llevaban meses en verde —"un desconocido no
> puede abrir conversación con ella"— se pusieron en rojo. No eran pruebas
> viejas: era el contrato anterior avisando de que lo estaba rompiendo. Quien
> puso `escribe: conocidos` lo puso para que no le escriban desconocidos, y un
> ajuste de privacidad **no se relaja en una actualización**.

### Q.6 · Lo que encontró la auditoría del módulo P

Cuatro hallazgos reales, los cuatro corregidos y con prueba:

1. **`call.receive()` antes de `autenticar()`** en la ruta de staff. Un anónimo
   distinguía un 400 "cuerpo mal formado" de un 404 y con eso confirmaba que la
   ruta existe. **El control de acceso va antes que la validación de entrada.**
2. **`?valor=` fallaba hacia "verificada".** Pedír `?valor=0` para **retirar** un
   distintivo lo volvía a poner, y la bitácora registraba una verificación que
   nadie quiso hacer. En la operación que afirma una identidad, la ausencia de
   instrucción no puede significar "sí".
3. **Staff podía verificarse y otorgarse tipos a sí mismo.** Es lo único que
   separa la verificación de una declaración.
4. **La ficha de empresa no aplicaba ninguna defensa de suplantación.** Con un
   override de dirección, `"\u202Eacme@ocnaB lanoicaN"` se dibuja como "Banco
   Nacional @acme": la suplantación entera sin tocar una letra. `etiquetaLimpia`
   vive ahora en el contrato y limpia **en el servidor**, porque un dato que la
   plataforma presenta a terceros se limpia donde se escribe, no en cada cliente.

Y al arreglar el segundo reintroduje el primero un escalón más arriba: validar
`valor` en la ruta hacía que el 400 llegara **antes** del 404 de staff. Lo vio la
prueba que había escrito para el caso anterior.

### Dos pruebas que dependían del corpus

`canales` buscaba su canal en la primera página de un directorio **ordenado por
suscriptores**: con 786 canales acumulados, un canal nuevo con cero suscriptores
cae fuera. `h3` buscaba en el panel por la palabra "cerrar", que comparten todos
los grupos de las corridas anteriores.

Ninguna de las dos decía nada del producto: decían cuántas veces se había corrido
la suite. Es el mismo defecto que ya se corrigió una vez en la búsqueda de
canales, reaparecido en otro sitio.

---

## Módulo R · Lo que la auditoría dejó pendiente ✅

*Dos deudas que no eran del módulo P sino de todo el proyecto.*

- [x] R.1 Una suspensión congela también el perfil público
- [x] R.2 Auditar no puede tumbar la acción ni perder el rastro

### R.1 · Suspendido por suplantar, y seguía suplantando

La política del proyecto es deliberada y no se tocó: **una suspensión no corta
la sesión**. Corta lo que produce contenido y deja entrar a ver por qué, porque
una sanción que no se explica no corrige nada.

El problema era el **alcance**. La suspensión se comprobaba en `Autz.puede`, que
es autorización *de conversación*. Todo lo que no pasa por ahí se quedaba fuera:

| Qué | Pasaba por `Autz.puede` |
|---|---|
| Escribir en un grupo | sí |
| Nombre, estado, biografía | **no** |
| Foto y portada | **no** |
| Publicar una historia | **no** |
| Tipo de cuenta y ficha de empresa | **no** |

Es decir, **justo lo que otros ven**. Medido antes de arreglarlo: una cuenta
suspendida con el motivo *"Suplantar a una institución"* cambió su nombre a
"Banco Nacional" y su biografía a "Entidad financiera regulada", y publicó una
historia. Nueve afirmaciones en rojo contra el código vulnerable.

La corrección es `Autz.exigirNoSuspendido`, aplicado en las seis escrituras que
no tenían conversación de la que colgar. **403 y no 404**: aquí no hay nada que
esconder —la persona sabe que está sancionada, se lo dijimos— y un 404 la
dejaría pensando que la ruta se rompió.

La suite cubre también los dos límites que hacen que la sanción sea una sanción
y no una condena: **una suspensión vencida no bloquea nada** —sin la condición
de `suspendido_hasta`, toda suspensión temporal sería permanente— y **no se
contagia** a otras cuentas.

### R.2 · Un `detalle` mal formado se llevaba la acción por delante

El `detalle` de una auditoría entra como `?::jsonb`. Si no es JSON válido,
Postgres falla la sentencia, y **una sentencia fallida aborta la transacción
entera**: el `commit()` posterior se vuelve un ROLLBACK silencioso. La acción
devuelve 200 y no pasó nada.

El proyecto ya había perdido una tarde con esto en `Seguridad.anotar`. Aquí el
riesgo estaba **latente**: todos los `detalle` interpolaban valores de listas
cerradas, así que funcionaba *por accidente de orden*. Dos sitios lo delataban
—el motivo de rechazo de un canal y la etiqueta de un dispositivo llevaban un
`.replace("\"", "")` a mano—: alguien ya había visto el problema y lo había
parcheado quitando comillas, que para las comillas sirve, para una barra
invertida final no, y encima **cambia el dato que se guarda**.

Dos arreglos, uno de fondo y otro de causa:

**SAVEPOINT alrededor del INSERT, y reintento sin detalle.** Un detalle
malformado ya no se lleva la acción. Y como un audit log sin fila es peor que
uno sin adorno, se reintenta sin el detalle: la fila queda siempre y lo único
que se pierde es el JSON que venía mal. Queda en el log del servidor, porque un
detalle que se pierde en producción es un defecto que alguien tiene que
arreglar.

Validado por reversión: sin el savepoint, dos de las siete pruebas caen.

**`Autz.detalleDe(vararg Pair)`**, que construye el JSON en vez de
interpolarlo. Respeta los tipos —un `24` sin comillas, para que
`detalle->'horas' > 12` funcione en una consulta— y omite los nulos en vez de
escribir `null`. Los sitios con texto libre ya no interpolan.

---

## Módulo P.2 · La ficha de empresa la ve la gente ✅

*Salió de llenar los siete campos a mano en el emulador y buscar después la
tarjeta desde la otra cuenta.*

- [x] P.2.1 `UsuarioPublico` lleva la ficha
- [x] P.2.2 El chat la dibuja al abrir el contacto
- [x] P.2.3 Una empresa de una persona no se anuncia como "1 personas"

### P.2.1 · Un modo empresa que solo ve su dueño es un formulario

El módulo P guardaba los siete campos y los dibujaba en el perfil **propio**.
`UsuarioPublico` no los llevaba y `leerPublico` no los leía, así que la ficha
era, desde fuera, invisible: la única pantalla que la mostraba era la de quien
la había escrito.

Se arregló añadiendo la ficha al perfil público, con tres decisiones:

| Decisión | Por qué |
|---|---|
| La ficha **no** pasa por los ajustes de privacidad | Declararse empresa es una declaración *hacia afuera*. Un interruptor para esconderla sería pedir un modo público y apagarlo. Quien no la quiera pública vuelve a cuenta personal, y entonces se borra. |
| `null` y no una ficha vacía | La diferencia entre "no tiene" y "tiene una vacía" no es un dato sobre sus ajustes: es qué clase de cuenta es, y eso es público. Una tarjeta en blanco no se dibuja igual que ninguna tarjeta. |
| `tipo_cuenta = 'empresa'` va **dentro del join** | Una ficha de quien ya no es empresa no se muestra aunque la fila siga ahí. Hoy `elegirTipo` la borra; esto no depende de que siga haciéndolo. |

**Y una trampa que costó dos suites.** `UsuarioPublico` se arma en **tres**
consultas: dos de perfil y una de los participantes de una conversación, que
arranca de `participante` y tiene otro FROM. Al añadir la ficha se le puso el
join a las dos primeras y no a la tercera. Nada de eso falla al compilar: falla
al **leer la columna 17**, y lo que se vio fue un 500 al abrir cualquier
conversación y `h3` y `l1` cayéndose con `.some is not a function` sobre el
cuerpo del error.

El join vive ahora en una constante, `JOIN_EMPRESA`, que las tres interpolan. Y
la suite pide la lista de conversaciones además del perfil, que es el camino por
el que el dato llega cuando alguien simplemente abre la app: sin esa sección, el
defecto volvía a pasar desapercibido. Validado por reversión — quitando el join
de la consulta de participantes, 3 de las 35 se ponen rojas con el 500 en la
mano.

### P.2.2 · Donde se busca quién es alguien

La tarjeta se dibuja al abrir **Ver contacto** desde el chat, que es donde se va
a buscar quién es la otra persona. El perfil público se pide ahí y no al abrir
la conversación: es un dato que casi nadie va a mirar, y pedirlo siempre sería
una petición de red por cada chat que se abre para dibujar algo que está detrás
de un menú. Si falla, la tarjeta no aparece y el contacto se abre igual.

El diálogo lleva `verticalScroll`: la descripción admite 600 caracteres y en un
teléfono corto el botón de cerrar se iba de la pantalla.

### P.2.3 · "1 personas"

Los rangos se dibujaban con un `"$tamano personas"` en tres sitios, y el primero
de la lista es `"1"`: una empresa de una sola persona se anunciaba en su propio
perfil público como **"1 personas"**. Ahora hay `TamanoEmpresa.legible`, en el
contrato y no en la app por lo mismo que `CategoriaEmpresa.legible`: si lo
arregla quien dibuja, el siguiente que dibuje lo vuelve a escribir mal.

---

## Módulo P.3 · Límites de ritmo en el autoservicio ✅

*La deuda que la auditoría del módulo P dejó marcada como "resolver antes de
abrir la beta".*

- [x] P.3.1 Ráfaga y cupo diario en `elegirTipo` y `guardarFicha`
- [x] P.3.2 Los dos límites se ven y se ajustan desde el panel
- [x] P.3.3 Dos arreglos de interfaz que salieron de mirar las capturas

### P.3.1 · El hueco, medido antes de taparlo

`PUT /v1/cuenta/tipo` y `PUT /v1/cuenta/empresa` no tenían ningún límite, y la
maquinaria ya existía y se usaba en cinco archivos. Aquí simplemente no se había
enchufado. Medido: **45 guardados seguidos, 45 doscientos.** 12 de 16
afirmaciones en rojo contra el código sin límite.

**Por qué importa en esta ruta.** La ficha es texto público que otros leen, y
cada guardado deja una fila de auditoría y retira la verificación. El riesgo que
de verdad manda no es la carga: es que **rotar el nombre comercial es una forma
de evadir la moderación**. Se denuncia una ficha que dice "Banco Nacional", y
cuando el moderador abre el caso la ficha dice otra cosa.

**Dos límites, y la división no es por importancia sino por duración** — la
misma regla que ya tenía escrita `Limites.kt`:

| | Dónde vive | Para qué | Número |
|---|---|---|---|
| Ráfaga de ficha | memoria | el script | 30 / 10 min |
| **Cupo de ficha** | **la base** | **la rotación sostenida** | **40 / día** |
| Cambio de tipo | memoria | el script | 20 / hora |

El cupo va en la base porque un límite diario que se olvida en cada despliegue
se evade esperando uno. La ráfaga puede vivir en memoria porque dura segundos.

**Tres decisiones de orden, cada una con su motivo:**

1. **La puerta de la beta va antes que el límite.** Al revés, quien está fuera
   distinguiría un 429 de un 404 y con esa diferencia confirmaría que la ruta
   existe. Es la misma regla que ya costó una corrección en `verificarEmpresa`.
2. **El límite va antes de validar el cuerpo**, que es lo contrario de lo que se
   hace con la autorización. Aquí no hay nada que filtrar —la cuenta ya está
   dentro de la beta— y validar primero significaría que mandar basura sale
   gratis.
3. **El cupo va dentro de la transacción y antes del INSERT.** Si fuera después,
   el guardado número 41 se escribiría y el 429 llegaría con el dato ya
   cambiado, que es exactamente lo que el cupo evita.

**Presupuestos separados, y eso es una invariante.** Si la ficha y el tipo
compartieran clave, agotar el de la ficha impediría volver a cuenta personal,
que es justamente como uno se quita la ficha de encima. Un límite que bloquea la
salida no es un límite, es una trampa. Está fijado en `LimitesDeCuentaTest`.

**El número del cambio de tipo empezó en 10 y subió a 20.** El dato que lo
decidió: `pruebas/cuentas.mjs` consume **9** cambios de tipo en una sola pasada
haciendo uso legítimo del módulo. Con 10, el margen era de uno, y la primera
prueba que alguien añadiera habría fallado con un 429 que no se parece en nada a
su causa. Entre 10 y 20 no hay diferencia de seguridad —las dos dicen "no sos un
script"— y sí de usabilidad.

**Y un test que detecta el desarme silencioso del cupo.** Si alguien sube la
ráfaga "un poco", puede dejar el cupo diario por encima de lo que la ráfaga
permite en un día, y entonces el cupo no corta nunca sin que nadie lo haya
tocado. `el cupo diario es mas estricto que la rafaga extrapolada` se cae en ese
caso. Validado por reversión: con `FICHAS_POR_DIA = 5000` la prueba se pone
roja.

### P.3.2 · Un límite que no está en el panel es un número que solo se cambia desplegando

Los dos entran en `Limitador.AJUSTABLES`, con los otros quince. La suite lo
aprovecha: para poder llegar al cupo diario hay que apartar la ráfaga, y lo hace
**subiéndola desde el panel** en vez de reiniciando el servidor, que es la vía
legítima y de paso ejercita el override. Lo restaura al terminar, porque un
ajuste de prueba que se queda puesto es un límite desactivado en el próximo
arranque.

### P.3.3 · Dos cosas que salieron de mirar las capturas

- **El contacto repetía el nombre.** En una conversación directa el diálogo
  mostraba "@fulano / Conversación directa / @fulano": la lista de miembros
  repetía a la única persona, que ya estaba de título. La lista ahora sale solo
  en grupos.
- Un `import` duplicado de `Verified` en `PerfilPantalla.kt`.

---

## Módulo S · La lista de chats ✅

*Paridad de uso con las apps de mensajería conocidas, con interfaz propia.*

- [x] S.1 El botón flotante deja de ser un "+"
- [x] S.2 Selección múltiple con acciones en lote
- [x] S.3 "Marcar como leídas" no miente

### S.1 · Un "+" no dice qué crea

El botón más visible de la pantalla hacía **una** de las tres cosas que se
pueden crear desde aquí. Un grupo estaba escondido en el menú de tres puntos;
un canal ni siquiera se podía empezar desde esta pantalla —había que ir a otra
pestaña y encontrar un icono—. Es decir: el elemento más visible resolvía la
acción que menos falta hace descubrir.

Ahora dice **Nuevo** y despliega Conversación, Grupo y Canal. Lleva texto a
propósito: un botón flotante con sólo un símbolo obliga a tocarlo para saber
qué hace, y tocar algo para averiguarlo sólo es gratis cuando no pasa nada;
aquí abre una pantalla.

**Crear un canal abre el diálogo, no lleva a la pestaña.** Llevar a alguien a
un sitio no es lo mismo que hacer lo que pidió. Cuesta un parámetro en
`DescubrirCanales` y se consume al atenderlo, para que volver a esa pestaña más
tarde no reabra el diálogo sin que nadie lo pida.

**"Historia" no está en el menú, y es deliberado.** Ya tiene su sitio: el
círculo "Publicar" de la fila de historias, justo encima y en su contexto.
Ponerla también aquí sería el mismo error que este cambio corrige en el menú de
tres puntos —dos caminos al mismo sitio sólo obligan a decidir cuál es el
bueno— y esta vez con el agravante de que el camino que ya existe es mejor. Por
la misma razón, crear salió del menú de tres puntos: ahora vive en un solo
lugar.

### S.2 · Selección múltiple

Mantener pulsado ya no abre la hoja de acciones de un chat: **selecciona**.
Tocar añade y quita, Atrás sale, y la barra de arriba se sustituye entera —el
buscador, los filtros y la fila de historias desaparecen, porque mientras se
opera sobre un lote todo lo demás estorba y tocarlo pierde la selección—.

Cinco acciones en lote: fijar, silenciar, marcar leídas, archivar y eliminar.

**Los botones hacen lo que le falta al lote.** No hay "fijar" y "desfijar" a la
vez: hay un botón que decide según lo que ya está. La regla para las mezclas es
*lo que falta* —con once fijados y uno no, fija—, y la alternativa razonable
("la mayoría manda") es peor: con siete de doce desfijaría y con seis fijaría,
así que dos toques seguidos harían cosas distintas por una cuenta que nadie
hizo.

La regla vive en `loteDe()`, que es una función y no cuatro `all { }` sueltos
dentro del Composable, porque es una regla y las reglas se prueban. Dos cosas
que sólo se ven en un test: un **silencio vencido** deja la columna distinta de
cero y en crudo contaría como silenciado, y `emptyList().all { }` devuelve
`true`, así que un lote vacío diría que todos están fijados.

**Nada se perdió.** La hoja de acciones de un chat tiene cosas sin versión en
lote —bloquear a alguien, denunciar, silenciar ocho horas—. Con **un solo** chat
marcado la barra ofrece "Más" y la abre. El gesto cambió de significado sin que
desapareciera nada.

**Sólo eliminar pregunta.** La diferencia no es el número de chats: es que
archivar, fijar, silenciar y marcar leído se deshacen con el mismo botón que los
hizo, y borrar no. Preguntar ante todo convierte la pregunta en un trámite que
se contesta sin leer, y entonces no protege de nada.

### S.3 · "Marcar como leídas" no manda acuse

Abrir un chat manda el acuse de lectura, y lo hace porque abrirlo **es** leerlo:
es el único momento en que se puede afirmar que alguien vio los mensajes.
"Marcar como leídas" desde la lista es otra cosa: lo que se pide es bajar el
globo rojo, y nadie leyó nada.

Por eso hay un `marcarLeidaLocal` aparte. Reutilizar `abrirChat` habría sido una
línea menos y **le diría a la otra persona que leíste su mensaje cuando no lo
hiciste**. En una app cuyo argumento es la privacidad eso no es un detalle: la
confirmación de lectura vale exactamente por ser cierta.

---

## Módulo T · Nombres de contacto y menciones ✅

- [x] T.1 En la lista sale el nombre de contacto, y sin "@"
- [x] T.2 Mencionar con `@` se puede hacer y se puede ver
- [x] T.3 La cola de moderación deja de depender del corpus (otra vez)

### T.1 · "@joaquin" era un identificador, no un nombre

La lista mostraba `@tatiana`, `@joaquin`: un listado de identificadores. La
regla nueva es la de cualquier agenda de teléfono:

| Caso | Qué se ve |
|---|---|
| La tengo agendada | **mi alias** — "Tati" |
| No la tengo agendada | el **username**, sin "@" |
| Grupo o canal | su nombre propio |

**Y hay una decisión de seguridad dentro.** Lo que *dejó* de usarse en una
conversación directa es `nombreMostrado`, el nombre que la otra persona **se
puso a sí misma**. No es un detalle de presentación: es un dato controlado por
quien podría querer hacerse pasar por alguien. Con él de título, una cuenta
`impostor99` que se llame "Tatiana" aparece en mi lista **idéntica** a la
Tatiana real. El alias sí puede ir solo, sin username al lado, porque lo
escribí yo.

Quien quiera ver el nombre que la persona se puso lo tiene en su perfil, que es
donde ese dato significa "así se llama esta cuenta" y no "esta es Fulano". Y el
username, que es lo que no se puede falsificar, sigue a un toque: **"Ver
contacto" lo muestra**, y eso pasó a ser obligatorio en cuanto el título dejó
de serlo.

La misma regla se aplicó en tres sitios más: el `"Tati:"` del último mensaje de
un grupo, la etiqueta de autor dentro de las burbujas, y la cabecera del chat.

**El alias se copia a la base local** (`contacto`, migración 14→15) porque la
lista se dibuja antes de que vuelva ninguna petición: con el nombre viniendo de
la red, cada arranque mostraría usernames durante un segundo y en un avión para
siempre. Se refresca en la sincronización normal y en las tres rutas que pueden
cambiar la libreta, y **poda**: sin podar, borrar un contacto lo quita del
servidor y su nombre sigue saliendo en este aparato para siempre.

### T.2 · La mención existía y era invisible

Mencionar **ya funcionaba**: quien escribe `@tatiana` manda ese username, el
servidor lo resuelve contra los participantes reales y queda registrado. Lo que
no había era nada de eso a la vista. Hacía falta acordarse del username exacto
—un `@taty` no menciona a nadie y nada lo dice— y al leer, `@tatiana` se
dibujaba como texto normal, así que una mención a uno mismo se perdía entre el
resto del mensaje. Una función entera por dentro e invisible por fuera, que
para quien la usa es lo mismo que no estar.

- **Al escribir**: teclear `@` en un grupo abre una tira de candidatos con *mi*
  nombre para cada uno. Se busca por nombre y por username —quien escribe
  piensa en la persona, no en el identificador— y se inserta siempre el
  username, que es lo único que el servidor resuelve.
- **Al leer**: la mención a **mí** va en negrita con fondo; las de otros, mismo
  color con un fondo apenas insinuado. Si se pintaran igual, en un grupo donde
  se menciona a diez personas la mía no se encontraría, que es justo lo que la
  mención viene a resolver.

**La regla de qué es una mención se mudó al contrato** (`PATRON_MENCION`,
`mencionesEn`). Había dos sitios que tenían que estar de acuerdo —el que manda
y el que pinta— y nada que los atara: con reglas distintas, la burbuja resalta
un nombre que nunca se registró, o sea una promesa visual sin nada detrás. De
paso, `mencionesEn` quita repetidos: mencionar a alguien tres veces en la misma
frase insertaba tres filas en `mencion` para la misma persona.

**El campo de texto pasó a `TextFieldValue`**, que es lo único que trae el
cursor. Con un `String` sólo se puede mirar el final, y entonces el selector no
aparece al editar por el medio. Salió gratis un arreglo de paso: el selector de
emoji inserta **en el cursor** y ya no al final, así que poner una cara en
mitad de una frase escrita deja de mandarla al final.

### T.3 · Dependencia del corpus, en la función que advertía de ella

`colaEntera` de `moderacion.mjs` paginaba con un tope fijo de 400 vueltas. Con
páginas de tres, el techo eran **1200 filas**; la cola de desarrollo llegó a
1217 y la pasada de tres en tres se cortó sola. El error decía *"1200 paginando
vs 1217 de una"*, que se lee como un defecto del cursor y no lo era.

Dos arreglos: el tope se calcula sobre el tamaño de página en vez de ser un
número fijo, y **revienta** al activarse en vez de devolver media cola en
silencio. Un cinturón que trunca callando convierte un problema en otro que no
se le parece.

---

## Módulo U · Bloqueo de la app y estados de error ✅

- [x] U.1 Bloquear la app con huella, rostro o el PIN del teléfono
- [x] U.2 Las pantallas dejan de confundir "no pude preguntar" con "no hay"

### U.1 · El hueco más visible que quedaba

Era el que yo mismo venía señalando: un mensajero cuyo argumento es la
privacidad y cualquiera que agarre el teléfono desbloqueado lee todo.

**Lo que protege, dicho con precisión.** Protege contra alguien con el teléfono
**desbloqueado en la mano**. No es cifrado: el historial ya está cifrado con
SQLCipher y su clave vive envuelta por el Keystore, y eso es lo que protege de
una extracción forense. Quien pueda leer la memoria del proceso no se detiene
aquí. Decirlo al revés haría que alguien confiara en esto para lo que no sirve,
así que **la pantalla de bloqueo lo dice en voz alta**, y el ajuste también.

**El reloj es `elapsedRealtime`, no la hora.** La hora del sistema la cambia
cualquiera desde ajustes: un bloqueo de quince minutos se creería caducado
adelantándola. `elapsedRealtime` cuenta desde el arranque y no se puede mover.
El precio es que al reiniciar el teléfono el contador vuelve a cero y lo
guardado queda **en el futuro**; eso se trata como "bloquear", que es lo
correcto — un reinicio es cuando menos motivos hay para suponer que sigue
siendo la misma persona.

**`BIOMETRIC_WEAK` y no `STRONG`.** En muchos teléfonos el reconocimiento
facial está clasificado como "weak", así que exigir "strong" deja sin entrar
—por su propia cara— a gente que la tiene configurada. "Strong" es obligatorio
cuando con el resultado se descifra una clave; aquí se abre una pantalla.
`DEVICE_CREDENTIAL` va siempre incluido: sin él, un dedo mojado deja a alguien
fuera de sus mensajes sin salida.

**`setRecentsScreenshotEnabled` y no `FLAG_SECURE`.** Sin nada, la miniatura de
la app en el conmutador muestra el último chat, y esa lista se ve sin
desbloquear. `FLAG_SECURE` también lo taparía, pero de paso prohíbe toda
captura dentro de la app, y eso es otra decisión que nadie pidió.

**Tres pruebas se cayeron al escribirlas** y destaparon una ambigüedad real: el
`0` de "último desbloqueo" puede leerse como "el milisegundo en que arrancó el
sistema" o como "no hay nada guardado". Se resolvió del lado seguro —el cero
bloquea— y quedó escrito en el contrato.

### U.2 · Tres pantallas que afirmaban cosas falsas

Lo señaló el usuario con dos capturas: un canal con la **cabecera vacía** y
"Cuenta y seguridad" con una línea gris. La causa inmediata era que los
emuladores habían perdido el túnel `adb reverse` que la app usa para hablar con
el servidor. La causa de fondo era otra, y estaba en el código:

```kotlin
runCatching { api.publicaciones(convId) }.getOrElse { emptyList() }
```

**"No pude preguntar" y "pregunté y no hay" acababan siendo el mismo valor.**
Y la pantalla creía el segundo:

| Pantalla | Decía sin red | Ahora |
|---|---|---|
| Canal | cabecera vacía + "este canal todavía no tiene publicaciones" | el nombre local + error con Reintentar |
| Cuenta y seguridad | "No se pudo leer el estado de la cuenta." | error con Reintentar |
| Directorio | "Todavía no hay canales públicos." | error con Reintentar |

Los tres usan `EstadoDeError`, compartido: icono, qué pasó, qué se puede hacer,
y un botón. Una línea de texto gris en una pantalla vacía no se lee como "no
hay conexión", se lee como que la app está rota — y además es mentira que no se
pueda hacer nada, porque se puede reintentar.

**La cabecera del canal nunca vuelve a quedar vacía**: cae al nombre que ya
está en la base local. Encontrado al probarlo: ese nombre se leía con
`remember(conversacionId)` y la lista llega por un `Flow`, así que en la primera
composición estaba vacía y la cabecera decía "Canal" teniendo el nombre a mano.

---

## Módulo V · Depurar la lista de chats ✅

*"Lo veo raro el diseño." Seis cosas concretas, cada una con su motivo.*

**Sobre la referencia.** El usuario mostró capturas de Telegram como ejemplo de
lo que quería. **No se copió su interfaz**, que es la regla del brief: lo que se
tomó son convenciones genéricas de una lista de conversaciones —densidad,
alineación, marcas de tiempo relativas— y se aplicaron con la identidad propia:
fondo oscuro, acento cian, tipografía y formas nuestras.

### V.1 · "Conectado" ocupaba una banda permanente

Una barra que está **siempre** deja de leerse, y entonces tampoco se lee el día
que dice algo importante: lo único que consigue es enseñar a ignorarla. Ahora
aparece sólo cuando hay algo que hacer —mensajes en cola, envíos fallidos, sin
conexión— y se va sola al arreglarse.

### V.2 · La franja de historias era casi toda hueco

90 dp de alto que, sin ninguna historia publicada, contenían un círculo con un
"+" y el resto vacío. Es lo primero que se ve al abrir la app, y se leía como un
fallo de maquetado.

No se escondió del todo, que era la tentación: las historias no tienen otra
puerta visible —el botón "Nuevo" no las ofrece, a propósito, para no duplicar
caminos—. Sin historias es **una línea**; con historias, la franja de círculos
de siempre.

### V.3 · Un canal se veía igual que un grupo

Los dos llevaban el icono de personas, y la etiqueta de texto la tenía sólo el
grupo: la ausencia de etiqueta no significaba "conversación directa", podía ser
un canal. Ahora el avatar distingue los tres —persona, personas, megáfono— y la
etiqueta de texto **se fue**: con el icono diciéndolo, la palabra al lado del
nombre era decirlo dos veces y le robaba ancho al título. Para quien no ve el
icono, la palabra sigue en `descripcionDeFila`.

### V.4 · Un mensaje de ayer decía "21/09/26"

Una fecha completa obliga a hacer una cuenta —¿qué día es hoy?— para responder
algo que se pregunta de un vistazo. Cuatro tramos: hora si es de hoy, **Ayer**,
el día de la semana si es de esta semana, y la fecha a partir de ahí.

La ventana se mide en **días de calendario, no en 24 horas**: un mensaje del
lunes a las 23:00 sigue siendo "lunes" el martes a las 08:00. Contar horas daría
"ayer" a algo de hace dos días según la hora.

**Y en español siempre.** El nombre del día salía del idioma del teléfono, así
que en un aparato en inglés la lista decía **"Sunday"** entre textos escritos en
español a mano. `dd/MM/yy` y `HH:mm` se quedan con el locale del sistema: ahí no
hay palabras, y el orden de día y mes o el reloj de 12/24 horas sí son
preferencias legítimas del aparato.

### V.5 · La hora flotaba a media altura

Estaba centrada verticalmente en la fila, así que no se alineaba ni con el
nombre ni con la vista previa: se leía como un número suelto. Ahora va arriba, a
la altura del nombre, que es a lo que pertenece.

### V.6 · El buscador se comía la primera pantalla

Un `OutlinedTextField` con su alto por defecto, borde y márgenes gastaba 96 dp
para un campo que casi nunca se usa; entre el título, el buscador y las
pestañas, el primer chat empezaba pasada la mitad del teléfono. Ahora es un
campo relleno sin borde, la mitad de alto —40 dp de área tocable, el mínimo
cómodo— y con una **X para borrar** que aparece sólo cuando hay algo escrito.

---

## Módulo W · Las hojas que se cortaban ✅

*"No deja publicar historias." Era literal: el botón estaba fuera de la
pantalla.*

### W.1 · Un defecto por defecto

`ModalBottomSheet` arranca **parcialmente expandido** —la mitad de la
pantalla— y **no desplaza su contenido**: lo que no entra simplemente no está.
En una hoja que es un formulario, lo que no entra es el botón del final, así
que la función entera queda inalcanzable sin que nada lo indique.

**Ninguna de las doce hojas de la app declaraba `skipPartiallyExpanded`.** El
defecto estaba en las seis que son formularios y sólo se notaba en las más
altas; la de historias era la más alta, así que fue la que se rompió del todo:
título, subtítulo, 200 dp de vista previa, los colores, el campo de texto y el
botón no caben en media pantalla.

| Hoja | Qué quedaba fuera |
|---|---|
| Publicar una historia | "Publicar" |
| Nueva encuesta | "Crear encuesta" |
| Nuevo evento | el botón de crear |
| Ubicación, Contacto, Descubrir | enviar / agregar |

Las seis se abren ahora enteras. La de historias lleva además `verticalScroll`
e `imePadding`, porque abrirse entera resuelve la pantalla en reposo pero al
escribir el pie **el teclado se come la mitad de abajo** y el botón volvía a
quedar fuera.

**Por qué no se había visto.** La suite de integración recorre el servidor, y
publicar una historia por la API funcionaba perfectamente: el defecto estaba
entre el dedo y la API. Es la clase de cosa que sólo aparece tocando la app en
una pantalla concreta, y por eso las capturas del usuario valen más que
cualquier prueba que yo escriba para esto.

### W.2 · Y lo que se veía distinto entre emuladores

No era el diseño ni el tamaño de pantalla —son idénticos, 1080×2424 a 420 dpi—:
**el segundo emulador tenía una compilación de 17 horas antes**. Las últimas
builds se habían instalado sólo en uno.

Queda escrito porque la conclusión equivocada era tentadora y cara: "la app se
ve distinta según el aparato" habría mandado a buscar un problema de densidades
que no existe.

### W.3 · Las historias con foto: un túnel que faltaba

"Con tatiana quiero subir una historia y no me deja." Con **texto** funcionaba;
con foto o vídeo, "No se pudo subir el archivo".

**La causa no estaba en el código.** `POST /v1/adjuntos` respondía 200: la
reserva funcionaba. Lo que fallaba era la subida, que no va al servidor sino
**directo al almacén**, con una URL firmada que apunta a `127.0.0.1:9000`. En el
emulador ahí no había nada: faltaba `adb reverse tcp:9000 tcp:9000`. El túnel
del servidor estaba; el del almacén no existía, y por eso el texto funcionaba y
sólo fallaban los archivos.

Y el puerto **tiene que ser 9000 a los dos lados**: la URL va firmada con SigV4
y la firma incluye el header `Host`, así que llegar por otro puerto da 403. Está
escrito en `Almacen.kt` desde el módulo D; lo que faltaba era que alguien
pusiera el túnel. Ahora lo hace
[`pruebas/conectar-emuladores.ps1`](../pruebas/conectar-emuladores.ps1) para
todos los emuladores de una vez, y los dos túneles están documentados en el
README y en el despliegue con su síntoma al lado.

**Lo que sí se arregló en el código.** La app ya hacía lo correcto con el fallo:
al no poder subir, **retira** la historia en vez de dejar una que nadie puede
abrir —comprobado en la base, las dos fallidas con `retirada_en`—. Lo que estaba
mal era que **la hoja se cerraba antes de saber el resultado**, así que un error
de red se llevaba por delante la foto elegida y el pie escrito: para reintentar
había que volver a abrir, volver a buscar la foto y volver a escribir.

Ahora se cierra sólo al terminar bien, el error aparece dentro diciendo que no
se perdió nada, y el botón muestra "Publicando…" mientras tanto —también para
que un segundo toque no publique dos historias, que con un archivo tarda lo
suficiente como para dudar y volver a tocar—.

---

## Módulo X · La pantalla de Privacidad mentía sin conexión ✅

*Salió de barrer el patrón `getOrElse { emptyList() }` que ya había mordido
tres veces, y resultó ser peor de lo que buscaba.*

### X.1 · Mostraba lo contrario de la verdad

El estado arrancaba en `Privacidad()` —los valores **por defecto**, que son los
más permisivos— y la carga fallaba en silencio. Medido: con `nadie` guardado en
cinco ajustes, la pantalla mostraba **"Todos" en los cinco**. No "no se pudo
cargar": lo contrario de la verdad, en la única pantalla cuyo trabajo es decir
quién te ve.

Es el mismo error que el módulo U, un escalón más arriba. Allí una lista vacía
decía "no hay nada" cuando quería decir "no pude preguntar"; aquí un objeto por
defecto decía "esto es tuyo" cuando quería decir lo mismo. **Un valor inicial
no es un dato: es la ausencia de uno.**

### X.2 · Y la segunda cara, cerrada por construcción

Guardar manda los quince campos y el servidor sobrescribe las quince columnas.
Partiendo de los defectos, tocar un solo ajuste escribiría los otros catorce con
valores permisivos: **un relajamiento de privacidad causado por un fallo de
red**, que es justo lo que este proyecto ya se había prohibido una vez.

**No conseguí reproducirlo** —en las dos pruebas la lectura se recuperó antes de
que guardara— y lo digo porque no quiero apuntarme un defecto que no demostré.
Pero la ventana existe y no depende de nadie que la cierre a mano.

El arreglo la cierra sin discutir la probabilidad: `_privacidad` pasa a ser
**nullable**, la pantalla no dibuja ningún control sin datos, y
`guardarPrivacidad` **falla** si nunca se leyó. No se puede escribir lo que no
se pudo leer.

### X.3 · "Escribiendo" se emitía sin saber si se podía

El mismo estado por defecto alimentaba la decisión de emitir el aviso de
"escribiendo", y ese campo viene en `true`. O sea: un fallo de red hacía que el
aparato emitiera una señal sobre su dueño que quizá tiene apagada.

Ahora `null` es "no sé", y no saber no autoriza nada. Cuesta que durante unos
segundos tras abrir sin conexión el otro lado no vea "escribiendo…"; nadie lo
nota, y si lo nota no se enteró de nada sobre nadie. La decisión vive en
`emiteEscribiendo`, una función, porque el ajuste es **recíproco** —quien no lo
emite tampoco lo ve— y con dos copias arreglar una dejaría la otra: un espejo
de una sola dirección.

### Lo que queda de la barrida

Quedan **doce** `getOrElse { emptyList() }` más en el repositorio. Los de las
pantallas de administración (bitácora, cola de moderación, límites, consolas)
son del mismo tipo pero de menor daño: un panel vacío se nota, y quien lo mira
sabe que hay red de por medio. Los de **dispositivos**, **sesiones** y **mis
eventos** son los que valdría la pena revisar después: decir "no tienes
dispositivos" o "no tienes sesiones abiertas" en una app de mensajería cifrada
es una afirmación de seguridad, no una lista vacía.

---

## Módulo Y · Stickers a partir de una foto ✅

*Pedido directo. Y al mirarlo, resultó que la función no existía a medias: no
existía.*

### Y.1 · El botón mentía por las dos mitades

La clase de adjunto `sticker` está desde el módulo D, con su tope de 2 MB y su
burbuja sin fondo. Lo que no había era **ninguna forma de crear uno**. El botón
se llama "Sticker o GIF" y sólo buscaba GIFs en un servicio externo que además
necesita una clave que no está configurada: las dos mitades del nombre llevaban
a la misma pantalla vacía.

### Y.2 · Las tres decisiones que valen

**512 × 512.** Es el tamaño con el que se dibujan los stickers en todas partes,
y hacerlo cuadrado en el origen evita que la burbuja tenga que decidir cómo
encajar una foto apaisada.

**WebP sin pérdida, no PNG.** Medido en el emulador: el sticker de prueba pesa
**48 KB**; el mismo recorte en PNG ronda los 400. Con el tope de 2 MB los dos
entran — pero cada sticker viaja **cifrado a cada aparato de cada
destinatario**, así que en un grupo de veinte la diferencia es 1 MB contra 8 MB
por sticker enviado.

Por debajo de API 30 no hay `WEBP_LOSSLESS` y se cae a **PNG**, no al `WEBP`
antiguo: ése es con pérdida, y con pérdida los bordes de un recorte
transparente salen con halo. Pesa más y se ve bien.

**Hay editor, no recorte automático.** El cuadrado centrado acierta con una
foto de producto y falla con cualquier foto de gente: la cara suele estar
arriba, y un recorte centrado de una vertical se lleva el torso. Se arrastra y
se pellizca sobre una ventana fija, que es el modelo que ya conoce cualquiera
que haya recortado una foto de perfil.

### Y.3 · Lo que NO hace, dicho por delante

**No quita el fondo.** Hacerlo bien sobre una foto cualquiera necesita un
modelo de segmentación —en Android, ML Kit sobre Play Services—: una
dependencia de Google, una descarga de modelo en el primer uso y un servicio
más del que depender. En una app cuyo argumento es que el servidor no puede
leer nada, eso es **una decisión de producto** y no la tomo solo.

La alternativa sin dependencias —quitar por color, tipo croma— funciona con un
fondo liso y deja bordes sucios con cualquier foto real. Un recorte cuadrado
bien hecho se usa; uno con halos se usa una vez.

Lo que sí hace: **si la foto de origen ya tiene transparencia, se conserva**. Se
decodifica en `ARGB_8888` explícito —si el decodificador elige `RGB_565` por
ahorrar, el alfa se pierde antes de que nadie lo pueda conservar— y se dibuja
sobre un lienzo transparente, no sobre blanco.

### Y.4 · Lo que enseñaron las pruebas

`cuadradoCentrado` devolvía un `android.graphics.Rect` y **cinco pruebas se
cayeron con "Rect.width not mocked"**: en una prueba de JVM los métodos del
framework no existen. Ninguna se cayó por el código.

Es una señal, no un estorbo: la aritmética del recorte es lo único que de
verdad se puede equivocar aquí —cuentas con enteros sobre tamaños que vienen de
fuera— y no se podía probar porque dependía del framework. Pasó a una clase
propia, `Recorte`, que se convierte a `Rect` sólo al dibujar.

---

## Módulo Y.2 · La colección de stickers, completa ✅

*"Faltan packs, favoritos y así." Y con movimiento.*

### Y.2.1 · De una carpeta a una colección

La primera versión listaba los archivos de `files/stickers/`. Alcanza para
diez y deja de servir con treinta: un archivo no tiene pack, ni favorito, ni
emoji, ni cuándo se usó. Todo eso son **metadatos**, y los metadatos van en la
base. Tablas `sticker` y `sticker_pack`, migración 15→16.

Los archivos que ya existían **no se pierden**: `adoptarStickersSueltos` les
crea su fila la primera vez que se abre la bandeja. Borrarlos habría sido tirar
el trabajo de quien ya recortó unos cuantos.

Cuatro ejes, y ninguno es decorativo:

| | Qué resuelve |
|---|---|
| **Recientes** | de ahí sale casi todo lo que se manda: nadie usa un sticker una sola vez |
| **Favoritos** | los que uno quiere a mano aunque hace una semana que no los usa — por eso no puede ser lo mismo que recientes |
| **Packs** | un sticker no tiene nombre; lo único que lo ubica es de dónde salió |
| **Emoji** | la única etiqueta que se le puede poner a algo sin nombre, y por tanto lo único con lo que se puede buscar |

**Borrar un pack suelta sus stickers, no los borra.** Deshacer una agrupación y
tirar el trabajo de recortar treinta imágenes son dos intenciones distintas, y
juntarlas convierte un "ordenar" en una pérdida.

### Y.2.2 · Guardar los que me mandan

Es lo que hace que la función sirva para dos personas. Sin esto cada quien sólo
puede usar los que recortó, y un sticker que llega es un callejón sin salida.
Se **copia** el archivo, no se referencia: el original vive con el mensaje y
vaciar el chat se lo llevaría.

### Y.2.3 · Con movimiento

**Se detecta por la cabecera, no por la extensión** — un `.webp` puede ser una
imagen quieta o una animación, así que el nombre no distingue nada. Para WebP
se busca el trozo `ANIM` dentro de un contenedor RIFF válido; para GIF, más de
un bloque de control de gráfico.

**Un animado se agrega tal cual, sin recortar.** Android no trae codificador de
WebP animado ni de GIF: se puede decodificar y no se puede volver a escribir.
Recortarlo obligaría a aplanarlo a un fotograma, o sea a quitarle justo lo que
se venía a conservar. Se comprueba el tope de 2 MB **al agregarlo** y no al
enviarlo, para no dejar en la colección algo que va a fallar después.

**Y la burbuja pasó a Coil.** Dibujaba con `BitmapFactory`, y un `Bitmap` es
*un* fotograma: un sticker animado se veía congelado en el primero. El
`ImageLoader` de la app ya traía `AnimatedImageDecoder`; sólo había que dejar
de esquivarlo.

### Y.2.4 · Dos defectos que salieron de probarlo a mano

**Un sticker sin pack y sin usar no aparecía en ninguna pestaña.** Ni en
Recientes —nunca se mandó—, ni en Favoritos —no se marcó—, ni en ningún pack.
Lo vi creando un pack y viendo desaparecer el único que había. De ahí sale la
pestaña **Todos**, que además es la que abre por defecto: con Recientes por
delante, quien acaba de crear su primer sticker lee "todavía no mandaste
ninguno" teniendo uno.

**La confirmación salía bajo un cartel de error.** "Guardado en tus stickers"
aparecía dentro del diálogo de `aviso`, titulado **"No se pudo completar"**.
Las confirmaciones tienen ahora su propio aviso, que se va solo y no pide que
lo cierren: lo que confirma ya pasó, así que no hay nada que perderse.

---

## Módulo Y.3 · Un panel con tres pestañas ✅

*"Separá los GIFs de los stickers" y "el diseño casi igual al de Telegram y
WhatsApp".*

### Sobre la instrucción de copiar el diseño

El brief decía **no copiar la interfaz** de esas apps. El usuario retiró esa
instrucción explícitamente y pidió lo contrario: que el diseño sea casi igual.
Es su producto y su decisión, ya la había planteado yo antes, y se siguió.

Se copió la **estructura, la disposición y el comportamiento**. No se copiaron
iconos, arte, colores ni código: los iconos son de Material, la paleta es la
del proyecto y el código es propio. Esa línea no la cruzo aunque se pida,
porque son obras de otros y no una convención de interfaz.

### Y.3.1 · Tres cosas del mismo gesto, en tres pestañas

Los GIFs y los stickers compartían hoja —el botón se llamaba "Sticker o GIF"—
y los emojis vivían en otra, abierta desde otro botón. Son tres formas de
*poner algo que no es texto*, y cambiar de una a otra obligaba a cerrar una
hoja y abrir otra.

Juntar GIFs con stickers era además lo peor de las dos: un GIF viene de un
buscador de fuera y un sticker es de la colección propia. Se buscan distinto y
no se mezclan en ninguna cabeza.

| Fila | Qué lleva |
|---|---|
| Packs | Todos · Recientes · Favoritos · un círculo por pack · nuevo pack · crear sticker |
| Pestañas | Emojis · GIFs · Stickers |

Las pestañas van **abajo**, que es donde las ponen las referencias y donde cae
el pulgar. El contenido tiene **alto fijo**: un panel que cambia de altura al
cambiar de pestaña mueve el chat entero por debajo.

### Y.3.2 · Los detalles que se decidieron mirando

- **Cada pack se representa con su primer sticker**, no con su nombre: en una
  fila de ocho no caben ocho nombres, y la imagen se reconoce antes que el
  texto.
- **La rejilla no lleva tarjeta debajo de cada sticker.** Un sticker tiene
  fondo transparente; ponerle un rectángulo gris detrás le inventa un borde
  que no tiene, y treinta rectángulos pesan más que los stickers.
- **Icono y texto en las pestañas**, no sólo icono: "GIF" y "sticker" no
  tienen un símbolo que todo el mundo reconozca, y dos caritas distintas al
  lado no distinguen nada.
- **Mantener pulsado un pack lo renombra.** Un lápiz permanente en cada
  círculo llenaría la fila de cosas que se tocan una vez al año.

### Y.3.3 · Y casi borro los emojis

Al quitar `SelectorEmoji`, que quedó sin uso, se fue con él la lista
`GRUPOS_EMOJI` que vivía justo encima. El compilador lo dijo en el acto —una
referencia sin resolver— pero vale anotarlo: **una lista de datos sin nadie
que la lea es lo primero que alguien borra creyendo que sobra.** Ahora vive
junto al único sitio que la usa.

---

## Módulo Z · Lo que le faltaba al panel, y seis lecturas que afirmaban lo que no comprobaron ✅

*"Agregá mejoras. ¿Qué mejoras faltan?"*

Dos bloques que no se parecen: **Z.1 a Z.4** cierran los huecos del panel de
emojis y stickers; **Z.5** termina un trabajo empezado en el módulo X, que es
que la app no diga como un hecho algo que no pudo comprobar.

### Z.1 · Emojis recientes

Era el hueco más grande y el más invisible: **la primera pestaña de cualquier
selector de emojis que exista**, y no por costumbre. Casi todo el mundo usa los
mismos diez. Sin ella, mandar 😂 por vigésima vez cuesta lo mismo que la
primera.

**Se ordena por veces y se desempata por cuándo**, y las dos hacen falta:

| Sólo recencia | Sólo frecuencia |
|---|---|
| El emoji que alguien manda cincuenta veces al día se cae de la lista en cuanto prueba veinticuatro distintos una tarde | Un emoji nuevo tarda semanas en subir |

**Va en la base cifrada, no en `SharedPreferences`.** El proyecto tiene una
regla declarada —los ajustes van en prefs sin cifrar porque el tema y el idioma
no dicen nada de nadie, ver la nota de `Bloqueo`— y la lista de los emojis que
alguien usa **sí dice**: hay banderas, hay símbolos de salud, hay cosas que una
persona puede no querer que se lean si le agarran el teléfono. Es del mismo
tipo de dato que el uso de stickers, que ya vivía ahí. Que sea más chico no lo
hace menos suyo.

Migración 16→17, y se crea **vacía**: no hay de dónde sacar el historial de uso
de quien ya venía usando la app, y poner un puñado de emojis "populares" de
fábrica sería inventarle gustos a alguien. La pestaña no existe hasta que hay
algo que mostrar.

### Z.2 · Buscar emojis

Con 250 emojis, encontrar 🥑 era recorrer cuatro grupos con el pulgar.

**El glifo y sus palabras viven en la misma línea**, y eso es la decisión de
diseño del módulo. Buscar exige una segunda estructura —glifo a palabras— y en
cuanto son dos estructuras se desincronizan: alguien agrega un emoji al grupo,
nadie le pone palabras, y el emoji existe pero no se puede encontrar. Que es
peor que no existir, porque nadie lo reporta. Ahora cada emoji es una línea y
`grupo()` **falla en voz alta** si le faltan las palabras.

Dos detalles que se decidieron y no se dejaron pasar:

- **Sin tildes**, de los dos lados. Quien escribe rápido en un teclado de
  teléfono no las pone, y una búsqueda que exige la tilde es una búsqueda que
  no encuentra nada.
- **Por prefijo y no por subcadena.** Con subcadena, "ojo" trae "enojado" y
  "cerrojo". Un buscador donde escribir más letras trae **cosas distintas** en
  vez de menos cosas no se usa dos veces.

El catálogo se mudó de un archivo de interfaz a `datos/Emojis.kt`, porque
buscar es lógica y la lógica se prueba. `buscarEmojis` y `aplicarTono` no tocan
el framework y por eso corren en la JVM: la misma razón por la que `Recorte`
existe en vez de usar `android.graphics.Rect`.

### Z.3 · Tono de piel, y el defecto que me enseñó algo

👍 salía amarillo y no había forma de cambiarlo. Es la única parte de esa
pantalla donde el valor por defecto **le queda mal a mucha gente a propósito**.

Se elige una vez manteniendo pulsado cualquiera que lo admita y vale para
todos: nadie tiene una mano de cada color, y preguntarlo emoji por emoji sería
pedir la misma respuesta treinta y seis veces. El amarillo es una opción más
—la que elige quien no quiere elegir—, no "ninguna".

**Y me equivoqué.** Puse 🫡, 🫢 y 🫣 en la lista de los que admiten tono porque
tienen una mano dibujada y están en la fila de gestos. Son **caras**, y Unicode
no les da modificador. El resultado en pantalla: la carita amarilla y un
rectángulo de color suelto debajo.

Tres cosas que vale anotar de ese error:

1. **Ninguna prueba de lógica podía verlo.** La lista era coherente consigo
   misma, el código hacía exactamente lo que le pedí, y lo que estaba mal era
   lo que le pedí. Salió de tomar una captura después de elegir un tono. Es la
   misma lección de siempre en este proyecto: la suite cubre el servidor, y los
   defectos de pantalla aparecen cuando alguien abre la pantalla.
2. **El comentario sobre esa lista advertía del error exacto** —"no es los que
   tienen una mano"— mientras la lista lo cometía tres líneas más abajo. Un
   comentario no valida nada.
3. **El arreglo de fondo no es borrar las tres.** Es que eran dos preguntas
   distintas y yo las había confundido: Unicode define qué secuencias existen;
   **la fuente del aparato decide cuáles sabe dibujar**. Una lista escrita a
   mano contesta la primera y aparenta contestar las dos. Ahora la lista es la
   mitad teórica y `PaintCompat.hasGlyph` pone la otra: si la fuente no tiene
   la secuencia, no se ofrece el tono, y nunca se dibuja algo roto. Se exigen
   los cinco: ofrecer seis opciones de las que dos no se dibujan es peor que no
   ofrecer ninguna.

Y hay una prueba clavada para las tres caras. No se deriva de nada —es un hecho
de Unicode— así que vive donde alguien la va a ver si vuelve a agregarlas.

### Z.4 · Escribir un emoji sugiere los stickers con esa etiqueta

Es el atajo de las dos apps de referencia que aquí faltaba: escribir 😂 y que
aparezcan los stickers etiquetados 😂, sin abrir el panel, sin elegir pestaña.
Tres toques pasan a ser uno.

**Y es lo que le da sentido a la etiqueta.** Hasta ahora el emoji del sticker
sólo filtraba *dentro* de la bandeja, o sea servía a quien ya había decidido
mandar un sticker. Esto lo usa en el momento anterior: cuando la persona
todavía está eligiendo **qué** mandar.

La regla es estricta: **un emoji y nada más**. Con "jaja 😂" alguien está
escribiendo una frase, no buscando un sticker, y una tira que aparece a mitad
de una frase tapa el teclado por nada. Se exige además que el emoji **esté en
el catálogo** en vez de adivinar por rango Unicode: así ":)" o "..." no
disparan nada, y lo que dispara es exactamente lo que puede haberse usado como
etiqueta.

Al elegir el sticker **el emoji se borra del compositor**: se lo eligió *en vez*
del emoji, no además de él. Dejarlo escrito mandaría las dos cosas.

Se filtra en memoria y no con una consulta nueva: la colección entera son
decenas de filas y ya está en un Flow vivo. Una consulta por cada tecla sería
pegarle a la base cifrada mientras alguien escribe.

### Z.5 · Seis sitios donde la app afirmaba algo que no había comprobado

El módulo X arregló esto en Privacidad y dejó dicho el principio: **un valor por
defecto no es un dato, es la ausencia de uno**. Quedaban sitios, y tres de ellos
son afirmaciones de seguridad.

| Dónde | Qué decía con el servidor caído |
|---|---|
| `dispositivos` | que esta cuenta **no tiene ningún otro aparato vinculado** |
| `sesiones` | que **nadie más tiene tu cuenta abierta** |
| `misEventosSeguridad` | que **no pasó nada raro** con tu cuenta |
| `miEstadoModeracion` | que **no tenés advertencias** |
| `publicaciones` de un canal | que **el canal no tiene publicaciones** |
| `contactos` al compartir | que **no tenés contactos** |

Los tres primeros son exactamente lo que alguien viene a mirar **cuando
sospecha que le entraron a la cuenta**. Contestarle eso por un fallo de red es
la peor respuesta posible, porque es tranquilizadora y es falsa.

El cuarto es el peor de todos, por lo que este proyecto ya dice de las
advertencias: no se pueden silenciar porque una advertencia que no llega no
cumple su única función, que es dar la oportunidad de corregir antes de la
sanción. **Una advertencia que la pantalla niega es lo mismo con un paso más.**

Ese lo encontré en una captura, mirando la pantalla que acababa de arreglar. El
`if` decía `e == null || e.advertencias.isEmpty()`: las dos cosas en una sola
rama, seis líneas arriba del bloque que estaba corrigiendo. Después barrí el
resto de la interfaz buscando el mismo patrón y salieron los dos últimos.

**El de `publicaciones` es el más incómodo de los seis**, porque estaba
documentado como arreglado. `publicaciones` devuelve `null` al fallar desde el
módulo X —el comentario en `refrescar` lo explica— y la pantalla lo tiraba con
un `?: emptyList()` en la línea de abajo. El caso que quedaba vivo: `canal`
responde y `publicaciones` no, que pasa porque son dos peticiones distintas.

Los seis se arreglan igual: la lectura devuelve `null` al fallar, y el
compilador obliga a decidir qué hacer con él en cada pantalla. Los tipos
hicieron el trabajo de encontrar los sitios; en `dispositivos` fueron tres
errores de compilación inmediatos.

Y una nota de cómo se verifica, que costó una vuelta: `adb reverse --remove`
deja de aceptar conexiones nuevas y **no corta las establecidas**. La primera
vez la pantalla se cargó igual, por la conexión que OkHttp tenía viva. Hay que
reiniciar el proceso además de cerrar el túnel. Parece que la prueba falló y lo
que falló fue la manera de provocar el fallo.

### Y un archivo que perdí y tuve que reescribir

`io.open(p,'w').write(datos)` **trunca antes de poder fallar**. Un script de
edición se encontró con un par de surrogates suelto al codificar, reventó a
mitad, y dejó `EmojisTest.kt` en cero bytes. Las 24 pruebas existían hacía diez
minutos y no estaban en ningún commit.

De ahí en adelante los scripts de este módulo codifican primero, escriben a un
temporal y **reemplazan al final**. Y quedó anotado que el heredoc del Bash
colapsa `\\` en `\`, que es lo que produjo el par de surrogates y, antes, un
selector de variación escrito como el carácter invisible U+FE0F en vez de como
su escape. Ese, en el código, terminó escrito como `0xFE0F.toChar()`: un
carácter que no se ve en el editor es un carácter que alguien borra sin darse
cuenta, y después nadie encuentra por qué un emoji dejó de dibujarse.

### Evidencias

Ocho capturas en
[`docs/evidencias/emojis-y-honestidad/`](evidencias/emojis-y-honestidad/), con
su propio README: lo nuevo, el defecto de las tres caras antes y después, y lo
que dice la pantalla con el servidor caído a propósito.

**1619 pruebas en verde**: 1323 de integración en 35 suites, 75 de JUnit en el
servidor y 221 en la app. Las 25 nuevas son de `EmojisTest`, y tres de ellas se
validaron **rompiendo el código a propósito** —quitando el `quitarTono` del
principio de `aplicarTono` y cambiando el prefijo por subcadena— para
comprobar que fallan contra la versión mala. Una prueba que no falla contra el
código roto no prueba nada.

---

## Módulo AA · Los comentarios de un canal perdían el texto ✅

*"Para las páginas cuando hay comentarios no veo los comentarios ¿por qué?
Podrías ver el tema de las páginas, que tengan reacciones y demás."*

La pregunta parecía ser por una pantalla que faltaba. Era por tres defectos, y
el primero no estaba en la interfaz.

### AA.1 · El texto de los comentarios no existía en ninguna parte

Dos decisiones correctas que juntas dejaban un agujero:

1. **Un canal público no reparte sobres.** Es lo que le permite escalar: con
   diez mil suscriptores, un sobre por dispositivo serían diez mil filas por
   publicación. En vez de eso se emite un aviso y cada cliente se trae el
   historial.
2. **Un comentario se mandaba como mensaje normal cifrado**, por el buzón.

En un grupo eso funciona. En un canal público no hay a quién entregarle el
sobre, así que quedaba la fila de `mensaje_meta` —de ahí el contador, que
cuenta metadatos y era honesto— y el cuerpo se iba al vacío.

Medido en la base antes de tocar nada, en las 196 filas del canal de pruebas:
**cada comentario tenía cero sobres y cero cuerpo guardado.** Sin excepción.

Y `Repositorio.comentar` **no tenía un solo llamador**: era código muerto. La
app nunca había podido escribir un comentario; los que existían los había
creado la suite de integración llamando a la API.

**El arreglo: el comentario sigue al cuerpo de la publicación.** En un canal
público va al servidor en claro (`comentario_contenido`, `V34`), bajo la
excepción que ya estaba argumentada en `V10__canales.sql`, y sin estirar
ninguno de sus tres motivos: un comentario en una publicación pública es tan
público como la publicación. En uno privado sigue siendo un mensaje cifrado, y
la hoja lo dice —ahí sólo se ven los que llegaron a ese teléfono—.

> La tabla guarda `publicacion_id` **además** de `mensaje_meta.responde_a`, y
> no es duplicado por descuido: `responde_a` puede apuntar a cualquier mensaje
> de la conversación, y esta columna lleva una FK a la publicación. O sea que la
> base garantiza que un comentario cuelga de una publicación y no de otro
> comentario, y al borrarse la publicación se van sus comentarios.

### AA.2 · El contador contaba cosas que nadie podía ver

Al abrir la hoja por primera vez quedó a la vista lo peor del defecto: la
tarjeta decía **"1 comentario"** y la hoja decía **"todavía nadie comentó"**.
Las dos eran ciertas.

El contador salía de `mensaje_meta`. Ahora cuenta los comentarios **que se
pueden leer**, y la estadística del canal también: dos números distintos para
"comentarios" en el mismo producto es lo que hace que alguien pierda una tarde
buscando cuál está mal.

Efecto lateral aceptado: los comentarios de antes de `V34`, cuyo texto se
perdió, dejan de contarse. Es lo correcto —no hay nada que abrir— y es más
honesto que un número que no lleva a ninguna parte.

### AA.3 · Las reacciones nunca viajaron

`Publicacion.reacciones` existía en el contrato **desde el módulo F** y la
consulta del muro no las leía. El campo tenía `= emptyList()` por defecto, así
que viajaba siempre vacío: no fallaba nada, simplemente no había nunca ninguna
reacción que dibujar.

Es el mismo error del módulo X con otra cara —un valor por defecto tapando la
ausencia de un dato—, esta vez en el servidor. Y el valor por defecto es lo que
lo hizo invisible: dejaba "no las leí" indistinguible de "no tiene".

Se leen en **una sola consulta** para toda la página, no una por publicación:
llamar al helper de un mensaje dentro de un bucle serían cien consultas para
una página de cien. El N+1 no se nota con tres filas de prueba y se nota con un
canal de verdad, que es cuando ya está desplegado.

**En la interfaz los emojis rápidos están siempre a la vista**, no detrás de un
mantener-pulsado como en el chat. En un canal, reaccionar es lo único que puede
hacer un suscriptor con una publicación cuando los comentarios están apagados;
esconderlo detrás de un gesto que hay que descubrir lo deja sin usar.

### AA.4 · Y el muro venía ordenado al azar

Salió de paso, y es el más viejo de los tres. Ordenaba por `mensaje_id DESC`, y
el id de un mensaje lo genera el **cliente** con `randomUUID()`: un v4, o sea
un número aleatorio. Medido en la base, ordenando por id salían las fechas 19,
19, 20, 19, 18 y 23 de septiembre.

Nadie lo había visto porque hace falta más de una publicación para notarlo, y
el canal de pruebas tenía una.

Un v7 llevaría el tiempo dentro y ordenar por id sería correcto, pero eso lo
decide quien crea el id —el teléfono— y el servidor no puede confiar en que lo
haga. Se ordena por el dato que el servidor sí controla, `creado_en`, con el id
como desempate para que el orden sea **total**: dos publicaciones del mismo
milisegundo, sin desempate, pueden salir en distinto orden en dos consultas y
romper la paginación. El cursor usa el mismo par, porque un cursor que ordena
por una clave y corta por otra saltea filas o las repite.

### AA.5 · Lo que la suite no veía, y por qué

La suite de canales ya probaba los comentarios:

```js
ck('ahora el suscriptor SI puede comentar', r.s === 200);
ck('la publicacion cuenta su comentario', r.b[0]?.comentarios === 1);
```

Las dos pasaban, y **las dos seguían pasando con el defecto puesto**: el
contador contaba metadatos y el metadato se registraba bien. Lo que no se
comprobaba era lo único que le importa a quien lee, que el texto se pueda
recuperar.

**Una prueba que afirma el contador no afirma el contenido.**

Esa segunda línea dice ahora lo contrario y es lo correcto: registrar sólo el
metadato **no** cuenta como comentario, porque no hay nada que leer.

Y dos afirmaciones más de esa suite estaban con números a mano —`publicaciones
=== 1`, `comentarios === 1`— y se rompieron al agregar datos arriba. Ahora se
calculan contra la lista, por lo mismo que en `moderacion.mjs`: **una prueba
con un número fijo se rompe cuando alguien agrega datos, y entonces lo que se
corrige es el número, sin mirar, en vez del código.**

### AA.6 · Un error mío, y la regla que me salté

`V34` agregó la tabla y se olvidó del **tipo de aviso**. `evento_pendiente`
tiene un CHECK con la lista cerrada de avisos que el servidor puede fabricar, y
el INSERT reventó con `violates check constraint tipo_evento_valido`: la ruta
respondió 500 en la primera ejecución de la suite.

Lo cazó la base, que es donde tenía que cazarse. Esa lista cerrada es
deliberada —un tipo de evento es contrato entre el servidor y todos los
clientes— y es la razón por la que el defecto salió en el acto en vez de seis
meses después, en forma de clientes recibiendo un tipo que no saben interpretar.

Se corrigió en **`V35`** y no reescribiendo `V34`, porque `V34` ya estaba
aplicada. Es la regla que dejó escrita `V11__evento_canal.sql` —*una migración
aplicada no se reescribe, se corrige con la siguiente; reescribirla funcionaría
en una base nueva y dejaría roto cualquier entorno donde ya corrió*— y esta vez
me la salté yo.

> **A quién se le avisa de un comentario:** sólo a quien escribió la
> publicación. Un canal con mil suscriptores y cien comentarios por publicación
> daría cien mil avisos de algo que nadie pidió seguir.

### Evidencias

Siete capturas en
[`docs/evidencias/comentarios-de-canal/`](evidencias/comentarios-de-canal/),
con las dos consultas a la base que prueban el diagnóstico.

**1646 pruebas en verde**: 1350 de integración en 35 suites, 75 de JUnit en el
servidor y 221 en la app. Las 27 nuevas se validaron **rompiendo el código a
propósito** —volviendo al orden por id y dejando de leer las reacciones—, y seis
fallaron. La de ordenamiento **elige los ids a propósito** para que el orden por
id sea el contrario al de fecha: con dos ids al azar pasaría la mitad de las
veces contra el código roto, que es peor que no tenerla.

---

## Módulo AB · Un GIF en el chat era una foto ✅

*"¿Por qué no se mueven los stickers o GIFs?"*

Dos respuestas distintas, y conviene separarlas porque sólo una era un defecto.

### AB.1 · El GIF: `BitmapFactory` devuelve un fotograma

`VistaImagen` dibujaba el archivo con `BitmapFactory.decodeFile` → `Image`. **Un
`Bitmap` es un fotograma.** Cualquier GIF recibido en el chat se veía congelado
en el primero, que es exactamente lo que un GIF no es. `VisorImagen` —abrirlo a
pantalla completa— tenía el mismo código y el mismo defecto, y ahí es peor,
porque la persona entró justamente a mirarlo.

El **envío no estaba mal**. `enviarAdjunto` ya tenía una guarda explícita para
no pasar un GIF por el compresor, con su comentario:

```kotlin
// Un GIF NO se recomprime: pasarlo por el compresor JPEG lo dejaria
// como una sola imagen quieta, que es justo lo contrario de un GIF.
if (clase == ClaseAdjunto.IMAGEN && original.mime != "image/gif") { ... }
```

O sea que los fotogramas llegaban enteros al teléfono y se tiraban al dibujar.

**Y es el mismo defecto que ya se había arreglado en Y.2.3.** Ahí `VistaSticker`
pasó de `BitmapFactory` a Coil, con una nota explicando que un Bitmap es un
fotograma. Se arregló el sitio donde se había visto y se dejó igual el otro
sitio que tenía el mismo código, a doscientas líneas de distancia, en el mismo
archivo.

> **Arreglar una instancia de un defecto no es arreglar el defecto.** Cuando el
> arreglo es "este código estaba mal", lo que sigue es buscar quién más tiene
> ese código, no cerrar el archivo.

De paso se corrige algo que el comentario anterior admitía a medias:
decodificar a resolución completa **dentro de la composición**, aunque fuera
una sola vez y recordada, bloquea el hilo de interfaz. Coil decodifica fuera y
cachea.

La miniatura sigue **sin** pasar por Coil, y es a propósito: esos bytes vienen
de un sobre ajeno y pasan por `Media.miniaturaAjena`. Que la miniatura sea un
fotograma está bien —es un anticipo, no el archivo—.

### AB.2 · El sticker: no estaba roto, no había ninguno animado

`VistaSticker` ya usaba Coil. Los dos stickers del emulador eran **recortes de
fotos**, estáticos por construcción: no había nada animado que mirar.

Verificado por primera vez con un archivo animado de verdad, el camino entero
funciona: se detecta por la cabecera, se ofrece sin editor —"tiene movimiento,
así que se agrega tal cual"—, y se copia **byte por byte**: 279171 de entrada,
279171 de salida. Esa igualdad es lo que garantiza que no se aplanó.

### AB.3 · Cómo se comprueba que algo se mueve

Una captura es un fotograma, así que no prueba nada sobre movimiento. **Tres
capturas de la misma región separadas por unos cientos de milisegundos**, y un
`ImageChops.difference().getbbox()` entre ellas: si difieren, hay animación.

Es el único modo de verificarlo desde una interfaz que sólo devuelve imágenes
fijas, y por eso queda escrito.

### AB.4 · Dos trampas del entorno, y una se disfrazó de defecto nuestro

Las dos son de la capa de Windows y las dos producen datos corruptos en
silencio.

**`adb shell cat` corrompe binarios.** Sacar el GIF con
`adb shell "run-as ... cat x.gif" > local.gif` inyecta retornos de carro:
279171 bytes se convirtieron en 280387. El archivo resultante rompe hasta al
MediaProvider de Android (`skia: decodeFrame: #lzw: bad code`), y en la app se
veía como **una vista previa en blanco** — idéntico a un defecto nuestro.
Estuve mirando el código de la hoja de crear sticker buscando un fallo que no
existía. Con `adb exec-out` sale intacto.

**Git Bash reescribe las rutas del dispositivo.** `adb push origen
/sdcard/Pictures/x.gif` respondía *"1 file pushed"* y no dejaba nada: MSYS
convertía el destino a `C:/Program Files/Git/sdcard/...`. Sólo se ve cuando el
directorio inventado no existe. Se resuelve con `MSYS_NO_PATHCONV=1` y la ruta
de origen en formato Windows.

Es la tercera vez en esta sesión que una capa de traducción de Windows
corrompe datos en silencio —antes fue el heredoc colapsando `\\` en `\`—, así
que va anotado.

### Evidencias

Cuatro tiras de fotogramas en
[`docs/evidencias/gifs-que-se-mueven/`](evidencias/gifs-que-se-mueven/).

**Ninguna prueba automática cubre esto**, y conviene decirlo en vez de fingir:
que un composable use `BitmapFactory` en vez de Coil no lo ve una prueba de
lógica, y comprobar que algo se anima necesita el aparato. Lo que sí está
cubierto es la detección de animación por cabecera (`StickersTest`), que es la
parte que se puede probar sin pantalla.

---

## Módulo AC · Un canal sólo podía publicar texto ✅

Continuación del hilo de las páginas. Empezó por un barrido y acabó en una
capacidad que faltaba y dos defectos que ya estaban.

### AC.0 · Primero, el barrido que pedía la lección de AB

AB terminó con *"arreglar una instancia de un defecto no es arreglar el
defecto"*. Así que antes de agregar nada, se buscaron las otras instancias de
los tres patrones ya encontrados:

| Patrón | Resultado |
|---|---|
| `BitmapFactory` donde importa la animación | **limpio**: los usos que quedan son miniaturas —que deben ser un fotograma—, medición de dimensiones y el editor de stickers, que necesita un `Bitmap` para recortar |
| `ORDER BY` sobre un id generado por el cliente | **limpio**, y con una razón: `mensaje_meta` es la **única** tabla sin `uuidv7()` por defecto |
| lecturas de red que devuelven vacío al fallar | ya barrido en Z.5 |

Lo segundo vale escribirlo como invariante:

```
 auditoria        | uuidv7()
 denuncia         | uuidv7()
 evento_pendiente | uuidv7()
 sesion           | uuidv7()
 sobre_pendiente  | uuidv7()
 mensaje_meta     |            ← el id lo pone el telefono
```

**`ORDER BY id` es cronológico en todas menos en una**, y esa una era justo la
que estaba mal. La cola del buzón ordena por `sobre_pendiente.id`, que es v7,
así que la entrega de mensajes siempre estuvo en orden.

### AC.1 · La capacidad que faltaba

El compositor de un canal era un campo de texto y un botón de enviar. No había
forma de adjuntar nada. Para una página de anuncios eso es el hueco grande.

Ahora: botón de imagen, vista previa con su X antes de publicar, la imagen en
la tarjeta y un visor a pantalla completa. **Con imagen y sin texto se
publica** —una foto sola es una publicación—; sin las dos cosas, no.

### AC.2 · Por qué va sin cifrar, y por qué aquí eso es una ventaja

El adjunto normal se cifra en el teléfono y **la clave viaja dentro del
sobre**. Un canal público no reparte sobres, así que no hay dónde meterla:
quien se suscriba mañana tendría el archivo y no la llave.

Va en claro, bajo la misma excepción declarada que el cuerpo, con los mismos
tres motivos de `V10__canales.sql` y **sin estirar ninguno**.

Y eso habilita algo que el brief pedía y el cifrado hacía imposible: **validar
el tipo de archivo en el servidor**. El §9 de la cobertura dice que las dos
cosas eran incompatibles y que se resolvió partiendo por clase —fotos de perfil
validadas, adjuntos cifrados no—. **Esta es la tercera clase**, y cae del lado
validable precisamente porque su contenido ya es público.

`Adjuntos.confirmar` lee los doce primeros bytes del almacén y comprueba la
firma real. La suite sube un ejecutable llamado `.png`, con
`Content-Type: image/png`, y se rechaza.

> Leer doce bytes **no rompe** la regla de `Almacen` que dice que los bytes no
> pasan por este servidor. Esa regla existe para no ser la tubería de archivos
> de 64 MB de los clientes; aquí son doce bytes entre el servidor y el almacén,
> que están al lado, para responder algo que sólo el servidor puede responder.

### AC.3 · El defecto que el rechazo destapó, y que ya estaba

La prueba no se quedó en "responde 400": preguntó si el adjunto rechazado
**había dejado de existir**. Respondió 200.

El rechazo borraba la fila y **lanzaba dentro de la misma transacción**, así que
`Db.tx` hacía rollback y el `DELETE` se deshacía. Quedaba una fila apuntando a
un objeto que **sí** se había borrado del almacén —esa parte no es
transaccional—, o sea un adjunto que existe para la base, no existe en el disco,
y del que `leer` devolvía tranquilamente una URL de descarga.

**Pasaba con el tope de tamaño desde el módulo D**, con el mismo código.
Afirmar el 413 nunca lo habría visto, porque el 413 llegaba igual.

Es la misma familia que la lección del `SAVEPOINT` ya anotada aquí: **una
sentencia y una excepción en la misma transacción no son dos cosas
independientes.** Ahora la transacción decide y devuelve el motivo; la limpieza
—borrar el objeto y la fila— pasa después, cada cosa en su sitio.

### AC.4 · Y otro contador que contaba metadatos

`cuenta publicaciones` falló con `estadistica=6 muro=5`. La estadística contaba
`mensaje_meta WHERE responde_a IS NULL`, así que un mensaje registrado cuyo
cuerpo nunca se guardó subía el número sin que hubiera nada en el muro.

Es **el mismo arreglo que se le hizo al contador de comentarios en AA**, en la
consulta de al lado, y no se hizo entonces. La lección de AB otra vez, dentro
del mismo archivo.

### AC.5 · El orden de los pasos, y el primer intento que tapaba el texto

**La imagen se sube antes de guardar el cuerpo**, por lo mismo que en
`publicarHistoria`: si algo falla en el medio, lo que queda es un adjunto que
nadie ve —y que barre el limpiador— en vez de una publicación visible con un
hueco donde debería estar la foto. El fallo barato es el invisible.

Y al fallar se devuelven **las dos cosas** al compositor, el texto y la imagen
elegida: perder el trabajo entero por un fallo de red obliga a rehacerlo.

**El primer intento tapaba el texto.** Copié el tope de proporción del chat
(`0.6`) y la tarjeta del canal ocupa el **ancho completo**, así que una foto
vertical medía 1.66 veces el ancho de la pantalla y el texto quedaba fuera. En
el chat la burbuja es más angosta y el mismo número da una altura razonable;
copiarlo tal cual fue el error. Ahora el suelo es `0.8`: recorta una vertical,
pero una publicación donde no se ve el texto que la acompaña no es una
publicación, es una foto.

> La URL firmada **no viaja en el muro**: se pide por publicación y se recuerda
> mientras la pantalla vive. Una URL firmada metida en la lista se vence
> mientras alguien lee, y la foto dejaría de cargar a mitad del scroll.

### Evidencias

Cinco capturas en
[`docs/evidencias/imagen-en-publicaciones/`](evidencias/imagen-en-publicaciones/),
incluida la del primer intento con el texto tapado.

**1664 pruebas en verde**: 1368 de integración en 35 suites, 75 de JUnit en el
servidor y 221 en la app. Las 14 nuevas no necesitaron reversión para
validarse: dos de ellas **fallaron contra el código que ya existía** y de ahí
salieron AC.3 y AC.4.

---

## Módulo AD · Comunidades ✅

Era **el último hueco declarado del brief**. El §3 pedía un ajuste de
"invitaciones a comunidades" y la cobertura decía, con razón, que faltaba el
contenedor entero y no el ajuste.

### AD.1 · Qué es una comunidad, y qué la distingue de una carpeta

Un conjunto de grupos bajo un nombre, **más un canal de anuncios**. Lo segundo
es lo único que la hace una comunidad: sin él, agrupar chats es una carpeta, y
una carpeta se resuelve en el teléfono sin que el servidor se entere. El canal
de anuncios es un sitio donde quien administra alcanza a todos de una vez, y
eso sí necesita existir en el servidor.

El canal de anuncios es una `conversacion` de tipo `canal`, **privada**:
reusa participante, roles, permisos, mensajes y adjuntos sin duplicar nada.
Privada y no pública porque los anuncios de una comunidad son para su gente; un
canal público guardaría el contenido en claro, y esa excepción se declara para
lo que de verdad es público, no se hereda sin querer.

**Administrar la comunidad es ser admin de su canal de anuncios.** No se
inventa un rol nuevo.

### AD.2 · La pertenencia se deriva, no se declara

Sos de la comunidad si sos de alguno de sus grupos. No hay lista de miembros
aparte, y eso es deliberado: **dos listas que dicen lo mismo se separan, y el
día que se separan nadie sabe cuál manda.**

Lo que sí existe es la fila de `participante` del canal de anuncios, que hay
que **mantener** en cuatro momentos:

1. cuando un grupo entra a la comunidad
2. cuando alguien entra a un grupo que **ya estaba** en la comunidad
3. cuando alguien sale de un grupo (o lo expulsan)
4. cuando un grupo sale de la comunidad

Los enganches van en los **pasos por los que todos acaban pasando** —
`Repo.agregarParticipantes`, `Repo.salir`, `Grupos.unir`, `Grupos.expulsar` — y
no en cada llamador: repetirlos es garantizar que alguien agregue un camino
nuevo y se olvide.

> Un grupo pertenece a **una** comunidad como mucho. Con dos, "salir de la
> comunidad" deja de tener un significado único: ¿de qué anuncios te vas? Es la
> clase de generalidad que se agrega barata y se paga cara.

### AD.3 · La prueba que no probaba nada, y dos defectos que se tapaban

Quité los dos enganches a propósito y la suite dio **una sola falla de 41**.

La causa: dos de mis cuatro secciones comprobaban `GET /v1/comunidades`, que se
**deriva** de estar en un grupo. Al salir del grupo la comunidad desaparece de
esa lista aunque la fila del canal se quede para siempre. O sea que pasaban con
el enganche puesto y sin él.

Y había algo peor, que sólo se vio al insistir: **los dos defectos se tapaban
entre sí.** Sin el enganche de entrada, quien entró después nunca estuvo en el
canal, así que "sale del canal" pasaba trivialmente. Hubo que romperlos **por
separado**.

> **Dos defectos simultáneos pueden esconderse el uno al otro.** Validar por
> reversión con varios cambios a la vez mide menos de lo que parece.

Con la sección arreglada —comprobando la lista de conversaciones, que es la que
refleja `participante`— y sólo el enganche de salida roto, fallan dos.

### AD.4 · El ajuste del §3, y por qué es este y no otro

El brief pide "invitaciones a comunidades". En este modelo a nadie se lo invita
a una comunidad: se lo agrega a un **grupo**, y eso ya lo gobierna
`priv_grupos`.

Lo que `priv_grupos` **no** cubre es el caso propio de las comunidades: alguien
agrega a la comunidad **el grupo en el que ya estabas**, y de golpe estás en un
canal de anuncios con quinientos desconocidos sin que nadie te haya agregado a
nada. Ese es el hecho nuevo, y es el que `priv_comunidades` gobierna.

| | Qué pasa |
|---|---|
| `todos` | me meten en el canal de anuncios |
| `conocidos` | sólo si quien agrega el grupo es alguien con quien ya hablo |
| `nadie` | mi grupo puede estar en la comunidad; yo no en sus anuncios |

**`nadie` no te saca del grupo ni te echa de la comunidad.** Un ajuste de
privacidad que te expulsa de algo no es un ajuste de privacidad, es una
sanción.

Por defecto `todos`, al contrario que `historias` y `llamadas`: estar en los
anuncios de la comunidad de tus propios grupos es lo que casi todo el mundo
espera, y un defecto en `nadie` dejaría a la mayoría sin la función sin saber
por qué.

### AD.5 · No falla entero

Agregar cinco grupos teniendo permiso en cuatro mete los cuatro y **devuelve
los rechazados con su motivo**. Un 403 que descarta los cinco convierte un
aviso en un reintento a ciegas.

La pantalla lo dice —"Algunos grupos no entraron: No administras ese grupo"— y
el número de la lista dice la verdad en vez de fingir que entró.

### AD.6 · Y no hay botón de "unirse"

No es un olvido: la pertenencia se deriva. Una ruta para unirse crearía una
segunda forma de pertenecer, y volveríamos a las dos listas que se separan.

### AD.7 · Una capacidad probada que la pantalla no ofrecia

Al mirarlo en el emulador: la hoja de una comunidad terminaba en **"0 grupos"**
y nada mas. **Una comunidad podia quedar en cero grupos sin forma de
arreglarlo**, porque los grupos solo se podian elegir al crearla.

Y lo peor: `agregarGrupos` y `editar` estaban **construidos y probados en el
servidor** —con sus pruebas en verde— y la pantalla no los llamaba.

> **Una capacidad probada que la interfaz no ofrece es una capacidad que no
> existe.** La suite decia que si y la pantalla decia que no. Las 43 pruebas
> pasaban porque prueban la API, y la API estaba bien.

Ya estan: lapiz para editar, "+ Agregar" con su selector, y un vacio que dice
que hacer en vez de dejar un callejon sin salida.

**Y un comentario que habia empezado a mentir.** La hoja de crear decia "solo
se ofrecen los grupos que administro" y el codigo ofrecia todos. Se corrigio
**el comentario y no el codigo**: hace falta `grupo.editar_info` y el telefono
no lo sabe —lo mas parecido que tiene es una jerarquia, que es un proxy—.
Filtrar por un proxy esconderia grupos que si se podian agregar, que es peor
que ofrecer uno y explicar el rechazo. Los permisos los resuelve el servidor en
cada peticion; la pantalla no los adivina.

### Evidencias

Ocho capturas en
[`docs/evidencias/comunidades/`](evidencias/comunidades/), incluidas las del
callejon sin salida antes y despues.

**1707 pruebas en verde**: 1411 de integración en **36 suites**, 75 de JUnit en
el servidor y 221 en la app. Las 43 nuevas están en `pruebas/comunidades.mjs`,
y su validación por reversión es la que produjo AD.3.

> La máquina se reinició a mitad del módulo. El entorno se levantó de cero
> —Docker, los tres contenedores, las dos instancias y los dos AVDs— y quedó
> anotado que `emulator-5554` y `5556` no vuelven necesariamente al mismo AVD.

---

## Módulo AE · Pulido visual ✅

*"¿Qué más hay para mejorar, estéticamente?"*

Se miraron las cuatro pestañas una por una. Lo que salió no fue cuestión de
gusto: seis cosas concretas, y una de ellas era un defecto funcional.

### AE.1 · La app entera estaba sin tildes

**266 palabras en 32 archivos**: `Diagnostico`, `todavia`, `telefono`,
`numero`, `mas`, `contrasena`, `sesion`, `camara`. Y era **inconsistente** —las
pantallas nuevas sí las tenían—, así que la app parecía medio traducida. Es lo
que más pesa visualmente y lo que menos se nota al escribir el código.

Se hizo con un script, y **los dos guardias nacieron de fallos reales**:

| Guardia | Por qué |
|---|---|
| Sólo texto, nunca un identificador | Hay cadenas que son rutas, claves y tipos: `"canal"`, `"grupo"`, `"mi-cuenta"`. Cambiar una rompe el contrato **en silencio** —compila y falla al comparar—. Regla: se traduce si tiene un espacio o empieza en mayúscula |
| Las interpolaciones son código | La primera versión produjo cinco `Unresolved reference 'publicación'` desde `"${publicacion.autor}"` |

Los cinco los **cazó el compilador**, que es para lo que sirve. Y dos pruebas
de accesibilidad fallaron —afirman sobre el texto anunciado—, que es la suite
haciendo su trabajo.

### AE.2 · El mismo canal dibujado de dos maneras en la misma pantalla

"Mis canales" usaba el icono de **dos personas** y "Descubrir" el **megáfono**.
`FilaMiCanal` pasaba `esGrupo = true` a un canal; `Avatar` ya tenía `esCanal`.

### AE.3 · Dos maquetaciones, una encima de la otra

Las filas de "Descubrir" dibujaban **su propio círculo a mano** —46 dp, siempre
cian— mientras "Mis canales" usaba el `Avatar` compartido —44 dp, color por
nombre—. Dos implementaciones de lo mismo en la misma pantalla: se leía como
dos listas de dos aplicaciones distintas. Y los encabezados de sección eran de
colores distintos, sugiriendo una jerarquía que no existe.

### AE.4 · El vacío de Contactos estaba torcido

El `Column` estaba centrado y al texto le faltaba `textAlign = Center`: con dos
líneas, la segunda se iba a la izquierda dentro de un bloque centrado. Y era el
**único vacío de la app sin icono**.

### AE.5 · El botón flotante tapaba la última conversación

`LazyColumn(Modifier.fillMaxSize())` sin `contentPadding`. Con pocos chats no
se nota porque la lista no llega abajo; con la lista llena, **la última
conversación queda debajo del botón y no se puede abrir**.

### AE.6 · "24 h" estaba en la columna de las horas

La fila de historias lo ponía alineado a la derecha, que es donde las filas de
abajo ponen `Ayer` y `Martes`: se leía como una marca de tiempo. Ahora dice
**"dura 24 h"** — una palabra que sobra en cualquier otro sitio y aquí es la
que desambigua.

### AE.7 · Y uno que no era estético

**El canal de anuncios de una comunidad abría en la pantalla de chat.** La
pantalla de comunidades navegaba a `chat/$id` para todo, y un canal abierto ahí
no tiene muro, ni reacciones, ni comentarios: justo lo que hace que un canal
sea un canal.

> Un solo callback para dos destinos distintos compila, navega, y lleva al
> sitio equivocado. Ahora son dos: `onAbrirCanal` y `onAbrirChat`.

### Evidencias

Seis capturas, antes y después, en
[`docs/evidencias/pulido-visual/`](evidencias/pulido-visual/).

**1707 pruebas en verde**, sin cambio de conteo: dos de accesibilidad
cambiaron de expectativa, ninguna se agregó ni se quitó.

---

## Módulo AF · Llamadas de grupo ✅

*"Que haya llamada grupal y videollamada grupal."*

Existían desde el módulo K y no se podían usar. Lo que faltaba no era el
protocolo: era poder **elegir a quién**.

### AF.1 · El tope se aplicaba al grupo, no a la llamada

En un grupo **no había botón de llamar**. El comentario que lo ocultaba decía
la verdad a medias:

> *"En un grupo la llamada en malla está limitada a 4 y no hay interfaz para
> elegir a quién, así que ofrecer el botón sería prometer algo que la pantalla
> no cumple."*

El servidor era peor de lo que ese comentario admitía: llamaba a **todos** los
de la conversación y rechazaba la llamada entera si eran más de cuatro. O sea
que **un grupo de ocho no podía tener una llamada nunca**, ni entre tres de sus
miembros.

Ahora `IniciarLlamadaReq` lleva `invitados`, y `MAX_EN_LLAMADA` se mudó al
protocolo: la app lo necesita para no dejar elegir más gente de la que cabe, y
con una copia en cada lado el día que cambie uno la pantalla dejaría elegir a
cinco para que el servidor los rechace después.

### AF.2 · El servidor no se cree esa lista

Llega del cliente, así que es una petición y no una orden. Se cruza con quien
está de verdad en la conversación; un id ajeno da **403**.

Y hay un segundo recorte que es el que de verdad importa: **los destinos**. Una
oferta cifrada que llega hace sonar el teléfono **sin preguntarle al servidor**.
Si `LlamadaCreada.destinos` trajera el grupo entero, elegir invitados no
serviría absolutamente de nada — les sonaría igual. Lo mismo en `contestar`: si
ahí viniera el grupo entero, el segundo en contestar cerraría malla contra los
ocho y desharía la elección.

### AF.3 · Elegir cambia lo que significa un bloqueo

Cuando se llamaba al grupo entero, un bloqueo era ruido de fondo: la llamada
iba dirigida a la conversación. **Elegir a una persona es señalarla**, y hacer
sonar el teléfono de quien te bloqueó por la vía de un grupo compartido sería
rodear el bloqueo sin romperlo.

Se quitan **en silencio** y no con un error: decir *"esa persona te bloqueó"*
revela justo lo que un bloqueo esconde.

### AF.4 · La malla, y el *glare*

Quien llama ofrecía a todos y cada uno le respondía. Entre ellos no pasaba
nada: en una llamada de tres, B y C hablaban los dos con A y **no se oían entre
sí**. La llamada de grupo existía a medias y parecía un fallo de red.

Al cerrarla aparece el problema de verdad: si B y C se ofrecen a la vez, cada
uno recibe una oferta mientras espera una respuesta —el *glare* de WebRTC— y
las dos conexiones quedan a medio negociar. Hace falta que **exactamente uno**
ofrezca, decidido sin mandar ningún mensaje extra: ofrece el del id de
dispositivo menor.

Esa comparación vive en [`Malla.kt`](../app/src/main/java/com/wtfuck/app/datos/Malla.kt)
y no dentro del servicio, porque de una línea depende que una llamada de grupo
conecte o no, y allí se puede probar sin WebRTC, sin red y sin tres teléfonos.
`MallaTest` simula la llamada entera para 2, 3 y 4 participantes y **con cada
uno como iniciador**, y exige que cada par tenga exactamente una oferta: cero es
el defecto original, dos es el glare.

Y el vídeo: `_videoRemoto` era **una** pista, así que con tres personas cada una
que llegaba pisaba a la anterior y ganaba la última. Ahora es un mapa por
dispositivo y una rejilla de dos columnas —el techo es cuatro, así que dan 1x1,
2x1 y 2x2— con el nombre sobre cada recuadro.

---

## Tres defectos que sólo aparecen haciendo la llamada

Ninguno salió de leer el código.

### AF.5 · Un rechazo cortaba el timbre de los demás

Se llamó a dos, el primero declinó y la llamada terminó entera **con el segundo
todavía sonando**. La condición era `dentro >= 2 && estado == "en_curso"`: o
sea que mientras la llamada *sonaba*, cualquier rechazo la mataba.

### AF.6 · Abrir la app mataba la llamada entrante

El peor. Al arrancar, la app pedía `/en-curso` y **colgaba lo que viniera**. El
razonamiento era correcto para una llamada en la que uno ya estaba —las
sesiones WebRTC murieron con el proceso—, pero el servidor devuelve también la
llamada que te está sonando. Con un aviso de llamada, **abrir la app la mataba
antes de que sonara**, que es exactamente el flujo normal.

### AF.7 · El barrido cerraba la llamada y no avisaba a nadie

`cerrarTimbresVencidos` cerraba la fila y se callaba: la pantalla del que
llamaba se quedaba en *"Llamando…"* para una llamada que ya no existía.

> La prueba que existía miraba la **fila**. Una prueba que afirma sobre un
> estado no afirma sobre el aviso.

### AF.8 · El TURN de desarrollo apuntaba al propio emulador

`turn:127.0.0.1:3478`. Desde un emulador, `127.0.0.1` es el emulador mismo: una
llamada entre dos nunca podía relevar y el medio se quedaba en `ICE CHECKING`
para siempre. Con `10.0.2.2` —la máquina anfitriona vista desde el emulador— el
vídeo de la evidencia es vídeo de verdad viajando por el relevo.

---

## AF.9 · La app estaba escrita en dos dialectos

26 apariciones de voseo en 8 archivos —`tenés`, `Elegí`, `Podés`, `Mantené`—
mientras el resto de la app tuteaba. La misma clase de defecto que las tildes
del módulo AE: no se nota escribiendo el código y se nota entero leyendo la
pantalla.

El script tocó **sólo cadenas**, nunca comentarios ni identificadores. Aun así
dejó un daño que hubo que cazar a mano:

> `('sos ', 'eres ')` convirtió **"acce*sos* recientes"** en *"acceeres
> recientes"*.

Una regla sin límite de palabra entra dentro de otra palabra. Es la misma
lección del módulo AE con `${publicacion.autor}`, y esta vez el compilador no
podía ayudar porque el resultado compila perfectamente.

---

## AF.10 · La pantalla de llamada

- El título se metía **debajo de la ventanita del vídeo propio**: se leía
  "@Equipo seguri" y el resto quedaba tapado por la cara de uno. Ahora el
  encabezado se va a la izquierda cuando hay vídeo, donde no compite con el
  botón de minimizar ni con la ventanita.
- La base del texto era `alpha = 0.55`. Sobre un vídeo **claro** eso da un gris
  medio y el texto secundario desaparecía. Ahora `0.82`, y el botón de
  minimizar tiene su propia base.
- La tercera línea dice cosas **distintas** a cada lado: quien llama ve
  `con joaquin, rocio` —ya sabe a qué grupo, lo que no ve es a quién eligió— y
  quien recibe ve `llamada de grupo · Equipo seguridad`, porque veía un nombre
  de persona y no sabía de dónde salía.

---

### Cómo se comprobó que las pruebas sirven

Siete guardias del servidor, rotos **de a uno** y cada uno con su servidor
recompilado, porque dos defectos a la vez se tapan entre sí. El que menos, una
prueba; el que más, seis.

Y una lección sobre el propio arnés: la primera versión del guion de validación
mandaba la salida de Gradle a `Out-Null`, así que un defecto **que no
compilaba** dejaba el jar anterior —el bueno— y la suite pasaba entera.

> Una validación que no comprueba que el defecto llegó a instalarse no valida
> nada. Durante un rato creí que una prueba no servía cuando el problema era
> que el defecto nunca se había instalado.

### Evidencias

Siete capturas del recorrido completo, con vídeo conectado de verdad, en
[`docs/evidencias/llamadas-grupales/`](evidencias/llamadas-grupales/). El guion
que las produce es [`pruebas/llamada-de-grupo.sh`](../pruebas/llamada-de-grupo.sh).

**1761 pruebas en verde**: 1456 de integración en 36 suites, 230 JUnit de app
y 75 de servidor. Son **+54** sobre las 1707 del módulo anterior — 45 de
integración y 9 de `MallaTest`.

---

## Módulo AG · La garantía del §16 dejó de ser una foto ✅

*"Aparte de aplicar seguridad."*

`ajeno.mjs` es el barrido de acceso ajeno: recorre las rutas que mutan algo con
un id, las ataca con un tercero sin relación con el objeto y exige que el
servidor se niegue. Es una de las mejores suites del proyecto —cada fila se
corre dos veces, ataque y control, para que un 400 por cuerpo mal formado no
cuente como verde—.

Y abría con esta frase:

> *"Un auditor de rutas sobre `Main.kt` da 127 rutas, 78 de ellas mutantes y 45
> con un `{id}` en el camino."*

**Ese número estaba en un comentario.** No había auditor: era una foto de un
día, y las filas son una lista fija.

### AG.1 · Lo que eso significaba

Toda ruta agregada después quedaba fuera del barrido **y la suite seguía dando
verde**. Hoy el servidor tiene 149 rutas, 92 mutantes y **54 con un `{id}`**:
diecinueve más de las que el comentario decía, y ninguna de las nuevas se
atacaba nunca. Entre ellas, las tres de comunidades (módulo AD), las de
historias, y las del panel.

Es la cuarta vez que este proyecto se encuentra la misma forma de defecto:

| Dónde | La forma |
|---|---|
| Módulo AD | Una capacidad que la API tiene y la interfaz no ofrece |
| Módulo AD | Una prueba que afirma sobre un contador y no sobre el contenido |
| Módulo AF | Un barrido que cierra la llamada y no avisa a nadie |
| **Aquí** | **Una garantía que no alcanza al código nuevo** |

### AG.2 · El auditor, que ahora es código

[`pruebas/lib/auditor-de-rutas.mjs`](../pruebas/lib/auditor-de-rutas.mjs) lee
`Main.kt`, resuelve las constantes `RUTA_*` del protocolo —la mitad de las
rutas se declaran como `post(RUTA_PERFIL)` o `post("$RUTA_LLAMADAS/{id}/contestar")`,
y una expresión regular que sólo mire cadenas literales se las pierde y da un
número que parece bien— y devuelve el inventario.

`ajeno.mjs` le pregunta al final: **si queda una ruta mutante con id sin
atacar ni eximir, falla y la nombra**. Se comprobó agregando una ruta
inventada: la suite la señaló por su nombre.

Vive en `lib/` porque es una biblioteca. Dejada junto a las suites, el runner
intentaba correrla y la marcaba `ROTA`.

### AG.3 · Las exenciones llevan motivo

Once rutas no se atacan, y cada una dice por qué. Una exención sin motivo es
una ruta olvidada con otro nombre.

- `PUT /v1/perfil/{campo}` — el parámetro es un **campo**, no un objeto.
- `POST/DELETE /v1/bloqueos/{username}` — bloquear a cualquiera está permitido
  a propósito.
- `POST /v1/invitaciones/{codigo}` — el código **es** la credencial.
- `POST /v1/canales/{id}/suscribir` — un canal público es abierto por diseño.

Y al revés: una exención que ya no corresponde a ninguna ruta también falla,
porque es basura que se queda tapando el hueco siguiente.

### AG.4 · Nueve rutas nuevas atacadas, y una que se movió

Entraron al barrido las tres de comunidades, dos de historias, y tres del panel
que un no-staff no debería tocar.

La de reconocer una advertencia **se movió a `moderacion.mjs`** en vez de
eximirse: una advertencia sólo existe después de resolver una denuncia, y
sembrar denuncias en los veinte mundos de control de `ajeno.mjs` inunda la cola
global de moderación y tira abajo esa otra suite. Se ataca donde el objeto ya
existe.

### AG.5 · Y ahí apareció algo

`POST /v1/moderacion/advertencias/{id}/reconocer` respondía **204 a cualquiera**.

No era una fuga: el `UPDATE` ya filtraba por `usuario_id`, así que la
advertencia de otro nunca se tocaba. Pero la ruta decía *"hecho"* cuando no
había pasado nada, y un cliente que lea ese 204 pinta la advertencia como leída
y se queda mintiendo hasta que recargue.

Ahora devuelve cuántas filas tocó y contesta **404** si fueron cero. Con dos
cuidados:

- **No es un oráculo.** Un id ajeno y un id inventado responden exactamente lo
  mismo, así que nadie averigua si una advertencia existe. Hay una prueba que
  lo fija.
- **Sigue siendo idempotente.** La condición `reconocida_en IS NULL` habría
  hecho que reconocer dos veces diera 404 la segunda, y un reintento tras un
  corte de red se leería como *"esa advertencia no es tuya"*. El `coalesce`
  conserva la fecha original.

---

### AG.6 · Y lo mismo del lado de la lectura

`ajeno-lectura.mjs` tenía el mismo comentario con el mismo problema: *"El mapa
de `Main.kt` da 49 rutas de lectura"*. Hoy son **57**.

Tres que nunca se habían mirado ya se atacan: el detalle de una comunidad
ajena, quién vio la historia de otra persona, y los comentarios de un canal
privado ajeno. Las 34 exenciones son casi todas *"mis propios datos"* —leer lo
de uno no es leer lo ajeno— y van una por línea igualmente: el día que alguien
agregue una ruta tiene que venir a escribir su razón, y escribirla obliga a
pensarla.

Y apareció un caso que el prefijo disfrazaba: `GET /v1/panel/consola` **no es
una lista global** pese al `/panel`. Es `Consola.mias(yo)`, los accesos de
quien pregunta, así que un desconocido recibe 200 con la lista vacía y eso está
bien. Se comprueba como `/leidos`: lo que importa no es el código de estado
sino que no venga nadie.

Los dos guardianes se validaron agregando una ruta inventada —`POST
/v1/inventada/{id}/tomar` y `GET /v1/espiar/{id}`—: las suites las señalaron
por su nombre.

---

**1790 pruebas en verde**: 1485 de integración en 36 suites, 230 JUnit de app y
75 de servidor. En la configuración mínima —una instancia, sin Redis— son
**1770**, con `bus` y `bus-inyeccion` marcadas `OMIT`.
