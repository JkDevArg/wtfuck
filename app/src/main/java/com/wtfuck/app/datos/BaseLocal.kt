package com.wtfuck.app.datos

import android.content.Context
import androidx.room.Index
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

// ============================================================
//  Entidades
// ============================================================

/** Una clase y cuantas hay. Proyeccion de [BaseDao.recuentoAdjuntos]. */
data class RecuentoClase(val clase: String, val cuantos: Int)

@Entity(tableName = "conversacion")
data class ConversacionEnt(
    @PrimaryKey val id: String,
    val tipo: String,                 // "directa" | "grupo"
    val nombre: String,               // username del otro, o nombre del grupo
    val nombreMostrado: String = "",  // nombre elegido por la persona, si puso uno
    val participantes: String,        // usernames separados por coma
    /** De quien es la foto que se muestra. En grupos va vacio. */
    val avatarUsername: String = "",
    /** Marca de tiempo del avatar: es el cache-buster de la URL. */
    val avatarVersion: Long = 0,
    val noLeidos: Int = 0,
    /** Mi rol aqui. Solo para decidir que mostrar; autorizar es del servidor. */
    val miRol: String = "miembro",
    val miJerarquia: Int = 10,
    /** -1 = silenciado para siempre. 0 = sin silencio. */
    val silenciadoHasta: Long = 0,
    val archivado: Boolean = false,
    val fijado: Boolean = false,
    /**
     * M.3 · La marque como no leida a mano.
     *
     * Es un campo aparte y no un `noLeidos = 1` falso, por dos razones. Una: el
     * contador dice cuantos mensajes hay sin leer, y mentirle rompe el numero
     * del globo. Dos: la marca tiene que sobrevivir a que llegue un mensaje
     * nuevo y a que se vaya, y un contador que se suma y se pisa no puede
     * distinguir "tengo tres sin leer" de "la deje marcada".
     *
     * Se apaga sola al ABRIR el chat, que es la unica accion que significa
     * "ya la vi". Mirarla en la lista no cuenta.
     */
    /**
     * Cuando este chat se borra solo, en epoch ms. `0` = nunca.
     *
     * Lo calcula el SERVIDOR y viaja con la conversacion; aqui solo se guarda.
     * Que la fecha venga de un solo reloj es lo que evita que el mismo chat
     * venza en momentos distintos en cada telefono — y que el que lo tuviera
     * adelantado lo borrara mientras el otro sigue escribiendo.
     */
    val expiraEn: Long = 0,
    /**
     * Segundos de vida de cada mensaje NUEVO de este chat. `0` = permanentes.
     *
     * No es lo mismo que [expiraEn], que borra el chat entero: esto borra cada
     * mensaje por separado y deja el chat.
     *
     * Aqui se guarda porque **este telefono es el que tiene que cumplirlo**.
     * El servidor no puede borrar el mensaje de nadie: no lo tiene. Entrega el
     * sobre y lo olvida. Asi que el vencimiento lo pone quien recibe, con este
     * numero, al guardar el mensaje. Ver `aplicarTemporizador`.
     */
    val temporalesSegundos: Int = 0,
    val marcadaNoLeida: Boolean = false,
    /**
     * Si sigo perteneciendo a esta conversacion.
     *
     * Se conserva la fila aunque me expulsen: el historial ya esta en este
     * telefono y borrarlo sin preguntar seria peor. Lo que cambia es que no se
     * puede escribir ni administrar.
     */
    val soyMiembro: Boolean = true,
)

/**
 * Un mensaje encontrado por la busqueda global, con de que chat viene.
 *
 * Lleva los datos de la conversacion pegados para que la lista de resultados
 * se pueda dibujar sin una consulta por fila: un resultado que no dice de que
 * chat es no sirve de nada, y resolverlo despues serian 200 consultas.
 */
data class ResultadoBusqueda(
    val id: String,
    val conversacionId: String,
    val autor: String,
    val esMio: Boolean,
    val texto: String,
    val creadoEn: Long,
    val tipo: String,
    val nombre: String,
    val nombreMostrado: String,
    val avatarUsername: String,
    val avatarVersion: Long,
    val aliasContacto: String,
) {
    /** Como se llama el chat, con las mismas reglas que la lista. */
    val titulo: String
        get() = when {
            aliasContacto.isNotBlank() -> aliasContacto
            nombreMostrado.isNotBlank() && tipo == "directa" -> nombreMostrado
            else -> nombre
        }
}

/**
 * Una fila de la lista de chats.
 *
 * El ultimo mensaje NO se copia a `conversacion`: se lee con un JOIN. Asi el
 * check de la lista y el de la burbuja salen del MISMO dato, y cuando llega el
 * acuse "entregado" ambos cambian a la vez sin sincronizar nada a mano.
 */
data class ChatFila(
    val id: String,
    val tipo: String,
    val nombre: String,
    val nombreMostrado: String,
    val participantes: String,
    val avatarUsername: String,
    val avatarVersion: Long,
    val noLeidos: Int,
    val ultimoTexto: String?,
    val ultimaFecha: Long?,
    val ultimoEsMio: Boolean?,
    val ultimoAutor: String?,
    val ultimoEstado: String?,
    /** Clase del adjunto del ultimo mensaje, para escribir "Foto" y no el pie. */
    val ultimoAdjuntoClase: String?,
    /** Nombre del documento, que para un adjunto sin clase propia ES el resumen. */
    val ultimoAdjuntoNombre: String?,
    val miRol: String,
    val miJerarquia: Int,
    val silenciadoHasta: Long,
    val archivado: Boolean,
    val fijado: Boolean,
    val marcadaNoLeida: Boolean,
    val soyMiembro: Boolean,
    /** Cuando este chat se borra solo. `0` = nunca. Ver [ConversacionEnt]. */
    val expiraEn: Long = 0,
    /**
     * Temporizador de mensajes, en segundos. `0` = permanentes.
     *
     * Viaja en la fila de la lista, y no se lee aparte al abrir el chat, para
     * que el reloj de la cabecera cambie SOLO cuando el otro lado lo cambia:
     * el aviso escribe la columna y el `Flow` lo repinta. Leerlo una vez al
     * entrar dejaria la cabecera mintiendo hasta que alguien saliera y volviera.
     */
    val temporalesSegundos: Int = 0,
    /** Como llamo YO a la otra persona, si la tengo agendada. Ver [titulo]. */
    val aliasContacto: String = "",
    /** Lo mismo para quien escribio el ultimo mensaje de un grupo. */
    val aliasAutor: String = "",
) {
    /** El globo se pinta si hay mensajes sin leer O si la marque a mano. */
    val sinLeer: Boolean get() = noLeidos > 0 || marcadaNoLeida

    val silenciado: Boolean
        get() = silenciadoHasta == -1L || (silenciadoHasta > 0 && silenciadoHasta > System.currentTimeMillis())

    /**
     * Lo que se ve como titulo de la fila.
     *
     * ## El orden, y por que no es el obvio
     *
     * 1. **Mi alias de contacto**, si lo tengo agendado.
     * 2. Si no, el **username**.
     * 3. Para un grupo o un canal, su nombre propio.
     *
     * Lo que NO se usa en una conversacion directa es `nombreMostrado`, que es
     * el nombre que la otra persona **se puso a si misma**. Y esa es la
     * decision: un nombre que elige quien esta del otro lado es un dato
     * controlado por quien podria querer hacerse pasar por alguien. Con el de
     * titulo, cualquiera se llama "Tatiana" y en la lista se ve igual que la
     * Tatiana de verdad.
     *
     * El alias si se puede mostrar solo, sin username al lado, porque **lo
     * escribi yo**: si dice "Tatiana" es porque yo decidi que esa cuenta es
     * Tatiana. Es la misma logica por la que una agenda de telefono muestra
     * nombres y no numeros.
     *
     * Quien quiera ver el nombre que la persona se puso lo tiene en su perfil,
     * que es donde ese dato significa algo: ahi se lee como "asi se llama esta
     * cuenta", no como "esta es Fulano".
     */
    val titulo: String get() = when {
        aliasContacto.isNotBlank() -> aliasContacto
        tipo != "directa" -> nombreMostrado.ifBlank { nombre }
        else -> nombre
    }
}

/**
 * ## Los indices, y por que son estos
 *
 * Esta tabla es la mas grande de la app y la que mas se escribe. Room
 * **reinvalida toda consulta que la toque en cada escritura**, asi que un
 * `Flow` sin indice no cuesta una vez: cuesta una vez por mensaje recibido.
 *
 * La pantalla principal tenia tres de esos a la vez -el tamano de la cola, el
 * de los fallidos y las conversaciones con fallidos-, todos filtrando por
 * `estado` sin indice. O sea **tres escaneos completos de la tabla por cada
 * mensaje que entra**, y creciendo con el historial.
 *
 *  - `(conversacionId, creadoEn)`: abrir un chat y ordenarlo.
 *  - `(esMio, estado)`: la cola de salida y los fallidos. Compuesto y en ese
 *    orden porque todas esas consultas filtran por los dos, empezando por
 *    `esMio`.
 *  - `expiraEn`: el barrido de temporales, que corre al abrir la app y cada
 *    chat. `historia` ya tenia el suyo para lo mismo; aqui faltaba.
 *  - `adjuntoEstado`: recuperar subidas interrumpidas al arrancar.
 *
 * No se indexa `rutaLocal` -solo la mira la pantalla de almacenamiento, a
 * mano- ni `oculto`, que casi siempre vale lo mismo y no separa nada.
 */
