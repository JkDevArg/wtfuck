package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo I: identidad y cuenta.
 *
 * ## El problema que resuelve
 *
 * El **ingreso es por username y contrasena**, y eso no cambia. Lo que faltaba
 * era un canal verificado, porque sin ninguno hay dos cosas imposibles:
 * recuperar una cuenta y encontrar a alguien. Las dos se resuelven con un
 * numero de telefono **opcional**.
 *
 * ## El telefono no es una credencial
 *
 * No sirve para entrar. Si sirviera, volveria a ser el identificador de la
 * cuenta y se perderia lo que el registro por username resolvio: que nadie
 * necesite dar su numero para poder hablar. Quien no quiera darlo no lo da; el
 * precio, declarado, es que no podra recuperar la cuenta ni ser encontrado.
 *
 * ## La parte que hay que entender del numero
 *
 * El servidor guarda **solo el hash**, nunca el numero. Y aun asi puede
 * recuperar cuentas, porque el numero se recibe cada vez que hace falta
 * usarlo: al verificar lo escribe el usuario, y al recuperar lo vuelve a
 * escribir. El servidor lo normaliza, lo hashea, compara con lo guardado, y
 * manda el codigo a lo que acaba de recibir. Una fuga de la base no entrega
 * ni un numero.
 */

const val RUTA_CUENTA = "/v1/cuenta"
const val RUTA_CONTACTOS = "/v1/contactos"
const val RUTA_SESIONES = "/v1/sesiones"

/**
 * Dias de gracia antes de que una eliminacion se ejecute.
 *
 * Vive en el contrato y no en cada lado: la pantalla tiene que poder decir el
 * numero ANTES de pedir la eliminacion -es el dato que cambia la decision- y el
 * servidor es quien lo hace cumplir. Dos copias se habrian desincronizado el
 * dia que alguien cambiara una.
 */
const val DIAS_GRACIA_ELIMINACION = 30

// ============================================================
//  Telefono de recuperacion
// ============================================================

/**
 * Numeros de telefono, normalizados a E.164.
 *
 * ## La normalizacion es la parte que importa
 *
 * El mismo telefono se escribe de seis maneras: `987 654 321`,
 * `987-654-321`, `(51) 987654321`, `+51 987 654 321`, `0051987654321`,
 * `51987654321`. Si cada una produjera un hash distinto, el descubrimiento
 * fallaria **en silencio**: dos personas con el mismo numero en la libreta no
 * se encontrarian y nadie sabria por que.
 *
 * Por eso todo pasa por [normalizar] antes de hashear, en el cliente y en el
 * servidor, y el resultado es siempre `+` seguido de digitos.
 *
 * ## El prefijo por defecto
 *
 * Un numero sin `+` es ambiguo: `987654321` es peruano aqui y de otro pais
 * en otra parte. Se asume [PAIS_POR_DEFECTO] cuando no viene prefijo, que es
 * lo que hace cualquier agenda de telefono, y la pantalla lo muestra para que
 * no sea una sorpresa.
 */
object Telefonos {

    /** Peru. Es donde esta la gente que usa esto. */
    const val PAIS_POR_DEFECTO = "51"

    /**
     * Deja el numero en E.164: `+` y entre 8 y 15 digitos.
     *
     * Devuelve null si no hay forma de interpretarlo. No se valida que el
     * numero EXISTA -eso lo responde el mensaje que llega o no llega-, solo
     * que tenga forma de numero.
     */
    fun normalizar(crudo: String?): String? {
        val bruto = crudo?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Se conserva solo el + inicial y los digitos. Espacios, guiones,
        // parentesis y puntos son decoracion de como lo escribio la persona.
        val masInicial = bruto.startsWith("+")
        var digitos = bruto.filter { it.isDigit() }
        if (digitos.isEmpty()) return null

        if (!masInicial) {
            // `00` es el prefijo internacional de marcado: 0051... es +51...
            digitos = when {
                digitos.startsWith("00") -> digitos.drop(2)
                // Un numero local, sin prefijo de pais: se asume el de aqui.
                digitos.length <= 10 -> PAIS_POR_DEFECTO + digitos.trimStart('0')
                else -> digitos
            }
        }

        // E.164: hasta 15 digitos incluyendo el pais. El minimo de 8 descarta
        // los errores de dedo sin rechazar los paises de numeracion corta.
        if (digitos.length !in 8..15) return null
        return "+$digitos"
    }

    fun valido(crudo: String?) = normalizar(crudo) != null

