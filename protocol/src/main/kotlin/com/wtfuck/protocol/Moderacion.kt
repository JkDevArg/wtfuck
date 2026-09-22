package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo G: moderacion.
 *
 * ## Lo que hay que entender antes de usar esto
 *
 * **El servidor no puede moderar lo que no puede leer.** Con cifrado de
 * extremo a extremo de verdad, una denuncia sobre un mensaje le llega al
 * moderador como un identificador y unos bytes opacos. No hay nada que
 * revisar, y decidir creyendole al que denuncio primero no es moderar.
 *
 * La unica salida posible es que **el texto lo entregue quien denuncia**. Su
 * telefono ya lo descifro —es el unico que puede—, y al denunciar renuncia a
 * la confidencialidad de *esos* mensajes. Es lo mismo que hace WhatsApp.
 *
 * Por eso [DenunciaReq] lleva [DenunciaReq.evidencia] con texto en claro, y
 * por eso la pantalla tiene que decirlo **antes** de confirmar. Un usuario que
 * denuncia sin saber que entrega el texto fue enganado, aunque el texto haga
 * falta.
 *
 * ## Dos ambitos de permiso, no uno
 *
 * Hasta el modulo F todos los permisos eran por conversacion. Una denuncia
 * sobre una *persona* no pertenece a ninguna conversacion, asi que no hay a
 * quien pedirle permiso. De ahi el nivel de **plataforma**: 50 moderador,
 * 80 administrador, 100 propietario. Ser staff no da nada dentro de un grupo,
 * y ser dueno de un grupo no hace staff.
 */

const val RUTA_MODERACION = "/v1/moderacion"
const val RUTA_PANEL = "/v1/panel"

// ============================================================
//  Denunciar
// ============================================================

/** Los motivos son lista cerrada: un campo libre no se puede ni ordenar ni medir. */
object MotivoDenuncia {
    const val SPAM = "spam"
    const val ACOSO = "acoso"
    const val ODIO = "discurso_de_odio"
    const val SEXUAL = "contenido_sexual"
    const val VIOLENCIA = "violencia"
    const val SUPLANTACION = "suplantacion"
    const val ESTAFA = "estafa"
    const val OTRO = "otro"

    val TODOS = listOf(SPAM, ACOSO, ODIO, SEXUAL, VIOLENCIA, SUPLANTACION, ESTAFA, OTRO)

    /** Como se lee en pantalla. */
    fun etiqueta(m: String) = when (m) {
        SPAM -> "Spam o publicidad"
        ACOSO -> "Acoso o amenazas"
        ODIO -> "Discurso de odio"
        SEXUAL -> "Contenido sexual"
        VIOLENCIA -> "Violencia"
        SUPLANTACION -> "Se hace pasar por otra persona"
        ESTAFA -> "Estafa"
        else -> "Otro motivo"
    }
}

object TipoDenuncia {
    const val USUARIO = "usuario"
    const val MENSAJE = "mensaje"
    const val GRUPO = "grupo"
    const val CANAL = "canal"
}

/**
 * Un mensaje que el denunciante entrega como prueba, ya descifrado por el.
 *
 * El servidor no lo obtuvo: lo recibio. La diferencia no es de matiz, es la
 * diferencia entre una excepcion declarada y una puerta trasera.
 */
@Serializable
data class Evidencia(
    val autor: String,
    val enviadoEn: Long,
    val contenido: String,
)

@Serializable
data class DenunciaReq(
    val tipo: String,
    /** Username, no id: el cliente no conoce los uuid de otras personas. */
    val objetivoUsuario: String? = null,
    val objetivoConversacion: String? = null,
    val objetivoMensaje: String? = null,

    val motivo: String,
    val detalle: String = "",

    /**
     * Contexto en claro, puesto aqui por el denunciante.
     *
     * Se recorta a [MAX_EVIDENCIA] en el servidor. El limite no es tecnico: una
     * denuncia con la conversacion entera deja de ser una prueba y se vuelve
     * una filtracion, y un moderador con mil mensajes delante no lee ninguno.
     */
    val evidencia: List<Evidencia> = emptyList(),
) {
    companion object {
        const val MAX_EVIDENCIA = 20
    }
}

