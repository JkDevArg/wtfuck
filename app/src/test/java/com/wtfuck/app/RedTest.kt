package com.wtfuck.app

import com.wtfuck.app.datos.Red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class RedTest {

    @Test
    fun `una direccion y un puerto normales sirven`() {
        assertNull(Red.problemaCon("127.0.0.1", "9050"))
        assertNull(Red.problemaCon("proxy.ejemplo.pe", "8080"))
    }

    @Test
    fun `sin direccion o con esquema no`() {
        assertNotNull(Red.problemaCon("", "9050"))
        assertNotNull(Red.problemaCon("http://proxy", "8080"))
        assertNotNull(Red.problemaCon("proxy/ruta", "8080"))
        assertNotNull(Red.problemaCon("pro xy", "8080"))
    }

    @Test
    fun `puerto fuera de rango no`() {
        assertNotNull(Red.problemaCon("127.0.0.1", "0"))
        assertNotNull(Red.problemaCon("127.0.0.1", "70000"))
        assertNotNull(Red.problemaCon("127.0.0.1", "abc"))
    }

    @Test
    fun `socks deja el nombre sin resolver, para que lo resuelva el proxy`() {
        val p = Red.ConfigProxy(Red.Tipo.SOCKS5, "servidor.ejemplo", 9050).aProxy()
        assertEquals(Proxy.Type.SOCKS, p.type())
        val dir = p.address() as InetSocketAddress
        assertEquals(true, dir.isUnresolved)
        assertEquals(9050, dir.port)
    }
}
