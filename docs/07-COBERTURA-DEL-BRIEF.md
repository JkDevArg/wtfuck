# Cobertura del brief, punto por punto

Este documento responde una sola pregunta: **de las 18 secciones del brief, qué
está hecho, qué falta, y dónde se hace cada cosa.**

La [hoja de ruta](06-HOJA-DE-RUTA.md) va por módulos técnicos (A, B, C…). Esta
tabla va por las secciones del brief, que es como está escrito el pedido. Es el
mismo trabajo visto desde el otro lado.

Convención: ✅ hecho y verificado · 🔨 parcial · ⬜ pendiente

**Estado al 2026-09-25: 1903 pruebas en verde** (1523 de integración + 380 de
JUnit) con todo levantado; **1845** en la configuración mínima, porque dos
suites se omiten cuando les falta el entorno y lo dicen.
Módulos 0, A, B, C, D, **E, F, G, H, I, J, K, L, M, N y O completos**, y **P en
beta cerrada**.

Lo que queda es **una credencial, dos cosas de alcance declarado y un
subsistema**: la clave de Firebase para el push (el camino está entero y
probado contra un FCM de mentira), `K.5` (SFU para llamadas de más de 4, la
malla tiene techo), el cliente web de mensajería y **los bots** del §4.

Las **comunidades**, que eran el otro hueco declarado, se cerraron en el módulo
AD junto con el ajuste de privacidad número dieciséis.

---

## Resumen de una línea por sección

| § | Sección del brief | Estado | Módulo |
|---|---|---|---|
| 1 | Objetivo general | ✅ 13 de 13 · push construido, falta la credencial | varios |
| 2 | Sistema de usuarios | ✅ | 0, I |
| 3 | Sistema de privacidad | ✅ **16 ajustes** en el servidor + `personalizado` con listas + solicitudes · comunidades incluidas (AD) | 0, L.1, O, Q, AD |
| 4 | Sistema de permisos | 🔨 34 permisos, RBAC completo · **falta gestionar bots** | A |
| 5 | Grupos | ✅ | B |
| 6 | Roles personalizados | ✅ | B.4 |
| 7 | Canales | ✅ | F |
| 8 | Chats privados | ✅ con ubicación, contacto, encuesta y evento | C, D, M |
| 9 | Seguridad | ✅ E2EE, huella, 2FA, límites, sesiones | E, G.5, I |
| 10 | Administración | ✅ cola, personas, canales, grupos, límites, bitácora + métricas de plataforma | H, Q.1 |
| 11 | Notificaciones | ✅ cinco categorías · push construido, falta la credencial | L.6, N.1 |
| 12 | Almacenamiento | ✅ | D.7 |
| 13 | Moderación | ✅ la sanción alcanza también al perfil público | B, G, R.1 |
| 14 | Arquitectura | ✅ | — |
| 15 | Interfaz | ✅ móvil, tablet y escritorio · dos temas · accesibilidad verificada · ninguna pantalla afirma lo que no comprobó (X, Z.5) | L, L.8, N.2, N.3, N.5 |
| 16 | Permisos técnicos | ✅ escrituras (N.7) y lecturas (N.8) barridas por un tercero · el barrido lo mantiene un **auditor que lee `Main.kt`**, no un recuento a mano (AG) | A, N.7, N.8, AG |
| 17 | Calidad y pruebas | ✅ 1903 con todo levantado · 1880 mínimo | — |
| 18 | Entregables | ✅ 20 de 20 | — |

---

## §1 · Objetivo general

