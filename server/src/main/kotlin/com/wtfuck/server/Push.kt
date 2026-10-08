package com.wtfuck.server

import com.wtfuck.protocol.PROVEEDOR_WEBPUSH
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Modulo N · Despertar un aparato que no tiene el socket abierto.
 *
 * ## Que resuelve, y que no
 *
 * El servicio en primer plano (K.8) hizo que una llamada **en curso** sobreviva
 * a salir de la app. No resuelve el caso de la app **cerrada**: ahi no hay
 * proceso al que avisar, el socket no existe, y una llamada entrante no suena.
 * Eso no se arregla con mas codigo dentro de la app —Android mata procesos y
 * tiene razon en hacerlo—: hace falta que alguien de afuera la despierte.
 *
 * ## Lo que NO viaja en el aviso: nada
 *
 * Ni el texto, ni quien escribe, ni la conversacion. El aviso es un "tenes algo,
 * conectate" y nada mas; el telefono abre el socket y se baja sus sobres, que es
 * lo que ya hacia al arrancar.
 *
 * Es deliberado y es la diferencia entre este push y el de casi cualquier otra
 * app. Poner el texto en el payload seria entregarle el contenido a un tercero
 * justo despues de haberlo cifrado de punta a punta, y poner el nombre de quien
 * escribe le entregaria el grafo social. Lo que el proveedor aprende es que este
 * aparato recibio un aviso a esta hora, que es metadato que ya tiene de
 * cualquier app instalada.
 *
 * ## El precio, declarado
 *
 * Depender de FCM significa depender de los servicios de Google en el aparato.
 * La alternativa —un socket permanente sostenido por un servicio en primer
 * plano— gasta bateria y Android la corta cada vez mas. Es el mismo balance que
 * eligio Signal, y por las mismas razones.
 *
 * ## Si no esta configurado
 *
 * No pasa nada: `configurado` queda en false, se dice **una vez** en el log, y
 * el resto del servidor sigue igual. Es como se comporta el intermediario de
 * GIFs sin su clave: una funcion apagada no puede ser un error en cada peticion.
 */
object Push {

    private val bitacora = LoggerFactory.getLogger("push")
    private val json = Json { ignoreUnknownKeys = true }

    private val cliente: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    // ------------------------------------------------------------------
    //  Configuracion
    // ------------------------------------------------------------------

    /** Datos de la cuenta de servicio, para firmar el token de acceso. */
    private val proyecto = System.getenv("WTFUCK_FCM_PROYECTO")?.takeIf { it.isNotBlank() }
    private val correoServicio = System.getenv("WTFUCK_FCM_EMAIL")?.takeIf { it.isNotBlank() }
    private val clavePem = System.getenv("WTFUCK_FCM_CLAVE")?.takeIf { it.isNotBlank() }

    /**
     * Lo que el CLIENTE necesita para inicializar Firebase.
     *
     * Va por `GET /v1/push/config` en vez de dentro del APK, y eso es lo que
     * permite habilitar el push **sin recompilar la app**: se ponen las
     * variables en el servidor y el proximo arranque del telefono ya se
     * registra. Un `google-services.json` incrustado obligaria a publicar una
     * version nueva para cambiar de proyecto.
     *
     * Ninguno de estos valores es secreto: la `apiKey` de Firebase identifica
     * al proyecto, no autoriza nada por si sola. El secreto es la clave privada
     * de la cuenta de servicio, y esa no sale nunca del servidor.
     */
    private val appId = System.getenv("WTFUCK_FCM_APP_ID")?.takeIf { it.isNotBlank() }
    private val apiKey = System.getenv("WTFUCK_FCM_API_KEY")?.takeIf { it.isNotBlank() }
    private val remitente = System.getenv("WTFUCK_FCM_REMITENTE")?.takeIf { it.isNotBlank() }

    /** Sobreescribibles para poder PROBAR sin hablar con Google. Ver `push.mjs`. */
    private val endpointEnvio = System.getenv("WTFUCK_FCM_ENDPOINT")
    private val endpointToken = System.getenv("WTFUCK_FCM_OAUTH")
        ?: "https://oauth2.googleapis.com/token"

    /**
     * Si el SERVIDOR puede enviar. Distinto de si el cliente puede registrarse:
     * para eso hacen falta ademas [appId], [apiKey] y [remitente].
     */
    val configurado: Boolean =
        proyecto != null && correoServicio != null && clavePem != null

    /** Si el cliente tiene con que inicializar Firebase. */
    val clientePuedeRegistrarse: Boolean =
        configurado && appId != null && apiKey != null && remitente != null

    fun configCliente(): Triple<String, String, String>? =
        if (clientePuedeRegistrarse) Triple(appId!!, apiKey!!, remitente!!) else null

