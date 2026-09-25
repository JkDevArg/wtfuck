package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo K: llamadas de audio y video.
 *
 * ## La señalizacion va CIFRADA, y ahi esta todo el modulo
 *
 * Una llamada WebRTC se negocia intercambiando SDP: codecs, direcciones, y
 * -lo que importa- la **huella del certificado DTLS** de cada lado. El medio
 * va cifrado con DTLS-SRTP de fabrica, asi que nadie escucha el audio en el
 * camino.
 *
 * Eso solo vale si las huellas son autenticas. Si la señalizacion pasara en
 * claro por el servidor, el servidor podria reemplazar las huellas por las
 * suyas, montar dos llamadas -una con cada lado- y escuchar todo. El cifrado
 * del medio no lo impediria: cada tramo estaria perfectamente cifrado *contra
 * el servidor*, que es justo quien esta escuchando.
 *
 * Por eso el SDP y los candidatos ICE viajan **dentro de un sobre cifrado**,
 * con las mismas sesiones de Signal que un mensaje: [Carga.LlamadaOferta],
 * [Carga.LlamadaRespuesta], [Carga.LlamadaCandidato], [Carga.LlamadaFin]. El
 * servidor mueve bytes opacos y no puede cambiar una huella que no puede leer.
 *
 * Lo que el servidor SI ve, porque lo necesita, son metadatos: quien llama a
 * quien, cuando, y como termino. Eso es el historial y los limites de abuso.
 *
 * ## Por que una llamada vive en una conversacion
 *
 * Una llamada 1:1 vive en la directa y una de grupo en el grupo. Asi los
 * permisos, los bloqueos y las restricciones son **los mismos que para
 * escribir**: quien no puede mandarte un mensaje no puede hacerte sonar el
 * telefono, y eso sale gratis en vez de necesitar un sistema de permisos
 * paralelo.
 */

const val RUTA_LLAMADAS = "/v1/llamadas"

/**
 * Tope de personas en una llamada.
 *
 * Cuatro. Sin servidor de medios la llamada es en MALLA: cada uno se conecta
 * con cada otro, asi que cada telefono sube su propio video N-1 veces. Con
 * cinco, cada uno sube cuatro copias, y con datos moviles eso no se sostiene.
 * Subirlo exige un SFU, que es infraestructura aparte.
 *
 * Vive en el protocolo y no solo en el servidor porque **la app tambien lo
 * necesita**: es el numero que limita cuantos invitados deja elegir. Con una
 * copia en cada lado, el dia que cambie uno la pantalla dejaria elegir a cinco
 * para que el servidor los rechace despues.
 */
const val MAX_EN_LLAMADA = 4

// ============================================================
//  Empezar y terminar
// ============================================================

@Serializable
data class IniciarLlamadaReq(
    val conversacionId: String,
    val conVideo: Boolean = false,
    /**
     * A quienes hacer sonar, por `usuarioId`. Vacio = a todos.
     *
     * ## Por que hace falta elegir
     *
     * Una llamada en malla admite cuatro. Sin esta lista, el servidor llamaba
     * a **todos** los de la conversacion y rechazaba la llamada entera si eran
     * mas: un grupo de ocho no podia hacer una llamada **nunca**, ni entre
     * tres de sus miembros. La app ni siquiera mostraba el boton.
     *
     * Vacia sigue significando "a todos", que es lo correcto en una directa y
     * mantiene el comportamiento anterior en los grupos pequenios.
     *
     * ## El servidor no se fia de esta lista
     *
     * Llega del cliente, asi que es una peticion, no una orden: el servidor la
     * cruza con quien esta de verdad en la conversacion. Sin eso, poner un id
     * cualquiera aqui seria hacer sonar el telefono de un desconocido desde un
     * grupo al que no pertenece.
     */
    val invitados: List<String> = emptyList(),
)

@Serializable
data class LlamadaCreada(
    val llamadaId: String,
    /**
     * A quienes hay que mandarles la oferta, por dispositivo.
     *
     * Viene resuelta por el servidor por la misma razon que en los mensajes:
     * solo el servidor sabe quien esta en la conversacion AHORA y con que
     * aparatos. Con multi-dispositivo son varios por persona, y a cada uno hay
     * que cifrarle su propia copia de la oferta.
     */
    val destinos: List<DestinoDispositivo> = emptyList(),
    val turn: ConfigTurn,
)

object FinLlamada {
    /** La colgo alguien que estaba dentro. */
    const val COLGADA = "colgada"
    /** El que recibia dijo que no. */
    const val RECHAZADA = "rechazada"
    /** Nadie contesto y se agoto el tiempo. */
    const val SIN_RESPUESTA = "sin_respuesta"
    /** El que recibia ya estaba en otra llamada. */
    const val OCUPADO = "ocupado"
    /** El que llamaba se arrepintio antes de que contestaran. */
    const val CANCELADA = "cancelada"
    const val FALLO_RED = "fallo_red"

