package com.wtfuck.app

import com.wtfuck.app.datos.ContactoEnt
import com.wtfuck.app.datos.ConversacionEnt
import com.wtfuck.app.datos.candidatosParaLlamada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A quien se ofrece anadir a una llamada.
 *
 * Nace de la prueba con dos emuladores: el selector salio VACIO con dos chats
 * abiertos, porque solo miraba la libreta y en esta app casi nadie agenda.
 */
class CandidatosLlamadaTest {

    private fun directa(u: String, mostrado: String = "", av: String = "", v: Long = 0) =
        ConversacionEnt(
            id = "c-$u", tipo = "directa", nombre = u, participantes = u,
            nombreMostrado = mostrado, avatarUsername = av, avatarVersion = v,
        )

    private fun grupo(nombre: String) =
        ConversacionEnt(id = "g-$nombre", tipo = "grupo", nombre = nombre, participantes = "a,b,c")

    private fun contacto(u: String, alias: String = "") = ContactoEnt(username = u, alias = alias)

    @Test
    fun `sin libreta salen las personas de los chats`() {
        // El caso que fallo de verdad: dos chats, cero contactos.
        val r = candidatosParaLlamada(
            libreta = emptyList(),
            directas = listOf(directa("goblin2026"), directa("probador")),
            excluir = listOf("nadie"),
        )
        assertEquals(listOf("goblin2026", "probador"), r.map { it.username })
    }

    @Test
    fun `quien ya esta en la llamada no sale`() {
        // Anadirla crearia un grupo de dos consigo misma y cortaria la llamada.
        val r = candidatosParaLlamada(
            libreta = listOf(contacto("goblin2026")),
            directas = listOf(directa("goblin2026"), directa("probador")),
            excluir = listOf("goblin2026"),
        )
        assertEquals(listOf("probador"), r.map { it.username })
    }

    @Test
    fun `la exclusion no distingue mayusculas`() {
        val r = candidatosParaLlamada(
            libreta = emptyList(),
            directas = listOf(directa("Goblin2026")),
            excluir = listOf("goblin2026"),
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `uno mismo tampoco sale`() {
        val r = candidatosParaLlamada(
            libreta = listOf(contacto("xampl3")),
            directas = listOf(directa("xampl3"), directa("ana")),
            excluir = listOf("goblin2026", "xampl3"),
        )
        assertEquals(listOf("ana"), r.map { it.username })
    }

    @Test
    fun `alguien en libreta y en chat sale una sola vez, con su alias`() {
        val r = candidatosParaLlamada(
            libreta = listOf(contacto("ana", alias = "Ana del trabajo")),
            directas = listOf(directa("ana", mostrado = "Ana P.")),
            excluir = emptyList(),
        )
        assertEquals(1, r.size)
        // El nombre que YO le puse gana sobre el que ella eligio.
        assertEquals("Ana del trabajo", r[0].nombre)
    }

    @Test
    fun `sin alias se usa el nombre mostrado, y si no el username`() {
        val r = candidatosParaLlamada(
            libreta = emptyList(),
            directas = listOf(directa("ana", mostrado = "Ana P."), directa("beto")),
            excluir = emptyList(),
        )
        assertEquals(listOf("Ana P.", "beto"), r.map { it.nombre })
    }

    @Test
    fun `los chats van primero y en su orden, despues la libreta por nombre`() {
        val r = candidatosParaLlamada(
            libreta = listOf(contacto("zoe"), contacto("carla"), contacto("beto")),
            directas = listOf(directa("beto"), directa("ana")),
            excluir = emptyList(),
        )
        assertEquals(listOf("beto", "ana", "carla", "zoe"), r.map { it.username })
    }

    @Test
    fun `los grupos no son candidatos`() {
        val r = candidatosParaLlamada(
            libreta = emptyList(),
            directas = listOf(grupo("Equipo"), directa("ana")),
            excluir = emptyList(),
        )
        assertEquals(listOf("ana"), r.map { it.username })
    }

    @Test
    fun `la foto del chat viaja con el candidato`() {
        val r = candidatosParaLlamada(
            libreta = emptyList(),
            directas = listOf(directa("ana", av = "ana", v = 42L)),
            excluir = emptyList(),
        )
        assertEquals("ana", r[0].avatarUsername)
        assertEquals(42L, r[0].avatarVersion)
    }

    @Test
    fun `solo de libreta no trae foto conocida`() {
        val r = candidatosParaLlamada(
            libreta = listOf(contacto("ana")),
            directas = emptyList(),
            excluir = emptyList(),
        )
        assertEquals("", r[0].avatarUsername)
    }
}
