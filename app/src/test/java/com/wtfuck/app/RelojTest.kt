package com.wtfuck.app

import com.wtfuck.app.datos.Reloj
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El reloj corregido contra el servidor.
 *
 * Nace de dos emuladores, uno con la hora quince minutos atrasada: sus
 * respuestas aparecian en el chat del otro quince minutos arriba.
 */
class RelojTest {

    private val T = 1_790_000_000_000L
    private val MIN = 60_000L

    @After
    fun limpiar() {
        // El objeto es global: se deja en cero para no contaminar otras pruebas.
        Reloj.observar(T, local = T)
    }

    @Test
    fun `un telefono atrasado quince minutos corrige quince minutos`() {
        assertEquals(15 * MIN, Reloj.desfaseDe(servidor = T, local = T - 15 * MIN))
    }

    @Test
    fun `uno adelantado corrige hacia atras`() {
        assertEquals(-3 * MIN, Reloj.desfaseDe(servidor = T, local = T + 3 * MIN))
    }

    @Test
    fun `la latencia de red no mueve la hora de un telefono bien puesto`() {
        // Medio viaje de red: corregir por esto moveria la hora de todos para
        // no arreglar nada.
        assertEquals(0, Reloj.desfaseDe(servidor = T, local = T + 400))
        assertEquals(0, Reloj.desfaseDe(servidor = T, local = T - 1_999))
    }

    @Test
    fun `justo en el umbral ya se corrige`() {
        assertEquals(-Reloj.UMBRAL_MS, Reloj.desfaseDe(servidor = T, local = T + Reloj.UMBRAL_MS))
    }

    @Test
    fun `una hora del servidor imposible no se aplica`() {
        assertEquals(0, Reloj.desfaseDe(servidor = 0, local = T))
        assertEquals(0, Reloj.desfaseDe(servidor = -5, local = T))
    }

    @Test
    fun `una cabecera rota no borra la medida buena`() {
        Reloj.observar(T, local = T - 10 * MIN)
        Reloj.observar(0)
        assertEquals(10 * MIN, Reloj.desfaseMs())
    }

    @Test
    fun `ahora aplica el desfase`() {
        Reloj.observar(T, local = T - 15 * MIN)
        val esperado = System.currentTimeMillis() + 15 * MIN
        assertTrue(kotlin.math.abs(Reloj.ahora() - esperado) < 1_000)
    }
}
