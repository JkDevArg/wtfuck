# Tanda A–C y 1 a 9: tres arreglos y nueve funciones

Sigue a la tanda 8 a 18. Los puntos A a C son defectos encontrados al usar la
app; los puntos 1 a 9 son funciones que tienen WhatsApp, Telegram o Signal y
que faltaban aquí.

Cada punto se probó en los dos emuladores (5554 = `xampl3`, 5556 =
`goblin2026`). Si tocaba el servidor, se probó también en la suite de
integración. Las capturas (`*.png`) no se suben al repositorio; están en esta
carpeta del equipo de desarrollo.

Lo que guarda la base local:

- Room pasa de la versión 29 a la 33.
- Todo es **solo de este teléfono**: estrella, spoiler recibido, recordatorios y
  fondo.
- El servidor solo cambió por el punto B (migración V47).

## A. Los chats archivados se abrían vacíos

`ChatPantalla` buscaba el chat en la misma lista que la pantalla principal, y
esa lista **excluye los archivados**. Para un chat archivado, `chat` quedaba en
`null`: el chat se abría sin título y sin menú. Ahora la
pantalla lee `todasLasConversaciones` (archivados incluidos). La lista
principal sigue igual.

Evidencia: `A-chat-archivado-completo.png` (el chat archivado "probador", con
título y menú).

## B. "Ver una vez" no avisaba a quien la mandó

Quien enviaba una foto de "ver una vez" nunca sabía si la habían abierto, y en
mis otros aparatos la foto seguía "sin ver" aunque la hubiera abierto en el
teléfono.

**Servidor:**

- Nueva ruta: `POST /mensajes/{id}/abierto`, que responde 204.
- Nuevo tipo de evento: `una_vez_abierta` (migración V47, que rehace el CHECK
  de tipos de `evento_pendiente`).
- Para pedirla hay que ser miembro de la conversación (`MIEMBRO_VER`).
- Al autor del mensaje le responde 400: abrir lo propio no es "abrirlo".
- A un mensaje que no existe le responde 404.

**Quién recibe el aviso:**

| Destinatario | ¿Recibe el aviso? | Por qué |
|---|---|---|
| Mis otros aparatos | Siempre | Son míos: allí la foto se da por vista y se borra. |
| Quien la mandó | Solo si **los dos** tenemos las confirmaciones de lectura encendidas | Es la misma regla de reciprocidad de los checks azules: si yo no muestro que leo, no se lo digo a nadie. |

**App:** la burbuja de quien envía muestra "Foto · ver una vez · abierta".

**Pruebas:** `pruebas/unavez.mjs` (11 casos) cubre:

- el autor recibe el aviso;
- una tablet vinculada recibe el aviso;
- con la lectura apagada en cualquiera de los dos lados, el autor no recibe
  nada;
- el 400 y el 404.

`ajeno.mjs` exige cubrir toda ruta nueva y ya incluye esta. Su control usa
`quien: (w) => w.socio`, porque el dueño recibe 400 y no sirve de control.

Evidencia: `B-ver-una-vez-abierta.png`.

## C. Los mensajes atascados no se podían ver ni descartar

La barra "Por enviar · N" contaba los mensajes que no salían, pero no decía
cuáles eran ni por qué, y no había forma de quitarlos.

- Ahora la barra dice "· toca para ver" y abre una hoja con cada mensaje.
- Cada mensaje trae su motivo, por ejemplo "Todavía no hay cifrado con…" o el
  error del servidor.
- Cada uno tiene "Descartar".
- Los motivos se anotan en los tres lugares donde un envío puede trabarse.

Evidencia: `C-por-enviar.png` (dos atascados, con su motivo) y
`C-sin-barra-tras-descartar.png` (descartados los dos, la barra desaparece).

**Defecto encontrado al probar.** La hoja se cerraba en el mismo instante en
que se abría. La lista empezaba como `emptyList()` mientras cargaba, y la regla
"si no queda ninguno, cerrar" se disparaba con esa lista inicial. Ahora el
valor inicial es `null`: la hoja se cierra solo cuando la lista **cargada**
está vacía.

## 1. Seleccionar varios mensajes

Mantener pulsado un mensaje y tocar "Seleccionar" activa el modo selección; a
partir de ahí, cada toque marca o desmarca un mensaje. La barra de arriba
permite:

- **Copiar:** con hora y autor, en el orden en que se escribieron.
- **Reenviar:** van en orden cronológico, no en el orden en que se tocaron.
- **Destacar.**
- **Borrar:** con confirmación.

"Atrás" sale del modo selección.

Mientras se selecciona, los toques se capturan antes de que lleguen a la
burbuja (`PointerEventPass.Initial`). Así, tocar un enlace o una foto la marca
en vez de abrirla.

Evidencia: `1-seleccion.png` (tres marcados) y `1-reenviados-en-orden.png` (dos
reenviados a "Nota para mí", que llegaron en orden).

## 2. Mensajes destacados

La estrella es una columna local (`mensaje.destacado`, Room 30); no viaja a
ningún lado. Hay dos listas:

