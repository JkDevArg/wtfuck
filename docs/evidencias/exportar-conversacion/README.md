# Exportar una conversación en claro

## Qué es esto realmente

Una función que **rompe a propósito** las protecciones de la app. Todo lo demás
aquí existe para que las conversaciones no salgan del teléfono en claro; esto
las saca en claro.

Porque a veces hace falta: guardar un acuerdo, aportar una prueba, archivar
algo antes de dejar de usar la app.

Negarlo no haría el sistema más seguro, solo menos útil: **quien lo necesite
hará capturas de pantalla**, que es peor — más trabajo, peor resultado, y sin
ninguno de los avisos. Lo correcto es darlo y decir exactamente qué implica.

---

## La decisión de diseño: los mensajes temporales NO se exportan

La que podría haber ido al revés, y la que define el módulo.

Quien pone un temporizador está pidiendo que lo que escribe **no persista**.
Meterlo en un `.txt` sin cifrar es exactamente lo contrario — y además lo
decide **una sola de las dos partes**, sin que la otra se entere.

> Un mensajero que ofrece "esto desaparece" y a la vez un botón para guardarlo
> para siempre está mintiendo en una de las dos pantallas.

Así que se excluyen. Y el archivo **dice cuántos se dejaron fuera**:

```
3 mensajes temporales no se incluyeron: quien los escribió pidió que no
quedaran guardados.
```

Callarlo haría creer que la conversación está entera — y si esto se usa como
prueba, eso importa.

**El coste está asumido:** si alguien necesitaba justo un mensaje temporal, no
lo tendrá. Es el precio de que el temporizador signifique algo.

Hay un detalle que lo cierra: antes de exportar se **barren los vencidos**.
Exportar un mensaje que ya debía haber desaparecido, solo porque nadie abrió el
chat desde que venció, sería colarlo por la puerta de atrás.

---

## Lo que tampoco va

| | Por qué |
|---|---|
| **Los archivos adjuntos** | Solo su nombre y tipo. Meterlos convertiría un `.txt` en un zip, y quien quiera las fotos tiene la copia de seguridad — que además va **cifrada**, que es donde deben estar |
| **Los mensajes retirados** | No tienen texto; una línea vacía como prueba solo estorba |
| **Los ocultos** | Por lo mismo que no se ven en el chat |

---

## El aviso va antes del botón

Cuatro líneas en ámbar, **mientras se decide**, no después:

```
• El archivo NO va cifrado: quien lo abra lo lee.
• Incluye lo que escribió la otra persona, y ella no se entera.
• Los mensajes temporales NO se exportan: quien los escribió pidió que no quedaran.
• Las fotos y archivos no van, solo su nombre.
```

La segunda es la que nadie pone y es la que más importa: **exportar es una
decisión unilateral sobre las palabras de otra persona.**

Y el sistema decide dónde se guarda, vía `CreateDocument`: esta app no elige
por nadie dónde dejar un archivo sin cifrar con sus conversaciones dentro.

---

## Detalles que no son detalles

- **Se ordena por fecha aquí**, aunque la base ya los devuelva ordenados. Un
  export desordenado como prueba no vale nada, y confiar en el orden de otra
  capa es cómo se rompe en silencio.
- **Los mensajes de sistema se marcan con `*` y sin autor.** Importa si esto se
  usa como prueba: que no parezca que alguien lo dijo.
- **Un documento no repite su nombre.** En un documento `texto` suele *ser* el
  nombre, y sin el guard quedaría `[archivo] contrato.pdf — contrato.pdf`.
- **El pie del adjunto se conserva.** Un `[foto]` a secas perdería el texto que
  la acompañaba, que muchas veces es lo único que importa.
- **Se usa el nombre de tu libreta** si lo hay; el username sigue en la
  cabecera.
- **"tú" y no tu username**: quien lee su propia conversación se reconoce antes
  así.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`ExportarChat.kt`](../../../app/src/main/java/com/wtfuck/app/datos/ExportarChat.kt) | **Nuevo.** El formato y las exclusiones. Puro y probado |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `exportarChatA`: barre vencidos, resuelve alias, escribe |
| [`ChatPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/ChatPantalla.kt) | Menú, aviso y selector de destino |

## Lo verificado

| Qué | Resultado |
|---|---|
| `ExportarChatTest` | ✅ **20 pruebas** |
| Suite unitaria del app | ✅ **483 pruebas, 0 fallos** |
| R8 sobre release | ✅ |

La mitad de las pruebas cubren **lo que no debe salir**, porque ese es el fallo
que no se ve: el archivo se genera, se abre, tiene mensajes, parece bien — y
lleva dentro algo que no debía. Eso solo lo descubre la persona a la que le
afecta, cuando el archivo ya está en otro sitio.

```
PASA  un mensaje temporal NO se exporta
PASA  el archivo DICE cuantos temporales se omitieron
PASA  un mensaje retirado no se exporta
PASA  una conversacion SOLO de temporales avisa y sale vacia
```

## Lo que NO está verificado

- **No se ha exportado nada en un teléfono.** El formato está probado con
  mensajes construidos a mano; lo que falta ver es el selector del sistema, el
  archivo resultante abierto en otra app, y los acentos en un editor de
  Windows.
- **La codificación es UTF-8 sin BOM** (lo que da `bufferedWriter()`). En
  Android y Linux es correcto; el Bloc de notas de Windows moderno también lo
  lee bien, pero no está comprobado en la práctica.
- **Un chat enorme carga todos los mensajes en memoria** para construir el
  texto. Es el mismo problema que la paginación pendiente: con 20.000 mensajes
  puede ir justo. No se ha medido.
