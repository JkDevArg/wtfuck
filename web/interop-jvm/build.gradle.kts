// W0 de la versión web: la libsignal del navegador contra la de la JVM.
//
// Solo tiene pruebas. Hace de "teléfono" con `libsignal-client`, que es la
// misma libsignal que la app Android (`libsignal-android`) con los nativos de
// escritorio, y habla con el WebAssembly del navegador a través de
// `web/interop/lado-web.mjs` en Node.
//
// Si el WebAssembly no está armado (`web/cripto/construir.sh`) o no hay Node,
// las pruebas se OMITEN con un aviso: no todo el que compila el proyecto tiene
// Rust instalado.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(project(":protocol"))
    testImplementation(libs.libsignal.client)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
}

tasks.test {
    systemProperty("wtfuck.web", rootProject.file("web").absolutePath)
    // El WebAssembly cambia sin que Gradle lo sepa: que no use un resultado viejo.
    inputs.files(rootProject.fileTree("web/cripto/pkg"))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
