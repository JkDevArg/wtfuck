# Historial de cambios

Lo que cambió en cada versión de wtfuck, contado para quien usa la app.

> Este archivo **se genera**: no se edita a mano. Sale de la pantalla de
> Novedades de la app (`app/src/main/java/com/wtfuck/app/ui/NovedadesPantalla.kt`)
> con `python despliegue/changelog.py`. Las notas "Para desplegar" salen de
> `despliegue/notas/<versión>.md`. El detalle técnico de cada cambio está en
> el historial de git y en `docs/evidencias/`.

## 0.6.3 · 2026-10-05

### Nuevo

- Nota para mí: un chat solo tuyo para apuntar y reenviarte cosas. Aparece en todos tus aparatos y va cifrada como cualquier chat. Está en Nuevo.
- Reenviar ahora te deja elegir a qué chats (hasta 5), y las fotos y archivos se reenvían enteros.
- Responder y marcar como leído desde la notificación.
- Formato de texto: *negrita*, _cursiva_, ~tachado~, `código` y ||spoiler||, que se toca para verlo.
- Vista previa de enlaces, armada por tu teléfono: quien recibe no visita nada y el servidor no sabe qué enlace mandaste. Se apaga en Privacidad.
- Ver una vez para fotos y videos: se abren una sola vez, con las capturas de pantalla bloqueadas, y se borran al cerrarlas.
- Mensajes programados: mantén pulsado el botón de enviar y elige la hora. Salen desde tu teléfono.
- Enviar sin sonido, en el mismo menú: le llega, pero su teléfono no suena.
- Proteger un chat con tu huella o tu PIN: no se ve en la lista, ni en el buscador, ni en las notificaciones.
- Carpetas de chats, como pestañas propias. Viven solo en tu teléfono.
- En un grupo, "Info" en tus mensajes te dice quién lo recibió y quién lo leyó.
- Videonotas: mensajes de video en un círculo, desde Adjuntar.
- Transcribir notas de voz y traducir mensajes, siempre en tu teléfono y nunca en internet. Depende de que tu teléfono lo sepa hacer sin conexión.
- Compartir a wtfuck desde otras apps, tus chats recientes en el menú de compartir y un widget con los mensajes sin leer.
- Proxy SOCKS5 o HTTP para las redes que bloquean la app, en Privacidad.
- @todos en los grupos, para quien puede fijar mensajes. Una mención te avisa aunque el grupo esté silenciado.
- Seleccionar varios mensajes a la vez: mantén pulsado uno y toca los demás para copiarlos, reenviarlos, destacarlos o borrarlos juntos.
- Mensajes destacados con una estrella: los de un chat en su menú, y los de todos en el menú de la lista.
- Responder en privado a un mensaje de un grupo: se abre tu chat con esa persona, con el mensaje citado.
- Varias fotos y videos de una vez, hasta 10, con un solo pie.
- Fotos y videos como spoiler: llegan difuminados hasta que los tocan.
- Recordarme este mensaje: eliges cuándo y, a esa hora, una notificación te lleva a él.
- Un sonido y un fondo propios para cada chat, en el menú del chat. Viven solo en tu teléfono.
- Copia de seguridad automática: cada día o cada semana, cifrada con tu frase, en la carpeta que elijas. Se guardan las dos últimas.
- Tu enlace y QR para que te escriban sin buscarte, en Perfil. Lo cambias o lo apagas cuando quieras, y el de otros se escanea desde Nuevo.
- Listas de difusión: un mensaje a varias personas, cada una en su chat contigo y sin ver a quién más le llegó. En el menú de la lista de chats.

### Mejoras

- Modo cerca: ahora sí manda mensajes sin internet a quien tengas al lado, por Bluetooth (antes solo recibía). Empareja los teléfonos una vez; sigue funcionando con la pantalla apagada y te dice cuáles llegaron así.
- Las fotos del chat pasan por el editor: recorte, giro, filtros, textos y stickers. Si no tocas nada, sale la original.
- Lo que dejas escrito sin enviar se guarda por chat, y la lista te lo recuerda.
- Teclado incógnito, encendido: le pide al teclado que no aprenda lo que escribes.
- Las notas de voz seguidas se escuchan una tras otra, sin tocar cada una.
- Ver una vez: quien la mandó ve "abierta" cuando la abres, si los dos tienen activadas las confirmaciones de lectura. Y en tus otros aparatos también se da por vista.
- Los mensajes que no logran salir se pueden ver, y descartar, tocando la barra "Por enviar".

### Seguridad

- Antes de abrir un enlace de un mensaje, la app pregunta y te dice a qué sitio lleva de verdad, con un aviso si intenta disfrazarse.

### Correcciones

