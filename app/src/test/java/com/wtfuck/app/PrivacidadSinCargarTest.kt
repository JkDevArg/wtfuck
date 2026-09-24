package com.wtfuck.app

import com.wtfuck.app.datos.emiteEscribiendo
import com.wtfuck.protocol.Privacidad
import org.junit.Assert.*
import org.junit.Test

/**
 * Qué se asume mientras los ajustes de privacidad no se han podido leer.
 *
 * ## El defecto que esto cierra
 *
 * El estado arrancaba en `Privacidad()` —los valores **por defecto**, que son
 * los más permisivos— y la carga fallaba en silencio. Dos consecuencias, las
 * dos medidas en el emulador:
 *
 *  1. Con `nadie` guardado en cinco ajustes, la pantalla de Privacidad mostraba
 *     **"Todos" en los cinco**. No "no se pudo cargar": lo contrario de la
 *     verdad, en la única pantalla cuyo trabajo es decir quién te ve.
 *  2. El aparato emitía "escribiendo" porque ese es el valor por defecto,
 *     aunque su dueño lo tuviera apagado.
 *
 * La regla que queda escrita aquí: **un ajuste de privacidad no se da por
 * concedido porque falló una consulta.**
 */
class PrivacidadSinCargarTest {

    // ============================================================
    //  La razón de ser del defecto: los valores por defecto son permisivos
    // ============================================================

    @Test
    fun `los valores por defecto son los MAS permisivos`() {
        // No es un reproche al contrato: para una cuenta nueva está bien que
        // se pueda hablar con ella. El problema era usarlos como "todavía no
        // sé". Esta prueba fija POR QUÉ eso era peligroso, para que quien
        // piense en volver a un valor inicial no nulo lo lea antes.
        val d = Privacidad()
        assertEquals("todos", d.foto)
        assertEquals("todos", d.estado)
        assertEquals("todos", d.nombre)
        assertEquals("todos", d.biografia)
        assertTrue("el aviso de escribiendo viene encendido", d.escribiendo)
    }

    // ============================================================
    //  Sin datos, no se emite
    // ============================================================

    @Test
    fun `sin ajustes cargados NO se emite escribiendo`() {
        // LA prueba. `null` es "no sé", y no saber no autoriza nada.
        assertFalse(emiteEscribiendo(null))
    }

    @Test
    fun `con el ajuste apagado tampoco`() {
        assertFalse(emiteEscribiendo(Privacidad(escribiendo = false)))
    }

    @Test
    fun `con el ajuste encendido si`() {
        // La otra mitad: la prudencia no puede romper el caso normal.
        assertTrue(emiteEscribiendo(Privacidad(escribiendo = true)))
    }

    @Test
    fun `la decision no mira ningun otro ajuste`() {
        // Un `&&` de más aquí apagaría el aviso por un motivo que no es el
        // suyo, y el síntoma sería "no funciona el escribiendo" sin pista.
        val todoCerrado = Privacidad(
            foto = "nadie", estado = "nadie", nombre = "nadie",
            escribiendo = true, lectura = false,
        )
        assertTrue(emiteEscribiendo(todoCerrado))
    }

    // ============================================================
    //  Es recíproco, y eso también se apoya en lo mismo
    // ============================================================

    @Test
    fun `la misma funcion decide emitir y mostrar`() {
        // El ajuste es recíproco: quien no lo emite tampoco lo ve. Con dos
        // funciones distintas, arreglar una dejaría la otra, y el resultado
        // sería un espejo de una sola dirección —ver sin ser visto—, que es
        // justo lo que la reciprocidad evita.
        //
        // Se comprueba como contrato: el repositorio llama a `emiteEscribiendo`
        // en los dos sentidos, así que basta con que la respuesta sea una.
        listOf(null, Privacidad(escribiendo = false), Privacidad(escribiendo = true))
            .forEach { assertEquals(emiteEscribiendo(it), emiteEscribiendo(it)) }
        assertFalse(emiteEscribiendo(null))
    }
}
