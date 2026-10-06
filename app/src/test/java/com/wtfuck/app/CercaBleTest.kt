package com.wtfuck.app

import com.wtfuck.protocol.Apreton
import com.wtfuck.protocol.Baliza
import com.wtfuck.protocol.CharlaCerca
import com.wtfuck.protocol.Curva
import com.wtfuck.protocol.MensajeCerca
import com.wtfuck.protocol.Sello
import com.wtfuck.protocol.TipoCifrado
import com.wtfuck.protocol.Trama
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement
import kotlin.concurrent.thread

/**
 * Modo cerca, fase 1: la baliza, el apretón de manos y el sello del enlace.
 *
 * La curva es la X25519 del JDK: la misma matemática que libsignal en el
 * telefono, sin cargar su biblioteca nativa. Lo que se prueba es sobre todo lo
 * que NO debe pasar: que un impostor complete el apretón, que una grabacion
 * repetida sirva, que una trama alterada o reordenada se abra.
 */
class CercaBleTest {

    /** X25519 del JDK, con las claves en bruto (32 bytes, little-endian). */
    private object CurvaJdk : Curva {
        override fun par(): Pair<ByteArray, ByteArray> {
            val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            val priv = (kp.private as XECPrivateKey).scalar.get()
            val pub = leU((kp.public as XECPublicKey).u)
            return priv to pub
        }

