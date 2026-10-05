# Social, contactos por usuario, editor de estados, borrar cuenta y Usuarios

Cinco pedidos de UX, probados en dos emuladores (`xampl3` en 5554,
`goblin2026` en 5556).

## 1. La pestaña Social

- Los estados **salieron de la lista de chats** (`1-chats-sin-estados-...`).
  Empujaban las conversaciones hacia abajo con una franja que cambia cada
  pocas horas.
- **Social reemplaza a Contactos** en la barra de abajo. Tiene dos secciones:
  Estados y Usuarios.
- **Contactos pasó al menú "Nuevo"**, junto a Conversación, Grupo y Canal: la
  libreta se abre para empezar algo con alguien.
- La sección Estados es una lista vertical: "Mi estado", "Recientes" (con algo
  sin ver, anillo cian) y "Vistos". La franja horizontal anterior solo mostraba
  cinco personas y el resto quedaba fuera de pantalla. Además ahora muestra las
  **fotos** de cada autor; antes eran siempre iniciales.

## 2. Contactos por @usuario

La búsqueda por teléfono se reemplazó por una búsqueda por @usuario. Dos
fuentes, y las dos respetan lo que cada persona eligió:

- el usuario **exacto**, con cualquiera que se deje encontrar
  (`priv_busqueda`), igual que "nueva conversación";
- sugerencias por **prefijo**, pero solo de quien eligió aparecer en Usuarios.

No hay buscador por prefijo abierto a todos, a propósito: escribir "a", "b",
"c"... listaría a cada persona del sistema, que es justo lo que el directorio
voluntario existe para no hacer. La pantalla lo explica en una línea, para que
"no aparece nadie" al escribir medio nombre no parezca un fallo.

Probado: `goblin2026` busca "xampl3", lo agrega, pasa a "ya lo tienes" y queda
en la libreta.

## 3. El editor de estados

A pantalla completa, con cuatro modos.

**Foto** (`6-` y `7-`):
- marco **siempre 9:16**; una foto apaisada se encuadra, no se deforma;
- **recorte** = pellizcar y arrastrar dentro del marco, como Instagram, sin
  poder destapar los bordes;
- **giro** de 90°;
- **8 filtros**, con la propia foto como muestra: Original, B/N, Sepia,
  Cálido, Frío, Vívido, Suave y Noche;
- **textos** con color y caja opcional, y **stickers** (emojis y los stickers
  propios). Se mueven, agrandan y giran con dos dedos; tocar un texto lo
  edita, y la papelera quita el que está seleccionado;
- sale a **1080×1920**, JPEG 92, y ya no pasa por el reductor de fotos del
  chat. Medido en el teléfono que lo recibió: **1080×1920**. Con el ajuste de
  calidad "Media", el reductor lo habría bajado a 900×1600.

**Lo que se ve es lo que se publica.** La vista previa y el archivo los pinta
la misma función (`RenderEstado.dibujar`), con la geometría en coordenadas
normalizadas (`Encuadre`): no hay dos cálculos que puedan diferir. La captura
`8-` es el estado tal como llegó al otro teléfono: igual a la vista previa.

**Texto**: sobre un color, con el tope de 280 caracteres que ya existía.

**Video**: se publica tal cual, con pie. **Sin filtros ni recorte**: procesar
video exige recodificarlo en el teléfono, y no se promete hasta poder probarlo
en aparatos reales.

**Audio** (nuevo, clase `audio` en el protocolo): se graba en el editor, hasta
un minuto, sobre un color. El visor lo reproduce con un micrófono que late, y
el estado dura lo que dura la grabación. Un cliente viejo lo ve como "estado
sin contenido", que es el fallo del lado seguro.

`9-texto-audio-video.png`: los tres llegando al otro teléfono.

### Defectos que la prueba encontró en el propio editor

- **Atrás descartaba todo sin preguntar.** Se vio al probarlo: una pulsación y
  se perdían la foto encuadrada, el filtro y los textos. Ahora, si hay algo
  hecho, pregunta "¿Descartar el estado?".
- **"hace 497548 h".** `haceCuanto` recibe lo transcurrido, y se le pasaba la
  marca de tiempo. Ahora dice "hace un momento", con el reloj corregido contra
  el servidor.
- **Una trampa de Kotlin evitada antes de que fallara.** Los filtros llamaban
  a funciones del `companion` del enum desde sus propias entradas. Las
  entradas se construyen antes que el companion, así que habría dado un
  NullPointerException al cargar la clase. Se movieron a un `object` aparte.

## 4. Borrar cuenta

En Privacidad, abajo de todo (`4-`). Cuatro pasos (`5-`):

1. "¿Seguro?", con lo que pasa **ahora** (se cierran todas las sesiones,
   dejas de aparecer en Usuarios) y lo que pasa a los 30 días;
2. lo que no se puede borrar (los mensajes en teléfonos ajenos, los canales
   públicos, las denuncias abiertas);
3. **escribir tu propio @usuario**: con otro texto, "Continuar" no avanza
   (probado);
4. la contraseña, y el segundo factor si está activo.

Un solo "¿seguro?" se confirma por reflejo; escribir el usuario no. El mismo
flujo quedó en Mi cuenta. El servidor ya tenía la eliminación con 30 días de
gracia, que se cancela al volver a entrar.

Una corrección al escribirlo: el primer borrador decía "dejas de aparecer en
las búsquedas". **No es cierto**: el directorio excluye las cuentas en proceso
de borrado, pero la búsqueda exacta por usuario no. Se quitó para no prometer
algo que no pasa.

La prueba llegó hasta la contraseña y canceló: la cuenta de prueba quedó
intacta (`eliminacion_pedida_en` vacío).

## 5. Usuarios: el directorio voluntario

- **Privacidad > "Aparecer en Usuarios"**, que **nace apagado** (V44,
  `priv_directorio boolean DEFAULT false`). Estar en una lista que cualquiera
  puede recorrer es exponerse, y eso no se presume (Ley 29733).
- `GET /v1/directorio?q=&desde=` lista solo a quien se apuntó, y de esos solo
  a quien además está activo (sin suspensión ni borrado pendiente), no tiene
  un bloqueo en ningún sentido y **me deja encontrarlo** (`priv_busqueda`). Si
  no, tocarlo abriría un perfil que responde 404.
- Cada fila pasa por las mismas reglas de foto y nombre del perfil.
- Paginación por clave y no por OFFSET, para no saltar ni repetir a nadie si
  alguien entra o sale de la lista mientras se recorre.
- Con el límite de ritmo de las búsquedas.

En pantalla (`3-`): mientras `goblin2026` no se apunta, ve el aviso "Tú no
apareces aquí" con el atajo a Privacidad. `xampl3` lo activa y `goblin2026` lo
encuentra escribiendo "xamp". Tocarlo abre su perfil.

`directorio.mjs`: **31 casos**. Entre ellos: nace apagado; uno mismo no
aparece; bloqueos en los dos sentidos; coherencia con el 404 del perfil;
`%` y `_` no son comodines; la foto oculta igual que en el perfil; quien pide
irse desaparece en el acto; paginación sin saltos.

Dos errores míos atrapados por la propia suite:

- el escape del `LIKE` mal escrito (la trampa del heredoc);
- la paginación podía saltarse personas cuando sobraban visibles.

## Números

- Unitarias: 536 → **555** (19 de la geometría y los filtros del editor).
- Integración: **1657**, 0 fallos (suite nueva `directorio.mjs` con 31; 2
  casos de audio en `historias.mjs`).