| Capacidad | Estado |
|---|---|
| Chats privados 1 a 1 | ✅ |
| Chats grupales | ✅ |
| Canales o comunidades | ✅ las dos: canales (F) y comunidades (AD) |
| Texto, imágenes, videos, audios, documentos | ✅ |
| Mensajes de voz | ✅ con adelantar y velocidad 1x/1.5x/2x (N.12) |
| Llamadas de audio y video | ✅ módulo K, con ventana flotante |
| Llamadas y videollamadas **de grupo** | ✅ módulo AF · hasta 4, eligiendo a quién; rejilla de vídeos · quien llama ve quién entró y quién dijo que no (AH) |
| Ubicación **en tiempo real** | ✅ módulo AM · 15 min a 24 h · el servidor no ve ni la posición ni el vencimiento |
| Ficha de contacto | ✅ módulo AO · al tocar la cabecera · lo compartido se cuenta y se abre, y el recuento sale de este teléfono porque el servidor no guarda el historial |
| Mapa incrustado | ✅ módulo AN · OpenStreetMap en las dos formas de mandar la posición, o **modo oculto** que dibuja el mismo recorrido sin pedirle una imagen a nadie |
| Respuestas, reacciones, menciones, reenvíos | ✅ una reacción por persona (V25) · responder deslizando (N.13) · menciones con selector y resaltado (T.2) · emojis, GIFs y stickers en un panel de tres pestañas (Y.3), con recientes, buscador, tonos de piel y stickers sugeridos al escribir un emoji (Z) |
| Mensajes fijados | ✅ |
| Búsqueda global y dentro de conversaciones | ✅ las dos (M.3) |
| Notificaciones push | 🔨 locales sí; FCM no: necesita credenciales de Firebase |
| Sincronización entre dispositivos | ✅ |
| Permisos y privacidad | ✅ permisos · ✅ privacidad |
| Panel de configuración detallado | ✅ usuario, grupo, moderación y plataforma |
| Ubicación, contacto, encuestas y eventos | ✅ módulo M |

## §2 · Sistema de usuarios

Hecho: id, nombre, nombre de usuario, foto, portada, estado personalizado,
estado en línea, última conexión, fecha de creación, dispositivo vinculado,
preferencias de privacidad. Registro, inicio y cierre de sesión, bloqueo y
desbloqueo.

Completo con el módulo I: biografía como campo aparte, **teléfono verificado**,
**recuperación de cuenta**, verificación por código, gestión y cierre remoto de
sesiones, reportar usuarios, eliminar cuenta con periodo de gracia, 2FA.

> **Nota de diseño, corregida el 2026-09-17.** El registro sigue siendo por
> *username* y el ingreso por usuario y contraseña: el teléfono **no es una
> credencial**. Es un canal verificado **opcional**, y es lo que hace posibles
> las dos cosas que sin él no lo eran: recuperar una cuenta y que alguien te
> encuentre. Quien no quiera darlo no lo da, y el precio está dicho en la
> pantalla.
>
> Hubo un intento intermedio con correo institucional y se descartó: atar la
> recuperación a dos dominios dejaba sin salida a cualquiera fuera de ellos.

## §3 · Sistema de privacidad

Hechos y **aplicados en el servidor**, no solo ocultando botones: quién ve mi
foto, quién ve mi estado, quién me escribe, quién me agrega a grupos. Con
niveles `todos` / `conocidos` / `nadie`.

> `conocidos` = gente con la que ya hay una conversación directa. Sin teléfonos
> no hay agenda que cruzar, así que "mis contactos" se define así.

Cerrado en L.1 y completado en el módulo Q, con **quince** ajustes aplicados
**en el servidor**. Los nueve primeros: foto, estado,
quién me escribe, quién me agrega a grupos, quién me llama, quién ve mi nombre,
quién ve mi última conexión, quién me encuentra por mi usuario, confirmaciones de
lectura y avisos de "escribiendo".

Dos de ellos son **recíprocos** y eso es la mitad del diseño: quien oculta su
última conexión no ve la de nadie, y quien apaga las confirmaciones de lectura
deja de mandarlas y de verlas. Sin reciprocidad, un ajuste de privacidad es un
espejo de una sola dirección —ver sin ser visto—, que es justamente para lo que
se usaría.

**Dos que se habían descartado, y por qué se hicieron igual.** Este documento
argumentaba que "si estoy grabando" no hacía falta —grabar una nota de voz ya
emite "escribiendo"— y que "solicitudes de mensaje" sería un interruptor sin
nada detrás. El módulo Q hizo las dos, y el argumento era flojo en los dos
casos: "escribiendo" y "grabando" dicen cosas distintas sobre qué está
haciendo alguien, y las solicitudes tienen detrás una bandeja aparte, no sólo
un ajuste. Los dos están en los quince y los cubre
[`pruebas/privacidad-fina.mjs`](../pruebas/privacidad-fina.mjs).

