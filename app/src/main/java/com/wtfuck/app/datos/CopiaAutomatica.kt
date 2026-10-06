package com.wtfuck.app.datos

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wtfuck.app.WtfuckApp
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * La copia de seguridad, hecha sola.
 *
 * ## Por que existe
 *
 * La copia manual de [CopiaSeguridad] funciona, y la propia pantalla reconocia
 * su problema: se olvida. El aviso ambar de "hace 30 dias" llega cuando ya
 * hay 30 dias de mensajes que no estan en ninguna copia.
 *
 * ## Como, y de donde sale el modelo
 *
 * Es el de las copias locales de Signal, que resuelven el mismo problema con
 * las mismas restricciones -el servidor no puede guardar nada legible-:
 *
 *  - la persona elige una CARPETA una vez (`ACTION_OPEN_DOCUMENT_TREE`, con
 *    permiso persistente), y ahi se escribe cada copia;
 *  - la FRASE se pide una vez y queda guardada en este telefono, envuelta con
 *    una clave del Keystore ([CajaFuerte]);
 *  - se conservan las dos ultimas automaticas y se borran las anteriores.
 *
 * El archivo es exactamente el mismo que el de la copia manual: se restaura
 * desde la misma pantalla, con la misma frase.
 *
 * ## Por que guardar la frase no debilita nada
 *
 * La frase protege el archivo FUERA del telefono. Dentro del telefono ya esta
 * todo lo que la copia contiene, sin cifrar por la frase: quien pudiera sacar
 * la frase de aqui ya tendria los mensajes. Y queda envuelta con una clave que
 * no sale del aparato, asi que el XML de preferencias solo no sirve.
 *
 * ## La identidad: se guarda la LLAVE del sello, no el codigo
 *
 * Para que la copia lleve la identidad Signal hace falta el codigo de
 * recuperacion. Guardar el codigo seria un error: ademas de sellar la
 * identidad, el codigo sirve para RECUPERAR LA CUENTA en otro telefono -es la
 * mitad de esa puerta-. Lo que se guarda es solo
 * [CodigoRecuperacion.claveDeIdentidad], que se deriva con otra etiqueta y no
 * abre la cuenta en el servidor. Y esa llave solo abre la identidad, que este
 * telefono ya tiene.
 *
 * ## Lo que NO resuelve, dicho en la pantalla
 *
 * Si la carpeta esta en este mismo telefono y el telefono se pierde, la copia
 * se pierde con el. Sirve para reinstalar, cambiar de telefono a mano o
 * recuperarse de un fallo; para sobrevivir a perder el telefono, la carpeta
 * tiene que estar en una tarjeta SD o en una que otra app sincronice.
 */
object CopiaAutomatica {

    private const val TAG = "CopiaAuto"

    const val PREFIJO = "wtfuck-auto-"
    const val EXTENSION = ".wtfbackup"

    /**
     * Cuantas automaticas se conservan. Dos y no una: si la ultima salio
     * dañada -un corte de luz, una tarjeta que falla- queda la anterior.
     */
    const val CONSERVAR = 2

    private const val TRABAJO = "wtfuck-copia-automatica"
    private const val TRABAJO_YA = "wtfuck-copia-automatica-ya"

    enum class Frecuencia(val etiqueta: String, val dias: Long) {
        DIARIA("Cada día", 1),
        SEMANAL("Cada semana", 7),
    }

    enum class Resultado { HECHA, SIN_CONFIGURAR, CARPETA_PERDIDA, FALLO }

    // ------------------------------------------------------------------
    //  Lo puro: el nombre y que se borra
    // ------------------------------------------------------------------

    private val FORMATO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * `wtfuck-auto-20261006-013000.wtfbackup`. La fecha va de mayor a menor y
     * con ancho fijo para que ordenar por nombre sea ordenar por fecha: asi la
     * poda no depende de la fecha de modificacion, que algunos proveedores de
     * documentos no informan o informan mal.
     */
    fun nombre(cuando: ZonedDateTime): String = PREFIJO + cuando.format(FORMATO) + EXTENSION

