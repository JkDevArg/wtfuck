package com.wtfuck.server

import com.wtfuck.protocol.VersionResp

/**
 * Qué versión del APK está publicada.
 *
 * ## Por qué hace falta
 *
 * El APK se reparte fuera de una tienda. Nadie avisa de que hay algo nuevo:
 * quien lo instaló se queda en esa versión hasta que vuelve a la página de
 * descarga por su cuenta, o sea, casi nunca. Con esto la app se entera sola.
 *
 * ## Por qué sale del entorno y no de la base
 *
 * Porque publicar una versión es exactamente lo mismo que desplegarla, y ya
 * hay un sitio donde se escriben los datos de un despliegue. Meterlo en la
 * base añadiría una tabla, una migración y una pantalla para editar tres
 * campos que sólo cambian cuando se sube un APK nuevo.
 *
 * Y tiene una consecuencia útil: **publicar exige reiniciar el servidor, y un
 * reinicio desconecta todos los sockets**. Los clientes reconectan y preguntan
 * de nuevo, así que la novedad llega sola sin ningún mecanismo de aviso. Ver
 * `Actualizador.kt` en la app.
 *
 * ## Apagado por defecto
 *
 * Sin `WTFUCK_APK_VERSION` el endpoint contesta `versionCode = 0` y la app no
 * hace nada. Actualizar el servidor no puede encenderle a nadie un mecanismo
 * que descarga e instala cosas sin que lo haya pedido — la misma regla que en
 * [Invitaciones].
 *
 * ## Lo que esto NO puede hacer, y conviene tenerlo claro
 *
 * No instala nada por su cuenta. Android no deja que una app normal se
 * actualice en silencio: hace falta ser *device owner* (un teléfono
 * administrado, que se enrola con un borrado de fábrica) o app de sistema
 * firmada con la clave de la plataforma. Lo máximo es descargar en segundo
 * plano y que la persona confirme **con un toque**.
 *
 * ## Y por qué esto no convierte al servidor en dueño de los teléfonos
 *
 * Un canal de actualización es, visto de frente, un canal de ejecución remota
 * de código: si el servidor dice "baja esto e instálalo", quien controle el
 * servidor controla los teléfonos. En una app cuya premisa es que el servidor
 * es un buzón tonto en el que no se confía, eso sería una contradicción.
 *
 * Lo que la cierra es que **Android rechaza una actualización firmada con otra
 * clave**. El ancla de confianza es el keystore, no el servidor. Un servidor
 * comprometido no puede inyectar código: como mucho puede ofrecer una versión
 * *antigua y legítima* —un downgrade— y de eso se defiende el cliente
 * negándose a "actualizar" a un número menor o igual al que ya tiene.
 */
object Actualizacion {

    private fun env(nombre: String): String = System.getenv(nombre)?.trim().orEmpty()

    /**
     * Se lee en cada petición y no una sola vez.
     *
     * Es tentador cachearlo con `by lazy` como el resto de la configuración,
     * pero esto se consulta unas pocas veces por cliente y por día: el coste
     * de leer cinco variables de entorno no se nota, y a cambio el valor
     * queda donde se puede mirar sin preguntarse desde cuándo está cacheado.
     */
    fun publicada(): VersionResp {
        val code = env("WTFUCK_APK_VERSION").toIntOrNull() ?: 0
        // Sin versión publicada no se devuelve nada más. Mandar la URL y la
        // huella de un APK que no se anuncia sería publicar por accidente lo
        // que se dejó a medio configurar.
        if (code <= 0) return VersionResp()

        return VersionResp(
            versionCode = code,
            versionName = env("WTFUCK_APK_NOMBRE").ifEmpty { code.toString() },
            url = env("WTFUCK_APK_URL"),
            sha256 = env("WTFUCK_APK_SHA256").lowercase(),
            // No puede ser mayor que la publicada: eso dejaría a todo el mundo
            // fuera, incluida la versión que se acaba de subir. Es un error de
            // dedo fácil de cometer y imposible de diagnosticar desde el
            // teléfono, donde sólo se ve "tienes que actualizar" en bucle.
            minima = (env("WTFUCK_APK_MINIMA").toIntOrNull() ?: 0).coerceAtMost(code),
            notas = env("WTFUCK_APK_NOTAS").take(500),
        )
    }
}
