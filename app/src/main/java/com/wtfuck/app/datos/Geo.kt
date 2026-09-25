package com.wtfuck.app.datos

import com.wtfuck.protocol.ESTELA_MAX
import com.wtfuck.protocol.PuntoEstela
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Las cuentas del mapa, **sin Android y sin red**.
 *
 * Todo lo que decide dónde va un punto en la pantalla vive acá y es una
 * función pura: se puede probar en la JVM, con números escritos a mano, sin
 * emulador y sin pedirle una imagen a nadie. La parte que dibuja
 * (`ui/Mapa.kt`) no calcula nada.
 *
 * Esa separación no es estética. La proyección de Mercator tiene un error
 * clásico —confundir grados con una distancia constante— que en Lima se nota
 * poco y en Oslo parte el mapa a la mitad; encontrarlo con una prueba de una
 * línea es barato, encontrarlo mirando un emulador no.
 */
object Geo {

    /**
     * El límite de Web Mercator.
     *
     * Más allá la proyección se va a infinito. Todos los mapas de baldosas
     * cortan acá, así que un punto en la Antártida se dibuja en el borde en
     * lugar de romper la cuenta.
     */
    const val LAT_MAX = 85.05112878

    /** El zoom más cerrado que sirve para algo, y el más abierto que da OSM. */
    const val Z_MIN = 2
    const val Z_MAX = 18

    /** Lado de una baldosa, en píxeles. Es el estándar de OSM y no se negocia. */
    const val LADO = 256

    /**
     * Cuántos metros hay entre dos posiciones.
     *
     * Aproximación equirrectangular y no Haversine a propósito: acá las
     * distancias son de metros a kilómetros —una estela, no una ruta
     * transatlántica— y a esa escala el error es de centímetros, con una
     * raíz y dos multiplicaciones en lugar de cuatro trigonométricas que se
     * ejecutarían en cada punto de cada repintado.
     */
    fun metros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        // El coseno de la latitud MEDIA, no de una de las dos: un meridiano
        // vale menos metros cuanto más lejos del ecuador, y tomar el de un
        // extremo sesga la cuenta hacia ese extremo.
        val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return r * sqrt(dLat * dLat + dLon * dLon)
    }

    /**
     * Cuánto tiene que haberse movido para que valga un punto nuevo: 12 m.
     *
     * Sin esto, alguien quieto ocho horas llenaría la estela de puntos
     * iguales y empujaría fuera todo el recorrido real — y el dibujo se
     * quedaría con sesenta copias del mismo lugar, que es exactamente no
     * tener estela. Doce metros es un poco menos que el margen típico de un
     * GPS urbano: por debajo, lo que se estaría dibujando es el ruido del
     * sensor y no un movimiento.
     */
    const val MINIMO_M = 12.0

    /**
     * La estela con una posición más, si esa posición aporta algo.
     *
     * Devuelve la MISMA lista cuando el punto no aporta, y no una copia: así
     * quien llama puede comparar por identidad para no reescribir la fila.
     */
    fun conPunto(estela: List<PuntoEstela>, lat: Double, lon: Double, en: Long): List<PuntoEstela> {
        val ultimo = estela.lastOrNull()
        if (ultimo != null && metros(ultimo.lat, ultimo.lon, lat, lon) < MINIMO_M) return estela
        // Por el principio: lo viejo es lo que sobra. `takeLast` sobre una
        // lista de 61 es barato y deja el orden natural —el último es el más
        // nuevo—, que es como lo lee el dibujo.
        return (estela + PuntoEstela(lat, lon, en)).takeLast(ESTELA_MAX)
    }

    // ------------------------------------------------------------------
    // Web Mercator
    // ------------------------------------------------------------------

    /**
     * La columna de baldosa de una longitud, con decimales.
     *
     * Con decimales y no entero porque la parte fraccionaria ES la posición
     * dentro de la baldosa, y es lo que permite centrar el mapa en el punto
     * en vez de en la esquina de una baldosa.
     */
    fun columna(lon: Double, z: Int): Double {
        val l = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return (l + 180.0) / 360.0 * (1 shl z)
    }

    /** La fila de baldosa de una latitud, con decimales. Ver [columna]. */
    fun fila(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-LAT_MAX, LAT_MAX))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
    }

    /**
     * El zoom más cerrado con el que TODA la estela entra en la vista.
     *
     * Se elige solo y no con botones. Un mapa con `+` y `−` obliga a la
     * persona a buscar el encuadre en el que se ve algo, y ese encuadre ya lo
     * sabe la app: es el que contiene el recorrido. Cuando hay un solo punto
     * no hay recorrido que encuadrar y se usa [zSuelto], que es la escala de
     * "una cuadra alrededor".
     *
     * @param margen cuánto del ancho se deja libre; 0.8 = se usa el 80 %,
     *   para que el marcador no quede pegado al borde.
     */
    fun zoomPara(
        puntos: List<PuntoEstela>,
        anchoPx: Int,
        altoPx: Int,
        zSuelto: Int = 16,
        margen: Double = 0.8,
    ): Int {
        if (puntos.size < 2 || anchoPx <= 0 || altoPx <= 0) return zSuelto.coerceIn(Z_MIN, Z_MAX)
        for (z in Z_MAX downTo Z_MIN) {
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in puntos) {
                val x = columna(p.lon, z) * LADO
                val y = fila(p.lat, z) * LADO
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
            if (maxX - minX <= anchoPx * margen && maxY - minY <= altoPx * margen) return z
        }
        return Z_MIN
    }

    /**
     * Cuántos metros mide un píxel acá.
     *
     * Es lo que hace legible el dibujo sin mapa: sin una barra de escala, un
     * paseo de cinco metros y un viaje de cinco kilómetros se dibujan
     * **idénticos**, porque el trazo siempre se estira para llenar la caja.
     */
    fun metrosPorPixel(lat: Double, z: Int): Double =
        156_543.03392 * cos(Math.toRadians(lat.coerceIn(-LAT_MAX, LAT_MAX))) / (1 shl z)

    /**
     * Un número redondo de metros para la barra de escala, y su largo en px.
     *
     * Redondo de verdad —1, 2 o 5 por una potencia de diez— porque una barra
     * que dijera "137 m" obligaría a hacer la cuenta para estimar cualquier
     * otra distancia.
     */
    fun escala(metrosPorPixel: Double, maxPx: Int): Pair<Int, Int> {
        if (metrosPorPixel <= 0.0 || maxPx <= 0) return 0 to 0
        val crudo = metrosPorPixel * maxPx
        var paso = 1.0
        val candidatos = doubleArrayOf(1.0, 2.0, 5.0)
        var e = 0
        while (e < 9) {
            var hubo = false
            for (c in candidatos) {
                val v = c * Math.pow(10.0, e.toDouble())
                if (v <= crudo) { paso = v; hubo = true }
            }
            if (!hubo) break
            e++
        }
        return paso.toInt() to (paso / metrosPorPixel).toInt().coerceAtLeast(1)
    }

    /** `true` si los dos puntos son el mismo lugar a efectos de dibujo. */
    fun mismoLugar(a: PuntoEstela, b: PuntoEstela): Boolean =
        abs(a.lat - b.lat) < 1e-7 && abs(a.lon - b.lon) < 1e-7
}
