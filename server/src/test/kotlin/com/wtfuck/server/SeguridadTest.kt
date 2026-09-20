package com.wtfuck.server

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pruebas del registro de eventos de seguridad.
 *
 * Existen por un defecto concreto y caro de encontrar: `Seguridad.anotar`
 * atrapaba su excepcion con un `runCatching`, con la intencion de que un fallo
 * al registrar no tumbara la operacion registrada. En Postgres eso hace lo
 * contrario. **Una sentencia fallida aborta la transaccion entera**, todo lo que
 * sigue falla, y el `commit()` se convierte en un ROLLBACK silencioso.
 *
 * El sintoma en la app fue el peor posible: la ruta devolvia 200, el usuario
 * veia su codigo de verificacion en pantalla, y en la base no existia ninguna
 * fila. Nada en la bitacora, nada en el codigo que se viera raro leyendolo.
 *
 * Estas pruebas van contra la base real porque el comportamiento que se prueba
 * es de Postgres, no de Kotlin: con un doble no se probaria nada.
 *
 * Requiere `docker compose up -d`.
 */
class SeguridadTest {

    @BeforeTest
    fun preparar() {
        Db.iniciar(
            System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck",
            System.getenv("WTFUCK_DB_USER") ?: "wtfuck",
            System.getenv("WTFUCK_DB_PASS") ?: "wtfuck_dev",
        )
    }

    // ============================================================
    //  El savepoint
    // ============================================================

    @Test
    fun `un evento que falla no tumba la transaccion que lo rodea`() {
        val user = "sg" + UUID.randomUUID().toString().take(8).replace("-", "")

        val id = Db.tx { c ->
            val nuevo = c.prepareStatement(
                "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
            ).use { st ->
                st.setString(1, user)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }

            // Un tipo que el CHECK de la tabla rechaza. Antes del savepoint,
            // esta linea abortaba la transaccion y el usuario de arriba no
            // llegaba a existir, pese a que `anotar` "no lanza".
            Seguridad.anotar(c, nuevo, "tipo_que_no_existe_en_el_check")

            // Y la transaccion tiene que seguir usable DESPUES del fallo.
            c.prepareStatement("UPDATE usuario SET biografia = 'sigo vivo' WHERE id = ?")
                .use { st -> st.setObject(1, nuevo); st.executeUpdate() }

            nuevo
        }

        val bio = Db.query { c ->
            c.prepareStatement("SELECT biografia FROM usuario WHERE id = ?").use { st ->
                st.setObject(1, id)
                st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
            }
        }
        assertEquals(
            "sigo vivo", bio,
            "la transaccion se confirmo y las sentencias posteriores al evento fallido surtieron efecto",
        )

        limpiar(id)
    }

    @Test
    fun `un evento valido dentro de una transaccion si se guarda`() {
        val user = "sg" + UUID.randomUUID().toString().take(8).replace("-", "")

        val id = Db.tx { c ->
            val nuevo = c.prepareStatement(
                "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
            ).use { st ->
                st.setString(1, user)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
            Seguridad.anotar(c, nuevo, "registro", ip = "10.0.0.7")
            nuevo
        }

        val eventos = Db.query { c -> Seguridad.mios(c, id) }
        assertEquals(1, eventos.size, "el evento valido se guardo")
        assertEquals("registro", eventos.first().tipo)
        assertEquals("10.0.0.7", eventos.first().ip)

        limpiar(id)
    }

    // ============================================================
    //  La validacion de IP
    // ============================================================
    //
    // `'localhost'::inet` es un ERROR en Postgres, no un null. Y `remoteHost`
    // de Ktor puede devolver justamente un nombre: detras de `adb reverse`
    // devolvia "localhost". Con el savepoint ya no seria grave, pero validar
    // evita el trabajo perdido y el ruido en la bitacora.

    @Test
    fun `solo se aceptan IP literales, nunca nombres`() {
        assertEquals("127.0.0.1", Seguridad.ipValida("127.0.0.1"))
        assertEquals("10.0.2.2", Seguridad.ipValida(" 10.0.2.2 "))
        assertEquals("::1", Seguridad.ipValida("::1"))
        assertEquals("2001:db8::ff00:42:8329", Seguridad.ipValida("2001:db8::ff00:42:8329"))

        assertNull(Seguridad.ipValida("localhost"), "un nombre no es una IP")
        assertNull(Seguridad.ipValida("mi-maquina.local"))
        assertNull(Seguridad.ipValida("unknown"))
        assertNull(Seguridad.ipValida(""))
        assertNull(Seguridad.ipValida(null))
        // Cada octeto tiene que caber en un byte.
        assertNull(Seguridad.ipValida("999.1.1.1"), "un octeto fuera de rango no es una IP")
    }

    @Test
    fun `una IP invalida no impide guardar el evento`() {
        val user = "sg" + UUID.randomUUID().toString().take(8).replace("-", "")
        val id = Db.tx { c ->
            val nuevo = c.prepareStatement(
                "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
            ).use { st ->
                st.setString(1, user)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
            // Es lo que llegaba desde el emulador.
            Seguridad.anotar(c, nuevo, "ingreso", ip = "localhost")
            nuevo
        }

        val eventos = Db.query { c -> Seguridad.mios(c, id) }
        assertEquals(1, eventos.size, "el evento se guarda igual, sin la IP")
        assertNull(eventos.first().ip, "la IP se descarta en vez de romper la insercion")

        limpiar(id)
    }

    // ============================================================
    //  Los cupos durables
    // ============================================================

    @Test
    fun `el cupo cuenta por ventana y devuelve el total ya actualizado`() {
        val user = "sg" + UUID.randomUUID().toString().take(8).replace("-", "")
        val id = Db.tx { c ->
            c.prepareStatement(
                "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
            ).use { st ->
                st.setString(1, user)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
        }

        val ventana = java.time.Duration.ofHours(1)
        val primero = Db.tx { c -> Cupos.sumar(c, id, "prueba", ventana) }
        val segundo = Db.tx { c -> Cupos.sumar(c, id, "prueba", ventana) }
        assertEquals(1, primero, "el primero devuelve 1, no 0: suma y responde con el valor nuevo")
        assertEquals(2, segundo)

        // Y el tope lanza 429 cuando se pasa.
        val e = runCatching { Db.tx { c -> Cupos.exigir(c, id, "prueba", 2, ventana) } }.exceptionOrNull()
        assertTrue(e is ErrorNegocio && e.codigo == 429, "pasar el tope da 429, no 500: $e")

        limpiar(id)
    }

    private fun limpiar(id: UUID) {
        Db.tx { c ->
            c.prepareStatement("DELETE FROM usuario WHERE id = ?")
                .use { st -> st.setObject(1, id); st.executeUpdate() }
        }
    }
}
