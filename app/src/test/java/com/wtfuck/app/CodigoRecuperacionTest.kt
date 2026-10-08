package com.wtfuck.app

import com.wtfuck.protocol.CodigoRecuperacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El codigo de recuperacion.
 *
 * Es la pieza de la app donde un error no se puede deshacer: si el codigo que
 * se muestra al registrarse no es el que despues se acepta, la persona
 * descubre el fallo el dia que perdio el telefono — cuando ya no hay arreglo
 * posible. Por eso se prueba el viaje de ida y vuelta, no solo que "genera
 * algo".
 */
class CodigoRecuperacionTest {

    // ------------------------------------------------------------ formato

    @Test
    fun `el codigo tiene 7 grupos de 4`() {
        val c = CodigoRecuperacion.generar()
        val grupos = c.split("-")
        assertEquals("grupos en $c", 7, grupos.size)
        grupos.forEach { assertEquals("grupo corto en $c", 4, it.length) }
    }

    @Test
    fun `no usa las letras que se confunden al copiar a mano`() {
        // I, L, O y U no estan en el alfabeto. Si aparecieran, alguien las
        // copiaria como 1, 1, 0 y V y el codigo dejaria de funcionar.
        repeat(500) {
            val c = CodigoRecuperacion.generar()
            listOf('I', 'L', 'O', 'U').forEach { malo ->
                assertFalse("salio una '$malo' en $c", c.contains(malo))
            }
        }
    }

    @Test
    fun `dos codigos seguidos no se repiten`() {
        val vistos = HashSet<String>()
        repeat(1_000) { vistos += CodigoRecuperacion.generar() }
        assertEquals("hubo repetidos: el azar no es azar", 1_000, vistos.size)
    }

    // --------------------------------------------------- ida y vuelta

    @Test
    fun `lo que se genera se acepta`() {
        // La prueba que de verdad importa. Si esto falla, el codigo que se le
        // muestra a la persona no sirve para recuperar nada.
        repeat(2_000) {
            val c = CodigoRecuperacion.generar()
            assertTrue("no se acepto el codigo recien generado: $c", CodigoRecuperacion.valido(c))
        }
    }

    @Test
    fun `normalizar devuelve los 28 simbolos sin guiones`() {
        val c = CodigoRecuperacion.generar()
        val n = CodigoRecuperacion.normalizar(c)
        assertNotNull(n)
        assertEquals(CodigoRecuperacion.SIMBOLOS, n!!.length)
        assertFalse(n.contains("-"))
    }

    // --------------------------------------------- como escribe la gente

    @Test
    fun `da igual como lo escriba la persona`() {
        val c = CodigoRecuperacion.generar()
        val esperado = CodigoRecuperacion.normalizar(c)

        val formas = listOf(
            c.lowercase(),                       // todo en minusculas
            c.replace("-", ""),                  // sin guiones
            c.replace("-", " "),                 // con espacios
            "  $c  ",                            // con espacios alrededor
            c.replace("-", "  "),                // separadores de mas
            c.lowercase().replace("-", ""),      // las dos cosas
        )
        formas.forEach { forma ->
            assertEquals("fallo con: '$forma'", esperado, CodigoRecuperacion.normalizar(forma))
        }
    }

    @Test
    fun `las confusiones de Crockford se entienden`() {
        // Quien copia a mano puede dibujar un 1 que parece I o L, y un 0 que
        // parece O. El alfabeto no las usa, asi que al leerlas solo pueden
        // significar el numero.
        val conUnos = "1111-1111-1111-1111-1111-1111-1111"
        val comoI = conUnos.replace('1', 'I')
        val comoL = conUnos.replace('1', 'l')
        // Los tres tienen que dar EL MISMO resultado, valido o no.
        assertEquals(CodigoRecuperacion.normalizar(conUnos), CodigoRecuperacion.normalizar(comoI))
        assertEquals(CodigoRecuperacion.normalizar(conUnos), CodigoRecuperacion.normalizar(comoL))

        val conCeros = "0000-0000-0000-0000-0000-0000-0000"
        assertEquals(
            CodigoRecuperacion.normalizar(conCeros),
            CodigoRecuperacion.normalizar(conCeros.replace('0', 'O')),
        )
    }

    // ------------------------------------------------------ rechazos

