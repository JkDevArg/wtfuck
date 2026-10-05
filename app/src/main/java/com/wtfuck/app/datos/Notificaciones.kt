package com.wtfuck.app.datos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wtfuck.app.MainActivity
import com.wtfuck.app.R

/**
 * Avisos del sistema operativo.
 *
 * Solo se notifica lo que el servidor ya conoce como metadato -por ejemplo, que
 * te agregaron a un grupo-. El CONTENIDO de los mensajes no se pone aqui: desde
 * la fase 4 va cifrado, y una notificacion con el texto dentro anularia el
 * cifrado en la pantalla de bloqueo.
 */
object Notificaciones {

    /**
     * L.6 · Que avisa y que no, por categoria.
     *
     * ## Por que los ajustes viven en el APARATO y no en la cuenta
     *
     * Una notificacion la recibe un telefono, no una persona. Con
     * multi-dispositivo, querer que suene el telefono y no la tablet es lo
     * normal, y guardarlo en el servidor obligaria a que las dos se comporten
     * igual. Ademas el servidor no tiene por que saber que te molesta.
     *
     * ## Por que hay interruptores propios si Android ya tiene canales
     *
     * Desde Android 8 el sistema manda sobre el sonido, la vibracion y la
     * importancia de cada canal, y eso se respeta: hay un acceso directo a los
     * ajustes del sistema para lo que le toca al sistema. Lo de aqui es otra
     * cosa y mas fuerte: si una categoria esta apagada, la app **no publica la
     * notificacion**. No es un volumen: es no decirlo.
     */
    class Ajustes(ctx: Context) {
        private val p = ctx.getSharedPreferences("wtfuck_avisos", Context.MODE_PRIVATE)

        var mensajes: Boolean
            get() = p.getBoolean("mensajes", true)
            set(v) { p.edit().putBoolean("mensajes", v).apply() }

        var grupos: Boolean
            get() = p.getBoolean("grupos", true)
            set(v) { p.edit().putBoolean("grupos", v).apply() }

        var canales: Boolean
            get() = p.getBoolean("canales", true)
            set(v) { p.edit().putBoolean("canales", v).apply() }

        var llamadas: Boolean
            get() = p.getBoolean("llamadas", true)
            set(v) { p.edit().putBoolean("llamadas", v).apply() }

        /**
         * Si el nombre de quien escribe aparece en la notificacion.
         *
         * El texto del mensaje NO se muestra nunca -va cifrado, y ponerlo en la
         * pantalla de bloqueo anularia el cifrado justo donde mas se ve-, pero
         * el nombre ya es informacion: "@fulano te escribio" a la vista de
         * quien tenga el telefono delante dice con quien hablas. Encendido por
         * defecto porque una notificacion que no dice de quien es no sirve de
         * mucho, y apagable porque hay situaciones donde eso importa mas.
         */
        var mostrarQuien: Boolean
            get() = p.getBoolean("mostrarQuien", true)
            set(v) { p.edit().putBoolean("mostrarQuien", v).apply() }

        /**
         * Las advertencias y sanciones NO se pueden apagar, y por eso no hay
         * interruptor. Una advertencia que no llega no cumple su unica funcion,
         * que es dar la oportunidad de corregir antes de la sancion; si se
         * pudiera silenciar, sancionar despues seria una emboscada.
         */
        val moderacion = true
    }

    private const val CANAL_MENSAJES = "mensajes"
    private const val CANAL_GRUPOS = "grupos"
    private const val CANAL_CANALES = "canales"
    const val CANAL_LLAMADAS = "llamadas"

    /**
     * Canal aparte para moderacion, y con importancia alta.
     *
     * No es para que moleste mas: es que una advertencia que el usuario no ve
     * no sirve de nada. El punto de advertir es dar la oportunidad de corregir,
     * y eso exige que llegue. Silenciarla la convierte en una fila de una tabla
     * que el interesado nunca leyo.
     */
    private const val CANAL_MODERACION = "moderacion"