@Entity(
    tableName = "mensaje",
    indices = [
        Index(value = ["conversacionId", "creadoEn"]),
        Index(value = ["esMio", "estado"], name = "mensaje_cola"),
        Index(value = ["expiraEn"], name = "mensaje_vence"),
        Index(value = ["adjuntoEstado"], name = "mensaje_adjunto_estado"),
    ],
)
data class MensajeEnt(
    @PrimaryKey val id: String,
    val conversacionId: String,
    val autor: String,
    val esMio: Boolean,
    val texto: String,
    val creadoEn: Long,
    /** PENDIENTE | ENVIADO | ENTREGADO | LEIDO | FALLIDO */
    val estado: String,
    /**
     * Aviso del sistema ("X te agrego a este grupo"), no un mensaje de nadie.
     * Se dibuja centrado, sin burbuja ni checks.
     */
    val esSistema: Boolean = false,

    // --- modulo C ---------------------------------------------------
    /** Id del mensaje al que responde. */
    val respondeA: String? = null,
    /** Copia del texto citado. Se guarda aqui a proposito: si el original se
     *  borra, la cita sigue teniendo sentido en el hilo. */
    val respondeTexto: String? = null,
    val respondeAutor: String? = null,
    val editado: Boolean = false,
    /** Retirado "para todos": la fila se conserva para dejar el hueco visible. */
    val retirado: Boolean = false,
    val fijado: Boolean = false,
    /** Si es un reenvio, de quien venia. */
    val reenviadoDe: String? = null,
    /** Reacciones serializadas como JSON. La UI no las recalcula. */
    val reaccionesJson: String = "",
    /** Vencimiento de un mensaje temporal. 0 = permanente. */
    val expiraEn: Long = 0,
    /**
     * Por que fallo el envio, tal como lo dijo el servidor.
     *
     * Sin esto la burbuja solo puede decir "fallo", y el usuario no sabe si es
     * falta de red o que lo sacaron del grupo. Son dos cosas muy distintas.
     */
    val motivoFallo: String? = null,

    // --- modulo D: adjuntos -----------------------------------------
    /** Id del adjunto en el almacen. null = mensaje de solo texto. */
    val adjuntoId: String? = null,
    val adjuntoClase: String = "",
    val adjuntoMime: String = "",
    val adjuntoNombre: String = "",
    val adjuntoBytes: Long = 0,
    val adjuntoAncho: Int = 0,
    val adjuntoAlto: Int = 0,
    val adjuntoDuracionMs: Int = 0,
    /**
     * Clave y nonce para descifrar el archivo, tal como llegaron en el sobre.
     *
     * Quedan en la base LOCAL, que va cifrada con SQLCipher. Es el unico lugar
     * donde pueden estar: sin ellas el archivo del almacen es ruido, incluso
     * para quien lo recibio.
     */
    val adjuntoClave: String = "",
    val adjuntoNonce: String = "",
    /** Miniatura JPEG en base64. Lo que se ve antes de descargar nada. */
    val adjuntoMiniatura: String = "",
    /**
     * La silueta de una nota de voz. Vacia en todo lo demas.
     *
     * Ver `com.wtfuck.protocol.Onda`. Se guarda en vez de calcularse al
     * dibujar porque calcularla exigiria decodificar el audio entero en cada
     * burbuja, y una conversacion con veinte notas haria eso veinte veces por
     * cada scroll.
     */
    val adjuntoOnda: String = "",

    // --- modulo O: la historia que este mensaje contesta ---------------
    //
    // Va COPIADA y no por referencia: una historia dura 24 horas y la respuesta
    // se queda en el chat para siempre. Con solo el id, al dia siguiente el
    // hilo quedaria contestando a nada.
    val citaHistoriaId: String = "",
    val citaHistoriaClase: String = "",
    val citaHistoriaTexto: String = "",
    val citaHistoriaMiniatura: String = "",
    /** Ruta del archivo ya descifrado en el cache, cuando esta descargado. */
    val rutaLocal: String? = null,
    /** SUBIENDO | ESPERA | DESCARGANDO | LISTO | FALLIDO */
    val adjuntoEstado: String = "",

    // --- modulo M: contenido con estructura -------------------------
    /**
     * Que clase de contenido es. Vacio = texto o adjunto, el caso normal.
     * Los valores son los de `ClaseContenido` del contrato.
     */
    val especial: String = "",
    /**
     * La carga con estructura, serializada tal como viaja en el sobre.
     *
     * Es UNA columna para todas las clases y no una por clase -latitud,
     * longitud, pregunta, opciones...- porque el contenido ya tiene una forma
     * definida en el contrato, y copiarla campo por campo a la base local
     * obligaria a una migracion por cada clase nueva. Aqui se guarda lo mismo
     * que se cifro, y la UI lo deserializa con el tipo que dice `especial`.
     */
    val especialJson: String = "",
    /**
     * Una fila que NO se muestra en el chat.
     *
     * Existe por los votos: un voto viaja como un sobre -tiene que pasar por la
     * cola, reintentarse sin red y respetar el orden, igual que un mensaje-
     * pero no es algo que nadie haya dicho en la conversacion. Antes de esto un
     * voto habria aparecido como una burbuja vacia.
     */
    val oculto: Boolean = false,
)

/**
 * M.2 · Un voto, ya contado del lado del cliente.
 *
 * ## Por que los votos son una tabla y no un campo de la encuesta
 *
 * Porque llegan sueltos y despues. La encuesta es un mensaje que ya esta
 * guardado y no se toca; los votos van cayendo, se corrigen, y con un buzon que
 * reentrega pueden llegar dos veces. Con una fila por (encuesta, votante) y
 * `REPLACE`, el voto que llega dos veces sigue contando uno y el que cambia de
 * opinion pisa el anterior sin sumar.
 *
 * Guardar el recuento dentro del mensaje obligaria a leer-modificar-escribir en
 * cada voto, que es exactamente donde se pierden votos cuando llegan dos juntos.
 */
@Entity(tableName = "voto", primaryKeys = ["consultaId", "votante"])
data class VotoEnt(
    /** Id del mensaje de la encuesta o del evento. */
    val consultaId: String,
    val votante: String,
    /** Indices elegidos, separados por coma. Vacio = retiro el voto. */
    val opciones: String,
    val creadoEn: Long,
)

/**
 * Una historia, como la guarda este telefono.
 *
 * ## Por que el contenido vive aqui y el metadato viene del servidor
 *
 * Son dos cosas que llegan por caminos distintos y no se pueden juntar del otro
 * lado: el **contenido** llega cifrado por el buzon y solo este aparato puede
 * abrirlo; el **metadato** —quien, cuando caduca, si ya la vi, cuantos la
 * vieron— lo sabe el servidor y se pide por HTTP.
 *
 * Esta tabla es donde se casan los dos, por `id`. Y explica una situacion que
 * hay que manejar y no esconder: el contenido puede llegar **antes** que el
 * metadato, o al reves. Una historia con contenido y sin metadato se guarda
 * igual y espera; una con metadato y sin contenido se dibuja como "no se pudo
 * descifrar", que es lo que de verdad pasa.
 *
 * ## Se borra sola
 *
 * `expiraEn` no es decorativo: las consultas filtran por el, y hay un borrado
 * que se lleva lo vencido. Una historia que caduco no deberia seguir ocupando
 * sitio en el telefono de nadie —es la mitad del trato de publicar algo que
 * dura un dia—.
 */
