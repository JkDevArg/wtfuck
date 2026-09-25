package com.wtfuck.app

import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.Relleno
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El relleno del sobre.
 *
 * Lo que se prueba aquí no es que el relleno "funcione" —copiar un array y
 * recortarlo no se rompe solo—, sino las tres cosas que sí se pueden romper
 * sin que nadie lo note: que el contenido sobreviva intacto al viaje de ida y
 * vuelta, que dos mensajes de longitudes distintas acaben **midiendo igual**,
 * y que un sobre sin relleno de una versión anterior se siga abriendo.
 */
class RellenoTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun viaje(c: Carga): Carga {
        val claro = json.encodeToString(Carga.serializer(), c).toByteArray()
        val enviado = Relleno.poner(claro)
        // Lo que el receptor hace: quitar y parsear.
        return json.decodeFromString(Carga.serializer(), String(Relleno.quitar(enviado)))
    }

    // ------------------------------------------------------------------
    // Lo que tiene que seguir funcionando
    // ------------------------------------------------------------------

    @Test
    fun `el contenido sobrevive al relleno`() {
        val original = Carga.Texto("hola, ¿cómo va todo por allá? 🙂")
        assertEquals(original, viaje(original))
    }

    @Test
    fun `un texto que termina en caracteres raros tampoco se rompe`() {
        // El recorte quita bytes CERO del final. Si alguna vez se cambiara por
        // "quitar espacios" o "quitar lo que no sea imprimible", esto lo
        // atrapa: son caracteres legítimos al final de un mensaje.
        for (t in listOf("fin   ", "fin\n\n", "fin\t", "  ", "\u0000dentro", "fin\\u0000")) {
            assertEquals(Carga.Texto(t), viaje(Carga.Texto(t)))
        }
    }

    @Test
    fun `quitar solo recorta ceros, nada mas`() {
        // Esta prueba existe porque la de arriba **pasaba por el motivo
        // equivocado**: un JSON siempre termina en `}`, asi que unos espacios
        // dentro del texto nunca quedan al final de los bytes. Se cambio
        // `quitar` para que se comiera tambien los espacios y ninguna prueba
        // se entero.
        //
        // Asi que la regla se comprueba directamente sobre la funcion, con
        // bytes escritos a mano. Importa porque el dia que alguien cambie el
        // relleno de ceros a espacios —la idea "obvia", porque un parser de
        // JSON los ignora— este recorte se volveria capaz de comerse
        // contenido, y no habria nada que lo dijera.
        for (b in listOf(32, 10, 9, 125, 97)) {
            val datos = byteArrayOf(120, b.toByte())
            assertEquals("no puede recortar el byte $b", 2, Relleno.quitar(datos).size)
        }
        // Y los ceros si, todos los del final y solo los del final.
        assertEquals(2, Relleno.quitar(byteArrayOf(120, 121, 0, 0, 0)).size)
        assertEquals(5, Relleno.quitar(byteArrayOf(120, 0, 0, 0, 121)).size)
    }

    @Test
    fun `un cero dentro de una cadena no confunde al recorte`() {
        // Un NUL de verdad dentro del texto. En JSON viaja escapado como seis
        // caracteres —barra, u, cuatro dígitos— y ninguno es un byte cero, así
        // que recortar por el final no puede alcanzarlo.
        val c = Carga.Texto("antes\u0000despues")
        assertEquals(c, viaje(c))
    }

    // ------------------------------------------------------------------
    // Lo que el relleno tiene que conseguir
    // ------------------------------------------------------------------

    @Test
    fun `dos mensajes de largos muy distintos miden lo mismo`() {
        // Es el punto entero: desde el servidor, "ok" y una frase tienen que
        // ser indistinguibles por tamaño.
        val corto = Relleno.poner(
            json.encodeToString(Carga.serializer(), Carga.Texto("ok")).toByteArray(),
        )
        val largo = Relleno.poner(
            json.encodeToString(
                Carga.serializer(),
                Carga.Texto("mirá, al final no voy a poder ir hoy, se me complicó"),
            ).toByteArray(),
        )
        assertEquals(corto.size, largo.size)
        assertEquals(256, corto.size)
    }

    @Test
    fun `todo lo que se manda cae en un tamano de la lista`() {
        for (largo in listOf(1, 10, 255, 256, 257, 1000, 8192, 8193, 20_000)) {
            val salida = Relleno.poner(ByteArray(largo) { 65 })
            assertTrue(
                "$largo -> ${salida.size}",
                Relleno.CUBOS.contains(salida.size),
            )
            assertTrue("no puede encoger", salida.size >= largo)
        }
    }

    @Test
    fun `los cubos crecen y ninguno se repite`() {
        val c = Relleno.CUBOS
        assertTrue(c.isNotEmpty())
        for (i in 1 until c.size) {
            assertTrue("${c[i - 1]} -> ${c[i]}", c[i] > c[i - 1])
        }
        assertEquals(256, c.first())
        assertEquals(Relleno.MAXIMO, c.last())
    }

    @Test
    fun `el relleno no desperdicia mas de ocho kilobytes en los grandes`() {
        // Arriba, el tamaño ya lo domina una miniatura. Duplicar cubos ahí
        // costaría hasta 30 KiB por sobre para esconder algo que no dice nada.
        val grandes = Relleno.CUBOS.filter { it > 8 * 1024 }
        for (i in 1 until grandes.size) {
            assertTrue(
                "${grandes[i - 1]} -> ${grandes[i]}",
                grandes[i] - grandes[i - 1] <= 8 * 1024,
            )
        }
    }

    // ------------------------------------------------------------------
    // Los bordes
    // ------------------------------------------------------------------

    @Test
    fun `lo que no entra en ningun cubo viaja tal cual, sin romperse`() {
        // Lanzar aquí rompería un envío que hoy funciona: el tope real lo pone
        // el servidor, y él lo rechazará si de verdad es demasiado.
        val enorme = ByteArray(Relleno.MAXIMO + 1) { 65 }
        assertSame(enorme, Relleno.poner(enorme))
        assertEquals(-1, Relleno.cubo(Relleno.MAXIMO + 1))
    }

    @Test
    fun `un sobre sin relleno se abre igual`() {
        // Los dos lados pueden tener versiones distintas. Un sobre de antes de
        // este módulo no lleva ceros al final, y tiene que seguir abriéndose.
        val viejo = json.encodeToString(Carga.serializer(), Carga.Texto("de otra version")).toByteArray()
        assertSame("sin ceros no se copia el array", viejo, Relleno.quitar(viejo))
        assertEquals(
            Carga.Texto("de otra version"),
            json.decodeFromString(Carga.serializer(), String(Relleno.quitar(viejo))),
        )
    }

    @Test
    fun `un cuerpo vacio o todo ceros no revienta`() {
        assertEquals(0, Relleno.quitar(ByteArray(0)).size)
        assertEquals(0, Relleno.quitar(ByteArray(64)).size)
    }

    @Test
    fun `el maximo se queda por debajo de lo que acepta el servidor`() {
        // `manejarEnvio` rechaza un cuerpo cifrado de mas de 65536 bytes, y la
        // cabecera de Signal se suma DESPUES de rellenar. Si alguien sube el
        // maximo hasta rozar ese numero, los sobres mas grandes empezarian a
        // rebotar en el servidor sin que ninguna prueba del cliente lo viera.
        assertTrue(Relleno.MAXIMO < 65536)
        assertTrue("margen para la cabecera", 65536 - Relleno.MAXIMO >= 4096)
    }
}
