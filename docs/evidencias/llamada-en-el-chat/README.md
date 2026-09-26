# La llamada deja rastro en el chat

Cuando una llamada termina, aparece en el chat con el tipo, la dirección y
cuánto duró — como en cualquier app de mensajería.

![el lado que contestó](1-el-lado-que-contesto.png)
![el lado que llamó](2-el-lado-que-llamo.png)

---

## No viaja, y eso es lo importante

Cada teléfono escribe el suyo cuando la llamada termina de su lado. **No se
manda ningún sobre.** Tres razones, en orden de peso:

1. **Los dos lados ya saben todo lo que hace falta.** La duración, si hubo
   vídeo y quién llamó están en el estado local. Mandar un sobre para contar
   algo que el otro ya sabe es trabajo y superficie por nada.
2. **Quien no contestó no puede recibir un sobre a tiempo.** Una llamada
   perdida tiene que aparecer en el chat de quien estaba sin señal, y ese es
   justo el caso en que un sobre no llega.
3. **Nadie puede inventarte un historial de llamadas.** `ClaseContenido.LLAMADA`
   **no** está en `VALIDAS`: si alguien la metiera en un sobre, el servidor lo
   rechaza con un 400. Un registro de llamadas falsificable desde afuera vale
   menos que ninguno.

El costo, dicho: las duraciones de los dos lados pueden diferir en uno o dos
segundos, porque cada uno cuenta desde que conectó lo suyo.

## El mismo hecho, dos frases

"Sin respuesta" y "Llamada perdida" son **el mismo hecho** contado por quien
llamó y por quien no atendió. No puede ser la misma frase: quien llamó necesita
saber que no le contestaron; quien no atendió, que lo llamaron.

Y sólo una de las dos va en rojo. La perdida es la única de la lista que pide
hacer algo. Si también se pintaran la rechazada o la cancelada, el color
dejaría de querer decir "hay algo pendiente" y pasaría a querer decir "llamada",
que es lo que ya dice el icono.

Una llamada que **yo** rechacé tampoco va en rojo: decidí no atender, no hay
nada que devolver.

## Sin tildes de entrega

El resumen se escribe en este teléfono y no se manda, así que un doble tilde de
"entregado" estaría afirmando algo que no ocurrió. No es purismo: el tilde es lo
que la gente mira para saber si algo llegó, y que aparezca donde no significa
nada le quita valor donde sí.

---

## El defecto de fondo: un campo con dos trabajos

Esta es la parte que costó, y vale escribirla entera porque los dos primeros
arreglos fueron al síntoma.

**El síntoma.** Quien **contestaba** una llamada de veinte segundos la
encontraba después en su chat como "Llamada perdida", en rojo. Del otro lado
estaba bien.

**El primer intento.** Supuse una carrera: `limpiar()` cierra los motores, y
cerrarlos dispara `onEstado(terminado)`, que cuando `Malla.trasCaida` dice
`ESPERAR` pone `conectadaEn = 0` para volver a "conectando". Así que moví la
lectura de la duración al principio de `limpiar`, antes de tocar los motores.

**No cambió nada.** Y ahí, en vez de suponer una tercera vez, puse un log:

```
5554 (contestó)  Fin de llamada: motivo=colgada inicio=0             segundos=0   saliente=false
5556 (llamó)     Fin de llamada: motivo=colgada inicio=1790392187237 segundos=21  saliente=true
```

`inicio=0` **antes** de que `limpiar` tocara nada. El valor ya venía en cero: mi
arreglo era correcto y atacaba el sitio equivocado.

**La causa.** `EstadoLlamada.conectadaEn` tiene dos trabajos que no son el
mismo:

- *qué muestra el cronómetro ahora* — y tiene que volver a cero cuando alguien
  se cae, porque la pantalla debe dejar de contar mientras no hay nadie;
- *cuánto duró esta llamada* — que no puede volver a cero nunca.

Usar un solo campo para las dos preguntas parecía natural. La pantalla seguía
mostrando `0:08` correctamente todo el tiempo, que es lo que hacía tan difícil
de ver el defecto: el campo que se leía mal era el mismo que se veía bien.

**El arreglo.** Un campo aparte, `conecto: Pair<String, Long>?`, que sólo se
escribe en la primera conexión de cada llamada. `conectadaEn` sigue haciendo su
trabajo de pantalla sin cambios.

**Y lleva el id de la llamada pegado** por algo que el log también mostró: el
lado que llamó registró el fin **dos veces**. `limpiar` corre más de una vez por
llamada. Con un campo que se borrara al final, la segunda pasada habría
reescrito el chat con cero segundos — el mismo defecto, por otro camino. Con el
id, las dos pasadas dan lo mismo:

```
5554 (contestó)  inicio=1790392332699 segundos=15 saliente=false
5556 (llamó)     inicio=1790392332829 segundos=15 saliente=true
5556 (llamó)     inicio=1790392332829 segundos=15 saliente=true   ← segunda pasada, igual
```

El `Log.i` se quedó. Una llamada que queda mal anotada no deja ningún otro
rastro de por qué.

---

## Pruebas

**11 pruebas nuevas** en `LlamadaEnElChatTest`, sobre las dos funciones puras
del protocolo (`llamadaEnElChat` y `duracionLegible`): los cuatro motivos por
los dos lados, la duración, el reloj que se corrige hacia atrás, y que
`LLAMADA` siga fuera de `VALIDAS`.

**8 defectos inyectados, 8 cazados:**

| # | Defecto | Prueba |
|---|---|---|
| m | una entrante rechazada por mí cuenta como perdida | `la que rechace yo NO es una perdida` |
| n | un motivo desconocido en una saliente pasa a perdida | `un motivo que nadie previo...` |
| o | se pierde el cero de los segundos (`5:3`) | `la duracion se lee como en un telefono` |
| p | los minutos llevan cero siempre (`05:32`) | `los minutos llevan cero delante SOLO si hay horas` |
| q | una duración negativa deja de recortarse | `una duracion imposible no rompe la linea` |
| r | el vídeo deja de distinguirse | `el video se dice, porque no es lo mismo` |
| s | `LLAMADA` entra en `VALIDAS` | `el servidor NO acepta un resumen...` |
| t | una llamada que conectó cuenta como perdida | `una llamada que hablo no es una perdida` |

```
=== 37 suites · 1548 pasan, 0 fallan ===   (integración)
    447 unitarias, 0 fallan
```

**Lo que ninguna de esas pruebas podía encontrar es el defecto de fondo.** No es
una función que devuelva mal: es un campo con dos significados y dos escrituras
compitiendo. Apareció en la primera llamada de verdad entre dos aparatos, y se
resolvió con un `Log.i`, no razonando.
