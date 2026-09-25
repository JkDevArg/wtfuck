package com.wtfuck.server

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lo que el servidor tiene que olvidar.
 *
 * ## El defecto que estas pruebas cierran, y no es una consulta
 *
 * `Repo.barrerExpirados` existia desde el modulo C, con su `expira_en` bien
 * puesto, y **no la llamaba nadie**. Los sobres que nadie recogia se quedaban
 * en la base para siempre. No lo vio ninguna prueba porque el codigo estaba
 * ahi y compilaba: lo que faltaba era la llamada, no la consulta.
 *
 * Es la segunda vez en este proyecto — `llamadas.recuperar` fue la primera, y
 * su comentario ya lo decia: *"la unica forma de que un arreglo no arregle
 * nada"*.
 *
 * Por eso estas pruebas no llaman a `barrerExpirados` directamente. Llaman a
 * [tareasDeMantenimiento], que es lo que el hilo periodico ejecuta. Si alguien
 * saca una linea de ahi, cae la prueba de ese barrido — que es exactamente lo
 * que no existia.
 *
 * Requiere `docker compose up -d`.
 */
class RetencionTest {

    @BeforeTest
    fun preparar() {
        Db.iniciar(
            System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck",
            System.getenv("WTFUCK_DB_USER") ?: "wtfuck",
            System.getenv("WTFUCK_DB_PASS") ?: "wtfuck_dev",
        )
    }

    private fun nuevoUsuario(): UUID = Db.tx { c ->
        c.prepareStatement(
            "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
        ).use { st ->
            st.setString(1, "ret" + UUID.randomUUID().toString().take(8).replace("-", ""))
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
    }

    private fun cuantos(tabla: String, id: UUID, campo: String): Int = Db.query { c ->
        c.prepareStatement("SELECT count(*) FROM $tabla WHERE $campo = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    // ------------------------------------------------------------------
    // Los registros con datos personales
    // ------------------------------------------------------------------

    @Test
    fun `un evento de seguridad viejo se borra y uno reciente no`() {
        val u = nuevoUsuario()
        Db.tx { c ->
            // Uno de hace dos anos y uno de ahora, del mismo usuario. La IP y
            // el agente son justo lo que no puede quedarse para siempre.
            c.prepareStatement(
                """INSERT INTO evento_seguridad (usuario_id, tipo, ip, agente, creado_en)
                   VALUES (?, 'ingreso', '203.0.113.7', 'prueba', now() - interval '2 years'),
                          (?, 'ingreso', '203.0.113.7', 'prueba', now())"""
            ).use { st -> st.setObject(1, u); st.setObject(2, u); st.executeUpdate() }
        }
        assertEquals(2, cuantos("evento_seguridad", u, "usuario_id"))

        tareasDeMantenimiento()

        assertEquals(
            1, cuantos("evento_seguridad", u, "usuario_id"),
            "el viejo tiene que irse y el de ahora quedarse",
        )
    }

    @Test
    fun `una auditoria vieja se borra y una reciente no`() {
        val u = nuevoUsuario()
        Db.tx { c ->
            // Dos anos supera la retencion de auditoria, que es mas larga que
            // la de seguridad a proposito: es el rastro de moderacion.
            c.prepareStatement(
                """INSERT INTO auditoria (actor_id, accion, recurso_tipo, creado_en)
                   VALUES (?, 'prueba', 'prueba', now() - interval '2 years'),
                          (?, 'prueba', 'prueba', now())"""
            ).use { st -> st.setObject(1, u); st.setObject(2, u); st.executeUpdate() }
        }
        assertEquals(2, cuantos("auditoria", u, "actor_id"))

        tareasDeMantenimiento()

        assertEquals(1, cuantos("auditoria", u, "actor_id"))
    }

    @Test
    fun `la auditoria aguanta mas que el evento de seguridad`() {
        // No es lo mismo y no pueden caducar juntos. Un evento de seguridad
        // sirve para reconocer un acceso raro y para detectar abuso en curso:
        // a los meses ya no sirve para ninguna de las dos. Una auditoria es el
        // rastro de una decision de moderacion, y una decision se discute
        // mucho despues.
        val u = nuevoUsuario()
        Db.tx { c ->
            c.prepareStatement(
                """INSERT INTO evento_seguridad (usuario_id, tipo, creado_en)
                   VALUES (?, 'ingreso', now() - interval '200 days')"""
            ).use { st -> st.setObject(1, u); st.executeUpdate() }
            c.prepareStatement(
                """INSERT INTO auditoria (actor_id, accion, recurso_tipo, creado_en)
                   VALUES (?, 'prueba', 'prueba', now() - interval '200 days')"""
            ).use { st -> st.setObject(1, u); st.executeUpdate() }
        }

        tareasDeMantenimiento()

        assertEquals(0, cuantos("evento_seguridad", u, "usuario_id"), "200 dias pasa seguridad")
        assertEquals(1, cuantos("auditoria", u, "actor_id"), "pero no la de auditoria")
    }

    // ------------------------------------------------------------------
    // Los sobres que nadie recogio
    // ------------------------------------------------------------------

    @Test
    fun `el mantenimiento barre los sobres vencidos`() {
        // La prueba del defecto original. Va contra `tareasDeMantenimiento` y
        // no contra `Repo.barrerExpirados`: lo que fallaba era la llamada.
        val u = nuevoUsuario()
        val disp = Db.tx { c ->
            c.prepareStatement(
                """INSERT INTO dispositivo
                       (usuario_id, etiqueta, identidad_pub, hardware_hash, hardware_nivel)
                   VALUES (?, 'p', 'x'::bytea, ?, 'SOFTWARE_DEV') RETURNING id"""
            ).use { st ->
                st.setObject(1, u)
                st.setBytes(2, UUID.randomUUID().toString().toByteArray())
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
        }
        Db.tx { c ->
            c.prepareStatement(
                """INSERT INTO sobre_pendiente
                       (destino_dispositivo, origen_dispositivo, cuerpo,
                        creado_en_origen, expira_en)
                   VALUES (?, ?, 'x'::bytea, 0, now() - interval '1 day'),
                          (?, ?, 'x'::bytea, 0, now() + interval '30 days')"""
            ).use { st ->
                st.setObject(1, disp); st.setObject(2, disp)
                st.setObject(3, disp); st.setObject(4, disp)
                st.executeUpdate()
            }
        }
        assertEquals(2, cuantos("sobre_pendiente", disp, "destino_dispositivo"))

        tareasDeMantenimiento()

        assertEquals(
            1, cuantos("sobre_pendiente", disp, "destino_dispositivo"),
            "el vencido se va; el que todavia vale se queda",
        )
    }

    // ------------------------------------------------------------------
    // La forma del mantenimiento
    // ------------------------------------------------------------------

    @Test
    fun `el mantenimiento incluye todos los barridos`() {
        // Fija la LISTA, no las consultas. Es lo que convierte "existe una
        // funcion que barre" en "se barre de verdad": quitar una linea de
        // `tareasDeMantenimiento` hace caer esta prueba aunque el codigo
        // borrado siga compilando en su sitio.
        val hecho = tareasDeMantenimiento()
        for (clave in listOf("cuentas", "cupos", "codigos", "sobres", "seguridad", "auditoria")) {
            assertTrue(clave in hecho, "falta el barrido '$clave'")
        }
        assertTrue(hecho.values.all { it >= 0 }, "nada puede dar negativo")
    }
}
