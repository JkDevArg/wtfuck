package com.wtfuck.app

import com.wtfuck.app.datos.ExportarChat
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.protocol.ClaseAdjunto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exportar una conversacion a texto plano.
 *
 * ## Por que la mitad de esto prueba lo que NO sale
 *
 * Porque esta funcion saca en claro lo que el resto de la app protege. Un
 * error aqui no se ve: el archivo se genera, se abre, tiene mensajes, parece
 * bien. Que ademas lleve dentro algo que no debia -un mensaje temporal, uno
 * retirado- solo lo descubre la persona a la que le afecta, y ya es tarde
 * porque el archivo esta en otro sitio.
 */
class ExportarChatTest {

    private val T0 = 1_700_000_000_000L

    private fun msg(
        texto: String,
        esMio: Boolean = false,
        autor: String = "fulano",
        creadoEn: Long = T0,
        expiraEn: Long = 0,
        retirado: Boolean = false,
        oculto: Boolean = false,
        esSistema: Boolean = false,
        adjuntoClase: String = "",
        adjuntoNombre: String = "",
    ) = MensajeEnt(
        id = "m-${texto.hashCode()}-$creadoEn",
        conversacionId = "c1",
        autor = autor,
        esMio = esMio,
        texto = texto,
        creadoEn = creadoEn,
        estado = "ENTREGADO",
        expiraEn = expiraEn,
        retirado = retirado,
        oculto = oculto,
        esSistema = esSistema,
        adjuntoClase = adjuntoClase,
        adjuntoNombre = adjuntoNombre,
    )

    // ------------------------------------------------ lo que NO sale

    @Test
    fun `un mensaje temporal NO se exporta`() {
        // La decision de diseno de este modulo. Quien pone temporizador pide
        // que no persista; meterlo en un txt sin cifrar es lo contrario, y lo
        // decide una sola de las dos partes.
        val out = ExportarChat.construir(
            "fulano",
            listOf(
                msg("esto queda"),
                msg("esto desaparece", expiraEn = T0 + 60_000),
            ),
            cuando = T0,
        )
        assertTrue(out.texto.contains("esto queda"))
        assertFalse("se exporto un mensaje temporal", out.texto.contains("esto desaparece"))
        assertEquals(1, out.resumen.temporalesOmitidos)
    }

    @Test
    fun `el archivo DICE cuantos temporales se omitieron`() {
        // Callarlo haria creer que la conversacion esta entera. Si esto se usa
        // como prueba, quien lo lea tiene que saber que falta algo.
        val out = ExportarChat.construir(
            "fulano",
            listOf(msg("a"), msg("b", expiraEn = T0 + 1), msg("c", expiraEn = T0 + 1)),
            cuando = T0,
        )
        assertTrue("no avisa de los omitidos", out.texto.contains("2 mensajes temporales"))
    }

    @Test
    fun `sin temporales no se menciona el tema`() {
        // Un aviso que sale siempre deja de leerse.
        val out = ExportarChat.construir("fulano", listOf(msg("a")), cuando = T0)
        assertFalse(out.texto.contains("temporales no se incluyeron"))
        assertEquals(0, out.resumen.temporalesOmitidos)
    }

    @Test
    fun `un mensaje retirado no se exporta`() {
        val out = ExportarChat.construir(
            "fulano", listOf(msg("borrado", retirado = true), msg("visible")), cuando = T0,
        )
        assertFalse(out.texto.contains("borrado"))
        assertTrue(out.texto.contains("visible"))
        assertEquals(1, out.resumen.incluidos)
    }

    @Test
    fun `un mensaje oculto no se exporta`() {
        val out = ExportarChat.construir(
            "fulano", listOf(msg("escondido", oculto = true)), cuando = T0,
        )
        assertFalse(out.texto.contains("escondido"))
    }

    // --------------------------------------------------- la cabecera

    @Test
    fun `avisa de que el archivo no va cifrado`() {
        val out = ExportarChat.construir("fulano", listOf(msg("a")), cuando = T0)
        assertTrue("falta el aviso de que no va cifrado", out.texto.contains("NO está cifrado"))
    }

    @Test
    fun `la cabecera trae el titulo y el numero de mensajes`() {
        val out = ExportarChat.construir(
            "Grupo de trabajo", listOf(msg("a"), msg("b")), cuando = T0,
        )
        assertTrue(out.texto.contains("Grupo de trabajo"))
        assertTrue(out.texto.contains("2 mensajes"))
    }

    // ------------------------------------------------------ el cuerpo

    @Test
    fun `los mensajes salen en orden cronologico`() {
        // Llegan de la base ordenados, pero un export desordenado como prueba
        // no vale nada. Se ordena aqui y no se confia.
        val out = ExportarChat.construir(
            "fulano",
            listOf(
                msg("tercero", creadoEn = T0 + 3000),
                msg("primero", creadoEn = T0),
                msg("segundo", creadoEn = T0 + 1000),
            ),
            cuando = T0,
        )
        val i1 = out.texto.indexOf("primero")
        val i2 = out.texto.indexOf("segundo")
        val i3 = out.texto.indexOf("tercero")
        assertTrue("salieron desordenados", i1 < i2 && i2 < i3)
    }

