package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.ui.theme.*

/**
 * Novedades: qué cambió en cada versión, contado para quien usa la app.
 *
 * ## Por qué existe, y por qué el texto no es el de los commits
 *
 * A quien instala la app no le sirve "strippear las nativas bajó el APK de 162
 * a 26 MB": le sirve "la app pesa mucho menos". El registro de cambios tecnico
 * ya vive en git y en `docs/06-HOJA-DE-RUTA.md`; esto es la otra cara, la que
 * responde "¿y esto en qué me cambia a mí?".
 *
 * ## Por qué va en la app y no en una web
 *
 * Porque el APK se reparte fuera de una tienda: no hay una ficha de Play Store
 * donde leer "qué hay de nuevo". Si no esta aqui, no esta en ningun lado que la
 * persona vaya a mirar.
 *
 * ## Por qué el historial es una lista en el codigo y no algo que baja del server
 *
 * Cada version de la app trae SUS novedades: son las de ese APK, no las del
 * servidor. Meterlas en el binario las ata a la version correcta —la 0.3.0
 * nunca puede mostrar novedades de una 0.4.0 que este aparato no tiene— y evita
 * una llamada de red para algo que ya se conoce al compilar.
 */

/** El tipo de cambio, con su etiqueta. El color lo pone [colorDe], que respeta el tema. */
enum class TipoCambio(val etiqueta: String) {
    NUEVO("Nuevo"),
    MEJORA("Mejora"),
    SEGURIDAD("Seguridad"),
    ARREGLO("Corrección"),
}

/**
 * El color de cada tipo. Se resuelve aqui y no en el enum porque los colores del
 * tema (`Cian`, `Ambar`...) dependen de si esta en claro u oscuro, y un valor
 * fijo en el enum se congelaria con el tema que hubiera al cargar la clase.
 *
 * Seguridad en ambar: es lo que mas conviene que se note. Nuevo en cian —el
 * color de la marca— para lo que suma; correccion en coral, el mismo rojo de los
 * errores en el resto de la app; mejora en un tono neutro, que no compite.
 */
private fun colorDe(t: TipoCambio): Color = when (t) {
    TipoCambio.NUEVO -> Cian
    TipoCambio.MEJORA -> Slate
    TipoCambio.SEGURIDAD -> Ambar
    TipoCambio.ARREGLO -> Coral
}

data class Cambio(val tipo: TipoCambio, val texto: String)

data class NotasVersion(val version: String, val fecha: String, val cambios: List<Cambio>)

/**
 * El historial, de lo mas nuevo a lo mas viejo.
 *
 * Al agregar una version: se anota ARRIBA, con la fecha del dia que se publica,
 * y en frases de lo que la persona NOTA —no de lo que se toco por dentro—.
 *
 * ## Como numerar (semver)
 *
 * El numero tiene tres partes: MAYOR.MENOR.PARCHE (p.ej. 0.5.1).
 *
 *  - **Mejora pequena** —un arreglo, un pulido, un detalle—: sube el ULTIMO.
 *    0.5.0 -> 0.5.1 -> 0.5.2.
 *  - **Mejora grande** —una funcion nueva, un cambio que se nota mucho—: sube
 *    el DEL MEDIO y el ultimo vuelve a 0. 0.5.3 -> 0.6.0.
 *
 * El primer numero (0.x) se queda en 0 hasta que la app se considere
 * terminada; ahi pasa a 1.0.0.
 */
object Novedades {
    val historial: List<NotasVersion> = listOf(
        NotasVersion(
            version = "0.6.0",
            fecha = "27/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Copia de seguridad cifrada: guarda tus chats en un archivo con una frase y recupéralos si cambias o pierdes el teléfono."),
            ),
        ),
        NotasVersion(
            version = "0.5.1",
            fecha = "27/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.MEJORA, "Al desbloquear el teléfono, la app ya no muestra por un momento un aviso de \"sin conexión\" que desaparece solo."),
            ),
        ),
        NotasVersion(
            version = "0.5.0",
            fecha = "27/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.MEJORA, "Para administradores: la lista de personas del panel muestra los últimos registros al abrirla, sin tener que buscar."),
            ),
        ),
        NotasVersion(
            version = "0.4.0",
            fecha = "27/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Esta sección de Novedades: aquí ves qué cambió en cada versión."),
            ),
        ),
        NotasVersion(
            version = "0.3.0",
            fecha = "27/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.MEJORA, "La app pesa mucho menos: la descarga pasó de 162 MB a 26 MB."),
                Cambio(TipoCambio.NUEVO, "La app te avisa sola cuando hay una versión nueva y la instalas con un toque, sin ir al navegador."),
                Cambio(TipoCambio.SEGURIDAD, "Las actualizaciones solo se descargan por conexión segura y se verifican antes de instalar."),
                Cambio(TipoCambio.MEJORA, "Puedes ver qué versión tienes al final de tu perfil."),
            ),
        ),
        NotasVersion(
            version = "0.2.0",
            fecha = "26/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Registro por invitación: en servidores cerrados entras con un código que te comparte alguien de dentro."),
                Cambio(TipoCambio.NUEVO, "Al terminar una llamada, en el chat queda cuánto duró."),
                Cambio(TipoCambio.MEJORA, "Tu foto de perfil y de portada se ajustan solas al subirlas; tócalas para verlas a pantalla completa."),
            ),
        ),
        NotasVersion(
            version = "0.1.0",
            fecha = "Primera versión",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Mensajería cifrada de extremo a extremo, sin número de teléfono: solo tu usuario."),
            ),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovedadesPantalla(onAtras: () -> Unit) {
    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                    }
                },
                title = { Text("Novedades", color = TextoPrimario, maxLines = 1) },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 28.dp),
        ) {
            items(Novedades.historial, key = { it.version }) { v ->
                TarjetaVersion(v, esActual = v.version == BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun TarjetaVersion(v: NotasVersion, esActual: Boolean) {
    Surface(color = BgSurface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Versión ${v.version}", color = TextoPrimario,
                     fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                // "La que tienes": ubica a la persona sin que tenga que ir a
                // mirar el numero al perfil y volver a comparar.
                if (esActual) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = Cian.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                        Text("La que tienes", color = Cian, fontSize = 11.sp,
                             modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(v.fecha, color = TextoTerciario, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            v.cambios.forEach { c ->
                FilaCambio(c)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun FilaCambio(c: Cambio) {
    Row(verticalAlignment = Alignment.Top) {
        // La etiqueta de tipo, de ancho fijo, para que los textos de la derecha
        // arranquen todos a la misma altura y la columna se lea de corrido.
        Surface(
            color = colorDe(c.tipo).copy(alpha = 0.15f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.width(84.dp),
        ) {
            Text(
                c.tipo.etiqueta,
                color = colorDe(c.tipo),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            c.texto,
            color = TextoSecundario,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
