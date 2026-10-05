package com.wtfuck.app

import com.wtfuck.app.datos.Carpetas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarpetasTest {

    @Test
    fun `un nombre normal sirve`() {
        assertNull(Carpetas.problemaCon("Trabajo", listOf("Familia")))
    }

    @Test
    fun `vacio o solo espacios no`() {
        assertEquals("Ponle un nombre.", Carpetas.problemaCon("   ", emptyList()))
    }

    @Test
    fun `largo no`() {
        assertNotNull(Carpetas.problemaCon("a".repeat(Carpetas.MAX_NOMBRE + 1), emptyList()))
        assertNull(Carpetas.problemaCon("a".repeat(Carpetas.MAX_NOMBRE), emptyList()))
    }

    @Test
    fun `repetido sin importar mayusculas`() {
        assertNotNull(Carpetas.problemaCon("trabajo", listOf("Trabajo")))
    }

    @Test
    fun `renombrar no choca consigo misma`() {
        assertNull(Carpetas.problemaCon("TRABAJO", listOf("Trabajo", "Familia"), propio = "Trabajo"))
        assertNotNull(Carpetas.problemaCon("familia", listOf("Trabajo", "Familia"), propio = "Trabajo"))
    }

    @Test
    fun `no puede llamarse como una pestana fija`() {
        assertNotNull(Carpetas.problemaCon("Grupos", emptyList()))
        assertNotNull(Carpetas.problemaCon("no leídos", emptyList()))
    }

    @Test
    fun `tope de carpetas`() {
        assertTrue(Carpetas.caben(Carpetas.MAX - 1))
        assertFalse(Carpetas.caben(Carpetas.MAX))
    }
}
