# Tanda 10 a 12, más "preguntar antes de abrir un enlace"

Sigue a la tanda A–C y 1–9. Cada punto se probó en los dos emuladores
(5554 = `xampl3`, 5556 = `goblin2026`). Lo que tocó el servidor tiene su prueba
de integración. Las capturas (`*.png`) no se suben al repositorio: están en esta
carpeta del equipo de desarrollo.

Resumen de cambios:

- **Room** pasa de 33 a 34: tablas `difusion` y `difusion_envio`.
- **Servidor:** migración V48 (`enlace_contacto`) y el cupo de mensajes, que
  ahora sí se aplica.
- **Pedido aparte del usuario, a mitad de la tanda:** que los enlaces del chat
  pregunten antes de abrirse en el navegador.

## 10. Copia de seguridad automática

Sigue el modelo de las copias locales de Signal:

- La persona elige una **carpeta** una vez (`ACTION_OPEN_DOCUMENT_TREE`, con
  permiso persistente).
- La **frase** se pide una vez y queda envuelta con una clave del Keystore
  (`CajaFuerte`).
- Cada día o cada semana sale sola una copia **idéntica a la manual**: se
  restaura desde el mismo botón y con la misma frase.
- Se conservan **las dos últimas** copias automáticas.

**Decisiones:**

- **Por qué guardar la frase no debilita nada.** La frase protege el archivo
  fuera del teléfono. Dentro del teléfono ya está todo lo que la copia contiene.
- **La identidad: se guarda la llave, no el código.** El código de recuperación
  también abre la cuenta en el servidor. Por eso se guarda solo
  `claveDeIdentidad`, que se deriva con otra etiqueta y no sirve para entrar.
- **La poda solo borra lo que se llama como una automática**
  (`wtfuck-auto-AAAAMMDD-HHMMSS.wtfbackup`). Corre después de escribir, nunca
  antes. Si la copia falla a mitad, el archivo a medias se borra: un archivo
  cortado desplazaría a una copia buena de las dos que se conservan.
- **Si la carpeta desaparece** (o falla tres veces), llega una notificación que
  lleva a la pantalla de copias.
- **Cerrar sesión o cambiar de cuenta** borra la frase y la carpeta.
- **Las condiciones:** batería no baja y espacio no bajo.
- **Lo que no resuelve,** y lo dice la pantalla: si la carpeta está en el mismo
  teléfono y el teléfono se pierde, la copia se pierde con él. Para eso hay que
  elegir una tarjeta SD o una carpeta que otra app sincronice.

**Prueba (5554):**

1. Activé la copia con la frase "frase-de-prueba-auto" en
   `Documents/wtfuck-copias`. Salió la primera al instante (1,5 MB).
2. Hice dos más con "Hacer una ahora". Quedaron solo las dos últimas, y un
   `notas-mias.txt` mío en la misma carpeta **no se tocó**.
3. **La copia automática se restauró** con su frase: "Restaurados 65 mensajes y
   11 archivos de 5 chats".
4. Borré la carpeta y pedí otra copia. En pantalla salió en rojo "No se
   encuentra la carpeta…" y llegó la notificación. Al tocarla se abrió la
   pantalla de copias.
5. Con "Cambiar carpeta" a `Documents/copias-nuevas` volvió a copiar y el error
   se borró.
6. `dumpsys jobscheduler` muestra el trabajo periódico con espera de unas 24 h y
   las condiciones `BATTERY_NOT_LOW STORAGE_NOT_LOW`.

**Pruebas unitarias:** `CopiaAutomaticaTest` tiene 6 casos: el nombre, el orden
y la poda. La mitad comprueba lo que **no** se borra.

**Evidencia:** las capturas `10-*.png`.

## 11. Enlace de contacto y QR

"Agrégame" sin dictar el usuario. El modelo es el de los enlaces de usuario de
Signal: un **código al azar de 22 caracteres por cuenta, revocable**. El enlace
tiene la forma `<servidor>/c/<código>`.

| Ajuste de privacidad | ¿El enlace lo salta? | Por qué |
|---|---|---|
| Quién me encuentra | **Sí**, aunque diga "nadie" | Repartirlo es decir "a ti sí". |
| Quién me escribe | **No** | Quien lo usa sin ser conocido llega como **solicitud**, aunque la cuenta no acepte solicitudes de desconocidos. Un enlace filtrado puede traer mensajes, no conversaciones abiertas a la fuerza. |
| Bloqueo | **No** | El enlace responde igual que uno que no existe. |

