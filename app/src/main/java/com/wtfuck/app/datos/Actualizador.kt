package com.wtfuck.app.datos

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import com.wtfuck.app.BuildConfig
import com.wtfuck.protocol.VersionResp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Enterarse de que hay una versión nueva, bajarla y ofrecer instalarla.
 *
 * ## Lo que Android NO deja hacer, y conviene saberlo antes de leer el resto
 *
 * Una app normal **no puede actualizarse sola en silencio**. Para eso hay que
 * ser *device owner* —un teléfono administrado, que se enrola con un borrado
 * de fábrica— o app de sistema firmada con la clave de la plataforma. Un APK
 * que la gente baja de una página no es ninguna de las dos cosas.
 *
 * Lo máximo que se puede hacer es lo que hace esto: enterarse, **descargar en
 * segundo plano**, y que la persona confirme con **un toque** en el diálogo
 * del sistema. Es lo mismo que hacen el APK directo de Signal, el de Telegram
 * y F-Droid. No es una limitación de esta implementación; es el techo.
 *
 * Lo que sí se evita es todo lo demás: volver a la página, buscar el enlace,
 * bajar con el navegador, abrir el archivo y pelearse otra vez con el permiso
 * de "orígenes desconocidos" — que ya está concedido desde la primera vez.
 *
 * ## Por qué no hace falta un push
 *
 * Publicar una versión significa cambiar `WTFUCK_APK_VERSION` y **reiniciar el
 * servidor**. Un reinicio desconecta todos los sockets, los clientes
 * reconectan, y [alReconectar] pregunta. La novedad llega sola sin ningún
 * mecanismo de aviso, sin Firebase y sin contarle a Google cada vez que se
 * publica un build — que en una app de mensajería privada no es un detalle.
 *
 * Los que estaban cerrados preguntan al abrir. Entre las dos cosas no queda
 * nadie fuera.
 *
 * ## La parte de seguridad, que es la que importa
 *
 * Esto es, visto de frente, un canal de ejecución remota de código: si el
 * servidor dice "baja esto e instálalo", quien controle el servidor controla
 * los teléfonos. En una app cuya premisa es que el servidor es un buzón tonto
 * en el que no se confía, eso sería una contradicción.
 *
 * Lo que la cierra es que **Android rechaza una actualización firmada con una
 * clave distinta**. El ancla de confianza es el keystore, no el servidor. Un
 * servidor comprometido no puede inyectar código propio; como mucho puede
 * ofrecer una versión *antigua y legítima*. De eso se defiende [hayQueBajar]
 * negándose a "actualizar" a un número que no sea mayor que el instalado.
 *
 * El SHA-256 se comprueba igual, pero no es la defensa principal: sirve contra
 * una descarga cortada o corrupta, que es el caso común de verdad.
 */
class Actualizador(private val ctx: Context, private val api: ApiCliente) {

    companion object {
        /** Cada cuánto se vuelve a preguntar, como mucho. */
        private const val CADA_MS = 6L * 60 * 60 * 1000

        /**
         * Nombre de la acción del resultado del instalador.
         *
         * Lleva el paquete delante porque un `Intent` implícito con un nombre
         * genérico lo puede recibir cualquiera.
         */
        private const val ACCION_RESULTADO = "com.wtfuck.app.INSTALACION"
    }

    private val prefs = ctx.getSharedPreferences("wtfuck_version", Context.MODE_PRIVATE)

    /** La versión que esta app es. */
    val instalada: Int get() = BuildConfig.VERSION_CODE

    // ------------------------------------------------------------------
    //  Preguntar
    // ------------------------------------------------------------------