    /**
     * El prefijo de pais. Heuristica corta y suficiente: de los prefijos que
     * importan aqui, unos son de un digito y otros de dos.
     *
     * No se pretende un catalogo completo de la ITU. Este dato es cosmetico
     * -sirve para mostrar "+51" en una pantalla- y equivocarse no rompe nada;
     * lo que no puede fallar es el numero completo, y ese va entero al hash.
     */
    fun paisDe(e164: String): String {
        val d = e164.removePrefix("+")
        return when {
            d.startsWith("1") -> "1"
            d.startsWith("7") -> "7"
            d.length >= 2 -> d.take(2)
            else -> d
        }
    }

    /** `+51987654321` -> `+51 *** *** 321`. Para mostrar sin exponer. */
    fun ofuscar(e164: String): String {
        val d = e164.removePrefix("+")
        if (d.length < 5) return "+***"
        val pais = paisDe(e164)
        return "+$pais *** *** " + d.takeLast(3)
    }
}

object PropositoCodigo {
    const val VERIFICAR_TELEFONO = "verificar_telefono"
    const val RECUPERAR_CUENTA = "recuperar_cuenta"
    const val ELIMINAR_CUENTA = "eliminar_cuenta"
}

@Serializable
data class PedirCodigoReq(
    /** Como lo escribio la persona. El servidor lo normaliza a E.164. */
    val telefono: String,
    val proposito: String = PropositoCodigo.VERIFICAR_TELEFONO,
    /** Solo para recuperar: el servidor necesita saber de que cuenta se habla. */
    val username: String? = null,
)

@Serializable
data class CodigoPedido(
    /**
     * Cuantos segundos hay que esperar para pedir otro.
     *
     * Va en la respuesta para que la pantalla muestre la cuenta atras en vez de
     * dejar que el usuario toque "reenviar" y se coma un 429.
     */
    val reintentarEnSegundos: Int,
    val expiraEnSegundos: Int,
    /**
     * El codigo, SOLO en desarrollo y solo cuando no hay pasarela de SMS
     * configurada. En produccion viaja null y el codigo va por mensaje.
     */
    val codigoDePrueba: String? = null,
)

@Serializable
data class VerificarTelefonoReq(val telefono: String, val codigo: String)

@Serializable
data class TelefonoVerificado(
    /** Prefijo de pais, en claro. El numero no vuelve nunca. */
    val pais: String,
    /** El numero ofuscado, para poder mostrarlo sin guardarlo. */
    val ofuscado: String,
    val verificadoEn: Long,
)

// ============================================================
//  Recuperar la cuenta
// ============================================================

/**
 * Cambiar la contrasena con un codigo recibido por SMS.
 *
 * **Lo que esto NO hace, y hay que decirlo:** no devuelve el acceso desde otro
 * telefono. El vinculo con el hardware se comprueba al ingresar y no cambia
 * aqui, asi que quien perdio el telefono recupera la contrasena y sigue sin
 * poder entrar. Es el techo declarado del "una cuenta por dispositivo"
 * (docs/04-DEVICE-BINDING.md); si el telefono pudiera saltearlo, pasaria a
 * valer mas que el dispositivo y la regla se caeria por la puerta de atras.
 *
 * Lo que resuelve es el caso comun y el unico que se podia resolver sin romper
 * nada: olvidar la contrasena teniendo el telefono a mano. Cambiar de telefono
 * es el modulo J.
 */
@Serializable
data class RecuperarReq(
    val username: String,
    val telefono: String,
    val codigo: String,
    val passwordNueva: String,
    /**
     * TOTP o codigo de respaldo, si la cuenta tiene dos pasos activado.
     *
     * ## Por que la recuperacion tambien lo pide
     *
     * Porque si no, **el SMS puentea el segundo factor**. La recuperacion
     * cambia la contrasena y cierra todas las sesiones, asi que quien controle
     * el numero -un cambio de SIM, un operador con un empleado comprado- podia
     * hacer eso con el TOTP encendido y sin tocarlo. El segundo factor existe
     * justamente para que tener el numero no alcance; una ruta de recuperacion
     * que lo salta lo anula por completo.
     *
     * No deja fuera a quien perdio el telefono: `exigirSegundoFactor` acepta
     * tambien un **codigo de respaldo**, que es para lo que se emiten.
     */
    val totp: String? = null,
)

// ============================================================
//  El codigo de recuperacion
// ============================================================

/**
 * Fija -o rota- el codigo de recuperacion de la cuenta. Pide sesion.
 *
 * ## Que viaja y que no
 *
 * **El codigo NUNCA viaja.** Lo genera el telefono, se lo muestra a la
 * persona para que lo escriba en papel, y de ahi deriva dos claves
 * independientes con HKDF y etiquetas distintas:
 *
 * ```
 *   codigo --HKDF("wtfuck/copia/identidad/v1")--> cifra la identidad Signal
 *   codigo --HKDF("wtfuck/servidor/verificador/v1")--> [verificadorB64]
 * ```
 *
 * Aqui viaja solo el segundo, y el servidor lo guarda **hasheado**. De lo que
 * el servidor tiene no se llega a la clave de la identidad: HKDF no es
 * invertible y las etiquetas producen salidas independientes. Por eso una
 * fuga de la base del servidor no permite descifrar la identidad de nadie ni
 * teniendo tambien el archivo de la copia.
 */
