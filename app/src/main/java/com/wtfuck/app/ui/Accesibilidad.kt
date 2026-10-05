package com.wtfuck.app.ui

import com.wtfuck.app.datos.Media
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.protocol.EstadoEnvio

/**
 * §15 · Lo que oye quien no ve la pantalla.
 *
 * ## Por que hizo falta un archivo y no unos `contentDescription` sueltos
 *
 * Los botones de icono ya tenian su descripcion desde el principio. El hueco
 * estaba en otro sitio y era peor: **las filas compuestas**.
 *
 * Una fila de la lista de chats son cinco nodos —avatar, nombre, quien hablo,
 * el ultimo mensaje, la hora, el globo de no leidos— y TalkBack los recorre de
 * a uno. Para enterarse de que "@tatiana escribio hace dos minutos y hay tres
 * sin leer" hay que pasar por seis paradas y armar la frase uno mismo. Con la
 * vista, esa fila es **una** cosa que se lee de un golpe; sin la vista deberia
 * serlo tambien.
 *
 * Lo mismo en la burbuja: el texto, la hora y el check de estado son tres
 * nodos, y el check **no se anunciaba en absoluto** porque su
 * `contentDescription` era null —correcto mientras el color y la forma lo
 * explicaran a la vista, inservible cuando no hay vista—.
 *
 * ## La regla que sigue todo lo de aqui
 *
 * Lo que a la vista es **un bloque** se fusiona en un nodo y se describe
 * entero; lo que es un **estado que cambia** va en `stateDescription`, no
 * pegado a la descripcion, para que el lector lo anuncie cuando cambia y no
 * solo cuando se enfoca.
 *
 * ## Lo que NO se hace
 *
 * No se anuncia el contenido de un mensaje cifrado en ningun sitio donde no se
 * mostraria a la vista. La accesibilidad no es una puerta lateral a la
 * privacidad: si la notificacion no dice el texto, el lector de pantalla
 * tampoco.
 */

// ------------------------------------------------------------------
//  Estado de envio
// ------------------------------------------------------------------

/**
 * El estado de un mensaje propio, en palabras.
 *
 * Existe porque a la vista el estado son un icono y un color, y las dos cosas
 * se pierden a la vez. El icono ademas se repite: `DoneAll` es "entregado" y
 * tambien "leido", y lo unico que los separa es el tinte. Para quien no ve el
 * tinte, sin esto los dos son el mismo doble check.
 */
fun EstadoEnvio.enPalabras(): String = when (this) {
    EstadoEnvio.PENDIENTE -> "en cola, esperando red"
    EstadoEnvio.ENVIADO -> "enviado"
    EstadoEnvio.ENTREGADO -> "entregado"
    EstadoEnvio.LEIDO -> "leido"
    EstadoEnvio.FALLIDO -> "no se envio"
}

// ------------------------------------------------------------------
//  Fila de la lista de chats
// ------------------------------------------------------------------

/**
 * Una fila de la lista, leida como la ve alguien que ve: de un golpe.
 *
 * El orden no es casual, es el de importancia al escuchar: **con quien**, si
 * hay algo **sin leer** —que es lo que hace que alguien abra la app—, qué se
 * dijo, y cuándo. Poner la hora primero obligaria a escuchar un dato que casi
 * nunca decide nada antes de llegar al que si.
 */
fun descripcionDeFila(c: ChatFila, miUsuario: String): String =
    // Lo que no se ve tampoco se dice: el lector de pantalla leeria en voz alta
    // el ultimo mensaje de un chat protegido.
    if (c.protegido) "${c.titulo}, chat protegido" else descripcionDeFilaAbierta(c, miUsuario)

private fun descripcionDeFilaAbierta(c: ChatFila, miUsuario: String): String = buildList {
    // El tipo va AQUI y ya no como etiqueta en pantalla: a la vista lo dice
    // el icono del avatar -personas para un grupo, megafono para un canal- y
    // repetirlo en texto al lado del nombre era ruido. Quien no ve el icono
    // necesita la palabra, y la sigue teniendo.
    add(
        when (c.tipo) {
            "grupo" -> "Grupo ${c.titulo}"
            "canal" -> "Canal ${c.titulo}"
            else -> c.titulo
        }
    )

    if (c.noLeidos > 0) {
        add(if (c.noLeidos == 1) "1 mensaje sin leer" else "${c.noLeidos} mensajes sin leer")
    } else if (c.marcadaNoLeida) {
        add("marcada como no leida")
    }

    val quien = when {
        c.ultimoTexto == null -> null
        c.ultimoEsMio == true -> "Tu"
        c.tipo == "grupo" -> c.ultimoAutor
        else -> null
    }
    // Mismo arreglo que en la lista: sin esto, el lector de pantalla no decia
    // NADA de una foto sin pie. En pantalla eso es una linea vacia; con lector
    // de pantalla es que la conversacion no existe.
    val cuerpo = when {
        !c.ultimoAdjuntoClase.isNullOrBlank() -> Media.resumen(
            c.ultimoAdjuntoClase, c.ultimoTexto.orEmpty(), c.ultimoAdjuntoNombre.orEmpty(),
        )
        else -> c.ultimoTexto ?: "sin mensajes todavía"
    }
    add(if (quien != null) "$quien: $cuerpo" else cuerpo)

    // El estado del ULTIMO mensaje solo si es mio: el de los ajenos no existe.
    if (c.ultimoEsMio == true) add(estadoDe(c.ultimoEstado).enPalabras())

    c.ultimaFecha?.takeIf { it > 0 }?.let { add(horaCorta(it)) }

    if (c.fijado) add("fijada arriba")
    if (c.silenciado) add("silenciada")
    if (!c.soyMiembro) add("ya no eres miembro")
}.joinToString(", ")

