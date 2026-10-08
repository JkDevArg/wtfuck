package com.wtfuck.server

import com.wtfuck.protocol.RUTA_WEB
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.io.File
import java.net.URI

/**
 * La version web, servida desde el MISMO origen que la API.
 *
 * ## Por que aqui y no en otro dominio
 *
 * En otro origen, cada llamada a la API pasaria por CORS, y no hay CORS en
 * Ktor, ni en Caddy ni en el almacen: habria que abrirlo en las tres capas y
 * exponer cabeceras como `X-Hora`. Mismo origen es lo que ya hace `/consola`, y
 * no abre nada.
 *
 * ## De donde salen los archivos
 *
 * De `WTFUCK_WEB_DIR`: lo que se armo en la PC y se subio, igual que el APK
 * (`despliegue/publicar-web.sh`). El servidor no compila nada. Sin la
 * variable, `/web` responde 404 con el motivo.
 *
 * La carpeta se lee en cada pedido, no al arrancar: publicar una version
 * nueva es reemplazar los archivos, sin reiniciar el servidor.
 *
 * ## Las cabeceras, y por que tan estrictas
 *
 * En el navegador, el codigo lo entrega este servidor cada vez. Una CSP
 * estricta no evita que el servidor sirva otro codigo, que es el precio
 * declarado en docs/12-VERSION-WEB.md, pero si evita que un tercero meta el
 * suyo por una inyeccion:
 *
 * - `script-src 'self' 'wasm-unsafe-eval'`: solo scripts de aqui, y compilar el
 *   WebAssembly de libsignal. Nada en linea, nada de `eval`.
 * - `connect-src 'self'` mas el almacen de adjuntos: la pagina solo habla con
 *   esta API (tambien por WebSocket) y con el almacen.
 * - `frame-ancestors 'none'`: nadie la mete en un iframe para engañar clics.
 * - `Cross-Origin-Opener-Policy`: otra ventana no conserva una referencia a
 *   esta.
 *
 * Los archivos de `assets/` llevan un hash en el nombre (los arma Vite), asi
 * que se guardan en cache para siempre. `index.html` no se guarda nunca: es el
 * que dice que version de esos archivos cargar.
 */
object Web {

    private val carpeta: File? = System.getenv("WTFUCK_WEB_DIR")
        ?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }

    /**
     * El origen del almacen de adjuntos: a donde la pagina sube y de donde baja
     * los archivos cifrados, con las URLs firmadas. Es el MISMO host que firma
     * `Almacen` (el publico, o el interno si no hay publico, como en
     * desarrollo): si no coincidieran, la CSP bloquearia justo esas URLs.
     */
    private val origenAlmacen: String? = (
        System.getenv("WTFUCK_S3_PUBLICO")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("WTFUCK_S3_URL")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "http://127.0.0.1:9000"
        )
        .let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf { it.scheme != null && it.authority != null }
        ?.let { "${it.scheme}://${it.authority}" }

    val CSP: String = listOf(
        "default-src 'none'",
        "script-src 'self' 'wasm-unsafe-eval'",
        "style-src 'self'",
        "img-src 'self' blob: data:",
        "media-src 'self' blob:",
        "font-src 'self'",
        "connect-src 'self'" + (origenAlmacen?.let { " $it" } ?: ""),
        "worker-src 'self'",
        "manifest-src 'self'",
        "base-uri 'none'",
        "form-action 'none'",
        "frame-ancestors 'none'",
    ).joinToString("; ")

    fun Route.rutasWeb() {
        // Sin la barra final, las rutas relativas de la pagina se resolverian
        // contra la raiz del dominio.
        get(RUTA_WEB) { call.respondRedirect("$RUTA_WEB/", permanent = true) }

        val dir = carpeta
        if (dir == null) {
            route(RUTA_WEB) {
                get("{...}") {
                    call.respondText(
                        "La version web no esta publicada en este servidor.",
                        ContentType.Text.Plain,
                        HttpStatusCode.NotFound,
                    )
                }
            }
            return
        }

        staticFiles(RUTA_WEB, dir, index = "index.html") {
            contentType { f ->
                // Sin `application/wasm`, el navegador no compila en streaming.
                when (f.extension) {
                    "wasm" -> ContentType("application", "wasm")
                    // W5d: el manifiesto de la PWA. Chrome lo lee igual con otro
                    // tipo, pero el estandar pide este.
                    "webmanifest" -> ContentType("application", "manifest+json")
                    else -> null
                }
            }
            modify { f, call ->
                val h = call.response
                h.header("Content-Security-Policy", CSP)
                h.header("X-Content-Type-Options", "nosniff")
                h.header("Referrer-Policy", "no-referrer")
                h.header("X-Frame-Options", "DENY")
                h.header("Cross-Origin-Opener-Policy", "same-origin")
                h.header("Cross-Origin-Resource-Policy", "same-origin")
                h.header(
                    "Permissions-Policy",
                    "camera=(self), microphone=(self), geolocation=(), payment=(), usb=(), bluetooth=()",
                )
                h.header(
                    HttpHeaders.CacheControl,
                    if (f.parentFile?.name == "assets") "public, max-age=31536000, immutable" else "no-cache",
                )
            }
        }
    }
}
