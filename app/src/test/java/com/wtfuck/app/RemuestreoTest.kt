package com.wtfuck.app

import com.wtfuck.app.datos.Transcriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class RemuestreoTest {

    private fun pcm(vararg muestras: Int): ByteArray {
        val b = ByteArray(muestras.size * 2)
        muestras.forEachIndexed { i, v ->
            b[i * 2] = (v and 0xFF).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun leer(b: ByteArray): List<Int> =
        (0 until b.size / 2).map { ((b[it * 2 + 1].toInt() shl 8) or (b[it * 2].toInt() and 0xFF)).toShort().toInt() }

    @Test
    fun `misma tasa y mono queda igual`() {
        val entrada = pcm(0, 100, -100, 32767, -32768)
        assertEquals(leer(entrada), leer(Transcriptor.remuestrear(entrada, 16_000, 1, 16_000)))
    }

    @Test
    fun `estereo se promedia a mono`() {
        val salida = leer(Transcriptor.remuestrear(pcm(100, 300, -200, 200), 16_000, 2, 16_000))
        assertEquals(listOf(200, 0), salida)
    }

    @Test
    fun `de 48 kHz a 16 kHz se queda con un tercio`() {
        val entrada = pcm(*IntArray(48) { it * 10 })
        val salida = leer(Transcriptor.remuestrear(entrada, 48_000, 1, 16_000))
        assertEquals(16, salida.size)
        assertEquals(0, salida[0])
        assertEquals(30, salida[1])
    }

    @Test
    fun `vacio da vacio`() {
        assertEquals(0, Transcriptor.remuestrear(ByteArray(0), 44_100, 1, 16_000).size)
    }
}