        override fun acordar(privada: ByteArray, publica: ByteArray): ByteArray {
            val kf = KeyFactory.getInstance("X25519")
            val priv = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privada))
            // La publica viaja en little-endian, como la escribe X25519.
            val pub = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, publica.reversedArray())))
            return KeyAgreement.getInstance("X25519").run { init(priv); doPhase(pub, true); generateSecret() }
        }

        private fun leU(u: BigInteger): ByteArray {
            val be = u.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return (ByteArray(32 - be.size) + be).reversedArray()
        }
    }

    private class Aparato(val id: String) {
        val identidad = CurvaJdk.par()
        val baliza = Baliza.nuevaClave()
        val estatico: (ByteArray) -> ByteArray = { pub -> CurvaJdk.acordar(identidad.first, pub) }
    }

    private val ana = Aparato("d-ana")
    private val beto = Aparato("d-beto")
    private val apreton = Apreton(CurvaJdk)
    private val hilos = Executors.newCachedThreadPool()
    private val cierres = mutableListOf<() -> Unit>()

    @After
    fun cerrar() {
        cierres.forEach { runCatching { it() } }
        hilos.shutdownNow()
    }

    /** Dos extremos de un enlace: (entrada, salida) de cada lado. */
    private fun tuberia(): Pair<Pair<InputStream, OutputStream>, Pair<InputStream, OutputStream>> {
        val aIn = PipedInputStream(1 shl 17)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 17)
        val aOut = PipedOutputStream(bIn)
        cierres += { aOut.close(); bOut.close(); aIn.close(); bIn.close() }
        return (aIn to aOut) to (bIn to bOut)
    }

    private fun conocidas(vararg a: Aparato): (String) -> ByteArray? =
        { id -> a.firstOrNull { it.id == id }?.identidad?.second }

    // ------------------------------------------------------------------ baliza

    @Test
    fun `la baliza cambia cada cuarto de hora y es distinta para cada clave`() {
        val e = Baliza.epoca(1_000_000_000_000L)
        assertArrayEquals(Baliza.token(ana.baliza, e), Baliza.token(ana.baliza, e))
        assertFalse(Baliza.token(ana.baliza, e).contentEquals(Baliza.token(ana.baliza, e + 1)))
        assertFalse(Baliza.token(ana.baliza, e).contentEquals(Baliza.token(beto.baliza, e)))
        assertEquals(Baliza.LARGO_TOKEN, Baliza.token(ana.baliza, e).size)
    }

    @Test
    fun `el anuncio se lee igual que se escribe`() {
        val t = Baliza.token(ana.baliza, 7)
        val a = Baliza.leer(Baliza.anuncio(t, 0x85))
        assertEquals(0x85, a?.psm)
        assertEquals(t.joinToString("") { "%02x".format(it) }, a?.token)
    }

    @Test
    fun `un anuncio ajeno o mal formado no se lee`() {
        val bien = Baliza.anuncio(Baliza.token(ana.baliza, 7), 0x85)
        assertNull(Baliza.leer(null))
        assertNull(Baliza.leer(bien.copyOf(bien.size - 1)))
        assertNull(Baliza.leer(bien + byteArrayOf(0)))
        assertNull(Baliza.leer(bien.copyOf().also { it[0] = 0x41 }))   // otra marca
        assertNull(Baliza.leer(bien.copyOf().also { it[1] = 9 }))      // otra version
        assertNull(Baliza.leer(Baliza.anuncio(Baliza.token(ana.baliza, 7), 0x25)))   // canal fuera de rango
    }

    @Test
    fun `se reconoce el cuarto de hora vecino pero no uno lejano`() {
        val ahora = 1_000_000_000_000L
        val indice = Baliza.indice(mapOf("d-ana" to ana.baliza), ahora)
        val e = Baliza.epoca(ahora)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals("d-ana", indice[hex(Baliza.token(ana.baliza, e - 1))])
        assertEquals("d-ana", indice[hex(Baliza.token(ana.baliza, e + 1))])
        assertNull(indice[hex(Baliza.token(ana.baliza, e + 2))])
        assertNull(indice[hex(Baliza.token(beto.baliza, e))])
    }

    // ------------------------------------------------------------------ apretón

    @Test
    fun `dos que ya se conocen se enlazan y cada uno sabe con quien`() {
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana), beto.estatico)
        })
        val selloAna = apreton.llamar(a.first, a.second, ana.id, beto.id, beto.baliza, beto.identidad.second, ana.estatico)
        val (quien, selloBeto) = atiende.get(5, TimeUnit.SECONDS)
        assertEquals("d-ana", quien)
        // Lo que cierra uno lo abre el otro, en los dos sentidos.
        assertArrayEquals("hola".toByteArray(), selloBeto.abrir(selloAna.cerrar("hola".toByteArray())))
        assertArrayEquals("chau".toByteArray(), selloAna.abrir(selloBeto.cerrar("chau".toByteArray())))
    }

    @Test
    fun `quien atiende corta si no conoce a quien llama`() {
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            runCatching { apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(), beto.estatico) }
        })
        thread(isDaemon = true) {
            runCatching { apreton.llamar(a.first, a.second, ana.id, beto.id, beto.baliza, beto.identidad.second, ana.estatico) }
        }
        val r = atiende.get(5, TimeUnit.SECONDS)
        assertTrue(r.exceptionOrNull() is Apreton.Rechazo)
    }

    @Test
    fun `sin la baliza de quien atiende no se puede ni decir quien sos`() {
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            runCatching { apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana), beto.estatico) }
        })
        thread(isDaemon = true) {
            runCatching {
                apreton.llamar(a.first, a.second, ana.id, beto.id, Baliza.nuevaClave(), beto.identidad.second, ana.estatico)
            }
        }
        assertTrue(atiende.get(5, TimeUnit.SECONDS).exceptionOrNull() is Apreton.Rechazo)
    }

    @Test
    fun `un impostor que dice ser Ana no completa el apreton aunque sepa la baliza de Beto`() {
        // Carla es contacto de Beto: conoce su baliza. Dice ser Ana.
        val carla = Aparato("d-carla")
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            runCatching { apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana, carla), beto.estatico) }
        })
        val llama = hilos.submit(Callable {
            runCatching {
                apreton.llamar(a.first, a.second, "d-ana", beto.id, beto.baliza, beto.identidad.second, carla.estatico)
            }
        })
        assertTrue(llama.get(5, TimeUnit.SECONDS).exceptionOrNull() is Apreton.Rechazo)
        cierres.forEach { runCatching { it() } }
        assertTrue(atiende.get(5, TimeUnit.SECONDS).isFailure)
    }

    @Test
    fun `quien atiende tiene que tener la identidad de Beto, no basta su baliza`() {
        // Alguien que conoce la baliza de Beto se hace pasar por el.
        val falso = Aparato("d-beto")
        val (a, b) = tuberia()
        hilos.submit(Callable {
            runCatching { apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana), falso.estatico) }
        })
        val r = runCatching {
            apreton.llamar(a.first, a.second, ana.id, beto.id, beto.baliza, beto.identidad.second, ana.estatico)
        }
        assertTrue(r.exceptionOrNull() is Apreton.Rechazo)
    }

    @Test
    fun `repetir la primera llamada grabada no sirve`() {
        // Se graba lo que Ana manda primero.
        val grabado = ByteArrayOutputStream()
        runCatching {
            apreton.llamar(ByteArrayInputStream(ByteArray(0)), grabado, ana.id, beto.id, beto.baliza, beto.identidad.second, ana.estatico)
        }
        // Un tercero se lo repite a Beto: Beto contesta, pero el tercero no
        // puede cerrar el apretón, porque no tiene la efimera de Ana.
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            runCatching { apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana), beto.estatico) }
        })
        a.second.write(grabado.toByteArray())
        a.second.flush()
        Trama.leer(a.first)                                  // la respuesta de Beto
        Trama.escribir(a.second, ByteArray(21) { 7 })       // un cierre inventado
        assertTrue(atiende.get(5, TimeUnit.SECONDS).exceptionOrNull() is Apreton.Rechazo)
    }

    // ------------------------------------------------------------------ sello

    private fun dosSellos(): Pair<Sello, Sello> {
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana), beto.estatico)
        })
        val s = apreton.llamar(a.first, a.second, ana.id, beto.id, beto.baliza, beto.identidad.second, ana.estatico)
        return s to atiende.get(5, TimeUnit.SECONDS).second
    }

    @Test
    fun `una trama alterada no abre`() {
        val (sa, sb) = dosSellos()
        val c = sa.cerrar("hola".toByteArray())
        c[0] = (c[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { sb.abrir(c) }
    }

    @Test
    fun `una trama repetida o fuera de orden no abre`() {
        val (sa, sb) = dosSellos()
        val uno = sa.cerrar("1".toByteArray())
        val dos = sa.cerrar("2".toByteArray())
        assertThrows(Exception::class.java) { sb.abrir(dos) }   // la 2 antes que la 1
        val (sa2, sb2) = dosSellos()
        val u = sa2.cerrar("1".toByteArray())
        sb2.abrir(u)
        assertThrows(Exception::class.java) { sb2.abrir(u) }    // la misma dos veces
        assertNotEquals(uno.toList(), dos.toList())
    }

    @Test
    fun `dos enlaces entre los mismos aparatos no comparten claves`() {
        val (s1, _) = dosSellos()
        val (s2, _) = dosSellos()
        assertFalse(s1.cerrar("x".toByteArray()).contentEquals(s2.cerrar("x".toByteArray())))
    }

    // ------------------------------------------------------------------ charla sellada

    @Test
    fun `una charla sellada entrega el sobre y su acuse, y un saludo de otro aparato se ignora`() {
        val (a, b) = tuberia()
        val atiende = hilos.submit(Callable {
            apreton.atender(b.first, b.second, beto.id, beto.baliza, conocidas(ana), beto.estatico)
        })
        val sa = apreton.llamar(a.first, a.second, ana.id, beto.id, beto.baliza, beto.identidad.second, ana.estatico)
        val (quien, sb) = atiende.get(5, TimeUnit.SECONDS)

        val sAna = MensajeCerca.Saludo("u-ana", "ana", "d-ana")
        val sBeto = MensajeCerca.Saludo("u-beto", "beto", "d-beto")
        val charlaAna = CharlaCerca(a.first, a.second, sAna, sa, autenticado = "d-beto")
        val charlaBeto = CharlaCerca(b.first, b.second, sBeto, sb, autenticado = quien)
        val sobres = LinkedBlockingQueue<MensajeCerca.Sobre>()
        val acuses = LinkedBlockingQueue<MensajeCerca.Acuse>()
        val saludosBeto = LinkedBlockingQueue<MensajeCerca.Saludo>()
        thread(isDaemon = true) {
            runCatching { runBlocking { charlaBeto.escuchar({ saludosBeto.put(it) }, { sobres.put(it); true }, { }) } }
        }
        thread(isDaemon = true) {
            runCatching { runBlocking { charlaAna.escuchar({ }, { true }, { acuses.put(it) }) } }
        }
        // Un saludo que dice ser otro aparato: Beto lo ignora.
        charlaAna.mandar(MensajeCerca.Saludo("u-x", "x", "d-x"))
        assertNull(saludosBeto.poll(300, TimeUnit.MILLISECONDS))
        charlaAna.saludar(); charlaBeto.saludar()
        assertEquals(sAna, saludosBeto.poll(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        charlaAna.mandar(
            MensajeCerca.Sobre(
                "m1", "m1", "c1", "u-ana", "ana", "d-ana", "d-beto", "QUJD", TipoCifrado.SESION, 1L,
            )
        )
        assertEquals("m1", sobres.poll(2, TimeUnit.SECONDS)?.mensajeId)
        assertEquals("d-beto", acuses.poll(2, TimeUnit.SECONDS)?.dispositivoId)
    }

    @Test
    fun `hkdf coincide con el vector de prueba del RFC 5869`() {
        // RFC 5869, caso 1.
        fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = Apreton.hkdf(
            h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            h("000102030405060708090a0b0c"),
            h("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }
}