    // " (1)": lo que agregan algunos proveedores si el nombre ya existe.
    private val PATRON = Regex("""^wtfuck-auto-(\d{8}-\d{6})(?: \((\d+)\))?\.wtfbackup$""")

    /**
     * Que borrar tras escribir una copia nueva: las automaticas que sobran
     * despues de las [conservar] mas nuevas.
     *
     * Solo toca lo que se llama como una automatica. En la carpeta puede haber
     * copias manuales, fotos o cualquier cosa de la persona, y un borrado que
     * se equivoca de archivo no se deshace.
     */
    fun sobrantes(nombres: List<String>, conservar: Int = CONSERVAR): List<String> =
        nombres.mapNotNull { n ->
            PATRON.matchEntire(n)?.let { m -> Triple(n, m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0) }
        }
            // Misma fecha: la de " (1)" se escribio despues que la sin numero.
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.second }.thenByDescending { it.third })
            .drop(conservar)
            .map { it.first }

    // ------------------------------------------------------------------
    //  La configuracion, con los dos secretos envueltos
    // ------------------------------------------------------------------

    /**
     * Preferencias propias y no las de [Ajustes]: asi "desactivar" y "cerrar
     * sesion" borran todo de un golpe, sin buscar claves sueltas.
     */
    class Config(ctx: Context) {
        private val p = ctx.getSharedPreferences("wtfuck_copia_auto", Context.MODE_PRIVATE)
        private val caja = CajaFuerte("wtfuck_copia_auto_v1")

        val activa: Boolean get() = carpeta != null && p.contains("frase")

        var carpeta: String?
            get() = p.getString("carpeta", null)
            set(v) = p.edit().putString("carpeta", v).apply()

        var frecuencia: Frecuencia
            get() = runCatching { Frecuencia.valueOf(p.getString("frecuencia", null)!!) }
                .getOrDefault(Frecuencia.DIARIA)
            set(v) = p.edit().putString("frecuencia", v.name).apply()

        /** Con fotos y archivos. Encendido, como la manual: es lo que la gente teme perder. */
        var conAdjuntos: Boolean
            get() = p.getBoolean("con_adjuntos", true)
            set(v) = p.edit().putBoolean("con_adjuntos", v).apply()

        var ultimaHecha: Long
            get() = p.getLong("ultima", 0)
            set(v) = p.edit().putLong("ultima", v).apply()

        /** Por que fallo la ultima, para decirlo en la pantalla. null = no fallo. */
        var ultimoError: String?
            get() = p.getString("error", null)
            set(v) = p.edit().putString("error", v).apply()

        val llevaIdentidad: Boolean get() = p.contains("clave_identidad")

        fun guardarFrase(frase: CharArray) {
            p.edit().putString("frase", caja.cerrar(String(frase).toByteArray())).apply()
        }

        /** La frase, o null si no hay o ya no se puede abrir (el Keystore se perdio). */
        fun frase(): CharArray? = p.getString("frase", null)
            ?.let { runCatching { String(caja.abrir(it)).toCharArray() }.getOrNull() }

        /** [clave] = `CodigoRecuperacion.claveDeIdentidad`, o null para no llevar identidad. */
        fun guardarClaveIdentidad(clave: ByteArray?) {
            if (clave == null) p.edit().remove("clave_identidad").apply()
            else p.edit().putString("clave_identidad", caja.cerrar(clave)).apply()
        }

        fun claveIdentidad(): ByteArray? = p.getString("clave_identidad", null)
            ?.let { runCatching { caja.abrir(it) }.getOrNull() }

        fun borrarTodo() {
            p.edit().clear().apply()
            runCatching { caja.tirar() }
        }
    }

    // ------------------------------------------------------------------
    //  La carpeta (Storage Access Framework)
    // ------------------------------------------------------------------
    //
    // Con `DocumentsContract` directo y no con `DocumentFile`: son cuatro
    // llamadas, y no justifican otra dependencia.

    private fun raiz(arbol: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))

    /** Como se llama la carpeta, para mostrarlo. */
    fun nombreCarpeta(cr: ContentResolver, arbol: Uri): String? = runCatching {
        cr.query(raiz(arbol), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Lanza si la carpeta ya no esta o se perdio el permiso. */
    fun crearArchivo(cr: ContentResolver, arbol: Uri, nombre: String): Uri =
        DocumentsContract.createDocument(cr, raiz(arbol), "application/octet-stream", nombre)
            ?: throw java.io.FileNotFoundException("el proveedor no creo el archivo")

    fun borrar(cr: ContentResolver, doc: Uri) {
        runCatching { DocumentsContract.deleteDocument(cr, doc) }
    }

    /** Borra las automaticas que sobran. Un fallo aqui no anula la copia recien hecha. */
    fun podar(cr: ContentResolver, arbol: Uri) {
        runCatching {
            val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(
                arbol, DocumentsContract.getTreeDocumentId(arbol),
            )
            val porNombre = mutableMapOf<String, String>()
            cr.query(
                hijos,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) porNombre[c.getString(1)] = c.getString(0) }
            for (n in sobrantes(porNombre.keys.toList())) {
                val id = porNombre[n] ?: continue
                borrar(cr, DocumentsContract.buildDocumentUriUsingTree(arbol, id))
            }
        }.onFailure { Log.w(TAG, "No se pudo podar: ${it.message}") }
    }

    // ------------------------------------------------------------------
    //  Cuando
    // ------------------------------------------------------------------

    /**
     * La periodica, con un periodo de espera: la primera la hace [ahora] al
     * activar, y sin la espera saldrian dos seguidas.
     *
     * Bateria y espacio "no bajos": una copia con fotos escribe cientos de MB,
     * y hacerla con el 5 % de bateria o el disco lleno es pedir que salga
     * cortada.
     */
    fun programar(ctx: Context, f: Frecuencia) {
        val pedido = PeriodicWorkRequestBuilder<Trabajo>(f.dias, TimeUnit.DAYS)
            .setInitialDelay(f.dias, TimeUnit.DAYS)
            .setConstraints(condiciones())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        runCatching {
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.UPDATE, pedido)
        }
    }

    /** Una ya, al activar o con "Hacer una ahora". */
    fun ahora(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                TRABAJO_YA, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Trabajo>()
                    .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                    .build(),
            )
        }
    }

    fun cancelar(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx).apply {
                cancelUniqueWork(TRABAJO)
                cancelUniqueWork(TRABAJO_YA)
            }
        }
    }

    /** Si hay una corriendo, para que la pantalla lo diga. */
    fun enCurso(ctx: Context): kotlinx.coroutines.flow.Flow<Boolean> =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(TRABAJO_YA)
            .combineEstado(WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(TRABAJO))

    private fun kotlinx.coroutines.flow.Flow<List<androidx.work.WorkInfo>>.combineEstado(
        otro: kotlinx.coroutines.flow.Flow<List<androidx.work.WorkInfo>>,
    ): kotlinx.coroutines.flow.Flow<Boolean> =
        kotlinx.coroutines.flow.combine(this, otro) { a, b ->
            (a + b).any { it.state == androidx.work.WorkInfo.State.RUNNING }
        }

    private fun condiciones() = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    class Trabajo(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as? WtfuckApp ?: return Result.success()
            if (!app.sesion.hayS) return Result.success()
            return when (app.repo.copiaAutomatica()) {
                Resultado.HECHA, Resultado.SIN_CONFIGURAR -> Result.success()
                Resultado.CARPETA_PERDIDA -> {
                    // Reintentar no la va a devolver: hace falta que la
                    // persona elija otra. Se le avisa una vez y se espera.
                    Notificaciones.copiaFallida(
                        applicationContext,
                        "No se encuentra la carpeta de las copias. Elígela otra vez.",
                    )
                    Result.success()
                }
                Resultado.FALLO ->
                    if (runAttemptCount < 3) Result.retry()
                    else {
                        Notificaciones.copiaFallida(
                            applicationContext,
                            "La copia automática no se pudo hacer. Toca para ver por qué.",
                        )
                        Result.success()
                    }
            }
        }
    }
}
