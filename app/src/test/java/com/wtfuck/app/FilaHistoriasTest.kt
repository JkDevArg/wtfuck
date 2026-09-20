package com.wtfuck.app

import com.wtfuck.app.datos.HistoriaEnt
import com.wtfuck.app.ui.autoresConHistorias
import org.junit.Assert.*
import org.junit.Test

/**
 * La regla de la fila de historias.
 *
 * Existe porque es facil de romper sin darse cuenta al tocar el orden, y es la
 * que impide prometer historias que al tocarlas no estan.
 */
class FilaHistoriasTest {

    private fun h(
        id: String,
        autor: String,
        conContenido: Boolean = true,
        vista: Boolean = false,
        mia: Boolean = false,
    ) = HistoriaEnt(
        id = id,
        autor = autor,
        texto = if (conContenido) "algo" else "",
        conContenido = conContenido,
        vista = vista,
        mia = mia,
        expiraEn = Long.MAX_VALUE,
    )

    @Test
    fun `una historia sin sobre no anuncia a su autor`() {
        // El caso que se veia en pantalla: una entrada en la fila que al
        // abrirla solo decia "no se pudo descifrar", durante 24 horas.
        val fila = autoresConHistorias(listOf(h("1", "ana", conContenido = false)), "yo")
        assertTrue("un autor sin nada abrible no entra en la fila", fila.isEmpty())
    }

    @Test
    fun `basta UNA con contenido para que el autor aparezca`() {
        // No se esconde al autor entero por una que fallo: las otras si se
        // pueden ver, y esconderlas seria el error contrario.
        val fila = autoresConHistorias(
            listOf(h("1", "ana", conContenido = false), h("2", "ana")),
            "yo",
        )
        assertEquals(1, fila.size)
        assertEquals("ana", fila[0].first)
        assertEquals("solo se listan las abribles", listOf("2"), fila[0].second.map { it.id })
    }

    @Test
    fun `lo mio va primero, y despues lo que no vi`() {
        val fila = autoresConHistorias(
            listOf(
                h("1", "zoe", vista = true),
                h("2", "ana", vista = false),
                h("3", "yo", mia = true, vista = true),
            ),
            "yo",
        )
        assertEquals(listOf("yo", "ana", "zoe"), fila.map { it.first })
    }

    @Test
    fun `un autor con varias es UNA entrada`() {
        // La fila no es una lista de historias: es una lista de personas que
        // tienen algo que contar.
        val fila = autoresConHistorias(
            (1..5).map { h(it.toString(), "ana") },
            "yo",
        )
        assertEquals(1, fila.size)
        assertEquals(5, fila[0].second.size)
    }

    @Test
    fun `sin historias la fila queda vacia y no revienta`() {
        assertTrue(autoresConHistorias(emptyList(), "yo").isEmpty())
    }
}
