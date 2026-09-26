package com.wtfuck.app

import com.wtfuck.protocol.ClaseBt
import com.wtfuck.protocol.MensajeCerca
import com.wtfuck.protocol.TipoCifrado
import com.wtfuck.protocol.Trama
import com.wtfuck.protocol.aceptable
import com.wtfuck.protocol.valeLaPenaIntentar
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.random.Random

/**
 * El transporte de cerca, en lo que se puede probar sin una radio.
 *
 * ## Qué queda fuera, y se dice antes
 *
 * Que dos teléfonos se encuentren y se conecten **no está probado aquí**: eso
 * necesita dos radios de verdad. Lo que sí se prueba es todo lo que decide si
 * un byte ajeno puede hacer daño: el marco de las tramas y la regla que dice
 * qué sobres se aceptan por el aire.
 *
 * Que es donde está el riesgo. Un enlace que no conecta es una función que no
 * anda; un enlace que acepta lo que no debe es otra cosa.
 */
class CercaTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------
    // La regla que importa
    // ------------------------------------------------------------------

    @Test
    fun `por el aire NO se abre una sesion nueva`() {
        // Es la defensa central de este transporte.
        //
        // Un `PreKeySignalMessage` establece una sesión con la identidad que
        // traiga dentro. Por el servidor eso está bien: hay una cuenta detrás,
        // con su contraseña y su dispositivo registrado. Por el aire no hay
        // nada de eso, así que cualquiera con una radio y esta app modificada
        // podría abrir una sesión a nombre de quien quisiera y aparecer en la
        // pantalla de alguien.
        assertFalse(aceptable(TipoCifrado.PREPARADO, haySesion = false))
        // Y tampoco teniendo sesión: si ya hay una, un mensaje que abre otra
        // sólo puede ser alguien reemplazando la identidad.
        assertFalse(aceptable(TipoCifrado.PREPARADO, haySesion = true))
    }

    @Test
    fun `sin cifrar tampoco, aunque el servidor de desarrollo lo permita`() {
        // `PLANO` existe para depurar el transporte contra un servidor local.
        // Por el aire sería un agujero del tamaño de la función entera.
        assertFalse(aceptable(TipoCifrado.PLANO, haySesion = true))
        assertFalse(aceptable(TipoCifrado.PLANO, haySesion = false))
    }

    @Test
    fun `lo que continua una sesion que existe, si`() {
        assertTrue(aceptable(TipoCifrado.SESION, haySesion = true))
        assertTrue(aceptable(TipoCifrado.GRUPO, haySesion = true))
    }

    @Test
    fun `pero no si esa sesion no existe`() {
        // Sin sesión no se puede abrir igual; rechazarlo aquí evita además
        // que un desconocido gaste CPU de criptografía a distancia.
        assertFalse(aceptable(TipoCifrado.SESION, haySesion = false))
        assertFalse(aceptable(TipoCifrado.GRUPO, haySesion = false))
    }

    @Test
    fun `un tipo inventado no se acepta`() {
        // La lista es cerrada y lo que no está, no pasa. Al revés —aceptar lo
        // desconocido— es como un tipo nuevo se cuela sin que nadie lo decida.
        for (t in listOf(-1, 1, 4, 5, 6, 8, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse("tipo $t", aceptable(t, haySesion = true))
        }
    }

    // ------------------------------------------------------------------
    // A quien se le toca la puerta
    // ------------------------------------------------------------------

    @Test
    fun `a los audifonos no se les toca la puerta`() {
        // El defecto que el emulador NO podia encontrar: alli la lista de
        // emparejados esta vacia, asi que el bucle no tocaba nada y todo
        // parecia bien. En un telefono de verdad esa lista son los audifonos,
        // el carro y el reloj, y un `connect()` de RFCOMM contra un enlace de
        // audio en uso se oye — nadie relacionaria el corte con una app de
        // mensajeria.
        assertFalse(valeLaPenaIntentar(ClaseBt.AUDIO_VIDEO))
        assertFalse(valeLaPenaIntentar(ClaseBt.VESTIBLE))
    }

    @Test
    fun `a un telefono si`() {
        assertTrue(valeLaPenaIntentar(ClaseBt.TELEFONO))
        // Las tablets se anuncian como computadora y son destino legitimo.
        assertTrue(valeLaPenaIntentar(ClaseBt.COMPUTADORA))
    }

    @Test
    fun `el que no declara categoria se intenta igual`() {
        // Es el unico caso donde equivocarse por NO intentar seria peor: un
        // telefono que no declara su clase quedaria fuera para siempre y nadie
        // sabria por que.
        assertTrue(valeLaPenaIntentar(ClaseBt.SIN_CATEGORIA))
    }

    @Test
    fun `lo demas se descarta sin abrir un socket`() {
        for (c in listOf(
            ClaseBt.RED, ClaseBt.PERIFERICO, ClaseBt.IMAGEN,
            ClaseBt.JUGUETE, ClaseBt.SALUD, 0x0000, -1, 0x9999,
        )) {
            assertFalse("clase $c", valeLaPenaIntentar(c))
        }
    }

    // ------------------------------------------------------------------
    // El marco de las tramas
    // ------------------------------------------------------------------

    private fun ida(datos: ByteArray): ByteArray {
        val salida = ByteArrayOutputStream()
        Trama.escribir(salida, datos)
        return Trama.leer(ByteArrayInputStream(salida.toByteArray()))
    }

    @Test
    fun `lo que se escribe es lo que se lee`() {
        for (largo in listOf(0, 1, 2, 255, 256, 1000, 65536, Trama.MAXIMO)) {
            val datos = Random(largo).nextBytes(largo)
            assertArrayEquals("largo $largo", datos, ida(datos))
        }
    }

    @Test
    fun `varias tramas seguidas no se mezclan`() {
        // El caso para el que existe el marco: RFCOMM es un flujo, no
        // mensajes. Tres escrituras pueden llegar juntas.
        val salida = ByteArrayOutputStream()
        val tres = listOf("uno".toByteArray(), "dos".toByteArray(), ByteArray(5000) { 7 })
        tres.forEach { Trama.escribir(salida, it) }

        val entrada = ByteArrayInputStream(salida.toByteArray())
        tres.forEach { assertArrayEquals(it, Trama.leer(entrada)) }
    }

    @Test
    fun `un flujo que entrega de a poco tambien se lee entero`() {
        // `read` puede devolver menos de lo pedido, y eso es normal. Un lector
        // que asume que lee todo de una vez funciona hasta que el mensaje pasa
        // del tamaño de un paquete — o sea, funciona en las pruebas y falla en
        // el aire.
        val datos = Random(1).nextBytes(9000)
        val salida = ByteArrayOutputStream()
        Trama.escribir(salida, datos)
        val lento = tacano(salida.toByteArray(), porVez = 7)
        assertArrayEquals(datos, Trama.leer(lento))
    }

    /** Un flujo que entrega como mucho `porVez` bytes por llamada. */
    private fun tacano(datos: ByteArray, porVez: Int): InputStream =
        object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i < datos.size) datos[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= datos.size) return -1
                val n = minOf(porVez, len, datos.size - i)
                System.arraycopy(datos, i, b, off, n)
                i += n
                return n
            }
        }

    // ------------------------------------------------------------------
    // Lo que manda un desconocido
    // ------------------------------------------------------------------

    @Test
    fun `un largo imposible se rechaza sin reservar memoria`() {
        // Es la PRIMERA línea que se lee de alguien a quien no se conoce. Sin
        // tope, `0x7FFFFFFF` hace que este lado intente reservar dos gigabytes
        // — y no hace falta ninguna habilidad para mandar cuatro bytes.
        val enorme = byteArrayOf(0x7F, -1, -1, -1)
        assertThrows(IllegalArgumentException::class.java) {
            Trama.leer(ByteArrayInputStream(enorme))
        }
    }

    @Test
    fun `un largo negativo tampoco`() {
        val negativo = byteArrayOf(-1, -1, -1, -1)  // 0xFFFFFFFF
        assertThrows(IllegalArgumentException::class.java) {
            Trama.leer(ByteArrayInputStream(negativo))
        }
    }

    @Test
    fun `una trama cortada a la mitad lanza en vez de devolver basura`() {
        // Devolver lo que llegó sería peor que fallar: un JSON a medias que
        // no parsea se descarta, pero uno que sí parsea por casualidad sería
        // un mensaje inventado.
        val salida = ByteArrayOutputStream()
        Trama.escribir(salida, ByteArray(1000) { 3 })
        val cortado = salida.toByteArray().copyOf(400)
        assertThrows(EOFException::class.java) {
            Trama.leer(ByteArrayInputStream(cortado))
        }
    }

    @Test
    fun `no se puede escribir mas grande que el tope`() {
        assertThrows(IllegalArgumentException::class.java) {
            Trama.escribir(ByteArrayOutputStream(), ByteArray(Trama.MAXIMO + 1))
        }
    }

    @Test
    fun `el tope deja pasar el sobre mas grande que existe`() {
        // 60 KiB de relleno más la cabecera de Signal, y el servidor rechaza
        // por encima de 64 KiB. Si alguien bajara este tope por debajo de eso,
        // los sobres grandes dejarían de viajar por el aire y sí por el buzón
        // — que es la clase de diferencia que nadie encuentra.
        assertTrue(Trama.MAXIMO > 65536)
    }

    // ------------------------------------------------------------------
    // El saludo y el sobre
    // ------------------------------------------------------------------

    @Test
    fun `el saludo y el sobre viajan y vuelven iguales`() {
        val saludo: MensajeCerca = MensajeCerca.Saludo("u1", "ana", "d1")
        val sobre: MensajeCerca = MensajeCerca.Sobre(
            sobreId = "s", mensajeId = "m", conversacionId = "c",
            origenUsuarioId = "u1", origenUsername = "ana", origenDispositivo = "d1",
            destinoDispositivo = "d2", cuerpo = "AAAA", tipo = TipoCifrado.SESION,
            creadoEn = 1_700_000_000_000L,
        )
        for (m in listOf(saludo, sobre)) {
            val vuelta = json.decodeFromString(
                MensajeCerca.serializer(),
                json.encodeToString(MensajeCerca.serializer(), m),
            )
            assertEquals(m, vuelta)
        }
    }

    @Test
    fun `un JSON que no es ninguno de los dos no se hace pasar por uno`() {
        for (basura in listOf("", "{}", "null", """{"type":"otra"}""", "[1,2,3]")) {
            val r = runCatching { json.decodeFromString(MensajeCerca.serializer(), basura) }
            assertTrue("'$basura' no tendria que parsear", r.isFailure)
        }
    }
}
