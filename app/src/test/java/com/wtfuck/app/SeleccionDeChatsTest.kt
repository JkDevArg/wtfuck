package com.wtfuck.app

import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.ui.loteDe
import org.junit.Assert.*
import org.junit.Test

/**
 * La regla de la barra de seleccion multiple.
 *
 * ## Que se prueba aqui, y por que no basta con mirarlo
 *
 * La barra no tiene "fijar" y "desfijar": tiene **un** boton que hace lo que
 * le falta al lote. Esa decision se toma con un `all` sobre una lista, y es
 * justo la clase de cosa que se escribe al reves sin que nada se queje: la
 * pantalla sigue compilando, el boton sigue apareciendo, y lo unico que pasa
 * es que desfija doce chats cuando le pediste fijarlos.
 *
 * Los casos que importan son los **mixtos**, que son los que no se prueban a
 * mano porque hay que construirlos a proposito.
 */
class SeleccionDeChatsTest {

    private fun chat(
        id: String,
        fijado: Boolean = false,
        silenciadoHasta: Long = 0L,
        noLeidos: Int = 0,
        marcadaNoLeida: Boolean = false,
    ) = ChatFila(
        id = id,
        tipo = "directa",
        nombre = id,
        nombreMostrado = "",
        participantes = "",
        avatarUsername = id,
        avatarVersion = 0L,
        noLeidos = noLeidos,
        ultimoTexto = null,
        ultimaFecha = null,
        ultimoEsMio = null,
        ultimoAutor = null,
        ultimoEstado = null,
        ultimoAdjuntoClase = null,
        ultimoAdjuntoNombre = null,
        miRol = "miembro",
        miJerarquia = 10,
        silenciadoHasta = silenciadoHasta,
        archivado = false,
        fijado = fijado,
        marcadaNoLeida = marcadaNoLeida,
        soyMiembro = true,
    )

    // ============================================================
    //  La regla: el boton hace lo que le FALTA al lote
    // ============================================================

    @Test
    fun `con todos fijados el boton ofrece quitar`() {
        val l = loteDe(listOf(chat("a", fijado = true), chat("b", fijado = true)))
        assertTrue(l.todosFijados)
    }

    @Test
    fun `con uno solo sin fijar de doce, el boton FIJA`() {
        // El caso mixto, y el que decide el diseño. "La mayoria manda" suena
        // razonable y es peor: con siete de doce desfijaria y con seis
        // fijaria, asi que dos toques seguidos harian cosas distintas por una
        // cuenta que nadie hizo.
        val lote = (1..11).map { chat("f$it", fijado = true) } + chat("suelto")
        assertFalse("un lote mixto tiene que FIJAR, no quitar", loteDe(lote).todosFijados)
    }

    @Test
    fun `un silencio VENCIDO no cuenta como silenciado`() {
        // La columna guarda hasta cuando, no si. Mirandola en crudo
        // (`!= 0`), un chat silenciado hasta ayer ofreceria hoy "quitar
        // silencio" sobre un silencio que ya no existe, y el boton no haria
        // nada visible.
        val ayer = System.currentTimeMillis() - 86_400_000
        assertFalse(loteDe(listOf(chat("a", silenciadoHasta = ayer))).todosSilenciados)
    }

    @Test
    fun `el silencio para siempre si cuenta`() {
        // -1 es "para siempre" y es un valor negativo: una comparacion
        // ingenua contra `System.currentTimeMillis()` lo dejaria fuera.
        assertTrue(loteDe(listOf(chat("a", silenciadoHasta = -1L))).todosSilenciados)
    }

    @Test
    fun `el silencio con fecha futura cuenta`() {
        val manana = System.currentTimeMillis() + 86_400_000
        assertTrue(loteDe(listOf(chat("a", silenciadoHasta = manana))).todosSilenciados)
    }

    // ============================================================
    //  Leidas: dos formas de estar pendiente, un solo boton
    // ============================================================

    @Test
    fun `un chat marcado a mano como no leido cuenta como pendiente`() {
        // Sin mensajes nuevos pero marcado a mano. Si solo se mirara
        // `noLeidos`, el boton diria "marcar como no leidas" sobre un chat que
        // ya lo esta, y tocarlo no cambiaria nada.
        val l = loteDe(listOf(chat("a", marcadaNoLeida = true)))
        assertFalse("la marca manual tambien es estar pendiente", l.todosLeidos)
    }

    @Test
    fun `con mensajes nuevos tampoco esta todo leido`() {
        assertFalse(loteDe(listOf(chat("a", noLeidos = 3))).todosLeidos)
    }

    @Test
    fun `uno pendiente entre diez leidos basta para ofrecer marcar leidas`() {
        val lote = (1..10).map { chat("l$it") } + chat("pendiente", noLeidos = 1)
        assertFalse(loteDe(lote).todosLeidos)
    }

    @Test
    fun `todos leidos de verdad ofrece marcarlos como no leidos`() {
        assertTrue(loteDe(listOf(chat("a"), chat("b"))).todosLeidos)
    }

    // ============================================================
    //  "Mas", que solo existe con uno
    // ============================================================

    @Test
    fun `con un solo chat marcado se ofrece Mas`() {
        // La hoja de acciones de un chat tiene cosas sin version en lote
        // -bloquear a alguien, denunciar-, y es a lo que se llega desde aqui
        // ahora que mantener pulsado selecciona en vez de abrirla.
        assertNotNull(loteDe(listOf(chat("a"))).unoSolo)
    }

    @Test
    fun `con dos o mas no se ofrece`() {
        assertNull(loteDe(listOf(chat("a"), chat("b"))).unoSolo)
    }

    // ============================================================
    //  El lote vacio, que es donde `all` miente
    // ============================================================

    @Test
    fun `un lote vacio no dice que TODOS estan fijados`() {
        // `emptyList().all { ... }` devuelve true, asi que sin la guarda la
        // barra ofreceria "quitar de fijados" sobre nada. La pantalla sale del
        // modo seleccion antes de dibujarlo, pero una funcion que miente en su
        // caso limite termina usandose en otro sitio donde nadie la protege.
        val l = loteDe(emptyList())
        assertEquals(0, l.cuantos)
        assertFalse(l.todosFijados)
        assertFalse(l.todosSilenciados)
        assertFalse(l.todosLeidos)
        assertNull(l.unoSolo)
    }
}
