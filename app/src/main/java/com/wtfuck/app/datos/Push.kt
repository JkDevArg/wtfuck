package com.wtfuck.app.datos

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.wtfuck.protocol.ConfigPush
import com.wtfuck.protocol.RegistrarPushReq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Modulo N · El lado del telefono de los avisos con la app cerrada.
 *
 * ## Por que la configuracion se pide al servidor
 *
 * Lo normal es meter un `google-services.json` en el APK y que el plugin de
 * Gradle lo convierta en recursos. Aqui no: la configuracion se pide a
 * `GET /v1/push/config` y Firebase se inicializa a mano.
 *
 * Tres razones, en orden de peso:
 *
 *  1. **El push se habilita sin recompilar.** Se ponen las variables en el
 *     servidor y el proximo arranque de cada telefono se registra solo. Con el
 *     JSON incrustado habria que publicar una version nueva.
 *  2. **No hay un `google-services.json` por entorno en el repositorio**, que
 *     es de las cosas que terminan con las credenciales de produccion en un
 *     commit.
 *  3. Si el servidor no tiene push, el cliente **no inicializa nada**: ni
 *     Firebase, ni token, ni servicio. Una funcion apagada no deja rastro.
 *
 * Ninguno de los valores que viajan es secreto: identifican al proyecto y no
 * autorizan nada por si solos. El secreto —la clave privada de la cuenta de
 * servicio— vive solo en el servidor y solo el firma con ella.
 *
 * ## Lo que se le concede al servidor, dicho
 *
 * Con esta decision el servidor elige **a que proyecto de Firebase se registra
 * este telefono**. Un servidor comprometido podria apuntarlo a un proyecto
 * ajeno y quedarse con el token; con el token se puede despertar el aparato,
 * nada mas: el aviso no lleva contenido y los mensajes siguen exigiendo sesion
 * y claves para bajarse y descifrarse.
 *
 * Se acepta porque no agrega poder que el servidor no tuviera: ya decide que
 * sobres entrega y ya conoce el token, que se lo manda este mismo cliente. Lo
 * que el servidor **no** puede hacer -leer un mensaje- sigue sin poder hacerlo.
 *
 * La alternativa, el JSON dentro del APK, cambia ese riesgo por otro peor en la
 * practica: credenciales por entorno versionadas en el repositorio y una
 * publicacion nueva cada vez que cambie el proyecto.
 *
 * ## Lo que este telefono recibe
 *
 * Un aviso vacio. Ver el lado del servidor: no hay texto, ni quien escribe, ni
 * conversacion. Lo unico que hace el aviso es despertar el proceso; el proceso
 * abre el socket y baja sus sobres, y recien ahi -ya descifrado- se decide si
 * hay algo que mostrar. Es lo que permite que una notificacion con la pantalla
 * bloqueada no delate el contenido de un mensaje cifrado de punta a punta.
 */
class Push(private val ctx: Context, private val api: ApiCliente, private val sesion: Sesion) {

    private val prefs = ctx.getSharedPreferences("wtfuck_push", Context.MODE_PRIVATE)

    /**
     * La ultima configuracion conocida, en disco.
     *
     * Se guarda para poder inicializar Firebase en el arranque **sin esperar la
     * red**: un aviso puede llegar antes de que el servidor conteste, y sin
     * esto el primero de cada arranque se perderia.
     */
    private var cacheProyecto: String?
        get() = prefs.getString("proyecto", null)
        set(v) { prefs.edit().putString("proyecto", v).apply() }

    private var cacheApp: String?
        get() = prefs.getString("app", null)
        set(v) { prefs.edit().putString("app", v).apply() }

    private var cacheApi: String?
        get() = prefs.getString("api", null)
        set(v) { prefs.edit().putString("api", v).apply() }

    private var cacheRemitente: String?
        get() = prefs.getString("remitente", null)
        set(v) { prefs.edit().putString("remitente", v).apply() }

    /** El ultimo token que se le mando al servidor, para no repetir la peticion. */
    private var tokenRegistrado: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    val disponible: Boolean get() = cacheProyecto != null

