package com.wtfuck.server

import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Version web, W4c · Despertar un navegador cerrado (Web Push, RFC 8030).
 *
 * Es el mismo papel que FCM para la app ([Push]): un "tienes algo, conectate"
 * para el aparato que no tiene el socket abierto. Lo llama [Push.despertar]
 * cuando el token guardado es de proveedor `webpush`.
 *
 * ## El aviso va VACIO
 *
 * Web Push deja mandar un cuerpo cifrado para el navegador (RFC 8291). Aqui no
 * se manda ninguno: el POST al servicio de push no lleva cuerpo, y por eso no
 * hace falta ni la clave `p256dh` ni el secreto `auth` de la suscripcion. Lo
 * que aprende el servicio de push (Google, Mozilla, Apple, Microsoft, segun el
 * navegador) es que este navegador recibio un aviso a esta hora. No quien
 * escribe, ni en que conversacion, ni cuanto.
 *
 * El service worker de la web (`web/app/public/sw.js`) muestra entonces un
 * texto fijo, y la pagina, al abrirse, baja y descifra lo pendiente. Es la
 * misma regla que en la app: la notificacion nunca lleva el texto.
 *
 * ## VAPID (RFC 8292)
 *
 * El servicio de push solo acepta avisos para una suscripcion si vienen
 * firmados por la clave con la que el navegador se suscribio. Esa clave es un
 * par P-256 del servidor:
 *
 *  - `WTFUCK_VAPID_PUBLICA`: 65 bytes sin comprimir, base64url. Se le da al
 *    navegador por `GET /v1/push/web`.
 *  - `WTFUCK_VAPID_PRIVADA`: los 32 bytes del escalar, base64url. Es el
 *    secreto: con ella cualquiera podria mandarle avisos (vacios o no) a todos
 *    los navegadores suscritos. No sale nunca del servidor.
 *  - `WTFUCK_VAPID_CONTACTO`: `mailto:` o `https:` que el servicio de push usa
 *    para avisar si este servidor se porta mal. Apple lo exige.
 *
 * Se generan con `node despliegue/generar-vapid.mjs`. Cambiarlas invalida
 * TODAS las suscripciones: los navegadores tendrian que volver a suscribirse
 * (la pagina lo hace sola al abrirse).
 *
 * ## A quien se le hace el POST
 *
 * La URL del endpoint la manda el cliente, y el servidor le hace un POST: sin
 * control, eso es una SSRF de manual -"mandame avisos a http://10.0.0.5/admin"-.
 * Por eso solo se aceptan endpoints HTTPS de los servicios de push conocidos,
 * se valida al registrar Y al enviar, y el cliente HTTP no sigue redirecciones.
 * `WTFUCK_WEBPUSH_HOSTS_EXTRA` (`host:puerto`, separados por coma) agrega hosts
 * para las pruebas; ver `pruebas/webpush.mjs`.
 */
object WebPush {

    private val bitacora = LoggerFactory.getLogger("webpush")

    /** Sin redirecciones: un 30x hacia una IP interna seria la SSRF por la puerta de atras. */
    private val cliente: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    // ------------------------------------------------------------------
    //  Claves
    // ------------------------------------------------------------------

    private val publicaB64 = System.getenv("WTFUCK_VAPID_PUBLICA")?.trim()?.takeIf { it.isNotEmpty() }
    private val privadaB64 = System.getenv("WTFUCK_VAPID_PRIVADA")?.trim()?.takeIf { it.isNotEmpty() }
    private val contacto = System.getenv("WTFUCK_VAPID_CONTACTO")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "mailto:wtfuck@localhost"

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    private class Par(val privada: PrivateKey, val publica: PublicKey)

