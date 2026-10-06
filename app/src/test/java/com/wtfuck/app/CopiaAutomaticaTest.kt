package com.wtfuck.app

import com.wtfuck.app.datos.CopiaAutomatica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * La copia automatica: como se llama cada archivo y cuales se borran.
 *
 * La poda es lo unico de esta funcion que DESTRUYE algo, y lo hace en una
 * carpeta que es de la persona: ahi puede haber copias manuales, fotos o lo
 * que sea. Por eso la mitad de estas pruebas son sobre lo que NO se borra.
 */
class CopiaAutomaticaTest {

    private val lima = ZoneId.of("America/Lima")

    @Test
    fun `el nombre lleva la fecha de mayor a menor y con ancho fijo`() {
        val n = CopiaAutomatica.nombre(ZonedDateTime.of(2026, 3, 7, 1, 5, 9, 0, lima))
        assertEquals("wtfuck-auto-20260307-010509.wtfbackup", n)
    }

    @Test
    fun `ordenar por nombre es ordenar por fecha`() {
        val a = CopiaAutomatica.nombre(ZonedDateTime.of(2026, 9, 30, 23, 59, 59, 0, lima))
        val b = CopiaAutomatica.nombre(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, lima))
        assertTrue(a < b)
    }

    @Test
    fun `se conservan las dos mas nuevas y se borra el resto`() {
        val nombres = listOf(
            "wtfuck-auto-20261003-010000.wtfbackup",
            "wtfuck-auto-20261005-010000.wtfbackup",
            "wtfuck-auto-20261001-010000.wtfbackup",
            "wtfuck-auto-20261004-010000.wtfbackup",
        )
        assertEquals(
            listOf("wtfuck-auto-20261003-010000.wtfbackup", "wtfuck-auto-20261001-010000.wtfbackup"),
            CopiaAutomatica.sobrantes(nombres),
        )
    }

    @Test
    fun `con dos o menos no se borra nada`() {
        assertEquals(emptyList<String>(), CopiaAutomatica.sobrantes(emptyList()))
        assertEquals(
            emptyList<String>(),
            CopiaAutomatica.sobrantes(
                listOf("wtfuck-auto-20261003-010000.wtfbackup", "wtfuck-auto-20261005-010000.wtfbackup"),
            ),
        )
    }

    @Test
    fun `las copias manuales y los archivos ajenos no se tocan nunca`() {
        val nombres = listOf(
            "wtfuck-copia-1759700000.wtfbackup",          // una manual
            "foto.jpg",
            "wtfuck-auto-20261005-010000.wtfbackup.bak",  // parecida, pero no
            "otra-wtfuck-auto-20261001-010000.wtfbackup",
            "wtfuck-auto-2026-10-01.wtfbackup",
            "wtfuck-auto-20261005-010000.wtfbackup",
            "wtfuck-auto-20261004-010000.wtfbackup",
            "wtfuck-auto-20261003-010000.wtfbackup",
        )
        assertEquals(listOf("wtfuck-auto-20261003-010000.wtfbackup"), CopiaAutomatica.sobrantes(nombres))
    }

    @Test
    fun `una duplicada por el proveedor cuenta como la misma fecha y va despues`() {
        // Algunos proveedores agregan " (1)" si el nombre ya existe. La de
        // " (1)" se escribio despues: si alguna sobra, sobra la otra.
        val nombres = listOf(
            "wtfuck-auto-20261005-010000.wtfbackup",
            "wtfuck-auto-20261005-010000 (1).wtfbackup",
            "wtfuck-auto-20261004-010000.wtfbackup",
        )
        val borrar = CopiaAutomatica.sobrantes(nombres)
        assertEquals(1, borrar.size)
        assertEquals("wtfuck-auto-20261004-010000.wtfbackup", borrar.single())
        // Y entre las dos de la misma fecha, la que queda es la de " (1)".
        assertEquals(
            listOf("wtfuck-auto-20261005-010000.wtfbackup"),
            CopiaAutomatica.sobrantes(nombres.take(2), conservar = 1),
        )
    }
}
