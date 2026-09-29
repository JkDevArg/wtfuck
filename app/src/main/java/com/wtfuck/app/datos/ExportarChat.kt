package com.wtfuck.app.datos

import com.wtfuck.protocol.ClaseAdjunto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportar una conversacion a texto plano.
 *
 * ## Que es esto realmente
 *
 * Una funcion que **rompe a proposito** las protecciones de la app. Todo lo
 * demas aqui existe para que las conversaciones no salgan del telefono en
 * claro; esto las saca en claro, porque a veces hace falta: guardar un acuerdo,
 * aportar una prueba, archivar algo antes de dejar de usar la app.
 *
 * Negarlo no haria el sistema mas seguro, solo menos util: quien lo necesite
 * hara capturas de pantalla, que es peor -mas trabajo, peor resultado y sin
 * ninguno de los avisos de abajo-. Lo correcto es darlo y decir exactamente
 * que implica.
 *
 * ## Los mensajes temporales NO se exportan
 *
 * La decision de diseno de este archivo, y la que podria haber ido al reves.
 *
 * Quien pone un temporizador esta pidiendo que lo que escribe **no persista**.
 * Meterlo en un `.txt` sin cifrar es exactamente lo contrario, y ademas lo
 * decide una sola de las dos partes sin que la otra se entere. Un mensajero
 * que ofrece "esto desaparece" y a la vez un boton para guardarlo para siempre
 * esta mintiendo en una de las dos pantallas.
 *
 * Asi que se excluyen, y el archivo **dice cuantos se dejaron fuera**: callarlo
 * haria creer que la conversacion esta entera.
 *
 * El coste esta asumido: si alguien necesitaba de prueba justo un mensaje
 * temporal, no lo tendra. Es el precio de que el temporizador signifique algo.
 *
 * ## Lo que tampoco va
 *
 * - **Los archivos adjuntos.** Solo su nombre y tipo. Meterlos convertiria un
 *   `.txt` en un zip, y quien quiera las fotos tiene la copia de seguridad —
 *   que ademas va cifrada, que es donde deben estar.
 * - **Los mensajes retirados.** No tienen texto que exportar; mandar una linea
 *   vacia como prueba solo estorba.
 * - **Los ocultos**, por lo mismo que no se ven en el chat.
 */
object ExportarChat {

    /** Lo que se le cuenta a la persona despues de exportar. */
    data class Resumen(
        val incluidos: Int,
        /** Temporales que se dejaron fuera. Se dice, no se calla. */
        val temporalesOmitidos: Int,
    )

    /** El texto y su resumen. */
    data class Salida(val texto: String, val resumen: Resumen)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /**
     * Arma el archivo.
     *
     * @param yo como se escribe el propio nombre. "tú" y no el username: quien
     *   lee su propia conversacion se reconoce antes asi, y es lo que hacen
     *   todas las exportaciones que la gente ya ha visto.
     */
    fun construir(
        titulo: String,
        mensajes: List<MensajeEnt>,
        cuando: Long,
        yo: String = "tú",
        alias: Map<String, String> = emptyMap(),
    ): Salida {
        val f = fmt()
        val visibles = mensajes.filter { !it.oculto && !it.retirado }
        val temporales = visibles.count { it.expiraEn > 0 }
        val exportables = visibles
            .filter { it.expiraEn <= 0 }
            .sortedBy { it.creadoEn }

        val sb = StringBuilder()
        sb.append("Conversación con ").append(titulo).append('\n')
        sb.append("Exportada el ").append(f.format(Date(cuando))).append(" desde wtfuck\n")
        sb.append(exportables.size).append(" mensajes\n")
        if (temporales > 0) {
            // Se dice arriba y no en una nota al pie: quien use esto como
            // prueba tiene que saber que NO esta completo antes de usarlo.
            sb.append(
                "$temporales mensajes temporales no se incluyeron: quien los escribió " +
                    "pidió que no quedaran guardados.\n",
            )
        }
        sb.append(
            "\nEste archivo NO está cifrado. Cualquiera que lo abra lo puede leer.\n",
        )
        sb.append("----------------------------------------\n\n")

        for (m in exportables) {
            sb.append('[').append(f.format(Date(m.creadoEn))).append("] ")
            if (m.esSistema) {
                // Sin autor: no lo dijo nadie. Con marca para que no se
                // confunda con un mensaje de una persona — importa si esto se
                // usa como prueba.
                sb.append("* ").append(m.texto).append('\n')
                continue
            }
            val quien = if (m.esMio) yo else (alias[m.autor] ?: m.autor)
            sb.append(quien).append(": ")
            sb.append(cuerpoDe(m))
            sb.append('\n')
        }

        return Salida(
            texto = sb.toString(),
            resumen = Resumen(incluidos = exportables.size, temporalesOmitidos = temporales),
        )
    }

    /**
     * El cuerpo de una linea.
     *
     * Un adjunto sale como marca y nombre. El pie -si lo hay- va detras, que
     * es donde estaba: un `[foto]` a secas perderia el texto que lo acompanaba.
     */
    private fun cuerpoDe(m: MensajeEnt): String {
        if (m.adjuntoClase.isEmpty()) {
            return m.texto.ifEmpty { "(sin texto)" }
        }
        val marca = when (m.adjuntoClase) {
            ClaseAdjunto.IMAGEN -> "[foto]"
            ClaseAdjunto.VIDEO -> "[video]"
            ClaseAdjunto.AUDIO -> "[audio]"
            ClaseAdjunto.NOTA_VOZ -> "[nota de voz]"
            ClaseAdjunto.STICKER -> "[sticker]"
            else -> "[archivo]"
        }
        val nombre = m.adjuntoNombre.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        // El pie solo si es distinto del nombre: en un documento, `texto` suele
        // SER el nombre, y repetirlo queda como "[archivo] x.pdf — x.pdf".
        val pie = m.texto
            .takeIf { it.isNotBlank() && it != m.adjuntoNombre }
            ?.let { " — $it" }
            .orEmpty()
        return marca + nombre + pie
    }

    /** Un nombre de archivo que no sorprenda a nadie al guardarlo. */
    fun nombreSugerido(titulo: String, cuando: Long): String {
        val limpio = titulo
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(40)
            .ifEmpty { "chat" }
        val fecha = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(cuando))
        return "wtfuck-$limpio-$fecha.txt"
    }
}