- Tocar una notificación abre el chat, y no la lista.
- Los borradores se perdían cada vez que abrías la app. Ya no.
- Mantener pulsado un enlace o una foto abre el menú del mensaje, en vez de abrir el enlace o la foto.
- Lo que escribes en otro de tus aparatos se ve como tuyo, y ya no te llega como una notificación.
- Ya no aparece un aviso de "fallo" cuando el teléfono cierra wtfuck en segundo plano para liberar memoria, como hacen Honor y Huawei. No era un fallo.
- Los chats archivados se abrían vacíos. Ya no.
- Si mandabas muchos mensajes muy rápido, algunos quedaban como fallidos. Ahora esperan un momento y salen solos.

### Para desplegar

- **Publicar con el servidor:** `bash despliegue/lanzar.sh 0.6.3 --con-servidor`.
  Esta versión trae cambios del servidor que la app necesita.
- **Migraciones del servidor:** se aplican solas al arrancar.
  - **V47:** aviso de "ver una vez · abierta".
  - **V48:** enlace de contacto.
- **El cupo de mensajes se aplica de verdad:** 30 por minuto por persona.
  Estaba declarado y ajustable desde el panel, pero ninguna ruta lo usaba. La
  0.6.3 trata el 429 como una espera. La 0.6.2 lo trata como un fallo: quien
  siga en la 0.6.2 y mande más de 30 mensajes en un minuto verá alguno fallido,
  que se reintenta a mano.
- **Variable nueva y opcional, `WTFUCK_DESCARGA_URL`:** la página del enlace
  de contacto (`/c/<código>`) la usa para ofrecer descargar la app.
  `lanzar.sh` la escribe sola con `DOMINIO_DESCARGA`.
- **Base local de la app:** pasa a Room 35 por migración, sin perder datos.
- **Modo cerca:** los dos teléfonos tienen que tener la 0.6.3. El enlace ahora
  es Bluetooth cifrado con emparejamiento y no habla con la versión anterior.

## 0.6.2 · 2026-10-04

### Nuevo

- Nueva pestaña Social, en lugar de Contactos: ahí están los estados y el directorio de Usuarios. Los estados ya no ocupan la parte de arriba de tus chats.
- Editor de estados: fotos siempre en vertical y con buena resolución, que puedes encuadrar, girar y decorar con filtros, textos y stickers. También estados de texto, de video y de audio grabado al momento.
- Usuarios: un directorio para que te encuentre gente que no sabe tu usuario. Es opcional y viene apagado; si quieres aparecer, actívalo en Privacidad.
- Puedes borrar tu cuenta desde Privacidad, abajo de todo. Pide confirmar varias veces, y tienes 30 días para arrepentirte.

### Mejoras

- Contactos ahora está en el botón Nuevo, y se busca por @usuario en vez de por teléfono.
- En Ajustes > Notificaciones, la sección "Con la app cerrada" te dice qué falta para que lleguen los avisos, con los pasos para Honor, Huawei, Xiaomi y otras marcas.

### Seguridad

- Si dejabas un chat abierto y salías de la app, a la otra persona le llegaba "leído" aunque no lo hubieras visto, y a ti no te avisaba. Ya no pasa.

### Correcciones

- Las notificaciones llegan con la app en segundo plano o cerrada. Antes el mensaje se recibía, pero el aviso se perdía.
- Al abrir un chat ves el último mensaje, y el teclado ya no tapa lo nuevo.
- Las respuestas ya no aparecen escondidas entre mensajes viejos cuando el teléfono de la otra persona tiene mal la hora.
- Si con una persona todavía no se puede cifrar, tus mensajes a los demás salen igual. Antes se quedaban todos en "Enviando...".
- Añadir a alguien a una llamada ahora sí arranca la llamada del grupo, y a la otra persona le suena.

## 0.6.1 · 2026-09-29

### Nuevo

- En una llamada de dos ya puedes anadir a otra persona: se crea un grupo con los tres y la llamada sigue ahi. El grupo se queda en tus chats.
- Boton "Buscar actualizacion" al final del perfil, para comprobar cuando quieras si hay una version nueva. Tambien te dice cuando ya tienes la ultima.

### Mejoras

- En la llamada se ve la foto de la persona, no sus iniciales.
- Si la app se cierra de golpe -sin llegar a avisar-, ahora recupera del sistema el motivo y te lo ofrece al volver a abrirla. Antes esos cierres no dejaban ningun rastro.

### Correcciones

- Las llamadas ya no cierran la app. Pasaba solo en la version publicada -no en desarrollo- y por eso tardo en verse: el optimizador borraba unas clases que WebRTC busca al arrancar la llamada.

## 0.6.0 · 2026-09-27

### Nuevo

