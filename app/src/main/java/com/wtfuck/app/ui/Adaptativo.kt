package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wtfuck.app.ui.theme.*

/**
 * L/N · Dos paneles cuando la pantalla da para dos.
 *
 * ## Por que 720 dp y no el breakpoint de Material
 *
 * Material llama "expanded" a 840 dp y recomienda lista-detalle desde ahi. Aqui
 * el umbral es 720 porque el reparto de esta pantalla es 320 para la lista y el
 * resto para la conversacion: con 720 quedan 320 + 400, y 400 dp de burbujas es
 * mas ancho que la mayoria de los telefonos en los que la app ya se ve bien.
 * Esperar a 840 dejaria una tablet de 10" en vertical usando media pantalla
 * para nada.
 *
 * ## Por que la seleccion NO es navegacion
 *
 * Con dos paneles, tocar un chat no apila un destino: cambia lo que muestra el
 * panel derecho. Si fuera navegacion, el boton "atras" del sistema tendria que
 * deshacer algo que visualmente no paso -la lista nunca desaparecio-, y el
 * historial se llenaria de un destino por chat mirado.
 *
 * En pantalla angosta sigue siendo navegacion, porque ahi si desaparece la
 * lista y el "atras" significa exactamente lo que parece.
 */
@Composable
fun anchoParaDosPaneles(): Boolean {
    val cfg = LocalConfiguration.current
    // El ALTO tambien manda, y esto se aprendio probando: un telefono en
    // horizontal tiene de sobra los 720 dp de ancho pero solo unos 415 de
    // alto, y ahi la cabecera de la lista -titulo, aviso de conexion, buscador
    // y filtros- se come casi todo, dejando dos paneles apretados en vez de
    // uno usable. Con el minimo de alto, un telefono acostado se queda con un
    // panel y una tablet -800 x 1280- entra en dos.
    return cfg.screenWidthDp >= 720 && cfg.screenHeightDp >= 480
}

/**
 * El panel derecho cuando no hay ninguna conversacion elegida.
 *
 * Existe porque el hueco vacio de un lista-detalle es la mitad de la pantalla:
 * dejarlo en negro parece que la app no cargo. Y se aprovecha para decir lo
 * unico que vale decir en un sitio donde no hay nada que hacer todavia.
 */
@Composable
fun PanelVacio(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(BgBase).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            null,
            tint = Slate,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Elige una conversación",
            style = MaterialTheme.typography.titleMedium,
            color = TextoSecundario,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Cian, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "Cifradas de extremo a extremo",
                style = MaterialTheme.typography.bodyMedium,
                color = TextoTerciario,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "El historial vive solo en este aparato, cifrado. El servidor guarda " +
                "sobres que no puede abrir.",
            style = MaterialTheme.typography.bodySmall,
            color = TextoTerciario,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
    }
}

/**
 * Lista a la izquierda, detalle a la derecha.
 *
 * La lista tiene ancho FIJO y el detalle se queda con el resto, no al reves ni
 * con pesos: una lista de chats no gana nada con ser mas ancha -los titulos ya
 * entran- y una conversacion si. Con pesos, en un monitor de escritorio la
 * lista terminaria ocupando 600 dp de nombres cortos.
 */
@Composable
fun DosPaneles(
    lista: @Composable () -> Unit,
    detalle: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(360.dp).fillMaxHeight()) { lista() }
        VerticalDivider(color = Slate.copy(alpha = 0.35f))
        Box(Modifier.weight(1f).fillMaxHeight()) { detalle() }
    }
}
