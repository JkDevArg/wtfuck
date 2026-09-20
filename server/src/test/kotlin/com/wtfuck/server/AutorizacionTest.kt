package com.wtfuck.server

import java.sql.Connection
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pruebas del motor de permisos.
 *
 * Son los casos que el brief pide explicitamente y que es donde se rompe un
 * RBAC mal hecho. Corren contra la base real: el motor vive en SQL tanto como
 * en Kotlin, y probarlo con dobles no probaria nada.
 *
 * Requiere `docker compose up -d`.
 */
class AutorizacionTest {

    private lateinit var c: Connection
    private val sufijo = UUID.randomUUID().toString().take(6)

    private lateinit var propietario: UUID
    private lateinit var admin: UUID
    private lateinit var moderador: UUID
    private lateinit var miembro: UUID
    private lateinit var extrano: UUID
    private lateinit var grupo: UUID

    @BeforeTest
    fun preparar() {
        // Db.iniciar es idempotente: JUnit crea una instancia por test, pero
        // el pool se abre una sola vez para toda la corrida.
        Db.iniciar(
            System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck",
            System.getenv("WTFUCK_DB_USER") ?: "wtfuck",
            System.getenv("WTFUCK_DB_PASS") ?: "wtfuck_dev",
        )
        c = Db.ds.connection
        c.autoCommit = true

        propietario = nuevoUsuario("prop")
        admin = nuevoUsuario("adm")
        moderador = nuevoUsuario("mod")
        miembro = nuevoUsuario("mie")
        extrano = nuevoUsuario("ext")

        grupo = nuevaConversacion("grupo")
        unir(grupo, propietario, "propietario")
        unir(grupo, admin, "administrador")
        unir(grupo, moderador, "moderador")
        unir(grupo, miembro, "miembro")
    }

    @AfterTest
    fun limpiar() {
        // Borrar el usuario arrastra participantes, restricciones y overrides.
        listOf(propietario, admin, moderador, miembro, extrano).forEach { id ->
            c.prepareStatement("DELETE FROM usuario WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() }
        }
        c.prepareStatement("DELETE FROM conversacion WHERE id = ?").use { it.setObject(1, grupo); it.executeUpdate() }
        c.close()
    }

    // ============================================================
    //  Casos del brief
    // ============================================================

    @Test
    fun `un miembro normal puede enviar mensajes`() {
        assertTrue(Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR).permitido)
    }

    @Test
    fun `quien no pertenece al grupo no puede nada`() {
        val v = Autz.puede(c, extrano, grupo, Permisos.MSG_ENVIAR)
        assertFalse(v.permitido)
        assertEquals(Denegacion.NO_PERTENECE, v.motivo)
    }

