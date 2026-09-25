package com.wtfuck.app

import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.ui.descripcionDeBurbuja
import com.wtfuck.app.ui.descripcionDeFila
import com.wtfuck.app.ui.descripcionDeOpcion
import com.wtfuck.app.ui.descripcionDeReaccion
import com.wtfuck.app.ui.enPalabras
import com.wtfuck.protocol.EstadoEnvio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §15 · Lo que oye quien no ve la pantalla.
 *
 * Estas funciones se prueban con JUnit y no mirando el telefono porque son lo
 * unico de la interfaz que es **texto puro**: entra un estado, sale una frase.
 * Lo demas -que el nodo se fusione, que el rol sea el correcto- se comprueba
 * volcando el arbol de accesibilidad del emulador, que es otra cosa.
 *
 * Lo que se fija aqui, en orden de importancia:
 *
 *  1. Que un mensaje **retirado no diga su texto**. Es la unica de estas
 *     pruebas que protege algo mas que la comodidad: anunciar lo que decia
 *     seria deshacer el borrado para quien usa lector de pantalla.
 *  2. Que "entregado" y "leido" suenen distinto. A la vista los separa solo el
 *     tinte del mismo icono, asi que sin palabras son el mismo doble check.
 *  3. Que lo primero que se oiga sea con quien y si hay algo sin leer, no la
 *     hora.
 */
class AccesibilidadTest {

    private fun fila(
        titulo: String = "tatiana",
        tipo: String = "directa",
        noLeidos: Int = 0,
        texto: String? = "hola",
        mio: Boolean? = false,
        estado: String? = null,
        autor: String? = null,
        fecha: Long? = null,
        fijado: Boolean = false,
        silenciado: Boolean = false,
        marcada: Boolean = false,
        miembro: Boolean = true,
        adjuntoClase: String? = null,
        adjuntoNombre: String? = null,
    ) = ChatFila(
        id = "c1",
        tipo = tipo,
        nombre = titulo,
        nombreMostrado = "",
        participantes = "",
        avatarUsername = "",
        avatarVersion = 0,
        noLeidos = noLeidos,
        ultimoTexto = texto,
        ultimaFecha = fecha,
        ultimoEsMio = mio,
        ultimoAutor = autor,
        ultimoEstado = estado,
        ultimoAdjuntoClase = adjuntoClase,
        ultimoAdjuntoNombre = adjuntoNombre,
        miRol = "miembro",
        miJerarquia = 10,
        silenciadoHasta = if (silenciado) -1L else 0L,
        archivado = false,
        fijado = fijado,
        marcadaNoLeida = marcada,
        soyMiembro = miembro,
    )

    private fun mensaje(
        texto: String = "hola",
        mio: Boolean = false,
        autor: String = "tatiana",
        estado: String = "ENTREGADO",
        retirado: Boolean = false,
        editado: Boolean = false,
        reenviadoDe: String? = null,
        clase: String = "",
        especial: String = "",
        expira: Long = 0,
    ) = MensajeEnt(
        id = "m1",
        conversacionId = "c1",
        autor = autor,
        esMio = mio,
        texto = texto,
        creadoEn = 0L,
        estado = estado,
        retirado = retirado,
        editado = editado,
        reenviadoDe = reenviadoDe,
        adjuntoClase = clase,
        especial = especial,
        expiraEn = expira,
    )

    // ------------------------------------------------------------------
    //  Lo que NO se dice
    // ------------------------------------------------------------------

    @Test
    fun `un mensaje retirado no anuncia su texto`() {
        // La fila se conserva a proposito para que el hueco se vea; el
        // contenido se borro. Leerlo en voz alta seria deshacer el borrado
        // justo para quien no puede comprobar que ya no esta.
        val d = descripcionDeBurbuja(
            mensaje(texto = "algo que se borro", retirado = true),
            esGrupo = false,
        )
        assertFalse(d, d.contains("algo que se borro"))
        assertTrue(d, d.contains("mensaje eliminado"))
    }

    @Test
    fun `un adjunto se anuncia por su clase y no por su ruta`() {
        val d = descripcionDeBurbuja(mensaje(texto = "", clase = "nota_voz"), esGrupo = false)
        assertTrue(d, d.contains("Nota de voz"))
    }

    // ------------------------------------------------------------------
    //  Estado de envio: el caso que el icono solo no distingue
    // ------------------------------------------------------------------

