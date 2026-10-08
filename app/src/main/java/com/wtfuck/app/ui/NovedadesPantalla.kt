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
            version = "0.7.0",
            fecha = "08/10/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Versión web: usa tu cuenta en el navegador de tu computadora. Se vincula desde Dispositivos con un código, y la puedes revocar cuando quieras."),
                Cambio(TipoCambio.NUEVO, "Invitar a alguien, en Perfil: para amigos con iPhone o sin Android. Con tu invitación crean su cuenta en la versión web y la instalan en su pantalla de inicio como una app más. Cada invitación sirve una vez y vence en 7 días."),
                Cambio(TipoCambio.MEJORA, "En Dispositivos, los navegadores vinculados se ven con su propio ícono."),
                Cambio(TipoCambio.SEGURIDAD, "Al registrarte, vincular o recuperar la cuenta, el chip de seguridad del teléfono firma una prueba de que es la app original y un sistema sin modificar. Así el servidor puede frenar las cuentas creadas por programas que se hacen pasar por la app."),
            ),
        ),
        NotasVersion(
            version = "0.6.4",
            fecha = "06/10/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Bloqueados, en Privacidad: a quiénes bloqueaste y desbloquearlos. También desde su perfil, que ahora dice si lo bloqueaste."),
                Cambio(TipoCambio.MEJORA, "Modo cerca sin emparejar: con Android 12 o más nuevo te encuentras por Bluetooth con tus contactos sin pasar por los ajustes. Solo te reconoce quien tiene tu clave de cercanía, que viaja sola en tus chats directos; para cualquier otro, tu teléfono es un número que cambia cada cuarto de hora."),
                Cambio(TipoCambio.MEJORA, "Modo cerca con varias personas a la vez, hasta cuatro: para un grupo en la misma sala."),
                Cambio(TipoCambio.SEGURIDAD, "El enlace del modo cerca va cifrado y autenticado con las claves de tus chats, y estrena claves en cada conexión."),
                Cambio(TipoCambio.SEGURIDAD, "Al bloquear a alguien cambia tu clave de cercanía: deja de reconocerte por Bluetooth, y el enlace con esa persona se corta si estaba abierto."),
                Cambio(TipoCambio.MEJORA, "El botón Nuevo de la lista de chats estrena ícono, a tono con el nombre de la app."),
                Cambio(TipoCambio.ARREGLO, "No se podía bloquear a alguien con quien tienes un chat si se ocultaba de la búsqueda, ni desbloquear a quien se ocultaba. Ya se puede."),
            ),
        ),
        NotasVersion(
            version = "0.6.3",
            fecha = "05/10/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Nota para mí: un chat solo tuyo para apuntar y reenviarte cosas. Aparece en todos tus aparatos y va cifrada como cualquier chat. Está en Nuevo."),
                Cambio(TipoCambio.NUEVO, "Reenviar ahora te deja elegir a qué chats (hasta 5), y las fotos y archivos se reenvían enteros."),
                Cambio(TipoCambio.NUEVO, "Responder y marcar como leído desde la notificación."),
                Cambio(TipoCambio.NUEVO, "Formato de texto: *negrita*, _cursiva_, ~tachado~, `código` y ||spoiler||, que se toca para verlo."),
                Cambio(TipoCambio.NUEVO, "Vista previa de enlaces, armada por tu teléfono: quien recibe no visita nada y el servidor no sabe qué enlace mandaste. Se apaga en Privacidad."),
                Cambio(TipoCambio.NUEVO, "Ver una vez para fotos y videos: se abren una sola vez, con las capturas de pantalla bloqueadas, y se borran al cerrarlas."),
                Cambio(TipoCambio.NUEVO, "Mensajes programados: mantén pulsado el botón de enviar y elige la hora. Salen desde tu teléfono."),
                Cambio(TipoCambio.NUEVO, "Enviar sin sonido, en el mismo menú: le llega, pero su teléfono no suena."),
                Cambio(TipoCambio.NUEVO, "Proteger un chat con tu huella o tu PIN: no se ve en la lista, ni en el buscador, ni en las notificaciones."),
                Cambio(TipoCambio.NUEVO, "Carpetas de chats, como pestañas propias. Viven solo en tu teléfono."),
                Cambio(TipoCambio.NUEVO, "En un grupo, \"Info\" en tus mensajes te dice quién lo recibió y quién lo leyó."),
                Cambio(TipoCambio.NUEVO, "Videonotas: mensajes de video en un círculo, desde Adjuntar."),
                Cambio(TipoCambio.NUEVO, "Transcribir notas de voz y traducir mensajes, siempre en tu teléfono y nunca en internet. Depende de que tu teléfono lo sepa hacer sin conexión."),
                Cambio(TipoCambio.NUEVO, "Compartir a wtfuck desde otras apps, tus chats recientes en el menú de compartir y un widget con los mensajes sin leer."),
                Cambio(TipoCambio.NUEVO, "Proxy SOCKS5 o HTTP para las redes que bloquean la app, en Privacidad."),
                Cambio(TipoCambio.NUEVO, "@todos en los grupos, para quien puede fijar mensajes. Una mención te avisa aunque el grupo esté silenciado."),
                Cambio(TipoCambio.NUEVO, "Seleccionar varios mensajes a la vez: mantén pulsado uno y toca los demás para copiarlos, reenviarlos, destacarlos o borrarlos juntos."),
                Cambio(TipoCambio.NUEVO, "Mensajes destacados con una estrella: los de un chat en su menú, y los de todos en el menú de la lista."),
                Cambio(TipoCambio.NUEVO, "Responder en privado a un mensaje de un grupo: se abre tu chat con esa persona, con el mensaje citado."),
                Cambio(TipoCambio.NUEVO, "Varias fotos y videos de una vez, hasta 10, con un solo pie."),
                Cambio(TipoCambio.NUEVO, "Fotos y videos como spoiler: llegan difuminados hasta que los tocan."),
                Cambio(TipoCambio.NUEVO, "Recordarme este mensaje: eliges cuándo y, a esa hora, una notificación te lleva a él."),
                Cambio(TipoCambio.NUEVO, "Un sonido y un fondo propios para cada chat, en el menú del chat. Viven solo en tu teléfono."),
                Cambio(TipoCambio.NUEVO, "Copia de seguridad automática: cada día o cada semana, cifrada con tu frase, en la carpeta que elijas. Se guardan las dos últimas."),
                Cambio(TipoCambio.NUEVO, "Tu enlace y QR para que te escriban sin buscarte, en Perfil. Lo cambias o lo apagas cuando quieras, y el de otros se escanea desde Nuevo."),
                Cambio(TipoCambio.NUEVO, "Listas de difusión: un mensaje a varias personas, cada una en su chat contigo y sin ver a quién más le llegó. En el menú de la lista de chats."),
                Cambio(TipoCambio.MEJORA, "Modo cerca: ahora sí manda mensajes sin internet a quien tengas al lado, por Bluetooth (antes solo recibía). Empareja los teléfonos una vez; sigue funcionando con la pantalla apagada y te dice cuáles llegaron así."),
                Cambio(TipoCambio.MEJORA, "Las fotos del chat pasan por el editor: recorte, giro, filtros, textos y stickers. Si no tocas nada, sale la original."),
                Cambio(TipoCambio.MEJORA, "Lo que dejas escrito sin enviar se guarda por chat, y la lista te lo recuerda."),
                Cambio(TipoCambio.MEJORA, "Teclado incógnito, encendido: le pide al teclado que no aprenda lo que escribes."),
                Cambio(TipoCambio.MEJORA, "Las notas de voz seguidas se escuchan una tras otra, sin tocar cada una."),
                Cambio(TipoCambio.MEJORA, "Ver una vez: quien la mandó ve \"abierta\" cuando la abres, si los dos tienen activadas las confirmaciones de lectura. Y en tus otros aparatos también se da por vista."),
                Cambio(TipoCambio.MEJORA, "Los mensajes que no logran salir se pueden ver, y descartar, tocando la barra \"Por enviar\"."),
                Cambio(TipoCambio.SEGURIDAD, "Antes de abrir un enlace de un mensaje, la app pregunta y te dice a qué sitio lleva de verdad, con un aviso si intenta disfrazarse."),
                Cambio(TipoCambio.ARREGLO, "Tocar una notificación abre el chat, y no la lista."),
                Cambio(TipoCambio.ARREGLO, "Los borradores se perdían cada vez que abrías la app. Ya no."),
                Cambio(TipoCambio.ARREGLO, "Mantener pulsado un enlace o una foto abre el menú del mensaje, en vez de abrir el enlace o la foto."),
                Cambio(TipoCambio.ARREGLO, "Lo que escribes en otro de tus aparatos se ve como tuyo, y ya no te llega como una notificación."),
                Cambio(TipoCambio.ARREGLO, "Ya no aparece un aviso de \"fallo\" cuando el teléfono cierra wtfuck en segundo plano para liberar memoria, como hacen Honor y Huawei. No era un fallo."),
                Cambio(TipoCambio.ARREGLO, "Los chats archivados se abrían vacíos. Ya no."),
                Cambio(TipoCambio.ARREGLO, "Si mandabas muchos mensajes muy rápido, algunos quedaban como fallidos. Ahora esperan un momento y salen solos."),
            ),
        ),
        NotasVersion(
            version = "0.6.2",
            fecha = "04/10/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Nueva pestaña Social, en lugar de Contactos: ahí están los estados y el directorio de Usuarios. Los estados ya no ocupan la parte de arriba de tus chats."),
                Cambio(TipoCambio.NUEVO, "Editor de estados: fotos siempre en vertical y con buena resolución, que puedes encuadrar, girar y decorar con filtros, textos y stickers. También estados de texto, de video y de audio grabado al momento."),
                Cambio(TipoCambio.NUEVO, "Usuarios: un directorio para que te encuentre gente que no sabe tu usuario. Es opcional y viene apagado; si quieres aparecer, actívalo en Privacidad."),
                Cambio(TipoCambio.MEJORA, "Contactos ahora está en el botón Nuevo, y se busca por @usuario en vez de por teléfono."),
                Cambio(TipoCambio.NUEVO, "Puedes borrar tu cuenta desde Privacidad, abajo de todo. Pide confirmar varias veces, y tienes 30 días para arrepentirte."),
                Cambio(TipoCambio.ARREGLO, "Las notificaciones llegan con la app en segundo plano o cerrada. Antes el mensaje se recibía, pero el aviso se perdía."),
                // SEGURIDAD y no ARREGLO, por lo mismo que los temporales de la
                // 0.6.0: quien recibio ese "leido" falso saco conclusiones de
                // algo que no paso, y conviene que lo sepa.
                Cambio(TipoCambio.SEGURIDAD, "Si dejabas un chat abierto y salías de la app, a la otra persona le llegaba \"leído\" aunque no lo hubieras visto, y a ti no te avisaba. Ya no pasa."),
                Cambio(TipoCambio.ARREGLO, "Al abrir un chat ves el último mensaje, y el teclado ya no tapa lo nuevo."),
                Cambio(TipoCambio.ARREGLO, "Las respuestas ya no aparecen escondidas entre mensajes viejos cuando el teléfono de la otra persona tiene mal la hora."),
                Cambio(TipoCambio.MEJORA, "En Ajustes > Notificaciones, la sección \"Con la app cerrada\" te dice qué falta para que lleguen los avisos, con los pasos para Honor, Huawei, Xiaomi y otras marcas."),
                Cambio(TipoCambio.ARREGLO, "Si con una persona todavía no se puede cifrar, tus mensajes a los demás salen igual. Antes se quedaban todos en \"Enviando...\"."),
                Cambio(TipoCambio.ARREGLO, "Añadir a alguien a una llamada ahora sí arranca la llamada del grupo, y a la otra persona le suena."),
            ),
        ),
        NotasVersion(
            version = "0.6.1",
            fecha = "29/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "En una llamada de dos ya puedes anadir a otra persona: se crea un grupo con los tres y la llamada sigue ahi. El grupo se queda en tus chats."),
                Cambio(TipoCambio.MEJORA, "En la llamada se ve la foto de la persona, no sus iniciales."),
                // ARREGLO y no MEJORA: para quien lo sufrio, esto no era una
                // funcion que faltaba, era la app cerrandose en la cara.
                Cambio(TipoCambio.ARREGLO, "Las llamadas ya no cierran la app. Pasaba solo en la version publicada -no en desarrollo- y por eso tardo en verse: el optimizador borraba unas clases que WebRTC busca al arrancar la llamada."),
                Cambio(TipoCambio.NUEVO, "Boton \"Buscar actualizacion\" al final del perfil, para comprobar cuando quieras si hay una version nueva. Tambien te dice cuando ya tienes la ultima."),
                Cambio(TipoCambio.MEJORA, "Si la app se cierra de golpe -sin llegar a avisar-, ahora recupera del sistema el motivo y te lo ofrece al volver a abrirla. Antes esos cierres no dejaban ningun rastro."),
            ),
        ),
        NotasVersion(
            version = "0.6.0",
            fecha = "27/09/2026",
            cambios = listOf(
                Cambio(TipoCambio.NUEVO, "Copia de seguridad cifrada: guarda tus chats con sus fotos en un archivo con una frase, y recupéralos si cambias o pierdes el teléfono."),
                Cambio(TipoCambio.MEJORA, "La app te recuerda cuándo hiciste la última copia de seguridad, para que no se te pase."),
                // Se declara como SEGURIDAD y no como ARREGLO a proposito:
                // quien uso los mensajes temporales antes de esta version creyo
                // que se borraban en los dos telefonos y solo se borraban en el
                // suyo. Eso no es un detalle que se arregla, es algo que la
                // persona necesita saber para decidir si tiene que borrar una
                // conversacion a mano.
                Cambio(TipoCambio.SEGURIDAD, "Los mensajes temporales ahora se borran de verdad en los DOS teléfonos. Antes desaparecían solo del tuyo y se quedaban en el de la otra persona. Si usaste esta función antes de esta versión, esos mensajes siguen en el otro teléfono."),
                Cambio(TipoCambio.NUEVO, "Mensajes temporales en chats de dos: antes solo se podían activar en grupos, y solo por un administrador."),
                Cambio(TipoCambio.MEJORA, "El temporizador ahora se ve: sale junto al nombre en el chat, dice en cuánto está, y queda escrito en la conversación cuando alguien lo cambia."),
                Cambio(TipoCambio.SEGURIDAD, "Recuperar la cuenta por SMS ahora pide tambien el codigo de dos pasos. Antes el SMS lo saltaba: quien se quedara con tu numero podia cambiarte la contrasena y cerrarte todas las sesiones con el segundo factor puesto."),
                Cambio(TipoCambio.ARREGLO, "La app ya no puede reenviar el mismo mensaje dos veces al reconectarse."),
                Cambio(TipoCambio.MEJORA, "La lista de chats va mas suelta con historiales grandes."),
                Cambio(TipoCambio.NUEVO, "Codigo de recuperacion: si pierdes el telefono, es lo unico que te devuelve la cuenta. Creado en Cuenta, se anota en papel. Antes, perder el telefono era perder la cuenta para siempre."),
                Cambio(TipoCambio.NUEVO, "La copia de seguridad puede llevar tu identidad cifrada. Al restaurarla en otro telefono conservas tu numero de seguridad y a tus contactos no les salta ninguna alarma."),
                Cambio(TipoCambio.ARREGLO, "La pantalla de copia de seguridad decia que para restaurar en un telefono nuevo habia que entrar primero a la cuenta, y en un telefono nuevo no se podia entrar. Ahora dice lo que hace falta de verdad."),
                Cambio(TipoCambio.MEJORA, "Si la app se cierra sola, guarda un informe en tu telefono y te ofrece enviarlo. No lleva el texto de tus chats ni tu usuario, y puedes leerlo entero antes de decidir."),
                Cambio(TipoCambio.ARREGLO, "Los mensajes escritos sin conexion ahora salen solos cuando vuelve la red, aunque hayas cerrado la app. Antes se quedaban esperando a que la volvieras a abrir."),
                Cambio(TipoCambio.SEGURIDAD, "El codigo de recuperacion detecta mejor las erratas: antes casi la mitad de los errores en el ultimo caracter se aceptaban en silencio."),
                Cambio(TipoCambio.NUEVO, "Nuevo ajuste para bloquear capturas de pantalla dentro de la app. Apagado por defecto: hay motivos legitimos para capturar una conversacion propia."),
                Cambio(TipoCambio.ARREGLO, "En Android anterior al 13, la pantalla decia que con el bloqueo activo la app salia en blanco en recientes, y no era cierto. Ahora lo dice claro y ofrece como taparla."),
                Cambio(TipoCambio.MEJORA, "Si un video o un archivo no cabe, se avisa ANTES de enviarlo y con los dos numeros -lo que pesa y el limite-, en vez de dejar un mensaje en rojo."),
                Cambio(TipoCambio.NUEVO, "Exportar una conversacion a un archivo de texto, desde el menu del chat. Los mensajes temporales NO se exportan: quien los escribio pidio que no quedaran guardados."),
                Cambio(TipoCambio.NUEVO, "Buscar en TODOS los chats a la vez desde el buscador de la lista: ya no hace falta acordarse de en que conversacion se dijo algo."),
                Cambio(TipoCambio.NUEVO, "Puedes elegir el color de la app: seis acentos en Perfil > Apariencia. El ambar de \"pendiente\" y el coral de \"error\" no cambian, porque son significado y no decoracion."),
                Cambio(TipoCambio.NUEVO, "Fondo para el chat: degradado, con tu color, o mas oscuro. Las burbujas siguen opacas, asi que el texto se lee igual de bien."),
                Cambio(TipoCambio.MEJORA, "Las burbujas tienen pico y los mensajes seguidos de la misma persona se agrupan, en vez de verse como fichas sueltas."),
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
