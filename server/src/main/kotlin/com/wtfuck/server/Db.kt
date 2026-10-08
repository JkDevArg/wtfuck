package com.wtfuck.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("Db")

object Db {
    lateinit var ds: DataSource
        private set

    /**
     * Idempotente a proposito: llamarla dos veces no abre un segundo pool.
     *
     * Sin esto, cada instancia de test creaba su propio pool de 10 conexiones y
     * Postgres terminaba rechazando con "too many clients already".
     */
    @Synchronized
    fun iniciar(url: String, usuario: String, clave: String) {
        if (::ds.isInitialized) return

        val cfg = HikariConfig().apply {
            jdbcUrl = url
            username = usuario
            password = clave
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            // El servidor es el unico escritor del esquema; una transaccion corta
            // por request. READ_COMMITTED (default de Postgres) basta.
        }
        ds = HikariDataSource(cfg)
        migrar()
    }

    /** Migraciones conocidas, en orden. Agregar aqui cada archivo nuevo. */
    private val MIGRACIONES = listOf(
        1 to "/db/V1__inicial.sql",
        2 to "/db/V2__perfil.sql",
        3 to "/db/V3__privacidad.sql",
        4 to "/db/V4__rbac.sql",
        5 to "/db/V5__grupos.sql",
        6 to "/db/V6__mensajes.sql",
        7 to "/db/V7__adjuntos.sql",
        8 to "/db/V8__hora_origen.sql",
        9 to "/db/V9__claves_e2ee.sql",
        10 to "/db/V10__canales.sql",
        11 to "/db/V11__evento_canal.sql",
        12 to "/db/V12__moderacion.sql",
        13 to "/db/V13__identidad.sql",
        14 to "/db/V14__telefono.sql",
        15 to "/db/V15__multidispositivo.sql",
        16 to "/db/V16__llamadas.sql",
        17 to "/db/V17__canal_aprobacion.sql",
        18 to "/db/V18__limites_config.sql",
        19 to "/db/V19__conversacion_cerrada.sql",
        20 to "/db/V20__privacidad_presencia.sql",
        21 to "/db/V21__consola_web.sql",
        22 to "/db/V22__escribiendo.sql",
        23 to "/db/V23__privacidad_personalizada.sql",
        24 to "/db/V24__nivel_personalizado_check.sql",
        25 to "/db/V25__reaccion_una_por_persona.sql",
        26 to "/db/V26__sobre_mensaje_id.sql",
        27 to "/db/V27__push.sql",
        28 to "/db/V28__historias.sql",
        29 to "/db/V29__sobre_sin_conversacion.sql",
        30 to "/db/V30__adjunto_de_historia.sql",
        31 to "/db/V31__tipos_de_cuenta.sql",
        32 to "/db/V32__indices.sql",
        33 to "/db/V33__privacidad_fina.sql",
        34 to "/db/V34__comentarios_de_canal.sql",
        35 to "/db/V35__aviso_de_comentario.sql",
        36 to "/db/V36__imagen_de_publicacion.sql",
        37 to "/db/V37__comunidades.sql",
        38 to "/db/V38__aviso_de_participante.sql",
        39 to "/db/V39__conversaciones_temporales.sql",
        40 to "/db/V40__aviso_de_chat_vencido.sql",
        41 to "/db/V41__invitaciones_de_registro.sql",
        42 to "/db/V42__aviso_de_temporizador.sql",
        43 to "/db/V43__codigo_de_recuperacion.sql",
        44 to "/db/V44__directorio_de_usuarios.sql",
        45 to "/db/V45__nota_para_mi.sql",
        46 to "/db/V46__entrega_en_grupos.sql",
        47 to "/db/V47__una_vez_abierta.sql",
        48 to "/db/V48__enlace_contacto.sql",
        49 to "/db/V49__navegador.sql",
        // 50 es `V50__contador_por_red.sql`, de la rama limite-registro.
        51 to "/db/V51__web_push.sql",
    )

    /**
     * Runner de migraciones.
     *
     * Cada archivo se aplica una sola vez y queda anotado en `schema_version`.
     * Es lo minimo que hace falta para poder evolucionar el esquema sin borrar
     * la base; cuando sean muchas mas, conviene cambiarlo por Flyway.
     */
    private fun migrar() {
        ds.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    """CREATE TABLE IF NOT EXISTS schema_version (
                           version   integer PRIMARY KEY,
                           aplicada  timestamptz NOT NULL DEFAULT now()
                       )"""
                )
            }

            // Bases creadas antes de que existiera schema_version: si las tablas
            // de V1 ya estan, se da V1 por aplicada en vez de reintentarla.
            c.createStatement().use { st ->
                st.execute(
                    """INSERT INTO schema_version (version)
                       SELECT 1
                       WHERE to_regclass('public.usuario') IS NOT NULL
                         AND NOT EXISTS (SELECT 1 FROM schema_version WHERE version = 1)"""
                )
            }

            val aplicadas = c.createStatement().use { st ->
                st.executeQuery("SELECT version FROM schema_version").use { rs ->
                    rs.mapear { it.getInt(1) }.toSet()
                }
            }

            for ((version, recurso) in MIGRACIONES) {
                if (version in aplicadas) continue
                val ddl = Db::class.java.getResourceAsStream(recurso)
                    ?.bufferedReader()?.readText()
                    ?: error("No se encontro $recurso en el classpath")

                c.autoCommit = false
                try {
                    c.createStatement().use { it.execute(ddl) }
                    c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)").use {
                        it.setInt(1, version); it.executeUpdate()
                    }
                    c.commit()
                    log.info("Migracion V$version aplicada.")
                } catch (e: Exception) {
                    c.rollback()
                    throw IllegalStateException("Fallo la migracion V$version", e)
                } finally {
                    c.autoCommit = true
                }
            }
        }
    }

    fun <T> tx(bloque: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            val r = bloque(c)
            c.commit()
            r
        } catch (e: Exception) {
            c.rollback()
            throw e
        }
    }

    fun <T> query(bloque: (Connection) -> T): T = ds.connection.use(bloque)
}

/** Recorre un ResultSet mapeando cada fila. */
fun <T> ResultSet.mapear(f: (ResultSet) -> T): List<T> {
    val out = mutableListOf<T>()
    while (next()) out += f(this)
    return out
}

/** Primera fila o null. */
fun <T> ResultSet.primero(f: (ResultSet) -> T): T? = if (next()) f(this) else null
