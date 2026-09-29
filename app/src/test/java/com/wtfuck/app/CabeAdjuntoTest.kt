package com.wtfuck.app

import com.wtfuck.app.datos.CabeAdjunto
import com.wtfuck.app.datos.CifradorArchivo
import com.wtfuck.protocol.ClaseAdjunto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El limite de los adjuntos, comprobado antes de crear el mensaje.
 *
 * Lo que se fija aqui es sobre todo el BORDE: que esta comprobacion y la que
 * hay dentro de `subirAdjunto` digan lo mismo. Si no coincidieran, un archivo
 * justo en el limite pasaria por aqui y fallaria despues — o sea, volveria el
 * defecto original, pero solo para los archivos del borde y por tanto mucho
 * mas dificil de ver.
 */
class CabeAdjuntoTest {

    private val MB = 1024L * 1024

    @Test
    fun `un video normal cabe`() {
        assertTrue(CabeAdjunto.evaluar(10 * MB, ClaseAdjunto.VIDEO).cabe)
    }

    @Test
    fun `un minuto de 4K no cabe`() {
        // El caso que la gente encuentra a la primera: el tope son 64 MB.
        assertFalse(CabeAdjunto.evaluar(340 * MB, ClaseAdjunto.VIDEO).cabe)
    }

    @Test
    fun `el sobrecosto del cifrado cuenta`() {
        // Justo en el limite SIN contar el cifrado: no debe caber, porque el
        // archivo cifrado pesa algo mas y `subirAdjunto` lo rechazaria.
        val limite = 64 * MB
        assertFalse(
            "un archivo del tamano exacto del limite no cabe una vez cifrado",
            CabeAdjunto.evaluar(limite, ClaseAdjunto.VIDEO).cabe,
        )
        // Y uno que deja sitio para el sobrecosto, si.
        assertTrue(
            CabeAdjunto.evaluar(limite - CifradorArchivo.SOBRECOSTO, ClaseAdjunto.VIDEO).cabe,
        )
    }

    @Test
    fun `cada clase tiene su limite`() {
        // 20 MB: cabe como video (64) y como documento (64), no como imagen
        // (16) ni como nota de voz (16).
        val veinte = 20 * MB
        assertTrue(CabeAdjunto.evaluar(veinte, ClaseAdjunto.VIDEO).cabe)
        assertTrue(CabeAdjunto.evaluar(veinte, ClaseAdjunto.DOCUMENTO).cabe)
        assertFalse(CabeAdjunto.evaluar(veinte, ClaseAdjunto.IMAGEN).cabe)
        assertFalse(CabeAdjunto.evaluar(veinte, ClaseAdjunto.NOTA_VOZ).cabe)
    }

    @Test
    fun `un archivo vacio cabe`() {
        // Puede pasar con una URI rota. Que no cuelgue aqui: si el archivo
        // esta mal, que falle mas adelante con un motivo mejor que "no cabe".
        assertTrue(CabeAdjunto.evaluar(0, ClaseAdjunto.VIDEO).cabe)
    }

    // ------------------------------------------------------ el aviso

    @Test
    fun `el aviso dice los DOS numeros`() {
        // "El archivo es demasiado grande" obliga a adivinar cuanto recortar.
        val v = CabeAdjunto.evaluar(340 * MB, ClaseAdjunto.VIDEO)
        val texto = CabeAdjunto.aviso(v, ClaseAdjunto.VIDEO)
        assertTrue("falta el tamano real: $texto", texto.contains("340"))
        assertTrue("falta el limite: $texto", texto.contains("64"))
    }

    @Test
    fun `el aviso del video sugiere que hacer`() {
        val v = CabeAdjunto.evaluar(340 * MB, ClaseAdjunto.VIDEO)
        val texto = CabeAdjunto.aviso(v, ClaseAdjunto.VIDEO)
        assertTrue("no dice que hacer: $texto", texto.contains("recórtalo"))
    }

    @Test
    fun `el aviso nombra el tipo de archivo`() {
        assertTrue(
            CabeAdjunto.aviso(CabeAdjunto.evaluar(999 * MB, ClaseAdjunto.VIDEO), ClaseAdjunto.VIDEO)
                .startsWith("Ese video"),
        )
        assertTrue(
            CabeAdjunto.aviso(CabeAdjunto.evaluar(999 * MB, ClaseAdjunto.AUDIO), ClaseAdjunto.AUDIO)
                .startsWith("Ese audio"),
        )
        assertTrue(
            CabeAdjunto.aviso(
                CabeAdjunto.evaluar(999 * MB, ClaseAdjunto.DOCUMENTO), ClaseAdjunto.DOCUMENTO,
            ).startsWith("Ese archivo"),
        )
    }

    @Test
    fun `solo el video sugiere recortar`() {
        // Un documento no se "recorta". Un consejo que no aplica hace dudar
        // de los que si.
        val texto = CabeAdjunto.aviso(
            CabeAdjunto.evaluar(999 * MB, ClaseAdjunto.DOCUMENTO), ClaseAdjunto.DOCUMENTO,
        )
        assertFalse(texto.contains("recórtalo"))
    }
}
