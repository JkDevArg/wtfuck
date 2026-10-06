package com.wtfuck.app

import com.wtfuck.app.datos.EnlaceSeguro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se le dice a la persona antes de abrir un enlace. Lo que importa es
 * que el SITIO que se muestra sea el de verdad, no el que el texto aparenta.
 */
class EnlaceSeguroTest {

    @Test
    fun `un enlace normal se muestra por su sitio, sin avisos`() {
        val a = EnlaceSeguro.analizar("https://www.ejemplo.com/ruta?x=1#y")!!
        assertEquals("www.ejemplo.com", a.sitio)
        assertTrue(a.cifrado)
        assertTrue(a.avisos.isEmpty())
    }

    @Test
    fun `lo de antes de la arroba no es el sitio`() {
        val a = EnlaceSeguro.analizar("https://banco.com@malo.com/entrar")!!
        assertEquals("malo.com", a.sitio)
        assertTrue(a.disfrazado)
        assertTrue(a.avisos.any { "@" in it })
    }

    @Test
    fun `tampoco con usuario y clave, ni con varias arrobas`() {
        assertEquals("malo.com", EnlaceSeguro.analizar("https://user:clave@banco.com@malo.com:8443/")!!.sitio)
    }

    @Test
    fun `el puerto, las mayusculas y el punto final no cambian el sitio`() {
        assertEquals("ejemplo.com", EnlaceSeguro.analizar("HTTPS://Ejemplo.COM.:8080/a")!!.sitio)
    }

    @Test
    fun `una arroba en la ruta no es un disfraz`() {
        val a = EnlaceSeguro.analizar("https://ejemplo.com/perfil/@ana")!!
        assertEquals("ejemplo.com", a.sitio)
        assertFalse(a.disfrazado)
    }

    @Test
    fun `punycode se avisa y se muestra legible`() {
        // "аpple.com" con la primera "а" cirilica, en la forma en que viaja.
        val puny = java.net.IDN.toASCII("аpple.com")
        assertTrue(puny.startsWith("xn--"))
        val a = EnlaceSeguro.analizar("https://$puny/")!!
        assertTrue(a.letrasRaras)
        assertEquals("аpple.com", a.sitio)
    }

    @Test
    fun `letras de otro alfabeto escritas tal cual tambien`() {
        assertTrue(EnlaceSeguro.analizar("https://аpple.com/")!!.letrasRaras)
    }

    @Test
    fun `http se avisa como sin cifrado`() {
        val a = EnlaceSeguro.analizar("http://ejemplo.com")!!
        assertFalse(a.cifrado)
        assertTrue(a.avisos.any { "cifrado" in it })
    }

    @Test
    fun `lo que no es http ni https no se abre`() {
        assertNull(EnlaceSeguro.analizar("javascript:alert(1)"))
        assertNull(EnlaceSeguro.analizar("file:///sdcard/x"))
        assertNull(EnlaceSeguro.analizar("intent://x#Intent;end"))
        assertNull(EnlaceSeguro.analizar("https:///sinhost"))
    }
}