// El indice se declara AQUI ademas de crearse en la migracion: Room compara
// los dos esquemas y un indice que existe en la base pero no en la entidad
// es una diferencia como cualquier otra. Lo mismo vale para los DEFAULT.
@Entity(
    tableName = "historia",
    indices = [Index(value = ["expiraEn"], name = "historia_viva")],
)
data class HistoriaEnt(
    @PrimaryKey val id: String,
    val autor: String,
    val clase: String = "texto",
    /** El texto, cuando llego el sobre. Vacio mientras no llego. */
    val texto: String = "",
    val fondo: String = "",
    val creadaEn: Long = 0,
    val expiraEn: Long = 0,
    /** Si YA la vi. Lo dice el servidor y tambien se marca al abrirla. */
    val vista: Boolean = false,
    /** Si es mia. Cambia lo que se dibuja: las mias muestran cuantos la vieron. */
    val mia: Boolean = false,
    /** Cuantos la vieron, si es mia y se puede saber. */
    val vistas: Int = 0,
    val destinatarios: Int = 0,
    /** Si llego el sobre con el contenido. */
    val conContenido: Boolean = false,

    // --- el archivo, cuando la historia es una foto o un video ---------
    //
    // Se repiten los mismos campos que en `MensajeEnt` en vez de compartir una
    // tabla de adjuntos: son dos ciclos de vida distintos -un mensaje dura lo
    // que dure el chat, una historia 24 horas y se borra sola- y unirlos
    // obligaria a que el limpiador de historias supiera de mensajes.
    /** Id en el almacen. Vacio = historia de solo texto. */
    val adjuntoId: String = "",
    /** Base64 de la clave AES del archivo. Viaja DENTRO del sobre cifrado. */
    val adjuntoClave: String = "",
    val adjuntoNonce: String = "",
    val adjuntoMime: String = "",
    val adjuntoBytes: Long = 0,
    val adjuntoAncho: Int = 0,
    val adjuntoAlto: Int = 0,
    val adjuntoDuracionMs: Int = 0,
    /**
     * Miniatura JPEG en base64: lo que se dibuja al instante, sin pedir nada.
     *
     * Es lo que hace que una historia con foto se abra de inmediato y no con un
     * rectangulo gris mientras baja el archivo. Ver `CargaAdjunto.miniatura`.
     */
    val miniatura: String = "",
    /** Ruta del archivo YA descifrado, cuando se bajo. Vacio mientras no. */
    val rutaLocal: String = "",
    /** ESPERA · DESCARGANDO · LISTO · FALLIDO. Vacio para las de texto. */
    val adjuntoEstado: String = "",
)

/**
 * Mi libreta, copiada aqui para poder dibujar la lista sin red.
 *
 * ## Por que una copia local y no una llamada
 *
 * La lista de chats sale de esta base y se dibuja al abrir la app, antes de
 * que ninguna peticion haya vuelto. Si el nombre de contacto viniera de la
 * red, cada arranque mostraria usernames durante un segundo y luego los
 * cambiaria por nombres: un parpadeo en la primera pantalla, y en un avion,
 * usernames para siempre.
 *
 * El alias es **mio**, no de la otra persona: lo escribi yo. Esa es justo la
 * razon por la que se puede mostrar solo, sin el username al lado. Ver
 * `ChatFila.titulo`.
 */
@Entity(tableName = "contacto")
data class ContactoEnt(
    /** El username, en minusculas. Es la clave porque es lo que no cambia. */
    @PrimaryKey val username: String,
    /** Como lo llamo yo. Vacio = lo tengo agendado pero sin nombre propio. */
    val alias: String = "",
    val favorito: Boolean = false,
)

/**
 * Modulo Y.2 · Un pack de stickers.
 *
 * ## Por que hay packs y no una sola bolsa
 *
 * Porque una bolsa de sesenta stickers no se navega. Los packs son como se
 * organiza esto en cualquier app que los tenga, y no por copiarlas: es que un
 * sticker no se busca por nombre -no tiene-, se busca por **de donde salio**.
 * "Los de la oficina", "los del viaje". El pack es esa memoria.
 */
@Entity(tableName = "sticker_pack")
data class PackEnt(
    @PrimaryKey val id: String,
    val nombre: String,
    val creadoEn: Long,
)

/**
 * Un sticker propio.
 *
 * ## Por que esto es una tabla y antes eran archivos sueltos
 *
 * La primera version listaba `files/stickers/` y ya. Funcionaba para "crear y
 * mandar" y no daba para nada mas: un archivo no tiene pack, ni favorito, ni
 * emoji, ni cuando se uso por ultima vez. Todo eso son **metadatos**, y los
 * metadatos van en la base, no en el nombre del archivo.
 *
 * El archivo sigue siendo un archivo: aqui se guarda su ruta. Meter los bytes
 * en la base haria que la consulta de la bandeja arrastrase megabytes para
 * dibujar una tira de miniaturas.
 */
@Entity(
    tableName = "sticker",
    indices = [
        Index(value = ["packId"], name = "sticker_por_pack"),
        Index(value = ["usadoEn"], name = "sticker_por_uso"),
    ],
)
data class StickerEnt(
    @PrimaryKey val id: String,
    /** Vacio = suelto, sin pack. */
    val packId: String,
    val archivo: String,
    /**
     * El emoji con el que se busca.
     *
     * Un sticker no tiene nombre, asi que lo unico con lo que se puede buscar
     * es con lo que significa. Es lo que hacen todas: el emoji ES la etiqueta.
     */
    val emoji: String,
    val favorito: Boolean,
    /** `elapsedRealtime` no: aqui hace falta orden entre sesiones, asi que hora real. */
    val usadoEn: Long,
    val creadoEn: Long,
    /** Si tiene movimiento. Cambia como se dibuja y como se creo. */
    val animado: Boolean,
)

/**
 * Modulo Z.1 - Cuantas veces se uso cada emoji, y cuando.
 *
 * ## Por que esto esta en la base cifrada y no en SharedPreferences
 *
 * Porque **es informacion sobre la persona**. Los ajustes que van en prefs sin
 * cifrar -el tema, el idioma- no dicen nada de nadie. La lista de los emojis
 * que alguien usa si dice: hay banderas, hay simbolos de salud, hay cosas que
 * una persona puede no querer que se lean si le agarran el telefono.
 *
 * Es del mismo tipo de dato que el uso de stickers, que ya vive aqui. Que sea
 * mas chico no lo hace menos suyo.
 *
 * ## Por que dos columnas y no una lista ordenada
 *
 * Porque "recientes" a secas es una mala pestana. Con solo recencia, el emoji
 * que alguien manda cincuenta veces al dia se cae de la lista en cuanto prueba
 * veinticuatro distintos una tarde. Con solo frecuencia, un emoji nuevo tarda
 * semanas en subir. Se guardan las dos y se ordena por **veces y despues por
 * cuando**: lo mucho usado se queda quieto y lo nuevo igual escala.
 */
/**
 * Modulo Z.3 - Ajustes chicos que **si** dicen algo de la persona.
 *
 * ## Por que no van en SharedPreferences como los demas
 *
 * `Ajustes` guarda el tema, el idioma, la calidad de imagen: cosas que no
 * identifican a nadie, y por eso estan en prefs sin cifrar a proposito -ver la
 * nota de `Bloqueo`-. El tono de piel elegido para los emojis no es de esa
 * clase: es un dato sobre quien usa el telefono.
 *
 * Esta tabla existe para no tener que elegir entre "lo meto en prefs y aflojo
 * la regla" y "le hago una tabla propia a cada valor suelto".
 */
@Entity(tableName = "ajuste_local")
data class AjusteLocalEnt(
    @PrimaryKey val clave: String,
    val valor: String,
)

@Entity(tableName = "emoji_uso")
data class EmojiUsoEnt(
    /** El glifo tal cual se manda, **con** su tono de piel si lo tiene. */
    @PrimaryKey val emoji: String,
    val veces: Long,
    val usadoEn: Long,
)

// ============================================================
//  DAO
// ============================================================

@Dao
interface ChatDao {

    @Query(
        """SELECT c.id, c.tipo, c.nombre, c.nombreMostrado, c.participantes,
                  c.avatarUsername, c.avatarVersion, c.noLeidos,
                  m.texto AS ultimoTexto, m.creadoEn AS ultimaFecha,
                  m.esMio AS ultimoEsMio, m.autor AS ultimoAutor, m.estado AS ultimoEstado,
                  m.adjuntoClase AS ultimoAdjuntoClase,
                  m.adjuntoNombre AS ultimoAdjuntoNombre,
                  c.miRol, c.miJerarquia, c.silenciadoHasta, c.archivado, c.fijado,
                  c.marcadaNoLeida, c.soyMiembro, c.expiraEn, c.temporalesSegundos,
                  COALESCE(k.alias, '') AS aliasContacto,
                  COALESCE(ka.alias, '') AS aliasAutor
           FROM conversacion c
           LEFT JOIN mensaje m ON m.id = (
               SELECT id FROM mensaje WHERE conversacionId = c.id AND oculto = 0
               ORDER BY creadoEn DESC, id DESC LIMIT 1
           )
           -- Mi libreta, dos veces: para el titulo de una directa y para el
           -- "Fulano:" del ultimo mensaje de un grupo. Son dos personas
           -- distintas y las dos merecen salir con el nombre que yo les puse.
           LEFT JOIN contacto k ON c.tipo = 'directa' AND k.username = c.nombre
           LEFT JOIN contacto ka ON ka.username = m.autor
           WHERE c.archivado = :archivados
           ORDER BY c.fijado DESC, COALESCE(m.creadoEn, 0) DESC"""
    )
    fun conversaciones(archivados: Boolean = false): Flow<List<ChatFila>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarContactos(filas: List<ContactoEnt>)

    @Query("DELETE FROM contacto WHERE username NOT IN (:vivos)")
    suspend fun podarContactos(vivos: List<String>)

