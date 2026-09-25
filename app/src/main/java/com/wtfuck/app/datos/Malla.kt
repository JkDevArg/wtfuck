package com.wtfuck.app.datos

/**
 * Módulo AF · Quién ofrece a quién en una llamada de grupo.
 *
 * ## El problema que resuelve
 *
 * Quien llama ofrece a todos y cada uno le responde. Pero entre ellos no pasa
 * nada: en una llamada de tres, B y C hablan los dos con A y **no se oyen
 * entre sí**. Para cerrar la malla, al contestar hay que ofrecer también a los
 * demás.
 *
 * Y ahí aparece el *glare*: si B y C se ofrecen a la vez, cada uno recibe una
 * oferta mientras espera una respuesta, y las dos conexiones quedan a medio
 * negociar. Hace falta que **exactamente uno** de los dos ofrezca, decidido
 * sin mandar ningún mensaje extra.
 *
 * ## La regla
 *
 * Ofrece el del id de dispositivo **menor**. Los dos lados conocen los dos
 * ids, son únicos, y la comparación da lo mismo mirada desde cualquiera de
 * los dos: uno ofrece y el otro espera, siempre.
 *
 * Vive aquí y no dentro del servicio porque es una decisión de una línea de la
 * que depende que una llamada de grupo conecte o no, y así se puede probar sin
 * WebRTC, sin red y sin dos teléfonos.
 */
object Malla {

    /**
     * Si me toca ofrecer a `suyo`.
     *
     * Requisitos, y los tres importan:
     *
     *  - **Antisimétrica**: si yo ofrezco, el otro no. Es lo que evita el glare.
     *  - **Nunca a mí mismo**: un dispositivo no se llama solo.
     *  - **Total**: entre dos ids distintos, alguno ofrece. Si los dos se
     *    abstuvieran, esos dos no se conectarían nunca — que es exactamente el
     *    agujero que esto viene a tapar.
     */
    fun meTocaOfrecer(mio: String, suyo: String): Boolean =
        mio.isNotEmpty() && suyo.isNotEmpty() && mio < suyo

    /**
     * A quiénes ofrecer para cerrar la malla.
     *
     * Se excluyen los que **ya tienen conexión**: con ésos la negociación ya
     * pasó, y ofrecerles de nuevo la tiraría abajo para rehacerla.
     */
    /**
     * Que hacer cuando se cae una conexion.
     *
     * ## El defecto que esto tapa
     *
     * La regla era `if (motores.isEmpty()) colgar()`. En una llamada de dos
     * esta bien: si el unico motor muere, no hay llamada. En una de grupo hay
     * motores que **nunca conectaron** —uno por cada persona que todavia suena,
     * creados al cerrar la malla—, asi que el mapa no se vaciaba nunca y la
     * llamada seguia con el cronometro corriendo sin nadie al otro lado. Se
     * vio en un emulador: `ICE: CONNECTED` y treinta segundos despues
     * `DISCONNECTED`, `CLOSED`, y la pantalla marcando 0:43.
     *
     * Un motor que existe no es una conversacion. Lo que cuenta es quien esta
     * CONECTADO, y aparte, quien todavia puede contestar.
     *
     * @param conectados cuantas conexiones estan vivas ahora mismo.
     * @param sonando a cuantas personas les sigue sonando el telefono.
     * @param definitiva si la conexion se cayo para no volver -`FAILED`- o si
     *   solo se corto y puede recuperarse -`DISCONNECTED`-. Una transitoria
     *   nunca cuelga.
     */
    fun trasCaida(conectados: Int, sonando: Int, definitiva: Boolean): TrasCaida = when {
        // Queda alguien hablando: que se caiga uno no corta a los demas.
        conectados > 0 -> TrasCaida.SEGUIR
        // Una caida TRANSITORIA nunca cuelga. ICE se recupera solo cuando el
        // telefono cambia de red, y colgar aqui cortaria la llamada cada vez
        // que se pasa de wifi a datos. Se deja de afirmar que hay
        // conversacion, que es distinto de darla por terminada.
        !definitiva -> TrasCaida.ESPERAR
        // Nadie conectado, pero alguien puede contestar todavia: no se cuelga,
        // seria colgarle a quien esta por entrar.
        sonando > 0 -> TrasCaida.ESPERAR
        // Ni conectados, ni sonando, y la caida fue definitiva.
        else -> TrasCaida.COLGAR
    }

    fun aQuienesOfrecer(
        mio: String,
        candidatos: List<String>,
        yaConectados: Set<String>,
    ): List<String> =
        candidatos
            .filter { it !in yaConectados }
            .filter { meTocaOfrecer(mio, it) }
            .distinct()
}

/** Lo que hay que hacer cuando muere una conexion. Ver [Malla.trasCaida]. */
enum class TrasCaida {
    /** Queda alguien hablando: no se toca nada. */
    SEGUIR,

    /**
     * Nadie conectado y alguien todavia suena: se vuelve a "conectando".
     *
     * No es colgar ni seguir. Dejarla "en curso" con el cronometro andando
     * seria afirmar que hay una conversacion donde no hay nadie.
     */
    ESPERAR,

    /** No queda nadie ni puede llegar nadie. */
    COLGAR,
}