    fun proyectoId(): String? = proyecto

    private var avisado = false

    private fun avisarUnaVez() {
        if (avisado) return
        avisado = true
        bitacora.info(
            "Push sin configurar: falta " +
                listOfNotNull(
                    "WTFUCK_FCM_PROYECTO".takeIf { proyecto == null },
                    "WTFUCK_FCM_EMAIL".takeIf { correoServicio == null },
                    "WTFUCK_FCM_CLAVE".takeIf { clavePem == null },
                ).joinToString(", ") +
                ". Una llamada o un mensaje con la app CERRADA no van a despertar el aparato."
        )
    }

    // ------------------------------------------------------------------
    //  Coalescencia
    // ------------------------------------------------------------------

    /**
     * Cuando se despertó por ultima vez cada aparato.
     *
     * Veinte mensajes seguidos en un grupo no son veinte avisos: el aviso no
     * lleva contenido, asi que el segundo no agrega nada que el primero no haya
     * conseguido ya —que el telefono se conecte y baje TODO lo pendiente—.
     * Sin esto, un grupo activo produce una peticion HTTP por mensaje y por
     * aparato, y el proveedor empieza a limitar.
     */
    private val ultimoAviso = ConcurrentHashMap<UUID, AtomicLong>()

    private const val VENTANA_MS = 12_000L