**Servidor:**

- Tabla `enlace_contacto` (V48), una fila por cuenta: cambiar el enlace pisa la
  fila.
- `GET/POST/DELETE /v1/perfil/enlace`: leer, crear o cambiar, y apagar.
- `GET /v1/enlaces/{código}`: con el cupo de "buscar" y la privacidad de nombre
  y foto de siempre.
- `DirectaReq.enlace`: el código viaja al abrir la conversación.
- `/c/{código}`: una página pública sin JavaScript, con
  `CSP default-src 'none'` y `noindex`. **No consulta la base ni dice de quién
  es**, para que una vista previa de otra mensajería no pueda averiguarlo. Su
  botón abre `intent://` y de ahí `wtfuck://c/<código>`.

**App:**

- "Mi enlace y QR" en Perfil: compartir, copiar, cambiar y apagar.
- "Escanear código" en el menú Nuevo.
- La hoja de un enlace muestra quién es y un botón "Escribir".

**Defecto encontrado al probar.** Si la app arrancaba desde un enlace, tocar
**el mismo enlace** otra vez no hacía nada. Con `launchMode="standard"`, Android
ve que el intent es igual al que creó la tarea y solo la trae al frente, sin
entregarlo. Lo comprobé con trazas: `atender()` ni se llamaba. `MainActivity`
pasa a `singleTop`, que lo entrega siempre a la instancia de arriba. Lo verifiqué
repitiendo la secuencia exacta: el servidor registra 4 → 5 resoluciones.

**Pruebas:**

- `pruebas/enlace.mjs`, 30 casos:
  - crear, leer, cambiar (el viejo deja de servir) y apagar;
  - encontrar con "busqueda: nadie";
  - el nombre oculto a desconocidos no sale por el enlace. Lo verifiqué: al
    principio el `PUT /v1/perfil` de la prueba no guardaba el nombre y el caso
    pasaba sin comprobar nada;
  - la solicitud se abre con el enlace correcto y no con el de otra persona;
  - un bloqueo responde 404;
  - la página no lleva el usuario y no refleja un código con `<script>`.
- `ajeno-lectura.mjs` exime las tres rutas nuevas, con su razón.
- `EnlaceDeContactoTest`: de un QR cualquiera solo sale un código con la forma
  exacta, o nada.

**En los emuladores:**

- El QR de la captura decodificado con OpenCV da la URL exacta.
- La página se ve bien a 375 px (captura `11-pagina-del-enlace.png`, hecha en el
  navegador del escritorio).
- `wtfuck://c/…` en el 5556 abre la hoja; "Escribir" abre el chat.
- Mi propio enlace muestra "Es tu propio enlace".
- Después de "Cambiar enlace", el viejo dice "Ese enlace no existe o ya no
  funciona".

**Lo que no se probó:**

- **El escáner con la cámara.** La cámara del emulador no ve otra pantalla. La
  hoja que abre es la misma que la del enlace, que sí se probó.
- **El botón de la página dentro de Chrome en el emulador.** Chrome pide aceptar
  sus términos al primer uso y no los acepté en nombre de nadie. Probé la
  página en el navegador del escritorio y el `wtfuck://` directo.

**Evidencia:** las capturas `11-*.png`.

## Preguntar antes de abrir un enlace

Lo pidió el usuario a mitad de la tanda. Todo enlace que se toca dentro de la
app pregunta antes de salir al navegador: el texto de un mensaje, la tarjeta de
vista previa y la lista de enlaces compartidos.

**Cómo:** se reemplaza `LocalUriHandler` en la raíz. Así preguntan también los
enlaces que se agreguen después, sin que nadie tenga que acordarse.

**Qué muestra el diálogo** (`EnlaceSeguro`):

- El **sitio real**. En `https://banco.com@malo.com/` dice `malo.com` y avisa
  del disfraz.
- Un aviso si hay punycode o letras de otro alfabeto, y otro si es `http` sin
  cifrar.
- Tres botones: Abrir, Copiar y Cancelar.

Un enlace de contacto de nuestro propio servidor no va al navegador: abre la
hoja de la app.

**Pruebas unitarias:** `EnlaceSeguroTest`, 9 casos: arroba, usuario y clave,
puerto, una arroba en la ruta, punycode derivado con `IDN.toASCII`, cirílico
escrito tal cual, http, y esquemas que no se abren.

**En el emulador:**

