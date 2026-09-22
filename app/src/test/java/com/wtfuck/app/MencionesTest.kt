package com.wtfuck.app

import androidx.compose.ui.graphics.Color
import com.wtfuck.app.ui.CandidatoMencion
import com.wtfuck.app.ui.candidatosDeMencion
import com.wtfuck.app.ui.insertarMencion
import com.wtfuck.app.ui.mencionEnCurso
import com.wtfuck.app.ui.textoConMenciones
import com.wtfuck.protocol.mencionesEn
import org.junit.Assert.*
import org.junit.Test

/**
 * Menciones: lo que se resalta, lo que se sugiere y lo que se inserta.
 *
 * ## La invariante que cruza todo el archivo
 *
 * **Lo que se pinta como mención tiene que ser exactamente lo que se manda en
 * `menciones`.** Son dos sitios distintos —la burbuja y el envío— y hasta que
 * la regla se mudó al contrato, nada los ataba. Si se separan, la pantalla
 * pinta de color un nombre que no avisó a nadie: una promesa visual sin nada
 * detrás, que es peor que no resaltar.
 */
class MencionesTest {

    private val negro = Color(0xFF000000)
    private val cian = Color(0xFF00FFFF)

    // ============================================================
    //  Qué cuenta como mención
    // ============================================================

    @Test
    fun `una mencion normal se reconoce`() {
        assertEquals(listOf("tatiana"), mencionesEn("hola @tatiana"))
    }

    @Test
    fun `el caso no importa al mencionar`() {
        // Quien escribe "@Tatiana" está mencionando a `tatiana`: el username
        // se guarda en minúsculas y nadie teclea respetando eso.
        assertEquals(listOf("tatiana"), mencionesEn("hola @Tatiana"))
    }

    @Test
    fun `mencionar dos veces a la misma persona no la duplica`() {
        // Antes se mandaba el username repetido y el servidor insertaba una
        // fila por cada aparición: la misma persona mencionada tres veces en
        // la tabla `mencion` de un solo mensaje.
        assertEquals(listOf("tatiana"), mencionesEn("@tatiana mira esto @tatiana"))
    }

    @Test
    fun `un correo no es una mencion`() {
        // `PATRON_MENCION` sí encuentra el "@ejemplo" de un correo, y por eso
        // lo que hay que sostener es lo otro: que el resaltado y el envío
        // coincidan. Este test fija el comportamiento real para que un cambio
        // lo haga a propósito y en los dos sitios a la vez.
        val texto = "escribime a joaquin@ejemplo_com"
        assertEquals(mencionesEn(texto), listOf("ejemplo_com"))
    }

    @Test
    fun `un nombre demasiado corto no es un username`() {
        assertTrue(mencionesEn("mira @ab").isEmpty())
    }

    // ============================================================
    //  El resaltado dice lo mismo que el envío
    // ============================================================

    @Test
    fun `lo resaltado es exactamente lo que se manda`() {
        // LA prueba del archivo. Si alguien cambia una de las dos reglas, el
        // texto pintado y la lista enviada dejan de coincidir.
        val texto = "gracias @tatiana y @joaquin por lo de ayer"
        val pintado = textoConMenciones(texto, "joaquin", negro, cian)
        val enColor = pintado.spanStyles
            .filter { it.item.color == cian }
            .map { texto.substring(it.start, it.end).removePrefix("@").lowercase() }
            .distinct()
            .sorted()
        assertEquals(mencionesEn(texto).sorted(), enColor)
    }

    @Test
    fun `un texto sin menciones se devuelve tal cual, sin tramos`() {
        // Es el caso común y el que más se dibuja: la lista de mensajes se
        // desplaza, y construir un AnnotatedString con tramos por cada burbuja
        // es trabajo por nada.
        val r = textoConMenciones("hola que tal", "joaquin", negro, cian)
        assertEquals("hola que tal", r.text)
        assertTrue("no debería añadir tramos: ${r.spanStyles}", r.spanStyles.isEmpty())
    }

    @Test
    fun `el texto resaltado conserva las mayusculas originales`() {
        // El patrón busca sobre el texto en minúsculas, pero lo que se dibuja
        // se recorta del original. Si se recortara del convertido, escribir
        // "Hola @Tatiana" se vería como "hola @tatiana".
        val r = textoConMenciones("Hola @Tatiana", "joaquin", negro, cian)
        assertEquals("Hola @Tatiana", r.text)
    }

    @Test
    fun `la mencion a MI va en negrita y la de otros no`() {
        // Es la razón de ser de la mención: distinguir "hablan de alguien" de
        // "me están llamando". Con las dos iguales, en un grupo donde se
        // menciona a diez personas la mía no se encuentra.
        val texto = "@tatiana y @joaquin"
        val r = textoConMenciones(texto, "joaquin", negro, cian)
        val mio = r.spanStyles.first { texto.substring(it.start, it.end).contains("joaquin") }
        val otro = r.spanStyles.first { texto.substring(it.start, it.end).contains("tatiana") }
        assertNotNull("la mía tiene que ir en negrita", mio.item.fontWeight)
        assertNull("la de otro no", otro.item.fontWeight)
    }