    @Test
    fun `entregado y leido suenan distinto`() {
        // A la vista los dos son `DoneAll` y solo cambia el tinte. Si las
        // palabras coincidieran, para un lector de pantalla serian el mismo
        // estado y el acuse de lectura no existiria.
        assertEquals("entregado", EstadoEnvio.ENTREGADO.enPalabras())
        assertEquals("leido", EstadoEnvio.LEIDO.enPalabras())
    }

    @Test
    fun `cada estado tiene una frase propia`() {
        val dichos = EstadoEnvio.entries.map { it.enPalabras() }
        assertEquals("hay estados que suenan igual: $dichos", dichos.size, dichos.toSet().size)
        assertTrue(dichos.none { it.isBlank() })
    }

    // ------------------------------------------------------------------
    //  Fila de la lista: el orden es el de importancia al escuchar
    // ------------------------------------------------------------------

    @Test
    fun `lo primero es con quien, y lo segundo si hay algo sin leer`() {
        val d = descripcionDeFila(fila(noLeidos = 3, fecha = 1L), miUsuario = "joaquin")
        val iNombre = d.indexOf("tatiana")
        val iSinLeer = d.indexOf("3 mensajes sin leer")
        val iTexto = d.indexOf("hola")
        assertTrue(d, iNombre >= 0 && iSinLeer > iNombre && iTexto > iSinLeer)
    }

    @Test
    fun `un grupo se anuncia como grupo`() {
        val d = descripcionDeFila(fila(titulo = "Equipo", tipo = "grupo"), "joaquin")
        assertTrue(d, d.startsWith("Grupo Equipo"))
    }

    @Test
    fun `en un grupo se dice quien hablo`() {
        val d = descripcionDeFila(
            fila(tipo = "grupo", titulo = "Equipo", autor = "rocio"),
            "joaquin",
        )
        assertTrue(d, d.contains("rocio: hola"))
    }

    @Test
    fun `lo mio se anuncia como Tu y con su estado`() {
        val d = descripcionDeFila(fila(mio = true, estado = "LEIDO"), "joaquin")
        assertTrue(d, d.contains("Tu: hola"))
        assertTrue(d, d.contains("leido"))
    }

    @Test
    fun `el estado no se anuncia si el ultimo mensaje es ajeno`() {
        // Un mensaje de otro no tiene estado de envio: decir "entregado"
        // ahi seria inventar un dato.
        val d = descripcionDeFila(fila(mio = false, estado = "ENTREGADO"), "joaquin")
        assertFalse(d, d.contains("entregado"))
    }

    @Test
    fun `marcada a mano se dice distinto de tener mensajes sin leer`() {
        val marcada = descripcionDeFila(fila(marcada = true), "joaquin")
        assertTrue(marcada, marcada.contains("marcada como no leida"))
        assertFalse(marcada, marcada.contains("sin leer,"))

        val conUno = descripcionDeFila(fila(noLeidos = 1), "joaquin")
        assertTrue(conUno, conUno.contains("1 mensaje sin leer"))
    }

    @Test
    fun `silenciada, fijada y expulsado se dicen`() {
        val d = descripcionDeFila(
            fila(fijado = true, silenciado = true, miembro = false),
            "joaquin",
        )
        assertTrue(d, d.contains("fijada arriba"))
        assertTrue(d, d.contains("silenciada"))
        assertTrue(d, d.contains("ya no eres miembro"))
    }

    @Test
    fun `una conversacion vacia lo dice en vez de quedarse muda`() {
        val d = descripcionDeFila(fila(texto = null, mio = null), "joaquin")
        assertTrue(d, d.contains("sin mensajes todavía"))
    }

    // ------------------------------------------------------------------
    //  Burbuja
    // ------------------------------------------------------------------

    @Test
    fun `en un grupo la burbuja ajena dice de quien es`() {
        val d = descripcionDeBurbuja(mensaje(autor = "rocio"), esGrupo = true)
        assertTrue(d, d.startsWith("rocio"))
    }

    @Test
    fun `en una directa no se repite el nombre en cada burbuja`() {
        // Ya esta en la cabecera. Repetirlo en cada mensaje convierte una
        // conversacion de veinte lineas en veinte veces el mismo nombre.
        val d = descripcionDeBurbuja(mensaje(autor = "tatiana"), esGrupo = false)
        assertFalse(d, d.startsWith("tatiana"))
    }

    @Test
    fun `reenviado y editado se anuncian`() {
        val d = descripcionDeBurbuja(
            mensaje(editado = true, reenviadoDe = "rocio"),
            esGrupo = false,
        )
        assertTrue(d, d.contains("reenviado de rocio"))
        assertTrue(d, d.contains("editado"))
    }

