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

    defaultConfig {
        applicationId = "com.wtfuck.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
            buildConfigField("String", "SERVIDOR", "\"http://127.0.0.1:8088\"")
            buildConfigField("String", "SERVIDOR_WS", "\"ws://127.0.0.1:8088\"")
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
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SERVIDOR", "\"https://api.wtfuck.com\"")
            buildConfigField("String", "SERVIDOR_WS", "\"wss://api.wtfuck.com\"")
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
    implementation(libs.coroutines.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
}
