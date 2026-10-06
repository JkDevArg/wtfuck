package com.wtfuck.app

import com.wtfuck.app.datos.EnlaceDeContacto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Leer un enlace de contacto. Lo que se lee viene de un QR o de un mensaje
 * ajeno: puede traer cualquier cosa, y de ahi solo puede salir un codigo con
 * la forma exacta o nada.
 */
class EnlaceDeContactoTest {

    private val c = "Ab3_-xYz0123456789abcd"

    @Test
    fun `se arma con el servidor, con o sin barra final`() {
        assertEquals("https://api.ejemplo.com/c/$c", EnlaceDeContacto.url("https://api.ejemplo.com", c))
        assertEquals("https://api.ejemplo.com/c/$c", EnlaceDeContacto.url("https://api.ejemplo.com/", c))
    }

    @Test
    fun `se leen las formas que da la app y las que agregan otras apps`() {
        assertEquals(c, EnlaceDeContacto.codigoDe("https://api.ejemplo.com/c/$c"))
        assertEquals(c, EnlaceDeContacto.codigoDe("http://127.0.0.1:8088/c/$c"))
        assertEquals(c, EnlaceDeContacto.codigoDe("wtfuck://c/$c"))
        assertEquals(c, EnlaceDeContacto.codigoDe("  https://api.ejemplo.com/c/$c/  "))
        assertEquals(c, EnlaceDeContacto.codigoDe("https://api.ejemplo.com/c/$c?utm_source=x"))
        assertEquals(c, EnlaceDeContacto.codigoDe(c))
    }

    @Test
    fun `lo que no es un enlace de contacto no da codigo`() {
        assertNull(EnlaceDeContacto.codigoDe(""))
        assertNull(EnlaceDeContacto.codigoDe("hola"))
        assertNull(EnlaceDeContacto.codigoDe("https://api.ejemplo.com/i/$c"))       // otra ruta
        assertNull(EnlaceDeContacto.codigoDe("https://api.ejemplo.com/c/${c}x"))    // largo de mas
        assertNull(EnlaceDeContacto.codigoDe("https://api.ejemplo.com/c/abc"))      // corto
        assertNull(EnlaceDeContacto.codigoDe("https://api.ejemplo.com/c/$c/otra"))  // sigue el camino
        assertNull(EnlaceDeContacto.codigoDe("javascript:alert(1)//c/$c"))
        assertNull(EnlaceDeContacto.codigoDe("K7M2-9QXF"))                          // un codigo de vinculacion
    }
}
