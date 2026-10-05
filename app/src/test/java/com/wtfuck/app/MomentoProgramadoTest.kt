package com.wtfuck.app

import com.wtfuck.app.datos.MomentoProgramado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MomentoProgramadoTest {

    private val lima = ZoneId.of("America/Lima")
    private fun en(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 10, 5, h, m, s, 0, lima)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test
    fun `de tarde hay tres atajos y redondeados a minuto`() {
        val a = MomentoProgramado.atajos(en(15, 12, 37))
        assertEquals(listOf("En 1 hora", "Esta noche, 20:00", "Mañana, 08:00"), a.map { it.etiqueta })
        assertEquals(ms(en(16, 12)), a[0].cuando)
        assertEquals(ms(en(20, 0)), a[1].cuando)
        assertEquals(ms(ZonedDateTime.of(2026, 10, 6, 8, 0, 0, 0, lima)), a[2].cuando)
    }

    @Test
    fun `casi a las ocho ya no ofrece esta noche`() {
        val a = MomentoProgramado.atajos(en(19, 45))
        assertEquals(listOf("En 1 hora", "Mañana, 08:00"), a.map { it.etiqueta })
    }

    @Test
    fun `pasadas las ocho tampoco`() {
        assertFalse(MomentoProgramado.atajos(en(22, 0)).any { it.etiqueta.startsWith("Esta noche") })
    }

    @Test
    fun `etiquetas de hoy, manana y mas adelante`() {
        val ahora = en(10, 0)
        assertEquals("hoy 20:00", MomentoProgramado.etiqueta(ms(en(20, 0)), ahora))
        assertEquals("mañana 08:00", MomentoProgramado.etiqueta(ms(ZonedDateTime.of(2026, 10, 6, 8, 0, 0, 0, lima)), ahora))
        val lejos = MomentoProgramado.etiqueta(ms(ZonedDateTime.of(2026, 10, 12, 9, 30, 0, 0, lima)), ahora)
        assertTrue(lejos, lejos.endsWith(", 09:30") && lejos.contains("12"))
    }

    @Test
    fun `una hora pasada o demasiado lejana no vale`() {
        val ahora = ms(en(10, 0))
        assertFalse(MomentoProgramado.valido(ahora - 1, ahora))
        assertFalse(MomentoProgramado.valido(ahora + 10_000, ahora))
        assertTrue(MomentoProgramado.valido(ahora + 3_600_000, ahora))
        assertFalse(MomentoProgramado.valido(ahora + 400L * 24 * 3600 * 1000, ahora))
    }
}