@Serializable
data class FijarRecuperacionReq(
    /** Base64 del verificador derivado. Nunca el codigo. */
    val verificadorB64: String,
    /**
     * La contrasena actual.
     *
     * Fijar un codigo nuevo INVALIDA el anterior, asi que quien se siente un
     * momento en una sesion abierta ajena podria dejar la cuenta con un
     * codigo suyo y quedarse con la unica salida. Pedir la contrasena lo
     * impide. Es la misma razon por la que cambiarla se pide dos veces.
     */
    val password: String,
    /** TOTP o codigo de respaldo, si la cuenta tiene dos pasos. */
    val totp: String? = null,
)

/** Lo que se sabe del codigo de recuperacion de la cuenta. */
@Serializable
data class EstadoRecuperacion(
    val configurado: Boolean,
    /** Cuando se fijo, en epoch ms. `0` si no hay. */
    val fijadoEn: Long = 0,
)

/**
 * Recupera la cuenta EN UN TELEFONO NUEVO. No pide sesion: es justo el caso
 * en que no se puede tener una.
 *
 * ## Las cuatro puertas, y por que cada una
 *
 * Esta ruta hace lo que el atado al hardware existe para impedir —dar de alta
 * un aparato que nadie autorizo desde dentro—, asi que se paga caro a
 * proposito:
 *
 * | Puerta | Contra quien |
 * |---|---|
 * | Codigo del SMS | Quien encontro el papel pero no controla el numero |
 * | Codigo de recuperacion | Quien te clono la SIM pero no tiene el papel |
 * | Segundo factor | Los dos anteriores a la vez, si esta activado |
 * | Contrasena nueva | Cierra las sesiones viejas: si te robaron el telefono, deja de valer |
 *
 * El SMS **no sobra** aunque parezca el factor debil. Sin el, quien entrara a
 * tu casa y encontrara el papel con el codigo, sabiendo tu usuario, entraria:
 * un secreto en papel tiene un modelo de robo muy distinto al de una clave.
 *
 * ## Que le pasa a los aparatos viejos
 *
 * Se revocan todos. El escenario es "perdi el telefono": dejarlo vivo seria
 * dejar dentro justo al que puede tenerlo. Y este aparato queda como
 * PRINCIPAL, porque despues de esto es el unico que hay.
 *
 * ## El orden importa en el telefono
 *
 * Conviene **restaurar la copia antes** de llamar aqui: asi `identidadPub` es
 * la identidad de siempre y a los contactos no les salta el aviso de clave
 * cambiada. Si se llama antes, la cuenta se recupera igual pero con identidad
 * nueva. La pantalla lo dice.
 */
@Serializable
data class RecuperarDispositivoReq(
    val username: String,
    /** El numero verificado, como lo escriba la persona. */
    val telefono: String,
    /** El del SMS. */
    val codigo: String,
    /** Base64 del verificador derivado del codigo de recuperacion. */
    val verificadorB64: String,
    val passwordNueva: String,
    val totp: String? = null,
    // --- el aparato nuevo, igual que en el registro ---
    val etiquetaDispositivo: String,
    val identidadPub: String,
    val hardwareHash: String,
    val hardwareNivel: String,
)

// ============================================================
//  2FA (TOTP)
// ============================================================

@Serializable
data class TotpIniciado(
    /** Secreto en base32, como lo esperan las apps de autenticacion. */
    val secretoBase32: String,
    /** URI `otpauth://` para el QR. */
    val uri: String,
)

@Serializable
data class TotpConfirmarReq(val codigo: String)

@Serializable
data class TotpActivado(
    /**
     * Codigos de respaldo, en claro y **una sola vez**.
     *
     * No se pueden volver a mostrar: en la base estan hasheados. Un segundo
     * factor sin salida de emergencia convierte perder el telefono en perder la
     * cuenta, que es el problema que este modulo vino a resolver.
     */
    val codigosRespaldo: List<String>,
)

// ============================================================
//  Estado de mi cuenta
// ============================================================