@Serializable
data class DenunciaCreada(val id: String, val estado: String)

// ============================================================
//  La cola de revision
// ============================================================

@Serializable
data class DenunciaEnCola(
    val id: String,
    val tipo: String,
    val motivo: String,
    val detalle: String = "",
    val estado: String,

    val denunciante: String,
    val objetivoUsuario: String? = null,
    val objetivoConversacion: String? = null,
    val objetivoTitulo: String? = null,
    val objetivoMensaje: String? = null,

    val creadaEn: Long,
    val revisor: String? = null,

    /**
     * Cuantas denuncias vivas acumula el denunciado.
     *
     * Va en la cola y no escondido en el detalle porque es lo que separa un
     * caso de un patron, y un moderador que no lo ve en la lista trata las
     * diez denuncias de la misma persona como diez incidentes sueltos.
     */
    val denunciasDelObjetivo: Int = 0,
    val advertenciasDelObjetivo: Int = 0,
    val evidencias: Int = 0,
)

@Serializable
data class ColaModeracion(val denuncias: List<DenunciaEnCola> = emptyList())

@Serializable
data class DenunciaDetalle(
    val cabecera: DenunciaEnCola,
    val evidencia: List<Evidencia> = emptyList(),
)

// ============================================================
//  Resolver
// ============================================================

/**
 * Que hace el moderador al cerrar una denuncia.
 *
 * `DESCARTAR` existe y no es lo mismo que "resolver sin accion": descartar
 * dice que la denuncia no tenia fundamento, y eso cuenta a favor del
 * denunciado si manana lo vuelven a denunciar.
 */
object AccionModeracion {
    const val DESCARTAR = "descartar"
    const val ADVERTIR = "advertir"
    const val SILENCIAR = "silenciar"
    const val EXPULSAR = "expulsar"
    const val SUSPENDER = "suspender"
    const val SIN_ACCION = "sin_accion"

    val TODAS = listOf(DESCARTAR, ADVERTIR, SILENCIAR, EXPULSAR, SUSPENDER, SIN_ACCION)
}

@Serializable
data class ResolverReq(
    val accion: String,
    val nota: String = "",
    /**
     * Cuanto dura la sancion. Null = permanente.
     *
     * Se manda en horas y no una fecha para que el cliente no tenga que
     * acertarle al reloj del servidor.
     */
    val horas: Int? = null,
)

@Serializable
data class ResolucionHecha(
    val denunciaId: String,
    val accion: String,
    /** Advertencias vivas del sancionado despues de aplicar esto. */
    val advertenciasVigentes: Int = 0,
    /** Si la acumulacion disparo una sancion automatica, cual fue. */
    val escaladaAutomatica: String? = null,
)

// ============================================================
//  Advertencias
// ============================================================

@Serializable
data class MiAdvertencia(
    val id: String,
    val motivo: String,
    val detalle: String = "",
    val creadaEn: Long,
    val venceEn: Long? = null,
    val reconocida: Boolean = false,
)

@Serializable
data class MiEstadoModeracion(
    val advertenciasVigentes: Int = 0,
    val advertencias: List<MiAdvertencia> = emptyList(),
    val suspendido: Boolean = false,
    val suspendidoHasta: Long? = null,
    val suspensionMotivo: String? = null,
    /** Cuantas advertencias vivas hacen falta para la suspension automatica. */
    val topeAdvertencias: Int = 0,
)

// ============================================================
//  Panel (modulo H)
// ============================================================

