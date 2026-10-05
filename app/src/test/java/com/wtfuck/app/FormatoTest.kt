package com.wtfuck.app

import com.wtfuck.app.datos.Estilo.*
import com.wtfuck.app.datos.Formato
import com.wtfuck.app.datos.Tramo
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatoTest {

    private fun t(texto: String, vararg e: com.wtfuck.app.datos.Estilo) = Tramo(texto, e.toSet())

    @Test
    fun `sin marcas es un solo tramo`() {
        assertEquals(listOf(t("hola que tal")), Formato.tramos("hola que tal"))
    }

    @Test
    fun `los cinco estilos`() {
        assertEquals(listOf(t("a", NEGRITA)), Formato.tramos("*a*"))
        assertEquals(listOf(t("a", CURSIVA)), Formato.tramos("_a_"))
        assertEquals(listOf(t("a", TACHADO)), Formato.tramos("~a~"))
        assertEquals(listOf(t("a", MONO)), Formato.tramos("`a`"))
        assertEquals(listOf(t("a", SPOILER)), Formato.tramos("||a||"))
    }

    @Test
    fun `las marcas desaparecen y el resto queda`() {
        assertEquals(
            listOf(t("es "), t("muy", NEGRITA), t(" importante")),
            Formato.tramos("es *muy* importante"),
        )
    }

    @Test
    fun `se pueden anidar`() {
        assertEquals(listOf(t("ojo", NEGRITA, CURSIVA)), Formato.tramos("*_ojo_*"))
    }

    @Test
    fun `una cuenta no es negrita`() {
        assertEquals(listOf(t("2*3*4")), Formato.tramos("2*3*4"))
    }

    @Test
    fun `un usuario con guiones bajos no es cursiva`() {
        assertEquals(listOf(t("habla con @juan_perez_x")), Formato.tramos("habla con @juan_perez_x"))
    }

    @Test
    fun `con espacios pegados a la marca no hay formato`() {
        assertEquals(listOf(t("* algo *")), Formato.tramos("* algo *"))
        assertEquals(listOf(t("*algo *")), Formato.tramos("*algo *"))
    }

    @Test
    fun `no cruza saltos de linea`() {
        val texto = "precio *final\nsigue* aqui"
        assertEquals(listOf(t(texto)), Formato.tramos(texto))
    }

    @Test
    fun `dentro de lo monoespaciado no se formatea`() {
        assertEquals(listOf(t("a*b*c", MONO)), Formato.tramos("`a*b*c`"))
    }

    @Test
    fun `una marca sin cierre se queda como texto`() {
        assertEquals(listOf(t("solo *uno")), Formato.tramos("solo *uno"))
    }

    @Test
    fun `marca vacia no es formato`() {
        assertEquals(listOf(t("**")), Formato.tramos("**"))
        assertEquals(listOf(t("||||")), Formato.tramos("||||"))
    }

    @Test
    fun `detras del cierre no puede haber letra`() {
        // "*hola*mundo" no cierra en el segundo asterisco.
        assertEquals(listOf(t("*hola*mundo")), Formato.tramos("*hola*mundo"))
    }

    @Test
    fun `signos de puntuacion alrededor si valen`() {
        assertEquals(
            listOf(t("("), t("ojo", NEGRITA), t(")!")),
            Formato.tramos("(*ojo*)!"),
        )
    }

    @Test
    fun `plano quita las marcas y tapa el spoiler`() {
        assertEquals("es muy importante, el final es ▒▒▒", Formato.plano("es *muy* importante, el final es ||muere||"))
    }

    @Test
    fun `plano sin marcas devuelve lo mismo`() {
        assertEquals("nada raro", Formato.plano("nada raro"))
    }
}
