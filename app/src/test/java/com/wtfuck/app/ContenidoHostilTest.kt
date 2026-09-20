package com.wtfuck.app

import com.wtfuck.app.ui.conResistencia
import com.wtfuck.app.ui.UMBRAL_RESPUESTA
import com.wtfuck.app.ui.MAXIMO_ARRASTRE
import com.wtfuck.app.ui.velocidadLegible
import com.wtfuck.app.ui.siguienteVelocidad
import com.wtfuck.app.ui.VELOCIDADES
import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.app.ui.TOPE_GRABACION_MS
import com.wtfuck.app.ui.TOPE_GRABACION_BYTES
import com.wtfuck.app.datos.Media
import com.wtfuck.app.contenido.*
import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.TopesConsulta
import org.junit.Assert.*
import org.junit.Test

/**
 * Lo que pasa cuando el sobre no lo escribio esta app.
 *
 * ## Por que estas pruebas existen
 *
 * En el servidor, una peticion es una entrada no confiable y eso lo tiene claro
 * cualquiera. En el cliente de una app cifrada de extremo a extremo pasa lo
 * mismo con **el contenido de un sobre**, y es menos evidente: como el sobre
 * viene de una conversacion en la que uno esta, se lo trata como si fuera de
 * fiar.
 *
 * No lo es. El servidor no puede abrirlo —esa es la garantia del producto— asi
 * que **no hay nadie mas** entre quien lo escribe y quien lo dibuja. Y nada
 * obliga a que del otro lado haya esta app: quien tenga las claves de la
 * conversacion arma el JSON a mano.
 *
 * ## Lo que se comprueba
 *
 * Que ningun campo de los que llegan de afuera se dibuje tal cual: ni un texto
 * de sesenta mil caracteres, ni unas coordenadas que no existen, ni un `@`
 * delante de algo que no tiene forma de cuenta.
 */
class ContenidoHostilTest {

    // ------------------------------------------------------------------
    //  Recortes
    // ------------------------------------------------------------------

    @Test
    fun `un texto corto no se toca ni se marca como recortado`() {
        val (texto, cortado) = recortado("hola", 100)
        assertEquals("hola", texto)
        assertFalse(cortado)
    }

    @Test
    fun `un texto largo se recorta y lo DICE`() {
        val (texto, cortado) = recortado("x".repeat(5_000), 100)
        assertEquals(100, texto.length)
        assertTrue("recortar en silencio es peor que recortar", cortado)
    }

    @Test
    fun `justo en el tope no se considera recortado`() {
        val (_, cortado) = recortado("x".repeat(100), 100)
        assertFalse(cortado)
    }

    // ------------------------------------------------------------------
    //  Controles de direccion
    // ------------------------------------------------------------------

    @Test
    fun `se quita el override de derecha a izquierda`() {
        // El truco clasico: con U+202E delante, lo de atras se dibuja al reves.
        val disfrazado = "\u202Egnp.exe"
        assertEquals("gnp.exe", sinTrucosDeDireccion(disfrazado))
    }

    @Test
    fun `se quitan tambien los aislantes y las marcas sueltas`() {
        val sucio = "a\u2066b\u2069c\u200Ed\u200Fe"
        assertEquals("abcde", sinTrucosDeDireccion(sucio))
    }

    @Test
    fun `el arabe y el hebreo quedan INTACTOS`() {
        // Esto es lo que separa quitar un truco de romper un idioma: el arabe
        // se escribe de derecha a izquierda por la direccion propia de sus
        // letras, no por marcas de control. Si esta prueba fallara, la limpieza
        // estaria rompiendo texto legitimo.
        val arabe = "مرحبا بالعالم"
        val hebreo = "שלום עולם"
        assertEquals(arabe, sinTrucosDeDireccion(arabe))
        assertEquals(hebreo, sinTrucosDeDireccion(hebreo))
    }

    @Test
    fun `los emoji y los acentos no se tocan`() {
        val texto = "Reunión ñandú 🎉 café"
        assertEquals(texto, sinTrucosDeDireccion(texto))
    }

    // ------------------------------------------------------------------
    //  Identidad: el `@` es una afirmacion
    // ------------------------------------------------------------------

