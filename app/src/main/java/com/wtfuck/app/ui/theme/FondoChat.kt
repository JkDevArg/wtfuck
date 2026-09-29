package com.wtfuck.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * El fondo del chat.
 *
 * ## Por que se puede poner un fondo sin romper nada
 *
 * Porque **las burbujas siguen siendo opacas**. Es la misma solucion que usa
 * Telegram y no es casualidad: el texto de un mensaje se lee contra el color
 * de su burbuja, no contra el fondo, asi que el fondo puede ser cualquier cosa
 * sin tocar el contraste que este proyecto mide.
 *
 * Lo unico que SI queda encima del fondo son las lineas de sistema y las
 * fechas. Por eso los degradados de aqui se mantienen dentro del rango de
 * luminosidad del fondo normal de cada tema: oscuros en oscuro, claros en
 * claro. Un fondo claro en tema oscuro dejaria esos textos ilegibles, y son
 * los unicos que no tienen burbuja que los proteja.
 *
 * ## Por que degradados y no fotos
 *
 * Una foto de fondo es lo que mas se pide y lo que peor sale: el texto sin
 * burbuja cae sobre lo que toque —una zona clara, uno oscura— y no hay forma
 * de garantizar que se lea. Telegram lo resuelve oscureciendo la foto hasta
 * dejarla casi plana, que es admitir que el problema existe.
 *
 * Los degradados dan el aire sin la trampa: se sabe exactamente entre que dos
 * luminosidades se mueve el fondo, porque las elige el tema.
 */
enum class FondoChat(val etiqueta: String) {
    /** El de siempre: el color de fondo de la app, plano. */
    NINGUNO("Ninguno"),

    /** Diagonal suave. El mas parecido al de Telegram. */
    DEGRADADO("Degradado"),

    /**
     * Degradado tenido con el acento elegido.
     *
     * Es el unico que cambia con la paleta, y por eso esta: hace que elegir
     * color se note en la pantalla donde se pasa el tiempo, no solo en los
     * botones.
     */
    ACENTO("Con tu color"),

    /** Mas oscuro que el fondo normal. Para leer de noche. */
    PROFUNDO("Profundo");

    /**
     * El pincel con el que se pinta.
     *
     * Se calcula dentro de la composicion porque depende del tema y de la
     * paleta, que son estado global mutable: guardarlo en el enum lo dejaria
     * congelado con los colores del arranque.
     */
    fun pincel(esClaro: Boolean, acento: Color): Brush = when (this) {
        NINGUNO -> Brush.linearGradient(listOf(BgBase, BgBase))

        DEGRADADO -> if (esClaro) {
            Brush.linearGradient(
                listOf(Color(0xFFEFF4F4), Color(0xFFE2EBEB)),
                start = Offset.Zero, end = Offset.Infinite,
            )
        } else {
            Brush.linearGradient(
                listOf(Color(0xFF121818), Color(0xFF0A0E0E)),
                start = Offset.Zero, end = Offset.Infinite,
            )
        }

        // Un 16% del acento arriba, degradando al fondo normal abajo.
        //
        // El primer intento fue 8% y en pantalla no se veia: al ampliarlo se
        // distinguia, pero a simple vista el chat parecia sin fondo. Un fondo
        // que hay que buscar no es un fondo.
        //
        // El techo sigue siendo que la burbuja propia -que ES el acento al
        // 100%- se recorte contra el. A 16% la diferencia sigue siendo
        // enorme; pasado ~25% empiezan a parecerse.
        ACENTO -> Brush.linearGradient(
            listOf(
                mezclar(BgBase, acento, if (esClaro) 0.14f else 0.16f),
                BgBase,
            ),
            start = Offset.Zero, end = Offset.Infinite,
        )

        PROFUNDO -> if (esClaro) {
            Brush.linearGradient(listOf(Color(0xFFDDE6E6), Color(0xFFCFDADA)))
        } else {
            Brush.linearGradient(listOf(Color(0xFF080B0B), Color(0xFF040606)))
        }
    }

    /** Una muestra plana para el selector. */
    fun muestra(esClaro: Boolean, acento: Color): Color = when (this) {
        NINGUNO -> BgBase
        DEGRADADO -> if (esClaro) Color(0xFFE2EBEB) else Color(0xFF121818)
        ACENTO -> mezclar(BgBase, acento, if (esClaro) 0.14f else 0.16f)
        PROFUNDO -> if (esClaro) Color(0xFFCFDADA) else Color(0xFF080B0B)
    }
}

/**
 * Mezcla dos colores. `cuanto` es cuanto del segundo entra.
 *
 * A mano y no con `lerp` de Compose para poder probarlo en JUnit sin
 * framework: `androidx.compose.ui.graphics.lerp` arrastra el runtime.
 */
internal fun mezclar(base: Color, encima: Color, cuanto: Float): Color {
    val c = cuanto.coerceIn(0f, 1f)
    return Color(
        red = base.red + (encima.red - base.red) * c,
        green = base.green + (encima.green - base.green) * c,
        blue = base.blue + (encima.blue - base.blue) * c,
        alpha = 1f,
    )
}

/** Aplica el fondo a un contenedor. */
fun Modifier.fondoDeChat(fondo: FondoChat, esClaro: Boolean, acento: Color): Modifier =
    this.background(fondo.pincel(esClaro, acento))
