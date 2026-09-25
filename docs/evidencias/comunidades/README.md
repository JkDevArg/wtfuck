# Módulo AD · Evidencias

Era el último hueco declarado del brief. El §3 pedía un ajuste de
"invitaciones a comunidades" y la cobertura decía, con razón, que faltaba el
**contenedor entero** y no el ajuste.

## Qué es una comunidad aquí

Un conjunto de grupos bajo un nombre, **más un canal de anuncios**. Lo segundo
es lo único que la hace una comunidad: sin él, agrupar chats es una carpeta, y
una carpeta se resuelve en el teléfono sin que el servidor se entere.

| | Qué muestra |
|---|---|
| `01-vacio-que-explica.png` | El vacío dice **qué es una comunidad**, no sólo que no tenés ninguna. Es una función que nadie usó antes en esta app: "no tenés ninguna" a secas no explica para qué sirve. |
| `02-crear.png` | Nombre, descripción y los grupos con los que nace. Se eligen al crear porque una comunidad vacía no le sirve a nadie. |
| `03-exito-parcial.png` | **La comunidad se creó y el grupo se rechazó, con su motivo.** `@tatiana` no administra "Equipo seguridad". |
| `04-en-la-lista.png` | "Auditoria Cientifica · 0 grupos · administras". |
| `05-por-dentro.png` | El canal de anuncios primero: es lo que la hace una comunidad. |

## No falla entero, y eso es una decisión

Quien agrega cinco grupos y tiene permiso en cuatro espera que entren los
cuatro y que le digan cuál no. Un 403 que descarta los cinco convierte un aviso
en un reintento a ciegas.

La captura `03` es ese caso en vivo: la comunidad quedó creada, el grupo no
entró, y la pantalla dice por qué. Y el número de la lista dice la verdad —
**0 grupos** — en vez de fingir que entró.

## La pertenencia se deriva, y eso hay que mantenerlo

Sos de la comunidad si sos de alguno de sus grupos. No hay lista de miembros
aparte: dos listas que dicen lo mismo se separan, y el día que se separan nadie
sabe cuál manda.

Lo que sí se mantiene es la fila de `participante` del canal de anuncios, en
**cuatro momentos**, y la suite tiene una sección por cada uno:

1. cuando un grupo entra a la comunidad
2. cuando alguien entra a un grupo que **ya estaba** en la comunidad
3. cuando alguien sale de un grupo
4. cuando un grupo sale de la comunidad

Los cuatro son el mismo invariante mirado desde cuatro lados. Probar sólo el
primero —que es el fácil— dejaría pasar justo los defectos que rompen el modelo
con el uso.

## La prueba que no probaba nada

Quité los dos enganches a propósito y la suite dio **una sola falla de 41**.

La causa: mis secciones 2 y 3 comprobaban `GET /v1/comunidades`, que se
**deriva** de estar en un grupo. Al salir del grupo la comunidad desaparece de
esa lista aunque la fila del canal se quede para siempre. O sea que pasaban con
el enganche puesto y sin él.

Y había algo peor: **los dos defectos se tapaban entre sí.** Sin el enganche de
entrada, quien entró después nunca estuvo en el canal, así que "sale del canal"
pasaba trivialmente. Hubo que romperlos **por separado** para ver cada uno.

Con la sección 3 arreglada —comprobando la lista de conversaciones, que es la
que refleja `participante`— y sólo el enganche de salida roto:

```
FALLA y deja de ver la comunidad
FALLA y sale del canal de anuncios
```

## El ajuste del §3, y por qué es este

El brief pide "invitaciones a comunidades". En este modelo a nadie se lo invita
a una comunidad: se lo agrega a un **grupo**, y eso ya lo gobierna
`priv_grupos`.

Lo que `priv_grupos` **no** cubre es el caso propio de las comunidades: alguien
agrega a la comunidad **el grupo en el que ya estabas**, y de golpe estás en un
canal de anuncios con quinientos desconocidos sin que nadie te haya agregado a
nada. Ese es el hecho nuevo, y es el que el ajuste gobierna.

```
PASA  el ajuste existe y viene en todos por defecto
PASA  se puede poner en nadie
PASA  el grupo entra a la comunidad igual
PASA  pero a quien dijo "nadie" NO se lo mete en los anuncios
PASA  y SIGUE en su grupo: el ajuste no expulsa de nada
```

La penúltima es la que importa: **`nadie` no te saca del grupo.** Un ajuste de
privacidad que te expulsa de algo no es un ajuste de privacidad, es una
sanción.

## Lo que se vio al mirarlo, y era un agujero

El usuario dijo que "no se ve bien". Tenía razón, y no era estético.

| | Qué muestra |
|---|---|
| `06-antes-callejon-sin-salida.png` | La hoja terminaba en **"0 grupos"** y nada más. |
| `07-con-agregar-y-editar.png` | Ahora: lápiz para editar, **"+ Agregar"** y un vacío que dice qué hacer. |
| `08-elegir-grupos.png` | El selector de grupos. |

**Una comunidad podía quedar en cero grupos sin forma de arreglarlo.** Y lo
peor: `agregarGrupos` y `editar` estaban **construidos y probados en el
servidor** —cuarenta y tres pruebas verdes— y la pantalla no los llamaba. Los
grupos sólo se podían elegir al crear.

> Una capacidad probada que la interfaz no ofrece es una capacidad que no
> existe. La suite decía que sí y la pantalla decía que no.

Y de paso salió un comentario que había empezado a mentir: la hoja de crear
decía *"sólo se ofrecen los grupos que administro"* y el código ofrecía todos.
Se corrigió **el comentario, no el código**: lo que hace falta es el permiso
`grupo.editar_info` y el teléfono no lo sabe —lo más parecido que tiene es una
jerarquía, que es un proxy—. Filtrar por un proxy escondería grupos que sí se
podían agregar, que es peor que ofrecer uno y explicar el rechazo. Los permisos
los resuelve el servidor en cada petición; la pantalla no los adivina.

## Cómo se tomaron

```
G:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 exec-out screencap -p > captura.png
```

Con los dos emuladores corriendo, los dos túneles abiertos y el APK en los dos.
La máquina se había reiniciado, así que el entorno se levantó de cero: Docker,
los tres contenedores, las dos instancias del servidor y los dos AVDs.
