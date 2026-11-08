package com.wtfuck.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * CAPTCHA con Cloudflare Turnstile, para frenar registros automatizados.
 *
 * Se activa SOLO si hay `WTFUCK_TURNSTILE_SECRET` configurado; sin eso (dev, o un
 * servidor que no lo use) no exige nada y todo sigue igual. La `sitekey` es
 * publica: se le pasa al cliente para que dibuje el widget. El `secret` nunca
 * sale del servidor.
 *
 * Se verifica del lado del servidor a proposito: un token que solo comprobara el
 * navegador no frena a un script que habla directo con la API.
 */
object Turnstile {
    private val secret: String? = System.getenv("WTFUCK_TURNSTILE_SECRET")?.ifBlank { null }
    val sitekey: String = System.getenv("WTFUCK_TURNSTILE_SITEKEY")?.ifBlank { null } ?: ""

    /** Si el CAPTCHA esta activo (hay secret). La web lo usa para decidir mostrarlo. */
    val activo: Boolean get() = secret != null

    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    /**
     * Verifica el token del widget. Lanza si no pasa. Sin secret configurado no
     * hace nada (CAPTCHA apagado).
     */
    fun verificar(token: String, ip: String?) {
        val s = secret ?: return
        if (token.isBlank()) throw ErrorNegocio(400, "Falta la verificacion anti-bot.")
        val cuerpo = buildString {
            append("secret=").append(enc(s))
            append("&response=").append(enc(token))
            if (!ip.isNullOrBlank()) append("&remoteip=").append(enc(ip))
        }
        val respuesta = try {
            val req = HttpRequest.newBuilder(URI("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString()).body()
        } catch (e: Exception) {
            throw ErrorNegocio(503, "No se pudo verificar el anti-bot. Reintenta en un momento.")
        }
        // La respuesta es { "success": true, ... }. Un contains alcanza: no hay
        // otro campo booleano llamado success en esta respuesta.
        if (!Regex("\"success\"\\s*:\\s*true").containsMatchIn(respuesta)) {
            throw ErrorNegocio(400, "La verificacion anti-bot no paso. Volve a intentarlo.")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
