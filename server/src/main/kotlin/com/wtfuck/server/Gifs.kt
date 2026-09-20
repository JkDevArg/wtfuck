package com.wtfuck.server

import com.wtfuck.protocol.BusquedaGifs
import com.wtfuck.protocol.FORMA_GIF_ID
import com.wtfuck.protocol.GifResumen
import com.wtfuck.protocol.TOPE_TITULO_GIF
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Intermediario de GIFs.
 *
 * La app nunca habla con el proveedor: ver `protocol/Gifs.kt` para el por que.
 * Aqui esta el como.
 *
 * Se usa el cliente HTTP del JDK y no uno nuevo: para tres peticiones GET
 * agregar una libreria de cliente al servidor no compra nada.
 */
object Gifs {

    private val bitacora = LoggerFactory.getLogger("gifs")

    private val clave: String? = System.getenv("WTFUCK_GIPHY_KEY")?.takeIf { it.isNotBlank() }

    /**
     * Tope de lo que se acepta proxyar.
     *
     * Sin tope, una busqueda podria hacer que el servidor descargue decenas de
     * MB por cada persona que abre el selector. Un GIF de chat pesa menos de
     * esto; lo que no entra, no vale la pena.
     */
    private const val TOPE_BYTES = 6L * 1024 * 1024

    /** Clasificacion maxima. Es un mensajero institucional, no un tablon. */
    private const val CLASIFICACION = "pg-13"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val configurado: Boolean get() = clave != null

