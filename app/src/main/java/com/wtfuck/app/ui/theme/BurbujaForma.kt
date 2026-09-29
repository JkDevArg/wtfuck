package com.wtfuck.app.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * La burbuja con pico, al estilo de Telegram.
 *
 * ## Que cambia respecto de la de antes
 *
 * Antes era un `RoundedCornerShape` con la esquina de abajo recortada a 4dp
 * del lado de quien escribe. Se entendia de quien era el mensaje, pero el
 * borde seguia siendo un rectangulo redondeado.
 *
 * Esto anade el **pico**: un saliente pequeno en la esquina inferior del lado
 * del emisor, que es lo que hace que una burbuja se lea como un bocadillo y no
 * como una tarjeta.
 *
 * ## Por que solo lo lleva el ULTIMO de una rafaga
 *
 * Porque el pico apunta a quien habla. Cinco mensajes seguidos de la misma
 * persona con cinco picos parecen cinco intervenciones distintas; con uno
 * solo al final se leen como lo que son, una sola persona hablando seguido.
 * Es la diferencia entre una lista de tarjetas y una conversacion.
 *
 * ## Por que una Shape y no una imagen
 *
 * Porque tiene que seguir al color de la burbuja -que cambia con la paleta- y
 * a la direccion del texto. Una imagen habria que tenerla en cada color y en
 * los dos sentidos, y se quedaria fuera del tema el dia que se agregue una
 * paleta.
 */
class BurbujaConPico(
    private val esMio: Boolean,
    /** `false` en los mensajes del medio de una rafaga: sin pico. */
    private val conPico: Boolean = true,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        with(density) {
            val r = RADIO.toPx()
            val pico = PICO.toPx()
            // El cuerpo deja sitio al pico por su lado. Sin esto, el pico
            // sobresaldria del ancho medido y quedaria cortado por el padre.
            val izq = if (esMio) 0f else if (conPico) pico else 0f
            val der = if (esMio) size.width - (if (conPico) pico else 0f) else size.width
            val abajo = size.height

            val p = Path()
            if (!conPico) {
                // Sin pico: las cuatro esquinas iguales. Es el mensaje del
                // medio de una rafaga, y tiene que verse continuo con los de
                // arriba y abajo.
                p.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        Rect(izq, 0f, der, abajo),
                        androidx.compose.ui.geometry.CornerRadius(r, r),
                    )
                )
                return Outline.Generic(p)
            }

            // El pico son DOS curvas, no tres.
            //
            // El primer intento tenia una tercera que volvia hacia arriba
            // antes de cerrar, y el resultado no era un pico: era una astilla
            // fina, un gancho sin relleno colgando de la esquina. Solo se vio
            // ampliando la captura — a tamano real parecia un borde raro.
            //
            // Con dos curvas el contorno encierra area: sale del lateral,
            // llega a la punta, y vuelve al borde de abajo. Eso es un pico.
            if (esMio) {
                // Arriba izquierda -> arriba derecha, redondeando.
                p.moveTo(izq + r, 0f)
                p.lineTo(der - r, 0f)
                p.quadraticTo(der, 0f, der, r)
                // Baja por la derecha hasta donde arranca el pico.
                p.lineTo(der, abajo - r)
                // Sale hacia la punta.
                p.quadraticTo(der, abajo - r * 0.35f, der + pico, abajo)
                // Y vuelve al borde de abajo. El punto de control por dentro
                // le da la curva concava que tiene un bocadillo de verdad.
                p.quadraticTo(der - pico * 0.4f, abajo, der - r, abajo)
                // Abajo derecha -> abajo izquierda.
                p.lineTo(izq + r, abajo)
                p.quadraticTo(izq, abajo, izq, abajo - r)
                p.lineTo(izq, r)
                p.quadraticTo(izq, 0f, izq + r, 0f)
            } else {
                p.moveTo(izq + r, 0f)
                p.lineTo(der - r, 0f)
                p.quadraticTo(der, 0f, der, r)
                p.lineTo(der, abajo - r)
                p.quadraticTo(der, abajo, der - r, abajo)
                p.lineTo(izq + r, abajo)
                // El pico, espejado.
                p.quadraticTo(izq + pico * 0.4f, abajo, izq - pico, abajo)
                p.quadraticTo(izq, abajo - r * 0.35f, izq, abajo - r)
                p.lineTo(izq, r)
                p.quadraticTo(izq, 0f, izq + r, 0f)
            }
            p.close()
            return Outline.Generic(p)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BurbujaConPico && other.esMio == esMio && other.conPico == conPico

    override fun hashCode(): Int = esMio.hashCode() * 31 + conPico.hashCode()

    companion object {
        val RADIO = 18.dp
        /** Lo que sobresale el pico. Pequeno a proposito: 6dp ya se lee. */
        val PICO = 6.dp
    }
}

/**
 * Si dos mensajes seguidos son del mismo autor y van juntos en el tiempo.
 *
 * Pura para poder probarla: es la regla que decide como se ve una rafaga, y
 * equivocarla se nota mucho -o todo se agrupa, o no se agrupa nada-.
 *
 * El tope de tiempo existe porque el autor no basta: dos mensajes de la misma
 * persona con tres horas en medio son dos conversaciones, y pegarlas haria
 * parecer que la segunda contesta a algo que ya nadie tiene en pantalla.
 */
fun mismaRafaga(
    autorA: String, creadoA: Long, sistemaA: Boolean,
    autorB: String, creadoB: Long, sistemaB: Boolean,
    topeMs: Long = 5 * 60_000L,
): Boolean {
    if (sistemaA || sistemaB) return false
    if (autorA != autorB) return false
    return kotlin.math.abs(creadoB - creadoA) <= topeMs
}
