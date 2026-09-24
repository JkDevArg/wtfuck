package com.wtfuck.server

import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.BucketExistsArgs
import io.minio.Http
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Almacen de objetos para los adjuntos.
 *
 * Dos decisiones que vale la pena dejar escritas:
 *
 * 1. Los bytes NO pasan por este servidor. Se firman URLs y el cliente sube y
 *    baja directo contra el almacen. Un servidor de aplicacion haciendo de
 *    tuberia para archivos de 64 MB se convierte en el cuello de botella.
 *
 * 2. La clave del objeto es aleatoria, no derivada del nombre del archivo ni
 *    del id del mensaje. Si se derivara, conocer una clave permitiria adivinar
 *    las demas.
 */
object Almacen {

    private val log = LoggerFactory.getLogger("Almacen")
    private val rnd = SecureRandom()

    private const val BUCKET = "wtfuck-adjuntos"

    /** Las URLs firmadas caducan pronto a proposito: son un pase, no un enlace. */
    private const val MINUTOS_SUBIDA = 15
    private const val MINUTOS_DESCARGA = 60

    private val cliente: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(System.getenv("WTFUCK_S3_URL") ?: "http://127.0.0.1:9000")
            .credentials(
                System.getenv("WTFUCK_S3_USER") ?: "wtfuck",
                System.getenv("WTFUCK_S3_PASS") ?: "wtfuck_dev_minio",
            )
            .build()
    }

    /**
     * OJO: la URL del almacen tiene que ser la MISMA que usara el cliente.
     *
     * La firma SigV4 incluye el header Host, asi que reescribir el host de una
     * URL ya firmada la invalida (403 del almacen). Lo intente y no funciona.
     * Si el cliente llega por otro host -por ejemplo un emulador con
     * `adb reverse`-, hay que configurar WTFUCK_S3_URL con ESE host, no
     * traducirlo despues.
     */

    @Volatile private var listo = false

    @Synchronized
    fun iniciar() {
        if (listo) return
        runCatching {
            val existe = cliente.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())
            if (!existe) {
                cliente.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
                log.info("Bucket $BUCKET creado.")
            }
            listo = true
        }.onFailure {
            // No se tumba el servidor por esto: el chat de texto debe seguir
            // funcionando aunque el almacen de archivos este caido.
            log.warn("Almacen no disponible: ${it.message}. Los adjuntos fallaran hasta que vuelva.")
        }
    }

    fun disponible(): Boolean = listo

    /** Clave aleatoria y opaca para un objeto nuevo. */
    fun nuevaClave(clase: String): String {
        val aleatorio = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(18).also { rnd.nextBytes(it) })
        // El prefijo por clase solo sirve para poder mirar el bucket y
        // entender que hay; no se usa para autorizar nada.
        return "$clase/$aleatorio"
    }

    fun urlSubida(objeto: String): String = firmar(objeto, Http.Method.PUT, MINUTOS_SUBIDA)

    fun urlDescarga(objeto: String): String = firmar(objeto, Http.Method.GET, MINUTOS_DESCARGA)

    private fun firmar(objeto: String, metodo: Http.Method, minutos: Int): String {
        if (!listo) throw ErrorNegocio(503, "El almacen de archivos no esta disponible.")
        val url = cliente.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(metodo)
                .bucket(BUCKET)
                .`object`(objeto)
                .expiry(minutos, TimeUnit.MINUTES)
                .build()
        )
        return url
    }

    /** Bytes reales del objeto, para comprobar lo que el cliente declaro. */
    fun tamanoReal(objeto: String): Long? = runCatching {
        cliente.statObject(StatObjectArgs.builder().bucket(BUCKET).`object`(objeto).build()).size()
    }.getOrNull()

    /**
     * Los primeros bytes de un objeto, para comprobar su firma real.
     *
     * ## Esto no rompe la regla de arriba
     *
     * La regla dice que **los bytes de los clientes** no pasan por este
     * servidor, y la razon es no ser la tuberia de archivos de 64 MB. Aqui se
     * leen doce bytes entre el servidor y el almacen, que estan al lado, y
     * para responder una pregunta que solo el servidor puede responder: si el
     * archivo es lo que el cliente dijo que era.
     *
     * Solo tiene sentido en lo que NO va cifrado -las imagenes de un canal
     * publico-. De un adjunto cifrado los primeros bytes son ruido.
     */
    fun primerosBytes(objeto: String, cuantos: Int): ByteArray? = runCatching {
        if (!listo) return null
        cliente.getObject(
            GetObjectArgs.builder().bucket(BUCKET).`object`(objeto)
                .offset(0L).length(cuantos.toLong()).build()
        ).use { it.readNBytes(cuantos) }
    }.getOrNull()

    fun borrar(objeto: String) {
        runCatching {
            cliente.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).`object`(objeto).build())
        }.onFailure { log.warn("No se pudo borrar $objeto: ${it.message}") }
    }
}