    @Query("DELETE FROM contacto")
    suspend fun borrarContactos()

    @Query("SELECT COALESCE(alias, '') FROM contacto WHERE username = :username")
    suspend fun aliasDe(username: String): String?

    @Query("SELECT * FROM contacto")
    suspend fun libreta(): List<ContactoEnt>

    // ---------------------------------------------------------- stickers

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarSticker(s: StickerEnt)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarPack(p: PackEnt)

    @Query("SELECT * FROM sticker_pack ORDER BY creadoEn")
    fun packs(): Flow<List<PackEnt>>

    @Query("SELECT * FROM sticker ORDER BY creadoEn DESC")
    fun stickers(): Flow<List<StickerEnt>>

    /**
     * Los recientes. Tope de 24 porque es una tira, no un archivo: mas alla de
     * dos pantallas de ancho nadie sigue mirando.
     */
    @Query("SELECT * FROM sticker WHERE usadoEn > 0 ORDER BY usadoEn DESC LIMIT 24")
    fun recientes(): Flow<List<StickerEnt>>

    @Query("UPDATE sticker SET usadoEn = :cuando WHERE id = :id")
    suspend fun marcarUsado(id: String, cuando: Long)

    @Query("UPDATE sticker SET favorito = :v WHERE id = :id")
    suspend fun marcarFavorito(id: String, v: Boolean)

    @Query("UPDATE sticker SET emoji = :e WHERE id = :id")
    suspend fun ponerEmoji(id: String, e: String)

    @Query("UPDATE sticker SET packId = :pack WHERE id = :id")
    suspend fun moverA(id: String, pack: String)

    @Query("DELETE FROM sticker WHERE id = :id")
    suspend fun borrarSticker(id: String)

    /**
     * Al borrar un pack, sus stickers quedan SUELTOS, no se borran.
     *
     * Borrar el pack es deshacer una agrupacion; borrar los stickers es tirar
     * el trabajo de recortarlos uno a uno. Son dos intenciones distintas y
     * juntarlas convierte un "ordenar" en una perdida.
     */
    @Query("UPDATE sticker SET packId = '' WHERE packId = :pack")
    suspend fun soltarDelPack(pack: String)

    @Query("DELETE FROM sticker_pack WHERE id = :pack")
    suspend fun borrarPack(pack: String)

    @Query("UPDATE sticker_pack SET nombre = :nombre WHERE id = :pack")
    suspend fun renombrarPack(pack: String, nombre: String)

    /**
     * Los comentarios LOCALES de una publicacion, para un canal privado.
     *
     * En un canal publico los comentarios los sirve el servidor. En uno
     * privado van cifrados y solo existen aqui, asi que esta consulta es la
     * unica fuente -y con el limite declarado de que solo hay lo que llego a
     * ESTE telefono-.
     *
     * ASC: una discusion se lee en el orden en que paso.
     */
    @Query(
        """SELECT * FROM mensaje
           WHERE conversacionId = :convId AND respondeA = :publicacionId AND oculto = 0
           ORDER BY creadoEn"""
    )
    fun comentariosLocales(convId: String, publicacionId: String): Flow<List<MensajeEnt>>

    // ------------------------------------------------------ emojis usados

    /**
     * Suma uno. En una sola sentencia y no leer-modificar-escribir: mandar dos
     * emojis rapido son dos corrutinas, y con lectura previa una pisa a la otra.
     */
    @Query(
        """INSERT INTO emoji_uso (emoji, veces, usadoEn) VALUES (:e, 1, :cuando)
           ON CONFLICT(emoji) DO UPDATE SET veces = veces + 1, usadoEn = :cuando"""
    )
    suspend fun sumarEmoji(e: String, cuando: Long)

    /**
     * Los mas usados primero, desempatando por los mas recientes.
     *
     * 32 y no 24: la rejilla tiene ocho columnas de ancho tipico, y un resto de
     * media fila se lee como si la lista estuviera cortada a la mitad.
     */
    @Query("SELECT * FROM emoji_uso ORDER BY veces DESC, usadoEn DESC LIMIT 32")
    fun emojisUsados(): Flow<List<EmojiUsoEnt>>

    @Query("DELETE FROM emoji_uso")
    suspend fun olvidarEmojis()

