package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo J: varios dispositivos por cuenta.
 *
 * ## Por que casi no hay contrato nuevo
 *
 * Porque el reparto ya era por dispositivo. Desde V1 la direccion
 * criptografica de una sesion es el **dispositivo**, no la persona, y
 * `DestinosConversacion` siempre devolvio una LISTA de dispositivos de todos
 * los participantes. El cifrado por copia ([CopiaCifrada]) existe justamente
 * porque con E2EE no hay un cuerpo unico.
 *
 * Asi que el fan-out a N dispositivos no hubo que construirlo: hubo que
 * **dejar de prohibirlo**. Lo unico verdaderamente nuevo es como entra un
 * aparato nuevo, y lo que pasa con el historial que no tiene.
 *
 * ## Las dos reglas que parecian una
 *
 * "Un dispositivo por cuenta" y "un hardware por cuenta" se escribieron juntas
 * en V1 y son distintas. La segunda impide duplicar cuentas y se queda; la
 * primera solo existia porque no habia vinculacion, y se fue.
 *
 * ## Lo que un dispositivo nuevo NO puede ver
 *
 * El historial anterior a su vinculacion, salvo que otro dispositivo de la
 * misma persona se lo mande. El servidor nunca lo tuvo. Ver [HistorialPedido].
 */

const val RUTA_DISPOSITIVOS = "/v1/dispositivos"

/**
 * Donde vive la version web: en el MISMO origen que la API, para no abrir
 * CORS. Ver docs/12-VERSION-WEB.md.
 */
const val RUTA_WEB = "/web"

/**
 * Los niveles de hardware que declara un aparato. Ver docs/04-DEVICE-BINDING.md.
 *
 * [NAVEGADOR] es de la version web: no tiene enclave ni atestacion, y por eso
 * tiene tres limites que el servidor hace cumplir, no la interfaz:
 *  - solo entra VINCULANDOSE desde un aparato que ya esta en la cuenta: no
 *    sirve para registrarse ni para recuperar la cuenta;
 *  - nunca es el principal, asi que nunca autoriza a otros;
 *  - su sesion dura [DIAS_SESION_NAVEGADOR] dias y no 90.
 */
object NivelHardware {
    const val STRONGBOX = "STRONGBOX"
    const val TEE = "TEE"
    const val SOFTWARE_DEV = "SOFTWARE_DEV"
    const val NAVEGADOR = "NAVEGADOR"
}

/** Cuanto vive la sesion de un navegador antes de pedir la contrasena otra vez. */
const val DIAS_SESION_NAVEGADOR = 30

// ============================================================
//  Vincular
// ============================================================

@Serializable
data class CodigoVinculacion(
    /**
     * El codigo para teclear en el aparato nuevo.
     *
     * Se muestra UNA vez: en la base solo esta su hash. Si se pierde, se pide
     * otro; no hay forma de recuperarlo y eso es lo correcto.
     */
    val codigo: String,
    val expiraEnSegundos: Int,
    /** Como se llama el dispositivo que lo emitio, para mostrarlo en el nuevo. */
    val emitidoPor: String,
)

/**
 * Lo que manda el aparato nuevo. **Sin sesion**: todavia no tiene ninguna.
 *
 * Lleva la misma atestacion de hardware que un registro, porque la regla de
 * "un hardware, una cuenta" sigue en pie: vincular no es una puerta para
 * meter una segunda cuenta en el mismo telefono.
 */
@Serializable
data class VincularReq(
    val username: String,
    val codigo: String,
    val etiquetaDispositivo: String,
    val identidadPub: String,
    val hardwareHash: String,
    val hardwareNivel: String,
)

@Serializable
data class DispositivoInfo(
    val id: String,
    val etiqueta: String,
    val principal: Boolean,
    val esEste: Boolean,
    val nivelHardware: String,
    val registradoEn: Long,
    val ultimoVistoEn: Long? = null,
    /** Quien lo autorizo. Null en el principal: se autorizo al registrarse. */
    val vinculadoPor: String? = null,
    /**
     * Si ya publico su material de claves.
     *
     * Importa porque un dispositivo sin claves **no puede recibir nada**: los
     * demas no tienen con que cifrarle. Verlo en la lista explica por que un
     * aparato recien vinculado todavia no ve mensajes.
     */
    val tieneClaves: Boolean = false,
    /**
     * Modulo N. Si este aparato tiene un token de push registrado.
     *
     * Se muestra porque explica una diferencia real de comportamiento: sin
     * token, ese aparato **no se entera de nada con la app cerrada**. Es el
     * mismo criterio que [tieneClaves]: un estado que cambia lo que la persona
     * va a observar tiene que poder verse.
     */
    val recibeAvisos: Boolean = false,
)

@Serializable
data class DispositivosResp(val dispositivos: List<DispositivoInfo> = emptyList())

@Serializable
data class VincularHechoResp(
    val token: String,
    val usuarioId: String,
    val dispositivoId: String,
    val username: String,
    /**
     * Cuantos dispositivos tiene ahora la cuenta, contando este.
     *
     * Va en la respuesta para que la pantalla pueda decir "este es tu segundo
     * dispositivo" en vez de dejar al usuario preguntandose si funciono.
     */
    val dispositivos: Int = 1,
)