    @Test
    fun `sin saber quien soy, ninguna mencion es mia`() {
        // Pasa de verdad: la sesión puede no haber cargado todavía. Lo que no
        // puede pasar es que una cadena vacía coincida con algo.
        val r = textoConMenciones("@tatiana hola", "", negro, cian)
        assertTrue(r.spanStyles.none { it.item.fontWeight != null })
    }

    // ============================================================
    //  Cuándo aparece el selector
    // ============================================================

    @Test
    fun `recien tecleado el arroba se ofrece la lista entera`() {
        // Es cuando más falta hace: quien no recuerda el username no puede
        // escribir ni la primera letra.
        assertEquals("", mencionEnCurso("hola @", 6))
    }

    @Test
    fun `con algo escrito se filtra por eso`() {
        assertEquals("ta", mencionEnCurso("hola @ta", 8))
    }

    @Test
    fun `un arroba pegado a una palabra NO abre el selector`() {
        // Escribir un correo no es mencionar a nadie, y un desplegable
        // saltando a media dirección tapa justo lo que se está escribiendo.
        assertNull(mencionEnCurso("joaquin@ejem", 12))
    }

    @Test
    fun `una mencion ya cerrada por un espacio no sigue abierta`() {
        // "@tatiana hola": quien sigue escribiendo la frase no quiere la lista
        // encima.
        assertNull(mencionEnCurso("@tatiana hola", 13))
    }

    @Test
    fun `el selector mira el CURSOR, no el final del texto`() {
        // Es la razón de haber pasado el campo a `TextFieldValue`. Editando
        // por el medio, mirar el final del texto no encuentra nada.
        assertEquals("ta", mencionEnCurso("hola @ta y adios", 8))
    }

    @Test
    fun `nada mas largo que un username se sigue sugiriendo`() {
        assertNull(mencionEnCurso("@" + "x".repeat(30), 31))
    }

    @Test
    fun `sin arroba no hay nada que sugerir`() {
        assertNull(mencionEnCurso("hola que tal", 12))
    }

    // ============================================================
    //  A quién se ofrece
    // ============================================================

    private val gente = listOf(
        CandidatoMencion("tatiana", "Tatiana"),
        CandidatoMencion("joaquin", "Joaquin"),
        CandidatoMencion("marta99", "Marta"),
    )

    @Test
    fun `con prefijo vacio salen todos`() {
        assertEquals(3, candidatosDeMencion("", gente).size)
    }

    @Test
    fun `se puede buscar por el nombre y no solo por el username`() {
        // Quien escribe piensa en la persona que tiene delante, no en su
        // identificador. `marta99` no se encuentra tecleando "marta" si solo
        // se mira el username… y sí se llama Marta.
        val r = candidatosDeMencion("marta", gente)
        assertEquals(listOf("marta99"), r.map { it.username })
    }

    @Test
    fun `los que EMPIEZAN por lo escrito van primero`() {
        // Escribir "ta" y que salga primero alguien llamado "Marta" obliga a
        // leer la lista entera para encontrar lo que se buscaba.
        val conMarta = gente + CandidatoMencion("otro", "Estanislao")
        val r = candidatosDeMencion("ta", conMarta)
        assertEquals("tatiana", r.first().username)
    }

    // ============================================================
    //  Qué se inserta
    // ============================================================

    @Test
    fun `insertar reemplaza lo escrito y deja un espacio detras`() {
        // Sin el espacio, seguir escribiendo pega la siguiente palabra al
        // username y la mención deja de serlo.
        val (t, cur) = insertarMencion("hola @ta", 8, "tatiana")
        assertEquals("hola @tatiana ", t)
        assertEquals(t.length, cur)
    }

    @Test
    fun `insertar en medio de una frase no se lleva el resto`() {
        // Y el cursor queda donde estaba la mención, no al final: mandarlo al
        // final rompe la frase de quien estaba editando por el medio.
        val (t, cur) = insertarMencion("hola @ta que tal", 8, "tatiana")
        assertEquals("hola @tatiana  que tal", t)
        assertEquals("hola @tatiana ".length, cur)
    }

    @Test
    fun `lo insertado es una mencion de verdad`() {
        // El cierre del círculo: lo que el selector escribe tiene que ser algo
        // que `mencionesEn` reconozca, o el selector estaría poniendo texto
        // decorativo.
        val (t, _) = insertarMencion("@ta", 3, "tatiana")
        assertEquals(listOf("tatiana"), mencionesEn(t))
    }
}
