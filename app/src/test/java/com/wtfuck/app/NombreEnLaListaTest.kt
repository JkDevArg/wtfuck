package com.wtfuck.app

import com.wtfuck.app.datos.ChatFila
import org.junit.Assert.*
import org.junit.Test

/**
 * Qué nombre se ve en la lista de chats.
 *
 * ## La regla, y la decisión que hay detrás
 *
 * 1. **Mi alias de contacto**, si la tengo agendada.
 * 2. Si no, el **username**.
 * 3. Para un grupo o un canal, su nombre propio.
 *
 * Lo que ya **no** se usa en una directa es `nombreMostrado`, el nombre que la
 * otra persona se puso a sí misma. Y eso no es un detalle de presentación: es
 * un dato controlado por quien podría querer hacerse pasar por alguien. Con él
 * de título, cualquiera se llama "Tatiana" y en la lista se ve igual que la
 * Tatiana de verdad.
 *
 * El alias sí puede ir solo, sin el username al lado, porque **lo escribí yo**.
 * Es la misma lógica por la que una agenda de teléfono muestra nombres.
 */
class NombreEnLaListaTest {

    private fun chat(
        tipo: String = "directa",
        nombre: String = "tatiana",
        nombreMostrado: String = "",
        aliasContacto: String = "",
        aliasAutor: String = "",
        ultimoAutor: String? = null,
    ) = ChatFila(
        id = "c1",
        tipo = tipo,
        nombre = nombre,
        nombreMostrado = nombreMostrado,
        participantes = "",
        avatarUsername = nombre,
        avatarVersion = 0L,
        noLeidos = 0,
        ultimoTexto = null,
        ultimaFecha = null,
        ultimoEsMio = null,
        ultimoAutor = ultimoAutor,
        ultimoEstado = null,
        ultimoAdjuntoClase = null,
        ultimoAdjuntoNombre = null,
        miRol = "miembro",
        miJerarquia = 10,
        silenciadoHasta = 0L,
        archivado = false,
        fijado = false,
        marcadaNoLeida = false,
        soyMiembro = true,
        aliasContacto = aliasContacto,
        aliasAutor = aliasAutor,
    )

    // ============================================================
    //  Una conversación directa
    // ============================================================

    @Test
    fun `sin agendar se ve el username`() {
        assertEquals("tatiana", chat().titulo)
    }

    @Test
    fun `agendada se ve el nombre que le puse yo`() {
        assertEquals("Tati del trabajo", chat(aliasContacto = "Tati del trabajo").titulo)
    }

    @Test
    fun `el nombre que la otra persona se puso NO manda`() {
        // La decisión de seguridad. Alguien que se llama a sí mismo "Tatiana"
        // no puede aparecer en mi lista como Tatiana: en una lista de chats,
        // el nombre es lo único que se lee, y ahí un nombre elegido por el
        // otro lado es una herramienta de suplantación.
        assertEquals("impostor99", chat(nombre = "impostor99", nombreMostrado = "Tatiana").titulo)
    }

    @Test
    fun `mi alias gana incluso si la persona se puso otro nombre`() {
        assertEquals(
            "Tati",
            chat(nombreMostrado = "Otra Cosa", aliasContacto = "Tati").titulo,
        )
    }

    @Test
    fun `un alias en blanco no tapa el username`() {
        // Se puede agendar a alguien sin ponerle nombre: entonces la fila cae
        // al username y no a un título vacío.
        assertEquals("tatiana", chat(aliasContacto = "").titulo)
    }

    // ============================================================
    //  Grupos y canales
    // ============================================================

    @Test
    fun `un grupo se llama por su nombre propio`() {
        // Aquí `nombreMostrado` sí manda: el nombre de un grupo no es la
        // declaración de una persona sobre sí misma, es el nombre del grupo.
        assertEquals(
            "Equipo seguridad",
            chat(tipo = "grupo", nombre = "g1", nombreMostrado = "Equipo seguridad").titulo,
        )
    }

    @Test
    fun `un canal tambien`() {
        assertEquals(
            "Avisos",
            chat(tipo = "canal", nombre = "c1", nombreMostrado = "Avisos").titulo,
        )
    }

    @Test
    fun `un grupo sin nombre cae a su identificador y no a un hueco`() {
        assertEquals("g1", chat(tipo = "grupo", nombre = "g1").titulo)
    }
}
