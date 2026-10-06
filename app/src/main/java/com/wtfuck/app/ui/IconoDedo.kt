package com.wtfuck.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * El dedo del medio: el "fuck" de wtfuck, en el botón de crear de la lista de
 * chats.
 *
 * ## Por qué dibujado y no un emoji
 *
 * Un emoji trae sus propios colores y cambia de dibujo según el fabricante del
 * teléfono. En un botón cian con el texto oscuro quedaría pegado como una
 * calcomanía. Este es como los íconos de Material: grilla de 24, relleno de un
 * solo color, y `Icon` le pone el tinte del botón.
 *
 * ## La forma
 *
 * El dorso de una mano derecha, con el pulgar a la izquierda: el dedo del
 * medio arriba y los otros doblados en fila. Cada dedo es una columna con la
 * punta redonda, y entre columnas queda una ranura de 0.6. A 24 dp en una
 * pantalla xxhdpi, eso son casi 2 px, lo justo para que se lean los nudillos
 * sin que la mano se vuelva una mancha.
 */
val IconoDedo: ImageVector by lazy {
    ImageVector.Builder(
        name = "Dedo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // El dedo del medio.
            moveTo(10.2f, 13f)
            lineTo(10.2f, 3.8f)
            arcTo(1.8f, 1.8f, 0f, false, true, 13.8f, 3.8f)
            lineTo(13.8f, 13f)
            close()
            // Índice, doblado.
            moveTo(6.4f, 13f)
            lineTo(6.4f, 10.35f)
            arcTo(1.6f, 1.6f, 0f, false, true, 9.6f, 10.35f)
            lineTo(9.6f, 13f)
            close()
            // Anular, doblado.
            moveTo(14.4f, 13f)
            lineTo(14.4f, 10.85f)
            arcTo(1.6f, 1.6f, 0f, false, true, 17.6f, 10.85f)
            lineTo(17.6f, 13f)
            close()
            // Meñique, más bajo y más fino.
            moveTo(18.2f, 13.5f)
            lineTo(18.2f, 12.4f)
            arcTo(1.2f, 1.2f, 0f, false, true, 20.6f, 12.4f)
            lineTo(20.6f, 13.5f)
            close()
            // El dorso y la muñeca. Abajo a la izquierda se ensancha hasta el
            // pulgar, y ahí se cierra la ranura entre el pulgar y el índice.
            moveTo(6.4f, 12.4f)
            lineTo(20.6f, 12.4f)
            lineTo(20.6f, 15.6f)
            curveTo(20.6f, 18.2f, 18.6f, 19.8f, 16.2f, 20.2f)
            lineTo(16.2f, 22.6f)
            lineTo(9.2f, 22.6f)
            lineTo(9.2f, 20.2f)
            curveTo(6.0f, 19.9f, 3.6f, 18.4f, 3.6f, 15.8f)
            lineTo(3.6f, 15.4f)
            lineTo(6.4f, 15.4f)
            close()
            // El pulgar.
            moveTo(3.6f, 15.8f)
            lineTo(3.6f, 13.1f)
            arcTo(1.1f, 1.1f, 0f, false, true, 5.8f, 13.1f)
            lineTo(5.8f, 15.8f)
            close()
        }
    }.build()
}
