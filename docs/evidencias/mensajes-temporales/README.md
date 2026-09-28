# Mensajes temporales · el arreglo de una promesa a medias

No es una función nueva. Estaba a medio construir y **fallaba en silencio**.

---

## El defecto

La app ofrecía "mensajes temporales": cada mensaje del chat se borra solo
pasados N segundos. Lo que hacía en realidad:

> El mensaje temporal desaparecía del teléfono de **quien lo escribió**, y se
> quedaba **para siempre** en el de quien lo leyó.

Sin error, sin aviso, sin nada en los registros. La pantalla decía una cosa y
el sistema hacía la mitad — la mitad que no protege a nadie.

### Por qué pasaba

La ruta `PUT /v1/conversaciones/{id}/temporales` era de **solo escritura**. Se
podía encender el temporizador, y ningún cliente podía volver a leerlo:
`temporales_segundos` vivía en la tabla `conversacion` y no salía en ninguna
respuesta.

Consecuencia directa: el único que se enteraba del vencimiento era el emisor,
porque se lo devolvía el registro de su propio mensaje
(`MensajeMeta.expiraEn`). Quien recibía no tenía de dónde sacarlo, así que
construía su `MensajeEnt` sin `expiraEn` y lo guardaba como permanente.

Y un segundo síntoma del mismo hueco: los botones del grupo **no podían mostrar
el valor actual**. Tres botones iguales, ninguno marcado. Un control de
privacidad que no dice su estado es peor que no tenerlo, porque invita a creer
que está puesto.

### Y un tercer hueco

No se podía activar en un **chat de dos**, que es donde más sentido tiene.
`configurarTemporales` exigía `grupo.editar_info`, un permiso que un `miembro`
no tiene — y en una conversación directa ambos son miembros.

---

## Quién hace cumplir qué

El reparto importa, porque explica el diseño:

| Quién | Qué puede hacer |
|---|---|
| **Servidor** | Guardar UN valor (el temporizador de la conversación) y avisar a todos de los cambios. Nada más: **no tiene el mensaje**, entrega el sobre y lo olvida. |
| **Cada cliente** | Poner el vencimiento a su propia copia y borrarla. Es el único que puede. |

Por eso el arreglo no es "que el servidor borre": el servidor no puede. Es
**que el otro teléfono se entere**.

### Por qué el vencimiento no viaja en la entrega

Se consideró añadir `expiraEn` a `Bajada.Entrega` y se descartó por dos razones:

1. El sobre se empuja por el socket mientras el metadato se registra por HTTP.
   En el momento del empuje el vencimiento puede **no existir todavía**.
2. El servidor **borra los metadatos vencidos** (`barrerVencidos`). Un teléfono
   apagado una semana recibiría un vencimiento nulo — y guardaría como
   permanente justamente el mensaje más viejo.

El temporizador de la conversación, en cambio, está siempre. Es un valor y no
uno por mensaje, así que no se barre ni llega tarde.

---

## El tope contra un reloj mentiroso

La cuenta arranca en `creadoEn`, que es el reloj de **quien escribió**. Un
emisor con la hora adelantada —por error o a propósito— pondría una fecha
futura, y su mensaje "de un minuto" viviría días en el teléfono ajeno.

Por eso el resultado se topa con `ahora + plazo`: nadie puede estirar el plazo
más allá de lo que aceptó el teléfono que borra.

Al revés **no** se corrige. Si `creadoEn` viene del pasado el mensaje vence
antes, o al instante — deliberado: un mensaje que llega tarde ya es viejo, y
equivocarse del lado de borrar es el lado correcto en el que equivocarse.

## Por qué el plazo mínimo es 1 minuto y no 30 segundos

Se intentó copiar los 30 segundos de Signal. El `CHECK temporales_valido` de la
V6 lo rechazó — y al mirar por qué, **el límite tenía razón en esta app**: el
transporte es un buzón que reentrega. Un sobre puede quedar encolado minutos si
el teléfono destino está apagado, y el vencimiento cuenta desde que se escribió.
Con 30 segundos ese mensaje llega **ya vencido** y el barrido se lo lleva antes
de que nadie lo vea: no es un mensaje que se borra pronto, es un mensaje que no
llega, y sin explicación.

## Lo que el cambio NO hace

