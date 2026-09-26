package com.wtfuck.app.datos

import com.wtfuck.app.contenido.bytesDeMiniaturaCreibles
import com.wtfuck.app.contenido.miniaturaCreible
import com.wtfuck.app.contenido.muestreoPara
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.wtfuck.protocol.ClaseAdjunto
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lo que se sabe de un archivo ANTES de subirlo.
 *
 * Todo esto se declara al reservar para que el servidor pueda rechazar por
 * clase, tamano o cuota sin haber transferido un byte.
 */
data class DatosArchivo(
    val nombre: String,
    val mime: String,
    val bytes: Long,
    val ancho: Int = 0,
    val alto: Int = 0,
    val duracionMs: Int = 0,
)

object Media {

    /**
     * Lado mayor de la miniatura que viaja en el sobre.
     *
     * La miniatura NO es una optimizacion: es lo que hace que una foto se vea
     * al instante en 3G. Llega dentro del mensaje, se dibuja de inmediato, y el
     * archivo completo se descarga despues -o nunca, si la persona no lo abre.
     */
    private const val LADO_MINIATURA = 240

    /** Tope de la miniatura ya codificada. Pasado esto se baja la calidad. */
    private const val TOPE_MINIATURA = 20 * 1024

    // ------------------------------------------------------------------
    //  Lectura del URI
    // ------------------------------------------------------------------