    /**
     * Modulo AM. El aviso de que la app esta leyendo donde estas.
     *
     * Importancia BAJA a proposito, y no es descuido: esta notificacion no
     * avisa de nada nuevo cada vez que se actualiza —se refresca sola cada
     * minuto mientras dure el compartido— y con importancia alta sonaria o
     * vibraria por algo que la persona ya sabe porque lo pidio. Lo que tiene
     * que hacer es ESTAR, no interrumpir.
     *
     * Lo que si es obligatorio es que no se pueda descartar, y eso lo decide
     * el `setOngoing(true)` de la notificacion, no el canal.
     */
    const val CANAL_UBICACION = "ubicacion"

    fun crearCanales(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_MENSAJES,
                "Mensajes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Mensajes directos" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_CANALES,
                "Canales",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                // Baja a proposito: un canal publica a mucha gente a la vez y
                // no espera respuesta. Tratarlo como un mensaje directo es la
                // forma mas rapida de que alguien apague todo.
                description = "Publicaciones de los canales que sigues"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_LLAMADAS,
                "Llamadas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Llamadas entrantes" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_GRUPOS,
                "Grupos",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Cuando alguien te agrega a un grupo" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_UBICACION,
                "Ubicación en tiempo real",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Mientras compartes dónde estás" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_MODERACION,
                "Advertencias y sanciones",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Cuando hay una decision de moderacion sobre tu cuenta" }
        )
    }

    /**
     * Advertencia o sancion sobre mi cuenta.
     *
     * Lleva a "Mi cuenta" y no al chat: lo que hace falta explicar es que paso
     * y que hacer distinto, y eso no vive en ninguna conversacion.
     *
     * El texto de la notificacion es deliberadamente corto y no incluye el
     * detalle: la pantalla de bloqueo es publica, y "te advirtieron por acoso"
     * a la vista de cualquiera es un castigo que nadie impuso.
     */
    fun moderacion(ctx: Context, esSancion: Boolean) {
        if (!permitido(ctx)) return

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("abrir", "mi-cuenta")
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, 991, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(ctx, CANAL_MODERACION)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(if (esSancion) "Hay una sancion en tu cuenta" else "Recibiste una advertencia")
            .setContentText("Toca para ver de que se trata.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(991, n) }
    }

    /**
     * Mensaje nuevo.
     *
     * NO lleva el texto. No es una omision: el contenido va cifrado de extremo
     * a extremo, y copiarlo a la pantalla de bloqueo lo dejaria legible
     * justamente donde cualquiera lo ve sin desbloquear nada. Lo unico que
     * puede ir es de quien es, y hasta eso se puede apagar.
     */
    fun mensaje(
        ctx: Context,
        autor: String,
        titulo: String,
        conversacionId: String,
        esGrupo: Boolean,
        /** Quien lo mando pidio "sin sonido": se muestra, pero no suena ni vibra. */
        silencioso: Boolean = false,
        /** Me menciona: se dice, sin decir que. */
        mencionado: Boolean = false,
    ) {
        val a = Ajustes(ctx)
        if (esGrupo && !a.grupos) return
        if (!esGrupo && !a.mensajes) return
        if (!permitido(ctx)) return

        val quien = if (a.mostrarQuien) {
            if (esGrupo) titulo else "@$autor"
        } else "wtfuck"
        val cuerpo = when {
            !a.mostrarQuien -> "Tienes un mensaje nuevo"
            mencionado && esGrupo -> "@$autor te mencionó en el grupo"
            esGrupo -> "@$autor escribio en el grupo"
            else -> "Te escribio"
        }

        publicar(
            ctx,
            canal = if (esGrupo) CANAL_GRUPOS else CANAL_MENSAJES,
            id = conversacionId.hashCode(),
            titulo = quien,
            texto = cuerpo,
            conversacionId = conversacionId,
            conAcciones = true,
            silencioso = silencioso,
        )
    }

    /** Publicacion en un canal al que estoy suscrito. */
    fun canal(ctx: Context, titulo: String, conversacionId: String) {
        if (!Ajustes(ctx).canales) return
        if (!permitido(ctx)) return
        publicar(
            ctx,
            canal = CANAL_CANALES,
            id = conversacionId.hashCode(),
            titulo = titulo,
            texto = "Publicacion nueva",
            conversacionId = conversacionId,
        )
    }

    /**
     * Llamada entrante.
     *
     * Existe porque sin servicio en primer plano la llamada solo suena si la
     * app esta en pantalla; con esto, al menos se ve. No reemplaza al servicio
     * -si Android mata el proceso no llega nada-, y eso sigue declarado.
     */
    fun llamada(ctx: Context, deQuien: String, conVideo: Boolean, conversacionId: String) {
        if (!Ajustes(ctx).llamadas) return
        if (!permitido(ctx)) return

        val id = ("llamada" + conversacionId).hashCode()
        val abrir = android.app.PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversacionId", conversacionId)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val b = NotificationCompat.Builder(ctx, CANAL_LLAMADAS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(if (conVideo) "Videollamada de @$deQuien" else "Llamada de @$deQuien")
            .setContentText("Toca para contestar")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(abrir)
            // Pantalla completa: es lo que hace que una llamada entrante se vea
            // con el telefono bloqueado en vez de quedar como una linea mas.
            .setFullScreenIntent(abrir, true)

        // Con botones de verdad, no solo "toca para abrir la app".
        //
        // Esta notificacion es la UNICA que se ve cuando la app esta en
        // segundo plano y el servicio en primer plano no pudo arrancar
        // -Android no lo permite desde el fondo-. Si aqui no se pudiera
        // contestar ni rechazar, la llamada entrante seria una notificacion
        // decorativa. Tocar una accion de notificacion SI da permiso temporal
        // para pasar a primer plano, asi que desde aqui si se puede.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val quien = androidx.core.app.Person.Builder()
                .setName("@$deQuien").setImportant(true).build()
            b.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    quien,
                    ServicioLlamadaFg.intentDeAccion(ctx, ServicioLlamadaFg.ACCION_RECHAZAR, id + 1),
                    ServicioLlamadaFg.intentDeAccion(ctx, ServicioLlamadaFg.ACCION_CONTESTAR, id + 2),
                )
            )
        } else {
            b.addAction(
                R.drawable.ic_notificacion, "Contestar",
                ServicioLlamadaFg.intentDeAccion(ctx, ServicioLlamadaFg.ACCION_CONTESTAR, id + 2),
            )
            b.addAction(
                R.drawable.ic_notificacion, "Rechazar",
                ServicioLlamadaFg.intentDeAccion(ctx, ServicioLlamadaFg.ACCION_RECHAZAR, id + 1),
            )
        }

        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    /** Se borra la notificacion de llamada: la llamada ya no esta. */
    fun quitarLlamada(ctx: Context, conversacionId: String) {
        runCatching {
            NotificationManagerCompat.from(ctx)
                .cancel(("llamada" + conversacionId).hashCode())
        }
    }

    /** El armado comun. Cada aviso solo decide que canal, que texto y a donde. */
    private fun publicar(
        ctx: Context,
        canal: String,
        id: Int,
        titulo: String,
        texto: String,
        conversacionId: String,
        /** Responder y marcar como leido. Solo en mensajes: a un canal no se contesta. */
        conAcciones: Boolean = false,
        silencioso: Boolean = false,
    ) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversacionId", conversacionId)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, id, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // `setSilent` y no un canal aparte: es una decision por mensaje, y
            // un canal "silencioso" quedaria en los ajustes del sistema como
            // una categoria mas que nadie entiende.
            .setSilent(silencioso)
        // Con el bloqueo de la app activo NO se ofrecen: contestar desde la
        // cortina seria entrar a la conversacion sin desbloquear, que es
        // justo lo que el bloqueo existe para impedir.
        if (conAcciones && !AjustesBloqueo(ctx).espera.activo) {
            b.addAction(accionResponder(ctx, id, conversacionId))
            b.addAction(accionLeido(ctx, id, conversacionId))
        }
        val n = b.build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun agregadoAGrupo(ctx: Context, actor: String, grupo: String, conversacionId: String) {
        if (!Ajustes(ctx).grupos) return
        if (!permitido(ctx)) return

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversacionId", conversacionId)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx,
            conversacionId.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(ctx, CANAL_GRUPOS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(grupo)
            .setContentText("@$actor te agrego a este grupo")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        runCatching {
            NotificationManagerCompat.from(ctx).notify(conversacionId.hashCode(), n)
        }
    }

    /**
     * Quita la notificacion despues de contestar desde ella.
     *
     * Cancelarla a secas NO alcanza desde Android 15: tras una respuesta
     * directa el sistema marca la notificacion con
     * `LIFETIME_EXTENDED_BY_DIRECT_REPLY` e ignora el `cancel` hasta que la
     * app publique una version nueva. Se vio en el emulador: la respuesta
     * salia y la notificacion se quedaba con su circulo de "enviando".
     *
     * Y tampoco sirve "publicar una nueva y cancelar": se probo, y el sistema,
     * que todavia tenia la respuesta en curso, volvia a poner la ORIGINAL con
     * la vida extendida. Lo que Android espera tras una respuesta directa es
     * una ACTUALIZACION, no una cancelacion.
     *
     * Asi que se publica una version nueva -silenciosa, que dice que salio- y
     * se deja que caduque sola en un segundo y medio. Sin `cancel`: una
     * caducidad la quita el sistema y no dispara la extension.
     */
    fun cerrarTrasResponder(ctx: Context, id: Int) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!permitido(ctx)) {
            runCatching { nm.cancel(id) }
            return
        }
        val n = NotificationCompat.Builder(ctx, CANAL_MENSAJES)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentText("Respuesta enviada")
            .setSilent(true)
            .setTimeoutAfter(1_500)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private fun intentAccion(ctx: Context, accion: String, id: Int, conv: String) =
        Intent(ctx, ReceptorAccionNotificacion::class.java).apply {
            action = accion
            putExtra(ReceptorAccionNotificacion.EXTRA_CONVERSACION, conv)
            putExtra(ReceptorAccionNotificacion.EXTRA_NOTIFICACION, id)
        }

    /**
     * "Responder" con campo de texto.
     *
     * MUTABLE a proposito, y es seguro: el sistema tiene que poder escribir la
     * respuesta dentro del intent, y el intent es EXPLICITO -apunta a nuestro
     * receptor, que no esta exportado-, asi que nadie puede redirigirlo.
     */
    private fun accionResponder(ctx: Context, id: Int, conv: String): NotificationCompat.Action {
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, id * 31 + 1, intentAccion(ctx, ReceptorAccionNotificacion.ACCION_RESPONDER, id, conv),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0),
        )
        val entrada = androidx.core.app.RemoteInput.Builder(ReceptorAccionNotificacion.CLAVE_RESPUESTA)
            .setLabel("Responder")
            .build()
        return NotificationCompat.Action.Builder(R.drawable.ic_notificacion, "Responder", pi)
            .addRemoteInput(entrada)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun accionLeido(ctx: Context, id: Int, conv: String): NotificationCompat.Action {
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, id * 31 + 2, intentAccion(ctx, ReceptorAccionNotificacion.ACCION_LEIDO, id, conv),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notificacion, "Marcar como leído", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /** En Android 13+ notificar sin permiso lanza; aqui simplemente no se hace. */
    private fun permitido(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
