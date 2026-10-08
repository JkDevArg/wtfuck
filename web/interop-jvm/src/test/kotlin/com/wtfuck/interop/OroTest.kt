package com.wtfuck.interop

import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.MensajeHistorico
import com.wtfuck.protocol.Relleno
import com.wtfuck.protocol.mencionesEn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import kotlin.test.Test

/**
 * Las "pruebas de oro" de la web: Kotlin escribe los JSON de referencia en
 * `web/app/src/datos/oro/` y `protocolo.test.ts` exige que TypeScript los
 * produzca y los lea IGUAL, byte a byte.
 *
 * Si alguien cambia `Carga` en el contrato, este test reescribe los archivos,
 * el diff de git lo muestra y la prueba de la web falla hasta que se ajuste.
 * Mismos ajustes que la app: `jsonApp` (encodeDefaults, ignoreUnknownKeys).
 */
class OroTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(System.getProperty("wtfuck.web") ?: "web", "app/src/datos/oro").apply { mkdirs() }

    private fun escribir(nombre: String, contenido: String) = File(dir, nombre).writeText(contenido + "\n")

    @Test
    fun `escribe los JSON de referencia`() {
        val textos = listOf("hola", "con ñ, tildes y 🙂", "", "comillas \" y \\ barra\nsalto")
        escribir(
            "textos.json",
            json.encodeToString(
                ListSerializer(String.serializer()),
                textos.map { json.encodeToString(Carga.serializer(), Carga.Texto(cuerpo = it)) },
            ),
        )

        val conClave = Carga.ConClaveGrupo("U0tETQ==", Carga.Texto(cuerpo = "a todos"))
        escribir("con-clave.json", json.encodeToString(Carga.serializer(), conClave))

        val historial = Carga.Historial(
            conversacionId = "0b4a5f0e-5b8f-4f43-9a3d-6a1f2a6b7c8d",
            mensajes = listOf(
                MensajeHistorico(id = "m1", autor = "ana", esMio = false, texto = "uno", creadoEn = 1_759_900_000_000),
                MensajeHistorico(id = "m2", autor = "beto", esMio = true, texto = "dos", creadoEn = 1_759_900_000_001),
            ),
        )
        escribir("historial.json", json.encodeToString(Carga.serializer(), historial))

        // Relleno: largos de entrada -> largo de salida.
        val largos = listOf(0, 1, 255, 256, 257, 8192, 8193, 16384, 16385, 61440, 61441, 70000)
        escribir(
            "relleno.json",
            json.encodeToString(
                ListSerializer(ListSerializer(Int.serializer())),
                largos.map { listOf(it, Relleno.poner(ByteArray(it) { 1 }).size) },
            ),
        )

        val conMenciones = "Hola @Ana y @beto_2, y @ana otra vez; @no y @x_muy_largo_mas_de_veinticuatro_caracteres"
        escribir("menciones.json", json.encodeToString(ListSerializer(String.serializer()), mencionesEn(conMenciones)))

        // Un texto rellenado, en base64: lo que de verdad se cifra.
        val claro = Relleno.poner(json.encodeToString(Carga.serializer(), Carga.Texto(cuerpo = "hola")).toByteArray())
        escribir("texto-rellenado.b64", Base64.getEncoder().encodeToString(claro))
    }
}
