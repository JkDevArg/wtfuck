package com.wtfuck.app

import com.wtfuck.app.ui.conQuienes
import com.wtfuck.protocol.LlamadaEnHistorial
import org.junit.Assert.*
import org.junit.Test

/**
 * La línea de "con quiénes" del historial de llamadas.
 *
 * ## Qué se arregló
 *
 * Dos llamadas al mismo grupo se veían como **dos filas idénticas**: el mismo
 * título, la misma hora relativa, y ninguna forma de saber si habían sido con
 * las mismas personas. En un grupo, con quién fue es lo único que cambia entre
 * una llamada y otra — y era justo el dato que no se mostraba, aunque el
 * servidor ya lo mandaba en `participantes`.
 *
 * Y sólo en los grupos: en una directa el título **ya es** la persona, así que
 * repetirlo debajo no dice nada.
 */
class ConQuienesTest {

    private fun llamada(
        esGrupo: Boolean,
        vararg gente: String,
    ) = LlamadaEnHistorial(
        id = "1",
        conversacionId = "c",
        titulo = if (esGrupo) "Equipo" else gente.firstOrNull().orEmpty(),
        iniciadaEn = 0L,
        participantes = gente.toList(),
        esGrupo = esGrupo,
    )

    /** En una directa, el título ya es la persona. */
    @Test
    fun `una directa no repite el nombre debajo`() {
        assertNull(conQuienes(llamada(esGrupo = false, "joaquin")))
    }

    @Test
    fun `un grupo de dos los nombra a los dos`() {
        assertEquals("con joaquin, rocio", conQuienes(llamada(true, "rocio", "joaquin")))
    }

    /** Uno solo también se nombra: fue una llamada de grupo a una persona. */
    @Test
    fun `un grupo con un solo invitado lo nombra`() {
        assertEquals("con joaquin", conQuienes(llamada(true, "joaquin")))
    }

    /**
     * Con más de dos, se cuentan los que faltan.
     *
     * Cuatro nombres no entran en una línea de lista, y cortarlos con puntos
     * suspensivos no dice **cuántos** faltan, que es lo único que se quiere
     * saber cuando no caben.
     */
    @Test
    fun `con mas de dos, dice cuantos faltan`() {
        assertEquals(
            "con ana, beto y 1 más",
            conQuienes(llamada(true, "ana", "beto", "ceci")),
        )
        assertEquals(
            "con ana, beto y 2 más",
            conQuienes(llamada(true, "ana", "beto", "ceci", "dani")),
        )
    }

    /** El orden no depende de cómo venga la lista del servidor. */
    @Test
    fun `el orden es estable`() {
        val a = conQuienes(llamada(true, "rocio", "ana", "beto"))
        val b = conQuienes(llamada(true, "beto", "rocio", "ana"))
        assertEquals(a, b)
        assertEquals("con ana, beto y 1 más", a)
    }

    /**
     * Un grupo del que no vino nadie no inventa una línea vacía.
     *
     * Pasa: si la llamada se canceló antes de que el servidor resolviera los
     * participantes, la lista llega vacía.
     */
    @Test
    fun `un grupo sin participantes no dibuja nada`() {
        assertNull(conQuienes(llamada(esGrupo = true)))
    }
}