    /**
     * Pregunta si hay algo nuevo. Devuelve `null` si no hay nada que hacer.
     *
     * Se traga cualquier fallo: un servidor viejo que no conoce la ruta, sin
     * red, o una respuesta rara. Ninguna de esas es razón para molestar a
     * nadie — la app funciona igual de bien sin actualizarse.
     */
    suspend fun consultar(forzar: Boolean = false): VersionResp? = withContext(Dispatchers.IO) {
        if (!forzar && !tocaPreguntar()) return@withContext null
        val v = runCatching { api.versionPublicada() }.getOrNull() ?: return@withContext null
        prefs.edit().putLong("ultima", System.currentTimeMillis()).apply()
        if (!hayQueBajar(v)) null else v
    }

    private fun tocaPreguntar(): Boolean =
        System.currentTimeMillis() - prefs.getLong("ultima", 0) > CADA_MS

    /**
     * Se llama cuando el socket reconecta: ahí es donde llega una publicación.
     *
     * **Fuerza la consulta**, saltándose el límite de [CADA_MS]. Antes no lo
     * hacía, y eso vaciaba de sentido a esta función: el comentario prometía
     * enterarse al reconectar y el límite de seis horas lo impedía. Se publicaba
     * una versión y la gente tardaba hasta seis horas en verla, aunque hubiera
     * abierto la app diez veces.
     *
     * No castiga al servidor: reconectar no es frecuente —el socket aguanta
     * abierto— y la respuesta es un JSON de cuatro campos. Es muchísimo menos
     * tráfico que el primer mensaje que se manda después de esa reconexión.
     */
    suspend fun alReconectar(): VersionResp? = consultar(forzar = true)

    fun hayQueBajar(v: VersionResp): Boolean = PoliticaActualizacion.hayQueBajar(v, instalada)

    fun estaObsoleta(v: VersionResp): Boolean = PoliticaActualizacion.estaObsoleta(v, instalada)

    /**
     * Si ya se avisó de esta versión y la persona dijo que luego.
     *
     * Por `versionCode` y no un booleano: decir "ahora no" a la 4 no puede
     * silenciar también la 5. Un aviso que se apaga para siempre con un toque
     * accidental deja a alguien en una versión vieja sin saberlo.
     */
    fun yaSeAviso(v: VersionResp): Boolean = prefs.getInt("descartada", 0) >= v.versionCode

    fun recordarMasTarde(v: VersionResp) {
        prefs.edit().putInt("descartada", v.versionCode).apply()
    }

    // ------------------------------------------------------------------
    //  Bajar
    // ------------------------------------------------------------------

