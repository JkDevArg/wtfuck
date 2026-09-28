package com.wtfuck.app

import com.wtfuck.app.datos.CodigoRecuperacion
import com.wtfuck.app.datos.CopiaSeguridad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * La identidad Signal dentro de la copia de seguridad.
 *
 * ## Por que esto se prueba y no se supone
 *
 * Porque el fallo es diferido y silencioso. Si el sellado no abre con el
 * codigo correcto, nadie se entera el dia que hace la copia: se entera el dia
 * que perdio el telefono, lo restaura, y a todos sus contactos les salta el
 * aviso de que su clave cambio — que es el momento exacto en que ya no hay
 * arreglo posible.
 *
 * La comprobacion que importa es [lo que se sella se abre con el mismo codigo].
 */
class IdentidadEnLaCopiaTest {

    private val azar = SecureRandom()

    private fun identidadDeMentira() = CopiaSeguridad.IdentidadClara(
        // 64 bytes: el tamano tipico de un IdentityKeyPair serializado. El
        // contenido da igual aqui; lo que se prueba es el sobre, no libsignal.
        parClavesB64 = java.util.Base64.getEncoder()
            .encodeToString(ByteArray(64).also { azar.nextBytes(it) }),
        registrationId = 4242,
        proximoPreKeyId = 137,
        proximoFirmadaId = 9,
        proximoKyberId = 5,
    )

    private fun codigoLimpio() = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!

    // --------------------------------------------------- ida y vuelta

    @Test
    fun `lo que se sella se abre con el mismo codigo`() {
        repeat(200) {
            val codigo = codigoLimpio()
            val clave = CodigoRecuperacion.claveDeIdentidad(codigo)
            val original = identidadDeMentira()

            val abierta = CopiaSeguridad.abrirIdentidad(
                CopiaSeguridad.sellarIdentidad(original, clave), clave,
            )
            assertEquals("no se recupero la identidad sellada", original, abierta)
        }
    }

    @Test
    fun `los contadores de prekey sobreviven al viaje`() {
        // No es un detalle: si volvieran a 1, las prekeys nuevas chocarian con
        // las que el servidor todavia tenga del aparato anterior.
        val codigo = codigoLimpio()
        val clave = CodigoRecuperacion.claveDeIdentidad(codigo)
        val original = identidadDeMentira()
        val abierta = CopiaSeguridad.abrirIdentidad(
            CopiaSeguridad.sellarIdentidad(original, clave), clave,
        )!!
        assertEquals(137, abierta.proximoPreKeyId)
        assertEquals(9, abierta.proximoFirmadaId)
        assertEquals(5, abierta.proximoKyberId)
        assertEquals(4242, abierta.registrationId)
    }

    @Test
    fun `escribir el codigo de otra forma tambien la abre`() {
        // El caso real: se sella con el codigo copiado de la pantalla y se
        // abre con el que la persona tecleo del papel, en minusculas y sin
        // guiones. Si esto fallara, la copia no abriria nunca.
        val conGuiones = CodigoRecuperacion.generar()
        val sellada = CopiaSeguridad.sellarIdentidad(
            identidadDeMentira(),
            CodigoRecuperacion.claveDeIdentidad(CodigoRecuperacion.normalizar(conGuiones)!!),
        )
        val tecleado = conGuiones.lowercase().replace("-", " ")
        val abierta = CopiaSeguridad.abrirIdentidad(
            sellada,
            CodigoRecuperacion.claveDeIdentidad(CodigoRecuperacion.normalizar(tecleado)!!),
        )
        assertNotNull("el mismo codigo escrito distinto no abrio", abierta)
    }

    // ------------------------------------------------------ rechazos

    @Test
    fun `otro codigo no la abre`() {
        val sellada = CopiaSeguridad.sellarIdentidad(
            identidadDeMentira(), CodigoRecuperacion.claveDeIdentidad(codigoLimpio()),
        )
        repeat(50) {
            val otro = CodigoRecuperacion.claveDeIdentidad(codigoLimpio())
            assertNull("abrio con un codigo ajeno", CopiaSeguridad.abrirIdentidad(sellada, otro))
        }
    }

    @Test
    fun `un sello manipulado se rechaza`() {
        // GCM tiene etiqueta: cambiar un byte del cifrado tiene que romper la
        // verificacion, no devolver basura que luego se guarde como identidad.
        val codigo = codigoLimpio()
        val clave = CodigoRecuperacion.claveDeIdentidad(codigo)
        val buena = CopiaSeguridad.sellarIdentidad(identidadDeMentira(), clave)

        val bytes = java.util.Base64.getDecoder().decode(buena.selladoB64)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        val tocada = buena.copy(
            selladoB64 = java.util.Base64.getEncoder().encodeToString(bytes),
        )
        assertNull("acepto un sello manipulado", CopiaSeguridad.abrirIdentidad(tocada, clave))
    }

    @Test
    fun `cambiarle el nonce la rechaza`() {
        val clave = CodigoRecuperacion.claveDeIdentidad(codigoLimpio())
        val buena = CopiaSeguridad.sellarIdentidad(identidadDeMentira(), clave)
        val otroNonce = java.util.Base64.getEncoder()
            .encodeToString(ByteArray(12).also { azar.nextBytes(it) })
        assertNull(CopiaSeguridad.abrirIdentidad(buena.copy(nonceB64 = otroNonce), clave))
    }