- **Los de un chat:** en el menú del chat.
- **Los de todos los chats:** en el ⋮ de la lista de chats. **No incluye los
  chats protegidos**, igual que el buscador.

Tocar un destacado lleva al chat, en ese mensaje.

Evidencia: `2-destacados-del-chat.png` y `2-destacados-de-todos.png`.

## 3. Responder en privado

En un mensaje de grupo, "Responder en privado" abre mi chat directo con el
autor (o lo crea) con el mensaje ya citado.

**Defecto encontrado al probar.** El servidor rechazaba la respuesta con "El
mensaje al que respondes no está en esta conversación", y tenía razón: el
`respondeA` apuntaba a un mensaje del grupo. Ahora:

- el id solo viaja si el mensaje citado es de la **misma** conversación;
- la cita se dibuja con `respondeTexto`, que sí viaja siempre, así que la
  respuesta privada se ve igual.

El mensaje fallido de la prueba se borró con "Eliminar para mí".

Evidencia: `3-defecto-cita-de-otro-chat.png` (el error, antes del arreglo) y
`3-respuesta-privada-recibida.png` (llega a goblin2026 con la cita
"@goblin2026 · alguien sabe la hora de la reunion").

## 4. Varias fotos y videos a la vez

Adjuntar → Galería usa ahora el selector múltiple del sistema
(`PickMultipleVisualMedia`, hasta 10). Se abre una hoja "N para enviar" con un
pie.

- Cada foto o video sale como un mensaje aparte, igual que en WhatsApp.
- El pie va solo en el primero.

Evidencia: `4-varias-fotos.png` (la hoja) y `4-enviadas.png`.

## 5. Notas de voz encadenadas

Cuando termina una nota de voz, suena sola la siguiente **si el mensaje que
sigue en el chat es otra nota de voz**. No se encadena si:

- el siguiente mensaje no es una nota de voz;
- es de "ver una vez";
- fue retirado;
- todavía no está descargado.

Evidencia: `5-segunda-nota-sonando.png`.

## 6. Fotos y videos spoiler

`CargaAdjunto.spoiler` es un campo nuevo del protocolo. Un cliente viejo lo
ignora y muestra la foto como siempre: no rompe nada.

- **Quien recibe:** ve la foto difuminada (`blur(32.dp)`) con "Spoiler · toca
  para ver" y la destapa con un toque.
- **Antes de Android 12:** `blur` no hace nada, así que allí la foto se tapa
  entera con un fondo opaco.
- **Quien envía:** no la ve tapada.

Dónde se activa:

- **En el editor de fotos.** El botón se oculta con "ver una vez", que ya va
  tapada.
- **En la hoja de varias fotos.**

Al reenviar, el spoiler se conserva.

Evidencia: `6-spoiler-tapado.png` y `6-spoiler-destapado.png`.

## 7. Recordarme este mensaje

Mantener pulsado un mensaje y tocar "Recordarme…" abre el mismo selector de
momento que los programados (atajos y "otra fecha y hora").

**Cómo se guarda y se dispara:**

- Se guarda en la tabla `recordatorio` (Room 32).
- Comparte la alarma de los programados (`setAndAllowWhileIdle`) y su respaldo
  de WorkManager: una sola alarma, despertada por el más próximo de los dos.

**Lo que se ve:**

- La burbuja muestra una campana mientras el recordatorio está pendiente.
- A la hora elegida llega "Recordatorio · chat" con el resumen del mensaje, en
  el canal nuevo "Recordatorios".
- Tocar la notificación abre el chat **en ese mensaje**.
- De un chat protegido solo dice "Un mensaje de un chat protegido".

**Prueba:** puse el recordatorio para las 00:53. `dumpsys alarm` mostró la
alarma con `origWhen=00:53:00 window=+1m31s`. Es inexacta a propósito, como la
de los programados: la exacta pide un permiso que Android 14 ya no da por
defecto, y un recordatorio que llega un minuto tarde no justifica mandar a la
persona a los ajustes del sistema. La notificación llegó a
las 00:54:35. Al tocarla se abrió el chat en la foto "dos de una", ya sin
campana.

Evidencia: `7-recordatorio-notificacion.png` y `7-recordatorio-abre-chat.png`.

## 8. Un sonido propio para un chat

Android no deja que una app ponga sonido a una notificación suelta: el sonido
es de un **canal**, y solo la persona lo cambia, en los ajustes del sistema.
Por eso funciona así:

- En el menú del chat, "Sonido de este chat" crea un canal `chat-<id>` llamado
  "Chat: <nombre>" y abre su ajuste en Android, donde se elige el sonido.
- Los mensajes de ese chat salen por su canal mientras exista.
- "Volver al de siempre" borra el canal.

**Privacidad.** El nombre del canal se ve en los ajustes de Android, así que:

- de un chat protegido el canal se llama "Chat protegido";
- los canales se borran al borrar el chat, al vencer un chat temporal, al cerrar
  sesión y si entra otra cuenta en el aparato. Sus nombres dicen con quién
  hablaba esta cuenta;
