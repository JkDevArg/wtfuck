package com.wtfuck.app

import com.wtfuck.app.datos.EnvioCerca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que puede salir por el modo cerca, y hacia quien. */
class EnvioCercaTest {

    private fun va(tipo: String, nombre: String = "", participantes: String = "", parUsuario: String = "u-beto", parUsername: String = "beto") =
        EnvioCerca.va(tipo, nombre, participantes, miUsuarioId = "u-ana", parUsuarioId = parUsuario, parUsername = parUsername)

    @Test
    fun `una directa sale solo hacia la persona de esa directa`() {
        assertTrue(va("directa", nombre = "beto"))
        assertTrue(va("directa", nombre = "Beto"))
        assertFalse(va("directa", nombre = "carla"))
    }

    @Test
    fun `un grupo sale hacia quien es del grupo, y solo hacia esa persona`() {
        assertTrue(va("grupo", participantes = "ana,beto,carla"))
        assertTrue(va("grupo", participantes = "ana, beto"))
        assertFalse(va("grupo", participantes = "ana,carla"))
        // "bet" no es "beto": se compara el usuario entero, no un pedazo.
        assertFalse(va("grupo", participantes = "ana,beto2", parUsername = "beto"))
    }

    @Test
    fun `a mi otro aparato va todo, incluida la nota para mi`() {
        assertTrue(va("directa", nombre = "carla", parUsuario = "u-ana", parUsername = "ana"))
        assertTrue(va("notas", parUsuario = "u-ana", parUsername = "ana"))
    }

    @Test
    fun `un canal no sale por cerca`() {
        assertFalse(va("canal", nombre = "beto", participantes = "beto"))
        assertFalse(va("notas"))
    }

    @Test
    fun `un usuario vacio no cuenta como yo`() {
        assertFalse(EnvioCerca.va("directa", "carla", "", miUsuarioId = "", parUsuarioId = "", parUsername = "beto"))
    }

    @Test
    fun `solo texto, un adjunto o un mensaje especial esperan la red`() {
        assertTrue(EnvioCerca.esTexto(null, "", ""))
        assertFalse(EnvioCerca.esTexto("adj-1", "IMAGEN", ""))
        assertFalse(EnvioCerca.esTexto(null, "IMAGEN", ""))
        assertFalse(EnvioCerca.esTexto(null, "", """{"type":"encuesta"}"""))
    }

    @Test
    fun `la lista de aparatos que ya lo tienen`() {
        assertEquals("d1", EnvioCerca.con("", "d1"))
        assertEquals("d1,d2", EnvioCerca.con("d1", "d2"))
        assertEquals("d1,d2", EnvioCerca.con("d1,d2", "d2"))
        assertTrue(EnvioCerca.tiene("d1,d2", "d2"))
        assertFalse(EnvioCerca.tiene("d1,d22", "d2"))
        assertFalse(EnvioCerca.tiene("", "d2"))
    }
}
