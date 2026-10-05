package com.wtfuck.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.datos.Geo
import com.wtfuck.app.ui.theme.BgElev
import com.wtfuck.app.ui.theme.Cian
import com.wtfuck.app.ui.theme.Slate
import com.wtfuck.app.ui.theme.TextoTerciario
import com.wtfuck.protocol.PuntoEstela
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * El cliente que pide las baldosas, **separado del de la app a propósito**.
 *
 * El [ImageLoader] de la app lleva un interceptor que agrega el token de
 * sesión, y aunque hoy comprueba el host antes de agregarlo, ese es el tipo de
 * cuidado que se pierde en el tercer refactor. Este cliente no tiene el
 * interceptor: no es que no mande el token al servidor de mapas, es que **no
 * conoce el token**. La diferencia importa cuando alguien toque el otro
 * archivo dentro de un año.
 *
 * También tiene su propia caché en disco. Compartirla dejaría que un rato de
 * mapa desalojara los avatares y las fotos del chat — y al revés, que un chat
 * con muchas imágenes obligara a volver a pedir las mismas baldosas.
 *
 * ## El User-Agent
 *
 * La política de uso de OpenStreetMap exige uno que identifique la aplicación;
 * el genérico de OkHttp se bloquea. Va con la versión, que es lo que les sirve
 * para escribirle a alguien si una versión se porta mal.
 */
object TeselaCliente {

    private var cache: ImageLoader? = null

    /** `""` apaga el mapa entero: sin servidor no hay a quién pedirle nada. */
    val hay: Boolean get() = BuildConfig.MAPA_BALDOSAS.isNotBlank()

    fun de(ctx: Context): ImageLoader = cache ?: crear(ctx).also { cache = it }

    private fun crear(ctx: Context): ImageLoader {
        val cliente = OkHttpClient.Builder()
            .addInterceptor { c ->
                c.proceed(
                    c.request().newBuilder()
                        .header("User-Agent", "wtfuck/${BuildConfig.VERSION_NAME} (Android)")
                        // Que no se filtre de dónde venimos. En una petición de
                        // imagen no aporta nada y es un dato gratis para el otro
                        // lado.
                        .header("Referer", "")
                        .build(),
                )
            }
            .let(com.wtfuck.app.datos.Red::construir)
        return ImageLoader.Builder(ctx)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { cliente })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.08).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("baldosas").toOkioPath())
                    .maxSizeBytes(24L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
    }

    /** La URL de una baldosa, o `null` si el índice se sale del mundo. */
    fun url(z: Int, x: Int, y: Int): String? {
        val lado = 1 shl z
        if (y < 0 || y >= lado) return null  // arriba del polo no hay nada
        val xx = ((x % lado) + lado) % lado  // el mundo da la vuelta en horizontal
        return BuildConfig.MAPA_BALDOSAS
            .replace("{z}", z.toString())
            .replace("{x}", xx.toString())
            .replace("{y}", y.toString())
    }
}

/**
 * Qué se clava en el punto.
 *
 * Son dos cosas distintas y no un icono con variantes. La cara de alguien
 * dice *"esta persona está acá ahora"*; el pin dice *"este lugar"*. Poner una
 * cara en una ubicación de una sola vez afirmaría que sigue ahí, que es justo
 * lo que una ubicación normal **no** afirma.
 */
sealed interface Marcador {
    /** Quien comparte su posición en vivo. Su cara ES el marcador (AM.5). */
    data class Persona(val autor: String, val foto: String?, val enVivo: Boolean) : Marcador

    /** Un sitio. No hay nadie de quien poner la cara. */
    data object Lugar : Marcador
}

/**
 * El recorrido de una ubicación en vivo, con mapa o sin él.
 *
 * ## Es una sola vista y no dos
 *
 * El modo oculto no es "una versión pobre": es exactamente esta vista sin el
 * fondo. La estela, el marcador, la barra de escala y el encuadre son los
 * mismos, y por eso lo que se pierde al ocultarse es nada más el decorado de
 * las calles — la información (por dónde pasó, cuánto y hacia dónde) sigue
 * entera. Tenerlas como dos composables se habría separado al primer arreglo.
 *
 * ## El encuadre lo elige la vista
 *
 * Se centra en el medio del recorrido y se ajusta el zoom para que entre
 * todo ([Geo.zoomPara]). No hay botones de `+` y `−`: el encuadre útil ya lo
 * sabe la app, y hacer que la persona lo busque a mano sería pedirle trabajo
 * para llegar a donde íbamos a llevarla igual.
 *
 * @param conBaldosas si se le piden las imágenes al servidor de mapas. Es el
 *   **Y** de dos permisos: el de quien comparte, que viaja en la carga, y el
 *   de este aparato, que es `Ajustes.mapaDeTerceros`.
 */