    private fun cod(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    // ------------------------------------------------------------------
    //  Busqueda
    // ------------------------------------------------------------------

    fun buscar(consulta: String, limite: Int): BusquedaGifs {
        val k = clave ?: return BusquedaGifs(
            aviso = "El buscador de GIFs no esta configurado en este servidor.",
        )
        val n = limite.coerceIn(1, 40)
        val url = if (consulta.isBlank()) {
            "https://api.giphy.com/v1/gifs/trending?api_key=$k&limit=$n&rating=$CLASIFICACION"
        } else {
            "https://api.giphy.com/v1/gifs/search?api_key=$k&q=${cod(consulta)}&limit=$n&rating=$CLASIFICACION"
        }

        val cuerpo = pedirTexto(url) ?: return BusquedaGifs(
            aviso = "No se pudo consultar el buscador de GIFs.",
        )
        return runCatching {
            val datos = json.parseToJsonElement(cuerpo).jsonObject["data"]?.jsonArray ?: return@runCatching BusquedaGifs()
            BusquedaGifs(resultados = datos.mapNotNull { fila -> aResumen(fila.jsonObject) })
        }.getOrElse {
            bitacora.warn("Respuesta del buscador ilegible: {}", it.message)
            BusquedaGifs(aviso = "El buscador devolvio algo inesperado.")
        }
    }

    /**
     * Una fila del buscador, o `null` si no se puede creer.
     *
     * Se valida **en la ingesta** y no solo al usarla: un id con forma rara no
     * llega a la lista, asi que tampoco llega al cliente ni vuelve luego en un
     * `GET /v1/gifs/{id}/bytes`. Filtrar donde entra el dato ajeno es mas
     * barato que acordarse de filtrarlo en cada sitio donde se usa.
     */
    private fun aResumen(o: kotlinx.serialization.json.JsonObject): GifResumen? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull() ?: return null
        if (!FORMA_GIF_ID.matches(id)) {
            bitacora.warn("El buscador devolvio un id con forma rara, se descarta")
            return null
        }
        val img = o["images"]?.jsonObject?.get("downsized")?.jsonObject
        return GifResumen(
            id = id,
            titulo = o["title"]?.jsonPrimitive?.contentOrNull().orEmpty().take(TOPE_TITULO_GIF),
            ancho = img?.get("width")?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0,
            alto = img?.get("height")?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0,
            bytes = img?.get("size")?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 0,
        )
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        content.takeIf { it.isNotBlank() && it != "null" }

    // ------------------------------------------------------------------
    //  Bytes
    // ------------------------------------------------------------------

    /**
     * Trae los bytes de un GIF.
     *
     * `previa` pide la version chica, para la rejilla del selector; sin ella
     * abrir el selector descargaria los GIF completos de 24 resultados.
     */
    fun bytes(id: String, previa: Boolean): ByteArray? {
        // Aunque la ruta ya lo comprueba: esta funcion arma una URL con la
        // clave de la API detras, y no puede depender de que quien la llame se
        // haya acordado de validar.
        if (!FORMA_GIF_ID.matches(id)) return null
        val k = clave ?: return null
        val meta = pedirTexto("https://api.giphy.com/v1/gifs/$id?api_key=$k") ?: return null
        val url = runCatching {
            val imgs = json.parseToJsonElement(meta).jsonObject["data"]!!.jsonObject["images"]!!.jsonObject
            val cual = if (previa) "fixed_width_small" else "downsized"
            (imgs[cual] ?: imgs["downsized"])!!.jsonObject["url"]!!.jsonPrimitive.content
        }.getOrElse {
            bitacora.warn("GIF {} sin URL utilizable: {}", id, it.message)
            return null
        }

        // La URL de descarga sale de la RESPUESTA de un tercero, asi que es el
        // tercero quien elige a donde va este servidor. Sin lista blanca, una
        // respuesta manipulada convierte esto en una peticion a donde sea,
        // incluida la red interna.
        if (!hostPermitido(url)) {
            bitacora.warn("GIF {} apuntaba a un host no permitido, se descarta", id)
            return null
        }

        val resp = runCatching {
            // `sinRedirecciones` y no el cliente normal: seguir un redirect
            // automaticamente deja sin efecto la lista blanca de arriba, porque
            // el salto ya no pasa por ella. Un GIF que redirige no se baja, y
            // eso es correcto: los de Giphy no redirigen.
            sinRedirecciones.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        }.getOrElse {
            bitacora.warn("No se pudo traer el GIF {}: {}", id, it.message)
            return null
        }
        if (resp.statusCode() !in 200..299) return null
        val datos = resp.body()
        if (datos.size > TOPE_BYTES) {
            bitacora.info("GIF {} descartado por tamano: {} bytes", id, datos.size)
            return null
        }
        return datos
    }

    /**
     * Cliente aparte para bajar el GIF, sin seguir redirecciones.
     *
     * El de arriba las sigue, que es lo normal para hablar con una API. Aqui no
     * sirve: la comprobacion de host se hace sobre la URL que dio el tercero, y
     * un redirect la deja atras.
     */
    private val sinRedirecciones: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Si esa URL es de donde Giphy sirve sus imagenes.
     *
     * Lista blanca y no lista negra, por lo de siempre: una lista negra hay que
     * acertarla entera y una blanca solo hay que ampliarla cuando algo
     * legitimo deje de funcionar.
     *
     * Se exige HTTPS ademas del host. Un `http://` de Giphy tampoco deberia
     * existir, y aceptarlo seria dejar la puerta a que un intermediario
     * cualquiera decida que bytes baja este servidor.
     */
    internal fun hostPermitido(url: String): Boolean {
        val u = runCatching { URI.create(url) }.getOrNull() ?: return false
        if (!u.scheme.equals("https", ignoreCase = true)) return false
        val host = u.host?.lowercase() ?: return false
        return host == "giphy.com" || host.endsWith(".giphy.com")
    }

    private fun pedirTexto(url: String): String? = runCatching {
        val r = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (r.statusCode() in 200..299) r.body() else {
            bitacora.warn("El buscador respondio {}", r.statusCode())
            null
        }
    }.getOrElse {
        bitacora.warn("Fallo la consulta al buscador: {}", it.message)
        null
    }
}
