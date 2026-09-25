package com.wtfuck.app

import com.wtfuck.app.ui.ClaseDeChat
import com.wtfuck.app.ui.claseDeTipo
import org.junit.Assert.*
import org.junit.Test

/**
 * De qué clase es una conversación, para dibujarla como lo que es.
 *
 * ## Por qué esto existe
 *
 * Tres veces se dibujó una cosa como si fuera otra:
 *
 * | Dónde | Qué se veía |
 * |---|---|
 * | Módulo AE | Un canal con el icono de grupo en "Mis canales" |
 * | Módulo AJ | Una llamada de grupo con el icono de una persona |
 * | Pantalla de llamada | `@Equipo seguridad` con iniciales, como una persona |
 *
 * Las tres por la misma causa: `Avatar` tenía `esGrupo: Boolean = false`, y ese
 * valor por defecto significa **"si no dices nada, es una persona"**. La razón
 * escrita para que fuera opcional era no tener que tocar los once sitios que ya
 * existían; salió más caro que tocarlos.
 *
 * Ahora `Avatar` no tiene banderas y `AvatarDeChat` exige `clase`, sin valor por
 * defecto. Esta prueba fija el mapeo desde el `tipo` que manda el servidor —que
 * es donde queda el único punto de decisión.
 */
class ClaseDeChatTest {

    @Test
    fun `los tres tipos del servidor se mapean`() {
        assertEquals(ClaseDeChat.DIRECTA, claseDeTipo("directa"))
        assertEquals(ClaseDeChat.GRUPO, claseDeTipo("grupo"))
        assertEquals(ClaseDeChat.CANAL, claseDeTipo("canal"))
    }

    /**
     * El caso que importa: un canal **no** es un grupo.
     *
     * Es el defecto del módulo AE, y lo que lo hacía invisible era que los dos
     * dibujaban un icono: parecía que la pantalla funcionaba.
     */
    @Test
    fun `un canal nunca se mapea a grupo`() {
        assertNotEquals(ClaseDeChat.GRUPO, claseDeTipo("canal"))
    }

    /**
     * Un tipo desconocido cae a directa, y ésa es la caída correcta.
     *
     * Dibujar las iniciales de un nombre es lo menos equivocado que se puede
     * hacer sin saber qué es: un icono de grupo sobre algo que no es un grupo
     * afirma algo falso, y unas iniciales no afirman nada.
     */
    @Test
    fun `un tipo desconocido o vacio cae a directa`() {
        assertEquals(ClaseDeChat.DIRECTA, claseDeTipo(""))
        assertEquals(ClaseDeChat.DIRECTA, claseDeTipo(null))
        assertEquals(ClaseDeChat.DIRECTA, claseDeTipo("comunidad"))
    }

    /**
     * Y el mapeo es sensible a mayúsculas a propósito.
     *
     * El `tipo` sale de una columna con un CHECK en la base: los valores son
     * exactamente `directa`, `grupo` y `canal`. Aceptar `"Grupo"` sería
     * aceptar un valor que el servidor no puede producir, y esconder un error
     * de escritura en vez de dejarlo caer a directa donde se nota.
     */
    @Test
    fun `no acepta variantes que el servidor no produce`() {
        assertEquals(ClaseDeChat.DIRECTA, claseDeTipo("Grupo"))
        assertEquals(ClaseDeChat.DIRECTA, claseDeTipo("CANAL"))
    }
}
