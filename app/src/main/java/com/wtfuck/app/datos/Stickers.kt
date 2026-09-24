package com.wtfuck.app.datos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Un recorte, en pixeles de la imagen de origen.
 *
 * Es una clase propia y **no `android.graphics.Rect`**, y no es purismo: la
 * aritmetica del recorte es lo unico que de verdad se puede equivocar aqui, y
 * con `Rect` no se puede probar —en una prueba de JVM sus metodos ni siquiera
 * existen, lanzan "not mocked"—. Lo descubri al escribir las pruebas: cinco se
 * cayeron por el framework y ninguna por el codigo.
 *
 * Se convierte a `Rect` en el unico sitio donde hace falta, que es al dibujar.
 */
data class Recorte(val izq: Int, val arriba: Int, val der: Int, val abajo: Int) {
    val ancho: Int get() = der - izq
    val alto: Int get() = abajo - arriba
}

/**
 * Módulo Y · Stickers hechos a partir de una foto.
 *
 * ## Qué había
 *
 * La clase de adjunto `sticker` existía desde el módulo D, con su tope de 2 MB
 * y su burbuja sin fondo. Lo que no existía era **ninguna forma de crear uno**:
 * la hoja de "Sticker o GIF" sólo buscaba GIFs en un servicio externo, que
 * además necesita una clave que no está. O sea que la mitad del nombre del
 * botón no llevaba a ningún sitio.
 *
 * ## Las tres decisiones que valen
 *
 * **512 × 512.** Es el tamaño con el que se dibujan los stickers en todas
 * partes, y hacerlo cuadrado en el origen evita que la burbuja tenga que
 * decidir cómo encajar una foto apaisada.
 *
 * **WebP y no PNG.** Un PNG de 512×512 con transparencia ronda los 400 KB; el
 * mismo en WebP sin pérdida baja a unos 60. El tope de la clase es 2 MB, así
 * que con PNG entraría igual — pero cada sticker viaja **cifrado a cada
 * aparato de cada destinatario**, y en un grupo de veinte eso es la diferencia
 * entre 1 MB y 8 MB de subida por sticker enviado.
 *
 * **La transparencia se conserva.** Si la foto de origen la tiene —un PNG
 * recortado, un sticker de otra app— sale igual. Si no la tiene, tampoco se
 * inventa: ver la nota sobre el fondo, más abajo.
 *
 * ## Lo que esto NO hace: quitar el fondo
 *
 * Y es deliberado. Quitar el fondo de una foto cualquiera con buenos resultados
 * necesita un modelo de segmentación —en Android, ML Kit sobre Play Services—,
 * que son una dependencia de Google, una descarga de modelo en el primer uso y
 * un servicio más del que depender. En una app cuyo argumento es que el
 * servidor no puede leer nada, meter eso **es una decisión de producto**, no un
 * detalle de implementación, y no la tomo yo solo.
 *
 * La alternativa sin dependencias —quitar por color, tipo croma— funciona con
 * un fondo liso y produce bordes sucios con cualquier foto real. Prefiero no
 * tener la función a tenerla mal: un recorte cuadrado bien hecho es útil de
 * verdad, y un recorte con halos se usa una vez.
 */
object Stickers {

    private const val TAG = "Stickers"

    /** El lado del sticker terminado, en píxeles. */
    const val LADO = 512

    /** Dónde viven los stickers propios. */
    private fun carpeta(ctx: Context): File =
        File(ctx.filesDir, "stickers").apply { mkdirs() }

    /**
     * El cuadrado centrado más grande que cabe en una imagen.
     *
     * Es el recorte de partida: lo que se ve al abrir el editor, antes de que
     * nadie toque nada. Se centra porque el motivo de una foto suele estar en
     * el medio, y porque un recorte que empieza en una esquina obliga a
     * moverlo siempre.
     */
    fun cuadradoCentrado(ancho: Int, alto: Int): Recorte {
        if (ancho <= 0 || alto <= 0) return Recorte(0, 0, 0, 0)
        val lado = minOf(ancho, alto)
        val x = (ancho - lado) / 2
        val y = (alto - lado) / 2
        return Recorte(x, y, x + lado, y + lado)
    }

