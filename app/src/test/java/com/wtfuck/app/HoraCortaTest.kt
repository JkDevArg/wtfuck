package com.wtfuck.app

import com.wtfuck.app.ui.horaCorta
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * La marca de tiempo de la lista de chats.
 *
 * ## Qué se arregló
 *
 * Antes era "la hora si es de hoy, la fecha si no", así que un mensaje de ayer
 * salía como **"21/09/26"**. En una lista donde casi todo es de los últimos
 * días, casi todas las filas mostraban una fecha larga que obliga a hacer una
 * cuenta para responder algo que se pregunta de un vistazo.
 *
 * El reloj se inyecta para poder probar los límites, que es lo único que de
 * verdad se rompe aquí: la medianoche y el corte de la semana.
 */
class HoraCortaTest {

    /** Un instante fijo: miércoles, 15:30. */
    private fun cuando(dia: Int, hora: Int = 15, minuto: Int = 30): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, dia, hora, minuto, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val hoy = cuando(23)

    @Test
    fun `de hoy sale la hora`() {
        assertEquals("09:05", horaCorta(cuando(23, 9, 5), hoy))
    }

    @Test
    fun `de ayer dice Ayer, no una fecha`() {
        // El caso que motivó el cambio.
        assertEquals("Ayer", horaCorta(cuando(22), hoy))
    }

    @Test
    fun `de esta semana dice el dia, y en español`() {
        // Tres días atrás: se nombra el día en vez de dar números.
        //
        // Y **en español pase lo que pase**: el nombre salía del idioma del
        // teléfono, así que en un aparato en inglés la lista decía "Sunday"
        // entre textos escritos en español a mano. El idioma de la interfaz
        // lo decide la interfaz.
        val r = horaCorta(cuando(20), hoy)
        assertFalse("no debería ser una fecha: $r", r.contains("/"))
        assertNotEquals("Ayer", r)
        assertTrue("debería empezar en mayúscula: $r", r.first().isUpperCase())
        val dias = listOf(
            "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo",
        )
        assertTrue("no es un día en español: $r", r in dias)
    }

    @Test
    fun `mas de una semana atras, la fecha`() {
        assertEquals("10/09/26", horaCorta(cuando(10), hoy))
    }

    // ============================================================
    //  Los límites, que son lo único que se rompe
    // ============================================================

    @Test
    fun `un mensaje de ayer a las 23 59 sigue siendo Ayer hoy a las 00 01`() {
        // LA prueba del archivo. Contando 24 horas, esto daría "hoy" —han
        // pasado dos minutos— y la fila diría una hora de ayer como si fuera
        // de hoy. Se cuentan días de calendario, no horas.
        val casi = cuando(22, 23, 59)
        val recienPasada = cuando(23, 0, 1)
        assertEquals("Ayer", horaCorta(casi, recienPasada))
    }

    @Test
    fun `un mensaje de hace 20 horas puede ser de hoy`() {
        // El reverso: 20 horas es menos de un día, pero si cruzó la medianoche
        // es ayer, y si no, es hoy. Aquí no cruzó.
        val temprano = cuando(23, 1, 0)
        val tarde = cuando(23, 21, 0)
        assertEquals("01:00", horaCorta(temprano, tarde))
    }

    @Test
    fun `el sexto dia todavia es un dia de la semana y el septimo ya no`() {
        // El corte. Un `<=` en vez de `<` mete siete días en la ventana y
        // entonces "lunes" puede significar dos lunes distintos.
        assertFalse(horaCorta(cuando(17), hoy).contains("/"))
        assertTrue(horaCorta(cuando(16), hoy).contains("/"))
    }

    @Test
    fun `una fecha en el futuro no dice Mañana`() {
        // Pasa con el reloj mal puesto en el otro aparato. "Mañana" en una
        // lista de mensajes ya recibidos es absurdo; se muestra la hora.
        val r = horaCorta(cuando(25), hoy)
        assertTrue("debería ser una hora: $r", r.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `sin fecha no se inventa nada`() {
        // Una conversación sin mensajes. Un "01/01/70" ahí se ve como un error.
        assertEquals("", horaCorta(0L, hoy))
        assertEquals("", horaCorta(-1L, hoy))
    }
}