@Serializable
data class ResumenPanel(
    val denunciasPendientes: Int = 0,
    val denunciasEnRevision: Int = 0,
    val denunciasResueltasHoy: Int = 0,
    val usuariosSuspendidos: Int = 0,
    val advertenciasVigentes: Int = 0,
    val limitesExcedidosHoy: Int = 0,

    /**
     * F.7: canales esperando la decision del dueno.
     *
     * Va en el resumen porque una cola que no se ve es una cola que no se
     * atiende, y un canal sin aprobar no le sirve a nadie: ni se lista, ni se
     * puede seguir.
     */
    val canalesPendientes: Int = 0,

    val porMotivo: Map<String, Int> = emptyMap(),

    // ------------------------------------------------------------
    //  Metricas de plataforma (§10 del brief)
    // ------------------------------------------------------------
    //
    // Las de arriba responden "¿que tengo que atender hoy?"; estas responden
    // "¿de que tamano es esto?". Van en el mismo resumen y no en una ruta
    // aparte porque se miran en la misma pantalla y de la misma consulta: dos
    // viajes para un tablero que se refresca a mano es latencia sin motivo.
    //
    // Lo que NO esta y no va a estar: uso de CPU, memoria y disco de los
    // servidores. El brief lo pide, pero este servidor no tiene telemetria de
    // maquina, y rellenar esos tres numeros con algo plausible convertiria el
    // panel en un sitio donde no se puede confiar en ninguno de los otros.
    // Un hueco que se explica vale mas que un dato que se inventa.

    /** Cuentas creadas, incluidas las desactivadas: es el total historico. */
    val usuariosRegistrados: Int = 0,

    /**
     * Personas con al menos una sesion usada en los ultimos 7 dias.
     *
     * Personas, no sesiones: quien tiene el telefono y el portatil abiertos es
     * un usuario activo, no dos.
     */
    val usuariosActivos7d: Int = 0,

    /**
     * Filas de `mensaje_meta`, que **no** es "mensajes leidos".
     *
     * El servidor no guarda el contenido -son bytes opacos que ni el remitente
     * le confia-, asi que esto cuenta sobres, no cartas. Se dice aqui porque
     * "mensajes enviados" en un panel de administracion suena a que alguien los
     * puede abrir, y no hay ninguna version de este panel que pueda.
     */
    val mensajesEnviados: Long = 0,

    /**
     * Si [mensajesEnviados] viene de la estimacion del planificador y no de un
     * conteo real. La interfaz tiene que decirlo: un numero aproximado que se
     * presenta como exacto es peor que no tenerlo.
     */
    val mensajesAproximados: Boolean = false,

    val gruposCreados: Int = 0,
    val canalesCreados: Int = 0,

    /** Bytes del archivo **cifrado**, que es lo que de verdad ocupa el almacen. */
    val almacenamientoBytes: Long = 0,
    val almacenamientoArchivos: Int = 0,

    /** Nivel de plataforma de quien pregunta. La interfaz decide con esto. */
    val miNivel: Int = 0,
)

@Serializable
data class UsuarioPanel(
    val username: String,
    val creadoEn: Long,
    val staffNivel: Int = 0,
    val suspendido: Boolean = false,
    val suspendidoHasta: Long? = null,
    val suspensionMotivo: String? = null,
    val advertenciasVigentes: Int = 0,
    val denunciasRecibidas: Int = 0,
    val denunciasHechas: Int = 0,
)

@Serializable
data class UsuariosPanel(val usuarios: List<UsuarioPanel> = emptyList())

@Serializable
data class SuspenderReq(
    val motivo: String,
    val horas: Int? = null,
)

@Serializable
data class StaffReq(val nivel: Int)

// ============================================================
//  Eventos de seguridad
// ============================================================

/**
 * Lo que le paso a MI cuenta.
 *
 * Es distinto de la auditoria a proposito: la auditoria responde "¿quien
 * expulso a Ana?" y la consulta un administrador; esto responde "¿desde donde
 * entraron a mi cuenta?" y lo consulta el dueno. Si estuvieran juntos, para
 * ver tus propios accesos habria que dejarte leer filas de moderacion.
 */