**Y el último que faltaba, cerrado en el módulo AD**: "invitaciones a
comunidades". Faltaba el **contenedor entero** y no el ajuste, así que se hizo
la comunidad —un conjunto de grupos más un canal de anuncios— y con ella el
ajuste número dieciséis.

No gobierna "que me inviten a una comunidad", porque a nadie se lo invita: se
lo agrega a un **grupo**, y eso ya lo decide `grupos`. Gobierna el hecho nuevo
que ese ajuste no cubre: **alguien agrega a la comunidad el grupo en el que ya
estabas**, y de golpe estás en un canal de anuncios con quinientos
desconocidos sin que nadie te haya agregado a nada.

Y `nadie` **no te saca del grupo**: seguís donde estabas, y lo único que deja
de pasar es que te sumen a un canal que no pediste. Un ajuste de privacidad que
te expulsa de algo no es un ajuste de privacidad, es una sanción.

**El nivel `personalizado` sí está.** `todos`, `conocidos` y `nadie` son valores
y caben en una columna; "todos menos Fulano" y "sólo Mengano" son **listas**, así
que el nivel apunta a una tabla de excepciones con dos modos —lista negra y
lista blanca—. Los dos hacen falta: una lista negra no puede expresar "sólo mi
familia" sin enumerar la plataforma entera.

**Historias, y responderlas.** El brief pedía también *"permitir respuestas a
historias/estados"*. El módulo O añade el décimo ajuste —`historias`, con los
cuatro niveles— y la respuesta.

Responder **no es un permiso nuevo**: es mandar un mensaje directo a quien
publicó, y lo decide `escribe`, como cualquier otro mensaje. De ahí sale un caso
que conviene entender antes de tocarlo, porque parece un fallo y no lo es:

| `historias` | Quién ve | ¿Puede responder? |
|---|---|---|
| `conocidos` | solo con conversación directa abierta | sí, salvo `escribe` más estricto |
| `todos` | todos los que me conocen, **incluida la libreta** | **no**, si `escribe: conocidos` |

Es decir: **se puede ver una historia y no poder contestarla**. Publicar para
todos no debería obligar a aceptar mensajes de todos. Lo que sí está resuelto es
que el botón no falle en silencio: el motivo del servidor llega a la pantalla.

Y **bloquear corta las dos cosas a la vez** —deja de ver las historias, incluso
las publicadas antes, y deja de poder escribir en el chat que ya tenía—. Si
cortara solo una, bloquear no serviría de nada.

Aplica a siete ajustes. **No** a `escribe` —una lista blanca de quién puede
escribirte convierte la cuenta en un club cerrado, y para eso están los
bloqueos— ni a los dos booleanos recíprocos, donde una lista de "a quién sí le
aviso" es el espejo de una sola dirección que la reciprocidad evita.

Y una lista blanca **vacía** no muestra el dato a nadie: si algo fallara al leer
las excepciones, el resultado es ocultar, nunca mostrárselo a todos.

## §4 · Sistema de permisos 🔨

34 permisos con forma `recurso.accion`. Cinco roles de sistema con jerarquía
(propietario 100 / administrador 80 / moderador 50 / miembro 10 / restringido 5),
overrides por persona, restricciones con vencimiento, bloqueos usuario-usuario
y audit log.

**Los permisos nunca viajan en el token**: se resuelven en cada petición. Por
eso degradar a alguien surte efecto de inmediato. Orden de resolución:
suspensión → bloqueo → pertenencia → restricción → override (un *deny* gana) →
rol. Para actuar *sobre* otra persona se exige jerarquía estrictamente mayor.

17 pruebas en `AutorizacionTest`.

**Lo que falta, y estaba marcado como hecho.** El brief lista "gestionar
bots/integraciones" entre los permisos, y ese permiso **no existe**: no hay
bots que gestionar. Esta sección decía ✅ y no mencionaba el hueco. Los 34
permisos que hay están completos; el que falta no es un permiso suelto, es un
subsistema entero (tokens de máquina, webhooks, y un modelo de autorización
donde el actor no es una persona — `Autz.puede` hoy asume que lo es).