    @Test
    fun `basura en lugar de un sello no revienta`() {
        // Un archivo corrupto o de otra version no debe tumbar la
        // restauracion: se informa y los mensajes se restauran igual.
        val clave = CodigoRecuperacion.claveDeIdentidad(codigoLimpio())
        assertNull(CopiaSeguridad.abrirIdentidad(
            CopiaSeguridad.IdentidadRespaldo("no-es-base64!!", "tampoco"), clave,
        ))
        assertNull(CopiaSeguridad.abrirIdentidad(
            CopiaSeguridad.IdentidadRespaldo("", ""), clave,
        ))
    }

    @Test
    fun `una clave que no mide 32 bytes no abre nada`() {
        val clave = CodigoRecuperacion.claveDeIdentidad(codigoLimpio())
        val sellada = CopiaSeguridad.sellarIdentidad(identidadDeMentira(), clave)
        assertNull(CopiaSeguridad.abrirIdentidad(sellada, ByteArray(16)))
        assertNull(CopiaSeguridad.abrirIdentidad(sellada, ByteArray(0)))
    }

    // ------------------------------------------------- forma del sello

    @Test
    fun `dos sellos de la misma identidad no son iguales`() {
        // Nonce nuevo en cada sellado. Si dos copias de la misma identidad
        // salieran byte a byte iguales, quien viera dos archivos sabria que no
        // cambio nada entre ellos.
        val clave = CodigoRecuperacion.claveDeIdentidad(codigoLimpio())
        val misma = identidadDeMentira()
        val a = CopiaSeguridad.sellarIdentidad(misma, clave)
        val b = CopiaSeguridad.sellarIdentidad(misma, clave)
        assertNotEquals("el nonce se esta repitiendo", a.selladoB64, b.selladoB64)
        assertNotEquals(a.nonceB64, b.nonceB64)
    }

    @Test
    fun `la clave privada no aparece en claro en el sello`() {
        val codigo = codigoLimpio()
        val clara = identidadDeMentira()
        val sellada = CopiaSeguridad.sellarIdentidad(
            clara, CodigoRecuperacion.claveDeIdentidad(codigo),
        )
        // Ni el base64 del par de claves ni el registrationId deben verse.
        assertTrue(
            "la clave privada se ve en el sello",
            !sellada.selladoB64.contains(clara.parClavesB64),
        )
        assertTrue(
            "el registrationId se ve en el sello",
            !sellada.selladoB64.contains("4242"),
        )
    }

    // ------------------------------------------ compatibilidad de formato

    @Test
    fun `una copia sin identidad sigue siendo valida`() {
        // Las copias v1 y v2 no traen el campo. Tienen que poder abrirse: la
        // gente que ya tiene copias hechas no deberia perderlas por esto.
        val respaldo = CopiaSeguridad.Respaldo(
            creado = 1_700_000_000_000L,
            cuenta = "xampl3",
            conversaciones = emptyList(),
        )
        assertNull("por defecto no deberia traer identidad", respaldo.identidad)
        assertEquals("la version tiene que haber subido a 3", 3, respaldo.version)
    }

    @Test
    fun `el respaldo con identidad sobrevive al cifrado del manifiesto`() {
        // La identidad viaja DENTRO del manifiesto, que va cifrado con la
        // frase. Esta es la comprobacion de que las dos cerraduras se aguantan
        // una encima de la otra.
        val codigo = codigoLimpio()
        val clara = identidadDeMentira()
        val respaldo = CopiaSeguridad.Respaldo(
            creado = 1_700_000_000_000L,
            cuenta = "xampl3",
            conversaciones = emptyList(),
            identidad = CopiaSeguridad.sellarIdentidad(
                clara, CodigoRecuperacion.claveDeIdentidad(codigo),
            ),
        )

        val frase = "una frase larga de prueba".toCharArray()
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(CopiaSeguridad.Respaldo.serializer(), respaldo)
        val cifrado = CopiaSeguridad.cifrar(json.toByteArray(), frase)

        val devuelto = CopiaSeguridad.descifrar(cifrado, "una frase larga de prueba".toCharArray())
        assertTrue("no se pudo descifrar el manifiesto", devuelto.isSuccess)

        val leido = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                CopiaSeguridad.Respaldo.serializer(), String(devuelto.getOrThrow()),
            )
        assertEquals(
            "la identidad no sobrevivio al manifiesto",
            clara,
            CopiaSeguridad.abrirIdentidad(
                leido.identidad!!, CodigoRecuperacion.claveDeIdentidad(codigo),
            ),
        )
    }

    @Test
    fun `sin el codigo no se saca la identidad aunque se tenga la frase`() {
        // La consecuencia honesta de las dos cerraduras, escrita como prueba:
        // quien tenga el archivo y la frase lee los mensajes, pero NO se lleva
        // la identidad.
        val clara = identidadDeMentira()
        val sellada = CopiaSeguridad.sellarIdentidad(
            clara, CodigoRecuperacion.claveDeIdentidad(codigoLimpio()),
        )
        // Con la frase se llega hasta aqui: al sello. Y aqui se para.
        val conOtroCodigo = CodigoRecuperacion.claveDeIdentidad(codigoLimpio())
        assertNull(CopiaSeguridad.abrirIdentidad(sellada, conOtroCodigo))
    }
}
