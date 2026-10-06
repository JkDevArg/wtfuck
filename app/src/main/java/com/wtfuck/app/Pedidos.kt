package com.wtfuck.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.wtfuck.app.datos.Atajos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Lo que llego desde afuera con "Compartir": un texto y archivos ya copiados.
 *
 * Copiados y no las URIs: el permiso de leer lo que manda otra app dura lo que
 * dura la pantalla que lo recibio, y mandar un video tarda mas que eso.
 */
data class Compartido(val texto: String?, val archivos: List<File>)

/**
 * Lo que alguien pidio desde afuera de la app: tocar una notificacion, un
 * atajo del icono o del widget, o compartir algo desde otra app.
 */
sealed interface Pedido {
    /** [mensaje]: si viene, se salta a ese mensaje (un recordatorio). */
    data class AbrirChat(val id: String, val mensaje: String? = null) : Pedido
    data object AbrirNota : Pedido
    /** La notificacion de una copia automatica fallida. */
    data object AbrirCopias : Pedido
    /** `wtfuck://c/<codigo>`: el enlace de contacto de alguien. */
    data class AbrirEnlace(val codigo: String) : Pedido
    /** [destino]: el chat que se eligio en la fila de compartir del sistema, si fue asi. */
    data class Compartir(val compartido: Compartido, val destino: String?) : Pedido
}

/**
 * Un solo sitio por el que entran todos. Antes no habia ninguno: las
 * notificaciones mandaban el chat en el Intent y nadie lo leia, asi que
 * tocarlas abria la app en la lista y no en el chat.
 */
object Pedidos {

    /** Lo facil, sin tocar disco. Compartir va aparte: ver [compartido]. */
    fun simple(i: Intent): Pedido? {
        i.getStringExtra("conversacionId")?.takeIf { it.isNotBlank() }?.let {
            return Pedido.AbrirChat(it, i.getStringExtra("mensajeId")?.takeIf { m -> m.isNotBlank() })
        }
        // El codigo sale validado o no sale: lo que llega por un enlace lo
        // escribio cualquiera.
        if (i.action == Intent.ACTION_VIEW && i.data?.scheme == "wtfuck") {
            return com.wtfuck.app.datos.EnlaceDeContacto.codigoDe(i.dataString.orEmpty())
                ?.let { Pedido.AbrirEnlace(it) }
        }
        return when (i.action) {
            Atajos.ACCION_NOTA -> Pedido.AbrirNota
            com.wtfuck.app.datos.Notificaciones.ACCION_COPIAS -> Pedido.AbrirCopias
            else -> null
        }
    }

    fun esCompartir(i: Intent): Boolean =
        i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE

    /** Hasta diez archivos por vez, como el selector de la galeria. */
    private const val MAX_ARCHIVOS = 10

    /**
     * Lee lo compartido y COPIA los archivos a la carpeta temporal.
     *
     * ## Solo `content://` y nunca de esta misma app
     *
     * Un `file://` o una URI de nuestro propio proveedor de archivos se
     * leerian con los permisos de ESTA app: otra app podria "compartir"
     * `file:///data/data/com.wtfuck.app/databases/...` y hacernos mandar
     * nuestra propia base de datos a quien quisiera. Es un error conocido de
     * los destinos de compartir, y se corta aqui.
     */
    suspend fun compartido(ctx: Context, i: Intent): Pedido.Compartir? = withContext(Dispatchers.IO) {
        val texto = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(4096)
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val propio = "${ctx.packageName}.archivos"
        val carpeta = File(ctx.cacheDir, "subiendo/compartido-${UUID.randomUUID()}").apply { mkdirs() }
        val archivos = uris
            .filter { it.scheme == "content" && it.authority != propio }
            .take(MAX_ARCHIVOS)
            .mapNotNull { uri -> copiar(ctx, uri, carpeta) }
        // Sin archivos, la carpeta sobra: no se deja un directorio vacio por
        // cada "Compartir" que no traia nada aprovechable.
        if (archivos.isEmpty()) carpeta.delete()
        if (texto.isNullOrBlank() && archivos.isEmpty()) return@withContext null
        val destino = i.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
            ?.removePrefix(Atajos.PREFIJO_CHAT)?.takeIf { it.isNotBlank() }
        Pedido.Compartir(Compartido(texto, archivos), destino)
    }

    private fun copiar(ctx: Context, uri: Uri, carpeta: File): File? = runCatching {
        val nombre = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.take(80)?.ifBlank { null }
            ?: "archivo"
        val destino = File(carpeta, nombre)
        ctx.contentResolver.openInputStream(uri)?.use { ent ->
            destino.outputStream().use { ent.copyTo(it, 64 * 1024) }
        } ?: return@runCatching null
        destino
    }.getOrNull()
}
