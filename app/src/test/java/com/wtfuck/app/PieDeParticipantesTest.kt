package com.wtfuck.app

import com.wtfuck.app.ui.pieDeParticipantes
import org.junit.Assert.*
import org.junit.Test

/**
 * La línea que dice en qué anda cada persona de una llamada de grupo.
 *
 * ## Qué se arregló
 *
 * Decía **"con joaquin, rocio"**, que era la lista de a quién se había
 * llamado, no de quién estaba. Desde el módulo AF un rechazo ya no corta la
 * llamada de los demás, así que "rocio" podía seguir ahí habiendo dicho que
 * no: la pantalla nombraba gente que no estaba.
 *
 * Se prueba aparte de la pantalla porque es la única parte con decisiones —qué
 * agrupar, en qué orden, cómo se dice— y porque el orden estable no se ve
 * mirando una captura: se ve cuando llegan dos avisos seguidos y la línea no
 * se reordena sola.
 */
class PieDeParticipantesTest {

    @Test
    fun `mientras suenan todos, dice a quienes se esta llamando`() {
        val pie = pieDeParticipantes(mapOf("rocio" to "sonando", "joaquin" to "sonando"))
        assertEquals("llamando a joaquin, rocio", pie)
    }

    @Test
    fun `quien entro va primero, que es lo que se quiere saber`() {
        val pie = pieDeParticipantes(mapOf("rocio" to "sonando", "joaquin" to "dentro"))
        assertEquals("con joaquin · llamando a rocio", pie)
    }

    /** El caso que la línea vieja no sabía contar. */
    @Test
    fun `quien rechazo deja de contarse como llamado`() {
        val pie = pieDeParticipantes(mapOf("joaquin" to "dentro", "rocio" to "rechazo"))
        assertEquals("con joaquin · rocio no entró", pie)
        assertFalse("seguia diciendo que se la estaba llamando", pie.contains("llamando a rocio"))
    }

    /**
     * Rechazar y colgar se juntan.
     *
     * Los dos significan "esta persona ya no está", y separarlos daría tres
     * frases donde la diferencia no cambia nada de lo que se puede hacer.
     */
    @Test
    fun `rechazar y colgar cuentan igual`() {
        val pie = pieDeParticipantes(
            mapOf("ana" to "dentro", "beto" to "rechazo", "ceci" to "fuera"),
        )
        assertEquals("con ana · beto, ceci no entraron", pie)
    }

    /** Una sola persona no "entraron". */
    @Test
    fun `el plural concuerda`() {
        assertTrue(pieDeParticipantes(mapOf("ana" to "rechazo")).endsWith("no entró"))
        assertTrue(
            pieDeParticipantes(mapOf("ana" to "rechazo", "beto" to "rechazo"))
                .endsWith("no entraron"),
        )
    }

    /**
     * El orden no depende de en qué orden llegaron los avisos.
     *
     * Sin esto la línea se reordena sola cada vez que llega uno y la pantalla
     * parpadea sin que haya pasado nada. Es el tipo de defecto que no se ve en
     * una captura y sí molesta usando la app.
     */
    @Test
    fun `el orden es estable venga como venga el mapa`() {
        val a = pieDeParticipantes(
            linkedMapOf("rocio" to "dentro", "ana" to "dentro", "beto" to "sonando"),
        )
        val b = pieDeParticipantes(
            linkedMapOf("beto" to "sonando", "ana" to "dentro", "rocio" to "dentro"),
        )
        assertEquals(a, b)
        assertEquals("con ana, rocio · llamando a beto", a)
    }

    /** Sin nadie, nada: una línea vacía no se dibuja. */
    @Test
    fun `sin participantes no dice nada`() {
        assertEquals("", pieDeParticipantes(emptyMap()))
    }

    /** Un estado que la app no conoce no se inventa una frase. */
    @Test
    fun `un estado desconocido se ignora en vez de adivinar`() {
        assertEquals("con ana", pieDeParticipantes(mapOf("ana" to "dentro", "x" to "inventado")))
    }
}
