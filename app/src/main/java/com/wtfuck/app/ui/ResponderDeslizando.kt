package com.wtfuck.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wtfuck.app.ui.theme.Cian
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Deslizar una burbuja para responderla.
 *
 * ## El gesto, y por que hacia la derecha
 *
 * Es el gesto mas caracteristico de un mensajero moderno, y no tiene
 * afordancia visible: nadie lo descubre leyendo la pantalla, se descubre
 * porque ya lo conoce de otra app. Eso deja una sola decision razonable sobre
 * la direccion —**la que la gente ya tiene en los dedos**— y es hacia la
 * derecha, para mensajes propios y ajenos por igual.
 *
 * Hacer que los propios vayan a la izquierda "para acompanar el lado de la
 * burbuja" suena simetrico y es peor: obliga a pensar antes de cada gesto.
 *
 * ## Por que hay resistencia
 *
 * Sin resistencia, la burbuja sigue al dedo hasta donde uno la lleve y el
 * unico aviso de que el gesto se activo es el icono. Con resistencia, la
 * burbuja **se frena** al pasar el umbral: el dedo siente el limite antes de
 * que el ojo lo lea, que es la mitad de lo que hace que un gesto se sienta
 * bien hecho.
 *
 * Y una vibracion corta al cruzar, **una sola vez**. Repetirla mientras se
 * arrastra de un lado a otro del umbral convierte una senal en un zumbido.
 *
 * ## Lo que este gesto NO puede ser
 *
 * Un gesto invisible es invisible tambien para quien no ve la pantalla. Por
 * eso ademas del arrastre hay una **accion de accesibilidad** con el mismo
 * nombre: sin ella, responder seria una funcion que existe solo para quien
 * puede arrastrar.
 */
@Composable
fun ParaResponder(
    habilitado: Boolean,
    onResponder: () -> Unit,
    contenido: @Composable () -> Unit,
) {
    if (!habilitado) {
        contenido()
        return
    }

    val densidad = LocalDensity.current
    val haptica = LocalHapticFeedback.current
    val ambito = rememberCoroutineScope()

    val umbralPx = with(densidad) { UMBRAL_RESPUESTA.toPx() }
    val maximoPx = with(densidad) { MAXIMO_ARRASTRE.toPx() }

    val desplazamiento = remember { Animatable(0f) }
    var bruto by remember { mutableStateOf(0f) }
    var yaVibro by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Responder") { onResponder(); true },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val paso = bruto >= umbralPx
                        bruto = 0f
                        yaVibro = false
                        if (paso) onResponder()
                        ambito.launch { desplazamiento.animateTo(0f) }
                    },
                    onDragCancel = {
                        bruto = 0f
                        yaVibro = false
                        ambito.launch { desplazamiento.animateTo(0f) }
                    },
                ) { cambio, delta ->
                    // Solo hacia la derecha: `coerceAtLeast(0f)` y no un valor
                    // absoluto. Arrastrar a la izquierda no hace nada, y que
                    // no haga nada es mejor que que haga algo distinto.
                    bruto = (bruto + delta).coerceAtLeast(0f)
                    val destino = conResistencia(bruto, umbralPx, maximoPx)
                    ambito.launch { desplazamiento.snapTo(destino) }

                    if (bruto >= umbralPx && !yaVibro) {
                        yaVibro = true
                        haptica.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    // Se consume para que la lista no interprete el mismo
                    // movimiento como desplazamiento.
                    if (delta != 0f) cambio.consume()
                }
            },
    ) {
        // El icono vive DETRAS de la burbuja y aparece segun el avance: es lo
        // que convierte un movimiento en una promesa de lo que va a pasar.
        val avance = (desplazamiento.value / umbralPx).coerceIn(0f, 1f)
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            null,
            tint = Cian,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp)
                .size(20.dp)
                .alpha(avance)
                // Crece hasta su tamano justo al llegar al umbral: al soltar
                // ahi, el icono ya esta entero, y esa es la senal.
                .scale(0.6f + avance * 0.4f),
        )

        // `offset` con lambda y no con valor: asi el desplazamiento se lee en
        // la fase de colocacion y arrastrar no recompone la burbuja entera en
        // cada fotograma.
        Box(Modifier.offset { IntOffset(desplazamiento.value.roundToInt(), 0) }) {
            contenido()
        }
    }

    // Si la burbuja se recicla con la lista, el desplazamiento no puede
    // quedarse pegado del mensaje anterior.
    LaunchedEffect(Unit) { desplazamiento.snapTo(0f) }
}

/** Lo que hay que arrastrar para que el gesto cuente. */
val UMBRAL_RESPUESTA = 56.dp

/** Lo mas que se mueve la burbuja, por mucho que se siga arrastrando. */
val MAXIMO_ARRASTRE = 84.dp

/**
 * El desplazamiento que se dibuja para un arrastre dado.
 *
 * Hasta el umbral la burbuja sigue al dedo uno a uno: ahi el gesto todavia se
 * esta decidiendo y cualquier retardo se siente como lentitud.
 *
 * Pasado el umbral avanza a la mitad, y nunca mas alla del maximo. Eso es lo
 * que hace que el dedo **sienta** que llego: un tope que se nota vale mas que
 * un icono que hay que mirar.
 */
fun conResistencia(bruto: Float, umbral: Float, maximo: Float): Float {
    if (bruto <= 0f) return 0f
    if (bruto <= umbral) return bruto
    return (umbral + (bruto - umbral) * 0.5f).coerceAtMost(maximo)
}
