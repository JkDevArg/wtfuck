package com.wtfuck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Preferencia de tema. Tres valores y no dos.
 *
 * `SISTEMA` existe porque es lo que la gente espera: el teléfono ya decide
 * cuando es de noche y una app que lo ignora obliga a cambiarla a mano dos veces
 * al día. Las otras dos existen porque hay quien quiere lo contrario de lo que
 * diga el sistema, y decirle que no se puede sería raro.
 */
enum class Tema(val etiqueta: String) {
    SISTEMA("Como el sistema"),
    OSCURO("Siempre oscuro"),
    CLARO("Siempre claro"),
}

/**
 * Si con este ajuste se esta pintando en claro.
 *
 * Lo necesita el selector de acento: tiene que ensenar cada color **como se
 * va a ver**, y cada paleta tiene dos versiones. Mostrar la oscura en un tema
 * claro haria elegir un color y recibir otro.
 */
@Composable
fun claroAhora(tema: Tema): Boolean = when (tema) {
    Tema.CLARO -> true
    Tema.OSCURO -> false
    Tema.SISTEMA -> !isSystemInDarkTheme()
}

/**
 * La tipografia de marca (Rajdhani / Barlow / IBM Plex Mono) todavia no esta
 * empaquetada: faltan los archivos de fuente. Se usa la del sistema para no
 * prometer una identidad que el APK no lleva. Al agregar los .ttf en res/font,
 * solo cambian los FontFamily de aqui.
 */
private val tipografia = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    )
}

/** Para huellas de clave y verificacion: el ancho fijo evita errores al comparar. */
val estiloHuella = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/**
 * El tema.
 *
 * El esquema de Material se arma **dentro** del composable y no a nivel de
 * archivo, porque los tokens dependen del modo: uno construido una vez al
 * cargar la clase se quedaria con los colores del primer tema para siempre.
 *
 * `claro` se fija ANTES de leer los tokens, no después: si se fijara después,
 * la primera composición pintaría con la paleta anterior y habría un parpadeo
 * al arrancar.
 */
@Composable
fun WtfuckTheme(
    tema: Tema = Tema.SISTEMA,
    paletaElegida: Paleta = Paleta.CIAN,
    contenido: @Composable () -> Unit,
) {
    val delSistema = isSystemInDarkTheme()
    val esClaro = when (tema) {
        Tema.CLARO -> true
        Tema.OSCURO -> false
        Tema.SISTEMA -> !delSistema
    }
    claro = esClaro
    paleta = paletaElegida

    // La paleta va en la CLAVE del remember, no solo asignada arriba.
    //
    // Sin ella, cambiar de acento no recalcularia el esquema de Material:
    // los tokens propios -que son getters- cambiarian al instante y los
    // componentes de Material3 se quedarian con el color viejo. El resultado
    // seria media pantalla en el color nuevo y media en el anterior, que
    // parece un fallo de pintado y es un remember mal cerrado.
    val esquema = remember(esClaro, paletaElegida) {
        if (esClaro) {
            lightColorScheme(
                primary = Cian,
                onPrimary = TextoSobreAcento,
                primaryContainer = BgElev,
                onPrimaryContainer = TextoPrimario,

                secondary = Ambar,
                onSecondary = TextoSobreAcento,

                error = Coral,
                onError = TextoSobreAcento,

                background = BgBase,
                onBackground = TextoPrimario,

                surface = BgSurface,
                onSurface = TextoPrimario,
                surfaceVariant = BgElev,
                onSurfaceVariant = TextoSecundario,

                outline = Slate,
                outlineVariant = Taupe,
            )
        } else {
            darkColorScheme(
                primary = Cian,
                onPrimary = TextoSobreAcento,
                primaryContainer = BgElev,
                onPrimaryContainer = TextoPrimario,

                secondary = Ambar,
                onSecondary = TextoSobreAcento,

                error = Coral,
                onError = TextoSobreAcento,

                background = BgBase,
                onBackground = TextoPrimario,

                surface = BgSurface,
                onSurface = TextoPrimario,
                surfaceVariant = BgElev,
                onSurfaceVariant = TextoSecundario,

                outline = Slate,
                outlineVariant = Taupe,
            )
        }
    }

    MaterialTheme(colorScheme = esquema, typography = tipografia, content = contenido)
}
