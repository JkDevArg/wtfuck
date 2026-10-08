package com.wtfuck.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * W5e · El lector DER y la lectura de `KeyDescription`, con una extension de
 * atestacion armada a mano byte a byte.
 *
 * Lo que fijan:
 *  - las etiquetas de numero alto de la AuthorizationList ([702], [704], [709]
 *    ocupan tres bytes) y los largos de forma larga;
 *  - que los campos se lean de la lista que sea (hardware o software): cada
 *    fabricante pone `attestationApplicationId` donde quiere;
 *  - que una extension truncada o mentirosa LANCE, en vez de leer basura: el
 *    verificador la da entonces por ilegible y la atestacion falla.
 *
 * La validacion de cadenas reales (firmas, raiz de Google, revocados) se
 * prueba con cadenas capturadas de telefonos: ver `pruebas/atestacion.mjs` y
 * docs/evidencias/web-w5/.
 */
class AtestacionTest {

    // --- un escritor DER minimo, solo para armar los casos ---

    private fun tlv(etiqueta: ByteArray, valor: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(etiqueta)
        when {
            valor.size < 0x80 -> o.write(valor.size)
            valor.size < 0x100 -> { o.write(0x81); o.write(valor.size) }
            else -> { o.write(0x82); o.write(valor.size shr 8); o.write(valor.size and 0xFF) }
        }
        o.write(valor)
        return o.toByteArray()
    }

    private fun seq(vararg h: ByteArray) = tlv(byteArrayOf(0x30), h.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg h: ByteArray) = tlv(byteArrayOf(0x31), h.fold(ByteArray(0)) { a, b -> a + b })
    private fun entero(n: Int) = tlv(byteArrayOf(0x02), java.math.BigInteger.valueOf(n.toLong()).toByteArray())
    private fun enumerado(n: Int) = tlv(byteArrayOf(0x0A), byteArrayOf(n.toByte()))
    private fun octetos(b: ByteArray) = tlv(byteArrayOf(0x04), b)
    private fun booleano(v: Boolean) = tlv(byteArrayOf(0x01), byteArrayOf(if (v) 0xFF.toByte() else 0))

    /** [n] EXPLICIT, construido, con el numero en base 128 como la AuthorizationList. */
    private fun ctx(n: Int, valor: ByteArray): ByteArray {
        val e = if (n < 31) byteArrayOf((0xA0 or n).toByte()) else byteArrayOf(0xBF.toByte(), (0x80 or (n shr 7)).toByte(), (n and 0x7F).toByte())
        return tlv(e, valor)
    }

    private val reto = ByteArray(32) { (it * 7).toByte() }
    private val firma = ByteArray(32) { 0xAB.toByte() }

    private fun appId(paquete: String = "com.wtfuck.app") = octetos(
        seq(
            set(seq(octetos(paquete.toByteArray()), entero(17))),
            set(octetos(firma)),
        ),
    )

    private fun descripcion(
        nivel: Int = 1,
        origen: Int = 0,
        bloqueado: Boolean = true,
        estado: Int = 0,
        appEnSoftware: Boolean = true,
        paquete: String = "com.wtfuck.app",
    ): ByteArray {
        val raiz = seq(octetos(ByteArray(32)), booleano(bloqueado), enumerado(estado), octetos(ByteArray(32)))
        val app = ctx(709, appId(paquete))
        val software = if (appEnSoftware) seq(app) else seq()
        val hardware = if (appEnSoftware) seq(ctx(702, entero(origen)), ctx(704, raiz))
        else seq(ctx(702, entero(origen)), ctx(704, raiz), app)
        return seq(entero(300), enumerado(nivel), entero(300), enumerado(nivel), octetos(reto), octetos(ByteArray(0)), software, hardware)
    }

    @Test fun `lee nivel, reto, origen, raiz de confianza y app`() {
        val d = Atestacion.Descripcion.leer(descripcion())
        assertEquals(300, d.version)
        assertEquals(1, d.nivelSeguridad)
        assertArrayEquals(reto, d.reto)
        assertEquals(0, d.origen)
        assertEquals(true, d.raizDeConfianza?.bloqueado)
        assertEquals(0, d.raizDeConfianza?.estado)
        assertEquals(listOf("com.wtfuck.app"), d.app?.paquetes)
        assertEquals(listOf("ab".repeat(32)), d.app?.firmas)
    }

    @Test fun `la app se encuentra este en la lista de software o en la de hardware`() {
        val d = Atestacion.Descripcion.leer(descripcion(appEnSoftware = false))
        assertEquals(listOf("com.wtfuck.app"), d.app?.paquetes)
    }

    @Test fun `StrongBox, bootloader abierto, arranque sin verificar y clave importada se leen tal cual`() {
        val d = Atestacion.Descripcion.leer(descripcion(nivel = 2, origen = 2, bloqueado = false, estado = 2, paquete = "otra.app"))
        assertEquals(2, d.nivelSeguridad)
        assertEquals(2, d.origen)
        assertEquals(false, d.raizDeConfianza?.bloqueado)
        assertEquals(2, d.raizDeConfianza?.estado)
        assertEquals(listOf("otra.app"), d.app?.paquetes)
    }