    @Test
    fun `lo mio sale como tu y lo suyo con su nombre`() {
        val out = ExportarChat.construir(
            "fulano",
            listOf(msg("hola", esMio = true), msg("que tal", autor = "fulano")),
            cuando = T0,
        )
        assertTrue(out.texto.contains("tú: hola"))
        assertTrue(out.texto.contains("fulano: que tal"))
    }

    @Test
    fun `se usa el nombre de mi libreta si lo hay`() {
        val out = ExportarChat.construir(
            "fulano",
            listOf(msg("hey", autor = "fulano")),
            cuando = T0,
            alias = mapOf("fulano" to "Ana del trabajo"),
        )
        assertTrue(out.texto.contains("Ana del trabajo: hey"))
    }

    @Test
    fun `un mensaje de sistema se marca y no se atribuye a nadie`() {
        // Importa si esto se usa como prueba: que no parezca que alguien lo
        // dijo.
        val out = ExportarChat.construir(
            "grupo", listOf(msg("@x te agrego al grupo", esSistema = true)), cuando = T0,
        )
        assertTrue(out.texto.contains("* @x te agrego al grupo"))
        assertFalse(out.texto.contains("fulano: @x te agrego"))
    }

    // ------------------------------------------------------ adjuntos

    @Test
    fun `un adjunto sale como marca y nombre`() {
        val out = ExportarChat.construir(
            "fulano",
            listOf(msg("", adjuntoClase = ClaseAdjunto.IMAGEN, adjuntoNombre = "playa.jpg")),
            cuando = T0,
        )
        assertTrue(out.texto.contains("[foto] playa.jpg"))
    }

    @Test
    fun `el pie del adjunto no se pierde`() {
        // Un `[foto]` a secas perderia el texto que la acompanaba, que muchas
        // veces es lo unico que importa.
        val out = ExportarChat.construir(
            "fulano",
            listOf(
                msg("mira esto", adjuntoClase = ClaseAdjunto.IMAGEN, adjuntoNombre = "a.jpg"),
            ),
            cuando = T0,
        )
        assertTrue(out.texto.contains("mira esto"))
    }

    @Test
    fun `un documento no repite su nombre dos veces`() {
        // En un documento `texto` suele SER el nombre; sin el guard quedaria
        // "[archivo] x.pdf — x.pdf".
        val out = ExportarChat.construir(
            "fulano",
            listOf(
                msg("contrato.pdf", adjuntoClase = ClaseAdjunto.DOCUMENTO, adjuntoNombre = "contrato.pdf"),
            ),
            cuando = T0,
        )
        assertEquals(
            "el nombre sale repetido",
            1,
            Regex("contrato\\.pdf").findAll(out.texto).count(),
        )
    }

    @Test
    fun `cada clase de adjunto tiene su marca`() {
        fun marca(clase: String) = ExportarChat.construir(
            "f", listOf(msg("", adjuntoClase = clase, adjuntoNombre = "x")), cuando = T0,
        ).texto
        assertTrue(marca(ClaseAdjunto.VIDEO).contains("[video]"))
        assertTrue(marca(ClaseAdjunto.NOTA_VOZ).contains("[nota de voz]"))
        assertTrue(marca(ClaseAdjunto.STICKER).contains("[sticker]"))
        assertTrue(marca(ClaseAdjunto.DOCUMENTO).contains("[archivo]"))
    }

    // -------------------------------------------------- casos limite

    @Test
    fun `una conversacion vacia no revienta`() {
        val out = ExportarChat.construir("fulano", emptyList(), cuando = T0)
        assertTrue(out.texto.contains("0 mensajes"))
        assertEquals(0, out.resumen.incluidos)
    }

    @Test
    fun `una conversacion SOLO de temporales avisa y sale vacia`() {
        // El caso que mas confundiria: un archivo sin mensajes. Sin el aviso,
        // pareceria que la exportacion fallo.
        val out = ExportarChat.construir(
            "fulano",
            listOf(msg("a", expiraEn = T0 + 1), msg("b", expiraEn = T0 + 1)),
            cuando = T0,
        )
        assertEquals(0, out.resumen.incluidos)
        assertTrue(out.texto.contains("2 mensajes temporales"))
    }

    // ------------------------------------------------ nombre de archivo

    @Test
    fun `el nombre sugerido es usable en cualquier sistema`() {
        val n = ExportarChat.nombreSugerido("Ana / Trabajo: urgente", T0)
        assertFalse("lleva caracteres que rompen rutas", n.contains("/"))
        assertFalse(n.contains(":"))
        assertTrue(n.endsWith(".txt"))
        assertTrue(n.startsWith("wtfuck-"))
    }

    @Test
    fun `un titulo raro no deja el nombre vacio`() {
        val n = ExportarChat.nombreSugerido("///", T0)
        assertTrue("quedo sin nombre: $n", n.contains("chat"))
        assertTrue(n.endsWith(".txt"))
    }

    @Test
    fun `un titulo larguisimo se recorta`() {
        val n = ExportarChat.nombreSugerido("a".repeat(300), T0)
        assertTrue("nombre demasiado largo: ${n.length}", n.length < 80)
    }
}
