package com.wtfuck.server

import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El limite de ritmo del registro, y lo que tuvo que cambiar debajo para que
 * un limite por IP sirviera de algo.
 *
 * La ruta se prueba de punta a punta en `pruebas/limite-registro.mjs`. Aqui va
 * lo que una prueba por HTTP no alcanza desde localhost: que pasa cuando la
 * conexion viene de una IP publica, y los numeros por defecto, que en la
 * suite se ajustan desde el panel y por eso no se ven.
 *
 * Las pruebas del contador van contra la base real. Requiere la base levantada
 * (`WTFUCK_DB_URL`).
 */
class LimiteDeRegistroTest {

    @BeforeTest
    fun preparar() {
        Db.iniciar(
            System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck",
            System.getenv("WTFUCK_DB_USER") ?: "wtfuck",
            System.getenv("WTFUCK_DB_PASS") ?: "wtfuck_dev",
        )
    }

    // ============================================================
    //  De donde viene la peticion
    // ============================================================

    @Test
    fun `desde una IP publica la cabecera no cuenta`() {
        // Sin proxy delante, la cabecera la escribe el cliente. Si contara,
        // cada peticion estrenaria cupo con un valor inventado.
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("1.2.3.4", "200.48.1.9"))
        assertEquals("2800:200:e840::7", Seguridad.ipDeCliente("1.2.3.4", "2800:200:e840::7"))
    }

    @Test
    fun `desde un proxy propio cuenta la ULTIMA entrada, no la primera`() {
        // Lo que manda nginx con $proxy_add_x_forwarded_for: lo que trajo el
        // cliente, y al final lo que vio nginx. La primera version tomaba la
        // primera y con eso cualquiera elegia su IP.
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("6.6.6.6, 200.48.1.9", "127.0.0.1"))
        // Caddy con `header_up X-Forwarded-For {remote_host}`: una sola.
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("200.48.1.9", "172.18.0.5"))
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("200.48.1.9", "::1"))
        // nginx en el anfitrion entra al contenedor por la puerta de enlace
        // del puente de Docker: privada.
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("1.1.1.1,200.48.1.9", "172.17.0.1"))
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("200.48.1.9", "10.0.0.2"))
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("200.48.1.9", "192.168.1.20"))
        assertEquals("200.48.1.9", Seguridad.ipDeCliente("200.48.1.9", "fd12:3456::1"))
    }

    @Test
    fun `sin cabecera, o vacia, es quien abrio la conexion`() {
        assertEquals("127.0.0.1", Seguridad.ipDeCliente(null, "127.0.0.1"))
        assertEquals("127.0.0.1", Seguridad.ipDeCliente("", "127.0.0.1"))
        assertEquals("127.0.0.1", Seguridad.ipDeCliente(" , ", "127.0.0.1"))
        assertEquals("200.48.1.9", Seguridad.ipDeCliente(null, "200.48.1.9"))
    }

    @Test
    fun `un nombre en vez de una IP no se toma por proxy propio`() {
        // `remoteHost` puede devolver "localhost" detras de adb reverse. No es
        // un literal, y lo que no se puede comprobar no se confia.
        assertEquals("localhost", Seguridad.ipDeCliente("1.2.3.4", "localhost"))
    }

    // ============================================================
    //  La red: IPv4 entera, IPv6 por /64
    // ============================================================

    @Test
    fun `una IPv4 es su propia red`() {
        assertEquals("200.48.1.9", Seguridad.redDe("200.48.1.9"))
        assertNotEquals(Seguridad.redDe("200.48.1.9"), Seguridad.redDe("200.48.1.10"))
    }

    @Test
    fun `dos IPv6 del mismo 64 son la misma red`() {
        // Un /64 entero se le da a un hogar o a una VM. Contar por direccion
        // seria regalar dieciocho trillones de cupos.
        val a = Seguridad.redDe("2001:db8:aa:1::1")
        val b = Seguridad.redDe("2001:0db8:00aa:0001:ffff:ffff:ffff:fffe")
        assertEquals(a, b)
        assertEquals("2001:db8:aa:1::/64", a)
        assertNotEquals(a, Seguridad.redDe("2001:db8:aa:2::1"))
    }

    @Test
    fun `una IPv4 escrita como IPv6 cuenta como la IPv4`() {
        assertEquals("200.48.1.9", Seguridad.redDe("::ffff:200.48.1.9"))
    }

    @Test
    fun `lo que no es una IP queda como esta`() {
        assertEquals("localhost", Seguridad.redDe("localhost"))
    }

    // ============================================================
    //  Los numeros
    // ============================================================

    @Test
    fun `los dos limites del registro se ajustan desde el panel`() {
        val claves = Limitador.AJUSTABLES.map { it.clave }
        assertTrue("registro_red" in claves)
        assertTrue("registro_red_dia" in claves)
        // Y el valor de fabrica se lee sin la base, tambien el del cupo
        // diario, que se define en `Cupos` y no en `Limitador`.
        assertEquals(Duration.ofDays(1), Limitador.porDefecto("registro_red_dia")!!.ventana)
    }

    @Test
    fun `el cupo diario es mas estricto que la rafaga extrapolada`() {
        // Si no lo fuera, el cupo seria decorativo. Es la prueba que salta si
        // alguien sube la rafaga "un poco" y desactiva el cupo sin tocarlo.
        val rafaga = Limitador.porDefecto("registro_red")!!
        val dia = Limitador.porDefecto("registro_red_dia")!!
        val rafagaPorDia = rafaga.cuantas.toLong() * (86_400L / rafaga.ventana.seconds)
        assertTrue(
            dia.cuantas < rafagaPorDia,
            "la rafaga permite $rafagaPorDia al dia y el cupo dice ${dia.cuantas}: el cupo no corta nada",
        )
    }

    @Test
    fun `los dos dejan pasar con margen lo que hace una red compartida`() {
        // Medido el 2026-10-08 en la maquina de desarrollo, con varias sesiones
        // compartiendo ::1: hasta 784 altas en 13 minutos y 164 en un minuto
        // (una corrida sola de la regresion: 277 y 127). Es lo que hace un
        // campus el dia que se anuncia la app, detras de una salida a
        // internet. Bajar de aqui repite el error del NAT por cuarta vez.
        val rafaga = Limitador.porDefecto("registro_red")!!
        val porMinuto = rafaga.cuantas * 60.0 / rafaga.ventana.seconds
        assertTrue(porMinuto >= 1.5 * 164, "rafaga de $porMinuto por minuto: no tolera un campus")
        val dia = Limitador.porDefecto("registro_red_dia")!!
        assertTrue(dia.cuantas >= 2 * 784, "cupo de ${dia.cuantas} al dia: no tolera un campus")
    }

    // ============================================================
    //  El contador por red, en la base
    // ============================================================

    private fun redDePrueba() = "t-" + UUID.randomUUID().toString().take(12)

    @Test
    fun `el contador suma por red y por accion, por separado`() {
        val red = redDePrueba()
        val otra = redDePrueba()
        val dia = Duration.ofDays(1)
        val n = Db.tx { c ->
            Cupos.sumarPorRed(c, red, "registro", dia)
            Cupos.sumarPorRed(c, red, "registro", dia)
            Cupos.sumarPorRed(c, red, "registro", dia)
        }
        assertEquals(3, n)
        assertEquals(1, Db.tx { c -> Cupos.sumarPorRed(c, otra, "registro", dia) }, "otra red empieza en cero")
        assertEquals(1, Db.tx { c -> Cupos.sumarPorRed(c, red, "otra_cosa", dia) }, "otra accion tambien")
    }

    @Test
    fun `pasado el tope lanza 429, y el contador sigue contando`() {
        val red = redDePrueba()
        val r = Regla(2, Duration.ofDays(1))
        Cupos.exigirPorRed(red, null, "registro", r)
        Cupos.exigirPorRed(red, null, "registro", r)
        val e = assertFailsWith<ErrorNegocio> { Cupos.exigirPorRed(red, null, "registro", r) }
        assertEquals(429, e.codigo)
        assertTrue(e.motivo.contains("2 por dia"), e.motivo)
        // La suma se confirmo aunque el intento se rechazara: un 429 que
        // deshace su propia cuenta dejaria insistir gratis.
        val guardado = Db.query { c ->
            c.prepareStatement("SELECT n FROM contador_red WHERE red = ? AND accion = 'registro'").use { st ->
                st.setString(1, red)
                st.executeQuery().use { rs -> rs.primero { it.getInt(1) } }
            }
        }
        assertEquals(3, guardado)
    }

    @Test
    fun `el evento del limite excedido queda guardado`() {
        // `Cupos.exigir` anota dentro de la transaccion que despues se deshace,
        // y el evento se pierde con ella. Este va en su propia transaccion.
        val red = redDePrueba()
        val ip = "198.18." + (0..255).random() + "." + (1..254).random()
        val r = Regla(1, Duration.ofDays(1))
        Cupos.exigirPorRed(red, ip, "registro", r)
        assertFailsWith<ErrorNegocio> { Cupos.exigirPorRed(red, ip, "registro", r) }
        val hay = Db.query { c ->
            c.prepareStatement(
                """SELECT count(*) FROM evento_seguridad
                    WHERE tipo = 'limite_excedido' AND host(ip) = ?
                      AND detalle->>'accion' = 'registro'
                      AND creado_en > now() - interval '1 minute'"""
            ).use { st ->
                st.setString(1, ip)
                st.executeQuery().use { rs -> rs.primero { it.getInt(1) } }
            }
        }
        assertEquals(1, hay)
    }

    @Test
    fun `el barrido se lleva las ventanas viejas del contador por red`() {
        // Es una IP: dato personal. No puede quedarse para siempre.
        val red = redDePrueba()
        Db.tx { c ->
            c.prepareStatement(
                "INSERT INTO contador_red (red, accion, ventana, n) VALUES (?, 'registro', now() - interval '3 days', 5)"
            ).use { st -> st.setString(1, red); st.executeUpdate() }
        }
        Db.tx { c -> Cupos.barrer(c) }
        val queda = Db.query { c ->
            c.prepareStatement("SELECT count(*) FROM contador_red WHERE red = ?").use { st ->
                st.setString(1, red)
                st.executeQuery().use { rs -> rs.primero { it.getInt(1) } }
            }
        }
        assertNotNull(queda)
        assertEquals(0, queda)
    }
}