    @Test fun `etiquetas de numero alto y largos de forma larga`() {
        // Un OCTET STRING de 300 bytes: largo 0x82 0x01 0x2C.
        val largo = octetos(ByteArray(300) { 1 })
        val n = Der(ctx(709, largo)).leer()
        assertEquals(2, n.clase)
        assertEquals(709, n.contexto)
        assertEquals(300, n.hijos()[0].valor.size)
    }

    @Test fun `una extension truncada o mentirosa lanza, no lee basura`() {
        val buena = descripcion()
        for (corte in listOf(1, 5, buena.size / 2, buena.size - 1)) {
            assertTrue("corte en $corte", runCatching { Atestacion.Descripcion.leer(buena.copyOf(corte)) }.isFailure)
        }
        // Un largo que dice mas de lo que hay.
        assertTrue(runCatching { Der(byteArrayOf(0x04, 0x05, 1, 2)).leer() }.isFailure)
        // Un largo de forma larga con demasiados bytes.
        assertTrue(runCatching { Der(byteArrayOf(0x04, 0x85.toByte(), 0, 0, 0, 0, 1, 9)).leer() }.isFailure)
        // Una etiqueta de numero alto que no termina.
        assertTrue(runCatching { Der(byteArrayOf(0xBF.toByte(), 0x85.toByte(), 0x85.toByte(), 0x85.toByte(), 0x85.toByte(), 0x85.toByte())).leer() }.isFailure)
    }

    @Test fun `getExtensionValue viene envuelto en un OCTET STRING`() {
        val d = descripcion()
        assertArrayEquals(d, Der.octetos(octetos(d)))
        assertTrue(runCatching { Der.octetos(seq(octetos(d))) }.isFailure)
    }

    @Test fun `lo que se anota de cada veredicto`() {
        assertEquals("verificada:TEE", Atestacion.Veredicto(null, nivel = "TEE", firmaConocida = true, revocacionComprobada = true).resumen())
        assertEquals(
            "verificada:STRONGBOX,firma-sin-configurar,sin-lista-de-revocados",
            Atestacion.Veredicto(null, nivel = "STRONGBOX", firmaConocida = null, revocacionComprobada = false).resumen(),
        )
        val fallo = Atestacion.Veredicto("bootloader-desbloqueado")
        assertFalse(fallo.valida)
        assertEquals("fallida:bootloader-desbloqueado", fallo.resumen())
        assertNull(Atestacion.Veredicto(null, nivel = "TEE").motivo)
    }

    // ------------------------------------------------------------------
    //  Con una cadena REAL: la del Keystore del emulador (API 37), capturada
    //  con `AtestacionCapturaTest` contra el servidor de desarrollo.
    // ------------------------------------------------------------------

    private fun cadenaDelEmulador(): List<java.security.cert.X509Certificate> {
        val json = AtestacionTest::class.java.getResourceAsStream("/atestacion/emulador-api37.json")!!.readBytes().decodeToString()
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return Regex("\"([A-Za-z0-9+/=]+)\"").findAll(json).map {
            cf.generateCertificate(java.util.Base64.getDecoder().decode(it.groupValues[1]).inputStream()) as java.security.cert.X509Certificate
        }.toList()
    }

    @Test fun `la extension de un emulador real se lee entera`() {
        val hoja = cadenaDelEmulador().first()
        val d = Atestacion.Descripcion.leer(Der.octetos(hoja.getExtensionValue("1.3.6.1.4.1.11129.2.1.17")))
        assertEquals(400, d.version) // KeyMint 4
        assertEquals(0, d.nivelSeguridad) // Software: un emulador honesto no dice TEE
        // El reto que el servidor dio en esa captura.
        assertArrayEquals(java.util.Base64.getDecoder().decode("djcB+WchqD06yjbeahso6V//jiizp4PTOuq51Zskw3U="), d.reto)
        assertEquals(0, d.origen) // GENERATED
        assertEquals(false, d.raizDeConfianza?.bloqueado)
        assertEquals(2, d.raizDeConfianza?.estado) // Unverified
        assertEquals(listOf("com.wtfuck.app"), d.app?.paquetes)
        // La huella del certificado de DEBUG con que se armo esa app.
        assertEquals(listOf("f259d7caee670df7752caa73eae1257d488648a81e5b6d427e89f498dc783b2d"), d.app?.firmas)
    }

    @Test fun `la cadena del emulador firma bien pero su raiz es de pruebas, no de Google`() {
        val cadena = cadenaDelEmulador()
        assertEquals(3, cadena.size)
        for (i in 0 until cadena.size - 1) cadena[i].verify(cadena[i + 1].publicKey)
        assertTrue(cadena.last().subjectX500Principal.name.contains("Google Test LLC"))
        // Una conexion que revienta si se usa: la raiz se rechaza ANTES de
        // tocar la base (antes de quemar ningun reto).
        val sinBase = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java),
        ) { _, m, _ -> error("no tenia que usar la base: ${m.name}") } as java.sql.Connection
        val b64 = cadena.map { java.util.Base64.getEncoder().encodeToString(it.encoded) }
        assertEquals("raiz-desconocida", Atestacion.verificar(sinBase, b64).motivo)
        // Y una cadena con un eslabon cambiado ya no firma.
        assertEquals("firma-rota-en-0", Atestacion.verificar(sinBase, listOf(b64[0], b64[0], b64[2])).motivo)
    }
}
