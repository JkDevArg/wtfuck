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