- al proteger un chat que ya tiene su sonido, el canal pasa a llamarse "Chat
  protegido", y al desprotegerlo recupera el nombre. Crear un canal que ya
  existe solo le cambia el nombre: el sonido elegido se queda.

**Prueba:**

1. Elegí "Opal Bell". El canal quedó con
   `mSound=…title=Opal%20Bell`, y el general sigue con el sonido por defecto.
2. Mandé "suena distinto" desde goblin2026. La notificación llegó con
   `channel=chat-01a0edab-…` y fue la que sonó (`mSoundNotificationKey`).
3. Toqué "Volver al de siempre". El canal quedó `mDeleted=true`, y el siguiente
   mensaje llegó por `channel=mensajes`.
4. Volví a elegir un sonido. Android **restauró** el canal borrado con
   "Opal Bell": recrear un canal con el mismo id le devuelve los ajustes que
   tenía. Es comportamiento del sistema y aquí juega a favor: quien vuelve a
   ponerle sonido propio a un chat recupera el que había elegido.

**Lo que no se pudo probar en el emulador:** el cambio de nombre al proteger.
Proteger exige una huella o un PIN en el teléfono, el emulador no tiene ninguno
("Primero configura una huella o un PIN en el teléfono") y no se tocan sus
ajustes de seguridad. El código es una llamada del sistema documentada; queda
para probar en un teléfono real.

Evidencia, en orden: `8-sonido-dialogo.png`, `8-sonido-ajustes-android.png`,
`8-sonido-canal-opal-bell.png`, `8-sonido-menu-propio.png`,
`8-sonido-notificacion-canal-propio.png` y `8-sonido-dialogo-propio.png`.

## 9. Un fondo propio para un chat

En el menú del chat, "Fondo de este chat" ofrece "El de todos los chats" más
los mismos fondos del ajuste general.

- Se guarda en la columna local `conversacion.fondo` (Room 33).
- Vacío significa "el general".

`guardarConversacion` es un REPLACE. Por eso `guardarResumen` copia la columna
de la fila anterior, igual que el borrador y el protegido. Sin eso, la
primera sincronización borraría el fondo.

**Prueba:** medí el color de un mismo píxel del fondo en cada captura.

| Caso | Píxel |
|---|---|
| goblin2026 con "Con tu color" | `(27,51,50)` |
| "Nota para mí", con el general | `(7,10,10)` |
| goblin2026 tras forzar el cierre y volver a abrir, que sincroniza | `(27,51,50)` |

Evidencia: `9-fondo-elegir.png`, `9-fondo-propio.png`,
`9-fondo-otro-chat-general.png` y `9-fondo-tras-reinicio.png`.

## Otros defectos encontrados en el camino

- **`temporales.mjs` fallaba a veces** ("quedan participantes antes de
  barrer"). Era una carrera con el barrido del servidor, que corre cada
  10 s: la prueba adelantaba el reloj y recién después contaba participantes.
  Si el barrido pasaba entre medio, no quedaba nada que contar. Ahora cuenta
  antes de mover el reloj.
- **La suite decía "0 fallan" con una suite rota.** En la corrida completa,
  `recuperacion` falló y murió a mitad. El runner la marcó "ROTA" y salió con
  código 1, pero la línea final decía "44 suites · 1706 pasan, 0 fallan", que es
  la única que se lee. Ahora esa línea también cuenta las suites con problemas.
- **Por qué cayó `recuperacion`: el límite del SMS.** El servidor respondía 429
  ("Vas muy rápido") al pedir el código. El runner borra los límites al
  empezar, pero solo los de **fallo**, que viven en Redis. Los de
  **frecuencia**, como el del SMS, viven en la memoria del servidor, y este
  llevaba horas encendido con corridas anteriores. No es un defecto de la app
  ni del servidor: hay que reiniciar el servidor o esperar la ventana. El
  runner ahora lo avisa cuando algo falla.

## Para probar en los emuladores

Los emuladores tienen instalada la 0.6.3 (versionCode 16). Un
`assembleDebug` sin parámetros sale con versionCode 1 y Android lo rechaza
(`INSTALL_FAILED_VERSION_DOWNGRADE`). Hay que compilar así:

```bash
./gradlew :app:assembleDebug -PversionCode=16 -PversionName=0.6.3
```

## Resultado

- Unitarias de la app: 620, sin fallos.
- Integración:
  - corrida completa: 43 de 44 suites bien, con 1706 pruebas que pasan;
  - `recuperacion`, repetida sola tras esperar la ventana del límite: 40 de 40;
  - en total, 1746 pruebas que pasan y 0 que fallan, las mismas que antes de la
    tanda.
- Novedades: los cambios van en la entrada de la 0.6.3, que todavía no se
  publicó (el marcador sigue en 15 = 0.6.2).
- **Hay que redesplegar el servidor** por la V47 antes de publicar el APK: sin
  la ruta, "ver una vez · abierta" simplemente no avisa.