    @Test
    fun `un expulsado no puede enviar mensajes`() {
        restringir(miembro, "expulsado", null)
        val v = Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR)
        assertFalse(v.permitido)
        assertEquals(Denegacion.EXPULSADO, v.motivo)
    }

    @Test
    fun `un silenciado no puede escribir pero si puede leer`() {
        restringir(miembro, "silenciado", null)
        assertEquals(
            Denegacion.SILENCIADO,
            Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR).motivo,
        )
        assertTrue(
            Autz.puede(c, miembro, grupo, Permisos.MIEMBRO_VER).permitido,
            "silenciar no debe impedir seguir leyendo",
        )
    }

    @Test
    fun `un silencio vencido deja de aplicar solo`() {
        // Vencido hace una hora: no hace falta ningun job que lo levante.
        restringir(miembro, "silenciado", "now() - interval '1 hour'")
        assertTrue(Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR).permitido)
    }

    @Test
    fun `un moderador no tiene permisos de administrador`() {
        assertTrue(Autz.puede(c, moderador, grupo, Permisos.MIEMBRO_EXPULSAR).permitido)
        val v = Autz.puede(c, moderador, grupo, Permisos.GRUPO_EDITAR_INFO)
        assertFalse(v.permitido, "un moderador no debe poder editar la info del grupo")
        assertEquals(Denegacion.SIN_PERMISO, v.motivo)
    }

    @Test
    fun `solo el propietario puede eliminar el grupo`() {
        assertTrue(Autz.puede(c, propietario, grupo, Permisos.GRUPO_ELIMINAR).permitido)
        assertFalse(Autz.puede(c, admin, grupo, Permisos.GRUPO_ELIMINAR).permitido)
    }

    @Test
    fun `un moderador no puede expulsar a un administrador`() {
        // Tiene el permiso, pero no la jerarquia.
        assertTrue(Autz.puede(c, moderador, grupo, Permisos.MIEMBRO_EXPULSAR).permitido)
        val e = runCatching {
            Autz.exigirSobre(c, moderador, admin, grupo, Permisos.MIEMBRO_EXPULSAR)
        }.exceptionOrNull()
        assertTrue(e is ErrorNegocio && e.codigo == 403, "se esperaba 403 por jerarquia")
    }

    @Test
    fun `nadie puede actuar sobre alguien de su mismo rango`() {
        val otroAdmin = nuevoUsuario("adm2")
        unir(grupo, otroAdmin, "administrador")
        val e = runCatching {
            Autz.exigirSobre(c, admin, otroAdmin, grupo, Permisos.MIEMBRO_EXPULSAR)
        }.exceptionOrNull()
        assertTrue(e is ErrorNegocio, "dos administradores no deben poder expulsarse entre si")
        c.prepareStatement("DELETE FROM usuario WHERE id = ?").use { it.setObject(1, otroAdmin); it.executeUpdate() }
    }

    @Test
    fun `el propietario no puede ser expulsado por un administrador`() {
        val e = runCatching {
            Autz.exigirSobre(c, admin, propietario, grupo, Permisos.MIEMBRO_EXPULSAR)
        }.exceptionOrNull()
        assertTrue(e is ErrorNegocio)
    }

    @Test
    fun `un override de denegar gana sobre el permiso del rol`() {
        assertTrue(Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR).permitido)
        override(miembro, Permisos.MSG_ENVIAR, false)
        val v = Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR)
        assertFalse(v.permitido)
        assertEquals(Denegacion.PERMISO_DENEGADO_EXPLICITO, v.motivo)
    }

    @Test
    fun `un override de permitir concede algo que el rol no da`() {
        assertFalse(Autz.puede(c, miembro, grupo, Permisos.MSG_FIJAR).permitido)
        override(miembro, Permisos.MSG_FIJAR, true)
        assertTrue(Autz.puede(c, miembro, grupo, Permisos.MSG_FIJAR).permitido)
    }

    @Test
    fun `degradar a alguien surte efecto de inmediato`() {
        assertTrue(Autz.puede(c, admin, grupo, Permisos.GRUPO_EDITAR_INFO).permitido)
        cambiarRol(admin, "miembro")
        assertFalse(
            Autz.puede(c, admin, grupo, Permisos.GRUPO_EDITAR_INFO).permitido,
            "los permisos no deben sobrevivir a la degradacion",
        )
    }

    @Test
    fun `una cuenta suspendida no puede hacer nada`() {
        c.prepareStatement("UPDATE usuario SET suspendido_en = now() WHERE id = ?").use {
            it.setObject(1, miembro); it.executeUpdate()
        }
        val v = Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR)
        assertFalse(v.permitido)
        assertEquals(Denegacion.CUENTA_SUSPENDIDA, v.motivo)
    }

    @Test
    fun `un bloqueo cierra la conversacion directa en ambos sentidos`() {
        val directa = nuevaConversacion("directa")
        unir(directa, miembro, "miembro")
        unir(directa, extrano, "miembro")

        assertTrue(Autz.puede(c, miembro, directa, Permisos.MSG_ENVIAR).permitido)

        Autz.bloquear(c, miembro, extrano)

        assertEquals(
            Denegacion.BLOQUEADO,
            Autz.puede(c, miembro, directa, Permisos.MSG_ENVIAR).motivo,
            "quien bloquea tampoco escribe",
        )
        assertEquals(
            Denegacion.BLOQUEADO,
            Autz.puede(c, extrano, directa, Permisos.MSG_ENVIAR).motivo,
            "el bloqueado tampoco",
        )

        Autz.desbloquear(c, miembro, extrano)
        assertTrue(Autz.puede(c, miembro, directa, Permisos.MSG_ENVIAR).permitido)

        c.prepareStatement("DELETE FROM conversacion WHERE id = ?").use {
            it.setObject(1, directa); it.executeUpdate()
        }
    }

    @Test
    fun `un bloqueo personal no rompe un grupo compartido`() {
        // Bloquear a alguien no te echa de los grupos donde ambos estan.
        Autz.bloquear(c, miembro, moderador)
        assertTrue(
            Autz.puede(c, miembro, grupo, Permisos.MSG_ENVIAR).permitido,
            "el bloqueo solo aplica a conversaciones directas",
        )
        Autz.desbloquear(c, miembro, moderador)
    }

    @Test
    fun `la auditoria deja rastro de la accion`() {
        Autz.auditar(c, admin, "miembro.expulsar", "conversacion", grupo, miembro, """{"motivo":"spam"}""")
        val n = c.prepareStatement(
            "SELECT count(*) FROM auditoria WHERE actor_id = ? AND recurso_id = ?"
        ).use { st ->
            st.setObject(1, admin); st.setObject(2, grupo)
            st.executeQuery().use { it.next(); it.getInt(1) }
        }
        assertEquals(1, n)
    }

    // ============================================================
    //  Utilidades
    // ============================================================

    private fun nuevoUsuario(prefijo: String): UUID =
        c.prepareStatement(
            "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
        ).use { st ->
            st.setString(1, "${prefijo}_$sufijo${(0..99999).random()}")
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

    private fun nuevaConversacion(tipo: String): UUID =
        c.prepareStatement(
            "INSERT INTO conversacion (tipo, nombre, clave_directa) VALUES (?, ?, ?) RETURNING id"
        ).use { st ->
            st.setString(1, tipo)
            st.setString(2, if (tipo == "grupo") "Prueba $sufijo" else null)
            st.setString(3, if (tipo == "directa") "d-$sufijo-${(0..99999).random()}" else null)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

    private fun unir(conv: UUID, usuario: UUID, rolClave: String) {
        c.prepareStatement(
            """INSERT INTO participante (conversacion_id, usuario_id, rol, rol_id)
               VALUES (?, ?, 'miembro', (SELECT id FROM rol WHERE es_sistema AND clave = ?))"""
        ).use { st ->
            st.setObject(1, conv); st.setObject(2, usuario); st.setString(3, rolClave)
            st.executeUpdate()
        }
    }

    private fun cambiarRol(usuario: UUID, rolClave: String) {
        c.prepareStatement(
            """UPDATE participante SET rol_id = (SELECT id FROM rol WHERE es_sistema AND clave = ?)
               WHERE conversacion_id = ? AND usuario_id = ?"""
        ).use { st ->
            st.setString(1, rolClave); st.setObject(2, grupo); st.setObject(3, usuario)
            st.executeUpdate()
        }
    }

    private fun restringir(usuario: UUID, tipo: String, hastaSql: String?) {
        val hasta = hastaSql ?: "NULL"
        c.prepareStatement(
            "INSERT INTO restriccion (conversacion_id, usuario_id, tipo, hasta) VALUES (?, ?, ?, $hasta)"
        ).use { st ->
            st.setObject(1, grupo); st.setObject(2, usuario); st.setString(3, tipo)
            st.executeUpdate()
        }
    }

    private fun override(usuario: UUID, permiso: String, permitido: Boolean) {
        c.prepareStatement(
            """INSERT INTO permiso_override (conversacion_id, usuario_id, permiso, permitido)
               VALUES (?, ?, ?, ?)"""
        ).use { st ->
            st.setObject(1, grupo); st.setObject(2, usuario)
            st.setString(3, permiso); st.setBoolean(4, permitido)
            st.executeUpdate()
        }
    }
}