// ============================================================
//  J.4 · Historial
// ============================================================

/**
 * El aparato nuevo pide historial.
 *
 * ## El servidor no puede responder esto, y no es una limitacion accidental
 *
 * El servidor nunca tuvo el historial: los sobres se borran al confirmarse la
 * entrega. Asi que el pedido no se le hace al servidor, se le hace a **los
 * otros dispositivos de la misma persona**, que son los unicos que lo tienen.
 * El servidor solo reparte el aviso, porque es el que sabe quien esta
 * conectado.
 *
 * El que responde reenvia una ventana de mensajes por el buzon normal,
 * cifrados para el dispositivo nuevo, con [Carga.Historial]. Si no hay ningun
 * otro dispositivo conectado, el pedido simplemente no se responde y el
 * aparato nuevo arranca vacio: es lo honesto, y la pantalla lo dice.
 */
@Serializable
data class HistorialPedido(
    /** Cuantos mensajes por conversacion se piden. El que responde lo acota. */
    val porConversacion: Int = MENSAJES_POR_CONVERSACION,
) {
    companion object {
        /**
         * Cuantos mensajes se reenvian por conversacion.
         *
         * Cincuenta y no todo. Tres razones, en orden de peso:
         *
         *  1. Cada mensaje reenviado es un sobre cifrado mas: mandar el
         *     historial completo de una cuenta vieja serian decenas de miles.
         *  2. El que reenvia es un telefono, no un servidor, y lo hace con la
         *     app abierta.
         *  3. Lo que la gente necesita al cambiar de aparato es el contexto
         *     reciente de cada conversacion, no el archivo.
         */
        const val MENSAJES_POR_CONVERSACION = 50
    }
}

@Serializable
data class EstadoHistorial(
    /** Si este dispositivo ya recibio historial de otro. */
    val recibido: Boolean = false,
    val cuantos: Int = 0,
    /** Si hay algun otro dispositivo activo que pueda responder el pedido. */
    val hayQuienResponda: Boolean = false,
)


// ============================================================
//  Modulo N: avisos con la app cerrada
// ============================================================

const val RUTA_PUSH = "/v1/push"
const val RUTA_PUSH_WEB = "/v1/push/web"

/**
 * El token que el servicio de mensajeria del sistema le dio a ESTA instalacion.
 *
 * Va por dispositivo y no por cuenta porque identifica una instalacion: si la
 * misma persona tiene telefono y tablet son dos tokens, y hay que despertar los
 * dos. Es la misma razon por la que la direccion de Signal aqui es el
 * dispositivo y no el usuario.
 */
@Serializable
data class RegistrarPushReq(
    val token: String,
    /**
     * `"fcm"` (la app Android) o `"webpush"` (la version web). Con `webpush`,
     * el token es la URL del endpoint que dio `pushManager.subscribe`: es lo
     * unico que hace falta, porque el aviso va VACIO. Ver [ConfigWebPush].
     */
    val proveedor: String = "fcm",
)

const val PROVEEDOR_FCM = "fcm"
const val PROVEEDOR_WEBPUSH = "webpush"

/**
 * Lo que la version web necesita para suscribirse: la clave publica VAPID.
 *
 * ## El aviso va vacio
 *
 * Web Push (RFC 8030) permite mandar un cuerpo cifrado para el navegador
 * (RFC 8291). Aqui no se usa: el POST al servicio de push no lleva cuerpo. Es lo
 * mismo que el `data: {"w":"1"}` de FCM, llevado al extremo: el servicio de
 * push (Google, Mozilla, Apple, Microsoft) aprende que este navegador recibio
 * un aviso a esta hora, y nada mas. El service worker muestra un "tienes algo
 * nuevo" fijo y la pagina, al abrirse, baja y descifra lo pendiente.
 *
 * La clave publica no es secreta: es lo que el navegador usa para comprobar que
 * los avisos vienen de ESTE servidor. La privada no sale nunca de el.
 *
 * Va en una ruta propia y no dentro de [ConfigPush] para no tocar el contrato
 * que ya usa la app Android.
 */
@Serializable
data class ConfigWebPush(
    val disponible: Boolean = false,
    /** P-256 sin comprimir (65 bytes), en base64url sin relleno. */
    val clavePublica: String = "",
)

/**
 * Lo que el cliente necesita para inicializar Firebase, servido por NUESTRO
 * servidor.
 *
 * ## Por que no va dentro del APK
 *
 * Porque asi el push se habilita **sin recompilar la app**: se ponen las
 * variables en el servidor y el proximo arranque del telefono ya se registra.
 * Un `google-services.json` incrustado obligaria a publicar una version nueva
 * para cambiar de proyecto, y a tener una build distinta por entorno.
 *
 * Ninguno de estos valores es secreto: identifican al proyecto y no autorizan
 * nada por si solos. El secreto —la clave privada de la cuenta de servicio— no
 * sale nunca del servidor.
 *
 * `disponible = false` significa que el servidor no tiene push configurado, y
 * el cliente no hace nada: no es un error, es una funcion apagada.
 */
@Serializable
data class ConfigPush(
    val disponible: Boolean = false,
    val proyectoId: String = "",
    val appId: String = "",
    val apiKey: String = "",
    val remitenteId: String = "",
)