    /**
     * El par, o null si falta o no sirve.
     *
     * Se comprueba que las dos mitades sean PAREJA firmando y verificando una
     * vez: copiar mal una de las dos variables es el error mas probable, y sin
     * esto se descubriria recien cuando todos los servicios de push contestaran
     * 403 sin decir por que.
     */
    private val par: Par? = run {
        val pubTexto = publicaB64 ?: return@run null
        val privTexto = privadaB64 ?: return@run null
        runCatching {
            val pub = b64d(pubTexto)
            val priv = b64d(privTexto)
            require(pub.size == 65 && pub[0] == 4.toByte()) { "la publica no es un punto P-256 sin comprimir (65 bytes)" }
            require(priv.size == 32) { "la privada no tiene 32 bytes" }
            val kf = KeyFactory.getInstance("EC")
            val privada = kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, priv), p256))
            val punto = ECPoint(BigInteger(1, pub.copyOfRange(1, 33)), BigInteger(1, pub.copyOfRange(33, 65)))
            val publica = kf.generatePublic(ECPublicKeySpec(punto, p256))
            val prueba = "wtfuck-vapid".toByteArray()
            val firma = Signature.getInstance(ALGORITMO).run { initSign(privada); update(prueba); sign() }
            val pareja = Signature.getInstance(ALGORITMO).run { initVerify(publica); update(prueba); verify(firma) }
            require(pareja) { "la publica y la privada no son pareja" }
            Par(privada, publica)
        }.onFailure {
            bitacora.error("Claves VAPID inservibles (${it.message}): Web Push queda apagado.")
        }.getOrNull()
    }

    val configurado: Boolean get() = par != null

    /** Lo que se le da al navegador para `pushManager.subscribe`. */
    fun clavePublica(): String? = publicaB64.takeIf { configurado }

    init {
        if (par != null && System.getenv("WTFUCK_VAPID_CONTACTO").isNullOrBlank()) {
            bitacora.warn("Sin WTFUCK_VAPID_CONTACTO: se usa $contacto. Apple rechaza los avisos sin un contacto real.")
        }
    }

    // ------------------------------------------------------------------
    //  A quien se le puede hacer el POST
    // ------------------------------------------------------------------

    /**
     * Los servicios de push de los navegadores. Un nombre con punto delante
     * admite subdominios (Microsoft y Apple reparten por region).
     */
    private val HOSTS = listOf(
        "fcm.googleapis.com",                 // Chrome, Edge en Android, Samsung Internet, Opera, Brave
        "updates.push.services.mozilla.com",  // Firefox
        "web.push.apple.com", ".push.apple.com", // Safari
        ".notify.windows.com",                // Edge en Windows
    )

    private val hostsExtra: Set<String> = System.getenv("WTFUCK_WEBPUSH_HOSTS_EXTRA")
        ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()

    fun endpointValido(endpoint: String): Boolean {
        val u = runCatching { URI(endpoint) }.getOrNull() ?: return false
        if (u.rawUserInfo != null || u.rawFragment != null) return false
        val host = u.host?.lowercase() ?: return false
        if (hostsExtra.isNotEmpty() && "$host:${u.port}" in hostsExtra && u.scheme in setOf("http", "https")) {
            return true
        }
        if (u.scheme != "https" || (u.port != -1 && u.port != 443)) return false
        return HOSTS.any { if (it.startsWith(".")) host.endsWith(it) else host == it }
    }

    // ------------------------------------------------------------------
    //  Envio
    // ------------------------------------------------------------------

    enum class Resultado { ENTREGADO, YA_NO_EXISTE, FALLO }

    /**
     * Un aviso vacio a [endpoint]. Lo llama [Push] desde su piscina de hilos, asi
     * que puede bloquear; nunca desde el camino de un mensaje.
     */
    fun enviar(endpoint: String): Resultado {
        val p = par ?: return Resultado.FALLO
        // Un endpoint que se guardo antes de achicar la lista, o que se cambio
        // en la base a mano: se borra en vez de reintentarlo para siempre.
        if (!endpointValido(endpoint)) return Resultado.YA_NO_EXISTE

        val u = URI(endpoint)
        val audiencia = "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
        val req = HttpRequest.newBuilder(u)
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "vapid t=${jwt(audiencia, p)}, k=$publicaB64")
            // Un dia: si el navegador esta apagado mas tiempo, al abrirse baja
            // todo lo pendiente igual; el aviso solo adelantaba ese momento.
            .header("TTL", "86400")
            // Alta: es lo que despierta a un movil en ahorro de energia. Mismo
            // criterio que `priority: high` en FCM.
            .header("Urgency", "high")
            // Con el mismo Topic, el servicio de push REEMPLAZA el aviso que
            // todavia no entrego en vez de acumularlo: veinte mensajes con el
            // navegador apagado son un aviso al prenderlo, no veinte.
            .header("Topic", "avisos")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val resp = cliente.send(req, HttpResponse.BodyHandlers.ofString())
        return when (resp.statusCode()) {
            in 200..299 -> Resultado.ENTREGADO
            // La suscripcion vencio o el navegador se dio de baja.
            404, 410 -> Resultado.YA_NO_EXISTE
            else -> {
                bitacora.warn("Web Push a ${u.host}: ${resp.statusCode()} ${resp.body().take(200)}")
                Resultado.FALLO
            }
        }
    }

    // ------------------------------------------------------------------
    //  El JWT de VAPID
    // ------------------------------------------------------------------

    private val jwts = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Un JWT ES256 por servicio de push (la audiencia es su origen), con cache.
     *
     * Vale 12 horas -el maximo que aceptan es 24- y se renueva con una de
     * margen. Sin el cache seria una firma ECDSA por aviso.
     */
    private fun jwt(audiencia: String, p: Par): String {
        val ahora = System.currentTimeMillis() / 1000
        jwts[audiencia]?.let { (t, vence) -> if (ahora < vence - 3600) return t }
        val vence = ahora + 12 * 3600
        val cabecera = b64e("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64e(
            """{"aud":"${escapar(audiencia)}","exp":$vence,"sub":"${escapar(contacto)}"}""".toByteArray()
        )
        val firmable = "$cabecera.$claims"
        // P1363: la firma como R||S de 32 bytes cada uno, que es lo que pide JWS.
        // El "SHA256withECDSA" a secas devuelve DER y los servicios lo rechazan.
        val firma = Signature.getInstance(ALGORITMO).run {
            initSign(p.privada)
            update(firmable.toByteArray())
            sign()
        }
        val t = "$firmable.${b64e(firma)}"
        jwts[audiencia] = t to vence
        return t
    }

    private const val ALGORITMO = "SHA256withECDSAinP1363Format"

    private fun escapar(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun b64e(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun b64d(s: String) = Base64.getUrlDecoder().decode(s.trimEnd('='))
}