    @Query("SELECT valor FROM ajuste_local WHERE clave = :clave")
    fun ajusteLocal(clave: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarAjusteLocal(a: AjusteLocalEnt)

    // ================================================================
    //  Modulo AO: lo compartido en una conversacion
    // ================================================================
    //
    // Estas cuentas salen de la base LOCAL y no podrian salir de otro lado:
    // el servidor es un buzon tonto que no guarda el historial, asi que no
    // sabe —ni puede saber— cuantas fotos se mandaron en un chat. Es la misma
    // propiedad que hace que este producto sea lo que dice ser, vista desde
    // el otro lado: la funcion no existe en el servidor porque el dato
    // tampoco.

    /**
     * Cuantos adjuntos de cada clase hay en una conversacion.
     *
     * `oculto = 0` deja fuera los sobres que viajan por la cola pero no son
     * lineas del chat -votos, posiciones en vivo-. `adjuntoId IS NOT NULL`
     * deja fuera el texto.
     */
    @Query(
        """SELECT adjuntoClase AS clase, COUNT(*) AS cuantos FROM mensaje
                  WHERE conversacionId = :convId AND oculto = 0
                    AND adjuntoId IS NOT NULL AND adjuntoClase != ''
                  GROUP BY adjuntoClase"""
    )
    suspend fun recuentoAdjuntos(convId: String): List<RecuentoClase>

    /** Lo mismo para el contenido con estructura: ubicaciones, encuestas... */
    @Query(
        """SELECT especial AS clase, COUNT(*) AS cuantos FROM mensaje
                  WHERE conversacionId = :convId AND oculto = 0 AND especial != ''
                  GROUP BY especial"""
    )
    suspend fun recuentoEspeciales(convId: String): List<RecuentoClase>

    /**
     * Cuantos mensajes llevan un enlace.
     *
     * Se cuentan MENSAJES y no enlaces: un mensaje con tres direcciones es
     * una cosa que alguien mando, y contarlo tres veces haria que el numero
     * no coincidiera con las filas que se ven al abrirlo.
     *
     * El `LIKE` es tosco a proposito. La alternativa seria una columna que se
     * escribe al guardar, y eso es una migracion y un sitio mas donde
     * equivocarse para un recuento que nadie audita.
     */
    @Query(
        """SELECT COUNT(*) FROM mensaje
                  WHERE conversacionId = :convId AND oculto = 0
                    AND (texto LIKE '%http://%' OR texto LIKE '%https://%')"""
    )
    suspend fun cuantosConEnlace(convId: String): Int

    /** Los adjuntos de una clase, del mas nuevo al mas viejo. */
    @Query(
        """SELECT * FROM mensaje
                  WHERE conversacionId = :convId AND oculto = 0
                    AND adjuntoId IS NOT NULL AND adjuntoClase = :clase
                  ORDER BY creadoEn DESC"""
    )
    suspend fun adjuntosDe(convId: String, clase: String): List<MensajeEnt>

    /** Lo mismo para el contenido con estructura. */
    @Query(
        """SELECT * FROM mensaje
                  WHERE conversacionId = :convId AND oculto = 0 AND especial = :clase
                  ORDER BY creadoEn DESC"""
    )
    suspend fun especialesDe(convId: String, clase: String): List<MensajeEnt>

    /** Los mensajes con enlace, del mas nuevo al mas viejo. */
    @Query(
        """SELECT * FROM mensaje
                  WHERE conversacionId = :convId AND oculto = 0
                    AND (texto LIKE '%http://%' OR texto LIKE '%https://%')
                  ORDER BY creadoEn DESC"""
    )
    suspend fun conEnlace(convId: String): List<MensajeEnt>

    @Query("SELECT COUNT(*) FROM sticker")
    suspend fun cuantosStickers(): Int

    @Query("SELECT COUNT(*) FROM conversacion WHERE archivado = 1")
    fun cuantosArchivados(): Flow<Int>

    @Query("SELECT * FROM conversacion WHERE id = :id")
    suspend fun conversacion(id: String): ConversacionEnt?

    /**
     * La misma fila, observable.
     *
     * Emite `null` mientras la conversacion no este en la base: quien la
     * observe tiene que poder dibujar algo antes de la primera sincronizacion,
     * y esperar en silencio se veria como una pantalla vacia.
     */
    @Query("SELECT * FROM conversacion WHERE id = :id")
    fun conversacionFlow(id: String): Flow<ConversacionEnt?>

    /**
     * La directa que ya existe con alguien, o null.
     *
     * Existe para poder ABRIR un perfil sin crear la conversacion. Entrar a
     * mirar quien es alguien no es empezar a hablarle, y crear el chat al
     * mirar llenaria la lista de conversaciones vacias con gente de un grupo.
     */
    @Query("SELECT * FROM conversacion WHERE tipo = 'directa' AND nombre = :username LIMIT 1")
    suspend fun directaCon(username: String): ConversacionEnt?

    /**
     * Los chats temporales que ya vencieron, segun el reloj de ESTE telefono.
     *
     * Se hace cumplir aqui ademas de en el servidor, y no es redundancia: el
     * historial vive solo en este aparato, asi que el servidor **no puede**
     * borrarlo. Lo unico que borra alla es el rastro de que la conversacion
     * existio.
     *
     * Es la misma reparticion que en la ubicacion en vivo: la fecha viaja
     * dentro del dato y cada lado la hace cumplir con lo que tiene.
     */
    @Query("SELECT * FROM conversacion WHERE expiraEn > 0 AND expiraEn <= :ahora")
    suspend fun conversacionesVencidas(ahora: Long): List<ConversacionEnt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarConversacion(c: ConversacionEnt)

    @Query("UPDATE conversacion SET noLeidos = noLeidos + 1 WHERE id = :id")
    suspend fun sumarNoLeido(id: String)

    /**
     * Al abrir el chat se apagan las DOS cosas: el contador y la marca manual.
     * Si solo se apagara el contador, una conversacion marcada a mano quedaria
     * con el punto puesto para siempre.
     */
    @Query("UPDATE conversacion SET noLeidos = 0, marcadaNoLeida = 0 WHERE id = :id")
    suspend fun marcarLeida(id: String)

    /** M.3 · Marcar a mano. El contador se pone en cero: la marca lo reemplaza. */
    @Query("UPDATE conversacion SET marcadaNoLeida = 1, noLeidos = 0 WHERE id = :id")
    suspend fun marcarNoLeida(id: String)

    // --- modulo M: votos -------------------------------------------

    /** REPLACE: cambiar de opinion pisa el voto anterior, no suma otro. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarVoto(v: VotoEnt)

    @Query("SELECT * FROM voto WHERE consultaId = :consultaId")
    fun votos(consultaId: String): Flow<List<VotoEnt>>

    @Query("SELECT * FROM voto WHERE consultaId = :consultaId AND votante = :votante")
    suspend fun votoDe(consultaId: String, votante: String): VotoEnt?

    @Query("DELETE FROM voto")
    suspend fun borrarVotos()

    @Query("SELECT id FROM conversacion")
    suspend fun idsLocales(): List<String>

    /**
     * Los ultimos N mensajes de una conversacion. Modulo J.4.
     *
     * Del mas nuevo al mas viejo porque el LIMIT tiene que cortar por el lado
     * viejo: quien lo use lo invierte para reenviarlo en orden.
     */
    @Query(
        """SELECT * FROM mensaje WHERE conversacionId = :conv AND oculto = 0
           ORDER BY creadoEn DESC LIMIT :cuantos"""
    )
    suspend fun ultimos(conv: String, cuantos: Int): List<MensajeEnt>

    /** Ya no pertenezco: se conserva el historial pero se bloquea escribir. */
    @Query("UPDATE conversacion SET soyMiembro = 0 WHERE id = :id")
    suspend fun marcarFuera(id: String)

    @Query("SELECT * FROM mensaje WHERE conversacionId = :conv AND oculto = 0 ORDER BY creadoEn ASC")
    fun mensajes(conv: String): Flow<List<MensajeEnt>>

    // Todos los mensajes de una conversacion, de una vez (no un Flow): lo usa la
    // copia de seguridad para exportar el historial completo, no solo lo que se
    // ve en pantalla.
    @Query("SELECT * FROM mensaje WHERE conversacionId = :conv AND oculto = 0 ORDER BY creadoEn ASC")
    suspend fun todosLosMensajes(conv: String): List<MensajeEnt>

    /**
     * M.3 · Buscar dentro de una conversacion.
     *
     * Se busca en la base LOCAL porque es el unico sitio donde el texto existe
     * en claro. El servidor no puede ofrecer esto: guarda sobres opacos. Es la
     * misma razon por la que el panel de administracion no busca mensajes.
     *
     * `LIKE` y no FTS: el historial de un telefono se mide en decenas de miles
     * de filas, y una tabla FTS aparte -con su indice, sus triggers y su
     * tamano, todo dentro de la base cifrada- se paga para buscar en millones.
     *
     * Del mas nuevo al mas viejo: lo que se busca casi siempre es reciente.
     *
     * Ojo con el `ESCAPE`: esta consulta vive en un string RAW de Kotlin, donde
     * `\\` son dos caracteres y no uno. SQLite exige que el escape sea **un
     * solo caracter** y rechaza la consulta con "ESCAPE expression must be a
     * single character". Costo un cierre de la app en la primera prueba.
     */
    @Query(
        """SELECT * FROM mensaje
           WHERE conversacionId = :conv AND oculto = 0 AND retirado = 0
             AND texto LIKE '%' || :q || '%' ESCAPE '\'
           ORDER BY creadoEn DESC LIMIT 200"""
    )
    suspend fun buscarEn(conv: String, q: String): List<MensajeEnt>

    /**
     * Buscar en TODAS las conversaciones.
     *
     * Misma semantica que [buscarEn] a proposito -mismo `LIKE`, mismo
     * `ESCAPE`, mismo limite, mismo orden-: dos buscadores con reglas
     * distintas en la misma app confunden mas que uno que falta. Quien busca
     * "100%" espera lo mismo aqui que dentro de un chat.
     *
     * ## Lo unico que cambia: los mensajes de sistema quedan fuera
     *
     * Dentro de un chat, un "te agrego a este grupo" es contexto util y son
     * cuatro lineas. En global, ese texto se repite en CADA grupo, asi que
     * buscar una palabra comun devolveria la misma linea generada veinte veces
     * y enterraria lo que alguien escribio de verdad.
     *
     * ## Por que no hay indice que ayude
     *
     * `LIKE '%x%'` empieza con comodin, y ningun indice de SQLite sirve para
     * eso: es un recorrido de la tabla se ponga lo que se ponga. Con decenas
     * de miles de filas y un `LIMIT 200` sobre una accion que la persona pide
     * a mano, es aceptable; en millones haria falta FTS, que es la misma
     * cuenta que ya se hizo para la busqueda dentro del chat.
     */
    @Query(
        """SELECT m.id, m.conversacionId, m.autor, m.esMio, m.texto, m.creadoEn,
                  c.tipo, c.nombre, c.nombreMostrado, c.avatarUsername, c.avatarVersion,
                  COALESCE(k.alias, '') AS aliasContacto
           FROM mensaje m
             JOIN conversacion c ON c.id = m.conversacionId
             LEFT JOIN contacto k ON c.tipo = 'directa' AND k.username = c.nombre
           WHERE m.oculto = 0 AND m.retirado = 0 AND m.esSistema = 0
             AND m.texto LIKE '%' || :q || '%' ESCAPE '\'
           ORDER BY m.creadoEn DESC LIMIT 200"""
    )
    suspend fun buscarEnTodo(q: String): List<ResultadoBusqueda>

    /** Cuantos mensajes hay arriba de uno dado: la posicion a la que saltar. */
    @Query(
        """SELECT COUNT(*) FROM mensaje
           WHERE conversacionId = :conv AND oculto = 0
             AND (creadoEn < :creadoEn OR (creadoEn = :creadoEn AND id <= :id))"""
    )
    suspend fun posicionDe(conv: String, creadoEn: Long, id: String): Int

    /** IGNORE, no REPLACE: un mensaje que ya llego no debe pisarse al reentregarse. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun guardarMensaje(m: MensajeEnt): Long

    @Query("UPDATE mensaje SET estado = :estado WHERE id = :id")
    suspend fun estado(id: String, estado: String)

    /**
     * Mensajes ajenos de un chat que este aparato todavia no acuso como
     * leidos. L.1.
     *
     * Se filtran los de sistema: "te agregaron al grupo" no lo escribio nadie
     * a quien avisarle.
     */
    @Query(
        """SELECT id FROM mensaje
           WHERE conversacionId = :convId AND esMio = 0 AND esSistema = 0
             AND oculto = 0 AND estado <> 'LEIDO'
           ORDER BY creadoEn DESC LIMIT 200"""
    )
    suspend fun noLeidosAjenos(convId: String): List<String>

    @Query("UPDATE mensaje SET estado = 'LEIDO' WHERE id IN (:ids)")
    suspend fun marcarLeidosLocal(ids: List<String>)

    @Query("SELECT * FROM mensaje WHERE id = :id")
    suspend fun mensaje(id: String): MensajeEnt?

    @Query("UPDATE mensaje SET retirado = 1, texto = '' WHERE id = :id")
    suspend fun marcarRetirado(id: String)

    @Query("UPDATE mensaje SET editado = 1, texto = :texto WHERE id = :id")
    suspend fun marcarEditado(id: String, texto: String)

    /**
     * Reemplaza la carga de un mensaje con estructura.
     *
     * Lo usa la ubicacion en vivo: cada posicion nueva pisa la anterior en la
     * MISMA fila, en vez de crear una burbuja. Una posicion que cambia cada
     * medio minuto durante ocho horas serian mil burbujas para decir siempre
     * lo mismo, y el chat dejaria de ser un chat.
     *
     * Filtra por `especial` ademas del id: sin eso, una carga de ubicacion
     * podria caer sobre un mensaje de otra clase si dos ids coincidieran, y
     * eso deja una fila que la pantalla no sabe dibujar.
     */
    @Query("UPDATE mensaje SET especialJson = :json WHERE id = :id AND especial = :clase")
    suspend fun actualizarEspecial(id: String, clase: String, json: String): Int

    /**
     * MIS compartidos de ubicacion, del mas nuevo al mas viejo.
     *
     * No filtra por vencidos aqui: el `hasta` vive dentro del JSON y filtrarlo
     * en SQL obligaria a buscar dentro de una cadena. Son pocas filas —una
     * persona no abre cientos de compartidos— y quien llama ya tiene que
     * deserializar la carga de todos modos.
     */
    @Query(
        """SELECT * FROM mensaje
                  WHERE esMio = 1 AND especial = 'ubicacion_viva'
                  ORDER BY creadoEn DESC"""
    )
    suspend fun misCompartidosDeUbicacion(): List<MensajeEnt>

    @Query("UPDATE mensaje SET fijado = :fijado WHERE id = :id")
    suspend fun marcarFijado(id: String, fijado: Boolean)

    @Query("UPDATE mensaje SET reaccionesJson = :json WHERE id = :id")
    suspend fun guardarReacciones(id: String, json: String)

    // --- modulo D: adjuntos -----------------------------------------

    @Query("UPDATE mensaje SET adjuntoEstado = :estado WHERE id = :id")
    suspend fun estadoAdjunto(id: String, estado: String)

    /** Archivo descifrado y en disco: desde ahora se abre sin pedir nada. */
    @Query("UPDATE mensaje SET rutaLocal = :ruta, adjuntoEstado = 'LISTO' WHERE id = :id")
    suspend fun adjuntoListo(id: String, ruta: String)

    /**
     * El archivo ya esta en el almacen: se guardan el id y la llave.
     *
     * Recien ahora el mensaje puede salir de la cola, porque el sobre necesita
     * el id del adjunto y la clave para que quien reciba pueda abrirlo.
     */
    @Query(
        """UPDATE mensaje SET adjuntoId = :adjuntoId, adjuntoClave = :clave,
                  adjuntoNonce = :nonce, adjuntoEstado = 'LISTO' WHERE id = :id"""
    )
    suspend fun adjuntoSubido(id: String, adjuntoId: String, clave: String, nonce: String)

    /**
     * Subidas que quedaron a medias porque la app murio.
     *
     * Sin esto un adjunto en SUBIENDO queda fuera de la cola para siempre: no
     * se envia y tampoco se ve como fallido. Se rescatan al arrancar.
     */
    @Query("SELECT * FROM mensaje WHERE esMio = 1 AND adjuntoEstado = 'SUBIENDO'")
    suspend fun subidasInterrumpidas(): List<MensajeEnt>

    /** Todo lo que ocupa cache, para la pantalla de almacenamiento. */
    @Query("SELECT * FROM mensaje WHERE rutaLocal IS NOT NULL")
    suspend fun conArchivoLocal(): List<MensajeEnt>

    @Query("UPDATE mensaje SET rutaLocal = NULL, adjuntoEstado = 'ESPERA' WHERE id = :id")
    suspend fun olvidarArchivoLocal(id: String)

    @Query("DELETE FROM mensaje WHERE id = :id")
    suspend fun borrarMensaje(id: String)

    @Query("UPDATE mensaje SET expiraEn = :cuando WHERE id = :id")
    suspend fun fijarVencimiento(id: String, cuando: Long)

    /**
     * Cambia el temporizador de mensajes de una conversacion. `0` = permanentes.
     *
     * Un UPDATE de una columna y no un `guardarConversacion` completo: el aviso
     * trae solo el temporizador, y reescribir la fila entera con lo que hubiera
     * a mano pisaria el rol, el silencio y los no leidos con valores viejos.
     */
    @Query("UPDATE conversacion SET temporalesSegundos = :segundos WHERE id = :id")
    suspend fun fijarTemporales(id: String, segundos: Int)

    /**
     * Ventana de contexto alrededor de un mensaje, para denunciarlo.
     *
     * Del mas nuevo al mas viejo a proposito: el LIMIT tiene que cortar por el
     * lado viejo. Quien lo use lo invierte para leerlo en orden.
     *
     * Excluye los retirados: un mensaje borrado no tiene texto que entregar, y
     * mandar una linea vacia como prueba solo estorba.
     */
    @Query(
        """SELECT * FROM mensaje
           WHERE conversacionId = :conv AND retirado = 0 AND esSistema = 0
             AND oculto = 0
             AND creadoEn <= (SELECT creadoEn FROM mensaje WHERE id = :hasta)
           ORDER BY creadoEn DESC LIMIT :cuantos"""
    )
    suspend fun contexto(conv: String, hasta: String, cuantos: Int): List<MensajeEnt>

    @Query("SELECT * FROM mensaje WHERE conversacionId = :conv AND fijado = 1 ORDER BY creadoEn DESC")
    fun fijados(conv: String): Flow<List<MensajeEnt>>

    /** Barrido de mensajes temporales vencidos. */
    @Query("DELETE FROM mensaje WHERE expiraEn > 0 AND expiraEn < :ahora")
    suspend fun borrarVencidos(ahora: Long): Int

    /**
     * La cola de salida: solo lo que TODAVIA puede salir.
     *
     * Los FALLIDO quedan fuera a proposito. Antes estaban dentro y eso producia
     * dos fallas: el servidor los rechazaba en cada despacho sin parar, y el
     * contador de "pendientes" nunca bajaba. Un mensaje rechazado vuelve a la
     * cola solo si la persona toca "reintentar".
     */
    @Query(
        """SELECT * FROM mensaje WHERE esMio = 1 AND estado = 'PENDIENTE'
                  AND adjuntoEstado != 'SUBIENDO' ORDER BY creadoEn ASC"""
    )
    suspend fun cola(): List<MensajeEnt>

    @Query(
        """SELECT COUNT(*) FROM mensaje WHERE esMio = 1 AND estado = 'PENDIENTE'
                  AND adjuntoEstado != 'SUBIENDO'"""
    )
    fun tamanoCola(): Flow<Int>

    /** Los que fallaron de forma definitiva. Se cuentan aparte: no van a salir. */
    @Query("SELECT COUNT(*) FROM mensaje WHERE esMio = 1 AND estado = 'FALLIDO'")
    fun tamanoFallidos(): Flow<Int>

    /**
     * En QUE conversaciones quedaron mensajes fallidos.
     *
     * El contador solo decia cuantos. La barra de estado anunciaba "1 mensaje
     * no se envio" y no se podia tocar: para reintentarlo habia que adivinar
     * en cual de los chats estaba y abrirlos uno por uno. Un aviso que informa
     * de un problema y no deja actuar sobre el es medio aviso.
     */
    @Query(
        """SELECT DISTINCT conversacionId FROM mensaje
                  WHERE esMio = 1 AND estado = 'FALLIDO'"""
    )
    fun conversacionesConFallidos(): Flow<List<String>>

    /** El mas viejo sin enviar de una conversacion: el que hay que ir a ver. */
    @Query(
        """SELECT id FROM mensaje
                  WHERE esMio = 1 AND estado = 'FALLIDO' AND conversacionId = :conv
                  ORDER BY creadoEn ASC LIMIT 1"""
    )
    suspend fun primerFallidoDe(conv: String): String?

    @Query("UPDATE mensaje SET estado = 'FALLIDO', motivoFallo = :motivo WHERE id = :id")
    suspend fun marcarFallido(id: String, motivo: String?)

    @Query("UPDATE mensaje SET estado = 'PENDIENTE', motivoFallo = NULL WHERE id = :id")
    suspend fun devolverACola(id: String)

    @Query("DELETE FROM mensaje")
    suspend fun borrarMensajes()

    @Query("DELETE FROM mensaje WHERE conversacionId = :conv")
    suspend fun borrarMensajesDe(conv: String)

    @Query("DELETE FROM voto WHERE consultaId IN (SELECT id FROM mensaje WHERE conversacionId = :conv)")
    suspend fun borrarVotosDe(conv: String)

    @Query("DELETE FROM conversacion")
    suspend fun borrarConversaciones()

    @Query("DELETE FROM conversacion WHERE id = :id")
    suspend fun borrarConversacion(id: String)

    // ------------------------------------------------------------------
    //  Historias
    // ------------------------------------------------------------------

    /**
     * Las vivas, agrupadas por autor y en orden.
     *
     * Filtra por caducidad en la consulta, igual que el servidor: una historia
     * vencida deja de verse en el instante en que vence, sin depender de que
     * algo la haya borrado.
     */
    @Query("SELECT * FROM historia WHERE expiraEn > :ahora ORDER BY mia DESC, autor, creadaEn")
    fun historiasVivas(ahora: Long): Flow<List<HistoriaEnt>>

    @Query("SELECT * FROM historia WHERE id = :id")
    suspend fun historia(id: String): HistoriaEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarHistoria(h: HistoriaEnt)

    /**
     * Guarda el METADATO sin pisar el contenido que ya hubiera.
     *
     * Hace falta porque los dos caminos son independientes: si el sobre llego
     * primero, refrescar la lista del servidor no puede borrar el texto que ya
     * se descifro.
     */
    @Query(
        """UPDATE historia
              SET autor = :autor, clase = :clase, creadaEn = :creadaEn,
                  expiraEn = :expiraEn, vista = :vista, mia = :mia,
                  vistas = :vistas, destinatarios = :destinatarios
            WHERE id = :id"""
    )
    suspend fun actualizarMetadato(
        id: String, autor: String, clase: String, creadaEn: Long, expiraEn: Long,
        vista: Boolean, mia: Boolean, vistas: Int, destinatarios: Int,
    ): Int

    @Query("UPDATE historia SET vista = 1 WHERE id = :id")
    suspend fun marcarHistoriaVista(id: String)

    @Query("UPDATE historia SET adjuntoEstado = :estado WHERE id = :id")
    suspend fun estadoArchivoHistoria(id: String, estado: String)

    @Query("UPDATE historia SET rutaLocal = :ruta, adjuntoEstado = 'LISTO' WHERE id = :id")
    suspend fun archivoDeHistoriaListo(id: String, ruta: String)

    @Query("DELETE FROM historia WHERE id = :id")
    suspend fun borrarHistoria(id: String)

    /**
     * Lo vencido se va. Publicar algo que dura un dia incluye que se borre.
     *
     * El `expiraEn > 0` no es decoracion: una historia cuyo **contenido llego
     * antes que el metadato** tiene `expiraEn = 0` mientras espera, y sin esta
     * condicion el limpiador la daba por vencida y la borraba —justo la fila
     * que traia el texto descifrado—. Despues el metadato la recreaba vacia, y
     * la pantalla decia "no se pudo descifrar" sobre algo que si se habia
     * descifrado.
     */
    @Query("DELETE FROM historia WHERE expiraEn > 0 AND expiraEn < :ahora")
    suspend fun limpiarHistorias(ahora: Long)
}

@Database(
    entities = [
        ConversacionEnt::class,
        MensajeEnt::class,
        // Modulo E: estado criptografico. Va en la MISMA base cifrada que el
        // historial a proposito: una sola clave de SQLCipher que proteger, no
        // dos, y las claves privadas de Signal son lo mas sensible que hay aqui.
        IdentidadPropiaEnt::class,
        IdentidadRemotaEnt::class,
        SesionSignalEnt::class,
        PreKeyLocalEnt::class,
        PreKeyFirmadaLocalEnt::class,
        PreKeyKyberLocalEnt::class,
        ClaveBaseVistaEnt::class,
        ClaveEmisorEnt::class,
        DistribucionGrupoEnt::class,
        VotoEnt::class,
        HistoriaEnt::class,
        ContactoEnt::class,
        StickerEnt::class,
        PackEnt::class,
        EmojiUsoEnt::class,
        AjusteLocalEnt::class,
    ],
    version = 21,
    exportSchema = false,
)
abstract class BaseLocal : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun signalDao(): SignalDao

