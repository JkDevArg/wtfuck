package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Comunidades (modulo AD).
 *
 * Un conjunto de grupos bajo un nombre, **mas un canal de anuncios**. Lo
 * segundo es lo unico que la hace una comunidad: sin el, agrupar chats es una
 * carpeta, y una carpeta se resuelve en el telefono sin que el servidor se
 * entere.
 *
 * La pertenencia se **deriva**: sos de la comunidad si sos de alguno de sus
 * grupos. No hay lista de miembros aparte, porque dos listas que dicen lo
 * mismo se separan y el dia que se separan nadie sabe cual manda. Ver
 * `V37__comunidades.sql`.
 */

const val RUTA_COMUNIDADES = "/v1/comunidades"

@Serializable
data class CrearComunidadReq(
    val nombre: String,
    val descripcion: String = "",
    /**
     * Grupos con los que nace, opcional.
     *
     * Se aceptan al crear porque una comunidad vacia no le sirve a nadie y
     * obligar a crearla y despues agregar es un paso de mas para el caso
     * normal. Los que no se puedan agregar **no cancelan la creacion**: se
     * informan aparte en [ComunidadDetalle.rechazados].
     */
    val grupos: List<String> = emptyList(),
)

@Serializable
data class ComunidadResumen(
    val id: String,
    val nombre: String,
    val descripcion: String = "",
    /** La conversacion del canal de anuncios, para abrirlo. */
    val anunciosId: String,
    val grupos: Int = 0,
    /** Si puedo administrarla: agregar y quitar grupos, editarla. */
    val soyAdmin: Boolean = false,
)

@Serializable
data class GrupoDeComunidad(
    val conversacionId: String,
    val nombre: String,
    val miembros: Int = 0,
    /** Si soy participante de ESE grupo. Uno puede ver la comunidad y no estar en todos. */
    val estoy: Boolean = false,
)

@Serializable
data class ComunidadDetalle(
    val comunidad: ComunidadResumen,
    val grupos: List<GrupoDeComunidad> = emptyList(),
    /**
     * Grupos que se pidieron agregar y no se pudieron, con el motivo.
     *
     * Se devuelven en vez de fallar entero: quien agrega cinco grupos y tiene
     * permiso en cuatro espera que entren los cuatro y que le digan cual no.
     * Un 403 que descarta los cinco convierte un aviso en un reintento a
     * ciegas.
     */
    val rechazados: List<GrupoRechazado> = emptyList(),
)

@Serializable
data class GrupoRechazado(val conversacionId: String, val motivo: String)

@Serializable
data class ListaComunidades(val comunidades: List<ComunidadResumen> = emptyList())

@Serializable
data class AgregarGruposReq(val grupos: List<String>)

@Serializable
data class EditarComunidadReq(val nombre: String, val descripcion: String = "")