@Composable
fun MapaDeUbicacion(
    estela: List<PuntoEstela>,
    lat: Double,
    lon: Double,
    marcador: Marcador,
    enVivo: Boolean,
    conBaldosas: Boolean,
    /** El margen del GPS, en metros. Se dibuja como un halo alrededor. */
    precisionM: Int = 0,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val densidad = LocalDensity.current

    // El punto de ahora va al final aunque ya esté en la estela: la estela se
    // recorta por distancia, así que la última posición puede no haber
    // entrado, y el marcador tiene que estar donde está la persona.
    val puntos = remember(estela, lat, lon) {
        val ultimo = estela.lastOrNull()
        if (ultimo != null && Geo.metros(ultimo.lat, ultimo.lon, lat, lon) < 1.0) estela
        else estela + PuntoEstela(lat, lon, 0L)
    }

    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgElev),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val anchoPx = with(densidad) { maxWidth.toPx() }.toInt()
            val altoPx = with(densidad) { maxHeight.toPx() }.toInt()
            if (anchoPx <= 0 || altoPx <= 0) return@BoxWithConstraints

            val z = remember(puntos, anchoPx, altoPx) {
                Geo.zoomPara(puntos, anchoPx, altoPx)
            }

            // Todo en píxeles del mundo a este zoom, que es el único sistema
            // en el que baldosas y posiciones se pueden sumar sin cuidado.
            val mundo = remember(puntos, z) {
                puntos.map { Geo.columna(it.lon, z) * Geo.LADO to Geo.fila(it.lat, z) * Geo.LADO }
            }
            val cx = (mundo.minOf { it.first } + mundo.maxOf { it.first }) / 2
            val cy = (mundo.minOf { it.second } + mundo.maxOf { it.second }) / 2

            // De mundo a pantalla: el centro del recorrido en el centro de la caja.
            val px = { p: Pair<Double, Double> ->
                Offset(
                    (anchoPx / 2.0 + (p.first - cx)).toFloat(),
                    (altoPx / 2.0 + (p.second - cy)).toFloat(),
                )
            }

            if (conBaldosas && TeselaCliente.hay) {
                val cargador = remember(ctx) { TeselaCliente.de(ctx) }
                val ladoDp = with(densidad) { Geo.LADO.toDp() }
                val x0 = floor((cx - anchoPx / 2.0) / Geo.LADO).toInt()
                val x1 = floor((cx + anchoPx / 2.0) / Geo.LADO).toInt()
                val y0 = floor((cy - altoPx / 2.0) / Geo.LADO).toInt()
                val y1 = floor((cy + altoPx / 2.0) / Geo.LADO).toInt()
                for (tx in x0..x1) for (ty in y0..y1) {
                    val url = TeselaCliente.url(z, tx, ty) ?: continue
                    val ox = (anchoPx / 2.0 + (tx.toDouble() * Geo.LADO - cx)).toInt()
                    val oy = (altoPx / 2.0 + (ty.toDouble() * Geo.LADO - cy)).toInt()
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        imageLoader = cargador,
                        modifier = Modifier
                            .offset { IntOffset(ox, oy) }
                            .size(ladoDp),
                    )
                }
            }

            // La estela, encima del fondo haya fondo o no.
            val colorVivo = Cian
            val mpp = Geo.metrosPorPixel(lat, z)
            Canvas(Modifier.fillMaxSize()) {
                // El margen del GPS, alrededor de la ultima posicion.
                //
                // No es decoracion para llenar el hueco, aunque tambien lo
                // llene: al empezar hay UN punto y sin esto el modo oculto
                // seria una cara sobre nada, que se lee como "se rompio".
                // Y dice algo cierto que el texto ya decia en metros pero que
                // aca se puede comparar con el recorrido de un vistazo: si el
                // halo es mas grande que la estela, lo que se ve es ruido.
                if (precisionM > 0 && mpp > 0) {
                    val r = (precisionM / mpp).toFloat()
                    if (r > 3f && r < size.maxDimension) {
                        drawCircle(colorVivo.copy(alpha = 0.12f), r, px(mundo.last()))
                        drawCircle(
                            colorVivo.copy(alpha = 0.35f), r, px(mundo.last()),
                            style = Stroke(width = 1.5f),
                        )
                    }
                }
                if (mundo.size >= 2) {
                    val camino = Path()
                    mundo.forEachIndexed { i, p ->
                        val o = px(p)
                        if (i == 0) camino.moveTo(o.x, o.y) else camino.lineTo(o.x, o.y)
                    }
                    // Dos trazos: uno grueso y oscuro debajo, para que la línea
                    // se vea igual sobre un mapa claro y sobre el fondo oscuro
                    // del modo oculto. Un solo trazo obliga a elegir un color
                    // que funcione en los dos, y no lo hay.
                    drawPath(
                        camino,
                        Color(0x99000000),
                        style = Stroke(width = 7f, cap = StrokeCap.Round),
                    )
                    drawPath(
                        camino,
                        if (enVivo) colorVivo else Slate,
                        style = Stroke(width = 3.5f, cap = StrokeCap.Round),
                    )
                    // Dónde empezó. Sin esto la línea no dice en qué sentido va.
                    drawCircle(Color(0xAA000000), 5.5f, px(mundo.first()))
                    drawCircle(if (enVivo) colorVivo else Slate, 3.5f, px(mundo.first()))
                }
            }

            // El marcador: la cara, en la última posición. Se corre media cara
            // para que el centro del avatar caiga en el punto y no su esquina.
            val fin = px(mundo.last())
            val medio = with(densidad) { 20.dp.toPx() }
            Box(
                Modifier.offset {
                    IntOffset(
                        (fin.x - medio).toInt().coerceIn(0, max(0, anchoPx - (medio * 2).toInt())),
                        (fin.y - medio).toInt().coerceIn(0, max(0, altoPx - (medio * 2).toInt())),
                    )
                },
            ) {
                when (marcador) {
                    is Marcador.Persona ->
                        MarcadorPersona(marcador.autor, marcador.foto, marcador.enVivo)
                    Marcador.Lugar -> MarcadorLugar()
                }
            }

            // La barra de escala. Es lo que impide leer un paseo de cinco
            // metros como un viaje: el trazo siempre llena la caja.
            val (metros, largoPx) = remember(mpp, anchoPx) {
                Geo.escala(mpp, min(anchoPx / 3, 120))
            }
            if (metros > 0) {
                // El numero ENCIMA de la barra y sobre su propia pastilla
                // oscura. Debajo se salia de la caja, y en blanco sobre un
                // mapa claro se perdia entre los nombres de las calles: la
                // barra sin numero no dice nada, asi que no era un detalle.
                Column(
                    Modifier.align(Alignment.BottomStart).padding(6.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        if (metros >= 1000) "${metros / 1000} km" else "$metros m",
                        color = Color.White,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .background(Color(0x88000000), RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp),
                    )
                    Canvas(Modifier.size(with(densidad) { largoPx.toDp() }, 6.dp)) {
                        drawLine(
                            Color(0xAA000000), Offset(0f, size.height / 2),
                            Offset(size.width, size.height / 2), strokeWidth = 6f,
                        )
                        drawLine(
                            Color.White, Offset(0f, size.height / 2),
                            Offset(size.width, size.height / 2), strokeWidth = 2f,
                        )
                    }
                }
            }

            // El crédito. No es cortesía: la licencia de OpenStreetMap lo pide,
            // y sólo cuando de verdad se usaron sus baldosas.
            if (conBaldosas && TeselaCliente.hay) {
                Text(
                    "© OpenStreetMap",
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0x66000000), RoundedCornerShape(3.dp))
                        .clickable {
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.openstreetmap.org/copyright"),
                                    ),
                                )
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** La cara de quien comparte, con la antena que la distingue de una foto cualquiera. */
@Composable
private fun MarcadorPersona(autor: String, foto: String?, enVivo: Boolean) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                // Un borde claro: sobre un mapa con calles, una cara sin
                // contorno se confunde con el fondo.
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(nombre = autor, url = foto, tamano = 36.dp)
        }
        Box(
            Modifier
                .size(15.dp)
                .clip(CircleShape)
                .background(if (enVivo) Cian else Slate),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Sensors,
                null,
                tint = if (enVivo) Color.Black else TextoTerciario,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/**
 * El pin de un sitio.
 *
 * Del mismo tamaño que el de una persona para que las dos burbujas se lean
 * igual, y con la misma base clara: sobre un mapa con calles, un icono sin
 * contorno se pierde en el fondo.
 */
@Composable
private fun MarcadorLugar() {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Cian),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Place, null, tint = Color.Black, modifier = Modifier.size(20.dp))
        }
    }
}
