package com.wtfuck.app.datos

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
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
    /**
     * La version de Android, como numero de API.
     *
     * OJO con este nombre: se llama `android` y por tanto **tapa el paquete
     * `android`** dentro de toda la clase. Escribir `android.os.Build` aqui
     * no compila, porque `android` es este String. Por eso arriba van los
     * imports de `Build`, `ActivityManager` y `ApplicationExitInfo` y se usan
     * los nombres simples. El error que da -"Unresolved reference 'os'"- no
     * se parece en nada a la causa.
     */
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

    /**
     * Lo que el SISTEMA sabe de la muerte anterior del proceso.
     *
     * ## El punto ciego que esto cierra
     *
     * [instalar] solo atrapa **excepciones de Java**. No atrapa un fallo
     * nativo, ni un ANR, ni que el sistema mate el proceso — y en esos casos
     * el manejador ni siquiera llega a ejecutarse. La app desaparece de golpe
     * y no queda absolutamente nada.
     *
     * Paso de verdad, en una llamada: el servidor veia `POST /v1/llamadas 200
     * OK` y 600 ms despues el socket caido, y el telefono no tenia ni un
     * informe. Un cierre sin rastro es el peor de todos, porque no hay por
     * donde empezar.
     *
     * Android SI lo sabe: desde API 30 guarda un historial de por que murio
     * cada proceso, y para los fallos y los ANR guarda tambien la traza. Se
     * lee al arrancar y se convierte en un informe normal.
     *
     * ## Por debajo de API 30 no hay nada que hacer
     *
     * `getHistoricalProcessExitReasons` no existe antes. No hay sustituto: lo
     * que se perdio, se perdio. Se declara en vez de fingir que esta cubierto.
     */
    fun revisarMuerteAnterior(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val salidas = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            val ultima = salidas.firstOrNull() ?: return

            // Solo lo anormal. Cerrar la app a mano o que el sistema la pare
            // por memoria no es un fallo que reportar, y avisar de eso seria
            // ruido que ensena a cerrar el aviso sin leerlo.
            val motivo = when (ultima.reason) {
                ApplicationExitInfo.REASON_CRASH -> "excepcion no atrapada"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "fallo nativo"
                ApplicationExitInfo.REASON_ANR -> "la app dejo de responder"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                    "el sistema la paro por consumo excesivo"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE ->
                    "cambio de permisos"
                ApplicationExitInfo.REASON_SIGNALED ->
                    "el sistema la mato con una senal"
                else -> return
            }

            // No repetir el mismo. La marca es la hora de la muerte, que el
            // sistema da en epoch ms y no se repite.
            val marca = File(carpeta, "ultima-muerte")
            val visto = runCatching { marca.readText().trim().toLong() }.getOrNull() ?: 0L
            if (ultima.timestamp <= visto) return

            if (!carpeta.exists()) carpeta.mkdirs()
            val sb = StringBuilder()
            sb.append("wtfuck ").append(version).append('\n')
            sb.append("cuando: ").append(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(ultima.timestamp))
            ).append('\n')
            sb.append("aparato: ").append(Fallos.sanear(modelo))
                .append(" · Android ").append(android).append('\n')
            sb.append("lo dice el SISTEMA, no la app\n")
            sb.append("motivo: ").append(motivo).append('\n')
            ultima.description?.let { sb.append("detalle: ").append(Fallos.sanear(it)).append('\n') }
            sb.append('\n')

            // Para un fallo o un ANR, Android guarda la traza. Es justo lo que
            // falta cuando la app se muere sin poder escribir nada.
            runCatching {
                ultima.traceInputStream?.use { entrada ->
                    entrada.bufferedReader().useLines { lineas ->
                        // Con tope: una traza de ANR trae TODOS los hilos y
                        // son miles de lineas. Las primeras son el hilo
                        // principal, que es donde esta el problema.
                        lineas.take(120).forEach { sb.append(Fallos.sanear(it)).append('\n') }
                    }
                }
            }

            File(carpeta, "fallo-${ultima.timestamp}.txt").writeText(sb.toString())
            marca.writeText(ultima.timestamp.toString())
            podar()
            Log.i(TAG, "Recuperado del sistema el motivo de la muerte anterior: $motivo")
        }.onFailure { Log.w(TAG, "no se pudo leer la muerte anterior: ${it.message}") }
    }

    companion object {
        /** La carpeta de informes dentro de los datos privados de la app. */
        fun carpetaDe(ctx: Context): File = File(ctx.filesDir, "fallos")
    }
}