    /**
     * Nombre, tipo y tamano de un `content://`.
     *
     * El nombre se lee del proveedor y no de la ruta: un URI de galeria no
     * tiene ruta, y el nombre del archivo es lo unico que la persona reconoce
     * cuando recibe un documento.
     */
    fun datosDe(ctx: Context, uri: Uri, clase: String): DatosArchivo {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        var nombre = "archivo"
        var bytes = 0L

        // Una nota de voz recien grabada llega como file:// y no tiene
        // proveedor que responda la consulta: se lee del archivo directo.
        if (uri.scheme == "file") {
            uri.path?.let { ruta ->
                val f = java.io.File(ruta)
                if (f.exists()) {
                    nombre = f.name
                    bytes = f.length()
                }
            }
        }

        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iNombre = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iTam = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (iNombre >= 0 && !c.isNull(iNombre)) nombre = c.getString(iNombre)
                if (iTam >= 0 && !c.isNull(iTam)) bytes = c.getLong(iTam)
            }
        }
        // Algunos proveedores no reportan el tamano: se mide leyendo.
        if (bytes <= 0L) {
            bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { e ->
                    var t = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = e.read(buf)
                        if (n < 0) break
                        t += n
                    }
                    t
                } ?: 0L
            }.getOrDefault(0L)
        }

        var ancho = 0
        var alto = 0
        var duracion = 0
        when (clase) {
            ClaseAdjunto.IMAGEN, ClaseAdjunto.STICKER -> {
                val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
                }
                ancho = op.outWidth.coerceAtLeast(0)
                alto = op.outHeight.coerceAtLeast(0)
            }
            ClaseAdjunto.VIDEO, ClaseAdjunto.AUDIO, ClaseAdjunto.NOTA_VOZ -> {
                conMetadatos(ctx, uri) { m ->
                    duracion = m.extract(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ancho = m.extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    alto = m.extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                }
            }
        }
        return DatosArchivo(nombre, mime, bytes, ancho, alto, duracion)
    }

    private fun MediaMetadataRetriever.extract(clave: Int): Int =
        extractMetadata(clave)?.toIntOrNull() ?: 0

    private inline fun conMetadatos(ctx: Context, uri: Uri, bloque: (MediaMetadataRetriever) -> Unit) {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(ctx, uri)
            bloque(m)
        } catch (e: Exception) {
            android.util.Log.w("Media", "Sin metadatos de $uri: ${e.message}")
        } finally {
            runCatching { m.release() }
        }
    }

    // ------------------------------------------------------------------
    //  Miniatura
    // ------------------------------------------------------------------

    /** Miniatura en base64 lista para el sobre, o "" si no aplica. */
    fun miniaturaDe(ctx: Context, uri: Uri, clase: String): String {
        val bmp = when (clase) {
            ClaseAdjunto.IMAGEN, ClaseAdjunto.STICKER -> imagenReducida(ctx, uri)
            ClaseAdjunto.VIDEO -> primerFotograma(ctx, uri)
            else -> null
        } ?: return ""

        // Se empieza en 70 y se baja si no entra. Un JPEG de 240 px casi
        // siempre entra en el primer intento; el bucle es para las excepciones.
        var calidad = 70
        var datos: ByteArray
        do {
            datos = ByteArrayOutputStream().use { s ->
                bmp.compress(Bitmap.CompressFormat.JPEG, calidad, s)
                s.toByteArray()
            }
            calidad -= 20
        } while (datos.size > TOPE_MINIATURA && calidad >= 20)

        bmp.recycle()
        return Base64.encodeToString(datos, Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------
    //  Foto de perfil
    // ------------------------------------------------------------------

    /**
     * Deja una imagen lista para subir: se **comprime hasta que entra**.
     *
     * ## Por que no se rechaza
     *
     * Antes se leia el archivo y, si pesaba mas que el tope, se mostraba "la
     * imagen pesa 3204 KB y el limite es 512 KB". Eso le pasa el problema a
     * quien no tiene como resolverlo: nadie tiene a mano una herramienta para
     * achicar una foto, y el telefono —que si la tiene— se estaba negando a
     * usarla.
     *
     * Una foto de la camara de cualquier telefono de hoy pasa los 3 MB. O sea
     * que el camino normal —elegir la foto que uno acaba de sacarse— fallaba
     * siempre.
     *
     * ## Como entra
     *
     * Primero se baja la RESOLUCION, despues la calidad. En ese orden y no al
     * reves: una foto de 4000 px comprimida a calidad 20 se ve peor, y pesa
     * mas, que la misma foto a 1280 px con calidad 85. El tamano de un JPEG lo
     * manda la cantidad de pixeles mucho antes que la calidad.
     *
     * `inSampleSize` decodifica ya reducido, asi que la de 4000 px nunca llega
     * entera a memoria. En un telefono modesto, decodificarla entera para
     * despues achicarla es como se cierra una app sin decir nada.
     *
     * ## Lo que NO hace
     *
     * Quitar el limite del servidor. Ese sigue donde estaba y tiene que
     * seguir: comprimir aqui es una comodidad para quien usa la app, y un
     * servidor que acepta subidas sin tope es otra cosa. Este lado siempre
     * puede estar modificado.
     *
     * @param lado el lado mayor, en pixeles, despues de reducir.
     * @param tope el peso maximo en bytes.
     */
    fun paraSubir(ctx: Context, uri: Uri, lado: Int, tope: Int): ByteArray {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
        }
        if (op.outWidth <= 0 || op.outHeight <= 0) error("No se pudo leer la imagen.")

        // Se decodifica ya reducida: `inSampleSize` divide por potencias de
        // dos y es lo unico que evita traer la foto entera a memoria.
        var paso = 1
        while (maxOf(op.outWidth, op.outHeight) / (paso * 2) >= lado) paso *= 2

        var bmp = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = paso })
            }
        }.getOrNull() ?: error("No se pudo leer la imagen.")

        // El giro del EXIF importa MAS en una foto de perfil que en un adjunto:
        // una selfie de lado en el chat se nota y se pasa; de avatar queda
        // para siempre y en todas las pantallas.
        bmp = girarSegunExif(ctx, uri, ajustarLado(bmp, lado))

        var calidad = 90
        var datos = comprimir(bmp, calidad)
        while (datos.size > tope && calidad > 40) {
            calidad -= 15
            datos = comprimir(bmp, calidad)
        }

        // Si a calidad 40 todavia no entra, el problema son los pixeles y no
        // la calidad: se achica a la mitad y se vuelve a empezar. Pasa con
        // fotos de mucho detalle —texto, follaje— donde el JPEG no puede
        // tirar nada.
        var intentos = 0
        while (datos.size > tope && intentos < 4) {
            val mitad = ajustarLado(bmp, maxOf(bmp.width, bmp.height) / 2)
            if (mitad === bmp) break
            bmp.recycle()
            bmp = mitad
            calidad = 85
            datos = comprimir(bmp, calidad)
            while (datos.size > tope && calidad > 40) {
                calidad -= 15
                datos = comprimir(bmp, calidad)
            }
            intentos++
        }
        bmp.recycle()
        return datos
    }

    private fun comprimir(b: Bitmap, calidad: Int): ByteArray =
        ByteArrayOutputStream().use { s ->
            b.compress(Bitmap.CompressFormat.JPEG, calidad, s)
            s.toByteArray()
        }

    /** Reduce al lado pedido. Devuelve el MISMO bitmap si ya era mas chico. */
    private fun ajustarLado(b: Bitmap, lado: Int): Bitmap {
        val mayor = maxOf(b.width, b.height)
        if (mayor <= lado || lado < 1) return b
        val f = lado.toFloat() / mayor
        return Bitmap.createScaledBitmap(
            b,
            (b.width * f).toInt().coerceAtLeast(1),
            (b.height * f).toInt().coerceAtLeast(1),
            true,
        )
    }

    fun deBase64(b64: String): ByteArray? =
        if (b64.isBlank()) null else runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()

    /**
     * Decodifica la miniatura que mando otra persona, midiendo primero.
     *
     * Ver `MiniaturaSegura.kt` para el porque. En corto: esos bytes vienen de un
     * sobre que el servidor no puede abrir, y un PNG de pocos KB puede pedir
     * gigabytes al decodificarse. Atrapar el `OutOfMemoryError` despues no sirve
     * -para entonces el telefono ya intento reservarlos-, asi que se mide con
     * `inJustDecodeBounds`, se descarta lo que no es creible, y lo que si se
     * decodifica con `inSampleSize`.
     *
     * Devuelve `null` ante cualquier duda. Una miniatura es un adorno: no verla
     * cuesta un recuadro gris, y el archivo completo se baja igual al tocarlo.
     */
    fun miniaturaAjena(bytes: ByteArray): Bitmap? {
        if (!bytesDeMiniaturaCreibles(bytes.size)) return null

        val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medida) }
        if (!miniaturaCreible(medida.outWidth, medida.outHeight)) return null

        val paso = muestreoPara(medida.outWidth, medida.outHeight)
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = paso },
            )
        }.getOrNull()
    }

    /**
     * Decodifica la imagen ya reducida.
     *
     * `inSampleSize` hace que el decodificador NO construya el bitmap completo:
     * una foto de 12 MP en memoria son casi 50 MB, y solo se necesita para
     * tirarla a 240 px.
     */
    private fun imagenReducida(ctx: Context, uri: Uri): Bitmap? {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
        }
        if (op.outWidth <= 0) return null

        val mayor = maxOf(op.outWidth, op.outHeight)
        var paso = 1
        while (mayor / (paso * 2) >= LADO_MINIATURA) paso *= 2

        val bruto = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = paso })
            }
        }.getOrNull() ?: return null

        return girarSegunExif(ctx, uri, escalar(bruto))
    }

    private fun primerFotograma(ctx: Context, uri: Uri): Bitmap? {
        var f: Bitmap? = null
        conMetadatos(ctx, uri) { m -> f = m.frameAtTime }
        return f?.let { escalar(it) }
    }

    private fun escalar(b: Bitmap): Bitmap {
        val mayor = maxOf(b.width, b.height)
        if (mayor <= LADO_MINIATURA) return b
        val f = LADO_MINIATURA.toFloat() / mayor
        val chico = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
        if (chico !== b) b.recycle()
        return chico
    }

    /**
     * Aplica la rotacion del EXIF.
     *
     * Sin esto las fotos de camara salen acostadas en la miniatura: la camara
     * guarda el sensor tal cual y anota la orientacion aparte.
     */
    /**
     * `internal` y no privada: la usa tambien `Stickers`.
     *
     * Es la misma correccion para el mismo problema -una foto de camara viene
     * derecha solo si se mira su EXIF- y duplicarla habria dejado dos copias
     * que se corrigen por separado. Un retrato sin girar hace que el recorte
     * cuadrado de un sticker se lleve media cara.
     */
    internal fun girarSegunExif(ctx: Context, uri: Uri, b: Bitmap): Bitmap {
        val orientacion = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: return b

        val m = Matrix()
        when (orientacion) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return b
        }
        val girado = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (girado !== b) b.recycle()
        return girado
    }

    // ------------------------------------------------------------------
    //  Reduccion antes de enviar
    // ------------------------------------------------------------------

    /**
     * Reescribe la foto a la calidad elegida y devuelve true si lo hizo.
     *
     * Una foto de camara moderna pesa 4-8 MB y en pantalla de telefono no se
     * distingue de la misma a 2560 px con calidad 88, que pesa menos de 1 MB.
     * Bajarla antes de cifrar ahorra datos de quien envia, de quien recibe y
     * cuota en el almacen, los tres a la vez.
     *
     * Devuelve false cuando conviene mandar el original: porque la persona
     * eligio ORIGINAL, porque la imagen ya es chica, o porque el reescrito
     * saldria mas pesado que la fuente -pasa con los PNG de pocos colores.
     */
    fun prepararImagen(ctx: Context, uri: Uri, calidad: CalidadImagen, destino: File): Boolean {
        if (calidad == CalidadImagen.ORIGINAL) return false

        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
        }
        if (op.outWidth <= 0) return false
        val mayor = maxOf(op.outWidth, op.outHeight)
        if (mayor <= calidad.ladoMax) return false

        var paso = 1
        while (mayor / (paso * 2) >= calidad.ladoMax) paso *= 2

        val bruto = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = paso })
            }
        }.getOrNull() ?: return false

        val bmp = girarSegunExif(ctx, uri, ajustarA(bruto, calidad.ladoMax))
        val ok = runCatching {
            destino.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, calidad.calidad, it) }
            true
        }.getOrDefault(false)
        bmp.recycle()

        if (!ok || destino.length() <= 0) {
            destino.delete()
            return false
        }
        return true
    }

    private fun ajustarA(b: Bitmap, ladoMax: Int): Bitmap {
        val mayor = maxOf(b.width, b.height)
        if (mayor <= ladoMax) return b
        val f = ladoMax.toFloat() / mayor
        val chico = Bitmap.createScaledBitmap(
            b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true,
        )
        if (chico !== b) b.recycle()
        return chico
    }

    // ------------------------------------------------------------------
    //  Presentacion
    // ------------------------------------------------------------------

    /** Clase de adjunto que corresponde a un tipo MIME. */
    fun claseDe(mime: String): String = when {
        mime.startsWith("image/") -> ClaseAdjunto.IMAGEN
        mime.startsWith("video/") -> ClaseAdjunto.VIDEO
        mime.startsWith("audio/") -> ClaseAdjunto.AUDIO
        else -> ClaseAdjunto.DOCUMENTO
    }

    fun tamanoLegible(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /**
     * La duracion, como `m:ss`.
     *
     * ## Por que no basta con dividir
     *
     * Este numero lo **declara quien sube** el archivo: viaja en el metadato y
     * nadie lo comprueba contra el audio de verdad -para comprobarlo habria que
     * decodificar el archivo, y el servidor ni siquiera puede abrirlo-.
     *
     * Sin guarda, un negativo sale como `0:-5` y un `Int.MAX_VALUE` como
     * `35791:23`. Ninguno de los dos revienta nada; los dos son basura en
     * pantalla donde deberia haber un dato.
     *
     * Se devuelve cadena vacia cuando no se puede creer, y quien llama ya
     * decide que poner. Es la misma linea que los topes del contenido de un
     * sobre: lo que no es creible no se dibuja como si lo fuera.
     */
    fun duracionLegible(ms: Int): String {
        if (ms <= 0 || ms > TOPE_DURACION_MS) return ""
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /**
     * Lo mas larga que puede ser una duracion declarada, en ms: seis horas.
     *
     * Generoso a proposito. La pregunta no es "cuanto dura un audio razonable"
     * -eso ya lo acota el limite de tamano del servidor- sino "a partir de
     * donde esto no lo escribio un codificador".
     */
    const val TOPE_DURACION_MS = 6 * 60 * 60 * 1000

    /** Texto que representa al adjunto en la lista de chats y las citas. */
    fun resumen(clase: String, pie: String, nombre: String): String {
        val etiqueta = when (clase) {
            ClaseAdjunto.IMAGEN -> "Foto"
            ClaseAdjunto.VIDEO -> "Video"
            ClaseAdjunto.AUDIO -> "Audio"
            ClaseAdjunto.NOTA_VOZ -> "Nota de voz"
            ClaseAdjunto.STICKER -> "Sticker"
            else -> nombre.ifBlank { "Documento" }
        }
        return if (pie.isBlank()) etiqueta else "$etiqueta · $pie"
    }
}