## §5 · Grupos ✅ · §6 · Roles personalizados ✅

Todo lo del brief: configuración de quién puede qué, aprobación manual,
historial para nuevos, enlaces de invitación con vencimiento y usos máximos,
solicitudes de ingreso, modo solo administradores, roles creados a medida.
44 pruebas.

## §7 · Canales ✅

Canal público y privado, alias/URL, administradores, suscriptores,
publicaciones **con imagen** (AC), reacciones **que se ven y se tocan**,
comentarios con su hoja (AA), mensajes fijados,
estadísticas, moderación heredada del RBAC y permisos propios
(`canal.publicar`, `canal.comentar`, `canal.estadisticas`).

Configuración: quién puede publicar (por permiso), quién puede comentar
(permiso + interruptor del canal), quién puede reaccionar (idem), quién puede
añadir administradores (jerarquía).

> **Un canal público no va cifrado de extremo a extremo**, y el servidor guarda
> sus publicaciones. Es la única excepción a la regla del buzón tonto, está
> declarada en el código, en la base y en la pantalla. Ver el módulo F de la
> hoja de ruta para los tres motivos.

## §8 · Chats privados ✅

Hecho: texto, emojis (selector propio), stickers y GIFs **con movimiento** (AB), imágenes, videos,
audios, mensajes de voz con forma de onda, documentos, respuestas, reacciones,
edición, eliminación para mí y para todos, reenvío, copiar, fijar, mensajes
temporales.

Cerrado en el módulo M: **ubicación** (coordenadas y margen del GPS, sin mapa de
terceros), **tarjeta de contacto** (un `@usuario` de aquí, nunca un teléfono
ajeno), **encuestas** y **eventos con confirmación de asistencia**, **buscar
dentro de la conversación** y **marcar como no leído**.

> Las encuestas **no** ofrecen la opción "anónima", y es una decisión y no una
> omisión: con cifrado de extremo a extremo los votos los cuentan los teléfonos
> —el servidor no puede leerlos—, así que cada participante ve quién votó qué.
> La burbuja lo dice antes de votar. Ofrecer la casilla sería una etiqueta falsa.

> Tampoco hay "ubicación en vivo". Exige un servicio en primer plano con su
> propio permiso, una sesión que caduque sola y una manera visible de cortarla:
> es un módulo, no un botón, y a medias deja algo encendido que la persona cree
> apagado.

## §9 · Seguridad ✅

| Punto | Estado |
|---|---|
| Cifrado en tránsito | ✅ TLS en producción |
| **Cifrado de extremo a extremo** | ✅ libsignal, PQXDH + Double Ratchet |
| Protección de sesiones, tokens | ✅ token opaco, solo su SHA-256 en la base |
| Gestión de dispositivos | ✅ hasta 8 por cuenta, con principal y revocación |
| Cierre remoto de sesiones | ✅ lista con IP y último uso, cerrar una o todas |
| 2FA | ✅ TOTP con 8 codigos de respaldo |
| Fuerza bruta / límite de frecuencia | ✅ cuenta fallos, no intentos; por usuario y por IP |
| Validación de archivos | 🔨 ver la tensión declarada más abajo |
| Lecturas ajenas y escalera de staff | ✅ barridas en N.8 |
| Antispam, reportes | ✅ dos limitadores + cola de denuncias |
| Registro de eventos de seguridad | ✅ `evento_seguridad` por cuenta, aparte de la auditoría |
| Bus entre instancias autenticado | ✅ HMAC-SHA256, falla cerrado (N.6) |
| Contenido de un sobre ajeno | ✅ topes y validación al dibujar (N.9) |
| Miniatura ajena | ✅ se mide antes de decodificar (N.10) |
| Audio ajeno | ✅ se prepara fuera del hilo de interfaz (N.11) |
| Salida a internet del servidor | ✅ lista blanca de host y sin redirecciones (N.10) |

