package com.wtfuck.app.datos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Dibuja un estado editado. La MISMA funcion pinta la vista previa del editor
 * y el archivo que se publica.
 *
 * ## Por que una sola funcion para las dos cosas
 *
 * Si la vista previa la dibujara Compose y el archivo un Canvas, cada uno
 * mediria el texto con su propia tipografia y pondria la foto con sus propias
 * cuentas, y lo publicado no seria lo que se vio. Aqui las dos pasan por
 * [dibujar], con las cuentas de [Encuadre] y tamanos relativos al lienzo.
 * Lo unico que cambia es el tamano: la pantalla o 1080x1920.
 */
object RenderEstado {

    /** El texto de una capa mide esto del ancho del lienzo, por 1 de escala. */
    const val TEXTO_BASE = 0.07f

    /** Un sticker mide esto del ancho del lienzo, por 1 de escala. */
    const val STICKER_BASE = 0.26f

    /**
     * Carga la foto para editarla: con la orientacion de la camara ya aplicada
     * y reducida a un tamano que alcanza para 1080x1920 con margen para el
     * zoom, sin cargar en memoria una foto de 50 megapixeles.
     */
    fun cargar(ctx: Context, uri: Uri, ladoMax: Int = 2600): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder aplica la orientacion EXIF solo: con BitmapFactory
            // una foto vertical de la camara llegaba acostada.
            val fuente = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(fuente) { dec, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val mayor = maxOf(w, h)
                if (mayor > ladoMax) {
                    val f = ladoMax.toFloat() / mayor
                    dec.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                }
                // Software: un bitmap de hardware no se puede dibujar en un
                // Canvas de software, que es justo donde se renderiza.
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
            var muestra = 1
            while (maxOf(limites.outWidth, limites.outHeight) / (muestra * 2) >= ladoMax) muestra *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
            }
        }
    }.getOrNull()

    private fun pinturaTexto(tamano: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = tamano
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /**
     * El rectangulo que ocupa una capa, SIN girar, centrado en su sitio. Sirve
     * para dibujar la caja del texto y para saber que capa se toco.
     */
    fun limites(capa: Capa, ancho: Float, alto: Float, stickers: Map<String, Bitmap> = emptyMap()): RectF {
        val cx = capa.x * ancho
        val cy = capa.y * alto
        return when (capa) {
            is Capa.Texto -> {
                val p = pinturaTexto(TEXTO_BASE * ancho * capa.escala, Color.WHITE)
                val lineas = capa.texto.ifBlank { " " }.split('\n')
                val w = lineas.maxOf { p.measureText(it) }
                val alturaLinea = p.fontSpacing
                val h = alturaLinea * lineas.size
                val pad = p.textSize * 0.35f
                RectF(cx - w / 2 - pad, cy - h / 2 - pad, cx + w / 2 + pad, cy + h / 2 + pad)
            }
            is Capa.Sticker -> {
                val lado = STICKER_BASE * ancho * capa.escala
                val bmp = stickers[capa.ruta]
                val prop = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
                val w = if (prop >= 1f) lado else lado * prop
                val h = if (prop >= 1f) lado / prop else lado
                RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            }
        }
    }

    /**
     * La capa mas alta bajo el punto (x, y), o null. Se recorre de la ultima
     * a la primera porque la ultima agregada es la que se ve encima.
     */
    fun capaEn(e: EdicionFoto, x: Float, y: Float, ancho: Float, alto: Float, stickers: Map<String, Bitmap>): Capa? =
        e.capas.asReversed().firstOrNull { c ->
            // El punto se lleva al sistema SIN girar de la capa.
            val r = limites(c, ancho, alto, stickers)
            val rad = Math.toRadians(-c.giro.toDouble())
            val dx = x - r.centerX()
            val dy = y - r.centerY()
            val lx = (dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat() + r.centerX()
            val ly = (dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat() + r.centerY()
            // Un margen para el dedo: un texto chico es un blanco chico.
            val margen = 0.03f * ancho
            lx >= r.left - margen && lx <= r.right + margen && ly >= r.top - margen && ly <= r.bottom + margen
        }

    /** Dibuja todo en un Canvas de `ancho` x `alto`. */
    fun dibujar(
        canvas: Canvas,
        foto: Bitmap?,
        e: EdicionFoto,
        ancho: Float,
        alto: Float,
        stickers: Map<String, Bitmap> = emptyMap(),
    ) {
        canvas.drawColor(Color.BLACK)
        if (foto != null) {
            val t = Encuadre.transformacion(foto.width, foto.height, e, ancho, alto)
            val m = Matrix().apply { setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f)) }
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                if (e.filtro != FiltroFoto.ORIGINAL) colorFilter = ColorMatrixColorFilter(ColorMatrix(e.filtro.matriz))
            }
            canvas.drawBitmap(foto, m, p)
        }
        e.capas.forEach { dibujarCapa(canvas, it, ancho, alto, stickers) }
    }

    private fun dibujarCapa(canvas: Canvas, capa: Capa, ancho: Float, alto: Float, stickers: Map<String, Bitmap>) {
        val r = limites(capa, ancho, alto, stickers)
        canvas.save()
        canvas.rotate(capa.giro, r.centerX(), r.centerY())
        when (capa) {
            is Capa.Texto -> {
                val p = pinturaTexto(TEXTO_BASE * ancho * capa.escala, capa.color.toInt())
                if (capa.conFondo) {
                    val caja = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
                    val radio = p.textSize * 0.3f
                    canvas.drawRoundRect(r, radio, radio, caja)
                }
                val lineas = capa.texto.split('\n')
                val alturaLinea = p.fontSpacing
                // La primera linea arranca de modo que el bloque quede centrado.
                var y = r.centerY() - alturaLinea * lineas.size / 2f - p.ascent()
                for (l in lineas) {
                    canvas.drawText(l, r.centerX(), y, p)
                    y += alturaLinea
                }
            }
            is Capa.Sticker -> {
                val bmp = stickers[capa.ruta]
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                } else if (capa.emoji.isNotBlank()) {
                    val p = pinturaTexto(r.height() * 0.8f, Color.WHITE)
                    canvas.drawText(capa.emoji, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
                }
            }
        }
        canvas.restore()
    }

    /**
     * El archivo final: 1080x1920, JPEG de alta calidad.
     *
     * 92 y no menos: es la calidad que publica, y despues ya no pasa por el
     * reductor de fotos del chat (ver `Repositorio.publicarHistoria`). Un
     * estado que se ve a pantalla completa no admite el ahorro de una foto
     * de chat que se ve del tamano de una burbuja.
     */
    fun renderizar(foto: Bitmap, e: EdicionFoto, stickers: Map<String, Bitmap>, destino: File): Boolean = runCatching {
        val bmp = Bitmap.createBitmap(Lienzo.ANCHO, Lienzo.ALTO, Bitmap.Config.ARGB_8888)
        dibujar(Canvas(bmp), foto, e, Lienzo.ANCHO.toFloat(), Lienzo.ALTO.toFloat(), stickers)
        destino.parentFile?.mkdirs()
        destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        destino.length() > 0
    }.getOrDefault(false)
}
