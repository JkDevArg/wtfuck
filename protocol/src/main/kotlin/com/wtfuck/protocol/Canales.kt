package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo F: canales.
 *
 * Un canal es una conversacion donde **quien habla y quien escucha son
 * papeles distintos**. Eso es todo lo que lo diferencia de un grupo, y de ahi
 * sale el resto: los suscriptores no publican, el alias sirve para
 * encontrarlo, y tiene estadisticas porque hay una audiencia que medir.
 *
 * ## Lo que hay que saber antes de usarlo
 *
 * Un canal PUBLICO no va cifrado de extremo a extremo, y el servidor guarda
 * sus publicaciones. No es un descuido: un canal publico no tiene secreto que
 * guardar -cualquiera se suscribe y lee-, y sin historial guardado quien se
 * suscribe hoy no veria nada de ayer. Ver la nota larga en `V10__canales.sql`.
 *
 * Un canal PRIVADO si va cifrado, como un grupo. El precio, declarado: no
 * puede mostrar historial a quien se suscribe despues, porque el servidor no
 * lo tiene.
 */

const val RUTA_CANALES = "/v1/canales"

/**
 * F.7 · Un canal existe cuando el dueno de la plataforma lo aprueba.
 *
 * ## Por que
 *
 * Un canal publico es la superficie de mas alcance del sistema: una tribuna
 * abierta a todos, con historial que el servidor guarda en claro. Quien
 * responde por lo que se publica ahi es el dueno de la plataforma, asi que es
 * el dueno quien decide que canales existen.
 *
 * ## Que cambia mientras esta pendiente
 *
 * No aparece en el directorio ni en la busqueda, y nadie se puede suscribir.
 * Quien lo creo SI puede entrar y prepararlo: bloquearlo del todo solo
 * agregaria una espera sin ningun beneficio.
 */
object EstadoCanal {
    const val PENDIENTE = "pendiente"
    const val APROBADO = "aprobado"
    const val RECHAZADO = "rechazado"

    val TODOS = listOf(PENDIENTE, APROBADO, RECHAZADO)
}

@Serializable
data class CrearCanalReq(
    val nombre: String,
    /** El "@algo" publico. Obligatorio si el canal es publico. */
    val alias: String? = null,
    val publico: Boolean = false,
    val descripcion: String = "",
    val comentarios: Boolean = false,
    val reacciones: Boolean = true,
)

@Serializable
data class ConfigCanal(
    val conversacionId: String,
    val nombre: String,
    val alias: String? = null,
    val publico: Boolean = false,
    val descripcion: String = "",
    val comentarios: Boolean = false,
    val reacciones: Boolean = true,

    val suscriptores: Int = 0,
    val publicaciones: Int = 0,

    // --- mi relacion con este canal ---
    val suscrito: Boolean = false,
    val puedoPublicar: Boolean = false,
    val puedoGestionar: Boolean = false,
    val miRol: String = "",

    /**
     * Si el contenido de este canal viaja cifrado de extremo a extremo.
     *
     * Viaja en la respuesta para que la interfaz pueda decirlo sin tener que
     * deducirlo. Una promesa de cifrado que no se cumple es peor que no
     * prometer nada.
     */
    val cifrado: Boolean = true,

    /** F.7: `pendiente`, `aprobado` o `rechazado`. Ver [EstadoCanal]. */
    val estado: String = EstadoCanal.APROBADO,

    /**
     * Por que se rechazo. Solo viene cuando el estado es `rechazado`.
     *
     * Rechazar sin decir por que deja a quien lo creo sin nada que hacer, y
     * eso convierte una decision en un muro. La base lo exige con un CHECK.
     */
    val motivoRechazo: String? = null,
)

@Serializable
data class ConfigCanalReq(
    val nombre: String,
    val alias: String? = null,
    val publico: Boolean = false,
    val descripcion: String = "",
    val comentarios: Boolean = false,
    val reacciones: Boolean = true,
)

/**
 * Publicar en un canal publico.
 *
 * Lleva el cuerpo EN CLARO, y esta es la unica ruta de todo el sistema que lo
 * hace. Que sea una ruta aparte y no un campo mas en `/v1/mensajes` es
 * deliberado: la excepcion tiene que verse en el mapa de rutas, no esconderse
 * dentro de algo que normalmente no guarda contenido.
 */
@Serializable
data class PublicarReq(
    val mensajeId: String,
    val cuerpo: String,
)

@Serializable
data class Publicacion(
    val mensajeId: String,
    val autor: String,
    val cuerpo: String,
    val creadoEn: Long,
    val editado: Boolean = false,
    val fijado: Boolean = false,
    val reacciones: List<ReaccionAgrupada> = emptyList(),
    val comentarios: Int = 0,
)

@Serializable
data class EstadisticasCanal(
    val suscriptores: Int,
    val publicaciones: Int,
    val reacciones: Int,
    val comentarios: Int,
    /** Altas de los ultimos siete dias. Lo unico "de tendencia" que se guarda. */
    val altasSemana: Int,
)

/** Un canal en los resultados de busqueda. Solo publicos. */
@Serializable
data class CanalEnBusqueda(
    val conversacionId: String,
    val alias: String,
    val nombre: String,
    val descripcion: String,
    val suscriptores: Int,
    val suscrito: Boolean = false,
)

@Serializable
data class ResultadoBusquedaCanales(val canales: List<CanalEnBusqueda> = emptyList())

/**
 * El directorio: los canales aprobados, sin buscar nada.
 *
 * Es el cambio de fondo de F.7. Antes un canal se encontraba escribiendo en un
 * buscador, lo que obliga a **adivinar el nombre de algo que no sabes que
 * existe**; ahora hay una lista curada, y la busqueda pasa a ser lo que
 * siempre deberia haber sido: un filtro sobre esa lista para cuando ya es
 * larga. Solo hay lista porque hay aprobacion: un directorio de todo lo que
 * cualquiera creo hace cinco minutos no seria un directorio.
 */
@Serializable
data class DirectorioCanales(val canales: List<CanalEnBusqueda> = emptyList())

/**
 * Un canal en la vista del dueno de la plataforma.
 *
 * Sirve para los tres estados y no solo para la cola: aprobar no puede ser una
 * puerta de un solo sentido. Un canal que se descarrila despues de aprobado
 * tiene que poder retirarse, y para retirarlo hay que poder verlo.
 */
@Serializable
data class CanalPendiente(
    val conversacionId: String,
    val nombre: String,
    val alias: String? = null,
    val publico: Boolean = false,
    val descripcion: String = "",
    val creador: String = "",
    val creadoEn: Long = 0,
    /** Cuantas publicaciones lleva ya. Sirve para revisar con algo delante. */
    val publicaciones: Int = 0,
    val estado: String = EstadoCanal.PENDIENTE,
    val suscriptores: Int = 0,
    val motivoRechazo: String? = null,
)

@Serializable
data class ColaCanales(val canales: List<CanalPendiente> = emptyList())

@Serializable
data class RevisarCanalReq(
    val aprobado: Boolean,
    /** Obligatorio al rechazar. */
    val motivo: String = "",
)
