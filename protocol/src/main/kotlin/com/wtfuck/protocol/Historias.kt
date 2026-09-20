package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo O · Historias.
 *
 * Lo que en WhatsApp son los "estados" y en Telegram las "historias":
 * contenido que se publica a una audiencia y **caduca a las 24 horas**.
 *
 * ## Por que "historias" y no "estados"
 *
 * `estado` ya esta ocupado dos veces en este proyecto: `estadoTexto` es la
 * frase del perfil y `Privacidad.estado` dice quien puede verla. Un tercer
 * significado daria `priv_estado` junto a `priv_estados`, que es la clase de
 * par que alguien confunde cuando esta cansado.
 *
 * ## Como viaja: igual que un mensaje de grupo
 *
 * Una historia va **cifrada de extremo a extremo**. No hay un mecanismo nuevo:
 * el cliente pide a quien le toca verla, cifra una vez por dispositivo de
 * destino con las sesiones de Signal que ya existen, y manda los sobres por el
 * buzon de siempre.
 *
 * Eso trae una consecuencia que conviene decir en voz alta: **la audiencia se
 * congela al publicar**. Quien abra una conversacion conmigo manana no vera lo
 * que publique hoy, porque no habia con que cifrarselo. No es una limitacion
 * que se arregla despues: es lo que significa cifrar contra destinatarios
 * concretos, y es tambien lo que hace WhatsApp.
 *
 * ## Lo que el servidor sabe
 *
 * Quien publico, cuando, de que clase dijo que era, a quien iba dirigida y
 * quien la vio. **No el contenido.** Es el mismo trato que un mensaje: el
 * buzon necesita saber a donde llevar el sobre, no que dice.
 */

const val RUTA_HISTORIAS = "/v1/historias"

/** Lo que una historia puede ser. */
object ClaseHistoria {
    const val TEXTO = "texto"
    const val IMAGEN = "imagen"
    const val VIDEO = "video"

    val TODAS = listOf(TEXTO, IMAGEN, VIDEO)
}

/**
 * Cuanto vive una historia.
 *
 * Veinticuatro horas, como en todas partes. El numero no es arbitrario ni
 * tecnico: es lo que hace que publicar algo sea barato. Si durara para siempre
 * seria un perfil, y la gente escribe distinto cuando sabe que queda.
 */
const val HORAS_DE_VIDA_HISTORIA = 24

/**
 * Publicar. El contenido NO va aqui: va en los sobres, cifrado.
 *
 * Se manda el id que genero el cliente —igual que con los mensajes— para que
 * los sobres puedan apuntar a una historia que todavia no existe en el
 * servidor, y para que reintentar no publique dos veces.
 */
@Serializable
data class PublicarHistoriaReq(
    val historiaId: String,
    val clase: String = ClaseHistoria.TEXTO,
)

/**
 * Una historia, como la ve quien la recibe.
 *
 * Sin contenido: el contenido llego por el buzon y el cliente lo tiene
 * guardado. Esto es lo que permite ordenar, agrupar por autor y saber que
 * caduco.
 */
@Serializable
data class HistoriaInfo(
    val historiaId: String,
    val autorUsername: String,
    val clase: String,
    val creadaEn: Long,
    val expiraEn: Long,
    /** Si YO ya la vi. Para el anillo de "sin ver" de la lista. */
    val vista: Boolean = false,
)

/**
 * Una historia mia, con su cuenta de vistas.
 *
 * `vistas` es `null` —y no 0— cuando no se puede saber: quien apago las
 * confirmaciones de lectura no registra las suyas y tampoco ve las ajenas.
 * Devolver 0 seria decir "nadie la vio", que es una afirmacion distinta y
 * falsa.
 */
@Serializable
data class HistoriaMia(
    val historiaId: String,
    val clase: String,
    val creadaEn: Long,
    val expiraEn: Long,
    val destinatarios: Int,
    val vistas: Int? = null,
)

@Serializable
data class HistoriasParaMi(val historias: List<HistoriaInfo> = emptyList())

@Serializable
data class MisHistorias(val historias: List<HistoriaMia> = emptyList())

/** Quien vio una historia mia. */
@Serializable
data class VistaDeHistoria(
    val username: String,
    val nombreMostrado: String = "",
    val vistaEn: Long,
)

@Serializable
data class VistasDeHistoria(
    val vistas: List<VistaDeHistoria> = emptyList(),
    /**
     * Por que la lista puede venir vacia sin que eso signifique "nadie la vio".
     *
     * Vale `sin_lectura` cuando quien pregunta tiene apagadas las
     * confirmaciones: ahi no hay lista que dar, y decirlo es mejor que
     * devolver un vacio que se lee como ausencia de interes.
     */
    val motivo: String = "",
)

object MotivoSinVistas {
    const val SIN_LECTURA = "sin_lectura"
}

/**
 * Los destinos de una historia, para poder cifrarla.
 *
 * El cliente pregunta **antes** de publicar: necesita saber a que dispositivos
 * les toca para armar un sobre por cada uno. Es la misma forma que
 * `/v1/conversaciones/{id}/destinos`, y a proposito: una historia se cifra como
 * un mensaje de grupo porque es un mensaje de grupo con otra interfaz.
 */
@Serializable
data class DestinosHistoria(
    val destinos: List<DestinoDispositivo> = emptyList(),
)

/**
 * Los sobres de una historia.
 *
 * Van por una ruta propia y no por la de mensajes porque una historia **no
 * pertenece a ninguna conversacion**: se publica a una audiencia que puede
 * incluir a quien te tiene agendado y nunca te escribio. Meterlos por
 * `/v1/mensajes` obligaria a inventar una conversacion con cada uno, o sea a
 * crear chats vacios en la pantalla de otra persona para que el buzon tenga
 * donde apoyarse.
 *
 * El servidor comprueba que cada destino este en la audiencia congelada antes
 * de encolar. Sin eso, esta ruta seria una forma de mandarle un sobre a
 * cualquiera saltandose las conversaciones y los bloqueos.
 */
@Serializable
data class SobresHistoriaReq(
    val copias: List<CopiaCifrada> = emptyList(),
    /**
     * Si estas son **todas** las copias de la historia.
     *
     * Con `true` —lo normal, y lo que hace el cliente hoy— el servidor cierra
     * el reparto: quien quedo sin ninguna copia **deja de tener la historia
     * anunciada**, porque una historia que se ve en la lista y no se puede
     * abrir es peor que una que no aparece.
     *
     * Un cliente que parta el reparto en tandas tiene que mandar `false` en
     * las intermedias. Si no, la primera tanda borraria de la audiencia a todo
     * aquel a quien todavia no le tocaba.
     */
    val ultimoLote: Boolean = true,
)

/**
 * Los dispositivos que el cliente no cubrio con ninguna copia.
 *
 * Igual que con los mensajes: el servidor no inventa un sobre para el que
 * falta, dice cuales faltan y el cliente decide si vuelve a pedir claves y
 * completa. Una historia que le llega a la mitad de la audiencia es un
 * problema; una que dice a quien no le llego, es un aviso.
 */
@Serializable
data class SobresPendientesResp(val sinCopia: List<String> = emptyList())