    /**
     * El `inSampleSize` para no decodificar más grande de lo necesario.
     *
     * Una foto de 50 megapíxeles entera en memoria tumba la pantalla antes de
     * recortar nada, y de todas formas va a acabar en 512 píxeles de lado.
     *
     * Se queda **por encima** del objetivo a propósito: `inSampleSize` sólo
     * admite potencias de dos, y pasarse hacia abajo significa escalar hacia
     * arriba después, que es emborronar. Mejor decodificar el doble de lo que
     * hace falta y reducir con filtro.
     */
    fun muestreoPara(ladoMayor: Int, objetivo: Int = LADO): Int {
        if (ladoMayor <= 0 || objetivo <= 0) return 1
        var paso = 1
        while (ladoMayor / (paso * 2) >= objetivo) paso *= 2
        return paso
    }

    /**
     * Si el formato de destino admite transparencia en este aparato.
     *
     * `WEBP_LOSSLESS` existe desde API 30. Por debajo hay que caer a PNG: el
     * `WEBP` antiguo es **con pérdida**, y con pérdida los bordes de un recorte
     * transparente salen con halo. Pesa más, pero un sticker feo no sirve.
     */
    fun formatoPara(sdk: Int = Build.VERSION.SDK_INT): Bitmap.CompressFormat =
        if (sdk >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
        else Bitmap.CompressFormat.PNG

    /** La extensión que corresponde al formato. De ella sale el MIME. */
    fun extensionPara(sdk: Int = Build.VERSION.SDK_INT): String =
        if (sdk >= Build.VERSION_CODES.R) "webp" else "png"

    /**
     * Decodifica la imagen acotada, para dibujarla en el editor.
     *
     * Devuelve null si no se pudo: una URI que ya no existe, un archivo que no
     * es una imagen, permisos revocados entre elegir y abrir.
     */
    fun cargarParaEditar(ctx: Context, uri: Uri, objetivo: Int = LADO * 2): Bitmap? {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
        }
        if (op.outWidth <= 0) return null
        val bruto = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = muestreoPara(maxOf(op.outWidth, op.outHeight), objetivo)
                        // ARGB_8888 explícito: si el decodificador elige
                        // RGB_565 por ahorrar, la transparencia del origen se
                        // pierde antes de que nadie la pueda conservar.
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            }
        }.getOrNull() ?: return null
        // Se respeta la orientación EXIF, como en cualquier foto de cámara:
        // sin esto, un retrato sale acostado y el recorte cuadrado se lleva
        // media cara.
        return Media.girarSegunExif(ctx, uri, bruto)
    }

    /**
     * Escribe el sticker terminado.
     *
     * @param origen el bitmap ya decodificado y girado.
     * @param recorte el cuadrado elegido, en coordenadas de [origen].
     */
    fun escribir(origen: Bitmap, recorte: Recorte, destino: File): Boolean {
        val lado = minOf(recorte.ancho, recorte.alto)
        if (lado <= 0) return false

        val salida = Bitmap.createBitmap(LADO, LADO, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(salida)
        // Se dibuja sobre un lienzo TRANSPARENTE y no sobre blanco: si la
        // imagen de origen tiene alfa, el sticker la conserva. Pintar un fondo
        // aquí sería decidir por el contenido.
        lienzo.drawBitmap(
            origen,
            // El recorte se acota a la imagen: un rectángulo que se salga
            // —por un gesto rápido, por un redondeo— hace que `drawBitmap`
            // lance, y el síntoma sería "crear sticker no hace nada".
            Rect(
                recorte.izq.coerceIn(0, origen.width),
                recorte.arriba.coerceIn(0, origen.height),
                recorte.der.coerceIn(0, origen.width),
                recorte.abajo.coerceIn(0, origen.height),
            ),
            Rect(0, 0, LADO, LADO),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )

        val ok = runCatching {
            destino.outputStream().buffered().use {
                // La calidad se ignora en los formatos sin pérdida; se pasa
                // 100 igualmente porque la firma la pide.
                salida.compress(formatoPara(), 100, it)
            }
            true
        }.onFailure { Log.w(TAG, "No se pudo escribir el sticker: ${it.message}") }
            .getOrDefault(false)

        salida.recycle()
        if (!ok || destino.length() <= 0L) {
            destino.delete()
            return false
        }
        return true
    }

    /** Un archivo nuevo en la carpeta de stickers propios. */
    fun nuevo(ctx: Context): File =
        File(carpeta(ctx), "${System.currentTimeMillis()}.${extensionPara()}")

    /**
     * Los stickers propios, del más nuevo al más viejo.
     *
     * El más nuevo primero porque el que se acaba de crear es el que se va a
     * mandar, y buscarlo al final de la lista sería raro.
     */
    fun mios(ctx: Context): List<File> =
        carpeta(ctx).listFiles()?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun borrar(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    // =======================================================================
    //  Stickers con movimiento
    // =======================================================================

    /**
     * Si estos bytes son una imagen **animada**.
     *
     * Se mira la cabecera del archivo y no la extension, por lo de siempre: la
     * extension la pone quien nombra el archivo y miente cuando quiere. Aqui
     * ademas importa mas que de costumbre, porque un `.webp` puede ser una
     * imagen fija o una animacion y el mismo nombre sirve para las dos.
     *
     * **GIF**: los animados tienen mas de un bloque de imagen. Basta con
     * encontrar la extension de control de grafico (`21 F9`) mas de una vez,
     * que es lo que lleva el retardo de cada fotograma.
     *
     * **WebP**: el formato es RIFF. Un WebP animado declara el trozo `VP8X`
     * con el bit de animacion, y lleva un trozo `ANIM`. Buscar `ANIM` en los
     * primeros bytes es suficiente y no obliga a parsear el contenedor.
     */
    fun esAnimado(cabecera: ByteArray): Boolean {
        fun contiene(marca: String, desde: Int = 0): Int {
            val m = marca.toByteArray(Charsets.US_ASCII)
            outer@ for (i in desde..cabecera.size - m.size) {
                for (j in m.indices) if (cabecera[i + j] != m[j]) continue@outer
                return i
            }
            return -1
        }
        // WebP animado: RIFF .... WEBP ... ANIM
        if (contiene("WEBP") in 8..12 && contiene("ANIM") > 0) return true
        // GIF animado: al menos dos bloques de control de grafico.
        if (cabecera.size > 6 && cabecera[0] == 'G'.code.toByte() &&
            cabecera[1] == 'I'.code.toByte() && cabecera[2] == 'F'.code.toByte()
        ) {
            var n = 0
            var i = 0
            while (i < cabecera.size - 1) {
                if (cabecera[i] == 0x21.toByte() && cabecera[i + 1] == 0xF9.toByte()) {
                    n++
                    if (n > 1) return true
                }
                i++
            }
        }
        return false
    }

    /** Lee lo justo para decidir si el archivo tiene movimiento. */
    fun esAnimado(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { flujo ->
            // 256 KB: el trozo ANIM de un WebP va al principio, y en un GIF el
            // segundo bloque de control aparece tras el primer fotograma. Leer
            // el archivo entero para responder una pregunta de cabecera seria
            // cargar en memoria lo que se intenta evitar.
            val tope = ByteArray(256 * 1024)
            val leidos = flujo.read(tope)
            if (leidos <= 0) false else esAnimado(tope.copyOf(leidos))
        } ?: false
    }.getOrDefault(false)

    /**
     * Copia un animado tal cual, sin recortar ni re-codificar.
     *
     * ## Por que NO se recorta
     *
     * Android no trae ningun codificador de WebP animado ni de GIF: se puede
     * decodificar y no se puede volver a escribir. Un recorte obligaria a
     * aplanarlo a un fotograma, o sea **a quitarle el movimiento para poder
     * ajustarlo**, que es perder justo lo que se venia a conservar.
     *
     * Asi que se acepta como viene, con dos condiciones que si se pueden
     * comprobar: que pese menos que el tope de la clase y que no sea enorme de
     * lado. Un animado de 2000 px dibujado a 132 dp es gastar memoria en cada
     * fotograma para no verlo.
     */
    fun copiarAnimado(ctx: Context, uri: Uri, destino: File, topeBytes: Long): Boolean {
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { entrada ->
                destino.outputStream().buffered().use { salida ->
                    // Se copia acotado: un archivo mas grande que el tope se
                    // corta y se descarta, en vez de llenar el disco antes de
                    // comprobar el tamano.
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > topeBytes) return@use false
                        salida.write(buf, 0, n)
                    }
                    true
                }
            } ?: false
        }.onFailure { Log.w(TAG, "No se pudo copiar el animado: ${it.message}") }
            .getOrDefault(false)

        if (!ok || destino.length() <= 0L) {
            destino.delete()
            return false
        }
        return true
    }

    /** Un archivo nuevo conservando la extension del origen animado. */
    fun nuevoAnimado(ctx: Context, extension: String): File =
        File(carpeta(ctx), "${System.currentTimeMillis()}.${extension.ifBlank { "webp" }}")

    /**
     * La extension de una URI, para no cambiarle el formato a un animado.
     *
     * De la extension sale el MIME cuando el archivo se lee por `file://`, y
     * un GIF guardado como `.webp` se dibuja mal en el otro aparato.
     */
    fun extensionDe(ctx: Context, uri: Uri): String {
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            mime.contains("gif") -> "gif"
            mime.contains("webp") -> "webp"
            else -> "webp"
        }
    }
}
