package com.wtfuck.app.datos

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wtfuck.app.MainActivity
import com.wtfuck.app.R

/**
 * Atajos: los chats recientes en la fila de "Compartir" del sistema y al
 * mantener pulsado el icono, mas "Nota para mi" fija (ver `res/xml/atajos.xml`).
 *
 * ## Lo que se le cuenta al sistema, y lo que no
 *
 * Un atajo es un nombre que el SISTEMA guarda y muestra fuera de la app. Por
 * eso:
 *  - nunca un chat protegido (ver `ConversacionEnt.protegido`);
 *  - con el bloqueo de la app encendido, ninguno: quien lo enciende no quiere
 *    que sus chats se vean sin desbloquear, y el menu del icono se ve sin
 *    desbloquear nada;
 *  - solo el nombre del chat, nunca un mensaje;
 *  - tres y no mas.
 */
object Atajos {
    const val ACCION_NOTA = "com.wtfuck.app.NOTA"
    const val ACCION_CHAT = "com.wtfuck.app.ABRIR_CHAT"
    const val CATEGORIA = "com.wtfuck.app.category.COMPARTIR"
    const val PREFIJO_CHAT = "chat-"

    fun publicar(ctx: Context, chats: List<ChatFila>, conBloqueo: Boolean) {
        runCatching {
            if (conBloqueo) {
                // Tambien los que el sistema guardo para la fila de compartir:
                // los dinamicos se van, pero esos se quedan si no se piden.
                borrarTodos(ctx)
                return
            }
            val lista = elegidos(chats).map { c ->
                ShortcutInfoCompat.Builder(ctx, PREFIJO_CHAT + c.id)
                    .setShortLabel(c.titulo.take(25).ifBlank { "Chat" })
                    .setLongLabel(c.titulo.ifBlank { "Chat" })
                    .setIcon(
                        IconCompat.createWithResource(
                            ctx, if (c.tipo == "notas") R.drawable.ic_atajo_nota else R.drawable.ic_atajo_chat,
                        ),
                    )
                    .setIntent(
                        Intent(ctx, MainActivity::class.java)
                            .setAction(ACCION_CHAT)
                            .putExtra("conversacionId", c.id),
                    )
                    .setCategories(setOf(CATEGORIA))
                    .setLongLived(true)
                    .build()
            }
            // Puede devolver falso si el sistema limita la frecuencia con la
            // app en segundo plano: se reintenta solo en el proximo cambio.
            ShortcutManagerCompat.setDynamicShortcuts(ctx, lista)
        }
    }

    /** Cuales: los tres mas recientes que se pueden mostrar. Pura, con pruebas. */
    fun elegidos(chats: List<ChatFila>): List<ChatFila> = chats
        .filter { it.soyMiembro && !it.protegido && it.tipo != "canal" }
        .sortedByDescending { it.ultimaFecha ?: 0L }
        .take(3)

    fun borrarTodos(ctx: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(ctx) }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(ctx, ShortcutManagerCompat.getShortcuts(ctx, ShortcutManagerCompat.FLAG_MATCH_CACHED).map { it.id }) }
    }
}

/**
 * El widget: cuantos mensajes sin leer, "Nota para mi" y abrir la app.
 *
 * **Nunca nombres ni mensajes.** El widget esta en la pantalla de inicio, a la
 * vista de cualquiera y sin pasar por el bloqueo de la app. Un numero no dice
 * de quien ni que; un nombre si.
 */
class WidgetWtfuck : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        pintar(ctx, mgr, ids, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(CLAVE, 0))
    }

    companion object {
        private const val PREFS = "widget"
        private const val CLAVE = "no_leidos"

        fun actualizar(ctx: Context, noLeidos: Int) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(CLAVE, noLeidos).apply()
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetWtfuck::class.java))
            if (ids.isNotEmpty()) pintar(ctx, mgr, ids, noLeidos)
        }

        fun texto(n: Int): String = when {
            n <= 0 -> "Nada sin leer"
            n == 1 -> "1 mensaje sin leer"
            n > 999 -> "999+ mensajes sin leer"
            else -> "$n mensajes sin leer"
        }

        private fun pintar(ctx: Context, mgr: AppWidgetManager, ids: IntArray, n: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_wtfuck)
            v.setTextViewText(R.id.widget_estado, texto(n))
            v.setOnClickPendingIntent(R.id.widget_raiz, abrir(ctx, Intent.ACTION_MAIN, 1))
            v.setOnClickPendingIntent(R.id.widget_nota, abrir(ctx, Atajos.ACCION_NOTA, 2))
            mgr.updateAppWidget(ids, v)
        }

        private fun abrir(ctx: Context, accion: String, codigo: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, codigo,
                Intent(ctx, MainActivity::class.java).setAction(accion)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