    @Test
    fun `un temporal se anuncia como temporal`() {
        val d = descripcionDeBurbuja(mensaje(expira = 999L), esGrupo = false)
        assertTrue(d, d.contains("temporal"))
    }

    // ------------------------------------------------------------------
    //  Reacciones
    // ------------------------------------------------------------------

    @Test
    fun `una reaccion dice si la propia esta puesta`() {
        // A la vista lo dice el fondo cian, que es justo lo que se pierde.
        assertTrue(descripcionDeReaccion("❤️", 3, mia = true).contains("incluida la tuya"))
        assertFalse(descripcionDeReaccion("❤️", 3, mia = false).contains("incluida la tuya"))
    }

    @Test
    fun `una sola reaccion no se dice en plural`() {
        assertTrue(descripcionDeReaccion("👍", 1, mia = false).contains("1 reacción"))
        assertFalse(descripcionDeReaccion("👍", 1, mia = false).contains("reacciones"))
    }

    // ------------------------------------------------------------------
    //  Opciones de encuesta
    // ------------------------------------------------------------------

    @Test
    fun `una opcion dice el porcentaje, que es lo que la barra muestra`() {
        // Los votos crudos dan el numero; la barra da la COMPARACION, y eso es
        // lo que hay que poner en palabras.
        val d = descripcionDeOpcion("Sala 3", votos = 3, total = 4)
        assertTrue(d, d.contains("3 votos"))
        assertTrue(d, d.contains("75 por ciento"))
    }

    @Test
    fun `una opcion sin votos no inventa un cero por ciento`() {
        val d = descripcionDeOpcion("Remoto", votos = 0, total = 4)
        assertTrue(d, d.contains("sin votos"))
        assertFalse(d, d.contains("por ciento"))
    }

    @Test
    fun `una encuesta sin votos no divide por cero`() {
        val d = descripcionDeOpcion("Sala 3", votos = 0, total = 0)
        assertTrue(d, d.contains("sin votos"))
    }
    @Test
    fun `una foto sin pie SI se anuncia`() {
        // Antes decia "" y la fila sonaba como una conversacion sin mensajes.
        val d = descripcionDeFila(fila(texto = "", adjuntoClase = ClaseAdjunto.IMAGEN), "yo")
        assertTrue(d, d.contains("Foto"))
    }

    @Test
    fun `un documento se anuncia por su nombre`() {
        val d = descripcionDeFila(
            fila(texto = "", adjuntoClase = ClaseAdjunto.DOCUMENTO, adjuntoNombre = "acta.pdf"),
            "yo",
        )
        assertTrue(d, d.contains("acta.pdf"))
    }
}

/**
 * La linea de resumen de una conversacion cuando el ultimo mensaje es un
 * adjunto.
 *
 * Una foto sin pie dejaba la linea en blanco. En pantalla eso es una fila que
 * parece vacia; con lector de pantalla es una conversacion que no dice nada.
 */
class PreviaDeAdjuntoTest {

    @Test
    fun `una foto sin pie se llama Foto y no cadena vacia`() {
        assertEquals("Foto", Media.resumen(ClaseAdjunto.IMAGEN, "", ""))
    }

    @Test
    fun `con pie se dicen las dos cosas`() {
        assertEquals("Foto · en la playa", Media.resumen(ClaseAdjunto.IMAGEN, "en la playa", ""))
    }

    @Test
    fun `cada clase tiene su palabra`() {
        assertEquals("Video", Media.resumen(ClaseAdjunto.VIDEO, "", ""))
        assertEquals("Audio", Media.resumen(ClaseAdjunto.AUDIO, "", ""))
        assertEquals("Nota de voz", Media.resumen(ClaseAdjunto.NOTA_VOZ, "", ""))
        assertEquals("Sticker", Media.resumen(ClaseAdjunto.STICKER, "", ""))
    }

    @Test
    fun `un documento se resume con SU NOMBRE`() {
        // "Documento" a secas no distingue un contrato de un meme.
        assertEquals("contrato.pdf", Media.resumen(ClaseAdjunto.DOCUMENTO, "", "contrato.pdf"))
    }

    @Test
    fun `un documento sin nombre cae a la palabra generica`() {
        assertEquals("Documento", Media.resumen(ClaseAdjunto.DOCUMENTO, "", ""))
    }
}