- El enlace disfrazado muestra "Te lleva a **ejemplo.com**" con el aviso.
- "Cancelar" no abre nada.
- La tarjeta de Wikipedia también pregunta.
- "Abrir" lanza Chrome. Lo cerré en su pantalla de bienvenida sin aceptar nada.
- El enlace de contacto en el chat abre la hoja de la app.

**Evidencia:** las capturas `enlace*.png`.

## 12. Listas de difusión

Un mensaje a varias personas. Cada una lo recibe **en su chat conmigo**, como
un mensaje directo normal cifrado para ella, y nadie ve a quién más le llegó.
La lista vive solo en este teléfono: para el servidor no existe.

**Reglas:**

- **Solo con personas con las que ya tengo un chat directo.** Es la regla de
  WhatsApp y evita que la lista sea una herramienta de spam.
- Los chats protegidos no se ofrecen, igual que en el buscador.
- Las listas tienen de 2 a 100 personas.
- Cada envío muestra "Saliendo: X de N", "Entregado a X de N", "leído por Y" y
  "Z no salieron". El texto sale de `Difusiones`, una función pura con 7
  pruebas.
- Con fotos, cada persona recibe su propia copia cifrada. Si la cuenta pasa de
  40 archivos, la app lo frena antes, con el motivo: es el cupo de subida del
  servidor.

**Prueba:**

- Creé "Avisos del curso" con goblin2026 y probador, y mandé "La clase del
  jueves pasa a las 10".
- A goblin2026 le llegó en su chat con xampl3.
- La lista marca "Saliendo: 1 de 2 · leído por 1". El de probador sigue en cola
  porque esa cuenta nunca publicó sus claves: es el caso del punto C.

**Evidencia:** las capturas `12-*.png`.

### Dos defectos que salieron al probar la lista

1. **El cupo de mensajes del servidor no existía.** `Limitador.ENVIAR_MENSAJE`
   (30 por minuto) estaba declarado y era **ajustable desde el panel**, pero
   ninguna ruta lo aplicaba, y así desde el primer commit. Ahora se aplica en
   `POST /v1/mensajes`, por persona. Lo prueba `pruebas/cupo-mensajes.mjs`:
   - pasan 30;
   - el 31 recibe 429 con los segundos de espera;
   - otra persona sigue escribiendo.
2. **La app trataba el 429 como rechazo definitivo.** El mensaje quedaba
   FALLIDO para siempre: escribiendo muy rápido, o con una lista de más de 30,
   todos los que pasaban del cupo.

   Ahora el 429 deja el mensaje PENDIENTE, con el motivo "El servidor pidió ir
   más despacio", y la cola se retoma sola pasada la espera que indica el
   servidor.

   **Defecto en mi propio arreglo.** El reintento programado no podía programar
   el siguiente: se veía a sí mismo "activo" y la cadena se cortaba tras la
   primera espera. Corregido.

   **Prueba en el emulador.** Bajé el cupo a 3 por minuto en la base **local**
   (`limite_config`) y lo quité al terminar. Mandé 6 mensajes de golpe: quedaron
   "en cola" y salieron **solos**, en tandas, en unos 130 s, sin ninguno
   fallido (`12-cupo-esperando.png` y `12-cupo-salieron.png`).

Otro detalle: `directaCon` podía devolver un chat **temporal** con esa persona.
Ahora prefiere el permanente, así un mensaje "a su chat" no cae en uno que se
borra solo. Afecta a la lista, a "responder en privado" y al enlace de contacto.

## De paso

Dos comentarios de documentación de las migraciones de Room habían quedado
sobre la migración equivocada, al ir insertando migraciones nuevas arriba.
Volvieron a la suya (`DE_20_A_21` y `DE_18_A_19`).

## Resultado

- **Unitarias:** 645, sin fallos.
- **Integración:** 46 suites, 1781 pruebas que pasan y 0 que fallan (2
  omitidas, las del bus, que necesitan dos servidores). Son las 1746 de antes,
  más las 30 de `enlace.mjs` y las 5 de `cupo-mensajes.mjs`.
- **Para publicar** (la 0.6.3 sigue sin publicarse):
  1. Redesplegar el **servidor**: V47, V48 y el cupo de mensajes.
  2. Agregar al despliegue `WTFUCK_DESCARGA_URL` (opcional) para que la página
     del enlace ofrezca descargar la app.
  3. Ojo con la 0.6.2: trata el 429 como fallo definitivo. Con el cupo activo,
     quien tenga la 0.6.2 y mande más de 30 mensajes por minuto verá alguno
     fallido, que puede reintentar a mano. Con 30 por minuto es raro.
