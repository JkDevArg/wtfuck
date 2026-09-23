package com.wtfuck.app.datos

/**
 * Módulo U · Cuándo hay que volver a pedir la huella.
 *
 * ## Qué protege esto, y qué no
 *
 * Protege contra **alguien que tiene el teléfono desbloqueado en la mano**: se
 * lo prestaste, lo dejaste en la mesa, te lo quitaron abierto. Eso es lo que
 * pasa de verdad, y hasta ahora no había nada.
 *
 * **No es cifrado y no hay que venderlo como tal.** El historial ya está
 * cifrado con SQLCipher y su clave vive envuelta por el Keystore; eso es lo
 * que protege de una extracción forense. Este bloqueo es una puerta en la
 * interfaz: quien pueda leer la memoria del proceso o sacar la partición no se
 * detiene aquí. Decir lo contrario haría que alguien confiara en esto para lo
 * que no sirve, y esa es la peor clase de defecto de seguridad.
 */
enum class EsperaBloqueo(val etiqueta: String, val minutos: Int) {
    /** Sin bloqueo. El valor por defecto: nadie debería descubrir que su app se cerró sola. */
    NUNCA("Desactivado", -1),
    INMEDIATO("Al salir de la app", 0),
    UN_MINUTO("Después de 1 minuto", 1),
    CINCO("Después de 5 minutos", 5),
    QUINCE("Después de 15 minutos", 15),
    ;

    val activo: Boolean get() = this != NUNCA
}

/**
 * Si la app tiene que pedir autenticación ahora mismo.
 *
 * ## Por qué el reloj es `elapsedRealtime` y no la hora
 *
 * `elapsedRealtime` cuenta desde el último arranque y **no se puede mover**.
 * La hora del sistema sí: cualquiera con el teléfono en la mano entra en
 * ajustes, la adelanta una hora y el bloqueo de quince minutos se cree
 * caducado. Un candado que se abre cambiando el reloj no es un candado.
 *
 * El precio es que al reiniciar el teléfono el contador vuelve a cero, así que
 * lo guardado queda **en el futuro**. Eso se trata como "bloquear", y es lo
 * correcto: un reinicio es exactamente el momento en que hay menos motivos
 * para confiar en que sigue siendo la misma persona.
 *
 * ## El cero significa "nunca", no "el instante cero"
 *
 * Es una ambigüedad real —`elapsedRealtime` vale 0 en el milisegundo en que
 * arranca el sistema— y se resuelve del lado seguro: **el cero bloquea**. El
 * precio es que quien desbloqueara la app en ese primer milisegundo tendría
 * que hacerlo dos veces; el precio de la otra lectura sería una app que se
 * abre sola cuando no hay nada guardado.
 *
 * @param guardado el `elapsedRealtime` del último desbloqueo. 0 = nunca.
 * @param ahora `SystemClock.elapsedRealtime()`.
 */
fun debeBloquear(espera: EsperaBloqueo, guardado: Long, ahora: Long): Boolean {
    if (!espera.activo) return false
    // Nunca se desbloqueó en esta sesión del sistema.
    if (guardado <= 0L) return true
    // El reloj "retrocedió": hubo un reinicio. Ante la duda, cerrado.
    if (guardado > ahora) return true
    if (espera == EsperaBloqueo.INMEDIATO) return true
    return (ahora - guardado) >= espera.minutos * 60_000L
}

/**
 * Ajustes del bloqueo, en el aparato.
 *
 * Van en `SharedPreferences` normales y no cifradas a propósito: aquí no hay
 * ningún secreto. Lo que se guarda es *cuántos minutos* y *cuándo fue el
 * último desbloqueo*, y las dos cosas las puede leer quien ya tiene root —a
 * quien este bloqueo no pretende parar—. Meterlas en `EncryptedSharedPreferences`
 * daría una sensación de protección que no existe y costaría una dependencia
 * más en el arranque de la app.
 */
class AjustesBloqueo(ctx: android.content.Context) {

    private val p = ctx.getSharedPreferences("wtfuck_bloqueo", android.content.Context.MODE_PRIVATE)

    var espera: EsperaBloqueo
        get() = runCatching { EsperaBloqueo.valueOf(p.getString("espera", null) ?: "") }
            .getOrDefault(EsperaBloqueo.NUNCA)
        set(v) = p.edit().putString("espera", v.name).apply()

    /**
     * `elapsedRealtime` del último desbloqueo, o 0.
     *
     * `commit()` y no `apply()`: esto se escribe justo antes de que la app se
     * vaya a segundo plano, que es cuando el sistema puede matar el proceso.
     * Con `apply()` la escritura es asíncrona y se perdería, y perderla
     * significa **pedir la huella otra vez al volver** dos segundos después.
     * Es una preferencia diminuta; el `commit` no se nota.
     */
    var ultimoDesbloqueo: Long
        get() = p.getLong("ultimo", 0L)
        @android.annotation.SuppressLint("ApplySharedPref")
        set(v) { p.edit().putLong("ultimo", v).commit() }

    fun bloqueadoAhora(ahora: Long): Boolean = debeBloquear(espera, ultimoDesbloqueo, ahora)

    /** Al apagar el bloqueo se borra el rastro: no queda nada que interpretar. */
    fun apagar() {
        espera = EsperaBloqueo.NUNCA
        ultimoDesbloqueo = 0L
    }
}