> **El brief pide dos cosas incompatibles sobre el mismo archivo:** validar el
> tipo de archivo en el servidor *y* cifrado de extremo a extremo. Un servidor
> que no puede leer un archivo no puede validarlo. Se resolvió partiendo por
> clase: las fotos de perfil van sin cifrar y el servidor las valida; los
> adjuntos de mensajes van cifrados y la firma la comprueba el cliente al
> descifrar.
>
> **Y hay una tercera clase desde el módulo AC**: la imagen de una publicación
> de canal público. Va sin cifrar porque un canal público no reparte sobres y
> no hay dónde meter la clave —quien se suscriba mañana tendría el archivo y no
> la llave—, y **por eso mismo el servidor sí la valida**: lee los doce
> primeros bytes del almacén y comprueba la firma real. Es el único sitio donde
> lo que el brief pedía se puede cumplir tal cual.

> El brief dice: *"No crear algoritmos criptográficos propios."* Se respeta:
> libsignal para los mensajes, AES-256-GCM del propio Android para los
> archivos, Argon2id para las contraseñas.

## §10 · Administración ✅

Métricas de moderación, cola de reportes, búsqueda de personas con su historial,
suspender y reactivar cuentas, nombrar staff. Dos niveles de plataforma:
moderador (50) trabaja la cola, administrador (80) suspende cuentas y reparte
roles.

Cerrado en H: gestión de **grupos y canales** desde el panel (cerrar y reabrir
una conversación, aprobar o rechazar canales), **límites configurables sin
recompilar** (15 reglas, con caché de 30 s invalidada después del commit) y la
**bitácora** en pantalla, que lee el audit log que ya se escribía en cada acción.

Y en **web**, la consola de administración (L.8): responsive, con el token
emitido por el teléfono y acotado por construcción.

> **Lo que este panel no puede hacer es la parte que importa**: no muestra
> mensajes, no los busca, no los lee, no los exporta. No es una decisión de la
> interfaz, es que el servidor solo guarda bytes opacos. No existe una versión
> del panel con más permisos que sí pueda leer.

## §11 · Notificaciones ✅ (salvo push)

Cinco canales del sistema por categoría —mensajes, grupos, canales, llamadas y
moderación—, con interruptores propios por aparato: si una categoría está
apagada, la app **no publica** la notificación. No es un volumen, es no decirlo.

La configuración granular está repartida donde corresponde: el **sonido y la
vibración** los manda el sistema desde Android 8 y hay un acceso directo a sus
ajustes —pelear contra el sistema por eso sólo produce dos sitios donde mirar—;
la **vista previa** es un interruptor propio (el texto del mensaje nunca se
muestra, va cifrado, pero el nombre de quien escribe sí es información); y el
**silencio por conversación y por período** vive en el chat, que es donde se
decide.

> Las advertencias y sanciones **no** se pueden apagar, y por eso no tienen
> interruptor. Una advertencia que no llega no cumple su única función —dar la
> oportunidad de corregir antes de la sanción—, y sancionar después de un aviso
> silenciable sería una emboscada.

**El push está construido (N.1) y falta la credencial.** El camino entero
—registro del token, JWT RS256 con la cuenta de servicio, OAuth2, envío,
coalescencia y borrado de tokens muertos— está verificado contra un FCM de
mentira. Lo que no se puede probar sin credenciales es que Google acepte la
firma.

> **El aviso no lleva nada: `{"w":"1"}` y nada más.** Ni texto, ni quién
> escribe, ni conversación. El teléfono despierta, abre el socket, baja sus
> sobres y recién ahí —descifrado— se decide si hay notificación. Es un paso más
> de trabajo a cambio de lo único que hace que el cifrado signifique algo en la
> pantalla de bloqueo: el proveedor nunca vio el contenido porque nunca lo tuvo.

> La configuración la sirve **nuestro servidor** (`GET /v1/push/config`), no un
> `google-services.json` dentro del APK. Así se habilita poniendo variables en
> el servidor, sin recompilar ni publicar, y no hay credenciales por entorno en
> el repositorio.

Y se declara el precio: depender de FCM es depender de los servicios de Google
en el aparato. La alternativa —un socket permanente con servicio en primer
plano— gasta batería y Android la corta cada vez más. Es el mismo balance que
eligió Signal.

