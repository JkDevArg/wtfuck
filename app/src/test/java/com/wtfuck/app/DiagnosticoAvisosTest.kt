package com.wtfuck.app

import com.wtfuck.app.datos.EstadoAvisos
import com.wtfuck.app.datos.ProblemaAviso.*
import com.wtfuck.app.datos.pasosDelFabricante
import com.wtfuck.app.datos.problemasDeAvisos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticoAvisosTest {

    private val bien = EstadoAvisos(
        notificacionesPermitidas = true,
        servidorConPush = true,
        googlePlay = true,
        tokenRegistrado = true,
        bateriaSinRestriccion = true,
        fabricante = "Google",
    )

    @Test
    fun `con todo en orden no hay nada que decir`() {
        assertTrue(problemasDeAvisos(bien).isEmpty())
    }

    @Test
    fun `sin permiso va primero`() {
        val r = problemasDeAvisos(bien.copy(notificacionesPermitidas = false, bateriaSinRestriccion = false))
        assertEquals(SIN_PERMISO, r.first())
    }

    @Test
    fun `servidor sin push`() {
        assertEquals(listOf(SERVIDOR_SIN_PUSH), problemasDeAvisos(bien.copy(servidorConPush = false)))
    }

    @Test
    fun `sin Google no se dice ademas que falta el token`() {
        // Sin Google no hay token posible: decir las dos cosas confunde la
        // causa con su consecuencia.
        val r = problemasDeAvisos(bien.copy(googlePlay = false, tokenRegistrado = false))
        assertEquals(listOf(SIN_GOOGLE), r)
    }

    @Test
    fun `con servidor sin push no se habla de Google`() {
        val r = problemasDeAvisos(bien.copy(servidorConPush = false, googlePlay = false))
        assertEquals(listOf(SERVIDOR_SIN_PUSH), r)
    }

    @Test
    fun `token sin registrar`() {
        assertEquals(listOf(SIN_TOKEN), problemasDeAvisos(bien.copy(tokenRegistrado = false)))
    }

    @Test
    fun `bateria restringida`() {
        assertEquals(listOf(BATERIA_RESTRINGIDA), problemasDeAvisos(bien.copy(bateriaSinRestriccion = false)))
    }

    @Test
    fun `en Honor se da el consejo aunque todo lo demas este bien`() {
        // No hay API para saber si "Gestionar automaticamente" esta puesto.
        assertEquals(listOf(FABRICANTE_AGRESIVO), problemasDeAvisos(bien.copy(fabricante = "HONOR")))
    }

    @Test
    fun `la marca se reconoce sin importar mayusculas ni espacios`() {
        assertNotNull(pasosDelFabricante("  Xiaomi "))
        assertNotNull(pasosDelFabricante("HUAWEI"))
        assertNotNull(pasosDelFabricante("samsung"))
    }

    @Test
    fun `una marca sin gestor propio no recibe pasos`() {
        assertNull(pasosDelFabricante("Google"))
        assertNull(pasosDelFabricante(""))
    }

    @Test
    fun `los pasos de Honor nombran la opcion que bloquea el push`() {
        assertTrue(pasosDelFabricante("honor")!!.contains("Gestionar"))
    }
}
