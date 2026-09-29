# Informe de cierres inesperados

## El agujero

La app se reparte **fuera de una tienda**. Hoy, si se cierra sola en el
teléfono de alguien, **no queda ningún rastro**: ni traza, ni contador, ni
fecha. La persona dice "se me cerró" y ahí acaba la información.

Con un historial cifrado que solo vive en el teléfono, un cierre en el sitio
equivocado puede además perder cosas — y no habría forma de saber qué pasó.

Un barrido de `setDefaultUncaughtExceptionHandler|Crashlytics` sobre
`app/src/main` devolvía **cero**.

---

## Por qué no es Crashlytics

Porque manda las trazas a Google, y esta es una app cuya premisa entera es que
nada sale del teléfono sin querer. **Un mensajero privado que instala
telemetría de terceros se contradice a sí mismo** — y la traza es exactamente
el sitio por donde se cuela lo que no debe.

Así que el informe se guarda en el teléfono y **solo sale si la persona
decide**, habiéndolo podido leer entero antes.

---

## El problema de verdad: que no lleve contenido

Las trazas son nombres de clase, método y línea. Eso no es contenido de nadie.

El peligro está en el **mensaje de la excepción**, donde sí se cuela lo que la
aplicación estaba manejando: un `IllegalArgumentException` al parsear puede
traerse el texto entero del mensaje que falló.

Y esto **falla en silencio**: nadie revisa un informe de fallo buscando datos
personales. Lo descubriría la persona a la que se lo pegan en un grupo.

`Fallos.sanear` tacha antes de escribir nada. La regla: dejar pasar lo que
ayuda a encontrar el fallo, tachar lo que solo puede ser dato.

| Se tacha | Por qué |
|---|---|
| Números de teléfono | Va primero: es corto y se escaparía de las demás reglas |
| Base64 de 40+ | Claves, cuerpos de sobre, miniaturas — el material más sensible viaja así |
| Correos | Obvio |
| Rutas con nombre | El nombre del adjunto lo eligió quien lo mandó: **ya es contenido** |
| Comillas de 30+ | Casi siempre un valor interpolado, casi nunca necesario |
| Todo lo que pase de 200 | Da de sobra para "no se pudo abrir X", se queda corto para un mensaje de chat |

**Ante la duda, se tacha.** Un informe algo peor es mucho mejor que uno que
filtra una conversación. Y lo que ayuda a depurar sí sobrevive — hay una
prueba que lo fija (`lo que ayuda a depurar SI sobrevive`), porque un filtro
que tacha todo hace el informe inútil y entonces da igual tenerlo.

### Lo que tampoco va: el username

Tentaba, porque ayuda a preguntar. Pero entonces el informe deja de ser anónimo
y **quien lo comparta en un grupo estaría diciendo quién es**. Si hace falta
saberlo, lo dice la persona al mandarlo.

---

## Escribir desde un proceso que se está muriendo

El manejador corre en un proceso que ya se muere, en el hilo que lanzó la
excepción. Casi nada de lo normal es seguro:

- **Nada de corrutinas.** El ámbito puede estar cancelado, y el proceso no va a
  vivir para verlas terminar.
- **Nada de base de datos.** SQLCipher puede ser justo lo que reventó, y abrirla
  podría lanzar *dentro* del manejador — la forma de perder el informe **y** el
  aviso del sistema a la vez.
- **Nada de red.** Ni con tiempo límite: bloquea el cierre y el sistema mata el
  proceso antes de escribir nada.

Queda una escritura de archivo, síncrona y corta. Es poco, y es exactamente lo
que hace falta.

### Se encadena al manejador anterior

Sin eso la app **dejaría de cerrarse como debe**: no saldría el aviso del
sistema, y en algunos aparatos el proceso quedaría colgado. **Un manejador que
se traga el cierre es peor que no tener manejador.** Hay una prueba dedicada.

### Un archivo por informe

Escribir al final de un archivo compartido, desde un proceso que se muere, es
la forma de acabar con un archivo a medio escribir que ya no se puede leer — y
entonces se pierden también los informes anteriores, que estaban bien.

### La poda va al escribir, no al leer

Leer puede no pasar nunca: quien no abra la pantalla acumularía un archivo por
cada cierre hasta llenar el teléfono. Es **la fuga que solo le pasa a quien más
problemas tiene**, que es justo a quien no hay que darle otro.

---

## El aviso sale una vez

Al descartarlo se borran los informes. Un aviso que vuelve en cada arranque se
aprende a cerrar sin leer, y entonces el siguiente —el que sí importa— tampoco
se lee.

Va **después** del aviso de actualización: si la app se cerró por un fallo que
la versión nueva ya arregla, lo útil es actualizar, no mandar el informe.

Y el informe **se puede leer entero antes de mandarlo**. No es transparencia
decorativa: es la única forma de que la promesa de "no lleva tus mensajes" se
pueda comprobar en vez de creer. Un filtro que nadie puede auditar es un filtro
en el que hay que confiar a ciegas.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`Fallos.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Fallos.kt) | **Nuevo.** Saneado, formato y poda. Puro y probado |
| [`CazadorDeFallos.kt`](../../../app/src/main/java/com/wtfuck/app/datos/CazadorDeFallos.kt) | **Nuevo.** El manejador y el almacén en disco |
| [`AvisoDeFallo.kt`](../../../app/src/main/java/com/wtfuck/app/ui/AvisoDeFallo.kt) | **Nuevo.** Ver, compartir, descartar |
| [`WtfuckApp.kt`](../../../app/src/main/java/com/wtfuck/app/WtfuckApp.kt) | Lo instala lo primero de `onCreate` |
| [`MainActivity.kt`](../../../app/src/main/java/com/wtfuck/app/MainActivity.kt) | Muestra el aviso |

## Lo verificado

| Qué | Resultado |
|---|---|
| `FallosTest` | ✅ **19 pruebas** |
| Suite unitaria del app | ✅ **451 pruebas, 0 fallos** |

Las que cubren fallos silenciosos:

```
PASA  un mensaje de chat largo no sobrevive entero
PASA  base64 largo se tacha
PASA  una ruta con nombre de archivo se tacha
PASA  el manejador no se traga el cierre
PASA  una cadena de causas circular no cuelga
PASA  solo se conservan los ultimos
```

La de la cadena circular no es teórica: pasa con algunas bibliotecas, y sin el
control de vistos sería un bucle infinito **dentro del manejador de cierres** —
la app no llegaría ni a morirse bien.

## Lo que NO está verificado

- **No se ha provocado un cierre real en un teléfono.** La lógica está probada
  con excepciones construidas a mano; lo que no se ha visto es el ciclo
  completo: reventar de verdad, reabrir, y que salga el aviso.
- **El saneado es una lista de reglas, no una garantía.** Tacha lo que se supo
  anticipar. Un mensaje de excepción con contenido en un formato que no está
  en la lista pasaría — por eso además se recorta a 200 caracteres y por eso la
  persona puede leerlo antes de mandarlo. Son tres defensas porque ninguna
  basta sola.
