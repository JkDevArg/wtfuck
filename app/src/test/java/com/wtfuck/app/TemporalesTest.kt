package com.wtfuck.app

import com.wtfuck.app.datos.Temporales
import com.wtfuck.protocol.DuracionMensaje
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los mensajes temporales: la cuenta del vencimiento y como se escribe el plazo.
 *
 * Existe porque esta funcion se rompio una vez EN SILENCIO. `expiraEn` lo
 * ponia solo el emisor, asi que el mensaje temporal desaparecia del telefono de
 * quien lo escribio y se quedaba para siempre en el de quien lo leyo. Nada
 * fallaba, nada avisaba: la app prometia una cosa y hacia la mitad. Estas
 * pruebas cubren la parte que decide si una copia se borra.
 */
class TemporalesTest {

    private val AHORA = 1_700_000_000_000L

    @Test
    fun `sin temporizador no vence`() {
        assertEquals(0L, Temporales.vencimiento(0, AHORA, AHORA))
    }

    @Test
    fun `un temporizador negativo tampoco vence`() {
        // Puede llegar de un servidor futuro, de un cliente modificado o de una
        // fila a medio migrar. "No entiendo este valor" tiene que significar
        // "permanente" y no "borralo ya".
        assertEquals(0L, Temporales.vencimiento(-1, AHORA, AHORA))
    }

    @Test
    fun `vence a los N segundos de escrito`() {
        val vence = Temporales.vencimiento(DuracionMensaje.DIA_1, AHORA, AHORA)
        assertEquals(AHORA + 86_400_000L, vence)
    }

    @Test
    fun `el plazo mas largo no desborda`() {
        // 90 dias en ms son 7.776.000.000: mas de lo que cabe en un Int. Si la
        // multiplicacion se hiciera en Int, el plazo MAS LARGO daria negativo y
        // el mensaje naceria vencido.
        val vence = Temporales.vencimiento(7_776_000, AHORA, AHORA)
        assertTrue("el vencimiento quedo en el pasado: $vence", vence > AHORA)
        assertEquals(AHORA + 7_776_000_000L, vence)
    }

    @Test
    fun `un emisor con el reloj adelantado no estira el plazo`() {
        // El ataque: mandar un mensaje "de un minuto" con `creadoEn` dentro de
        // una semana, para que en el telefono ajeno viva una semana. El tope lo
        // corta en el plazo medido con el reloj de ESTE telefono.
        val unaSemanaEnElFuturo = AHORA + 7 * 24 * 3_600_000L
        val vence = Temporales.vencimiento(DuracionMensaje.MINUTO_1, unaSemanaEnElFuturo, AHORA)
        assertEquals(AHORA + 60_000L, vence)
    }

    @Test
    fun `un mensaje que llega tarde vence antes, no despues`() {
        // Un sobre encolado dos dias con un temporizador de un dia: ya deberia
        // estar borrado, y el barrido se lo lleva en cuanto se guarda. Es el
        // lado correcto en el que equivocarse.
        val haceDosDias = AHORA - 2 * 24 * 3_600_000L
        val vence = Temporales.vencimiento(DuracionMensaje.DIA_1, haceDosDias, AHORA)
        assertTrue("deberia estar vencido ya: $vence vs $AHORA", vence < AHORA)
    }

    @Test
    fun `el tope no castiga un desfase pequeno de reloj`() {
        // Dos telefonos nunca estan exactamente en hora. Un minuto de deriva no
        // debe cambiar nada perceptible en un plazo de un dia.
        val unMinutoAdelantado = AHORA + 60_000L
        val vence = Temporales.vencimiento(DuracionMensaje.DIA_1, unMinutoAdelantado, AHORA)
        assertEquals(AHORA + 86_400_000L, vence)
    }

    // ------------------------------------------------------------ el texto

    @Test
    fun `todas las opciones que ofrece la pantalla las acepta el servidor`() {
        // Si esto falla, la pantalla ofrece un plazo que el servidor rechaza con
        // un 400 y el usuario ve "no se pudo completar" sin entender por que.
        DuracionMensaje.OPCIONES.forEach { s ->
            assertTrue("$s fuera del rango del servidor", DuracionMensaje.valida(s))
        }
    }

    @Test
    fun `permanentes es un valor valido`() {
        assertTrue(DuracionMensaje.valida(null))
    }

    @Test
    fun `el texto de cada opcion se lee bien`() {
        assertEquals("1 minuto", DuracionMensaje.texto(DuracionMensaje.MINUTO_1))
        assertEquals("5 minutos", DuracionMensaje.texto(DuracionMensaje.MINUTOS_5))
        assertEquals("1 hora", DuracionMensaje.texto(DuracionMensaje.HORA_1))
        assertEquals("8 horas", DuracionMensaje.texto(DuracionMensaje.HORAS_8))
        assertEquals("1 dia", DuracionMensaje.texto(DuracionMensaje.DIA_1))
        assertEquals("1 semana", DuracionMensaje.texto(DuracionMensaje.SEMANA_1))
        assertEquals("4 semanas", DuracionMensaje.texto(DuracionMensaje.SEMANAS_4))
    }

    @Test
    fun `el singular no dice 1 dias`() {
        assertEquals("1 minuto", DuracionMensaje.texto(60))
        assertEquals("1 hora", DuracionMensaje.texto(3_600))
        assertEquals("1 dia", DuracionMensaje.texto(86_400))
        assertEquals("1 semana", DuracionMensaje.texto(604_800))
    }

    @Test
    fun `un plazo que la pantalla ya no ofrece se escribe igual`() {
        // Un chat configurado por otra version -o por otro cliente- puede traer
        // un valor que no esta en OPCIONES. Hay que poder escribirlo: dejarlo
        // en blanco haria que el selector pareciera apagado estando encendido.
        assertEquals("3 dias", DuracionMensaje.texto(3 * 86_400))
        assertEquals("45 segundos", DuracionMensaje.texto(45))
        assertEquals("2 horas", DuracionMensaje.texto(7_200))
    }

    @Test
    fun `cero se escribe como desactivado`() {
        assertEquals("desactivado", DuracionMensaje.texto(0))
    }
}
