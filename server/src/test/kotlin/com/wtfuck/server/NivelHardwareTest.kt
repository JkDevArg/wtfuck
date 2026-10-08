package com.wtfuck.server

import com.wtfuck.protocol.NivelHardware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que niveles de hardware admite el servidor, y con que defecto.
 *
 * ## El defecto que motivo estas pruebas
 *
 * `WTFUCK_PERMITIR_SOFTWARE_DEV` valia `true` cuando no estaba definida. Un
 * despliegue que se la olvidara aceptaba emuladores en el registro, la
 * vinculacion y la recuperacion, y el unico aviso era una linea INFO en la
 * bitacora. Las pruebas de `leerPermitirSoftwareDev` son las que se caen si
 * alguien vuelve a poner el defecto abierto.
 *
 * ## El limite que fijan a proposito
 *
 * `un TEE declarado se acepta sin prueba` NO describe una virtud: describe que
 * el nivel es el que el cliente dice, porque el servidor no verifica ninguna
 * cadena de atestacion. Se fija para que el dia que alguien implemente la
 * verificacion esta prueba falle y obligue a reescribir docs/04-DEVICE-BINDING.md
 * en el mismo cambio. Mientras pase, el documento no puede prometer otra cosa.
 *
 * Sin base de datos: todo esto es logica pura.
 */
class NivelHardwareTest {

    // ------------------------------------------------------------------
    //  El defecto de la variable
    // ------------------------------------------------------------------

    @Test
    fun `sin la variable se cierra`() {
        assertFalse(Config.leerPermitirSoftwareDev(null))
    }

    @Test
    fun `solo true abre, sin importar mayusculas ni espacios`() {
        assertTrue(Config.leerPermitirSoftwareDev("true"))
        assertTrue(Config.leerPermitirSoftwareDev("TRUE"))
        assertTrue(Config.leerPermitirSoftwareDev(" true "))
    }

    @Test
    fun `cualquier otra cosa cierra`() {
        // "1" y "si" son lo que alguien escribiria creyendo que abre. Cerrar
        // ante la duda es el punto: el que se equivoca lo nota en desarrollo,
        // donde un emulador rechazado se ve enseguida, y no en produccion.
        for (v in listOf("", "false", "FALSE", "1", "si", "yes", "verdadero", "ture")) {
            assertFalse("'$v' no deberia abrir", Config.leerPermitirSoftwareDev(v))
        }
    }

    // ------------------------------------------------------------------
    //  Lo que se admite con el interruptor cerrado (produccion)
    // ------------------------------------------------------------------

    @Test
    fun `cerrado, un SOFTWARE_DEV no crea cuenta ni recupera`() {
        val e = falla { Repo.nivelParaPrincipal(NivelHardware.SOFTWARE_DEV, permitirSoftwareDev = false) }
        assertEquals(403, e.codigo)
    }

    @Test
    fun `cerrado, un SOFTWARE_DEV tampoco se vincula`() {
        val e = falla { Repo.nivelParaVincular(NivelHardware.SOFTWARE_DEV, permitirSoftwareDev = false) }
        assertEquals(403, e.codigo)
    }

    @Test
    fun `abierto, un SOFTWARE_DEV entra por los dos caminos`() {
        assertEquals(
            NivelHardware.SOFTWARE_DEV,
            Repo.nivelParaPrincipal(NivelHardware.SOFTWARE_DEV, permitirSoftwareDev = true),
        )
        assertEquals(
            NivelHardware.SOFTWARE_DEV,
            Repo.nivelParaVincular(NivelHardware.SOFTWARE_DEV, permitirSoftwareDev = true),
        )
    }

    @Test
    fun `un navegador solo entra vinculandose, con el interruptor como este`() {
        for (permitir in listOf(false, true)) {
            assertEquals(403, falla { Repo.nivelParaPrincipal(NivelHardware.NAVEGADOR, permitir) }.codigo)
            assertEquals(NivelHardware.NAVEGADOR, Repo.nivelParaVincular(NivelHardware.NAVEGADOR, permitir))
        }
    }

    @Test
    fun `un nivel desconocido es 400`() {
        assertEquals(400, falla { Repo.nivelParaPrincipal("CUALQUIERA", permitirSoftwareDev = true) }.codigo)
        assertEquals(400, falla { Repo.nivelParaVincular("tee", permitirSoftwareDev = true) }.codigo)
    }

    // ------------------------------------------------------------------
    //  El limite conocido
    // ------------------------------------------------------------------

    @Test
    fun `un TEE declarado se acepta sin prueba - limite conocido, ver docs 04`() {
        // Nada acompana a este string: ni cadena de certificados ni reto. Si
        // esta prueba empieza a fallar porque ahora se exige la cadena, es la
        // buena noticia; reescribirla junto con el documento.
        assertEquals(NivelHardware.TEE, Repo.nivelParaPrincipal(NivelHardware.TEE, permitirSoftwareDev = false))
        assertEquals(
            NivelHardware.STRONGBOX,
            Repo.nivelParaPrincipal(NivelHardware.STRONGBOX, permitirSoftwareDev = false),
        )
    }

    private fun falla(bloque: () -> Unit): ErrorNegocio {
        try {
            bloque()
        } catch (e: ErrorNegocio) {
            return e
        }
        throw AssertionError("se esperaba un ErrorNegocio")
    }
}
