# La llamada que mataba la app, y por que R8

## El sintoma

Llamar cerraba la app **de golpe**: sin dialogo de error, sin informe, sin
nada. Solo en el APK publicado; en desarrollo funcionaba.

El servidor no veia ningun fallo:

```
04:59:56.360  POST /v1/llamadas -> 200 OK
04:59:56.960  @jcenturion desconectado      <- 600 ms despues
```

La sospecha inicial -del usuario y mia- era la diferencia de version con la
otra persona. **Era falsa.**

## Por que el informe de fallos no lo atrapo

Porque no era una excepcion de Java. El manejador de
`setDefaultUncaughtExceptionHandler` solo ve excepciones; esto era un abort
nativo, y ahi el proceso muere sin que nada de Java llegue a ejecutarse.

Lo dijo el buffer de fallos del sistema:

```
signal 6 (SIGABRT)
Abort message: 'JNI DETECTED ERROR IN APPLICATION: java_class == null'
  #05 art::JNI::GetStaticMethodID(...)
  #06 libjingle_peerconnection_so.so
```

## La causa

WebRTC moderno usa **JNI Zero**, el generador de enlaces JNI de Chromium, y
sus clases viven en **`org.jni_zero`** — no en `org.webrtc`.

El codigo nativo las busca **por nombre**, con `FindClass`. R8 no ve esas
referencias porque estan en C++ y no en bytecode, asi que se llevo el paquete
entero. `FindClass` devolvio null, nadie lo comprobo, y `GetStaticMethodID`
aborto el proceso.

El `usage.txt` de la compilacion lo decia sin ambiguedad:

| | antes | despues |
|---|---|---|
| clases `org.jni_zero` eliminadas | **22** | 2 |
| conservadas | **0** | 54 |

La regla `-keep class org.webrtc.**` estaba bien escrita y con buenos
comentarios. Simplemente **no alcanzaba**: el paquete que importaba era otro.

## Como se separo R8 del resto

El fallo solo pasaba en release, y en release cambian DOS cosas a la vez
-minificacion y ABI- ademas del aparato. Para separarlos se anadio
`-Pminify=false`, que compila release **sin R8 pero con la misma clave**: se
instala encima sin perder los datos y se compara de verdad.

| Build | Minify | Llamada |
|---|---|---|
| release publicado | si | aborta a los 600 ms |
| release con `-Pminify=false` | no | **aguanta, 10 s+** |
| debug (emulador) | no | aguanta |

Mismo telefono, mismo ABI, misma persona. La unica variable era R8.

## El arreglo

```proguard
-dontwarn org.jni_zero.**
-keep class org.jni_zero.** { *; }
-keepclasseswithmembers class * { @org.jni_zero.CalledByNative <methods>; }
-keepclasseswithmembers class * { @org.jni_zero.CalledByNativeUnchecked <methods>; }
-keepclassmembers class * { @org.jni_zero.AccessedByNative <fields>; }
```

El `-dontwarn` hace falta: `org.jni_zero.JniZeroJni` la genera el procesador
de anotaciones y **no viene en el AAR**. Antes no se notaba porque R8 borraba
el paquete entero; al conservarlo, R8 avisa de la referencia rota. No hace
falta esa clase — WebRTC trae el `.so` ya compilado.

Y las tres reglas de anotaciones no son de mas: `@CalledByNative` marca
metodos que el nativo **invoca de vuelta**, en clases que pueden estar fuera
de `org.jni_zero`. Un metodo de callback renombrado no falla con un abort:
simplemente no se llama nunca, y el sintoma seria una llamada que se queda
"conectando" para siempre. Peor de diagnosticar que este.

## Verificado en el telefono

Release **minificado**, con la regla, en el mismo aparato y llamando a la
misma persona: **15 segundos en llamada sin abort**, y el buffer de fallos
vacio.

El APK sigue pesando 26,5 MB: conservar 54 clases no engorda nada.

## Lo que queda anotado

- **El informe de fallos tiene un punto ciego** y este caso lo demostro: no
  atrapa fallos nativos. Ya se cerro leyendo `ApplicationExitInfo` al
  arrancar, que si registra los abort nativos y los ANR. A partir de la
  proxima version, un cierre asi deja informe.
- **Nunca se probo una llamada en release antes de publicar.** Todas las
  pruebas de llamadas eran en debug, donde R8 no corre. Cualquier cosa que
  dependa de JNI o de reflexion puede romperse SOLO en release, y ahi ninguna
  prueba automatica llega.