    @Test
    fun `un username normal se acepta`() {
        assertTrue(pareceUsername("joaquin"))
        assertTrue(pareceUsername("tati_99"))
        assertTrue(pareceUsername("abc"))
    }

    @Test
    fun `un username con espacios o puntos NO parece username`() {
        // Este es el ataque: la tarjeta pinta "@" + lo que venga, y
        // "@soporte · Administrador" se lee como una cuenta oficial.
        assertFalse(pareceUsername("soporte · Administrador"))
        assertFalse(pareceUsername("joaquin admin"))
        assertFalse(pareceUsername("Joaquin"))     // mayusculas no existen
        assertFalse(pareceUsername("jo"))          // menos de 3
        assertFalse(pareceUsername("x".repeat(25)))
        assertFalse(pareceUsername(""))
    }

    @Test
    fun `un contacto con username inventado se dibuja SIN arroba y sin boton`() {
        val c = segura(Carga.Contacto(username = "soporte · Administrador", nombre = "Soporte"))
        assertNull("sin username no hay arroba ni boton de abrir", c.username)
        assertEquals("Soporte", c.nombre)
    }

    @Test
    fun `un contacto legitimo conserva su username`() {
        val c = segura(Carga.Contacto(username = "tatiana", nombre = "Tati"))
        assertEquals("tatiana", c.username)
        assertEquals("Tati", c.nombre)
    }

    @Test
    fun `un contacto sin nombre cae al username, y si tampoco vale a una etiqueta neutra`() {
        assertEquals("tatiana", segura(Carga.Contacto(username = "tatiana")).nombre)
        assertEquals("Contacto", segura(Carga.Contacto(username = "no vale")).nombre)
    }

    @Test
    fun `el nombre de un contacto tiene tope`() {
        val c = segura(Carga.Contacto(username = "tatiana", nombre = "n".repeat(50_000)))
        assertEquals(TopesConsulta.LARGO_NOMBRE, c.nombre.length)
    }

    @Test
    fun `un username con un override de direccion no cuela`() {
        // Sin quitar el control, la cadena tiene un caracter que el regex
        // rechaza; con el quitado tiene que quedar el username de verdad.
        val c = segura(Carga.Contacto(username = "\u202Etatiana"))
        assertEquals("tatiana", c.username)
    }

    // ------------------------------------------------------------------
    //  Ubicacion: un punto que no existe no es un punto
    // ------------------------------------------------------------------

    @Test
    fun `unas coordenadas normales se formatean`() {
        assertEquals("-12.046374, -77.042793", coordenadasLegibles(-12.046374, -77.042793))
    }

