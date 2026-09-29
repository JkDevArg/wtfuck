package com.wtfuck.app

import com.wtfuck.app.datos.CazadorDeFallos
import com.wtfuck.app.datos.Fallos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El informe de fallos.
 *
 * ## Que se prueba aqui, y por que estas cosas
 *
 * Lo que puede salir mal en silencio. Un informe que no se escribe se nota
 * -no hay informe-; uno que **lleva contenido de una conversacion** no se nota
 * nunca, porque nadie revisa un informe de fallo buscando datos personales. Lo
 * descubriria la persona a la que se lo pegan en un grupo.
 *
 * Por eso la mitad de estas pruebas son sobre lo que NO debe aparecer.
 */
class FallosTest {

    @get:Rule
    val carpeta = TemporaryFolder()

    // ------------------------------------------------- lo que se tacha

    @Test
    fun `un mensaje de chat largo no sobrevive entero`() {
        val texto = "Oye, te paso la direccion de la reunion de manana, es en " +
            "la casa de mi hermana y llevamos el regalo entre todos, avisa a los demas"
        val saneado = Fallos.sanear("no se pudo procesar: \"$texto\"")
        assertFalse("el texto del chat aparece tal cual", saneado.contains("mi hermana"))
    }

    @Test
    fun `un numero de telefono se tacha`() {
        assertTrue(Fallos.sanear("fallo al verificar +51 955 123 456").contains("[telefono]"))
        assertFalse(Fallos.sanear("fallo al verificar +51955123456").contains("955123456"))
    }

    @Test
    fun `base64 largo se tacha`() {
        // Es el formato de lo mas sensible: claves, cuerpos de sobre,
        // miniaturas. Si algo tiene que no escaparse, es esto.
        val clave = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAx7Vv9ktQ2mZpLr8sT3n"
        val saneado = Fallos.sanear("clave invalida: $clave")
        assertTrue("no se tacho el base64", saneado.contains("[base64]"))
        assertFalse(saneado.contains("MIIBIjANBgkqhkiG"))
    }

    @Test
    fun `un correo se tacha`() {
        assertTrue(Fallos.sanear("destino ana.perez@ejemplo.com").contains("[correo]"))
    }

    @Test
    fun `una ruta con nombre de archivo se tacha`() {
        // El nombre del adjunto lo eligio quien lo mando: ya es contenido.
        val saneado = Fallos.sanear("no existe /data/user/0/com.wtfuck.app/files/contrato firmado.pdf")
        assertFalse("el nombre del archivo se ve", saneado.contains("contrato firmado"))
    }

    @Test
    fun `lo que ayuda a depurar SI sobrevive`() {
        // El saneado tiene que dejar pasar lo util. Si tachara todo, el
        // informe no serviria y daria igual tenerlo.
        val saneado = Fallos.sanear("indice 7 fuera de rango, tamano 3")
        assertTrue(saneado.contains("7"))
        assertTrue(saneado.contains("rango"))
    }

    @Test
    fun `un mensaje muy largo se recorta`() {
        val largo = "a".repeat(1000)
        assertTrue(Fallos.sanear(largo).length <= Fallos.TOPE_MENSAJE + 5)
    }

    @Test
    fun `sanear aguanta el nulo y el vacio`() {
        assertEquals("", Fallos.sanear(null))
        assertEquals("", Fallos.sanear(""))
        assertEquals("", Fallos.sanear("   "))
    }

    // ------------------------------------------------- la traza

    @Test
    fun `la traza incluye la clase y la linea`() {
        val e = IllegalStateException("algo se rompio")
        val t = Fallos.formatear(e)
        assertTrue("falta la clase", t.contains("IllegalStateException"))
        assertTrue("falta el mensaje", t.contains("algo se rompio"))
        assertTrue("faltan los marcos", t.contains("    en "))
    }

    @Test
    fun `se sigue la cadena de causas`() {
        // La de arriba suele ser el envoltorio; el fallo de verdad esta
        // debajo. Un informe que solo trae la primera no sirve de mucho.
        val raiz = NumberFormatException("no es un numero")
        val medio = IllegalArgumentException("parametro malo", raiz)
        val arriba = RuntimeException("fallo la operacion", medio)
        val t = Fallos.formatear(arriba)
        assertTrue(t.contains("fallo la operacion"))
        assertTrue(t.contains("Causado por"))
        assertTrue("no llego a la raiz", t.contains("no es un numero"))
    }