    /**
     * Inicializa Firebase con lo que haya en disco. Idempotente.
     *
     * Se llama al arrancar la app Y desde el servicio de mensajeria, porque el
     * servicio puede arrancar el proceso sin que la interfaz se haya creado: en
     * ese caso `Application.onCreate` corrio, pero nada mas.
     */
    fun inicializarSiSePuede(): Boolean {
        val proyecto = cacheProyecto ?: return false
        val app = cacheApp ?: return false
        val apiKey = cacheApi ?: return false
        val remitente = cacheRemitente ?: return false

        // `FirebaseApp.getInstance` lanza si no hay ninguna: es la forma de
        // preguntar "ya esta inicializado" sin guardar un booleano propio, que
        // se desincronizaria si el proceso muriera entre medio.
        if (runCatching { FirebaseApp.getInstance() }.isSuccess) return true

        return runCatching {
            FirebaseApp.initializeApp(
                ctx,
                FirebaseOptions.Builder()
                    .setProjectId(proyecto)
                    .setApplicationId(app)
                    .setApiKey(apiKey)
                    .setGcmSenderId(remitente)
                    .build(),
            )
            true
        }.onFailure { Log.w(TAG, "No se pudo inicializar Firebase: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Pide la configuracion, inicializa y registra el token.
     *
     * Nada de esto es obligatorio para que la app funcione: si falla, se pierde
     * el aviso con la app cerrada y nada mas. Por eso no propaga.
     */
    suspend fun poner() {
        val cfg = runCatching { api.configPush() }.getOrElse {
            Log.i(TAG, "No se pudo pedir la config de push: ${it.message}")
            // Si ya habia una cacheada, se sigue con esa: la red puede estar
            // caida y el push del arranque anterior sigue siendo valido.
            if (!inicializarSiSePuede()) return
            null
        }

        if (cfg != null) {
            if (!cfg.disponible) {
                // El servidor no tiene push. Se limpia lo que hubiera: si se
                // apago a proposito, el telefono no debe seguir registrado.
                if (cacheProyecto != null) {
                    Log.i(TAG, "El servidor ya no tiene push: se olvida la config")
                    olvidar()
                }
                return
            }
            cacheProyecto = cfg.proyectoId
            cacheApp = cfg.appId
            cacheApi = cfg.apiKey
            cacheRemitente = cfg.remitenteId
            if (!inicializarSiSePuede()) return
        }

        val token = runCatching { pedirToken() }.getOrElse {
            Log.w(TAG, "No se pudo obtener el token de push: ${it.message}")
            return
        }
        registrar(token)
    }

    /** Manda el token al servidor. Salta si es el mismo de la ultima vez. */
    suspend fun registrar(token: String, forzar: Boolean = false) {
        if (token.isBlank()) return
        if (!forzar && token == tokenRegistrado) return
        runCatching { api.registrarPush(RegistrarPushReq(token, "fcm")) }
            .onSuccess {
                tokenRegistrado = token
                Log.i(TAG, "Token de push registrado")
            }
            .onFailure { Log.w(TAG, "No se pudo registrar el token: ${it.message}") }
    }

    /**
     * Se da de baja al cerrar sesion.
     *
     * Hace falta de verdad: sin esto, un telefono del que alguien se fue
     * seguiria recibiendo el aviso de que esa cuenta tiene algo nuevo. El aviso
     * no lleva contenido, pero en un aparato prestado saber CUANDO le llegan
     * mensajes a alguien ya es de mas.
     */
    suspend fun quitar() {
        if (sesion.token != null) {
            runCatching { api.borrarPush() }
                .onFailure { Log.i(TAG, "No se pudo dar de baja el push: ${it.message}") }
        }
        // El token del aparato se borra igual, aunque el servidor no contestara:
        // asi el proximo arranque pide uno nuevo en vez de reusar el que quedo
        // asociado a la cuenta anterior.
        runCatching { withContext(Dispatchers.IO) { borrarTokenLocal() } }
        tokenRegistrado = null
    }

    private fun olvidar() {
        prefs.edit().clear().apply()
    }

    // ------------------------------------------------------------------
    //  Puente con la API de callbacks de Firebase
    // ------------------------------------------------------------------

    /**
     * El token, como suspend.
     *
     * Firebase devuelve un `Task`; se envuelve en vez de propagar callbacks por
     * media app. `suspendCancellableCoroutine` y no `await()` para no agregar
     * la dependencia de kotlinx-coroutines-play-services por una funcion.
     */
    private suspend fun pedirToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    private suspend fun borrarTokenLocal() = suspendCancellableCoroutine<Unit> { cont ->
        if (runCatching { FirebaseApp.getInstance() }.isFailure) {
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        FirebaseMessaging.getInstance().deleteToken()
            .addOnCompleteListener { if (cont.isActive) cont.resume(Unit) }
    }

    private companion object {
        const val TAG = "Push"
    }
}
