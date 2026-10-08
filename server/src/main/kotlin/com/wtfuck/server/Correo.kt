package com.wtfuck.server

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val bitacoraCorreo = LoggerFactory.getLogger("Correo")

/**
 * W5 · Correos con un codigo de 6 digitos, para registrarse desde la web y para
 * recuperar una cuenta creada ahi.
 *
 * ## Que se guarda: una huella, no el correo
 *
 * Igual que el telefono (`Identidad.hashTelefono`): HMAC del correo con el
 * pepper del servidor y el prefijo `correo:`, para que la huella de un correo
 * no se pueda confundir nunca con la de un telefono. Con eso alcanza para lo
 * unico que hace falta -que no se repita y encontrar la cuenta al recuperarla,
 * cuando la persona lo vuelve a escribir-. El correo en claro solo pasa por la
 * memoria para mandar el mensaje.
 *
 * ## Por que una API HTTP y no SMTP
 *
 * Porque el JDK no trae cliente SMTP y agregar Jakarta Mail es una dependencia
 * grande para mandar un codigo. Los servicios de correo transaccional (Brevo,
 * Resend, Mailjet, MailChannels...) tienen todos una API JSON por HTTP: se
 * configura igual que la pasarela de SMS, con una plantilla. Ver
 * docs/09-DESPLIEGUE.md, "Correo".
 *
 * ## Sin servicio configurado
 *
 * En desarrollo (`WTFUCK_PERMITIR_SOFTWARE_DEV=true`) el codigo se escribe en
 * la bitacora y vuelve en la respuesta, para poder probar. En produccion NO:
 * devolverlo seria saltarse la verificacion entera, asi que el registro web
 * queda cerrado y `GET /v1/registro/modo` lo dice (`registroWeb = false`).
 */
interface Correo {
    /** Si de verdad sale un correo. */
    val real: Boolean
    fun enviar(destino: String, asunto: String, texto: String): Boolean
}

object CorreoConsola : Correo {
    override val real = false
    override fun enviar(destino: String, asunto: String, texto: String): Boolean {
        bitacoraCorreo.warn("SIN servicio de correo. Para {}: {} | {}", Correos.ofuscar(destino), asunto, texto)
        return true
    }
}

class CorreoHttp(
    private val url: String,
    private val cabecera: String,
    private val token: String?,
    private val plantilla: String,
    private val remitente: String,
) : Correo {
    override val real = true

    private val cliente: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun enviar(destino: String, asunto: String, texto: String): Boolean {
        val cuerpo = plantilla
            .replace("{destino}", json(destino))
            .replace("{asunto}", json(asunto))
            .replace("{texto}", json(texto))
            .replace("{remitente}", json(remitente))
        return runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .apply { token?.let { header(cabecera, it) } }
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build()
            val resp = cliente.send(req, HttpResponse.BodyHandlers.ofString())
            val ok = resp.statusCode() in 200..299
            if (!ok) {
                bitacoraCorreo.error(
                    "El servicio de correo rechazo el envio a {}: {} {}",
                    Correos.ofuscar(destino), resp.statusCode(), resp.body().take(200),
                )
            }
            ok
        }.getOrElse {
            bitacoraCorreo.error("No se pudo enviar a {}: {}", Correos.ofuscar(destino), it.message)
            false
        }
    }

    /** El valor va DENTRO de comillas en la plantilla: se escapa como cadena JSON. */
    private fun json(s: String) = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}

object Correos {

    /**
     * El servicio, desde el entorno.
     *
     * - `WTFUCK_CORREO_URL`: el endpoint de la API del servicio.
     * - `WTFUCK_CORREO_CABECERA`: donde va la credencial. `Authorization` por
     *   defecto (Resend: `Bearer re_...`); Brevo usa `api-key`.
     * - `WTFUCK_CORREO_TOKEN`: el valor ENTERO de esa cabecera. Es el secreto.
     * - `WTFUCK_CORREO_CUERPO`: plantilla JSON con `{destino}`, `{asunto}`,
     *   `{texto}` y `{remitente}`.
     * - `WTFUCK_CORREO_REMITENTE`: la direccion de origen, ya verificada en el
     *   servicio.
     */
    val servicio: Correo by lazy {
        val url = System.getenv("WTFUCK_CORREO_URL")?.takeIf { it.isNotBlank() }
        if (url == null) {
            bitacoraCorreo.warn(
                "Sin WTFUCK_CORREO_URL: " + if (Config.permitirSoftwareDev) {
                    "los codigos se escriben en la bitacora (modo desarrollo)."
                } else {
                    "el registro desde la web queda CERRADO."
                },
            )
            return@lazy CorreoConsola
        }
        bitacoraCorreo.info("Correo por API HTTP")
        CorreoHttp(
            url = url,
            cabecera = System.getenv("WTFUCK_CORREO_CABECERA")?.takeIf { it.isNotBlank() } ?: "Authorization",
            token = System.getenv("WTFUCK_CORREO_TOKEN")?.takeIf { it.isNotBlank() },
            plantilla = System.getenv("WTFUCK_CORREO_CUERPO")
                ?: """{"from":"{remitente}","to":["{destino}"],"subject":"{asunto}","text":"{texto}"}""",
            remitente = System.getenv("WTFUCK_CORREO_REMITENTE") ?: "no-responder@localhost",
        )
    }