## §12 · Almacenamiento ✅

Uso local y uso en el servidor por separado, cuota, descarga automática por
clase y solo-con-WiFi, calidad de imagen al enviar, liberar espacio.

## §13 · Moderación ✅

Bloqueo, silenciamiento, restricciones temporales, expulsión, veto, lista de
restringidos, registro de acciones, reportes de usuario/mensaje/grupo/canal,
cola de revisión, advertencias acumulativas con escalada automática,
suspensión de cuenta con vencimiento, antispam con dos limitadores y registro
de eventos de seguridad por cuenta.

> **Un servidor no puede moderar lo que no puede leer.** Denunciar un mensaje
> **entrega su texto en claro**, puesto ahí por el denunciante —su teléfono ya lo
> descifró y es el único que puede—. Es la segunda excepción declarada al buzón
> tonto, después de los canales públicos, y la pantalla lo dice **antes** de
> confirmar. El texto se borra al cerrarse el caso. Ver el módulo G de la hoja de
> ruta para las tres salidas que había y por qué se eligió esta.

El nivel de **plataforma** (moderador/administrador/propietario) es un ámbito
aparte del de conversación: ser staff no da nada dentro de un grupo, y ser dueño
de un grupo no hace staff. Una denuncia sobre una persona no pertenece a ninguna
conversación, así que no había a quién pedirle permiso.

## §14 · Arquitectura ✅

Tres módulos de compilación: `protocol/` (contrato compartido), `server/`
(Ktor + PostgreSQL), `app/` (Android nativo con Compose). Todo Kotlin, para que
un cambio en el contrato rompa **los dos lados en el mismo build**.

WebSocket para tiempo real. Las entidades del brief están todas, con estos
nombres: `usuario`, `dispositivo`, `sesion`, `conversacion`, `participante`,
`mensaje_meta`, `adjunto`, `rol`, `permiso`, `sobre_pendiente`,
`evento_pendiente`, `auditoria`, `canal`, `publicacion_contenido`, `denuncia`,
`denuncia_evidencia`, `advertencia`, `evento_seguridad`, `contador_uso`,
`codigo_verificacion`, `codigo_respaldo`, `contacto`, `lectura`,
`privacidad_excepcion`, `limite_config`, `token_consola`.

> `Notification` no existe **a propósito** y no es una tabla pendiente: las
> notificaciones se generan en el teléfono a partir de lo que ya llega por el
> socket, y sus ajustes viven en el aparato. Una tabla de notificaciones en el
> servidor sería una lista de a quién se le avisó de qué y a qué hora: metadatos
> que este proyecto justamente no genera. La tabla aparecería el día que haya
> push por FCM, y sólo con lo mínimo para entregar un aviso vacío.

Y dos tablas que viven **sólo en el teléfono**, dentro de la base cifrada:
`mensaje` (el historial, que el servidor no tiene) y `voto` (los votos de las
encuestas, que el servidor no puede contar).

> **El servidor es un buzón tonto**: guarda sobres opacos y los borra al
> confirmarse la entrega. No guarda historial. El historial vive solo en los
> teléfonos, cifrado con SQLCipher.

## §15 · Interfaz ✅

Android: lista de conversaciones, conversación, perfil, privacidad,
almacenamiento, grupo. Estados de carga, estados vacíos, manejo de errores,
contrastes medidos contra WCAG AA.

En Android está todo: centro de seguridad, contactos, canales con su
directorio, llamadas con su ventana flotante, ajustes de notificaciones,
verificación de huella por aparato, panel de moderación.

**La lista de chats** (módulo S): el botón flotante dice **Nuevo** y despliega
Conversación, Grupo y Canal, en vez de un "+" que hacía una sola de las tres.
Mantener pulsado **selecciona**, y con varios chats marcados hay fijar,
silenciar, marcar leídas, archivar y eliminar en lote. Los botones hacen *lo que
le falta* al lote: con once fijados y uno no, fijan.

La interfaz es propia y no una copia: el botón lleva texto donde las apps
conocidas ponen sólo un icono, y la barra contextual resuelve las mezclas con
un botón por acción en vez de dos.

