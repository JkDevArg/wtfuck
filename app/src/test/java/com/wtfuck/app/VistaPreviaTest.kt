package com.wtfuck.app

import com.wtfuck.app.datos.VistaPreviaHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VistaPreviaTest {

    @Test
    fun `encuentra el enlace y le quita la puntuacion final`() {
        val e = VistaPreviaHtml.enlacesEn("mira esto: https://cientifica.edu.pe/noticias. Esta bueno")
        assertEquals("https://cientifica.edu.pe/noticias", e.single().second)
    }

    @Test
    fun `la posicion coincide con el texto`() {
        val t = "ver (https://a.com/x) ya"
        val (rango, url) = VistaPreviaHtml.enlacesEn(t).single()
        assertEquals(url, t.substring(rango))
    }

    @Test
    fun `solo http y https`() {
        assertTrue(VistaPreviaHtml.enlacesEn("javascript:alert(1) file:///etc ftp://x").isEmpty())
    }

    @Test
    fun `sin enlace no hay primero`() {
        assertNull(VistaPreviaHtml.primerEnlace("hola que tal"))
    }

    @Test
    fun `lee open graph con cualquier orden de atributos`() {
        val html = """<html><head>
            <meta content="Titulo OG" property="og:title">
            <meta property='og:description' content='Una descripcion'>
            <meta property="og:site_name" content="Sitio">
            <meta property="og:image" content="/img/portada.jpg">
            </head><body>...</body></html>"""
        val m = VistaPreviaHtml.extraer(html, "https://ejemplo.com/nota/1")
        assertEquals("Titulo OG", m.titulo)
        assertEquals("Una descripcion", m.descripcion)
        assertEquals("Sitio", m.sitio)
        assertEquals("https://ejemplo.com/img/portada.jpg", m.imagen)
    }

    @Test
    fun `sin open graph usa el title y el dominio`() {
        val m = VistaPreviaHtml.extraer("<head><title> Hola   mundo </title></head>", "https://www.ejemplo.com/a")
        assertEquals("Hola mundo", m.titulo)
        assertEquals("ejemplo.com", m.sitio)
        assertEquals("", m.imagen)
    }

    @Test
    fun `decodifica entidades`() {
        assertEquals("Peña & Cía \"2026\" é", VistaPreviaHtml.decodificar("Pe&ntilde;a &amp; C&iacute;a &quot;2026&quot; &#233;"))
    }

    @Test
    fun `imagen relativa al protocolo`() {
        assertEquals("https://cdn.x.com/a.png", VistaPreviaHtml.resolver("https://x.com/p", "//cdn.x.com/a.png"))
    }

    @Test
    fun `el dominio que se muestra es el real`() {
        // Lo que va antes de una arroba es un usuario, no el sitio: es el
        // truco de siempre para que un enlace parezca de otro lado.
        assertEquals("malo.com", VistaPreviaHtml.dominio("https://banco.com@malo.com/login"))
        assertEquals("ejemplo.com", VistaPreviaHtml.dominio("https://www.Ejemplo.com:8443/x?y=1"))
    }

    @Test
    fun `textos largos se recortan`() {
        val largo = "a".repeat(500)
        val m = VistaPreviaHtml.extraer("<head><meta property=\"og:title\" content=\"$largo\"></head>", "https://x.com")
        assertEquals(200, m.titulo.length)
    }
}
