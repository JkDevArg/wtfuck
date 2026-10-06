package com.wtfuck.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wtfuck.app.ui.theme.*

/**
 * Varias fotos o videos elegidos a la vez: se ven antes de mandarlos, con un
 * pie para el primero. Sin editor: editar diez fotos una por una no es lo que
 * quiere quien elige diez.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaVariosAdjuntos(uris: List<Uri>, pieInicial: String, onEnviar: (String, Boolean) -> Unit, onCerrar: () -> Unit) {
    var pie by remember { mutableStateOf(pieInicial) }
    var spoiler by remember { mutableStateOf(false) }
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = hoja, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                "${uris.size} para enviar",
                color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uris) { u ->
                    AsyncImage(
                        model = u, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            OutlinedTextField(
                value = pie, onValueChange = { pie = it },
                placeholder = { Text("Añade un pie (va con el primero)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Como spoiler: difuminadas hasta tocarlas", color = TextoSecundario, modifier = Modifier.weight(1f))
                Switch(checked = spoiler, onCheckedChange = { spoiler = it })
            }
            Button(
                onClick = { onEnviar(pie.trim(), spoiler) },
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Enviar ${uris.size}") }
        }
    }
}