- **No toca los mensajes que ya estaban.** El temporizador rige lo que se
  escriba de ahí en adelante, como en Signal. Aplicarlo hacia atrás convertiría
  "activar los temporales" en "borrar el historial", que es otra acción y nadie
  la pidió. Lo mismo en la migración de Room: por defecto `0`.
- **No protege contra quien no quiere cooperar.** Borrar en el teléfono ajeno
  depende de que la app del otro lo haga. Contra una captura de pantalla o un
  cliente modificado, ningún temporizador sirve — y **la pantalla lo dice**, en
  vez de insinuar lo contrario.

---

## El bug que encontró la prueba de integración

Al correr la suite: **500 Error interno** al guardar el temporizador. La causa
fue el `CHECK tipo_evento_valido` de `evento_pendiente`, que valida el tipo de
evento contra una lista cerrada y no conocía `conversacion_temporales`.

La defensa funcionó exactamente como debía: un tipo de evento nuevo **no se
entrega en silencio**. Arreglado en `V42`, que reconstruye la restricción
(Postgres no deja añadir un valor a un `CHECK` existente).

Merece la nota porque es el tipo de fallo que las pruebas unitarias no ven: el
código compilaba, la lógica era correcta y la base decía no.

---

## Los archivos

| Archivo | Qué cambió |
|---|---|
| [`Temporales.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Temporales.kt) | **Nuevo.** La política pura del vencimiento, con el tope del reloj |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `vencimientoDe` aplicado al mensaje ENTRANTE; evento `conversacion_temporales`; `temporalesFlow` |
| [`BaseLocal.kt`](../../../app/src/main/java/com/wtfuck/app/datos/BaseLocal.kt) | `ConversacionEnt.temporalesSegundos` + migración 19→20; `fijarTemporales`; `conversacionFlow` |
| [`MensajesTemporales.kt`](../../../app/src/main/java/com/wtfuck/app/ui/MensajesTemporales.kt) | **Nuevo.** El selector con el valor actual marcado, y el reloj de la cabecera |
| [`ChatPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/ChatPantalla.kt) | Entrada en el menú (también en directas) y el reloj junto al nombre |
| [`GrupoPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/GrupoPantalla.kt) | Los tres botones ciegos → una fila que dice cómo está, visible para todos |
| [`Api.kt`](../../../protocol/src/main/kotlin/com/wtfuck/protocol/Api.kt) | `ConversacionResumen.temporalesSegundos`; `DuracionMensaje` (opciones, rango, `texto`) |
| [`Mensajes.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Mensajes.kt) | Autorización por tipo de chat; emite el aviso |
| [`Repo.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Repo.kt) | `resumen()` devuelve el temporizador; `Triple` → `MetaConv` |
| [`Main.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Main.kt) | La ruta despacha los avisos |
| [`V42__aviso_de_temporizador.sql`](../../../server/src/main/resources/db/V42__aviso_de_temporizador.sql) | **Nueva.** El tipo de evento en el `CHECK` |

## Lo verificado

| Qué | Cómo | Resultado |
|---|---|---|
| La cuenta del vencimiento | `TemporalesTest`, 13 pruebas | ✅ incluidos el tope del reloj, el desborde del plazo de 90 días y el mensaje que llega tarde |
| El contrato completo | `pruebas/temporales.mjs`, 49 comprobaciones | ✅ contra servidor + Postgres reales |
| Sin regresiones | 38 suites de integración | ✅ **1580 pasan, 0 fallan** |
| Suite del servidor | `:server:test` | ✅ 87 pruebas |
| Suite del app | `:app:testDebugUnitTest` | ✅ |

La comprobación clave, la que cubre el defecto original:

```
=== el temporizador por mensaje se puede LEER ===
  PASA  se puede encender en una directa
  PASA  y vuelve en MI listado
  PASA  y en el del OTRO, que es quien tiene que borrar su copia
```

## Lo que NO está verificado

El borrado **on-device**: que pasado el plazo el mensaje desaparezca de verdad
de los dos teléfonos. Requiere dos aparatos y esperar el plazo, y el emulador de
esta máquina está corrupto (su `PackageManager` no lanza la app; ver la nota en
[`copia-seguridad`](../copia-seguridad/README.md)). Lo verificable sin
dispositivo sí está: el plazo llega a los dos lados, el cálculo es correcto y el
barrido (`borrarVencidos`) ya existía y ya se llamaba al abrir la app y cada
chat.

Queda pendiente probarlo con dos teléfonos reales y un plazo de 1 minuto.
