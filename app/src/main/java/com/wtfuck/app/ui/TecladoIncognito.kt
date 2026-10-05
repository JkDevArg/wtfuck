package com.wtfuck.app.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Teclado incognito, como en Signal.
 *
 * ## Que hace, y que NO
 *
 * Le pide al teclado que no APRENDA de lo que se escribe: que no guarde
 * palabras nuevas, no las sugiera despues en otras apps y no las use para su
 * modelo personal. Es el flag `IME_FLAG_NO_PERSONALIZED_LEARNING` de Android.
 *
 * Es un PEDIDO, y hay que decirlo asi: el teclado es otra app y la decision de
 * respetarlo es suya. Gboard y SwiftKey lo respetan; un teclado de terceros
 * puede no hacerlo. Por eso la pantalla habla de "pedir" y no de "impedir".
 *
 * ## Por que en la raiz y no en el campo del chat
 *
 * Porque se escribe en muchos sitios -el buscador, el pie de una foto, el
 * nombre de un grupo, la respuesta a un estado- y todos dicen algo de la
 * persona. Envolviendo la raiz cubre cualquier campo, incluido el que se
 * agregue manana sin acordarse de esto.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TecladoIncognito(activo: Boolean, contenido: @Composable () -> Unit) {
    if (!activo) {
        contenido()
        return
    }
    InterceptPlatformTextInput(
        interceptor = { pedido, siguiente ->
            // Por delegacion y no implementando a mano: si la interfaz suma
            // miembros en otra version de Compose, se heredan del pedido
            // original en vez de romper la compilacion o quedar vacios.
            val incognito = object : PlatformTextInputMethodRequest by pedido {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val conexion = pedido.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return conexion
                }
            }
            siguiente.startInputMethod(incognito)
        },
        content = contenido,
    )
}
