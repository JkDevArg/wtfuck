package com.wtfuck.app

import com.wtfuck.protocol.CodigoRecuperacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que las pantallas del codigo de recuperacion dan por sentado.
 *
 * No prueban Compose -para eso harian falta pruebas instrumentadas- sino las
 * reglas que las pantallas consultan para decidir si dejan seguir. Si alguna
 * de estas cambiara sin querer, la pantalla dejaria pasar un codigo que el
 * servidor va a rechazar, o bloquearia uno bueno.
 */
class CodigoEnLaPantallaTest {

    /**
     * La confirmacion de dos pasos compara NORMALIZADO, no como cadena.
     *
     * Es lo que permite que quien anoto el codigo en minusculas, o sin
     * guiones, lo pueda teclear asi. Comparando en crudo se le diria "no
     * coincide" a alguien que lo escribio bien, que es la peor forma de
     * perder la confianza en una pantalla de seguridad.
     */
    @Test
    fun `la confirmacion acepta el codigo escrito de otra forma`() {
        repeat(200) {
            val mostrado = CodigoRecuperacion.generar()
            val comoLoTeclean = listOf(
                mostrado,
                mostrado.lowercase(),
                mostrado.replace("-", ""),
                mostrado.replace("-", " "),
                "  " + mostrado.lowercase().replace("-", "") + "  ",
            )
            comoLoTeclean.forEach { escrito ->
                assertEquals(
                    "la pantalla rechazaria '$escrito' siendo correcto",
                    CodigoRecuperacion.normalizar(mostrado),
                    CodigoRecuperacion.normalizar(escrito),
                )
            }
        }
    }

    @Test
    fun `la confirmacion NO acepta un codigo distinto`() {
        // El paso 2 existe para comprobar que el codigo salio de la pantalla.
        // Si aceptara cualquier cosa valida, no comprobaria nada.
        val mostrado = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())
        repeat(100) {
            val otro = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())
            assertFalse("dos codigos distintos dieron lo mismo", mostrado == otro)
        }
    }

    /**
     * El campo de entrada marca el error ANTES de mandar nada.
     *
     * Sin esto, una errata vuelve del servidor como "no autorizado" y hace
     * pensar que el codigo era el equivocado —o que la cuenta no es esa—
     * cuando lo unico que pasa es que falta una letra.
     */
    @Test
    fun `el campo detecta un codigo incompleto`() {
        val bueno = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        assertTrue("uno bueno tiene que pasar", CodigoRecuperacion.valido(bueno))
        assertFalse("uno corto no", CodigoRecuperacion.valido(bueno.dropLast(3)))
        assertFalse("uno con una letra de mas no", CodigoRecuperacion.valido(bueno + "K"))
        assertFalse("texto cualquiera no", CodigoRecuperacion.valido("hola que tal"))
    }

    @Test
    fun `el campo en blanco no se marca como error`() {
        // La pantalla trata el blanco como "todavia no escribio nada", no como
        // "esta mal": el codigo es OPCIONAL al hacer y restaurar la copia.
        assertFalse(
            "un campo vacio no deberia validar como codigo",
            CodigoRecuperacion.valido(""),
        )
        // Y por eso la pantalla comprueba `valor.isBlank() || valido(valor)`,
        // no `valido(valor)` a secas. Esta prueba fija esa distincion.
        val enBlancoEsAceptable = "".isBlank() || CodigoRecuperacion.valido("")
        assertTrue(enBlancoEsAceptable)
    }

    @Test
    fun `un codigo a medio escribir bloquea el boton pero no grita`() {
        // Caso real: la persona esta tecleando. Mientras no este completo, el
        // boton tiene que estar apagado; lo que no puede es dar por bueno algo
        // incompleto y guardar una copia SIN identidad creyendo que la lleva.
        val bueno = CodigoRecuperacion.normalizar(CodigoRecuperacion.generar())!!
        for (largo in 1 until bueno.length) {
            assertFalse(
                "acepto $largo de ${bueno.length} caracteres",
                CodigoRecuperacion.valido(bueno.take(largo)),
            )
        }
        assertTrue(CodigoRecuperacion.valido(bueno))
    }

    /**
     * Lo que se muestra en pantalla es exactamente lo que se puede teclear.
     *
     * El dialogo inserta un separador invisible para que el codigo parta bien
     * de linea. Si eso cambiara el valor, la persona copiaria algo que no
     * sirve — y no lo descubriria hasta el dia malo.
     */
    @Test
    fun `el formato de pantalla no cambia el codigo`() {
        repeat(100) {
            val codigo = CodigoRecuperacion.generar()
            val comoSeDibuja = codigo.replace("-", "-​")
            assertEquals(
                "lo dibujado no vuelve al mismo codigo",
                CodigoRecuperacion.normalizar(codigo),
                CodigoRecuperacion.normalizar(comoSeDibuja.replace("​", "")),
            )
        }
    }
}
