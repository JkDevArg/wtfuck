package com.wtfuck.app

import com.wtfuck.app.datos.ResultadoBusqueda
import com.wtfuck.app.ui.fragmento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La busqueda global.
 *
 * No prueba la consulta -eso pide una base de datos- sino las dos piezas que
 * pueden fallar sin que se note: el recorte del fragmento y como se decide el
 * titulo del chat al que pertenece cada resultado.
 *
 * Un resultado que sale con el nombre equivocado no parece un fallo: parece
 * que el mensaje era de otra persona.
 */
class BusquedaGlobalTest {

    // ------------------------------------------------- el fragmento

    @Test
    fun `si la coincidencia esta al principio no se recorta`() {
        // Anteponer "…" cuando no hace falta es ruido, y el caso normal es
        // que la palabra buscada este al principio del mensaje.
        val t = "hola que tal, nos vemos manana"
        assertEquals(t, fragmento(t, "hola"))
    }

    @Test
    fun `una coincidencia lejana se recorta por delante`() {
        // Sin esto, la lista diria "coincide" y la persona no veria donde:
        // el texto empezaria por el principio y la palabra quedaria fuera de
        // las dos lineas que caben.
        val t = "a".repeat(200) + " CONTRASENA " + "b".repeat(50)
        val f = fragmento(t, "CONTRASENA")
        assertTrue("no se ve lo buscado: $f", f.contains("CONTRASENA"))
        assertTrue("no marco que venia recortado", f.startsWith("…"))
        assertTrue("sigue siendo larguisimo", f.length < t.length)
    }

    @Test
    fun `el recorte deja contexto por delante`() {
        // Cortar justo en la palabra la deja sin frase y no se entiende.
        val t = "el numero de la reserva del restaurante es 4471 para el sabado"
        val f = fragmento(t, "4471", contexto = 10)
        assertTrue("sin contexto previo: $f", f.contains("es 4471"))
    }

    @Test
    fun `la busqueda no distingue mayusculas`() {
        // Coherente con el LIKE de SQLite, que para ASCII no distingue.
        val t = "x".repeat(100) + " Reunion manana"
        assertTrue(fragmento(t, "reunion").contains("Reunion"))
    }

    @Test
    fun `si no aparece se devuelve el texto tal cual`() {
        // Puede pasar: SQLite encontro la coincidencia con reglas suyas que
        // `indexOf` no reproduce. Devolver el texto entero es mejor que
        // devolver vacio y dejar una fila en blanco.
        val t = "un mensaje cualquiera"
        assertEquals(t, fragmento(t, "zzz"))
    }

    @Test
    fun `una consulta vacia no rompe`() {
        assertEquals("hola", fragmento("hola", ""))
        assertEquals("hola", fragmento("hola", "   "))
    }

    @Test
    fun `un texto vacio no rompe`() {
        assertEquals("", fragmento("", "algo"))
    }

    // --------------------------------------------------- el titulo

    private fun res(
        tipo: String = "directa",
        nombre: String = "fulano",
        nombreMostrado: String = "",
        alias: String = "",
    ) = ResultadoBusqueda(
        id = "m1", conversacionId = "c1", autor = "fulano", esMio = false,
        texto = "hola", creadoEn = 0, tipo = tipo, nombre = nombre,
        nombreMostrado = nombreMostrado, avatarUsername = "", avatarVersion = 0,
        aliasContacto = alias,
    )

    @Test
    fun `manda el nombre que YO le puse`() {
        // Es como lo reconozco. Si la lista de chats lo llama "Ana del
        // trabajo", el resultado tiene que llamarlo igual: dos nombres para
        // la misma persona en la misma pantalla es un fallo de verdad.
        assertEquals(
            "Ana del trabajo",
            res(alias = "Ana del trabajo", nombreMostrado = "Ana Perez").titulo,
        )
    }

    @Test
    fun `sin alias vale el nombre que la persona eligio`() {
        assertEquals("Ana Perez", res(nombreMostrado = "Ana Perez").titulo)
    }

    @Test
    fun `sin nada se cae al username`() {
        assertEquals("fulano", res().titulo)
    }

    @Test
    fun `un grupo usa su nombre y no el mostrado de nadie`() {
        // `nombreMostrado` es el nombre de una PERSONA. En un grupo no aplica,
        // y usarlo pondria el nombre de alguien como titulo del grupo.
        assertEquals(
            "Equipo",
            res(tipo = "grupo", nombre = "Equipo", nombreMostrado = "Ana Perez").titulo,
        )
    }

    @Test
    fun `el alias no se aplica a un grupo por accidente`() {
        // El JOIN de contactos solo engancha en chats directos, pero si algun
        // dia cambiara, el titulo no debe romperse en silencio.
        val r = res(tipo = "grupo", nombre = "Equipo", alias = "")
        assertFalse(r.titulo.isBlank())
        assertEquals("Equipo", r.titulo)
    }
}
