package com.wtfuck.app.datos

/**
 * Alguien a quien se puede anadir a una llamada en curso.
 *
 * [avatarUsername] vacio = sin foto conocida; se dibujan las iniciales.
 */
data class CandidatoLlamada(
    val username: String,
    val nombre: String,
    val avatarUsername: String = "",
    val avatarVersion: Long = 0,
)

/**
 * A quien se ofrece anadir a una llamada.
 *
 * ## Por que no solo la libreta
 *
 * La primera version listaba solo los contactos guardados, y en la prueba con
 * dos emuladores salio vacia: la cuenta tenia dos chats y cero contactos. En
 * esta app se llega a la gente escribiendo su usuario, no agendandola, asi que
 * "solo la libreta" es "casi nunca nadie" y la funcion parece rota.
 *
 * Se juntan las dos fuentes: las personas con las que tengo un chat directo y
 * las de la libreta. Sigue sin haber campo libre de username, por lo que ya
 * decia el selector: en medio de una llamada nadie teclea un @ bien, y un error
 * crea un grupo con la persona equivocada dentro.
 *
 * ## Por que se excluye a quien ya esta en la llamada
 *
 * Porque "anadirla" crearia un grupo de dos con la misma persona y cortaria la
 * llamada para nada. Tampoco sale uno mismo, que puede aparecer si alguna vez
 * se abrio un chat consigo.
 *
 * ## Orden
 *
 * Primero los chats, del mas reciente al mas viejo (`directas` ya viene asi):
 * la persona a la que se quiere sumar casi siempre es alguien con quien se
 * hablo hace poco. Despues, el resto de la libreta por nombre.
 *
 * Todo en minusculas para comparar: el servidor trata los usernames sin
 * distinguir mayusculas y una "Ana" y una "ana" son la misma persona.
 */
fun candidatosParaLlamada(
    libreta: List<ContactoEnt>,
    directas: List<ConversacionEnt>,
    excluir: Collection<String>,
): List<CandidatoLlamada> {
    val fuera = excluir.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
    val alias = libreta.associate { it.username.lowercase() to it.alias }
    val vistos = mutableSetOf<String>()
    val salida = mutableListOf<CandidatoLlamada>()

    for (c in directas) {
        if (c.tipo != "directa") continue
        val u = c.nombre.lowercase()
        if (u.isBlank() || u in fuera || !vistos.add(u)) continue
        val nombre = alias[u].orEmpty().ifBlank { c.nombreMostrado.ifBlank { c.nombre } }
        salida += CandidatoLlamada(
            username = u,
            nombre = nombre,
            avatarUsername = c.avatarUsername,
            avatarVersion = c.avatarVersion,
        )
    }

    libreta
        .filter { it.username.isNotBlank() }
        .sortedBy { it.alias.ifBlank { it.username }.lowercase() }
        .forEach { k ->
            val u = k.username.lowercase()
            if (u in fuera || !vistos.add(u)) return@forEach
            salida += CandidatoLlamada(username = u, nombre = k.alias.ifBlank { k.username })
        }

    return salida
}