// ------------------------------------------------------------------
//  Burbuja
// ------------------------------------------------------------------

/**
 * Una burbuja, leida entera.
 *
 * Un mensaje retirado se dice y **no se lee su texto**, igual que a la vista:
 * la fila queda como hueco a proposito, y anunciar lo que decia seria
 * deshacer el borrado para quien usa lector de pantalla.
 */
fun descripcionDeBurbuja(
    m: MensajeEnt,
    esGrupo: Boolean,
    /**
     * Como llamo yo a un username. Por defecto, el username tal cual.
     *
     * Existe para que el lector de pantalla diga **lo mismo que se ve**: la
     * etiqueta de autor muestra mi nombre de contacto -"Tati"- y decir
     * "tatiana" en voz alta obliga a mantener dos identidades de la misma
     * persona en la cabeza, justo a quien no puede comprobarlo mirando.
     */
    nombreDe: (String) -> String = { it },
): String = buildList {
    if (m.esMio) add("Tu") else if (esGrupo) add(nombreDe(m.autor))

    when {
        m.retirado -> add("mensaje eliminado")
        m.especial.isNotBlank() -> add(m.texto.ifBlank { "contenido" })
        m.adjuntoClase.isNotBlank() -> {
            add(nombreDeClase(m.adjuntoClase))
            if (m.texto.isNotBlank()) add(m.texto)
        }
        else -> add(m.texto)
    }

    if (m.reenviadoDe != null && !m.retirado) add("reenviado de ${m.reenviadoDe}")
    if (m.editado && !m.retirado) add("editado")
    if (m.expiraEn > 0 && !m.retirado) add("temporal")

    add(hora(m.creadoEn))
}.joinToString(", ")

/** Lo que se dice de un adjunto sin abrirlo. */
private fun nombreDeClase(clase: String): String = when (clase) {
    "imagen" -> "Foto"
    "video" -> "Video"
    "audio" -> "Audio"
    "nota_voz" -> "Nota de voz"
    "documento" -> "Documento"
    "sticker" -> "Sticker"
    else -> "Adjunto"
}

/**
 * El estado de la burbuja como `stateDescription`, no como parte del texto.
 *
 * La diferencia importa: TalkBack vuelve a anunciar un `stateDescription`
 * cuando **cambia**, asi que "enviado" se convierte en "entregado" sin tener
 * que volver a enfocar la burbuja. Pegado a la descripcion habria que salir y
 * volver para enterarse.
 */
fun SemanticsPropertyReceiver.estadoDeBurbuja(m: MensajeEnt) {
    if (!m.esMio || m.retirado) return
    val e = estadoDe(m.estado)
    stateDescription = if (e == EstadoEnvio.FALLIDO && m.motivoFallo != null) {
        "${e.enPalabras()}: ${m.motivoFallo}"
    } else {
        e.enPalabras()
    }
}

// ------------------------------------------------------------------
//  Reacciones
// ------------------------------------------------------------------

/**
 * Una reaccion, con su cuenta y si es la propia.
 *
 * "Corazon, 3" no alcanza: lo que decide si se toca o no es si la tuya ya esta
 * puesta, y eso a la vista lo dice el fondo cian.
 */
fun descripcionDeReaccion(emoji: String, total: Int, mia: Boolean): String {
    val cuantas = if (total == 1) "1 reacción" else "$total reacciones"
    return if (mia) "$emoji, $cuantas, incluida la tuya" else "$emoji, $cuantas"
}

fun SemanticsPropertyReceiver.reaccion(emoji: String, total: Int, mia: Boolean) {
    contentDescription = descripcionDeReaccion(emoji, total, mia)
    stateDescription = if (mia) "puesta" else "sin poner"
}

// ------------------------------------------------------------------
//  Opciones de encuesta
// ------------------------------------------------------------------

/**
 * Una opcion de encuesta: el texto, cuantos votos y que porcentaje.
 *
 * El porcentaje se dice **porque a la vista se ve**: la barra de fondo es
 * exactamente esa proporcion. Sin decirlo, quien escucha tiene los numeros
 * crudos y no la comparacion, que es lo que la barra comunica de un vistazo.
 */
fun descripcionDeOpcion(etiqueta: String, votos: Int, total: Int): String {
    val cuantos = when (votos) {
        0 -> "sin votos"
        1 -> "1 voto"
        else -> "$votos votos"
    }
    if (total <= 0 || votos == 0) return "$etiqueta, $cuantos"
    val pct = (votos * 100.0 / total).toInt()
    return "$etiqueta, $cuantos, $pct por ciento"
}
