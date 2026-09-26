# Chats y grupos temporales · módulo AY

Una conversación que se borra sola: la conversación entera, no sus mensajes uno
a uno.

![Elegir el plazo](1-elegir-el-plazo.png)

## Lo que dice, y lo que no promete

> *Al cumplirse el plazo, la conversación y sus mensajes se borran en todos los
> teléfonos. **No impide que alguien haga una captura antes.***

Esa segunda frase está ahí a propósito. "Temporal" invita a entender "nadie lo
va a poder ver", y no es eso: lo que hace es que el **registro** deje de
existir. Una captura de pantalla, una foto del teléfono con otro teléfono, o
alguien mirando por encima del hombro no los para nada.

Decirlo donde se decide es la diferencia entre una función y una promesa que la
app no puede cumplir.

**Apagado por defecto.** Lo normal es un chat que se queda, y ofrecerlo
encendido haría que alguien creara sin querer un chat que se borra — algo que no
se deshace: cuando se nota, ya no está.

## Es una conversación aparte, no la de siempre con fecha

Pedir un chat temporal con alguien **no convierte** el que ya existe. Eso le
pondría fecha de borrado a un historial que nadie aceptó perder.

Con una conversación nueva, la de siempre sigue donde estaba y la temporal
empieza vacía, que es lo que alguien espera al abrir una. Por eso su clave
lleva un identificador propio: la clave de una directa normal es única por
pareja —es lo que hace que abrirla dos veces devuelva la misma— y una temporal
tiene que poder convivir con ella, e incluso con otra temporal.

## Quién lo hace cumplir: los dos, y hace falta que sean los dos

**El teléfono** borra la conversación y sus mensajes. Es el único que puede: el
historial vive sólo ahí, porque el servidor lo borra al confirmarse la entrega.

**El servidor** borra su rastro —la fila, los participantes, los sobres
pendientes— y avisa a quien esté conectado.

Ninguno de los dos alcanza solo:

- Sin el del teléfono, el servidor olvidaría y el chat seguiría en la pantalla
  de todos.
- Sin el del servidor, quedaría en la base para siempre el registro de que esa
  conversación existió, entre quiénes y cuándo.

La fecha la calcula el **servidor** y viaja con la conversación. Es el mismo
reparto que en la ubicación en vivo, y por la misma razón: con dos teléfonos
mal puestos en hora, el mismo chat vencería en momentos distintos en cada uno —
y el que lo tuviera adelantado lo borraría mientras el otro sigue escribiendo.

Y viaja en **cada listado**, no sólo al crearlo: un aparato que se sincroniza
por primera vez no estuvo cuando se creó, y ése es el único camino por el que
puede enterarse.

---

## Tres cosas que encontraron las restricciones de la base

Merecen contarse porque las tres las atrapó el esquema, no una prueba.

**`expira_en` no puede ser anterior a `creada_en`.** El CHECK que escribí en la
misma migración rechazó mi propia prueba, que adelantaba el reloj poniendo el
vencimiento un minuto atrás en una conversación recién creada. La restricción
tenía razón: una fila así no tiene sentido. La prueba ahora mueve las dos
fechas y modela un chat creado hace una hora que venció hace un minuto.

**`evento_pendiente` valida el tipo contra una lista cerrada.** Sin agregar
`conversacion_vencida`, el barrido fallaba cada diez segundos con un CHECK
violado. Es una defensa que funciona: un tipo de evento mal escrito no se
entrega en silencio.

**Y ese fallo se llevaba puestos los otros barridos.** Los tres compartían un
solo `runCatching`, así que el CHECK violado del chat temporal dejó de barrer
también los timbres y las llamadas abandonadas — cada diez segundos, durante
todo el rato que tardó en verse. El síntoma no se parecía a la causa: las
llamadas dejaron de cerrarse solas por culpa de un chat. Ahora cada barrido
tiene el suyo.

## Un orden que costó una prueba en rojo

`evento_pendiente.conversacion_id` apunta a `conversacion` con
`ON DELETE CASCADE`. Emitir el aviso **antes** de borrar creaba los avisos y el
borrado se los llevaba en la misma transacción: quien estaba conectado se
enteraba igual —el empujón lleva el objeto en memoria— y **quien estaba apagado
no se enteraba nunca**.

Ahora el aviso se emite después del borrado, con el id de la conversación en el
detalle en vez de en la columna. Así sobrevive y espera al teléfono que estaba
apagado.

---

## Qué está verificado y cómo

**Por la suite `temporales`, 22 pruebas contra el servidor y la base reales**:
que el plazo se guarda y vuelve en el listado, que la conversación temporal es
otra y no toca la normal, que dos temporales con la misma persona conviven, que
una duración fuera de la lista se rechaza con 400, que al vencer el servidor
borra la fila **y sus participantes**, que la normal sigue entera, y que queda
un aviso para quien no estaba.

**Visualmente**: el diálogo de la captura, en el emulador.

**Lo que no se capturó**: el distintivo de reloj en la lista de chats. Está
escrito siguiendo el mismo patrón que los de fijado y silenciado —en cian y no
en gris, porque que un chat se borre solo no se deshace— pero la automatización
de toques no consiguió completar la creación desde la pantalla, y no se afirma
lo que no se vio.

**1959 pruebas en verde**: 1548 de integración en 37 suites, 329 JUnit de app
y 87 de servidor.
