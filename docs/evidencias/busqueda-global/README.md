# Buscar en todos los chats

## Lo que había

Dos búsquedas, y ninguna hacía esto:

| Dónde | Qué buscaba |
|---|---|
| Dentro de un chat | El texto de los mensajes de **esa** conversación |
| La lista de chats | Solo título, nombre y el **último** mensaje, filtrando en memoria los chats ya cargados |

Así que buscar una palabra que dijo alguien hace tres meses exigía acordarse
de **en qué chat** fue, entrar, y buscar allí. Si no te acordabas, no había
forma.

## Lo que hace ahora

El mismo buscador de la lista de chats, sin nada nuevo que aprender: al
escribir dos o más caracteres aparece una sección **MENSAJES** debajo de los
chats que coinciden. Tocar un resultado abre ese chat **en ese mensaje**
—reutilizando `onAbrirEnMensaje`, que ya existía para la búsqueda dentro del
chat—.

---

## Misma semántica que la búsqueda de siempre, a propósito

Mismo `LIKE`, mismo `ESCAPE`, mismo límite de 200, mismo orden. **Dos
buscadores con reglas distintas en la misma app confunden más que uno que
falta**: quien busca `100%` espera lo mismo aquí que dentro de un chat.

Y para que sea verdad y no una intención, el escapado se **extrajo a una
función compartida**. Estaba escrito dentro de `buscarEnChat`, y al añadir la
global la tentación era copiarlo:

> Dos copias de un escapado son cómo una de las dos se queda sin arreglar el
> día que aparezca el tercer comodín.

### La única diferencia: los mensajes de sistema quedan fuera

Dentro de un chat, un *"te agregó a este grupo"* es contexto útil y son cuatro
líneas. En global, ese texto se repite en **cada** grupo: buscar una palabra
común devolvería la misma línea generada veinte veces y enterraría lo que
alguien escribió de verdad.

---

## El fragmento se centra en lo buscado

Un mensaje largo mostrado desde el principio puede **no enseñar la palabra que
se buscó**: la lista diría "coincide" y la persona no vería dónde. Se recorta
por delante con `…`, dejando contexto para que la frase se entienda.

Si la coincidencia está al principio —el caso normal— no se recorta nada:
anteponer unos puntos suspensivos inútiles es ruido.

Y si `indexOf` no encuentra lo que SQLite sí encontró (sus reglas de
comparación no son las de Kotlin), se devuelve el texto entero. Mejor eso que
una fila en blanco.

## El título sale de las mismas reglas que la lista

Alias de mi libreta → nombre que la persona eligió → username. Y en un grupo,
**nunca** el `nombreMostrado`, que es el nombre de una persona: usarlo pondría
el nombre de alguien como título del grupo.

Tiene pruebas porque el fallo no parece un fallo:

> Un resultado que sale con el nombre equivocado no parece un error. Parece
> que el mensaje era de otra persona.

## Rebote de 250 ms

`LIKE '%x%'` empieza con comodín y **ningún índice de SQLite sirve para eso**:
es un recorrido de la tabla se ponga lo que se ponga. Sin rebote, cada tecla
lanzaría una búsqueda completa.

Y cuando se llega al límite, se dice:

> *"Se muestran los 200 mensajes más recientes. Escribe algo más concreto."*

Es la diferencia entre "no hay más" y "hay más, afina".

## Por qué esto solo puede pasar en el teléfono

El servidor guarda **sobres opacos**: no podría ofrecer esta búsqueda ni
queriendo. Es la misma razón por la que el panel de administración no busca
mensajes.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`BaseLocal.kt`](../../../app/src/main/java/com/wtfuck/app/datos/BaseLocal.kt) | `ResultadoBusqueda` y `buscarEnTodo` |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `buscarEnTodo`; `paraLike` compartido con la búsqueda por chat |
| [`ChatsPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/ChatsPantalla.kt) | La sección, el rebote, `FilaResultado` y `fragmento` |

## Lo verificado

| Qué | Resultado |
|---|---|
| `BusquedaGlobalTest` | ✅ 12 pruebas |
| Suite unitaria del app | ✅ **495 pruebas, 0 fallos** |
| La consulta, validada por Room en compilación | ✅ columnas y tipos cuadran con `ResultadoBusqueda` |
| El `ESCAPE` es **un solo carácter** | ✅ comprobado a nivel de bytes |

Esa última no es paranoia: el KDoc del proyecto cuenta que un `ESCAPE` de dos
caracteres **cerró la app** en la primera prueba de la búsqueda por chat. Room
no lo detecta en compilación, así que se verificó con `cat -A` que las dos
consultas llevan exactamente el mismo byte.

## Lo que NO está verificado

- **No se ha buscado nada en un teléfono.** Room valida la consulta al
  compilar, pero nadie la ha ejecutado contra una base con mensajes: el
  emulador de esta máquina sigue corrupto.
- **No se ha medido con un historial grande.** Con decenas de miles de
  mensajes el recorrido completo podría notarse pese al rebote y al `LIMIT`.
  Es el mismo techo que la paginación pendiente.
- **Los acentos importan.** `canción` no encuentra `cancion`, porque el `LIKE`
  de SQLite solo ignora mayúsculas en ASCII. Se hereda de la búsqueda por
  chat y se deja igual **a propósito** —dos comportamientos distintos serían
  peor—, pero es una limitación real. Arreglarlo pide una columna normalizada
  y una migración que recorra todo el historial.
