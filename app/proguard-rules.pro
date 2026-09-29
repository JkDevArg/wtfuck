# Reglas de R8 para la compilacion de release.
#
# El archivo estaba declarado en `build.gradle.kts` y no existia, asi que
# `assembleRelease` fallaba entero. O sea: la variante de release NUNCA se
# habia compilado. Eso se descubre el dia que hay que publicar, que es el peor
# dia posible para descubrirlo.
#
# Lo que sigue NO es una lista defensiva de "por si acaso": cada bloque tapa un
# camino concreto por el que R8 borraria algo que nadie llama desde Kotlin pero
# que se usa igual — desde JNI, por reflexion o por un serializador generado.
# Esa es exactamente la clase de fallo que no aparece al compilar sino al
# ejecutar, y en release, que es donde menos se mira.


# ---------------------------------------------------------------
#  libsignal — el cifrado
# ---------------------------------------------------------------
#
# Su motor va en Rust y se comunica por JNI. El codigo nativo busca clases y
# metodos POR NOMBRE: si R8 los renombra, el enlace falla al arrancar y no al
# compilar. Y lo que se rompe es el cifrado, que es lo ultimo que uno quiere
# que falle de formas raras.
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** {
    native <methods>;
}
# Las excepciones viajan de Rust a Java construidas por nombre.
-keep class org.signal.libsignal.protocol.** { *; }

# ---------------------------------------------------------------
#  WebRTC — las llamadas
# ---------------------------------------------------------------
#
# Mismo caso y peor: ademas de JNI, el nativo INVOCA de vuelta a los oyentes de
# Java (observadores de SDP, de estado de conexion, de pista remota). Un
# metodo de callback renombrado no falla: simplemente no se llama nunca, y el
# sintoma es una llamada que se queda "conectando" para siempre.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** {
    native <methods>;
    *;
}

# Y `org.jni_zero`, que NO esta en org.webrtc y por eso se escapaba.
#
# ## Como se encontro, porque el sintoma no se parece a la causa
#
# Una llamada cerraba la app de golpe, SOLO en release, sin dialogo de error
# ni nada en el informe de fallos. No era una excepcion de Java sino un abort
# nativo:
#
#   signal 6 (SIGABRT)
#   Abort message: 'JNI DETECTED ERROR IN APPLICATION: java_class == null'
#     #05 art::JNI::GetStaticMethodID(...)
#     #06 libjingle_peerconnection_so.so
#
# WebRTC moderno usa JNI Zero -el generador de enlaces JNI de Chromium- y sus
# clases viven en `org.jni_zero`. El codigo nativo las busca POR NOMBRE con
# FindClass; R8 no ve esas referencias porque estan en C++ y no en bytecode,
# asi que se llevo las 22 clases del paquete. FindClass devolvio null, nadie
# lo comprobo, y GetStaticMethodID aborto el proceso.
#
# El `usage.txt` de la compilacion lo decia: 22 clases de org.jni_zero
# eliminadas, 0 conservadas. La regla de org.webrtc.** estaba bien y no
# alcanzaba.
#
# Confirmado con el experimento: el MISMO release compilado con
# `-Pminify=false` aguanta la llamada sin morir en el mismo telefono.
# `JniZeroJni` la genera el procesador de anotaciones de JNI Zero y NO viene
# en el AAR: se referencia y no existe. Antes no se notaba porque R8 borraba
# el paquete entero; al conservarlo, R8 avisa de la referencia rota. No hace
# falta: WebRTC trae el .so ya compilado y no usa la generacion del lado Java.
-dontwarn org.jni_zero.**

-keep class org.jni_zero.** { *; }
-keepclasseswithmembers class * {
    @org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers class * {
    @org.jni_zero.CalledByNativeUnchecked <methods>;
}
-keepclassmembers class * {
    @org.jni_zero.AccessedByNative <fields>;
}

# ---------------------------------------------------------------
#  kotlinx.serialization — el protocolo entero
# ---------------------------------------------------------------
#
# La biblioteca trae sus propias reglas, pero no cubren un caso que aqui es
# central: las clases `@Serializable` del modulo `protocol` se resuelven por
# `Companion.serializer()`, y las SELLADAS ademas por su `@SerialName`, que es
# una cadena. R8 no ve ninguna de las dos cosas como un uso.
#
# Si esto falla, lo que se rompe es leer los sobres: todo el contenido con
# estructura —ubicaciones, encuestas, resumenes de llamada— deja de entenderse.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wtfuck.protocol.**$$serializer { *; }
-keepclassmembers class com.wtfuck.protocol.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.wtfuck.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Las jerarquias selladas se eligen por el nombre del tipo dentro del JSON.
-keep class com.wtfuck.protocol.Carga { *; }
-keep class com.wtfuck.protocol.Carga$* { *; }
-keep class com.wtfuck.protocol.Subida$* { *; }
-keep class com.wtfuck.protocol.Bajada$* { *; }
-keep class com.wtfuck.protocol.MensajeCerca$* { *; }

# ---------------------------------------------------------------
#  Room — la base local
# ---------------------------------------------------------------
#
# Room genera implementaciones y las busca por nombre al abrir la base. Trae
# reglas propias; esto cubre las entidades, que se mapean por campo.
-keep class com.wtfuck.app.datos.*Ent { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------
#  SQLCipher — la base local va cifrada
# ---------------------------------------------------------------
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# ---------------------------------------------------------------
#  Ktor y OkHttp — el cliente
# ---------------------------------------------------------------
#
# Ktor elige el motor por ServiceLoader, o sea por un archivo de recursos y no
# por una referencia en el codigo.
-keep class io.ktor.client.engine.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
# OkHttp declara APIs que solo existen en la JVM de escritorio.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------
#  Coil — las imagenes
# ---------------------------------------------------------------
-dontwarn coil3.**

# ---------------------------------------------------------------
#  Lo que ayuda cuando algo falla igual
# ---------------------------------------------------------------
#
# Sin esto, un informe de fallo de release trae nombres de una letra y no dice
# nada. `SourceFile` se renombra a una constante para no filtrar rutas.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- WorkManager -----------------------------------------------------
#
# WorkManager instancia los workers POR NOMBRE, con reflexion. R8 no ve
# ninguna llamada al constructor y puede renombrarlo o quitarlo entero.
#
# La biblioteca trae sus propias reglas y probablemente baste, pero esto se
# deja explicito a proposito: si fallara, el sintoma seria que la cola de
# salida nunca se vacia con la app cerrada, SOLO en release, y sin ningun
# error visible. Una linea contra un fallo que solo aparece en produccion y
# no se parece a su causa.
-keep class com.wtfuck.app.datos.ColaEnSegundoPlano$Repartidor { <init>(...); }