    val TODOS = listOf(COLGADA, RECHAZADA, SIN_RESPUESTA, OCUPADO, CANCELADA, FALLO_RED)
}

@Serializable
data class TerminarLlamadaReq(val motivo: String = FinLlamada.COLGADA)

// ============================================================
//  K.4 · TURN
// ============================================================

/**
 * Credenciales para el servidor TURN.
 *
 * ## Por que hace falta un TURN, en una linea
 *
 * Dos telefonos detras de NAT no se ven. ICE prueba primero la conexion
 * directa (host y STUN); cuando el NAT no lo permite -y con NAT simetrico,
 * que es lo normal en redes moviles, no lo permite- hace falta un relevo. Eso
 * es TURN: un servidor que reenvia los paquetes.
 *
 * **El relevo NO rompe el cifrado.** TURN mueve paquetes DTLS-SRTP que no puede
 * abrir: ve que hay trafico y cuanto, no que se dice. Un TURN comprometido
 * aprende metadatos, no contenido.
 *
 * ## Por que las credenciales son temporales
 *
 * Un TURN abierto es ancho de banda gratis para cualquiera que encuentre la
 * URL, y el ancho de banda de un relevo se paga. Se usa el esquema REST de
 * coturn: el usuario es `<vencimiento>:<algo>` y la clave es
 * `HMAC-SHA1(secreto, usuario)` en base64. El secreto vive solo en el servidor
 * y en el TURN; el cliente recibe algo que sirve unas horas y nada mas.
 */
@Serializable
data class ConfigTurn(
    /** `turn:host:puerto?transport=udp`, y la variante TCP si la hay. */
    val urls: List<String> = emptyList(),
    val usuario: String = "",
    val clave: String = "",
    val expiraEnSegundos: Int = 0,
    /**
     * Si esta vacio, no hay TURN configurado y la llamada solo funcionara
     * cuando ICE logre una conexion directa.
     *
     * Va como campo y no como una excepcion porque una llamada sin TURN no es
     * un error: en la misma red funciona perfectamente. Lo que no se puede es
     * *pretender* que funcionara siempre.
     */
    val hay: Boolean = false,
)

// ============================================================
//  Historial
// ============================================================

@Serializable
data class LlamadaEnHistorial(
    val id: String,
    val conversacionId: String,
    val titulo: String = "",
    val conVideo: Boolean = false,
    /** Si la inicie yo. Es lo que separa "llamada saliente" de "entrante". */
    val fueMia: Boolean = false,
    val iniciadaEn: Long,
    /** Segundos hablados. 0 si no se contesto. */
    val duracion: Int = 0,
    val estado: String = "",
    val finMotivo: String? = null,
    /**
     * Si no se contesto Y no era mia. Es el unico caso que la interfaz pinta
     * distinto, porque es el unico que reclama una accion: devolver la llamada.
     */
    val perdida: Boolean = false,
    val participantes: List<String> = emptyList(),
)

@Serializable
data class HistorialLlamadas(val llamadas: List<LlamadaEnHistorial> = emptyList())

@Serializable
data class LlamadaEnCurso(
    val llamadaId: String,
    val conversacionId: String,
    val conVideo: Boolean = false,
    val origen: String = "",
    val estado: String = "",
    val turn: ConfigTurn = ConfigTurn(),
    val destinos: List<DestinoDispositivo> = emptyList(),
    /**
     * Mi estado en esta llamada: `sonando` o `dentro`.
     *
     * ## Por que hace falta, y no basta con `estado`
     *
     * `estado` es el de la LLAMADA; este es el mio, y la diferencia decide que
     * hacer al arrancar la app.
     *
     * Al arrancar hay que cerrar la llamada en la que el proceso murio: las
     * sesiones WebRTC se fueron con el y no se retoman. Pero sin este campo la
     * app no distinguia eso de **una llamada que me esta sonando ahora**, y la
     * colgaba: bastaba que el telefono no tuviera la app abierta —o sea, el
     * caso normal cuando llega un aviso— para que abrirla matara la llamada
     * entrante antes de que sonara.
     *
     * Con `sonando` no se toca nada: la oferta cifrada sigue en el buzon y
     * hace sonar el telefono al llegar.
     */
    val miEstado: String = "",
    /**
     * En que anda cada persona de la llamada: username -> `sonando` |
     * `dentro` | `rechazo`.
     *
     * ## Por que no basta con los avisos
     *
     * `llamada_participante` cuenta los CAMBIOS, y quien se une a una llamada
     * que ya empezo se perdio los anteriores: alguien que entro antes no va a
     * emitir un aviso nuevo para el recien llegado, asi que sin esto se le
     * mostraria como "sonando" para siempre.
     *
     * Esto es la foto del momento de entrar; los avisos la van moviendo.
     */
    val estados: Map<String, String> = emptyMap(),
)