    @Test
    fun `una errata de un solo simbolo se detecta`() {
        // Para esto existe el byte de control. Sin el, un codigo mal copiado
        // se manda al servidor y vuelve como "no autorizado", que hace pensar
        // que el codigo era el equivocado y no que hay una letra mal.
        var detectadas = 0
        val intentos = 2_000
        repeat(intentos) {
            val limpio = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
            val i = (0 until limpio.length).random()
            val alfabeto = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val otro = alfabeto.filter { it != limpio[i] }.random()
            val roto = limpio.substring(0, i) + otro + limpio.substring(i + 1)
            if (!CodigoRecuperacion.valido(roto)) detectadas++
        }
        // El control es de un byte: deja pasar 1 de cada 256, o sea un 0,4%.
        // Se exige >= 99%, que deja margen para el azar pero NO para un
        // agujero estructural.
        //
        // El umbral estuvo en 97% y fue un error: a ese nivel pasaba tambien
        // con un defecto real -los 4 bits de relleno del ultimo simbolo no se
        // comprobaban, y 15 de las 31 erratas posibles ahi se aceptaban-. La
        // prueba fallaba de vez en cuando y parecia inestable; no lo era. Un
        // umbral flojo convierte un fallo en ruido.
        val tasa = detectadas.toDouble() / intentos
        assertTrue("solo detecto el ${(tasa * 100).toInt()}% de las erratas", tasa >= 0.99)
    }

    @Test
    fun `un codigo de largo equivocado no vale`() {
        val c = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        assertNull("acepto uno corto", CodigoRecuperacion.normalizar(c.dropLast(1)))
        assertNull("acepto uno largo", CodigoRecuperacion.normalizar(c + "A"))
        assertNull("acepto vacio", CodigoRecuperacion.normalizar(""))
    }

    @Test
    fun `un simbolo fuera del alfabeto no vale`() {
        val c = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        // La U no esta en Crockford y NO se traduce a nada: es un error.
        assertNull(CodigoRecuperacion.normalizar("U" + c.drop(1)))
        assertNull(CodigoRecuperacion.normalizar("@" + c.drop(1)))
        assertNull(CodigoRecuperacion.normalizar("ñ" + c.drop(1)))
    }

    // -------------------------------------------------------- las claves

    @Test
    fun `el mismo codigo da siempre las mismas claves`() {
        // Si esto fallara, una copia hecha hoy no se podria abrir manana con
        // el mismo codigo.
        val c = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        assertArrayEq(CodigoRecuperacion.claveDeIdentidad(c), CodigoRecuperacion.claveDeIdentidad(c))
        assertArrayEq(CodigoRecuperacion.verificadorServidor(c), CodigoRecuperacion.verificadorServidor(c))
    }

    @Test
    fun `las dos claves del mismo codigo son distintas`() {
        // La separacion por etiqueta es lo que impide que el servidor, con su
        // mitad, pueda descifrar la identidad. Si fueran iguales, entregarle
        // el verificador seria entregarle la clave de la copia.
        val c = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        val identidad = CodigoRecuperacion.claveDeIdentidad(c).toList()
        val servidor = CodigoRecuperacion.verificadorServidor(c).toList()
        assertNotEquals("la clave de la copia y la del servidor coinciden", identidad, servidor)
    }

    @Test
    fun `las claves son de 32 bytes`() {
        val c = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        assertEquals(32, CodigoRecuperacion.claveDeIdentidad(c).size)
        assertEquals(32, CodigoRecuperacion.verificadorServidor(c).size)
    }

    @Test
    fun `dos codigos distintos dan claves distintas`() {
        val a = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        val b = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        assertNotEquals(
            CodigoRecuperacion.claveDeIdentidad(a).toList(),
            CodigoRecuperacion.claveDeIdentidad(b).toList(),
        )
    }

    @Test
    fun `escribir el codigo de otra forma da la MISMA clave`() {
        // El punto de normalizar antes de derivar. Sin esto, guardar la copia
        // escribiendo el codigo con guiones y abrirla escribiendolo sin ellos
        // darian claves distintas, y la copia no abriria nunca.
        val c = CodigoRecuperacion.generar()
        val desdeGuiones = CodigoRecuperacion.claveDeIdentidad(CodigoRecuperacion.normalizar(c)!!)
        val desdeSuelto = CodigoRecuperacion.claveDeIdentidad(
            CodigoRecuperacion.normalizar(c.lowercase().replace("-", ""))!!,
        )
        assertArrayEq(desdeGuiones, desdeSuelto)
    }

    private fun assertArrayEq(a: ByteArray, b: ByteArray) =
        assertEquals(a.toList(), b.toList())
}
