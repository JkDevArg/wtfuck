package com.wtfuck.server

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quedarse solo en una llamada.
 *
 * ## La regla, y por qué no es una sola
 *
 * En una **directa**, quedarse solo es que la otra persona colgó: no hay nadie
 * más que pueda entrar, así que la llamada termina en el acto. Seguir ahí sería
 * mirar una pantalla que ya no es una llamada.
 *
 * En un **grupo** es normal —alguien llega tarde, alguien se corta y vuelve— y
 * cerrar al instante haría imposible ser el primero en entrar. Así que se
 * permite, con un tope de [Llamadas.MAX_SOLO]: si no, una llamada de grupo que
 * alguien deja abierta por descuido se queda viva hasta el barrido de las doce
 * horas, con el micrófono abierto y sin dejar empezar otra.
 *
 * Las pruebas mueven el reloj de la base en vez de esperar cinco minutos.
 * Esperarlos de verdad haría que nadie corriera esta suite.
 *
 * Requiere `docker compose up -d`.
 */
class SoloEnLlamadaTest {

    @BeforeTest
    fun preparar() {
        Db.iniciar(
            System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck",
            System.getenv("WTFUCK_DB_USER") ?: "wtfuck",
            System.getenv("WTFUCK_DB_PASS") ?: "wtfuck_dev",
        )
    }

