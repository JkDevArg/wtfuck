package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * W5e · Key Attestation: que el SERVIDOR compruebe -con una firma que sale del
 * chip del telefono- lo que hasta ahora solo DECLARABA el cliente.
 *
 * ## El problema que cierra
 *
 * `hardwareNivel` y `hardwareHash` los manda el cliente y el servidor les cree
 * (docs/04-DEVICE-BINDING.md). Un script que se hace pasar por la app entra sin
 * invitacion, porque la app Android no la pide: es la puerta que usaria un bot
 * para saltarse el correo y la invitacion de la web.
 *
 * ## El mecanismo
 *
 * 1. La app pide un reto ([RUTA_ATESTACION_RETO]): 32 bytes al azar, de un uso.
 * 2. Genera una clave NUEVA en el Keystore con `setAttestationChallenge(reto)`.
 *    El chip (TEE o StrongBox) firma un certificado que dice que clave es, en
 *    que hardware vive, el estado del arranque, y que app la pidio.
 * 3. Manda la cadena entera en [RegistroReq.atestacion] (o al vincular o
 *    recuperar). El servidor la valida hasta una raiz de Google y lee lo que
 *    dice, en vez de creerle al cliente.
 *
 * Una cadena asi no se puede fabricar en una PC: la firma es del chip, con una
 * clave que el fabricante puso de fabrica y que Google certifica.
 */
const val RUTA_ATESTACION_RETO = "/v1/atestacion/reto"

@Serializable
data class RetoAtestacion(
    /** 32 bytes, base64. Va DENTRO del certificado que firma el chip. */
    val reto: String,
    val expiraEnSegundos: Int,
    /** `apagada`, `registrar` o `exigir`. Ver [ModoAtestacion]. */
    val modo: String,
)

object ModoAtestacion {
    /** No se pide ni se mira. */
    const val APAGADA = "apagada"

    /**
     * Se verifica y se ANOTA el resultado, pero no se rechaza a nadie. Es el
     * modo de arranque: sirve para medir cuantos telefonos reales fallan, y
     * por que, antes de decidir exigirla (docs/04-DEVICE-BINDING.md).
     */
    const val REGISTRAR = "registrar"

    /** Sin una cadena valida, un telefono no se registra, vincula ni recupera. */
    const val EXIGIR = "exigir"
}
