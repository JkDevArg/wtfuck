package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo B: grupos avanzados.
 *
 * Contrato compartido entre servidor y app para configuracion, invitaciones,
 * solicitudes de ingreso, roles y preferencias personales.
 */

const val RUTA_BLOQUEOS = "/v1/bloqueos"
const val RUTA_INVITACIONES = "/v1/invitaciones"

/**
 * Alguien a quien bloqueé: `GET /v1/bloqueos`, el más reciente primero.
 *
 * Solo el username, sin foto ni nombre: esos datos los rige la privacidad del
 * otro, y la lista existe para poder desbloquear, no para seguir mirando.
 */
@Serializable
data class Bloqueado(
    val usuarioId: String,
    val username: String,
    /** Cuándo lo bloqueé, epoch ms. */
    val desde: Long,
)

// ============================================================
//  Configuracion del grupo
// ============================================================

@Serializable
data class ConfigGrupo(
    val nombre: String,
    val descripcion: String = "",
    /** Publico: cualquiera con el enlace entra. Privado: solo por invitacion. */
    val publico: Boolean = false,
    /** Modo anuncio: solo quien tenga el permiso por rol escribe. */
    val soloAdmins: Boolean = false,
    val historialVisible: Boolean = true,
    val aprobarIngreso: Boolean = false,
    val permitirMedia: Boolean = true,
    val permitirEnlaces: Boolean = true,
    /** Alias publico sin arroba. Vacio = sin alias. */
    val alias: String = "",
)

// ============================================================
//  Miembros y roles
// ============================================================

@Serializable
data class MiembroDetalle(
    val usuario: UsuarioPublico,
    val rolClave: String,
    val rolNombre: String,
    val jerarquia: Int,
    /** null si no tiene restriccion activa. */
    val restriccion: String? = null,
    val restringidoHasta: Long? = null,
)

@Serializable
data class RolDetalle(
    val id: String,
    val clave: String,
    val nombre: String,
    val jerarquia: Int,
    val esSistema: Boolean,
    val permisos: List<String>,
)

@Serializable
data class CrearRolReq(
    val nombre: String,
    /** Entre 1 y 79: por encima quedan los roles de sistema. */
    val jerarquia: Int,
    val permisos: List<String>,
)

@Serializable
data class CambiarRolReq(val rolClave: String)

@Serializable
data class SilenciarReq(
    /** Minutos. 0 o negativo = indefinido. */
    val minutos: Int = 0,
    val motivo: String = "",
)

@Serializable
data class ExpulsarReq(
    val motivo: String = "",
    /** true = vetado: no puede volver ni con enlace. */
    val vetar: Boolean = false,
)

// ============================================================
//  Invitaciones
// ============================================================

@Serializable
data class CrearInvitacionReq(
    /** Horas de validez. 0 = sin vencimiento. */
    val horas: Int = 0,
    /** 0 = usos ilimitados. */
    val usosMax: Int = 0,
)

@Serializable
data class Invitacion(
    val codigo: String,
    val conversacionId: String,
    val creadaEn: Long,
    val expiraEn: Long?,
    val usosMax: Int?,
    val usos: Int,
    val revocada: Boolean,
)

/** Lo que ve alguien que abre un enlace ANTES de decidir si entra. */
@Serializable
data class VistaPreviaInvitacion(
    val conversacionId: String,
    val nombre: String,
    val descripcion: String,
    val miembros: Int,
    val requiereAprobacion: Boolean,
    val yaEsMiembro: Boolean,
)

@Serializable
data class ResultadoIngreso(
    /** ingresado | solicitud_enviada */
    val estado: String,
    val conversacionId: String,
)

// ============================================================
//  Solicitudes
// ============================================================

@Serializable
data class Solicitud(
    val usuario: UsuarioPublico,
    val mensaje: String,
    val creadaEn: Long,
)

@Serializable
data class ResolverSolicitudReq(val aprobar: Boolean)

// ============================================================
//  Preferencias personales por conversacion
// ============================================================

/**
 * Silenciar, archivar y fijar son de cada persona, no del grupo. Silenciar un
 * grupo no lo silencia para los demas.
 */
@Serializable
data class PreferenciasChat(
    /** Minutos desde ahora. 0 = quitar silencio. -1 = indefinido. */
    val silenciarMinutos: Int? = null,
    val archivado: Boolean? = null,
    val fijado: Boolean? = null,
)

@Serializable
data class EstadoChat(
    val conversacionId: String,
    val silenciadoHasta: Long?,
    val archivado: Boolean,
    val fijado: Boolean,
)

// ============================================================
//  Bloqueos
// ============================================================

@Serializable
data class ListaBloqueados(val usuarios: List<UsuarioPublico>)
