import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.wtfuck.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    // El NDK existe SOLO para strippear las bibliotecas nativas en release.
    //
    // No compilamos nada en C/C++: las .so vienen de libsignal, WebRTC y
    // SQLCipher. Pero libsignal publica `libsignal_jni.so` con simbolos de
    // depuracion —70 MB en arm64, 65 en armeabi— y sin NDK, AGP no tiene con
    // que quitarlos: deja las .so tal cual y solo AVISA. Ese aviso se perdio
    // entre el ruido del build, y el APK salia a 162 MB, casi todo simbolos
    // que ningun telefono usa.
    //
    // Con esto presente, la tarea `stripReleaseDebugSymbols` corre y las deja
    // en una fraccion. Es el mismo patron que ya mordio en este proyecto: el
    // build "funciona" y se salta un paso en silencio.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.wtfuck.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // La version, que se puede subir sin editar este archivo:
        //
        //   ./gradlew :app:assembleRelease -PversionCode=3 -PversionName=0.3.0
        //
        // Los nombres NO son `-Pversion` y `-PversionNombre`, que es lo que se
        // escribio primero: `version` ya es una propiedad de Gradle -vale
        // "unspecified" si nadie la pone- asi que `findProperty("version")`
        // devuelve eso y no lo que uno cree. Funcionaba de casualidad, porque
        // "unspecified" no es un numero y caia al valor por defecto; el dia
        // que algo pusiera un objeto de version ahi, el cast reventaria la
        // compilacion con un error que no menciona ninguna de estas lineas.
        //
        // ## Por que esto importa mas de lo que parece
        //
        // `versionCode` estuvo clavado en 1 durante todo el desarrollo, y con
        // eso NINGUNA actualizacion funciona: Android compara este numero para
        // decidir si un APK es mas nuevo que el instalado, y dos builds con el
        // mismo numero son la misma version para el sistema. El instalador no
        // se queja de nada raro — simplemente no actualiza, o pide desinstalar
        // primero, que en una app de mensajeria significa perder el historial.
        //
        // Tiene que subir en CADA publicacion y no puede bajar nunca. Es lo
        // unico que el telefono mira para no dejarse poner una version vieja.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String) ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cert pinning, OPT-IN. Vacio = sin pinning (validacion de CA normal).
        // Se activa al compilar, con los pines separados por coma:
        //   -Ppin=<pin-leaf>,<pin-respaldo>
        // Va en defaultConfig para que valga en debug y release; en debug se
        // deja vacio salvo que se pase a proposito (localhost no tiene cert que
        // pinear). Ver datos/Pinning.kt y el porque es opt-in (riesgo de brick).
        val pines = (project.findProperty("pin") as? String).orEmpty().replace("\"", "")
        buildConfigField("String", "PIN_HASHES", "\"$pines\"")

    }

    /**
     * La firma de release, desde un archivo que NO esta en el repositorio.
     *
     * Un APK sin firmar no se instala en ningun telefono, asi que sin esto la
     * variante de release solo sirve para comprobar que compila.
     *
     * `keystore.properties` y el `.jks` van fuera de git a proposito. La clave
     * de firma es lo que demuestra que una actualizacion viene de quien hizo
     * la app: quien la tenga puede publicar una version modificada que los
     * telefonos aceptan como legitima. Y **no se puede rotar**: Android
     * rechaza una actualizacion firmada con otra clave, asi que perderla
     * obliga a publicar la app como si fuera otra y a que todo el mundo la
     * reinstale a mano.
     *
     * Si el archivo no esta, la release se compila SIN firmar en vez de
     * fallar: asi se puede verificar que R8 no rompio nada sin tener las
     * llaves a mano.
     */
    val firma = rootProject.file("keystore.properties")
    val datosFirma = Properties().apply {
        if (firma.exists()) firma.inputStream().use { load(it) }
    }

    signingConfigs {
        if (firma.exists()) {
            create("publicacion") {
                storeFile = rootProject.file(datosFirma.getProperty("storeFile"))
                storePassword = datosFirma.getProperty("storePassword")
                keyAlias = datosFirma.getProperty("keyAlias")
                keyPassword = datosFirma.getProperty("keyPassword")
                // v2 y v3 ademas de v1: v1 sola la rechazan los Android
                // nuevos, y v2 sola no permite rotar la clave mas adelante.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Solo el ABI del emulador. libsignal trae su motor en Rust y cada
            // biblioteca nativa pesa unos 70 MB sin recortar: incluir los tres
            // ABIs en desarrollo son 200 MB de APK que se instalan en cada
            // vuelta por nada. El de release si lleva los de telefono.
            //
            // Para probar en un TELEFONO de verdad hay que pedirlo:
            //
            //   ./gradlew :app:assembleDebug -Pabi=arm64-v8a
            //
            // Sin eso el APK de depuracion no tiene una sola biblioteca que
            // sirva en un telefono, y el instalador lo rechaza con
            // INSTALL_FAILED_NO_MATCHING_ABIS. Falla temprano y con un nombre
            // claro, que es lo mejor que puede pasar: instalarlo y reventar al
            // arrancar cuando libsignal no encuentra su motor seria peor.
            val abiPedido = (project.findProperty("abi") as String?) ?: "x86_64"
            ndk { abiFilters += abiPedido.split(",").map { it.trim() } }

            // Se usa 127.0.0.1 + `adb reverse tcp:8088 tcp:8088`, NO 10.0.2.2.
            // Motivo verificado: el AVD de API 37 tiene eth0 y wlan0 en la misma
            // subred y el alias 10.0.2.2 del host da SocketTimeout desde la app
            // (aunque ping y nc desde el shell si pasen). El tunel de adb es
            // inmune a esa diferencia y funciona igual en todos los AVDs.
            //   adb -s <serial> reverse tcp:8088 tcp:8088
            // Por defecto el debug habla con el servidor local por el tunel de
            // adb. Pero con `-Papi=` se le puede apuntar a un servidor de
            // verdad —util para probar contra produccion desde un emulador—:
            //
            //   ./gradlew :app:assembleDebug -Papi=apiwtf.hackl4bs.com
            //
            // Ahi pasa a https/wss, porque un servidor real no habla en claro.
            // OJO: esto NO saltea el device-binding. El emulador solo acredita
            // hardware "de software", y un servidor de produccion con
            // WTFUCK_PERMITIR_SOFTWARE_DEV=false rechaza el registro igual. Solo
            // sirve para VER que conecta, no para registrarse contra prod.
            val apiDebug = project.findProperty("api") as? String
            if (apiDebug.isNullOrBlank()) {
                buildConfigField("String", "SERVIDOR", "\"http://127.0.0.1:8088\"")
                buildConfigField("String", "SERVIDOR_WS", "\"ws://127.0.0.1:8088\"")
            } else {
                buildConfigField("String", "SERVIDOR", "\"https://$apiDebug\"")
                buildConfigField("String", "SERVIDOR_WS", "\"wss://$apiDebug\"")
            }
            // El emulador no tiene TEE: sin esto no se podria probar nada.
            // De donde salen las baldosas del mapa.
            //
            // Es configuracion y no una constante en la pantalla porque es el
            // unico tercero al que esta app le habla, y quien la despliegue
            // tiene que poder cambiarlo por su propio servidor sin tocar
            // codigo. Vacio apaga el mapa entero: la burbuja se queda con la
            // estela y no se le pide una imagen a nadie.
            //
            // El default es OSM, que es gratis y publico. Su politica de uso
            // exige un User-Agent que identifique a la app (ver TeselaCliente)
            // y no sirve para trafico pesado: un despliegue de verdad va con
            // baldosas propias o pagas.
            buildConfigField(
                "String", "MAPA_BALDOSAS",
                "\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"",
            )
            buildConfigField("boolean", "PERMITIR_SOFTWARE_DEV", "true")
        }
        release {
            // Los dos ABIs que existen en telefonos reales. x86 de 32 bits no
            // existe en telefonos y x86_64 solo en emuladores.
            //
            // Se puede pedir otro —`-Pabi=x86_64`— y hace falta: un release
            // que compila y revienta al arrancar es peor que uno que no
            // compila, y la unica forma de comprobar que R8 no borro nada que
            // el codigo nativo busca por nombre es EJECUTARLO. Sin esto, la
            // primera ejecucion de una release seria en el telefono de
            // alguien.
            val abiRelease = (project.findProperty("abi") as String?)
                ?: "arm64-v8a,armeabi-v7a"
            ndk { abiFilters += abiRelease.split(",").map { it.trim() } }
            // Se puede APAGAR para diagnosticar, sin tocar este archivo:
            //
            //   ./gradlew :app:assembleRelease -Pminify=false
            //
            // Existe porque un fallo que solo pasa en release deja dos
            // sospechosos pegados -R8 y el aparato- y no hay forma de
            // separarlos si no se puede compilar release sin R8. Un APK asi
            // se firma con la MISMA clave, asi que se instala encima sin
            // perder los datos y se compara de verdad.
            //
            // No es para publicar: sin minificar el APK pesa mucho mas y se
            // va con los nombres originales.
            isMinifyEnabled = (project.findProperty("minify") as String?) != "false"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (firma.exists()) signingConfig = signingConfigs.getByName("publicacion")

            // El dominio se puede cambiar sin tocar este archivo:
            //
            //   ./gradlew :app:assembleRelease -Papi=api.miempresa.com
            //
            // Hace falta porque quien despliegue esto no va a usar el dominio
            // de aqui, y obligarle a editar el build para cambiar un nombre es
            // como se acaba con un repositorio lleno de cambios locales que
            // nadie puede fusionar.
            val api = (project.findProperty("api") as String?) ?: "api.wtfuck.com"
            buildConfigField("String", "SERVIDOR", "\"https://$api\"")
            buildConfigField("String", "SERVIDOR_WS", "\"wss://$api\"")
            buildConfigField(
                "String", "MAPA_BALDOSAS",
                "\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"",
            )
            buildConfigField("boolean", "PERMITIR_SOFTWARE_DEV", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * Desugaring de la biblioteca base.
     *
     * Lo exige libsignal: su API usa `java.time.Instant`, que en Android solo
     * existe desde API 26 en algunas partes y desde API 33 en otras. El
     * desugaring reescribe esas clases dentro del APK, y es lo que permite
     * mantener minSdk 26 con E2EE en vez de subir el minimo y dejar afuera
     * telefonos que si pueden correr la app.
     */
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

        /**
         * Lo que NO tiene que viajar en el APK.
         *
         * libsignal publica un solo artefacto con todo adentro y sin esto el
         * APK pasaba de 500 MB. Se sacan dos cosas que no se usan nunca:
         *
         *  - `libsignal_jni_testing`: la biblioteca de pruebas de la propia
         *    libsignal. Son 229 MB entre los tres ABIs y la app no la invoca.
         *  - los binarios de ESCRITORIO (.dylib de macOS, .dll de Windows) que
         *    vienen como recursos para el artefacto de JVM. Otros 121 MB que en
         *    Android no se pueden ni cargar.
         */
        jniLibs.excludes += setOf("**/libsignal_jni_testing.so")
        resources.excludes += setOf(
            "**/libsignal_jni*.dylib",
            "**/signal_jni*.dll",
            "**/libsignal_jni*_amd64.so",
            "**/libsignal_jni*_aarch64.so",
        )
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform(libs.compose.bom))
    // WorkManager: reintentar la cola de salida cuando vuelva la red, aunque
    // la app este cerrada. Ver `ColaEnSegundoPlano`.
    implementation(libs.androidx.work)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.gif)

    // Modulo K. WebRTC de verdad: el medio va cifrado con DTLS-SRTP y la
    // negociacion la hace esta libreria. La SEÑALIZACION no pasa por aqui: va
    // en sobres cifrados, porque el SDP lleva las huellas DTLS y un servidor
    // que pudiera cambiarlas podria escuchar la llamada.
    implementation(libs.webrtc)
    implementation(libs.exifinterface)
    implementation(libs.biometric)

    // Modulo N: avisos con la app CERRADA.
    //
    // Se agrega el SDK pero NO el plugin `google-services`: la configuracion
    // del proyecto la sirve nuestro servidor y Firebase se inicializa a mano
    // (ver datos/Push.kt). Asi el push se habilita poniendo variables en el
    // servidor, sin recompilar ni publicar una version nueva, y no hace falta
    // un `google-services.json` por entorno dentro del repositorio.
    implementation(libs.firebase.messaging)

    // Modulo E: E2EE. El artefacto -android trae los .so nativos por ABI.
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Solo el nucleo de ZXing, para DIBUJAR el QR de la huella. No trae camara
    // ni actividades: son unos 500 KB y se usa una sola funcion.
    implementation(libs.zxing.core)
    // J.6: escanear el QR de vinculacion.
    //
    // CameraX + el nucleo de zxing, y NO una biblioteca de escaneo con
    // actividad propia: las que hay traen su pantalla, su tema y su forma de
    // pedir permisos, y en una app cuya promesa es que nada sale del aparato,
    // una dependencia que abre camara y red por su cuenta es justo lo que no
    // se quiere auditar. Tampoco ML Kit: exige Play Services y no funciona sin
    // Google.
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    // Las videonotas: grabar con la misma CameraX, sin otra biblioteca.
    implementation(libs.camera.video)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
}
