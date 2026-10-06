package com.wtfuck.app.datos

import android.content.Context
import com.wtfuck.protocol.Baliza
import com.wtfuck.protocol.Curva

/**
 * Mi clave de baliza del modo cerca (fase 1). Ver `Baliza` en el protocolo.
 *
 * ## Que es y quien la tiene
 *
 * 32 bytes al azar con los que este aparato firma su anuncio Bluetooth. La
 * reciben solo mis contactos DIRECTOS -y mis otros aparatos-, dentro de los
 * mensajes normales, cifrada de punta a punta. Con ella me reconocen cuando
 * tengo el modo cerca encendido; sin ella, mi anuncio es ruido que cambia cada
 * cuarto de hora.
 *
 * ## Cuando cambia
 *
 * Al bloquear a alguien: esa persona se queda con la vieja, y con la nueva ya
 * no me reconoce. Los demas la reciben con el proximo mensaje que les mande.
 *
 * Envuelta con el Keystore, como la frase de las copias: ver [CajaFuerte].
 */
class MiBaliza(ctx: Context) {
    private val p = ctx.getSharedPreferences("wtfuck_baliza", Context.MODE_PRIVATE)
    private val caja = CajaFuerte("wtfuck_baliza_v1")

    /** Cuando se creo la clave actual: la "version" que se anota por chat. */
    val version: Long get() = p.getLong("version", 0)

    @Synchronized
    fun clave(): ByteArray {
        p.getString("clave", null)?.let { g -> runCatching { caja.abrir(g) }.getOrNull()?.let { return it } }
        return nueva()
    }

    @Synchronized
    fun rotar() {
        nueva()
    }

    @Synchronized
    fun borrar() {
        p.edit().clear().apply()
        runCatching { caja.tirar() }
    }

    private fun nueva(): ByteArray {
        val k = Baliza.nuevaClave()
        p.edit().putString("clave", caja.cerrar(k)).putLong("version", System.currentTimeMillis()).apply()
        return k
    }
}

/**
 * Lo que el modo cerca necesita para el enlace sin emparejar, sin saber de
 * donde sale: las claves de baliza, las identidades de Signal y la curva.
 */
interface LlavesCerca {
    val curva: Curva?
    fun miBaliza(): ByteArray
    /** Cambia cuando la clave rota: el anuncio se rehace en el acto. */
    fun versionBaliza(): Long
    /** Las balizas de mis contactos: aparato -> clave. */
    suspend fun conocidas(): Map<String, ByteArray>
    /** La identidad publica (32 bytes) de un aparato, o `null` si no se le conoce. */
    fun identidadDe(dispositivo: String): ByteArray?
    /** DH entre mi identidad y una clave publica. */
    fun miEstatico(publica: ByteArray): ByteArray
}
