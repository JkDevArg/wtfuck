package com.wtfuck.app

import com.wtfuck.protocol.CharlaCerca
import com.wtfuck.protocol.MensajeCerca
import com.wtfuck.protocol.TipoCifrado
import com.wtfuck.protocol.Trama
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * La conversacion por el enlace del modo cerca, sobre dos tuberias.
 *
 * Es lo que se puede probar sin dos telefonos: las reglas de que se acepta,
 * de quien y que se acusa. La radio en si -que dos telefonos se encuentren-
 * necesita hardware y no esta aqui.
 */
class CharlaCercaTest {

    private val ana = MensajeCerca.Saludo("u-ana", "ana", "d-ana")
    private val beto = MensajeCerca.Saludo("u-beto", "beto", "d-beto")

    private class Lado(val charla: CharlaCerca, val cerrar: () -> Unit) {
        val saludos = LinkedBlockingQueue<MensajeCerca.Saludo>()
        val sobres = LinkedBlockingQueue<MensajeCerca.Sobre>()
        val acuses = LinkedBlockingQueue<MensajeCerca.Acuse>()
        val fin = LinkedBlockingQueue<Throwable>()
        /** Que contesta este lado cuando le llega un sobre: si lo guardo. */
        @Volatile var guarda = true
    }

    private val hilos = mutableListOf<Thread>()
    private val cierres = mutableListOf<() -> Unit>()

    @After
    fun cerrarTodo() {
        cierres.forEach { runCatching { it() } }
        hilos.forEach { it.join(2_000) }
    }

    /** Dos lados conectados por tuberias, escuchando cada uno en su hilo. */
    private fun conectar(): Pair<Lado, Lado> {
        val aEntrada = PipedInputStream(1 shl 17)
        val bSalida = PipedOutputStream(aEntrada)
        val bEntrada = PipedInputStream(1 shl 17)
        val aSalida = PipedOutputStream(bEntrada)
        val a = Lado(CharlaCerca(aEntrada, aSalida, ana)) { aSalida.close(); aEntrada.close() }
        val b = Lado(CharlaCerca(bEntrada, bSalida, beto)) { bSalida.close(); bEntrada.close() }
        cierres += a.cerrar
        cierres += b.cerrar
        for (l in listOf(a, b)) {
            hilos += thread(isDaemon = true) {
                runCatching {
                    runBlocking {
                        l.charla.escuchar(
                            alSaludo = { l.saludos.put(it) },
                            alSobre = { l.sobres.put(it); l.guarda },
                            alAcuse = { l.acuses.put(it) },
                        )
                    }
                }.exceptionOrNull()?.let { l.fin.put(it) }
            }
        }
        return a to b
    }

    private fun sobre(de: MensajeCerca.Saludo, para: String, id: String = "m1") = MensajeCerca.Sobre(
        sobreId = id, mensajeId = id, conversacionId = "c1",
        origenUsuarioId = de.usuarioId, origenUsername = de.username, origenDispositivo = de.dispositivoId,
        destinoDispositivo = para, cuerpo = "QUJD", tipo = TipoCifrado.SESION, creadoEn = 1L,
    )

    private fun <T> LinkedBlockingQueue<T>.espera(): T? = poll(2, TimeUnit.SECONDS)
    private fun <T> LinkedBlockingQueue<T>.nada(): T? = poll(300, TimeUnit.MILLISECONDS)

    @Test
    fun `cada lado conoce al otro por su saludo`() {
        val (a, b) = conectar()
        a.charla.saludar(); b.charla.saludar()
        assertEquals(beto, a.saludos.espera())
        assertEquals(ana, b.saludos.espera())
        assertEquals(beto, a.charla.suyo)
    }

    @Test
    fun `un sobre guardado vuelve como acuse de quien lo guardo`() {
        val (a, b) = conectar()
        a.charla.saludar(); b.charla.saludar()
        a.saludos.espera(); b.saludos.espera()
        assertTrue(a.charla.mandar(sobre(ana, "d-beto")))
        assertEquals("m1", b.sobres.espera()?.mensajeId)
        val acuse = a.acuses.espera()
        assertEquals(MensajeCerca.Acuse("m1", "d-beto"), acuse)
    }

    @Test
    fun `lo que no se pudo guardar no se acusa`() {
        val (a, b) = conectar()
        b.guarda = false
        a.charla.saludar(); b.charla.saludar()
        a.saludos.espera(); b.saludos.espera()
        a.charla.mandar(sobre(ana, "d-beto"))
        b.sobres.espera()
        assertNull(a.acuses.nada())
    }

    @Test
    fun `un sobre para otro aparato no se entrega`() {
        val (a, b) = conectar()
        a.charla.saludar(); b.charla.saludar()
        a.saludos.espera(); b.saludos.espera()
        a.charla.mandar(sobre(ana, "d-otro"))
        assertNull(b.sobres.nada())
    }

    @Test
    fun `antes del saludo no se acepta ningun sobre`() {
        val (a, b) = conectar()
        // Ana manda sin haber saludado: Beto no sabe de quien es.
        a.charla.mandar(sobre(ana, "d-beto"))
        assertNull(b.sobres.nada())
    }

    @Test
    fun `un sobre a nombre de otro que no es quien saludo se descarta`() {
        val (a, b) = conectar()
        a.charla.saludar(); b.charla.saludar()
        a.saludos.espera(); b.saludos.espera()
        val carla = MensajeCerca.Saludo("u-carla", "carla", "d-carla")
        a.charla.mandar(sobre(carla, "d-beto"))
        assertNull(b.sobres.nada())
    }

    @Test
    fun `un acuse a nombre de otro aparato se ignora`() {
        val (a, b) = conectar()
        a.charla.saludar(); b.charla.saludar()
        a.saludos.espera(); b.saludos.espera()
        b.charla.mandar(MensajeCerca.Acuse("m1", "d-otro"))
        assertNull(a.acuses.nada())
        b.charla.mandar(MensajeCerca.Acuse("m1", "d-beto"))
        assertEquals("d-beto", a.acuses.espera()?.dispositivoId)
    }

    @Test
    fun `una trama ilegible se salta y la charla sigue`() {
        val aEntrada = PipedInputStream(1 shl 16)
        val bSalidaCruda = PipedOutputStream(aEntrada)
        val bEntrada = PipedInputStream(1 shl 16)
        val aSalida = PipedOutputStream(bEntrada)
        val a = Lado(CharlaCerca(aEntrada, aSalida, ana)) { aSalida.close(); aEntrada.close() }
        cierres += a.cerrar
        cierres += { bSalidaCruda.close(); bEntrada.close() }
        hilos += thread(isDaemon = true) {
            runCatching {
                runBlocking { a.charla.escuchar({ a.saludos.put(it) }, { a.sobres.put(it); true }, { a.acuses.put(it) }) }
            }
        }
        Trama.escribir(bSalidaCruda, "esto no es json".toByteArray())
        Trama.escribir(bSalidaCruda, """{"type":"desconocido","x":1}""".toByteArray())
        CharlaCerca(PipedInputStream(), bSalidaCruda, beto).saludar()
        assertEquals(beto, a.saludos.espera())
    }

    @Test
    fun `al cortarse el enlace, escuchar termina con un error`() {
        val (a, b) = conectar()
        b.cerrar()
        assertTrue(a.fin.espera() != null)
    }
}
