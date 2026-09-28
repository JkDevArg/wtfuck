package com.wtfuck.app

import com.wtfuck.app.datos.CopiaSeguridad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo unico que de verdad importa de una copia de seguridad: que se pueda
 * RESTAURAR con la frase correcta, y que NO se pueda de ninguna otra forma.
 *
 * Una copia que no abre es peor que no tener copia -da una falsa sensacion de
 * seguridad-, asi que el round-trip no se supone: se prueba.
 */
class CopiaSeguridadTest {

    private val contenido = """{"hola":"mundo","n":42,"acentos":"ñáé"}""".toByteArray()

    @Test
    fun `la frase correcta recupera exactamente el contenido`() {
        val frase = "correcta-y-larga-123".toCharArray()
        val archivo = CopiaSeguridad.cifrar(contenido, frase)
        // La frase se puede volver a escribir: cifrar no debe consumirla.
        val abierto = CopiaSeguridad.descifrar(archivo, "correcta-y-larga-123".toCharArray())
        assertTrue(abierto.isSuccess)
        assertArrayEquals(contenido, abierto.getOrNull())
    }

    @Test
    fun `una frase equivocada no abre`() {
        val archivo = CopiaSeguridad.cifrar(contenido, "la-buena".toCharArray())
        val r = CopiaSeguridad.descifrar(archivo, "la-mala".toCharArray())
        assertTrue(r.isFailure)
        assertEquals(CopiaSeguridad.Fallo.FRASE_O_DANADO,
            (r.exceptionOrNull() as CopiaSeguridad.ErrorCopia).fallo)
    }

    @Test
    fun `un archivo manipulado se rechaza`() {
        val archivo = CopiaSeguridad.cifrar(contenido, "clave".toCharArray())
        // Cambiar un byte del cuerpo cifrado: GCM tiene que detectarlo.
        archivo[archivo.size - 5] = (archivo[archivo.size - 5] + 1).toByte()
        val r = CopiaSeguridad.descifrar(archivo, "clave".toCharArray())
        assertTrue(r.isFailure)
    }

    @Test
    fun `bajar las iteraciones en el archivo lo invalida`() {
        // La cabecera va autenticada (AAD): si alguien la toca para debilitar la
        // derivacion, GCM rechaza. Se cambia el byte de las iteraciones.
        val archivo = CopiaSeguridad.cifrar(contenido, "clave".toCharArray())
        val offset = 8 + 16 // MAGIA + salt -> aqui empiezan las iteraciones
        archivo[offset] = (archivo[offset] + 1).toByte()
        val r = CopiaSeguridad.descifrar(archivo, "clave".toCharArray())
        assertTrue(r.isFailure)
    }

    @Test
    fun `un archivo que no es una copia se reconoce por el formato`() {
        val basura = "esto no es una copia de wtfuck".toByteArray()
        val r = CopiaSeguridad.descifrar(basura, "clave".toCharArray())
        assertTrue(r.isFailure)
        assertEquals(CopiaSeguridad.Fallo.FORMATO,
            (r.exceptionOrNull() as CopiaSeguridad.ErrorCopia).fallo)
    }

    @Test
    fun `dos copias del mismo contenido no son iguales`() {
        // salt y nonce nuevos cada vez: dos copias identicas no deben producir
        // el mismo archivo, o filtrarian que el contenido no cambio.
        val a = CopiaSeguridad.cifrar(contenido, "clave".toCharArray())
        val b = CopiaSeguridad.cifrar(contenido, "clave".toCharArray())
        assertFalse(a.contentEquals(b))
        // Pero las dos abren con la misma frase.
        assertArrayEquals(contenido, CopiaSeguridad.descifrar(a, "clave".toCharArray()).getOrNull())
        assertArrayEquals(contenido, CopiaSeguridad.descifrar(b, "clave".toCharArray()).getOrNull())
    }
}