    /**
     * Cuatro hilos y una cola de 256.
     *
     * Cuatro porque cada envio es una peticion HTTP que pasa casi todo su
     * tiempo esperando, no calculando: mas hilos no mandan mas rapido, solo
     * abren mas conexiones a la vez contra el mismo proveedor, que es como se
     * llega a que empiece a limitar.
     *
     * La cola tiene tope y la politica es DESCARTAR el que llega. Ver
     * `despertar`: perder un aviso cuesta que un telefono se entere al
     * reconectar; no perderlo nunca cuesta memoria sin techo.
     */
    private val piscina = ThreadPoolExecutor(
        2, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        { r -> Thread(r, "push").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Para la prueba: cuantos envios se intentaron de verdad. */
    private val enviados = AtomicLong(0)

    fun enviadosHastaAhora(): Long = enviados.get()

    // ------------------------------------------------------------------
    //  Envio
    // ------------------------------------------------------------------

    /**
     * Despierta un aparato. No bloquea al que llama y nunca lanza.
     *
     * Un push que falla **no puede** romper la entrega: el sobre ya esta en la
     * base y el telefono lo va a tomar al reconectar. El push solo adelanta ese
     * momento.
     */
    fun despertar(dispositivoId: UUID) {
        if (!configurado && !WebPush.configurado) {
            avisarUnaVez()
            return
        }
        val marca = ultimoAviso.computeIfAbsent(dispositivoId) { AtomicLong(0) }
        val ahora = System.currentTimeMillis()
        val previo = marca.get()
        if (ahora - previo < VENTANA_MS) return
        if (!marca.compareAndSet(previo, ahora)) return

        // Fuera del hilo que entrega: esto se llama desde el camino de un
        // mensaje y una peticion HTTP a un tercero no puede meterse en el
        // tiempo de respuesta de quien envio.
        //
        // Va a un pool ACOTADO y no a un hilo nuevo por aviso. La primera
        // version hacia `Thread {}` por dispositivo, y eso escala con la peor
        // variable posible: un mensaje a un grupo de cincuenta personas
        // desconectadas son cincuenta hilos a la vez, cada uno esperando
        // hasta diez segundos a un servidor ajeno.
        runCatching { piscina.execute { enviarConCuidado(dispositivoId) } }
            .onFailure {
                // La cola esta llena: se DESCARTA, y esta bien. El sobre ya
                // esta en la base y el telefono lo toma al reconectar; el push
                // solo adelanta ese momento. Encolar sin limite para no perder
                // un adelanto es como se tumba un servidor.
                bitacora.warn("Cola de push llena: se descarta el aviso a $dispositivoId")
                ultimoAviso[dispositivoId]?.set(0)
            }
    }

    private fun enviarConCuidado(dispositivoId: UUID) {
        runCatching { enviarAhora(dispositivoId) }
            .onFailure { bitacora.warn("Push a $dispositivoId fallo: ${it.message}") }
    }

    private fun enviarAhora(dispositivoId: UUID) {
        val destino = Repo.tokenPush(dispositivoId) ?: return
        if (destino.proveedor == PROVEEDOR_WEBPUSH) {
            if (!WebPush.configurado) return
            enviados.incrementAndGet()
            when (WebPush.enviar(destino.token)) {
                WebPush.Resultado.ENTREGADO -> Repo.pushOk(dispositivoId)
                WebPush.Resultado.YA_NO_EXISTE -> {
                    bitacora.info("Suscripcion web de $dispositivoId ya no existe: se borra")
                    Repo.borrarTokenPush(dispositivoId)
                }
                WebPush.Resultado.FALLO -> Repo.pushFallo(dispositivoId)
            }
            return
        }
        if (!configurado) return
        val acceso = tokenDeAcceso() ?: return
        enviados.incrementAndGet()

        val url = endpointEnvio
            ?: "https://fcm.googleapis.com/v1/projects/$proyecto/messages:send"

        // El cuerpo entero, y lo que dice de este proyecto: `data` con UNA clave
        // que no significa nada. No hay `notification`, asi que el sistema no
        // dibuja nada solo y la app decide -ya descifrado- si hay algo que
        // mostrar. `priority: high` es lo que permite despertar un proceso
        // muerto; sin eso el aviso espera a la proxima ventana de
        // mantenimiento, que para una llamada es inservible.
        val cuerpo = """
            {"message":{"token":"${escapar(destino.token)}",
             "data":{"w":"1"},
             "android":{"priority":"high"}}}
        """.trimIndent()

        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $acceso")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
            .build()

        val resp = cliente.send(req, HttpResponse.BodyHandlers.ofString())
        when {
            resp.statusCode() in 200..299 -> Repo.pushOk(dispositivoId)

            // 404 y UNREGISTERED significan lo mismo: ese token ya no existe
            // -app desinstalada, datos borrados-. Se borra en vez de contarlo
            // como fallo, porque no va a volver a funcionar nunca.
            resp.statusCode() == 404 || resp.body().contains("UNREGISTERED") -> {
                bitacora.info("Token de push de $dispositivoId ya no existe: se borra")
                Repo.borrarTokenPush(dispositivoId)
            }

            else -> {
                bitacora.warn("Push a $dispositivoId: ${resp.statusCode()} ${resp.body().take(200)}")
                Repo.pushFallo(dispositivoId)
            }
        }
    }

    private fun escapar(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ------------------------------------------------------------------
    //  OAuth2 con la cuenta de servicio
    // ------------------------------------------------------------------

    @Volatile private var acceso: String? = null
    @Volatile private var accesoVence = 0L

    /**
     * El token de acceso de FCM, con cache.
     *
     * FCM HTTP v1 no acepta una clave de API: exige un token OAuth2 firmado con
     * la cuenta de servicio. Se pide uno, vale una hora, y se renueva cinco
     * minutos antes de vencer. Sin el cache seria una firma RSA y una peticion
     * HTTP extra **por cada aviso**.
     */
    private fun tokenDeAcceso(): String? {
        val ahora = System.currentTimeMillis()
        acceso?.let { if (ahora < accesoVence) return it }
        synchronized(this) {
            acceso?.let { if (ahora < accesoVence) return it }
            val nuevo = pedirToken() ?: return null
            acceso = nuevo
            accesoVence = ahora + 55 * 60 * 1000
            return nuevo
        }
    }

    private fun pedirToken(): String? {
        val jwt = firmarJwt() ?: return null
        val cuerpo = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$jwt"
        val req = HttpRequest.newBuilder(URI.create(endpointToken))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
            .build()
        val resp = cliente.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            bitacora.warn("No se pudo obtener token de FCM: ${resp.statusCode()} ${resp.body().take(200)}")
            return null
        }
        return runCatching {
            json.parseToJsonElement(resp.body()).jsonObject["access_token"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * El JWT que la cuenta de servicio cambia por un token de acceso.
     *
     * Se firma a mano con `java.security` y no con una libreria de JWT: son tres
     * base64url y una firma RS256, y agregar una dependencia de JWT al servidor
     * para eso trae su propio parser, su propio modelo de claims y su propia
     * superficie de fallo.
     */
    private fun firmarJwt(): String? = runCatching {
        val ahora = System.currentTimeMillis() / 1000
        val cabecera = b64url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64url(
            ("""{"iss":"$correoServicio",""" +
                """"scope":"https://www.googleapis.com/auth/firebase.messaging",""" +
                """"aud":"$endpointToken","iat":$ahora,"exp":${ahora + 3600}}""").toByteArray()
        )
        val firmable = "$cabecera.$claims"

        val der = clavePem!!
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            // La clave suele venir de un JSON con \n literales; se aceptan las
            // dos formas para que copiarla no sea un juego de escapes.
            .replace("\\n", "")
            .replace(Regex("\\s"), "")
        val llave = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(der)))

        val firma = Signature.getInstance("SHA256withRSA").run {
            initSign(llave)
            update(firmable.toByteArray())
            sign()
        }
        "$firmable.${b64url(firma)}"
    }.onFailure {
        bitacora.error("La clave de la cuenta de servicio no se pudo usar: ${it.message}")
    }.getOrNull()

    private fun b64url(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
