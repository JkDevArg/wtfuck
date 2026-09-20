plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.wtfuck.server.MainKt")
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.neg)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.bouncycastle)
    implementation(libs.logback)
    implementation(libs.minio)

    // Modulo N. Solo se usa si hay WTFUCK_REDIS_URL; sin eso el servidor
    // funciona igual y no abre ninguna conexion. Ver Bus.kt.
    implementation(libs.lettuce)
    // Correo. Solo lo usa el transporte SMTP real; en desarrollo no se toca.
    implementation(libs.angus.mail)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
}
