package com.wtfuck.app

import com.wtfuck.app.datos.Atajos
import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.datos.WidgetWtfuck
import org.junit.Assert.assertEquals
import org.junit.Test

class AtajosTest {

    private fun chat(
        id: String,
        fecha: Long?,
        tipo: String = "directa",
        protegido: Boolean = false,
        soyMiembro: Boolean = true,
    ) = ChatFila(
        id = id, tipo = tipo, nombre = id, nombreMostrado = "", participantes = "",
        avatarUsername = "", avatarVersion = 0, noLeidos = 0,
        ultimoTexto = null, ultimaFecha = fecha, ultimoEsMio = null, ultimoAutor = null,
        ultimoEstado = null, ultimoAdjuntoClase = null, ultimoAdjuntoNombre = null,
        miRol = "miembro", miJerarquia = 10, silenciadoHasta = 0, archivado = false,
        fijado = false, marcadaNoLeida = false, soyMiembro = soyMiembro, protegido = protegido,
    )

    @Test
    fun `los tres mas recientes`() {
        val r = Atajos.elegidos(listOf(chat("a", 1), chat("b", 4), chat("c", 3), chat("d", 2)))
        assertEquals(listOf("b", "c", "d"), r.map { it.id })
    }

    @Test
    fun `nunca un chat protegido`() {
        val r = Atajos.elegidos(listOf(chat("secreto", 9, protegido = true), chat("a", 1)))
        assertEquals(listOf("a"), r.map { it.id })
    }

    @Test
    fun `ni canales ni chats de los que sali`() {
        val r = Atajos.elegidos(
            listOf(chat("canal", 9, tipo = "canal"), chat("fuera", 8, soyMiembro = false), chat("a", 1)),
        )
        assertEquals(listOf("a"), r.map { it.id })
    }

    @Test
    fun `la nota si`() {
        assertEquals(listOf("n"), Atajos.elegidos(listOf(chat("n", 5, tipo = "notas"))).map { it.id })
    }

    @Test
    fun `el widget dice un numero y nada mas`() {
        assertEquals("Nada sin leer", WidgetWtfuck.texto(0))
        assertEquals("1 mensaje sin leer", WidgetWtfuck.texto(1))
        assertEquals("7 mensajes sin leer", WidgetWtfuck.texto(7))
        assertEquals("999+ mensajes sin leer", WidgetWtfuck.texto(5000))
    }
}
