package com.wtfuck.server

import java.sql.Connection
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auditar no puede tumbar la accion, y no puede perder el rastro.
 *
 * ## El defecto que estas pruebas cierran
 *
 * El `detalle` de una auditoria entra como `?::jsonb`. Si no es JSON valido,
 * Postgres falla la sentencia, y **una sentencia fallida aborta la transaccion
 * entera**: el `commit()` posterior se vuelve un ROLLBACK silencioso. La accion
 * devuelve 200 y no paso nada.
 *
 * Hoy no ocurria porque todos los `detalle` interpolan valores de listas
 * cerradas —"por accidente de orden", como lo llamo la auditoria—. El dia que
 * alguien interpolara un nombre con una comilla, una suspension se perderia sin
 * dejar constancia de por que. Estas pruebas provocan ese caso a proposito.
 */
class AuditoriaTest {

    private lateinit var c: Connection
    private val sufijo = UUID.randomUUID().toString().take(6)
    private lateinit var actor: UUID

    @BeforeTest
    fun preparar() {
        Db.iniciar(
            System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck",
            System.getenv("WTFUCK_DB_USER") ?: "wtfuck",
            System.getenv("WTFUCK_DB_PASS") ?: "wtfuck_dev",
        )
        c = Db.ds.connection
        c.autoCommit = true
        actor = c.prepareStatement(
            "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
        ).use { st ->
            st.setString(1, "aud$sufijo")
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
    }

    @AfterTest
    fun limpiar() {
        c.prepareStatement("DELETE FROM usuario WHERE id = ?").use {
            it.setObject(1, actor); it.executeUpdate()
        }
        c.close()
    }

    private fun cuantas(accion: String): Int =
        c.prepareStatement("SELECT count(*) FROM auditoria WHERE actor_id = ? AND accion = ?").use { st ->
            st.setObject(1, actor); st.setString(2, accion)
            st.executeQuery().use { it.next(); it.getInt(1) }
        }

    private fun detalleDeLaFila(accion: String): String? =
        c.prepareStatement(
            "SELECT detalle::text FROM auditoria WHERE actor_id = ? AND accion = ? LIMIT 1"
        ).use { st ->
            st.setObject(1, actor); st.setString(2, accion)
            st.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    // ============================================================
    //  El caso que costaba una transaccion entera
    // ============================================================

    @Test
    fun `un detalle malformado NO aborta la transaccion de la accion`() {
        // Se simula exactamente la situacion real: una accion de negocio y su
        // auditoria dentro de la MISMA transaccion. Antes, el INSERT fallido de
        // la auditoria abortaba todo y el commit no guardaba nada.
        val nombre = "nom$sufijo"
        c.autoCommit = false
        try {
            c.prepareStatement("UPDATE usuario SET nombre_mostrado = ? WHERE id = ?").use { st ->
                st.setString(1, nombre); st.setObject(2, actor); st.executeUpdate()
            }
            // JSON roto a proposito: es lo que produce interpolar un texto con
            // una comilla dentro de `{"x":"..."}`.
            Autz.auditar(c, actor, "prueba.malformado", "usuario", actor, detalle = """{"x":"a"b"}""")
            c.commit()
        } finally {
            c.autoCommit = true
        }

        val guardado = c.prepareStatement("SELECT coalesce(nombre_mostrado,'') FROM usuario WHERE id = ?")
            .use { st ->
                st.setObject(1, actor)
                st.executeQuery().use { it.next(); it.getString(1) }
            }
        assertEquals(nombre, guardado, "la accion de negocio tiene que haberse guardado")
    }

    @Test
    fun `y la fila de auditoria queda, sin el detalle que venia mal`() {
        // Un audit log sin fila es peor que uno sin adorno: lo que importa es
        // que conste QUE se hizo y QUIEN, no el JSON accesorio.
        c.autoCommit = false
        try {
            Autz.auditar(c, actor, "prueba.rastro", "usuario", actor, detalle = "{esto no es json")
            c.commit()
        } finally {
            c.autoCommit = true
        }
        assertEquals(1, cuantas("prueba.rastro"), "el rastro no se pierde")
        assertEquals(null, detalleDeLaFila("prueba.rastro"), "y el detalle invalido no se guarda")
    }

    @Test
    fun `un detalle valido se guarda tal cual`() {
        c.autoCommit = false
        try {
            Autz.auditar(c, actor, "prueba.buena", "usuario", actor, detalle = """{"horas":24}""")
            c.commit()
        } finally {
            c.autoCommit = true
        }
        val d = detalleDeLaFila("prueba.buena")
        assertTrue(d != null && d.contains("24"), "el detalle bueno se conserva: $d")
    }

    // ============================================================
    //  El constructor que hace que el caso no exista
    // ============================================================

    @Test
    fun `detalleDe escapa las comillas en vez de romper el JSON`() {
        // Es el caso que tumbaba la transaccion, ahora imposible por
        // construccion: el valor va escapado, no interpolado.
        val json = Autz.detalleDe("nombre" to """Banco "Nacional" S.A.""")
        c.autoCommit = false
        try {
            Autz.auditar(c, actor, "prueba.escapado", "usuario", actor, detalle = json)
            c.commit()
        } finally {
            c.autoCommit = true
        }
        val d = detalleDeLaFila("prueba.escapado")
        assertTrue(d != null && d.contains("Nacional"), "se guardo con comillas dentro: $d")
    }

    @Test
    fun `detalleDe respeta los tipos y no escribe todo como texto`() {
        // Un `24` entre comillas obliga a quien lea la bitacora a convertirlo,
        // y quien escribe una consulta sobre el campo se encuentra con que
        // `detalle->'horas' > 12` no funciona.
        val json = Autz.detalleDe("horas" to 24, "permanente" to false)
        assertTrue(json.contains("\"horas\":24"), "el numero va sin comillas: $json")
        assertTrue(json.contains("\"permanente\":false"), "el booleano tambien: $json")
    }

    @Test
    fun `detalleDe omite los nulos en vez de escribir null`() {
        // En un registro que alguien va a leer, una clave ausente dice lo mismo
        // que `null` y ocupa menos.
        val json = Autz.detalleDe("motivo" to "spam", "horas" to null)
        assertTrue(json.contains("motivo"), "lo que hay se escribe: $json")
        assertTrue(!json.contains("horas"), "lo que no hay no aparece: $json")
    }

    @Test
    fun `detalleDe sin datos devuelve un objeto vacio, no una cadena vacia`() {
        // Una cadena vacia NO es JSON valido y romperia el cast: este es el
        // caso limite que convertiria el constructor seguro en un problema.
        assertEquals("{}", Autz.detalleDe())
    }
}