- Copia de seguridad cifrada: guarda tus chats con sus fotos en un archivo con una frase, y recupéralos si cambias o pierdes el teléfono.
- Mensajes temporales en chats de dos: antes solo se podían activar en grupos, y solo por un administrador.
- Codigo de recuperacion: si pierdes el telefono, es lo unico que te devuelve la cuenta. Creado en Cuenta, se anota en papel. Antes, perder el telefono era perder la cuenta para siempre.
- La copia de seguridad puede llevar tu identidad cifrada. Al restaurarla en otro telefono conservas tu numero de seguridad y a tus contactos no les salta ninguna alarma.
- Nuevo ajuste para bloquear capturas de pantalla dentro de la app. Apagado por defecto: hay motivos legitimos para capturar una conversacion propia.
- Exportar una conversacion a un archivo de texto, desde el menu del chat. Los mensajes temporales NO se exportan: quien los escribio pidio que no quedaran guardados.
- Buscar en TODOS los chats a la vez desde el buscador de la lista: ya no hace falta acordarse de en que conversacion se dijo algo.
- Puedes elegir el color de la app: seis acentos en Perfil > Apariencia. El ambar de "pendiente" y el coral de "error" no cambian, porque son significado y no decoracion.
- Fondo para el chat: degradado, con tu color, o mas oscuro. Las burbujas siguen opacas, asi que el texto se lee igual de bien.

### Mejoras

- La app te recuerda cuándo hiciste la última copia de seguridad, para que no se te pase.
- El temporizador ahora se ve: sale junto al nombre en el chat, dice en cuánto está, y queda escrito en la conversación cuando alguien lo cambia.
- La lista de chats va mas suelta con historiales grandes.
- Si la app se cierra sola, guarda un informe en tu telefono y te ofrece enviarlo. No lleva el texto de tus chats ni tu usuario, y puedes leerlo entero antes de decidir.
- Si un video o un archivo no cabe, se avisa ANTES de enviarlo y con los dos numeros -lo que pesa y el limite-, en vez de dejar un mensaje en rojo.
- Las burbujas tienen pico y los mensajes seguidos de la misma persona se agrupan, en vez de verse como fichas sueltas.

### Seguridad

- Los mensajes temporales ahora se borran de verdad en los DOS teléfonos. Antes desaparecían solo del tuyo y se quedaban en el de la otra persona. Si usaste esta función antes de esta versión, esos mensajes siguen en el otro teléfono.
- Recuperar la cuenta por SMS ahora pide tambien el codigo de dos pasos. Antes el SMS lo saltaba: quien se quedara con tu numero podia cambiarte la contrasena y cerrarte todas las sesiones con el segundo factor puesto.
- El codigo de recuperacion detecta mejor las erratas: antes casi la mitad de los errores en el ultimo caracter se aceptaban en silencio.

### Correcciones

- La app ya no puede reenviar el mismo mensaje dos veces al reconectarse.
- La pantalla de copia de seguridad decia que para restaurar en un telefono nuevo habia que entrar primero a la cuenta, y en un telefono nuevo no se podia entrar. Ahora dice lo que hace falta de verdad.
- Los mensajes escritos sin conexion ahora salen solos cuando vuelve la red, aunque hayas cerrado la app. Antes se quedaban esperando a que la volvieras a abrir.
- En Android anterior al 13, la pantalla decia que con el bloqueo activo la app salia en blanco en recientes, y no era cierto. Ahora lo dice claro y ofrece como taparla.

## 0.5.1 · 2026-09-27

### Mejoras

- Al desbloquear el teléfono, la app ya no muestra por un momento un aviso de "sin conexión" que desaparece solo.

## 0.5.0 · 2026-09-27

### Mejoras

- Para administradores: la lista de personas del panel muestra los últimos registros al abrirla, sin tener que buscar.

## 0.4.0 · 2026-09-27

### Nuevo

- Esta sección de Novedades: aquí ves qué cambió en cada versión.

## 0.3.0 · 2026-09-27

### Nuevo

- La app te avisa sola cuando hay una versión nueva y la instalas con un toque, sin ir al navegador.

### Mejoras

- La app pesa mucho menos: la descarga pasó de 162 MB a 26 MB.
- Puedes ver qué versión tienes al final de tu perfil.

### Seguridad

- Las actualizaciones solo se descargan por conexión segura y se verifican antes de instalar.

## 0.2.0 · 2026-09-26

### Nuevo

- Registro por invitación: en servidores cerrados entras con un código que te comparte alguien de dentro.
- Al terminar una llamada, en el chat queda cuánto duró.

### Mejoras

- Tu foto de perfil y de portada se ajustan solas al subirlas; tócalas para verlas a pantalla completa.

## 0.1.0 · Primera versión

### Nuevo

- Mensajería cifrada de extremo a extremo, sin número de teléfono: solo tu usuario.