@Serializable
data class EstadoCuenta(
    val username: String,
    val creadoEn: Long,
    val biografia: String = "",

    val telefonoVerificado: Boolean = false,
    /** Prefijo de pais, en claro. Lo comparten millones: no identifica a nadie. */
    val telefonoPais: String? = null,

    val descubrible: Boolean = true,
    val totpActivado: Boolean = false,
    val codigosRespaldoSinUsar: Int = 0,

    /** Si hay una eliminacion pedida, cuando se ejecuta. */
    val eliminacionPedidaEn: Long? = null,
    val eliminacionSeEjecutaEn: Long? = null,

    /**
     * Si esta cuenta tiene algun camino de vuelta.
     *
     * Hoy es exactamente "tiene telefono verificado". Sin eso, olvidar la
     * contrasena es perder la cuenta, y la pantalla tiene que poder decirlo
     * antes de que pase, no despues.
     */
    val puedeRecuperarse: Boolean = false,
)

@Serializable
data class AjustesCuentaReq(
    val biografia: String? = null,
    val descubrible: Boolean? = null,
)

// ============================================================
//  Sesiones
// ============================================================

@Serializable
data class SesionActiva(
    val id: String,
    val esLaActual: Boolean,
    val emitidoEn: Long,
    val ultimoUsoEn: Long? = null,
    val expiraEn: Long,
    val ip: String? = null,
    val agente: String? = null,
)

@Serializable
data class SesionesResp(val sesiones: List<SesionActiva> = emptyList())

// ============================================================
//  Contactos y descubrimiento
// ============================================================

@Serializable
data class Contacto(
    val username: String,
    val nombreMostrado: String = "",
    /** Como lo llamo YO. No viaja a nadie mas. */
    val alias: String? = null,
    val favorito: Boolean = false,
    val avatarVersion: Long = 0,
    val agregadoEn: Long,
)

@Serializable
data class ContactosResp(val contactos: List<Contacto> = emptyList())

@Serializable
data class GuardarContactoReq(
    val username: String,
    val alias: String? = null,
    val favorito: Boolean? = null,
)

/**
 * Descubrir personas por su numero de telefono.
 *
 * Es lo que hace WhatsApp con la agenda: se mandan los numeros que ya tienes
 * guardados y el servidor dice cuales tienen cuenta.
 *
 * ## Por que el cliente manda numeros y no hashes
 *
 * El protocolo "el cliente hashea y manda hashes" suena mejor y aqui no
 * funciona. `telefono_hash` se calcula con un pepper que vive **solo en el
 * servidor**, y es lo unico que hace que una fuga de la base no permita
 * recuperar los numeros por fuerza bruta: un telefono son nueve digitos, o sea
 * mil millones de candidatos, que para un SHA-256 son minutos.
 *
 * Para que el cliente pudiera hashear habria que entregarle el pepper, y un
 * secreto que esta en cuarenta mil telefonos no es un secreto: la primera
 * persona que lo extraiga deja la base abierta otra vez.
 *
 * Asi que la eleccion real era: privacidad frente al SERVIDOR -que ya ve el
 * numero al verificarlo- o resistencia a una FUGA de la base. Gana la segunda,
 * porque la fuga es el escenario que no se controla.
 *
 * El servidor recibe los numeros, los normaliza, los hashea y **no los
 * guarda**. Lo que acota la enumeracion es otra cosa: el tope por peticion, el
 * limite de ritmo, y que solo aparece quien acepto ser descubrible.
 *
 * Los numeros van en el cuerpo de un POST y nunca en la URL: un dato personal
 * en una query string termina en los registros de cualquier proxy.
 */
@Serializable
data class DescubrirReq(val telefonos: List<String>) {
    companion object {
        /**
         * Tope por peticion.
         *
         * Alto porque el caso legitimo es subir una agenda entera de una vez,
         * y bajo respecto de lo que costaria enumerar: con el limite de ritmo,
         * son unos pocos miles de numeros por hora frente a mil millones
         * posibles.
         */
        const val MAX_TELEFONOS = 500
    }
}

@Serializable
data class Descubierto(
    /** El numero consultado, ya normalizado, para que el cliente sepa cual coincidio. */
    val telefono: String,
    val username: String,
    val nombreMostrado: String = "",
    val avatarVersion: Long = 0,
    val yaEsContacto: Boolean = false,
)

@Serializable
data class DescubrirResp(val encontrados: List<Descubierto> = emptyList())

// ============================================================
//  Eliminar la cuenta
// ============================================================

@Serializable
data class EliminarCuentaReq(
    val password: String,
    /** Si la cuenta tiene 2FA, tambien hace falta el codigo. */
    val totp: String? = null,
)

@Serializable
data class EliminacionPedida(
    val pedidaEn: Long,
    val seEjecutaEn: Long,
    val diasDeGracia: Int,
    /**
     * Lo que el servidor NO puede borrar.
     *
     * Va en la respuesta porque prometer un borrado total seria mentir: los
     * mensajes ya entregados viven en los telefonos de los demas y ninguna
     * cuenta puede deshacer eso.
     */
    val advertencias: List<String> = emptyList(),
)
