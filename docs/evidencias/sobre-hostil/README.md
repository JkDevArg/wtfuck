# Un sobre lo escribió otra persona · módulo AS

El servidor no puede abrir un sobre, así que **no valida nada de lo que hay
dentro**. El único filtro es quién puede escribir en esa conversación, y eso
no protege de un participante hostil: alguien con un cliente modificado manda
exactamente los bytes que quiera a cualquiera de sus chats.

Así que la pregunta no es si el contenido raro se dibuja bien —eso ya lo
cubrían 55 pruebas desde el módulo M— sino algo más básico: **qué pasa cuando
algo salta**.

## El defecto: una excepción mataba el canal de entrada

```kotlin
launch { socket.entrantes.collect { manejar(it) } }
```

Sin `try`. Si `manejar` lanzaba, el `collect` terminaba y con él **todo el
canal de entrada**: no llegaba ni un mensaje más, ni una llamada, ni un acuse,
hasta que alguien reiniciara la app.

Y el síntoma es **silencio**. No hay error, no hay aviso, no hay reintento:
simplemente deja de llegar. La persona cree que nadie le escribe.

Con el bucle desprotegido eso es una negación de servicio contra alguien
concreto, y barata: un solo sobre bien elegido, mandado por cualquiera que
comparta un chat.

### Y no era el único

Buscar el mismo patrón dio dos más, y el segundo es **más probable que el
hostil**:

```kotlin
socket.conectado.collect { cargarMiPerfil(); sincronizar(); despachar() }
```

Las tres llamadas salen a la red. Un 500 pasajero del servidor, o un cuerpo
que no parsea, mataba el `collect` — y entonces la app no volvía a sincronizar
ni a vaciar su cola de salida en lo que durara el proceso. **Los mensajes
escritos sin red se quedaban sin salir para siempre**, que es justo lo que el
modo offline vino a evitar. No hace falta ningún atacante.

El tercero, en el servicio de llamadas: si la notificación fallaba, el
servicio no se enteraba nunca de que la llamada había terminado y quedaba una
notificación de "Llamada" puesta para siempre, sin llamada.

Los tres van ahora con `runCatching`. **`runCatching` y no
`catch (e: Exception)`**: un JSON muy anidado tira `StackOverflowError`, que
es un `Error` y no una `Exception`. Hay una prueba que fija justo eso, porque
escribir `catch (e: Exception)` es lo natural y dejaría el caso fuera.

## El fuzzer

Mil cargas construidas para romper —sustitutos UTF-16 sueltos, overrides de
dirección, cadenas de 200.000 caracteres, emojis compuestos, listas de 30
opciones, `Long.MIN_VALUE`, dobles extremos— pasadas por el viaje completo:
serializar, rellenar, quitar relleno, parsear, y sanear para dibujar. Ninguna
puede hacer saltar nada.

Más JSON directamente roto: vacío, `{`, `null`, `[]`, discriminadores
inventados, campos con el tipo cambiado, y 2.000 corchetes anidados.

**Semilla fija.** Un fuzzer que sortea entradas distintas en cada corrida falla
una vez de cada cien en el ordenador de otra persona y nadie consigue
reproducirlo. Con semilla fija, o falla siempre o no falla — y cuando
encuentre algo, se podrá volver a ver.

## Dos cosas que encontró el fuzzer, y ninguna era la esperada

**1. Una defensa que ya estaba y nadie sabía.** El fuzzer no llegó a
ejecutarse: falló *construyendo* el caso.

```
JsonEncodingException: Unexpected special floating-point value NaN
```

`jsonApp` no admite valores especiales de punto flotante, así que una posición
con `NaN` **no se puede ni serializar**: no puede viajar ni guardarse. Estaba
ahí por defecto, sin que ninguna decisión lo dijera.

Ahora hay una prueba que lo fija, porque se pierde con una línea: basta que
alguien ponga `allowSpecialFloatingPointValues = true` —lo natural de hacer
cuando algo "no serializa"— para que un `NaN` empiece a escribirse en
`especialJson` en la base de todo el mundo. Al dibujar se rechazaría igual,
así que nadie lo vería hasta mucho después.

**2. Una suposición mía equivocada.** El fuzzer daba la cadena vacía por clase
inválida. No lo es: `ClaseContenido.TEXTO` **es** `""`. El código estaba bien
y la prueba estaba mal.

---

## Lo que esto no cubre

El fuzzer trabaja sobre el contenido ya descifrado y sobre el saneado, que es
lo que se puede ejercitar en la JVM. Lo que queda fuera:

- **El SDP de una llamada.** Llega dentro de un sobre y lo parsea WebRTC, que
  es código nativo. Un SDP hostil es la superficie más interesante que sigue
  sin fuzzear, y hace falta un emulador para tocarla.
- **Las miniaturas**, que ya tienen su propia defensa medida (`MiniaturaSegura`,
  14 pruebas) porque un PNG de pocos KB puede pedir gigabytes al decodificarse.

**1922 pruebas en verde**: 1523 de integración en 36 suites, 324 JUnit de app
y 75 de servidor.