    private fun usuario(): UUID = Db.tx { c ->
        c.prepareStatement(
            "INSERT INTO usuario (username, password_hash) VALUES (?, 'x') RETURNING id"
        ).use { st ->
            st.setString(1, "sol" + UUID.randomUUID().toString().take(8).replace("-", ""))
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
    }

    /** Una conversación con su llamada en curso y dos participantes dentro. */
    private fun escena(tipo: String): Triple<UUID, UUID, UUID> = Db.tx { c ->
        val a = usuario()
        val b = usuario()
        // `clave_directa` solo en una directa: hay un CHECK que lo exige en
        // una y lo prohibe en la otra. Que el esquema rechace un escenario mal
        // armado es exactamente lo que tiene que hacer.
        val conv = c.prepareStatement(
            "INSERT INTO conversacion (tipo, nombre, clave_directa) VALUES (?, 'x', ?) RETURNING id"
        ).use { st ->
            st.setString(1, tipo)
            if (tipo == "directa") st.setString(2, UUID.randomUUID().toString())
            else st.setNull(2, java.sql.Types.VARCHAR)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        // Participantes de la CONVERSACION y un aparato por persona.
        //
        // Sin esto no hay a quien avisar, y la prueba de "cerrar en silencio
        // deja la pantalla mintiendo" pasaria siempre por falta de
        // destinatarios en vez de por el aviso.
        for (u in listOf(a, b)) {
            c.prepareStatement(
                "INSERT INTO participante (conversacion_id, usuario_id) VALUES (?, ?)"
            ).use { st -> st.setObject(1, conv); st.setObject(2, u); st.executeUpdate() }
            c.prepareStatement(
                """INSERT INTO dispositivo
                       (usuario_id, etiqueta, identidad_pub, hardware_hash, hardware_nivel)
                   VALUES (?, 'p', 'x'::bytea, ?, 'SOFTWARE_DEV')"""
            ).use { st ->
                st.setObject(1, u)
                st.setBytes(2, UUID.randomUUID().toString().toByteArray())
                st.executeUpdate()
            }
        }
        val llamada = c.prepareStatement(
            """INSERT INTO llamada (conversacion_id, origen_id, estado, contestada_en)
               VALUES (?, ?, 'en_curso', now()) RETURNING id"""
        ).use { st ->
            st.setObject(1, conv); st.setObject(2, a)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        for (u in listOf(a, b)) {
            c.prepareStatement(
                """INSERT INTO llamada_participante (llamada_id, usuario_id, estado)
                   VALUES (?, ?, 'dentro')"""
            ).use { st -> st.setObject(1, llamada); st.setObject(2, u); st.executeUpdate() }
        }
        Triple(llamada, a, b)
    }

    /** Uno se va, y hace cuánto. */
    private fun seVa(llamada: UUID, quien: UUID, haceSegundos: Long) = Db.tx { c ->
        c.prepareStatement(
            """UPDATE llamada_participante
               SET estado = 'fuera', salido_en = now() - make_interval(secs => ?)
               WHERE llamada_id = ? AND usuario_id = ?"""
        ).use { st ->
            st.setDouble(1, haceSegundos.toDouble())
            st.setObject(2, llamada); st.setObject(3, quien)
            st.executeUpdate()
        }
    }

    private fun estado(llamada: UUID): String = Db.query { c ->
        c.prepareStatement("SELECT estado FROM llamada WHERE id = ?").use { st ->
            st.setObject(1, llamada)
            st.executeQuery().use { it.next(); it.getString(1) }
        }
    }

    // ------------------------------------------------------------------
    // El grupo aguanta
    // ------------------------------------------------------------------

    @Test
    fun `en un grupo, quedarse solo un rato corto no cierra nada`() {
        val (llamada, _, b) = escena("grupo")
        seVa(llamada, b, haceSegundos = 60)

        Llamadas.cerrarLlamadasSolitarias()

        assertEquals(
            "en_curso", estado(llamada),
            "un minuto solo es 'ahora vengo', no 'me olvide'",
        )
    }

    @Test
    fun `pasado el tope, la llamada de grupo se cierra sola`() {
        val (llamada, _, b) = escena("grupo")
        seVa(llamada, b, haceSegundos = Llamadas.MAX_SOLO.seconds + 30)

        val (cuantas, avisos) = Llamadas.cerrarLlamadasSolitarias()

        assertTrue(cuantas >= 1, "tendria que haber cerrado al menos esta")
        assertEquals("terminada", estado(llamada))
        // Y se AVISA. Cerrar la fila sin decirlo deja la pantalla de quien
        // quedo solo afirmando una llamada que ya no existe.
        assertTrue(avisos.isNotEmpty(), "cerrar en silencio deja la pantalla mintiendo")
    }

    @Test
    fun `al que quedo solo tambien se le marca la salida`() {
        val (llamada, a, b) = escena("grupo")
        seVa(llamada, b, haceSegundos = Llamadas.MAX_SOLO.seconds + 30)

        Llamadas.cerrarLlamadasSolitarias()

        val suyo = Db.query { c ->
            c.prepareStatement(
                "SELECT estado FROM llamada_participante WHERE llamada_id = ? AND usuario_id = ?"
            ).use { st ->
                st.setObject(1, llamada); st.setObject(2, a)
                st.executeQuery().use { it.next(); it.getString(1) }
            }
        }
        assertEquals(
            "fuera", suyo,
            "sin esto, la persona queda 'dentro' de una llamada terminada y no puede empezar otra",
        )
    }

    // ------------------------------------------------------------------
    // La directa no
    // ------------------------------------------------------------------

    @Test
    fun `una directa no espera, y el barrido de solitarias ni la mira`() {
        // En una directa, quedarse solo ya cerro la llamada al colgar el otro
        // —lo hace `terminar`—, asi que este barrido no tiene nada que hacer
        // ahi. Se comprueba que NO la toque: si la tocara, estaria cerrando
        // por el motivo equivocado y el historial diria otra cosa.
        val (llamada, _, b) = escena("directa")
        seVa(llamada, b, haceSegundos = Llamadas.MAX_SOLO.seconds + 300)

        Llamadas.cerrarLlamadasSolitarias()

        assertEquals(
            "en_curso", estado(llamada),
            "este barrido es solo de grupos; la directa la cierra `terminar`",
        )
    }

    // ------------------------------------------------------------------
    // Los bordes
    // ------------------------------------------------------------------

    @Test
    fun `con dos personas dentro no se cierra por mucho tiempo que pase`() {
        // Nadie se fue: no hay `salido_en`, y la consulta no puede confundir
        // "no hay fecha" con "hace mucho". Es el caso que un `NULL` mal
        // comparado convertiria en cerrar llamadas que estan funcionando.
        val (llamada, _, _) = escena("grupo")

        Llamadas.cerrarLlamadasSolitarias()

        assertEquals("en_curso", estado(llamada))
    }

    @Test
    fun `una llamada ya terminada no se vuelve a tocar`() {
        val (llamada, _, b) = escena("grupo")
        seVa(llamada, b, haceSegundos = Llamadas.MAX_SOLO.seconds + 30)
        Db.tx { c ->
            // `terminada_en` tambien: hay un CHECK que no admite una llamada
            // terminada sin fecha de fin. Otra defensa del esquema.
            c.prepareStatement(
                "UPDATE llamada SET estado = 'terminada', terminada_en = now() WHERE id = ?"
            ).use { st -> st.setObject(1, llamada); st.executeUpdate() }
        }

        val (cuantas, _) = Llamadas.cerrarLlamadasSolitarias()
        // No se afirma que sea 0 —puede haber otras de otra prueba— sino que
        // ESTA sigue como estaba, que es lo que importa.
        assertTrue(cuantas >= 0)
        assertEquals("terminada", estado(llamada))
    }

    @Test
    fun `cinco minutos, y esta escrito en un solo sitio`() {
        // El numero vive en `Llamadas.MAX_SOLO` y las pruebas lo leen de ahi.
        // Si alguien lo cambia, estas pruebas siguen valiendo; si alguien lo
        // duplica en una consulta, esta afirmacion es la que lo delata.
        assertEquals(300, Llamadas.MAX_SOLO.seconds)
    }
}
