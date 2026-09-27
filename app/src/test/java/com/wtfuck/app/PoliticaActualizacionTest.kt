package com.wtfuck.app

import com.wtfuck.app.datos.PoliticaActualizacion.estaObsoleta
import com.wtfuck.app.datos.PoliticaActualizacion.hayQueBajar
import com.wtfuck.protocol.VersionResp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se acepta instalar, y sobre todo lo que no.
 *
 * ## Por qué estas pruebas y no otras
 *
 * La actualización automática es, vista de frente, un canal de ejecución
 * remota de código: el servidor dice "baja esto e instálalo". Lo que impide
 * que comprometer el servidor sea comprometer todos los teléfonos es que
 * **Android rechaza una actualización firmada con otra clave** — el ancla de
 * confianza es el keystore, no el servidor.
 *
 * Eso deja un hueco que Android no tapa por nosotros y que sí depende de este
 * código: **el downgrade**. Un servidor hostil no puede inyectar una versión
 * propia, pero sí puede ofrecer una *antigua y legítima* — por ejemplo, una
 * con un fallo que ya se arregló. Esa es la regla que se prueba aquí.
 */
class PoliticaActualizacionTest {

    private fun v(
        code: Int,
        url: String = "https://ejemplo.com/wtfuck.apk",
        sha: String = "a".repeat(64),
        minima: Int = 0,
    ) = VersionResp(versionCode = code, url = url, sha256 = sha, minima = minima)

    @Test
    fun `una version mas nueva se baja`() {
        assertTrue(hayQueBajar(v(5), instalada = 4))
    }

    @Test
    fun `la misma version no se baja`() {
        // No es paranoia: es el caso NORMAL. La app pregunta en cada arranque
        // y en cada reconexion del socket, asi que la respuesta habitual es
        // "estas en la ultima". Si esto devolviera true, la app ofreceria
        // reinstalarse a si misma cada vez que se abre.
        assertFalse(hayQueBajar(v(4), instalada = 4))
    }

    @Test
    fun `una version ANTERIOR no se baja aunque el servidor la anuncie`() {
        // El ataque que esta linea impide. Un servidor comprometido no puede
        // firmar un APK propio -eso lo para Android- pero si puede servir uno
        // viejo y legitimo, con un fallo que ya se arreglo, y hacer que los
        // telefonos retrocedan a el.
        assertFalse(hayQueBajar(v(2), instalada = 7))
    }

    @Test
    fun `por http no se baja nada`() {
        // De esa URL sale un archivo que se va a instalar. Por http, quien
        // este en el camino elige cual.
        assertFalse(hayQueBajar(v(9, url = "http://ejemplo.com/x.apk"), instalada = 1))
    }

    @Test
    fun `una URL vacia no se baja`() {
        // Pasa de verdad: el servidor con `WTFUCK_APK_VERSION` puesta y la URL
        // sin poner. Sin esta comprobacion la app intentaria descargar "" y
        // fallaria con un error que no dice nada del error real.
        assertFalse(hayQueBajar(v(9, url = ""), instalada = 1))
    }

    @Test
    fun `sin huella completa no se baja`() {
        assertFalse(hayQueBajar(v(9, sha = ""), instalada = 1))
        assertFalse(hayQueBajar(v(9, sha = "abc"), instalada = 1))
        assertFalse(hayQueBajar(v(9, sha = "a".repeat(63)), instalada = 1))
    }

    // --- obsolescencia ------------------------------------------------

    @Test
    fun `sin minima declarada nadie esta obsoleto`() {
        // 0 tiene que significar "ninguna version queda fuera". Es el valor
        // por defecto, o sea el de todo servidor que no configuro esto: si
        // aqui saliera true, actualizar el servidor dejaria a todo el mundo
        // con un aviso que no se puede cerrar.
        assertFalse(estaObsoleta(v(9, minima = 0), instalada = 1))
    }

    @Test
    fun `por debajo de la minima si`() {
        assertTrue(estaObsoleta(v(9, minima = 5), instalada = 4))
    }

    @Test
    fun `justo en la minima no`() {
        // El limite es inclusivo: `minima = 5` significa "la 5 sirve", no "hay
        // que pasar de la 5". Confundirlo deja obsoleta a la version que se
        // acaba de declarar como la minima buena.
        assertFalse(estaObsoleta(v(9, minima = 5), instalada = 5))
    }

    @Test
    fun `estar obsoleto no depende de que la descarga sea valida`() {
        // Son dos preguntas distintas y conviene que no se contaminen: "tu
        // version ya no sirve" es cierto aunque la URL publicada este mal.
        // Si se mezclaran, un servidor con la URL mal puesta dejaria de avisar
        // a las apps obsoletas, que es justo cuando mas falta hace saberlo.
        assertTrue(estaObsoleta(v(9, url = "", sha = "", minima = 5), instalada = 4))
    }
}