    companion object {
        /**
         * Base cifrada con SQLCipher. La frase de paso es aleatoria y vive
         * envuelta por una clave del Keystore: no esta en el codigo ni en claro.
         */
        fun crear(ctx: Context): BaseLocal {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(ClaveBase.obtener(ctx))
            return Room.databaseBuilder(ctx, BaseLocal::class.java, "wtfuck.db")
                .openHelperFactory(factory)
                .addMigrations(
                    DE_9_A_10, DE_10_A_11, DE_11_A_12, DE_12_A_13, DE_13_A_14, DE_14_A_15,
                    DE_15_A_16, DE_16_A_17, DE_17_A_18, DE_18_A_19, DE_19_A_20,
                    DE_20_A_21,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        /**
         * Modulo M. La primera migracion escrita a mano, y hay una razon para
         * que exista en vez de dejar que Room borre y vuelva a crear.
         *
         * **Esta base es la unica copia del historial.** El servidor no guarda
         * mensajes: los borra al confirmarse la entrega. Asi que
         * `fallbackToDestructiveMigration` -que venia de la epoca en que no
         * habia nada que perder- significa, en una app instalada, que la
         * proxima version se lleva puestas todas las conversaciones de la
         * persona sin avisar.
         *
         * El fallback se deja como ultimo recurso para un salto de version que
         * nadie escribio, pero de aqui en adelante cada cambio de esquema
         * necesita su migracion. Si esto se rompe, se rompe con una excepcion
         * en desarrollo, que es mucho mejor que borrarle el historial a alguien.
         */
        private val DE_9_A_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mensaje ADD COLUMN especial TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mensaje ADD COLUMN especialJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mensaje ADD COLUMN oculto INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversacion ADD COLUMN marcadaNoLeida INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS voto (
                           consultaId TEXT NOT NULL,
                           votante TEXT NOT NULL,
                           opciones TEXT NOT NULL,
                           creadoEn INTEGER NOT NULL,
                           PRIMARY KEY (consultaId, votante)
                       )"""
                )
            }
        }

        /**
         * Modulo O. La tabla de historias.
         *
         * Migracion de verdad y no `fallbackToDestructiveMigration`, por lo
         * mismo que la anterior: el historial de esta base es la unica copia
         * que existe de las conversaciones de alguien.
         */
        private val DE_10_A_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREAR_HISTORIA)
                db.execSQL("CREATE INDEX IF NOT EXISTS historia_viva ON historia (expiraEn)")
            }
        }

        /**
         * Rehace la tabla de historias.
         *
         * ## Por que existe esta migracion y no basto con arreglar la anterior
         *
         * La 10→11 creaba la tabla **con `DEFAULT` en SQL**. Los valores por
         * defecto de `HistoriaEnt` son del constructor de Kotlin, que es otra
         * cosa: un `DEFAULT` de columna vale para cualquier `INSERT`, venga de
         * donde venga, y Room exige declararlo con `@ColumnInfo(defaultValue)`
         * si se quiere. Al no coincidir, Room aborto con
         * "Migration didn't properly handle" —ruidosamente, en desarrollo, que
         * es exactamente para lo que esta esa validacion—.
         *
         * Corregir la 10→11 arregla a quien todavia no la corrio. A quien ya la
         * corrio, no: el runner no repite una version aplicada, asi que ese
         * aparato se quedaria con la tabla mal para siempre. De ahi esta.
         *
         * Se puede **borrar y rehacer** sin pensarlo dos veces porque lo unico
         * que hay dentro son historias, que duran 24 horas y se vuelven a pedir
         * al servidor. Es la unica tabla de esta base de la que se puede decir
         * eso; con `mensaje` no habria mas remedio que copiar fila por fila.
         */
        private val DE_11_A_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS historia")
                db.execSQL(CREAR_HISTORIA)
                db.execSQL("CREATE INDEX IF NOT EXISTS historia_viva ON historia (expiraEn)")
            }
        }

        /**
         * Las columnas del archivo de una historia con foto o video.
         *
         * Se **borra y rehace** por la misma razon que la 11-12: lo unico que
         * hay dentro son historias, que duran 24 horas y se vuelven a pedir al
         * servidor. Once `ALTER TABLE ADD COLUMN` seguidos harian lo mismo con
         * mas superficie donde equivocarse, y el archivo descargado que se
         * perderia se vuelve a bajar solo al abrirla.
         *
         * Lo que NO se puede hacer asi es `mensaje`: esa tabla es la unica
         * copia del historial. Ver la nota de la 9-10.
         */
        /**
         * Las columnas de la historia citada en un mensaje.
         *
         * `ALTER TABLE ADD COLUMN` y no borrar y rehacer, al reves que con
         * `historia`: **`mensaje` es la unica copia del historial**. El
         * servidor no lo guarda, asi que una migracion destructiva aqui le
         * borraria las conversaciones a la persona sin avisar. Ver la nota de
         * la 9-10.
         *
         * El `DEFAULT ''` es obligatorio en SQLite para agregar una columna
         * NOT NULL a una tabla con filas; la entidad no declara
         * `@ColumnInfo(defaultValue)` y Room no lo exige porque solo compara
         * los defaults que la entidad si declara.
         */
        /**
         * Tabla nueva, asi que se crea y ya: no hay datos que conservar.
         *
         * Se escribe a mano igualmente en vez de dejar que Room borre la base:
         * `fallbackToDestructiveMigration` aqui significaria perder **todo el
         * historial**, que en esta app no esta en ningun servidor. Una tabla
         * nueva no puede costar eso.
         *
         * Sin `DEFAULT` en el CREATE: los valores por defecto viven en el
         * constructor de `ContactoEnt`, y declararlos tambien aqui es lo que
         * rompio la 10→11. Ver la nota de `CREAR_HISTORIA`.
         */
        /**
         * Dos tablas nuevas para los stickers propios.
         *
         * Los archivos que ya existan en `files/stickers/` **no se pierden**:
         * el repositorio los adopta la primera vez que se abre la bandeja, con
         * `Stickers.adoptarSueltos`. Borrarlos aqui seria tirar el trabajo de
         * quien ya recorto unos cuantos con la version anterior.
         *
         * Sin `DEFAULT` en el CREATE: los valores por defecto viven en el
         * constructor de las entidades. Ver la nota de `CREAR_HISTORIA`.
         */
        /**
         * La tabla de uso de emojis.
         *
         * Se crea **vacia** y no se rellena con nada: no hay de donde sacar el
         * historial de uso de quien ya venia usando la app, y poner un punado
         * de emojis "populares" de fabrica seria inventarle gustos a alguien.
         * La pestana Recientes no existe hasta que hay algo que mostrar.
         *
         * Sin `DEFAULT` en el CREATE: los valores por defecto viven en el
         * constructor de la entidad. Ver la nota de `CREAR_HISTORIA`.
         */
        /**
         * Modulo AY. La fecha en que un chat temporal se borra solo.
         *
         * A mano y no destructiva, por lo mismo de siempre: esta base es la
         * unica copia del historial. Dejar que Room la recree por una columna
         * nueva borraria todos los mensajes de todo el mundo.
         */
        /**
         * La silueta de las notas de voz.
         *
         * Las notas que ya estaban se quedan con la cadena vacia y se siguen
         * dibujando como antes, con la figura derivada del id. No se puede
         * hacer mejor: la onda real de una nota vieja solo se podria sacar
         * decodificandola, y ninguna migracion deberia abrir mil archivos de
         * audio.
         */
        /**
         * Los indices que faltaban en `mensaje`. Ver el KDoc de [MensajeEnt].
         *
         * No cambia ni una columna: solo crea indices. Es una migracion barata
         * y hace falta igual, porque los `indices` de la anotacion solo se
         * aplican al CREAR la tabla — en una base que ya existe, declararlos
         * sin migracion no crea nada y Room falla la validacion del esquema.
         *
         * `IF NOT EXISTS` porque una base recien creada por la version 21 ya
         * los trae de la anotacion, y esta migracion tambien corre en el salto
         * desde cualquier version anterior.
         */
        private val DE_20_A_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS mensaje_cola ON mensaje (esMio, estado)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS mensaje_vence ON mensaje (expiraEn)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS mensaje_adjunto_estado ON mensaje (adjuntoEstado)"
                )
            }
        }

        /**
         * El temporizador de mensajes, que antes no llegaba a este telefono.
         *
         * Por defecto `0` —permanentes— y es lo correcto para lo que ya estaba:
         * hasta ahora quien recibia no borraba nada, asi que sus mensajes son
         * permanentes de hecho. Ponerles un vencimiento retroactivo al migrar
         * seria borrarle a alguien historial que hoy tiene, sin avisar. El
         * temporizador empieza a contar para lo que llegue de aqui en adelante,
         * y el valor real entra en la primera sincronizacion.
         */
        private val DE_19_A_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversacion ADD COLUMN temporalesSegundos INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val DE_18_A_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mensaje ADD COLUMN adjuntoOnda TEXT NOT NULL DEFAULT ''")
            }
        }

        private val DE_17_A_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversacion ADD COLUMN expiraEn INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val DE_16_A_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS emoji_uso (
                           emoji TEXT NOT NULL PRIMARY KEY,
                           veces INTEGER NOT NULL,
                           usadoEn INTEGER NOT NULL
                       )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ajuste_local (
                           clave TEXT NOT NULL PRIMARY KEY,
                           valor TEXT NOT NULL
                       )"""
                )
            }
        }

