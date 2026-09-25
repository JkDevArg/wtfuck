package com.wtfuck.app

import com.wtfuck.app.ui.TeselaCliente
import com.wtfuck.app.contenido.segura
import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.ModoUbicacion
import com.wtfuck.protocol.PuntoEstela
import com.wtfuck.protocol.paraLaRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que el mapa **no** hace.
 *
 * Esta función es la única de la app que le habla a un servidor que no es el
 * nuestro, así que lo que hay que fijar no es que dibuje bien: es que no
 * filtre nada que no se haya decidido filtrar.
 */
class MapaPrivacidadTest {

    // ------------------------------------------------------------------
    // Lo que es de un teléfono no sale del teléfono
    // ------------------------------------------------------------------

    @Test
    fun `la estela y la hora local no viajan`() {
        val guardada = Carga.UbicacionEnVivo(
            mensajeId = "m1",
            lat = -12.0464, lon = -77.0428, hasta = 9_999_999L, secuencia = 7,
            recibidaEn = 1_700_000_000_000L,
            estela = (0..40).map { PuntoEstela(-12.0 + it * 0.001, -77.0, it.toLong()) },
        )
        val red = guardada.paraLaRed()
        assertEquals(0L, red.recibidaEn)
        assertTrue(red.estela.isEmpty())
        // Y lo que SÍ es del compartido sigue entero: recortar de más sería
        // tan defecto como recortar de menos.
        assertEquals("m1", red.mensajeId)
        assertEquals(7, red.secuencia)
        assertEquals(9_999_999L, red.hasta)
        assertEquals(-12.0464, red.lat, 1e-9)
    }

    @Test
    fun `el permiso de quien comparte si viaja`() {
        // Es lo único de esta familia que TIENE que cruzar: del otro lado no
        // hay forma de saber si se aceptó el mapa si no se lo dicen.
        val conMapa = Carga.UbicacionEnVivo(
            mensajeId = "m", lat = 0.0, lon = 0.0, hasta = 1L, conMapa = true,
        )
        assertTrue(conMapa.paraLaRed().conMapa)
        assertFalse(conMapa.copy(conMapa = false).paraLaRed().conMapa)
    }

    @Test
    fun `una carga vieja cae en el modo que no filtra nada`() {
        // Los dos lados pueden tener versiones distintas. Una carga sin el
        // campo tiene que leerse como "sin mapa", nunca al revés.
        val sinElCampo = Carga.UbicacionEnVivo(mensajeId = "m", lat = 0.0, lon = 0.0, hasta = 1L)
        assertFalse(sinElCampo.conMapa)
        assertEquals(ModoUbicacion.OCULTO, ModoUbicacion.de(sinElCampo.conMapa))
    }

    @Test
    fun `los dos modos y nada mas`() {
        assertEquals(2, ModoUbicacion.entries.size)
        assertFalse(ModoUbicacion.OCULTO.conMapa)
        assertTrue(ModoUbicacion.VISIBLE.conMapa)
    }

    // ------------------------------------------------------------------
    // Un punto invalido no puede terminar en una peticion
    // ------------------------------------------------------------------

    @Test
    fun `una coordenada imposible no da punto para dibujar`() {
        // El punto sale por la MISMA puerta que el `geo:`, que es donde se
        // valida. Sin eso, un sobre armado a mano con NaN se convertiria en
        // una peticion con NaN en la URL contra un servidor ajeno.
        val malos = listOf(
            Double.NaN to 0.0,
            0.0 to Double.POSITIVE_INFINITY,
            91.0 to 0.0,
            0.0 to -181.0,
        )
        for ((lat, lon) in malos) {
            val v = segura(Carga.Ubicacion(lat, lon, conMapa = true))
            assertNull("lat=$lat lon=$lon", v.punto)
            assertNull(v.geoUri)
            // El permiso se conserva aunque no haya nada que dibujar: no es
            // el sitio donde se decide eso.
            assertTrue(v.conMapa)
        }
    }

    @Test
    fun `una coordenada buena si da punto`() {
        val v = segura(Carga.Ubicacion(-12.0464, -77.0428, 5, "Oficina", conMapa = true))
        val p = v.punto ?: error("tendria que haber punto")
        assertEquals(-12.0464, p.lat, 1e-9)
        assertEquals(-77.0428, p.lon, 1e-9)
    }

    @Test
    fun `una ubicacion normal no lleva mapa si no se pidio`() {
        assertFalse(segura(Carga.Ubicacion(-12.0, -77.0)).conMapa)
    }

    // ------------------------------------------------------------------
    // Las URLs que se piden
    // ------------------------------------------------------------------

    @Test
    fun `no se pide nada arriba del polo`() {
        // A zoom 2 hay 4 filas: la 4 no existe. Sin esto se pediría una
        // baldosa inexistente en cada repintado, que es ruido gratis contra
        // un servidor ajeno.
        assertNull(TeselaCliente.url(2, 0, 4))
        assertNull(TeselaCliente.url(2, 0, -1))
    }

    @Test
    fun `la columna da la vuelta en vez de salirse`() {
        assertEquals(TeselaCliente.url(2, 0, 1), TeselaCliente.url(2, 4, 1))
        assertEquals(TeselaCliente.url(2, 3, 1), TeselaCliente.url(2, -1, 1))
    }

    @Test
    fun `la url no lleva nada de la cuenta`() {
        val u = TeselaCliente.url(12, 1171, 2185) ?: error("sin servidor de baldosas")
        // Una URL de baldosa es tres números. Si algún día alguien mete una
        // clave de API o un identificador en la plantilla, esto lo dice: ese
        // identificador ata cada petición a esta persona.
        for (sospechoso in listOf("token", "key", "user", "id=", "@", "session")) {
            assertFalse("la url dice '$sospechoso': $u", u.contains(sospechoso, ignoreCase = true))
        }
        assertTrue(u.contains("12") && u.contains("1171") && u.contains("2185"))
    }
}