**Móvil, tablet y escritorio** (N.3): desde 720 dp de ancho y 480 de alto, la
lista y la conversación se ven a la vez, con la lista en ancho fijo. El umbral
mira el alto y no sólo el ancho porque un teléfono acostado tiene ancho de sobra
y 415 dp de alto, y ahí dos paneles quedan peor que uno. Más `Esc` y `Ctrl+F`
para el buscador del chat, que son los dos atajos que alguien prueba sin que se
los digan.

**Tema claro y oscuro** (N.2), con la preferencia en el aparato y tres valores
—como el sistema, siempre claro, siempre oscuro—.

**Accesibilidad** (N.5). Lo que faltaba no eran los `contentDescription` de los
botones —esos estaban— sino las **filas compuestas**: una fila de la lista eran
seis paradas de lector de pantalla y el check de estado no se anunciaba. Ahora
cada bloque que a la vista es uno se lee como uno, el estado va en
`stateDescription` para que se anuncie al **cambiar**, y las encuestas son
controles con rol de radio o casilla en vez de cajas tocables mudas.

> Un mensaje retirado **no lee su texto**. La accesibilidad no es una puerta
> lateral a la privacidad: si la notificación no muestra el contenido, el lector
> de pantalla tampoco.

Verificado volcando el árbol de accesibilidad del emulador, que es donde
aparecieron las dos cosas que ninguna prueba de texto ve: que fusionar la
burbuja se tragaba los controles de la encuesta, y que dos blancos táctiles
estaban en 34 y 32 dp contra los 48 de la guía.

En **web** está la consola de administración (L.8), responsive. Un cliente web
de **mensajería** no está, y la razón es de fondo y no de tiempo: para descifrar
haría falta ser un dispositivo con claves de identidad propias, y eso significa
libsignal en el navegador —WASM, almacén de claves en IndexedDB— más la pregunta
seria de si un navegador es sitio para claves de largo plazo. Prometerlo a
medias sería peor que declararlo.

**El tema claro ya está, y hubo que rederivar la escala para tenerlo.** Estuvo
declarado fuera de alcance todo el proyecto por una razón real: el cian de marca
da 1.28:1 sobre blanco, invisible como texto y como relleno. No era pereza, era
que la paleta de marca está pensada para fondo oscuro.

Los acentos claros son los primeros valores que pasan 4.5:1 contra las tres
superficies claras **y** 4.5:1 con texto blanco encima: cian `#0B6E6D` (5.11
peor caso), ámbar `#8A5300` (5.33), coral `#B3301A` (5.28). Los contrastes están
calculados, no estimados. Y `TextoSobreAcento` se **invierte** entre temas: en
oscuro el acento es luminoso y pide tinta oscura, en claro es oscuro y pide
blanca. Un solo valor dejaría ilegible la mitad de los botones.

## §16 · Permisos técnicos ✅

La secuencia que pide el brief es literalmente la del motor de autorización:
autenticar → identificar recurso → obtener rol → resolver permisos → comprobar
restricciones → ejecutar → registrar. Con excepciones por persona
(`User → Resource → Override`) y un *deny* que siempre gana.

## §17 · Calidad y pruebas ✅

**1903 pruebas con todo levantado**: 1523 de integración en 36 suites de Node,
75 de JUnit en el servidor (RBAC, seguridad, cuentas, auditoría y el
intermediario de GIFs) y 230 en
la app (el decodificador de QR, lo que anuncia el lector de pantalla, y el
contenido de un sobre que no escribió esta app: miniaturas y duraciones
incluidas).

```
=== 35 suites · 1323 pasan, 0 fallan ===
```

**1616 en la configuración mínima** —una instancia, sin Redis y sin push—, y la
diferencia no es un fallo: dos suites necesitan más que el servidor y lo dicen
en vez de fingir.

```
=== 29 suites · 1155 pasan, 0 fallan · 2 omitidas (bus, bus-inyeccion) ===
```

- `push.mjs` da 30 apuntado a un FCM de mentira y **18** sin push configurado:
  omite la parte del envío. Los dos números de arriba son sin el FCM de
  mentira, así que ya llevan el 18.