    /**
     * Descarga el APK y comprueba su huella.
     *
     * Va a la caché y no a Descargas: es un archivo temporal que sólo le sirve
     * al instalador, y dejarlo en una carpeta pública sería dejar un APK
     * suelto en el teléfono de todo el mundo después de cada actualización.
     */
    suspend fun bajar(v: VersionResp, progreso: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val destino = File(ctx.cacheDir, "wtfuck-${v.versionCode}.apk")
                // Si ya está bajado y cuadra, no se vuelve a bajar. Pasa al
                // reintentar después de cerrar el diálogo del sistema.
                if (destino.exists() && huella(destino) == v.sha256) return@runCatching destino

                api.descargarA(v.url, destino, progreso)

                val real = huella(destino)
                if (real != v.sha256) {
                    destino.delete()
                    // Se borra antes de lanzar: un APK a medio bajar en la
                    // caché se reintentaría igual de mal la próxima vez.
                    error("La descarga no coincide con la huella publicada.")
                }
                destino
            }
        }

    private fun huella(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { entrada ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = entrada.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    //  Instalar
    // ------------------------------------------------------------------

    /**
     * Le entrega el APK al instalador de Android.
     *
     * A partir de aquí manda el sistema: enseña su diálogo de confirmación y
     * no hay forma de saltárselo. Esta función termina en cuanto el diálogo
     * aparece, no cuando la instalación acaba — si acaba bien, el proceso se
     * reinicia con la versión nueva y ya no hay nadie escuchando.
     *
     * Se usa `PackageInstaller` y no un `Intent` con `FileProvider` porque la
     * sesión recibe el APK por un flujo: no hace falta exponer el archivo a
     * otra app ni declarar un provider para un temporal de la caché.
     */
    fun instalar(apk: File): Result<Unit> = runCatching {
        val inst = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(ctx.packageName)

        val idSesion = inst.createSession(params)
        inst.openSession(idSesion).use { sesion ->
            sesion.openWrite("apk", 0, apk.length()).use { salida ->
                apk.inputStream().use { it.copyTo(salida) }
                sesion.fsync(salida)
            }
            sesion.commit(intentDeResultado().intentSender)
        }
    }

    private fun intentDeResultado(): PendingIntent {
        val i = Intent(ACCION_RESULTADO).setPackage(ctx.packageName)
        return PendingIntent.getBroadcast(
            ctx, 0, i,
            // MUTABLE porque el sistema le añade los extras del resultado.
            // Con FLAG_IMMUTABLE llega vacío y no hay forma de saber si la
            // persona confirmó, canceló o falló.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * Escucha el resultado y, si el sistema lo pide, saca su diálogo.
     *
     * `STATUS_PENDING_USER_ACTION` es el caso normal, no un error: significa
     * "tengo el APK, falta que lo confirme una persona". El `Intent` que viene
     * dentro es el diálogo del sistema, y hay que lanzarlo — si nadie lo hace,
     * la instalación se queda esperando para siempre y desde fuera parece que
     * el botón no funciona.
     */
    fun escucharResultado(alTerminar: (ok: Boolean, mensaje: String) -> Unit): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val dialogo = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        dialogo?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        dialogo?.let { ctx.startActivity(it) }
                    }
                    PackageInstaller.STATUS_SUCCESS ->
                        alTerminar(true, "Instalada.")
                    else -> alTerminar(
                        false,
                        i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "No se pudo instalar.",
                    )
                }
            }
        }
        ContextCompat.registrarNoExportado(ctx, r, IntentFilter(ACCION_RESULTADO))
        return r
    }
}

/**
 * `registerReceiver` con la bandera de exportación, que es obligatoria desde
 * Android 14 y hace falta que sea NO exportado: el resultado de la instalación
 * sólo lo manda el sistema a esta app, y un receptor exportado dejaría que
 * cualquier otra le mandara un resultado inventado.
 */
private object ContextCompat {
    fun registrarNoExportado(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, f)
        }
    }
}

/**
 * Qué se acepta y qué no. Sin `Context`, a propósito.
 *
 * Es la única parte de la actualización que **decide** algo con consecuencias
 * de seguridad; el resto es fontanería. Separarla la deja probable en JUnit
 * normal, sin emulador ni Robolectric — y una regla que se puede probar en
 * medio segundo se prueba, mientras que una que necesita un teléfono
 * conectado se comprueba a ojo una vez y nunca más.
 */
object PoliticaActualizacion {

    /**
     * Si esa versión es realmente instalable.
     *
     * `> instalada` y no `!=`: un servidor comprometido —o simplemente mal
     * configurado— podría anunciar una versión **anterior**. Android también
     * rechaza los downgrades, pero comprobarlo aquí evita descargar decenas de
     * megas para que el instalador diga que no al final, y deja la regla
     * escrita en un sitio donde se puede leer.
     *
     * El `https` no es opcional: de esa URL se baja un archivo que se va a
     * instalar. Por http, cualquiera en el camino elige cuál. La firma que
     * comprueba Android convertiría ese ataque en un fallo de instalación en
     * vez de en código ajeno ejecutándose — pero deja la actualización
     * igualmente rota, que ya es bastante.
     */
    fun hayQueBajar(v: VersionResp, instalada: Int): Boolean =
        v.versionCode > instalada &&
            v.url.startsWith("https://") &&
            v.sha256.length == 64

    /**
     * Si esta versión ya no puede seguir funcionando contra este servidor.
     *
     * Distinto de "hay una nueva": aquí no actualizar deja la app rota, así
     * que el aviso no se puede descartar.
     */
    fun estaObsoleta(v: VersionResp, instalada: Int): Boolean =
        v.minima > 0 && instalada < v.minima
}