    @Test
    fun `NaN e infinito no son una posicion`() {
        assertNull(coordenadasLegibles(Double.NaN, 0.0))
        assertNull(coordenadasLegibles(0.0, Double.NaN))
        assertNull(coordenadasLegibles(Double.POSITIVE_INFINITY, 0.0))
        assertNull(coordenadasLegibles(0.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `una latitud o longitud fuera de rango tampoco`() {
        assertNull(coordenadasLegibles(91.0, 0.0))
        assertNull(coordenadasLegibles(-91.0, 0.0))
        assertNull(coordenadasLegibles(0.0, 181.0))
        assertNull(coordenadasLegibles(0.0, -181.0))
        assertNull(coordenadasLegibles(1e308, 1e308))
    }

    @Test
    fun `los bordes exactos SI valen`() {
        assertNotNull(coordenadasLegibles(90.0, 180.0))
        assertNotNull(coordenadasLegibles(-90.0, -180.0))
        assertNotNull(coordenadasLegibles(0.0, 0.0))
    }

    @Test
    fun `una ubicacion fabricada no ofrece mapa`() {
        val u = segura(Carga.Ubicacion(lat = Double.NaN, lon = Double.NaN))
        assertNull("sin coordenadas no se arma un geo: para otra app", u.coordenadas)
    }

    @Test
    fun `el margen absurdo se descarta y el normal se muestra`() {
        assertNull(margenLegible(0))
        assertNull(margenLegible(-5))
        assertNull(margenLegible(Int.MAX_VALUE))
        assertEquals("Margen de 12 m", margenLegible(12))
    }

    @Test
    fun `la etiqueta de una ubicacion tiene tope`() {
        val u = segura(Carga.Ubicacion(lat = 0.0, lon = 0.0, etiqueta = "e".repeat(60_000)))
        assertEquals(TopesConsulta.LARGO_LUGAR, u.etiqueta.length)
        assertTrue(u.etiquetaRecortada)
    }

    // ------------------------------------------------------------------
    //  Encuesta
    // ------------------------------------------------------------------

    @Test
    fun `una encuesta con dos mil opciones se recorta y lo dice`() {
        val e = segura(Carga.Encuesta(pregunta = "?", opciones = List(2_000) { "op $it" }))
        assertEquals(TopesConsulta.OPCIONES, e.opciones.size)
        assertTrue(e.opcionesRecortadas)
    }

    @Test
    fun `la pregunta tambien tiene tope, no solo las opciones`() {
        val e = segura(Carga.Encuesta(pregunta = "p".repeat(60_000), opciones = listOf("si")))
        assertEquals(TopesConsulta.LARGO_PREGUNTA, e.pregunta.length)
        assertTrue(e.preguntaRecortada)
    }

    @Test
    fun `cada opcion tiene tope por separado`() {
        val e = segura(Carga.Encuesta(pregunta = "?", opciones = listOf("o".repeat(9_000))))
        assertEquals(TopesConsulta.LARGO_OPCION, e.opciones[0].length)
    }

    @Test
    fun `una encuesta normal no se marca como recortada`() {
        val e = segura(Carga.Encuesta(pregunta = "Cafe?", opciones = listOf("si", "no")))
        assertFalse(e.preguntaRecortada)
        assertFalse(e.opcionesRecortadas)
        assertEquals(listOf("si", "no"), e.opciones)
    }

    // ------------------------------------------------------------------
    //  Evento
    // ------------------------------------------------------------------

    @Test
    fun `los tres campos de texto de un evento tienen tope`() {
        val ev = segura(Carga.Evento(
            titulo = "t".repeat(60_000),
            cuandoMs = 1_800_000_000_000L,
            lugar = "l".repeat(60_000),
            nota = "n".repeat(60_000),
        ))
        assertEquals(TopesConsulta.LARGO_TITULO, ev.titulo.length)
        assertEquals(TopesConsulta.LARGO_LUGAR, ev.lugar.length)
        assertEquals(TopesConsulta.LARGO_NOTA, ev.nota.length)
        assertTrue(ev.notaRecortada)
    }

    @Test
    fun `una fecha del ano 292 millones no es una fecha`() {
        val ev = segura(Carga.Evento(titulo = "x", cuandoMs = Long.MAX_VALUE))
        assertNull(ev.cuandoMs)
    }

    @Test
    fun `una fecha negativa o cero tampoco`() {
        assertNull(segura(Carga.Evento(titulo = "x", cuandoMs = 0)).cuandoMs)
        assertNull(segura(Carga.Evento(titulo = "x", cuandoMs = -1)).cuandoMs)
    }

    @Test
    fun `una fecha normal se conserva`() {
        val cuando = 1_800_000_000_000L
        assertEquals(cuando, segura(Carga.Evento(titulo = "x", cuandoMs = cuando)).cuandoMs)
    }

    // ------------------------------------------------------------------
    //  Votos
    // ------------------------------------------------------------------

    @Test
    fun `un voto fuera de rango no cuenta`() {
        // No es solo no reventar: un indice de mas contado como voto cambia
        // los porcentajes que ve todo el mundo.
        assertEquals(listOf(0, 1), votosDentroDe(listOf(0, 1, 99, -3), 2))
    }

    @Test
    fun `un voto repetido cuenta una sola vez`() {
        assertEquals(listOf(1), votosDentroDe(listOf(1, 1, 1), 3))
    }

    @Test
    fun `una encuesta sin opciones no acepta ningun voto`() {
        assertTrue(votosDentroDe(listOf(0, 1, 2), 0).isEmpty())
    }
}

/**
 * El nombre de un adjunto, que es donde el truco de direccion tiene su uso
 * clasico: hacer que un ejecutable parezca una imagen.
 */
class NombreDeArchivoTest {

    @Test
    fun `un nombre normal se conserva entero`() {
        assertEquals("informe anual.pdf", nombreDeArchivoSeguro("informe anual.pdf"))
        assertEquals("Reunión ñ 2026.docx", nombreDeArchivoSeguro("Reunión ñ 2026.docx"))
    }

    @Test
    fun `el disfraz de extension se deshace`() {
        // `factura\u202Egpj.exe` se DIBUJA como "factura exe.jpg": un ejecutable
        // que parece una foto. Quitado el control, se ve lo que es.
        assertEquals("facturagpj.exe", nombreDeArchivoSeguro("factura\u202Egpj.exe"))
    }

    @Test
    fun `un salto de linea no puede fabricar una segunda linea`() {
        // Sin esto, "foto.jpg\nVerificado por wtfuck" se dibuja como dos lineas
        // y la segunda parece de la app.
        assertEquals("foto.jpg Verificado por wtfuck",
            nombreDeArchivoSeguro("foto.jpg\nVerificado por wtfuck"))
        assertEquals("a b", nombreDeArchivoSeguro("a\r\nb").replace("  ", " "))
    }

    @Test
    fun `un nombre desmedido se recorta`() {
        assertEquals(TOPE_NOMBRE_ARCHIVO, nombreDeArchivoSeguro("n".repeat(9_000)).length)
    }

    @Test
    fun `los espacios de los bordes se van`() {
        assertEquals("foto.jpg", nombreDeArchivoSeguro("   foto.jpg   "))
    }

    @Test
    fun `un nombre vacio sigue vacio, y la burbuja ya decide que poner`() {
        assertEquals("", nombreDeArchivoSeguro(""))
        assertEquals("", nombreDeArchivoSeguro("\u202E\u200F"))
    }
}

/**
 * La duracion de un audio, que la **declara quien sube** el archivo.
 *
 * Nadie la comprueba contra el audio real: para eso habria que decodificarlo, y
 * el servidor ni siquiera puede abrirlo. Es un numero ajeno mas.
 */
class DuracionDeclaradaTest {

    @Test
    fun `una duracion normal se formatea`() {
        assertEquals("0:07", Media.duracionLegible(7_000))
        assertEquals("1:05", Media.duracionLegible(65_000))
        assertEquals("10:00", Media.duracionLegible(600_000))
    }

    @Test
    fun `los segundos van siempre con dos cifras`() {
        assertEquals("2:03", Media.duracionLegible(123_000))
    }

    @Test
    fun `una duracion negativa no se dibuja como 0 dos puntos menos cinco`() {
        // Sin guarda esto daba "0:-5", que no es una duracion, es un descuido
        // en pantalla.
        assertEquals("", Media.duracionLegible(-5_000))
        assertEquals("", Media.duracionLegible(Int.MIN_VALUE))
    }

    @Test
    fun `cero es ausencia de dato, no cero segundos`() {
        assertEquals("", Media.duracionLegible(0))
    }

    @Test
    fun `una duracion absurda tampoco se dibuja`() {
        // `Int.MAX_VALUE` daba "35791:23". No revienta; es basura donde deberia
        // haber un dato.
        assertEquals("", Media.duracionLegible(Int.MAX_VALUE))
        assertEquals("", Media.duracionLegible(Media.TOPE_DURACION_MS + 1))
    }

    @Test
    fun `justo en el tope si se dibuja`() {
        assertTrue(Media.duracionLegible(Media.TOPE_DURACION_MS).isNotEmpty())
    }

    @Test
    fun `el tope de grabacion entra holgado en el limite del servidor`() {
        // 64 kbps son 8000 B/s. Si esta cuenta dejara de dar, una nota de voz
        // completa se rechazaria AL SUBIR, despues de grabarla entera.
        val bytesEsperados = (TOPE_GRABACION_MS / 1000L) * 8_000L
        assertTrue(
            "una nota al tope pesaria $bytesEsperados B",
            bytesEsperados < ClaseAdjunto.limite(ClaseAdjunto.NOTA_VOZ),
        )
        // Y la segunda red va por debajo del limite del servidor.
        assertTrue(TOPE_GRABACION_BYTES < ClaseAdjunto.limite(ClaseAdjunto.NOTA_VOZ))
    }
}

/**
 * La rueda de velocidades de las notas de voz.
 *
 * Es un boton que rota, no un menu: la prueba fija que la rotacion cierre el
 * circulo y que un valor que no esta en la lista no deje el boton atascado.
 */
class VelocidadAudioTest {

    @Test
    fun `la rueda gira y vuelve al principio`() {
        assertEquals(1.5f, siguienteVelocidad(1f))
        assertEquals(2f, siguienteVelocidad(1.5f))
        assertEquals(1f, siguienteVelocidad(2f))
    }

    @Test
    fun `una velocidad que no esta en la lista vuelve al principio`() {
        // Puede pasar de verdad: si el aparato no admite un factor, el
        // reproductor cae a 1x, y tambien si un ajuste guardado viene de una
        // version con otra lista. Sin esto el boton se quedaria sin siguiente.
        assertEquals(1f, siguienteVelocidad(0.75f))
        assertEquals(1f, siguienteVelocidad(3f))
        assertEquals(1f, siguienteVelocidad(Float.NaN))
    }

    @Test
    fun `se escribe sin decimal cuando es entera`() {
        assertEquals("1x", velocidadLegible(1f))
        assertEquals("2x", velocidadLegible(2f))
        assertEquals("1.5x", velocidadLegible(1.5f))
    }

    @Test
    fun `la lista empieza en velocidad normal`() {
        // Si algun dia se reordena, la nota empezaria acelerada sin que nadie
        // lo pidiera.
        assertEquals(1f, VELOCIDADES.first())
    }
}

/**
 * La curva de resistencia de "deslizar para responder".
 *
 * Es aritmetica de un gesto, y se prueba aparte porque el gesto en si no se
 * puede probar sin un dedo: lo que si se puede fijar es que la burbuja siga al
 * dedo antes del umbral, se frene despues, y nunca se pase del maximo.
 */
class ResistenciaDeslizarTest {

    private val umbral = 56f
    private val maximo = 84f

    @Test
    fun `antes del umbral la burbuja sigue al dedo uno a uno`() {
        // Cualquier retardo aqui se siente como lentitud, no como resistencia.
        assertEquals(0f, conResistencia(0f, umbral, maximo))
        assertEquals(20f, conResistencia(20f, umbral, maximo))
        assertEquals(56f, conResistencia(56f, umbral, maximo))
    }

    @Test
    fun `pasado el umbral avanza a la mitad`() {
        // 56 + (76-56)/2 = 66
        assertEquals(66f, conResistencia(76f, umbral, maximo))
    }

    @Test
    fun `nunca se pasa del maximo por mucho que se arrastre`() {
        assertEquals(maximo, conResistencia(1_000f, umbral, maximo))
        assertEquals(maximo, conResistencia(100_000f, umbral, maximo))
    }

    @Test
    fun `arrastrar a la izquierda no mueve nada`() {
        // El gesto es solo hacia la derecha: que no haga nada es mejor que que
        // haga algo distinto.
        assertEquals(0f, conResistencia(-30f, umbral, maximo))
        assertEquals(0f, conResistencia(-1_000f, umbral, maximo))
    }

    @Test
    fun `la curva nunca retrocede`() {
        // Si en algun tramo el desplazamiento bajara al arrastrar mas, la
        // burbuja se moveria hacia atras con el dedo yendo hacia adelante.
        var anterior = -1f
        var x = 0f
        while (x <= 300f) {
            val y = conResistencia(x, umbral, maximo)
            assertTrue("retrocede en x=$x", y >= anterior)
            anterior = y
            x += 1f
        }
    }

    @Test
    fun `el maximo deja sitio para que se vea que hay tope`() {
        // Si el maximo fuera igual al umbral, el dedo no notaria el frenazo:
        // la burbuja se pararia justo donde se activa y las dos senales se
        // confundirian en una.
        assertTrue(MAXIMO_ARRASTRE > UMBRAL_RESPUESTA)
    }
}