@Serializable
data class EventoSeguridad(
    val tipo: String,
    val ip: String? = null,
    val agente: String? = null,
    val detalle: String? = null,
    val cuando: Long,
)

@Serializable
data class EventosSeguridadResp(val eventos: List<EventoSeguridad> = emptyList())

// ============================================================
//  H.6 · Limites ajustables y bitacora
// ============================================================

/**
 * Un limite de abuso, con su valor efectivo y su valor de fabrica.
 *
 * Los dos viajan juntos a proposito: sin el de fabrica, quien mira la pantalla
 * no puede saber si el numero que ve es el probado o el que alguien cambio un
 * martes, y "volver al valor por defecto" seria un boton a ciegas.
 */
@Serializable
data class LimiteAjustable(
    val clave: String,
    val etiqueta: String,
    val detalle: String = "",
    val tope: Int,
    val ventanaSegundos: Int,
    val topeDefecto: Int,
    val ventanaDefectoSegundos: Int,
    /** Si el valor vigente es el de fabrica. */
    val esDefecto: Boolean = true,
    val actualizadoPor: String? = null,
    val actualizadoEn: Long = 0,
)

@Serializable
data class LimitesPanel(val limites: List<LimiteAjustable> = emptyList())

@Serializable
data class AjustarLimiteReq(val tope: Int, val ventanaSegundos: Int)

/**
 * Una linea de la bitacora.
 *
 * Es la respuesta a "quien hizo esto". El detalle va como texto y no
 * interpretado: la bitacora tiene que poder leerse aunque el formato de un
 * detalle viejo ya no exista en el codigo.
 */
@Serializable
data class LineaBitacora(
    val id: String,
    val actor: String = "",
    val accion: String,
    val recursoTipo: String = "",
    val recursoId: String? = null,
    val objetivo: String? = null,
    val detalle: String? = null,
    val creadoEn: Long,
)

@Serializable
data class Bitacora(val lineas: List<LineaBitacora> = emptyList())

/**
 * Un grupo o canal visto desde el panel de la plataforma.
 *
 * NO trae ni un mensaje. El panel no puede leer contenido -no lo tiene- y esta
 * vista no es la excepcion: son metadatos que el servidor ya conoce porque los
 * necesita para autorizar.
 */
@Serializable
data class ConversacionPanel(
    val id: String,
    val tipo: String,
    val nombre: String,
    val creador: String = "",
    val miembros: Int = 0,
    val mensajes: Int = 0,
    val denuncias: Int = 0,
    val creadoEn: Long = 0,
    val cerrada: Boolean = false,
    val cierreMotivo: String? = null,
    val cerradaPor: String? = null,
)

@Serializable
data class ConversacionesPanel(val conversaciones: List<ConversacionPanel> = emptyList())

@Serializable
data class CerrarConversacionReq(val motivo: String)

// ============================================================
//  L.8 · La consola web
// ============================================================

/**
 * El token que se pega en el navegador.
 *
 * Se devuelve UNA sola vez, como un codigo de vinculacion: el servidor guarda
 * el hash y no puede volver a mostrarlo. Si se pierde, se emite otro.
 */
@Serializable
data class TokenConsola(
    val id: String,
    val token: String,
    val expiraEnSegundos: Long,
)

@Serializable
data class EmitirConsolaReq(val password: String, val etiqueta: String? = null)

@Serializable
data class ConsolaAbierta(
    val id: String,
    val etiqueta: String = "",
    val emitidaDesde: String = "",
    val creadaEn: Long = 0,
    val expiraEn: Long = 0,
    val ultimoUso: Long = 0,
)

@Serializable
data class ConsolasAbiertas(val consolas: List<ConsolaAbierta> = emptyList())
