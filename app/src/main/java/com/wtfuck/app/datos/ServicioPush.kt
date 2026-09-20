package com.wtfuck.app.datos

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Modulo N · Lo que hace el telefono cuando lo despiertan.
 *
 * ## Lo que NO hace: mostrar el aviso que llego
 *
 * Porque el aviso no trae nada que mostrar. Ver el lado del servidor: el
 * payload es `{"w":"1"}` y nada mas. Este servicio abre el socket, baja los
 * sobres pendientes, los descifra, y **recien ahi** el camino normal de la app
 * decide si hay una notificacion que publicar y con que texto.
 *
 * Es un paso mas de trabajo a cambio de lo unico que hace que el cifrado de
 * punta a punta signifique algo en la pantalla de bloqueo: el proveedor de push
 * nunca vio el contenido, porque nunca lo tuvo.
 *
 * ## El limite del que hay que acordarse
 *
 * `onMessageReceived` tiene una ventana corta -del orden de diez segundos- y
 * cuando termina, Android puede matar el proceso. Por eso aqui se BLOQUEA a
 * proposito: si se lanzara una corrutina y se volviera, el proceso podria morir
 * antes de que el socket llegue a conectar, y el aviso se habria gastado para
 * nada.
 *
 * Para una llamada eso no alcanzaria por si solo, y no hace falta que alcance:
 * en cuanto la oferta entra por el socket, `ServicioLlamadas` arranca el
 * servicio en primer plano (K.8), que ya no depende de esta ventana.
 */
class ServicioPush : FirebaseMessagingService() {

    /**
     * Token nuevo: Android lo rota solo, al reinstalar o al restaurar un
     * respaldo. Si no se reenvia, el servidor sigue despertando a un token
     * muerto y la persona deja de recibir avisos sin que nada falle a la vista.
     */
    override fun onNewToken(token: String) {
        val app = applicationContext as? WtfuckApp ?: return
        if (app.sesion.token == null) return
        runBlocking {
            withTimeoutOrNull(8_000) { app.push.registrar(token, forzar = true) }
        }
    }

    override fun onMessageReceived(mensaje: RemoteMessage) {
        val app = applicationContext as? WtfuckApp ?: return

        // Sin sesion no hay nada que bajar. Pasa si alguien cerro sesion y el
        // servidor todavia no proceso la baja del token.
        if (app.sesion.token == null) {
            Log.i(TAG, "Aviso con la sesion cerrada: se ignora")
            return
        }

        Log.i(TAG, "Despertado por push")
        app.push.inicializarSiSePuede()

        runBlocking {
            // Se espera a que el socket traiga algo, con techo. El `iniciar`
            // es idempotente: si la app ya estaba viva y conectada, esto no
            // hace nada y se sale enseguida.
            withTimeoutOrNull(VENTANA_MS) {
                app.repo.iniciar()
                app.repo.esperarQuietud()
            }
        }
    }

    private companion object {
        const val TAG = "ServicioPush"

        /**
         * Nueve segundos. La ventana real que da Android ronda los diez y no
         * esta documentada como garantia, asi que se deja margen: que el
         * sistema mate el proceso a mitad de un descifrado es peor que cortar
         * un segundo antes y dejar el sobre pendiente para el proximo arranque.
         */
        const val VENTANA_MS = 9_000L
    }
}