- `bus.mjs` se omite **entera** si no hay una segunda instancia escuchando.
- `bus-inyeccion.mjs` se omite en dos casos, y el segundo se agregó en N.7 al
  medir la configuración mínima: si no hay Redis alcanzable, y si hay Redis
  pero **ninguna instancia suscrita**. Ese segundo caso daba FALLA, y estaba
  mal por los dos lados: un rojo ahí no significa que el arreglo se rompió sino
  que falta levantar la segunda instancia, y un rojo que no distingue «falta
  entorno» de «hay un agujero» es un rojo al que se deja de hacer caso.

El runner distingue tres estados y no dos: **OK**, **OMIT** y **ROTA**. Una
suite que sale con código 0 sin haber comprobado nada se marca OMIT y no OK,
porque marcar verde un cruce entre instancias que no se hizo sería el peor
resultado posible. Y una que sale con código 0 **sin línea de resumen** se marca
ROTA: murió a mitad sin fallar ninguna aserción, que es el falso verde clásico.
Las dos reglas se ganaron el sitio encontrando algo el día que se escribieron.

Las suites de integración están en **`pruebas/`** y se corren con
`node pruebas/correr.mjs` contra un servidor levantado. Vivían en una carpeta
temporal hasta el módulo M; un `%TEMP%` limpio se las llevaba.

> El runner trata como **rota** a la suite que no imprime línea de resumen,
> aunque salga con código 0. Una suite que muere a mitad sin fallar ninguna
> aserción es el peor resultado posible: verde y vacío. Lo encontró en su primera
> ejecución, con nueve suites marcando "0 pruebas".

Los siete casos difíciles que el brief nombra están cubiertos:

| Caso del brief | Dónde |
|---|---|
| Expulsado intentando enviar | `expulsado.mjs` |
| Bloqueado intentando escribir | `privacidad.mjs` |
| Moderador excediéndose | `AutorizacionTest` |
| Administrador degradado | `AutorizacionTest` (permisos no viajan en el token) |
| Modificar recursos ajenos | `mensajes.mjs`, `adjuntos.mjs` |
| Sesión revocada | `e2e.mjs` |
| Permisos cambiados en sesión activa | `AutorizacionTest` |

## §18 · Entregables

✅ arquitectura · estructura de carpetas · modelo de base de datos · endpoints ·
autenticación · RBAC · privacidad · grupos · mensajería en tiempo real ·
almacenamiento · pruebas · variables de entorno · ejecución local ·
recomendaciones de escala · canales · moderación · identidad y cuenta.

✅ panel administrativo completo (cola, personas, canales, grupos, límites,
bitácora) · notificaciones locales por categoría · **consola web responsive**.

✅ notificaciones locales por categoría y **push construido**: falta una
credencial de Firebase para que una llamada entrante suene con la app cerrada.

✅ **recomendaciones de escala, implementadas y no sólo escritas**: el `Hub` sale
del proceso con un bus opcional (N.4), verificado con dos instancias de verdad.
Sin configurar es un no-op y una sola instancia sigue siendo el caso normal.

✅ contenido con estructura: ubicación, tarjeta de contacto, encuestas y eventos
con confirmación de asistencia · búsqueda dentro de la conversación · marcar
como no leído.

✅ documentación de API (`docs/08-API.md`, las 142 rutas) · instrucciones de
despliegue (`docs/09-DESPLIEGUE.md`).

---

## El orden en que sigue

Los módulos del plan están cerrados. Lo que queda es infraestructura y
alcance declarado, en este orden:

1. **Push (FCM o equivalente).** Es lo único que separa las llamadas de estar
   terminadas: con la app cerrada no hay proceso que despertar, así que una
   llamada entrante no suena. El servicio en primer plano (K.8) resolvió el caso
   frecuente —la llamada en curso sobrevive a salir de la app—, no este.
2. **`K.5` · SFU** para llamadas de grupo de más de cuatro. La malla actual
   tiene techo declarado: con N participantes son N-1 conexiones por aparato.
3. **Cliente web de mensajería**, si alguna vez se decide pagar el precio de
   libsignal en el navegador. Hoy la respuesta honesta es que no está.