    @Test
    fun `una cadena de causas circular no cuelga`() {
        // Pasa de verdad con algunas bibliotecas. Sin el control de vistos,
        // esto seria un bucle infinito DENTRO del manejador de cierres: la
        // app no llegaria ni a morirse bien.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val t = Fallos.formatear(a)
        assertTrue(t.isNotEmpty())
    }

    @Test
    fun `el mensaje de la excepcion tambien se sanea`() {
        val e = IllegalArgumentException("no se pudo abrir +51955123456")
        assertFalse(Fallos.formatear(e).contains("955123456"))
    }

    // ------------------------------------------------- el informe

    @Test
    fun `el informe lleva version y aparato pero no el usuario`() {
        val t = Fallos.informe(
            e = RuntimeException("x"), cuando = 1_700_000_000_000L,
            version = "0.6.0-beta", modelo = "Pixel 9", android = "36", hilo = "main",
        )
        assertTrue(t.contains("0.6.0-beta"))
        assertTrue(t.contains("Pixel 9"))
        assertTrue(t.contains("Android 36"))
        assertTrue(t.contains("main"))
        // El username NO va: un informe que identifica a quien lo manda deja
        // de poder compartirse sin decir quien eres.
        assertFalse("el informe no deberia llevar arroba de usuario", t.contains("@"))
    }

    // ------------------------------------------------- el cazador

    private fun cazador(reloj: () -> Long = System::currentTimeMillis) = CazadorDeFallos(
        carpeta = carpeta.root, version = "0.6.0-beta",
        modelo = "Pixel 9", android = "36", ahora = reloj,
    )

    @Test
    fun `guardar deja un informe legible`() {
        val c = cazador { 1_700_000_000_000L }
        c.guardar(IllegalStateException("se rompio"), "main")
        assertTrue(c.hayInformes())
        val texto = c.ultimo()!!
        assertTrue(texto.contains("IllegalStateException"))
        assertTrue(texto.contains("0.6.0-beta"))
    }

    @Test
    fun `solo se conservan los ultimos`() {
        // Sin poda, quien nunca abre la pantalla acumula un archivo por cada
        // cierre. Es la fuga que solo le pasa a quien mas problemas tiene.
        var t = 1_700_000_000_000L
        val c = cazador { t }
        repeat(Fallos.MAXIMO + 4) {
            t += 1000
            c.guardar(RuntimeException("fallo $it"), "main")
        }
        assertEquals(Fallos.MAXIMO, c.informes().size)
        // Y los que quedan son los MAS NUEVOS, no los primeros.
        assertTrue("se quedo con los viejos", c.ultimo()!!.contains("fallo 8"))
    }

    @Test
    fun `sin informes no hay nada que ofrecer`() {
        val c = cazador()
        assertFalse(c.hayInformes())
        assertEquals(null, c.ultimo())
    }

    @Test
    fun `borrar los deja en cero`() {
        val c = cazador()
        c.guardar(RuntimeException("x"), "main")
        assertTrue(c.hayInformes())
        c.borrarTodos()
        assertFalse(c.hayInformes())
    }

    @Test
    fun `el manejador no se traga el cierre`() {
        // La propiedad mas importante del cazador: si se tragara la excepcion,
        // no saldria el aviso del sistema y en algunos aparatos el proceso
        // quedaria colgado. Un manejador asi es peor que no tener ninguno.
        var llamado = false
        val previo = Thread.UncaughtExceptionHandler { _, _ -> llamado = true }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previo)
            cazador().instalar()
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertTrue("no se encadeno al manejador anterior", llamado)
            assertTrue("no se guardo el informe", cazador().hayInformes())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `instalar dos veces no encadena dos veces`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var veces = 0
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> veces++ }
            val c = cazador()
            c.instalar()
            c.instalar()
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertEquals("el manejador previo se llamo de mas", 1, veces)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