    /**
     * Si se puede verificar un correo. Sin servicio real, solo en desarrollo:
     * en produccion seria devolver el codigo en la respuesta.
     */
    val disponible: Boolean get() = servicio.real || Config.permitirSoftwareDev

    // ------------------------------------------------------------
    //  Forma y normalizacion
    // ------------------------------------------------------------

    /**
     * Una forma razonable, no la RFC 5322 entera: la RFC acepta cosas que
     * ningun servicio de correo real entrega. Una sola arroba, algo antes, un
     * dominio con punto y sin espacios.
     */
    private val FORMA = Regex("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

    /**
     * El correo como se compara: sin espacios y en minusculas.
     *
     * No se quitan los puntos ni el `+algo` de Gmail: dependen del proveedor, y
     * adivinarlo mal uniria dos cuentas de dos personas distintas. Lo que eso
     * deja pasar -varias cuentas con alias del mismo Gmail- lo frena la
     * invitacion, que es de un solo uso.
     */
    fun normalizar(crudo: String): String? {
        val c = crudo.trim().lowercase()
        if (c.length > 254 || !FORMA.matches(c)) return null
        return c
    }

    fun exigir(crudo: String): String {
        val c = normalizar(crudo) ?: throw ErrorNegocio(400, "Ese correo no parece valido.")
        if (desechable(c)) {
            throw ErrorNegocio(400, "No se aceptan correos temporales. Usa uno que vayas a conservar.")
        }
        return c
    }

    /** `ana@dominio.com` -> `a***@dominio.com`, para la bitacora. */
    fun ofuscar(correo: String): String {
        val i = correo.indexOf('@')
        return if (i <= 0) "***" else correo.take(1) + "***" + correo.substring(i)
    }

    // ------------------------------------------------------------
    //  Correos desechables
    // ------------------------------------------------------------

    /**
     * Dominios de correo de usar y tirar. La lista viene en el recurso
     * `/correo/desechables.txt` (uno por linea) y se le puede sumar
     * `WTFUCK_CORREO_BLOQUEADOS` (separados por coma).
     *
     * No es una barrera completa -salen dominios nuevos todos los dias- y no
     * pretende serlo: la invitacion de un solo uso es la que pone el costo. Esto
     * saca de en medio lo mas comun.
     */
    private val desechables: Set<String> by lazy {
        val base = Correos::class.java.getResourceAsStream("/correo/desechables.txt")
            ?.bufferedReader()?.useLines { l ->
                l.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            } ?: emptySet()
        val extra = System.getenv("WTFUCK_CORREO_BLOQUEADOS")
            ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
        base + extra
    }

    /** El dominio o cualquiera de sus padres: `x.mailinator.com` tambien cae. */
    fun desechable(correo: String): Boolean {
        var d = correo.substringAfter('@')
        while (true) {
            if (d in desechables) return true
            val i = d.indexOf('.')
            if (i < 0 || d.indexOf('.', i + 1) < 0) return false
            d = d.substring(i + 1)
        }
    }

    // ------------------------------------------------------------
    //  Los textos
    // ------------------------------------------------------------

    fun textoRegistro(codigo: String) =
        "Tu codigo para crear la cuenta en wtfuck es $codigo. Vence en 10 minutos.\n\n" +
            "Si no lo pediste, ignora este correo: nadie puede crear la cuenta sin el."

    fun textoYaRegistrado() =
        "Alguien intento crear una cuenta de wtfuck con este correo, pero ya hay una cuenta con el.\n\n" +
            "Si fuiste tu y perdiste el acceso, usa \"Recuperar cuenta\" en la web. Si no fuiste tu, " +
            "ignora este correo: no cambio nada."

    fun textoRecuperar(codigo: String) =
        "Tu codigo para recuperar tu cuenta de wtfuck es $codigo. Vence en 10 minutos.\n\n" +
            "Si no lo pediste, alguien esta intentando entrar a tu cuenta: no compartas este codigo."
}
