package com.wtfuck.app.datos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Responder o marcar como leido desde la notificacion, sin abrir la app.
 *
 * ## Por que un receptor y no la Activity
 *
 * Porque la gracia es NO abrir nada: se contesta desde la cortina y se sigue
 * con lo que se estaba haciendo. Abrir la Activity para eso es lo que hace que
 * responder "ok" cueste tres pantallas.
 *
 * ## Lo que hace cada accion
 *
 *  - **Responder**: el texto entra por la cola de envio normal
 *    (`enviarTexto`), con su cifrado y sus reintentos: si no hay red, sale
 *    solo cuando vuelva, igual que uno escrito en el chat. Responder es haber
 *    leido, asi que tambien marca el chat como leido.
 *  - **Marcar como leido**: lo mismo que abrir el chat, sin abrirlo. El acuse
 *    sale con las reglas de siempre (`sincronizarLecturas`): si la persona
 *    tiene apagadas las confirmaciones, el servidor no las reparte.
 *
 * ## Por que corre con `goAsync`
 *
 * Un receptor tiene unos diez segundos y despues el sistema puede matar el
 * proceso. Enviar y acusar son operaciones de red: se hacen en el ambito de la
 * app, con techo, y se avisa al sistema al terminar.
 */
class ReceptorAccionNotificacion : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as? WtfuckApp ?: return
        val conv = intent.getStringExtra(EXTRA_CONVERSACION).orEmpty()
        val idNotificacion = intent.getIntExtra(EXTRA_NOTIFICACION, 0)
        if (conv.isBlank() || app.sesion.token == null) return

        // Si se activo el bloqueo despues de publicar la notificacion, la
        // accion vieja sigue en la cortina. Se comprueba aqui tambien: la
        // decision de publicarla no basta.
        if (app.bloqueo.espera.activo) {
            Log.i(TAG, "Accion ignorada: la app tiene bloqueo")
            return
        }

        val texto = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CLAVE_RESPUESTA)?.toString()?.trim().orEmpty()

        val pendiente = goAsync()
        app.ambito.launch {
            try {
                withTimeoutOrNull(9_000) {
                    app.repo.iniciar()
                    when (intent.action) {
                        ACCION_RESPONDER -> if (texto.isNotEmpty()) {
                            app.repo.enviarTexto(conv, texto)
                            app.repo.marcarLeidaLocal(conv)
                            runCatching { app.repo.sincronizarLecturas(conv) }
                        }
                        ACCION_LEIDO -> {
                            app.repo.marcarLeidaLocal(conv)
                            runCatching { app.repo.sincronizarLecturas(conv) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo completar la accion: ${e.message}")
            } finally {
                // Se quita la notificacion: ya se contesto o ya se leyo. Con
                // una respuesta, dejarla daria un circulo de "enviando" que
                // Android no cierra solo.
                if (intent.action == ACCION_RESPONDER) Notificaciones.cerrarTrasResponder(ctx, idNotificacion)
                else runCatching { NotificationManagerCompat.from(ctx).cancel(idNotificacion) }
                pendiente.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AccionNotificacion"
        const val ACCION_RESPONDER = "com.wtfuck.app.RESPONDER"
        const val ACCION_LEIDO = "com.wtfuck.app.MARCAR_LEIDO"
        const val EXTRA_CONVERSACION = "conversacionId"
        const val EXTRA_NOTIFICACION = "notificacionId"
        const val CLAVE_RESPUESTA = "respuesta"
    }
}
