package com.wtfuck.app.datos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/**
 * Donde viven los archivos adjuntos en el telefono.
 *
 * Dos carpetas con proposito distinto:
 *
 *   - `media/` en filesDir: los archivos YA DESCIFRADOS, listos para ver. Van
 *     en filesDir y no en cacheDir porque el sistema vacia el cache cuando
 *     quiere, y entonces una foto que la persona ve en el chat desapareceria
 *     sin aviso. Vaciarlo es una decision del usuario, no del sistema.
 *
 *   - `subiendo/` en cacheDir: los temporales cifrados que se estan subiendo.
 *     Estos SI son descartables: si se pierden, la subida se reintenta.
 *
 * Nada de esto va a almacenamiento externo: seria legible por cualquier app y
 * ahi se acaba la privacidad de un mensajero cifrado.
 */
class ArchivosLocales(private val ctx: Context) {

    private val media: File by lazy { File(ctx.filesDir, "media").apply { mkdirs() } }
    private val temporales: File by lazy { File(ctx.cacheDir, "subiendo").apply { mkdirs() } }

    /**
     * Archivo local de un mensaje.
     *
     * El nombre lo pone el id del mensaje y no el nombre original: dos personas
     * pueden mandar "foto.jpg" y el segundo pisaria al primero. La extension se
     * conserva para que el visor y las otras apps sepan que es.
     */
    fun archivoDe(mensajeId: String, nombre: String): File {
        val ext = nombre.substringAfterLast('.', "").take(8).filter { it.isLetterOrDigit() }
        return File(media, if (ext.isBlank()) mensajeId else "$mensajeId.$ext")
    }

    fun temporal(id: String): File = File(temporales, "$id.bin")

    /**
     * Temporal con nombre y extension propios.
     *
     * La extension no es cosmetica: de ella sale el tipo MIME cuando el archivo
     * se lee por file://, y de ese tipo depende que un GIF se trate como GIF.
     */
    fun temporalNombrado(nombre: String): File = File(temporales, nombre)

    fun abrir(uri: Uri): InputStream? = ctx.contentResolver.openInputStream(uri)

    /** Copia el original al almacenamiento de la app. */
    fun copiarDesde(uri: Uri, destino: File): Boolean = runCatching {
        abrir(uri)?.use { ent -> destino.outputStream().buffered().use { ent.copyTo(it, 64 * 1024) } }
            ?: return false
        true
    }.getOrElse {
        android.util.Log.w("Archivos", "No se pudo copiar $uri: ${it.message}")
        destino.delete()
        false
    }

    // ------------------------------------------------------------------
    //  Delegaciones a Media
    // ------------------------------------------------------------------
    //
    // Van aqui para que el Repositorio no necesite un Context. Todo lo que
    // depende del telefono -proveedores de contenido, decodificadores- queda
    // de este lado de la frontera.

    fun datosDe(uri: Uri, clase: String): DatosArchivo = Media.datosDe(ctx, uri, clase)

    fun miniaturaDe(uri: Uri, clase: String): String = Media.miniaturaDe(ctx, uri, clase)

    fun prepararImagen(uri: Uri, calidad: CalidadImagen, destino: File): Boolean =
        Media.prepararImagen(ctx, uri, calidad, destino)

    fun bytesUsados(): Long = media.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun cuantosArchivos(): Int = media.walkTopDown().count { it.isFile }

    /** Borra los archivos descargados. Los mensajes quedan; se vuelven a bajar. */
    fun vaciar(): Int {
        var n = 0
        media.listFiles()?.forEach { if (it.delete()) n++ }
        return n
    }

    /** Barre temporales que quedaron de una subida interrumpida. */
    fun limpiarTemporales() {
        temporales.listFiles()?.forEach { it.delete() }
    }

    /**
     * URI compartible para abrir el archivo con otra app.
     *
     * Hace falta un FileProvider: desde Android 7 pasar un `file://` a otra app
     * lanza FileUriExposedException. El permiso se concede solo para ese
     * archivo y solo mientras dura el intent.
     */
    fun uriCompartible(archivo: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", archivo)

    fun intentVer(archivo: File, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriCompartible(archivo), mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun intentCompartir(archivo: File, mime: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uriCompartible(archivo))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
