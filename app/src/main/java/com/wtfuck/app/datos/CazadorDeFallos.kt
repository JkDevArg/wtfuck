package com.wtfuck.app.datos

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Atrapa los cierres inesperados y deja un informe en el telefono.
 *
 * ## Lo que se puede y no se puede hacer aqui
 *
 * Este codigo corre en un proceso que **ya se esta muriendo**, en el hilo que
 * lanzo la excepcion. Casi nada de lo normal es seguro:
 *
 *  - **Nada de corrutinas.** El ambito puede estar cancelado, y aunque no lo
 *    estuviera, el proceso no va a vivir para verlas terminar.
 *  - **Nada de base de datos.** SQLCipher puede ser justo lo que reviento, y
 *    abrirla podria lanzar dentro del manejador — que es la forma de perder el
 *    informe y el aviso del sistema a la vez.
 *  - **Nada de red.** Ni siquiera con tiempo limite: bloquea el cierre y el
 *    sistema acaba matando el proceso antes de escribir nada.
 *
 * Queda una escritura de archivo, sincrona y corta. Es poco, y es exactamente
 * lo que hace falta.
 *
 * ## Por que se encadena al manejador anterior
 *
 * Porque sin eso la app **dejaria de cerrarse como debe**: no saldria el aviso
 * del sistema, y en algunos aparatos el proceso quedaria colgado en vez de
 * morir. Un manejador que se traga el cierre es peor que no tener manejador.
 * Se escribe el informe y se le pasa la excepcion a quien estuviera antes.
 *
 * ## Por que un archivo por informe
 *
 * Porque escribir al final de un archivo compartido, desde un proceso que se
 * muere, es la forma de acabar con un archivo a medio escribir que ya no se
 * puede leer — y entonces se pierden tambien los informes anteriores, que
 * estaban bien. Un archivo por cierre: si uno queda a medias, es solo ese.
 */
class CazadorDeFallos(
    private val carpeta: File,
    private val version: String,
    private val modelo: String,
    private val android: String,
    /** Inyectable para poder probar sin tocar el reloj del sistema. */
    private val ahora: () -> Long = System::currentTimeMillis,
) {

    private val TAG = "Fallos"

    /**
     * Instala el manejador. Idempotente si se llama dos veces.
     *
     * Devuelve el manejador anterior por si hiciera falta en una prueba.
     */
    fun instalar(): Thread.UncaughtExceptionHandler? {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        if (anterior is Encadenado) return anterior.previo
        Thread.setDefaultUncaughtExceptionHandler(Encadenado(anterior))
        return anterior
    }

    private inner class Encadenado(
        val previo: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(hilo: Thread, e: Throwable) {
            // Todo en runCatching: si escribir el informe fallara, lo que NO
            // puede pasar es que se coma el cierre. El aviso del sistema
            // importa mas que el informe.
            runCatching { guardar(e, hilo.name) }
                .onFailure { Log.e(TAG, "no se pudo guardar el informe: ${it.message}") }
            previo?.uncaughtException(hilo, e)
        }
    }

    /** Escribe el informe. Publico para poder probarlo sin provocar un cierre. */
    fun guardar(e: Throwable, hilo: String) {
        if (!carpeta.exists()) carpeta.mkdirs()
        val cuando = ahora()
        val texto = Fallos.informe(
            e = e, cuando = cuando, version = version,
            modelo = modelo, android = android, hilo = hilo,
        )
        // El nombre lleva la marca de tiempo: ordena solo y no necesita leer
        // el contenido para saber cual es el mas nuevo.
        File(carpeta, "fallo-$cuando.txt").writeText(texto)
        podar()
    }

    /** Los informes guardados, del mas viejo al mas nuevo. */
    fun informes(): List<File> =
        carpeta.listFiles { f -> f.name.startsWith("fallo-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** El ultimo, o null. Es lo que se le ofrece a la persona al arrancar. */
    fun ultimo(): String? = informes().lastOrNull()?.let {
        runCatching { it.readText() }.getOrNull()
    }

    fun hayInformes(): Boolean = informes().isNotEmpty()

    fun borrarTodos() {
        informes().forEach { runCatching { it.delete() } }
    }

    /**
     * Se queda con los [Fallos.MAXIMO] mas nuevos.
     *
     * Al escribir y no al leer: quien nunca abra la pantalla acumularia un
     * archivo por cada cierre. Es la fuga que solo le pasa a quien mas
     * problemas tiene, que es justo a quien no hay que darle otro.
     */
    private fun podar() {
        val todos = informes()
        if (todos.size <= Fallos.MAXIMO) return
        todos.dropLast(Fallos.MAXIMO).forEach { runCatching { it.delete() } }
    }

    companion object {
        /** La carpeta de informes dentro de los datos privados de la app. */
        fun carpetaDe(ctx: Context): File = File(ctx.filesDir, "fallos")
    }
}
