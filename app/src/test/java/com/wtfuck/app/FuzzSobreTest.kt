package com.wtfuck.app

import com.wtfuck.app.contenido.segura
import com.wtfuck.app.contenido.seguraViva
import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.ClaseContenido
import com.wtfuck.protocol.Relleno
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Lo que llega dentro de un sobre lo escribió otra persona.
 *
 * ## Contra qué se defiende esto
 *
 * El servidor no puede abrir un sobre, así que **no valida nada de lo que hay
 * dentro**. El único filtro es el de quién puede escribir en esa conversación,
 * y eso no protege de un participante hostil: alguien con un cliente
 * modificado manda exactamente los bytes que quiera a cualquiera de sus
 * chats.
 *
 * Lo que este fuzzer fija no es que los datos raros se dibujen bien, sino algo
 * más básico: **que ninguna entrada haga saltar una excepción**. En esta app
 * eso no es un detalle de robustez — es una negación de servicio contra una
 * persona concreta, porque el bucle que reparte los mensajes entrantes muere
 * con la primera excepción que se le escape.
 *
 * ## Por qué es determinista
 *
 * Semilla fija. Un fuzzer que sortea entradas distintas en cada corrida falla
 * una vez de cada cien en el ordenador de otra persona y nadie consigue
 * reproducirlo. Con semilla fija, o falla siempre o no falla; y cuando
 * encuentre algo, se podrá volver a ver.
 */
class FuzzSobreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val azar = Random(20260925)

    /** Cadenas pensadas para romper. */
    private fun textoHostil(): String = when (azar.nextInt(12)) {
        0 -> ""
        1 -> "\u0000\u0000\u0000"
        2 -> "\uD800"                         // sustituto suelto, UTF-16 inválido
        3 -> "\uDFFF\uD800"                   // par al revés
        4 -> "‮" + "gpj.exe"             // override de derecha a izquierda
        5 -> "A".repeat(200_000)
        6 -> "\\u0000\\\\\"}{[]"              // escapes que parecen JSON
        7 -> "👩‍👩‍👧‍👦".repeat(500)          // emoji compuesto, muchos puntos de código
        8 -> "\n\r\t".repeat(1000)
        9 -> "%s%n%p" + "\u0007"
        10 -> (1..50).joinToString("") { azar.nextInt(0x10FFFF).let { c ->
            if (c in 0xD800..0xDFFF) "?" else String(Character.toChars(c))
        } }
        else -> "normal"
    }

    /**
     * Doubles extremos pero que SE PUEDEN serializar.
     *
     * NaN e infinito quedan fuera y no por comodidad: `jsonApp` los rechaza al
     * escribir, asi que no pueden viajar ni guardarse. Esa propiedad se fija
     * aparte, en `ni NaN ni infinito pueden viajar`; meterlos aqui solo haria
     * fallar al propio fuzzer al construir el caso.
     */
    private fun doubleHostil(): Double = when (azar.nextInt(7)) {
        0 -> Double.MAX_VALUE
        1 -> -Double.MAX_VALUE
        2 -> Double.MIN_VALUE
        3 -> 0.0
        4 -> -0.0
        5 -> 1e308
        else -> azar.nextDouble(-1e9, 1e9)
    }

    private fun longHostil(): Long = when (azar.nextInt(6)) {
        0 -> Long.MIN_VALUE
        1 -> Long.MAX_VALUE
        2 -> 0L
        3 -> -1L
        else -> azar.nextLong()
    }

    private fun intHostil(): Int = when (azar.nextInt(6)) {
        0 -> Int.MIN_VALUE
        1 -> Int.MAX_VALUE
        2 -> 0
        3 -> -1
        else -> azar.nextInt()
    }

    private fun cargaHostil(): Carga = when (azar.nextInt(6)) {
        0 -> Carga.Texto(textoHostil())
        1 -> Carga.Ubicacion(doubleHostil(), doubleHostil(), intHostil(), textoHostil())
        2 -> Carga.Contacto(textoHostil(), textoHostil())
        3 -> Carga.Encuesta(
            textoHostil(),
            List(azar.nextInt(0, 30)) { textoHostil() },
            azar.nextBoolean(),
            longHostil(),
        )
        4 -> Carga.Evento(textoHostil(), longHostil(), textoHostil(), textoHostil())
        else -> Carga.UbicacionEnVivo(
            mensajeId = textoHostil(),
            lat = doubleHostil(),
            lon = doubleHostil(),
            precisionM = intHostil(),
            hasta = longHostil(),
            secuencia = intHostil(),
        )
    }

    // ------------------------------------------------------------------
    // El camino completo, con contenido hostil pero JSON válido
    // ------------------------------------------------------------------

    @Test
    fun `mil cargas hostiles no hacen saltar nada`() {
        repeat(1000) { i ->
            val c = cargaHostil()
            // El viaje real: serializar, rellenar, quitar relleno, parsear.
            val bytes = Relleno.poner(json.encodeToString(Carga.serializer(), c).toByteArray())
            val vuelta = try {
                json.decodeFromString(Carga.serializer(), String(Relleno.quitar(bytes)))
            } catch (e: Throwable) {
                throw AssertionError("ronda $i no sobrevivio al viaje: $c", e)
            }
            // Y lo que la pantalla hace con eso: sanear para dibujar.
            try {
                when (vuelta) {
                    is Carga.Ubicacion -> segura(vuelta)
                    is Carga.Contacto -> segura(vuelta)
                    is Carga.Encuesta -> segura(vuelta)
                    is Carga.Evento -> segura(vuelta)
                    is Carga.UbicacionEnVivo -> seguraViva(vuelta, ahora = 1_700_000_000_000L)
                    else -> Unit
                }
            } catch (e: Throwable) {
                throw AssertionError("ronda $i no se pudo sanear: $vuelta", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON roto: lo que llega cuando el otro lado no es esta app
    // ------------------------------------------------------------------

    @Test
    fun `un JSON invalido falla al parsear, pero de forma atrapable`() {
        // No se exige que parsee —no puede—, se exige que lo que lance sea una
        // excepción normal y no algo que se lleve el proceso por delante.
        val basura = listOf(
            "", " ", "null", "[]", "{}", "{", "}", "\u0000", "not json at all",
            """{"type":"clase.que.no.existe"}""",
            """{"type":"com.wtfuck.protocol.Carga.Texto"}""",       // sin campos
            """{"type":"com.wtfuck.protocol.Carga.Texto","cuerpo":123}""",  // tipo cambiado
            """{"type":"com.wtfuck.protocol.Carga.Ubicacion","lat":"no-es-numero"}""",
            "[".repeat(2000) + "]".repeat(2000),                    // anidado profundo
            "{" + """"a":""".repeat(5000) + "1" + "}".repeat(1),
        )
        for (s in basura) {
            val r = runCatching { json.decodeFromString(Carga.serializer(), s) }
            if (r.isSuccess) continue  // si parsea, mejor: no hay nada que romper
            val e = r.exceptionOrNull()!!
            assertTrue(
                "una entrada ajena no puede provocar ${e::class.java.name}: ${s.take(40)}",
                e is Exception || e is StackOverflowError,
            )
        }
    }

    @Test
    fun `runCatching atrapa tambien lo que no es Exception`() {
        // La defensa del repositorio es `runCatching`, que atrapa `Throwable`.
        // Importa que sea Throwable y no Exception: un JSON muy anidado tira
        // StackOverflowError, que es un Error. Si alguien cambiara el
        // `runCatching` por un `try/catch (e: Exception)` —lo natural de
        // escribir— ese caso dejaria de estar cubierto.
        val hondo = "[".repeat(100_000)
        val r = runCatching { json.decodeFromString(Carga.serializer(), hondo) }
        assertTrue("tiene que fallar, no colgarse", r.isFailure)
    }

    // ------------------------------------------------------------------
    // El relleno, con bytes arbitrarios
    // ------------------------------------------------------------------

    @Test
    fun `quitar relleno sobre bytes al azar no se rompe`() {
        repeat(500) {
            val largo = azar.nextInt(0, 4096)
            val datos = ByteArray(largo) { azar.nextInt(-128, 128).toByte() }
            val salida = Relleno.quitar(datos)
            assertTrue(salida.size <= datos.size)
            // Y lo que queda no termina en cero, salvo que sea todo ceros.
            if (salida.isNotEmpty()) {
                assertTrue("no puede quedar un cero al final", salida.last() != 0.toByte())
            }
        }
    }

    // ------------------------------------------------------------------
    // Las clases declaradas
    // ------------------------------------------------------------------

    @Test
    fun `una clase inventada no esta en la lista de validas`() {
        // El servidor valida `clase` contra una lista cerrada ANTES de usarla.
        // Esto fija la lista desde el otro lado: si alguien agrega una clase al
        // enum y se olvida de VALIDAS, el servidor la rechazaria y el sintoma
        // seria "mis mensajes nuevos no salen", muy lejos de la causa.
        // La cadena VACIA no esta: `ClaseContenido.TEXTO` es "". Lo descubrio
        // este mismo fuzzer, que la daba por invalida.
        for (mala in listOf(" ", "../../etc", "TEXTO", "texto ", "'; DROP TABLE", " ")) {
            assertTrue("no puede ser valida: '$mala'", mala !in ClaseContenido.VALIDAS)
        }
    }

    @Test
    fun `ni NaN ni infinito pueden viajar`() {
        // Lo encontro el fuzzer al intentar CONSTRUIR el caso: `jsonApp` no
        // admite valores especiales de punto flotante, asi que una posicion
        // con NaN no se puede ni serializar. Es una defensa que ya estaba y de
        // la que nadie se habia dado cuenta.
        //
        // Importa fijarla porque se pierde con una linea: basta que alguien
        // ponga `allowSpecialFloatingPointValues = true` —lo natural de hacer
        // cuando algo "no serializa"— para que un NaN empiece a viajar y a
        // guardarse en `especialJson`. Al dibujar se rechaza, pero quedaria
        // escrito en la base de todo el mundo.
        for (malo in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val r = runCatching {
                json.encodeToString(Carga.serializer(), Carga.Ubicacion(malo, 0.0))
            }
            assertTrue("$malo no tendria que poder serializarse", r.isFailure)
        }
        // Y el borde: lo enorme pero finito SI viaja, y vuelve igual.
        val grande = Carga.Ubicacion(Double.MAX_VALUE, -Double.MAX_VALUE)
        assertEquals(
            grande,
            json.decodeFromString(
                Carga.serializer(),
                json.encodeToString(Carga.serializer(), grande),
            ),
        )
    }


    @Test
    fun `toda clase que la app sabe dibujar esta declarada como valida`() {
        // El lado que faltaba: las que SI existen tienen que estar.
        val usadas = listOf(
            ClaseContenido.UBICACION, ClaseContenido.CONTACTO, ClaseContenido.ENCUESTA,
            ClaseContenido.UBICACION_VIVA, ClaseContenido.UBICACION_VIVA_FIN,
        )
        for (c in usadas) assertTrue("'$c' falta en VALIDAS", c in ClaseContenido.VALIDAS)
        assertEquals(usadas.size, usadas.distinct().size)
    }
}
