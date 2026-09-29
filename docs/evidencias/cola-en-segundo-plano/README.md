# La cola de salida sale sin la app abierta

## El defecto

La cola de salida **ya era persistente** —filas `PENDIENTE` en la base— y eso
estaba bien: un mensaje escrito sin red no se pierde aunque se mate el proceso.

Lo que no había era **quién la vaciara después**. `despachar()` se llamaba en
tres sitios:

- al reconectar el socket,
- al mandar otro mensaje,
- al volver la app al primer plano.

Los tres tienen algo en común: **necesitan que la app esté viva**.

Así que el caso normal —"escribo en el ascensor, bloqueo el teléfono y me
olvido"— terminaba con el mensaje parado hasta que la persona volviera a abrir
la app. A veces horas. Y **sin ninguna señal**, porque en su pantalla el
mensaje ya estaba escrito.

## Por qué WorkManager

Porque hace falta justo lo que un hilo propio no puede dar:

- sobrevivir a que el sistema mate el proceso,
- esperar a que **vuelva la red** sin gastar batería sondeando,
- aguantar un reinicio del teléfono.

Doze también entra: un `delay()` dentro de una corrutina no corre con el
teléfono dormido, y una alarma exacta para mandar un mensaje de chat sería
abusar de un permiso que existe para despertadores.

Versión **2.12.0**, verificada en el repositorio, no puesta de memoria.

---

## El fallo que casi hace que todo esto fuera un no-op

Escrito el worker y compilando, quedaba un detalle que lo habría vaciado de
contenido entero:

> El transporte principal solo se declara disponible con el socket
> `CONECTADO`. **Con la app cerrada el socket no existe**: no lo ha abierto
> nadie.

Un worker que llamara a `despachar()` a secas no habría encontrado transporte,
habría vuelto en el acto sin mandar nada, habría pedido reintento, y habría
repetido la nada con espera creciente. **La función entera habría parecido
hecha y no habría hecho nada** — que es la peor clase de defecto, porque nadie
vuelve a mirarla.

Ahora el worker arranca el transporte y **espera** hasta 20 s a que abra antes
de despachar. Si no abre, pide reintento: hay red —el sistema lo garantizó con
la restricción— pero el servidor no responde, y eso es exactamente para lo que
sirve la espera creciente.

---

## Decisiones que podrían haber ido al revés

- **`ExistingWorkPolicy.KEEP`, no `REPLACE`.** Reemplazar **reinicia la
  espera**: con un mensaje nuevo cada pocos minutos el trabajo se pospondría
  una y otra vez y no llegaría a correr nunca. El clásico temporizador que se
  reinicia solo.
- **Trabajo único.** Sin eso, cada mensaje que no sale encolaría otro trabajo y
  al volver la red se despertarían veinte a la vez para lo mismo. Uno basta:
  vacía la cola entera.
- **Se ajusta en UN solo sitio**, al final de `despachar()`. Esa función tiene
  varios `return` tempranos —sin red, sin destinos, sin sesión de cifrado— y
  son justo los casos en que hace falta reintentar. Ponerlo en cada uno sería
  olvidarse en el próximo que se agregue.
- **Se cancela cuando la cola queda vacía.** Importa más de lo que parece: un
  trabajo programado sin nada que hacer despierta el teléfono para nada, y eso
  **no se nota nunca** — se nota semanas después, en la lista de apps que más
  batería gastan, y para entonces nadie lo relaciona.

## R8: verificado, no supuesto

`isMinifyEnabled = true` en release, y **WorkManager instancia los workers por
nombre, con reflexión**. R8 no ve ninguna llamada al constructor.

Si fallara, el síntoma sería que la cola nunca se vacía con la app cerrada,
**solo en release**, y sin ningún error visible.

Se añadió una regla `-keep` explícita y se comprobó pasando R8 de verdad:

```
seeds.txt:431   com.wtfuck.app.datos.ColaEnSegundoPlano$Repartidor
seeds.txt:7852  ...$Repartidor: Repartidor(android.content.Context, androidx.work.WorkerParameters)
```

De `usage.txt` solo desaparecen `$stable` y `<clinit>`, artefactos irrelevantes.

> Detalle que encaja: `proguard-rules.pro` ya conservaba
> `SourceFile,LineNumberTable` para que un informe de fallo de release no
> traiga nombres de una letra. Eso hace que el informe de cierres que se añadió
> justo antes traiga **líneas útiles también en release**.

---

## Y de paso: un agujero real en el código de recuperación

La suite falló en `una errata de un solo simbolo se detecta`. Parecía una
prueba inestable. **No lo era.**

17 bytes son 136 bits; 28 símbolos de base32 son 140. Sobran **4 bits de
relleno** en el último símbolo, que al generar se escriben a cero y que el
decodificador **no comprobaba**. Consecuencia: de los 31 símbolos equivocados
que se pueden escribir en la última posición, **15 producían los mismos 17
bytes**, pasaban el byte de control y el código se daba por bueno.

O sea: **casi la mitad de las erratas en el último carácter se aceptaban en
silencio**.

Arreglado validando que los bits de relleno sean cero. No rompe nada: los
códigos generados siempre los tuvieron a cero.

Y se subió el umbral de la prueba del 97% al 99%. El 97% fue un error: a ese
nivel **pasaba también con el defecto**, y por eso fallaba solo de vez en
cuando y parecía ruido. Un umbral flojo convierte un fallo en ruido.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`ColaEnSegundoPlano.kt`](../../../app/src/main/java/com/wtfuck/app/datos/ColaEnSegundoPlano.kt) | **Nuevo.** La decisión, el worker y el programado |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `hayPendientes()`; ajusta el reintento al final de `despachar()` |
| [`CodigoRecuperacion.kt`](../../../app/src/main/java/com/wtfuck/app/datos/CodigoRecuperacion.kt) | Valida los bits de relleno |
| [`proguard-rules.pro`](../../../app/proguard-rules.pro) | El `-keep` del worker |
| `libs.versions.toml` · `app/build.gradle.kts` | WorkManager 2.12.0 |

## Lo verificado

| Qué | Resultado |
|---|---|
| `ColaEnSegundoPlanoTest` | ✅ 3 pruebas |
| `CodigoRecuperacionTest` tras el arreglo | ✅ 15, **tres corridas seguidas** al 99% |
| Suite unitaria del app | ✅ **454 pruebas, 0 fallos** |
| R8 sobre release | ✅ el worker sobrevive (evidencia en `seeds.txt`) |

## Lo que NO está verificado

- **No se ha ejecutado el worker en un teléfono.** Lo que falta ver: escribir
  sin red, matar la app, devolver la red, y comprobar que el mensaje sale solo.
  Eso pide un dispositivo y el emulador de esta máquina sigue corrupto.
- **Los 20 s de espera al socket son un número elegido, no medido.** Da de
  sobra en una conexión móvil lenta y no agota el presupuesto del sistema,
  pero no se ha perfilado contra una red mala de verdad.
- **No hay `NetworkCallback`.** Con la app viva y la red volviendo, la
  reconexión la cubre el backoff del socket, que tiene techo de 30 s. Se puede
  hacer instantáneo, pero era ampliar el alcance de este cambio sin arreglar
  el defecto que lo motivó.