        private val DE_15_A_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS sticker_pack (
                           id TEXT NOT NULL PRIMARY KEY,
                           nombre TEXT NOT NULL,
                           creadoEn INTEGER NOT NULL
                       )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS sticker (
                           id TEXT NOT NULL PRIMARY KEY,
                           packId TEXT NOT NULL,
                           archivo TEXT NOT NULL,
                           emoji TEXT NOT NULL,
                           favorito INTEGER NOT NULL,
                           usadoEn INTEGER NOT NULL,
                           creadoEn INTEGER NOT NULL,
                           animado INTEGER NOT NULL
                       )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS sticker_por_pack ON sticker (packId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS sticker_por_uso ON sticker (usadoEn)")
            }
        }

        private val DE_14_A_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS contacto (
                           username TEXT NOT NULL PRIMARY KEY,
                           alias TEXT NOT NULL,
                           favorito INTEGER NOT NULL
                       )"""
                )
            }
        }

        private val DE_13_A_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mensaje ADD COLUMN citaHistoriaId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mensaje ADD COLUMN citaHistoriaClase TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mensaje ADD COLUMN citaHistoriaTexto TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mensaje ADD COLUMN citaHistoriaMiniatura TEXT NOT NULL DEFAULT ''")
            }
        }

        private val DE_12_A_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS historia")
                db.execSQL(CREAR_HISTORIA)
                db.execSQL("CREATE INDEX IF NOT EXISTS historia_viva ON historia (expiraEn)")
            }
        }

        /**
         * Sin `DEFAULT`: los valores por defecto viven en el constructor de
         * `HistoriaEnt`, y declararlos tambien aqui es lo que rompio la 10→11.
         */
        private const val CREAR_HISTORIA =
            """CREATE TABLE IF NOT EXISTS historia (
                   id TEXT NOT NULL PRIMARY KEY,
                   autor TEXT NOT NULL,
                   clase TEXT NOT NULL,
                   texto TEXT NOT NULL,
                   fondo TEXT NOT NULL,
                   creadaEn INTEGER NOT NULL,
                   expiraEn INTEGER NOT NULL,
                   vista INTEGER NOT NULL,
                   mia INTEGER NOT NULL,
                   vistas INTEGER NOT NULL,
                   destinatarios INTEGER NOT NULL,
                   conContenido INTEGER NOT NULL,
                   adjuntoId TEXT NOT NULL,
                   adjuntoClave TEXT NOT NULL,
                   adjuntoNonce TEXT NOT NULL,
                   adjuntoMime TEXT NOT NULL,
                   adjuntoBytes INTEGER NOT NULL,
                   adjuntoAncho INTEGER NOT NULL,
                   adjuntoAlto INTEGER NOT NULL,
                   adjuntoDuracionMs INTEGER NOT NULL,
                   miniatura TEXT NOT NULL,
                   rutaLocal TEXT NOT NULL,
                   adjuntoEstado TEXT NOT NULL
               )"""
    }
}

/**
 * Frase de paso de SQLCipher.
 *
 * Se genera una vez al azar y se guarda CIFRADA con una clave AES del Keystore.
 * Quien saque el archivo .db del telefono no puede abrirlo, y quien saque las
 * preferencias tampoco: la clave que las envuelve no es exportable.
 */
private object ClaveBase {

    private const val PREFS = "wtfuck_seguro"
    private const val CAMPO = "frase"

    private val caja = CajaFuerte("wtfuck_db_v1")

    /**
     * La frase, creandola la primera vez.
     *
     * ## Que pasa si la clave del Keystore ya no abre
     *
     * Antes: una excepcion, en cada arranque, para siempre. La version
     * anterior descifraba sin red de seguridad, y una clave del Keystore SE
     * PUEDE invalidar —cambio de credenciales del aparato en algunos
     * fabricantes, una restauracion, una actualizacion que rota el almacen—.
     * Entonces lo guardado es ruido y la app no abre la base, que es lo
     * primero que hace: un fallo en cada arranque sin ninguna salida salvo
     * desinstalar.
     *
     * Ahora se empieza de nuevo: se tira la clave rota, se genera otra frase y
     * la base vieja queda ilegible — porque lo es de verdad, no porque se
     * decida ignorarla.
     *
     * **Se pierde el historial local, y eso es lo correcto.** El historial
     * solo vive aqui, asi que no hay de donde recuperarlo; lo unico que estaba
     * en juego era si la app vuelve a arrancar. Y no es una puerta trasera:
     * quien tenga el archivo `.db` sigue sin poder leerlo, porque la frase que
     * lo abria se fue con la clave.
     */
    fun obtener(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(CAMPO, null)?.let { guardado ->
            runCatching { caja.abrir(guardado) }.getOrNull()?.let { return it }
            // No abre: la clave ya no sirve. Se tira para poder crear otra.
            caja.tirar()
        }
        val frase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(CAMPO, caja.cerrar(frase)).apply()
        return frase
    }
}
