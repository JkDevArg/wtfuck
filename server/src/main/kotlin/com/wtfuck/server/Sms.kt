package com.wtfuck.server

import com.wtfuck.protocol.Telefonos
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val bitacoraSms = LoggerFactory.getLogger("Sms")

/**
 * Envio de SMS.
 *
 * ## Dos implementaciones, y la de desarrollo es explicita
 *
 * Sin pasarela configurada, [SmsConsola] escribe el codigo en la bitacora y el
 * servidor lo devuelve en la respuesta. Eso ultimo es un agujero enorme, asi que
 * **solo pasa cuando no hay pasarela** y la respuesta lo marca como
 * `codigoDePrueba`: si alguien despliega sin SMS, el nombre del campo lo grita.
 *
 * ## Por que una pasarela genérica por HTTP y no un SDK
 *
 * Las pasarelas de SMS de la region -y las globales- se manejan todas igual: un
 * POST con el destino y el texto. Un SDK por proveedor seria una dependencia
 * grande, con su propio ciclo de vida, para construir una peticion de tres
 * campos. Con [SmsHttp] se configura la URL y la plantilla del cuerpo por
 * variables de entorno y se puede cambiar de proveedor sin recompilar.
 */
interface Sms {

    /** Si hay un transporte de verdad detras. */
    val real: Boolean

    /**
     * Manda el codigo. No lanza si falla el envio: devuelve false.
     *
     * El motivo es que el codigo ya se guardo en la base dentro de la misma
     * transaccion. Si esto lanzara, la transaccion se revertiria y un fallo
     * transitorio de la pasarela borraria un codigo que tal vez SI llego. Es
     * mejor un codigo huerfano -vence en diez minutos- que uno entregado y no
     * registrado.
     */
    fun enviarCodigo(destinoE164: String, codigo: String, proposito: String): Boolean
}

object SmsConsola : Sms {
    override val real = false

    override fun enviarCodigo(destinoE164: String, codigo: String, proposito: String): Boolean {
        // El destino se ofusca en la bitacora. Un log no es lugar para un
        // numero completo, y para depurar alcanza con ver que llego.
        bitacoraSms.warn(
            "SIN pasarela de SMS. Codigo de {} para {}: {}",
            proposito, Telefonos.ofuscar(destinoE164), codigo,
        )
        return true
    }
}

/**
 * Pasarela HTTP genérica. Se configura con:
 *
 *     WTFUCK_SMS_URL       obligatoria, activa el envio real
 *     WTFUCK_SMS_TOKEN     va en Authorization: Bearer
 *     WTFUCK_SMS_CUERPO    plantilla JSON con {destino} y {texto}
 *     WTFUCK_SMS_REMITENTE nombre corto del remitente, si la pasarela lo usa
 */
class SmsHttp(
    private val url: String,
    private val token: String?,
    private val plantilla: String,
    private val remitente: String,
) : Sms {

    override val real = true

    private val cliente: HttpClient = HttpClient.newBuilder()
        // Sin timeout, una pasarela colgada cuelga la peticion del usuario. El
        // codigo ya esta guardado: no vale la pena esperar.
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    override fun enviarCodigo(destinoE164: String, codigo: String, proposito: String): Boolean {
        val texto = textoDe(proposito, codigo)
        val cuerpo = plantilla
            .replace("{destino}", escapar(destinoE164))
            .replace("{texto}", escapar(texto))
            .replace("{remitente}", escapar(remitente))

        return runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build()

            val resp = cliente.send(req, HttpResponse.BodyHandlers.ofString())
            val ok = resp.statusCode() in 200..299
            if (!ok) {
                // El cuerpo de la respuesta puede traer el numero: no se
                // registra entero.
                bitacoraSms.error(
                    "La pasarela rechazo el envio a {}: {}",
                    Telefonos.ofuscar(destinoE164), resp.statusCode(),
                )
            }
            ok
        }.getOrElse {
            bitacoraSms.error("No se pudo enviar a {}: {}", Telefonos.ofuscar(destinoE164), it.message)
            false
        }
    }

    /**
     * El texto del mensaje.
     *
     * Corto y con el codigo al final: un SMS se ve en la pantalla de bloqueo
     * recortado, y lo que la persona necesita leer sin desbloquear es el
     * numero. Aun asi, el aviso de "si no lo pediste" va porque un codigo que
     * llega sin motivo es la primera senal de que alguien esta intentando
     * entrar.
     */
    private fun textoDe(proposito: String, codigo: String): String = when (proposito) {
        "recuperar_cuenta" -> "wtfuck: codigo para recuperar tu cuenta: $codigo. Vence en 10 minutos. Si no lo pediste, ignoralo."
        "eliminar_cuenta" -> "wtfuck: codigo para confirmar la eliminacion de tu cuenta: $codigo. Vence en 10 minutos."
        else -> "wtfuck: tu codigo de verificacion es $codigo. Vence en 10 minutos. Si no lo pediste, ignoralo."
    }

    /** Escapado minimo para meter un valor dentro de la plantilla JSON. */
    private fun escapar(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
}

object SmsFactory {

    /**
     * Elige el transporte segun el entorno. Se llama una vez al arrancar.
     *
     * Deja constancia en la bitacora de cual quedo: desplegar sin pasarela y no
     * darse cuenta es justamente el error que hay que hacer ruidoso.
     */
    fun desdeEntorno(): Sms {
        val url = System.getenv("WTFUCK_SMS_URL")?.takeIf { it.isNotBlank() }
        if (url == null) {
            bitacoraSms.warn(
                "Sin WTFUCK_SMS_URL: los codigos se escriben en la bitacora y se " +
                    "devuelven en la respuesta. Solo para desarrollo.",
            )
            return SmsConsola
        }
        val s = SmsHttp(
            url = url,
            token = System.getenv("WTFUCK_SMS_TOKEN")?.takeIf { it.isNotBlank() },
            plantilla = System.getenv("WTFUCK_SMS_CUERPO")
                ?: """{"to":"{destino}","message":"{texto}","from":"{remitente}"}""",
            remitente = System.getenv("WTFUCK_SMS_REMITENTE") ?: "wtfuck",
        )
        bitacoraSms.info("SMS por pasarela HTTP")
        return s
    }

    /**
     * Normaliza el numero y lo devuelve en E.164, o lanza.
     *
     * Es el unico punto de entrada: todo lo que siga trabaja con el numero
     * canonico. Si cada sitio normalizara por su cuenta, el mismo telefono
     * escrito de dos maneras daria dos hashes y el descubrimiento fallaria sin
     * que nadie entienda por que.
     */
    fun exigirTelefono(crudo: String): String =
        Telefonos.normalizar(crudo)
            ?: throw ErrorNegocio(
                400,
                "Ese numero no parece valido. Escribelo con el prefijo del pais, " +
                    "por ejemplo +51 987 654 321.",
            )
}
