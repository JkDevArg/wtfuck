package com.wtfuck.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contrato HTTP y WebSocket. Compilado por el servidor y por la app.
 *
 * Los ByteArray viajan en JSON como Base64 estandar (ver [Base64Util]).
 */

const val RUTA_REGISTRO = "/v1/registro"
const val RUTA_SESION = "/v1/sesion"
const val RUTA_USUARIO = "/v1/usuarios"
const val RUTA_CONVERSACIONES = "/v1/conversaciones"
const val RUTA_DIRECTA = "/v1/conversaciones/directa"
const val RUTA_GRUPOS = "/v1/conversaciones/grupo"
const val RUTA_PREKEYS = "/v1/prekeys"
const val RUTA_WS = "/v1/ws"
const val RUTA_PERFIL = "/v1/perfil"
const val RUTA_PRIVACIDAD = "/v1/perfil/privacidad"

// ============================================================
//  Autenticacion
// ============================================================

@Serializable
data class RegistroReq(
    val username: String,
    val password: String,
    val etiquetaDispositivo: String,
    /** Base64 de la clave publica de identidad del dispositivo. */
    val identidadPub: String,
    /** Base64 de SHA-256(clave_atestada || SSAID). Ver docs/04-DEVICE-BINDING.md */
    val hardwareHash: String,
    /** STRONGBOX | TEE | SOFTWARE_DEV */
    val hardwareNivel: String,
)

@Serializable
data class SesionReq(
    val username: String,
    val password: String,
    val hardwareHash: String,
    /**
     * Segundo factor, si la cuenta lo tiene activado.
     *
     * Acepta el codigo de la app de autenticacion o uno de respaldo. Va aqui y
     * no en una segunda peticion a proposito: un flujo de dos pasos necesitaria
     * un token intermedio -"ya puse la contrasena, falta el codigo"- y ese
     * token es una credencial mas que proteger. Mandando las dos cosas juntas
     * no hay estado intermedio que robar.
     */
    val totp: String? = null,
)

@Serializable
data class SesionResp(
    val token: String,
    val usuarioId: String,
    val dispositivoId: String,
    val username: String,
)

/** Perfil publico. Lo necesario para abrir una conversacion y cifrar hacia el. */
@Serializable
data class UsuarioPublico(
    val usuarioId: String,
    val username: String,
    val dispositivoId: String,
    val identidadPub: String,
    /**
     * Ultima vez que estuvo activo, o 0 si no se puede mostrar.
     *
     * El 0 tapa dos casos distintos a proposito -nunca se conecto, o no deja
     * verlo- y la app no puede distinguirlos. Si pudiera, "oculta su ultima
     * conexion" seria en si mismo un dato deducible, que es media filtracion.
     */
    val ultimaVez: Long = 0,
    /** Si esta conectado AHORA. Mismo filtro que [ultimaVez]. */
    val enLinea: Boolean = false,
    /** Nombre para mostrar. Si esta vacio, la UI cae al username. */
    val nombreMostrado: String = "",
    val estadoTexto: String = "",
    /**
     * Marca de tiempo del avatar, o 0 si no tiene.
     * Sirve de cache-buster: la URL del avatar lleva ?v=<esto>, asi que al
     * cambiar la foto cambia la URL y no hay que invalidar cache a mano.
     */
    val avatarVersion: Long = 0,
    val portadaVersion: Long = 0,
    /**
     * La biografia, o vacia si su duena no deja verla.
     *
     * Vacia y no null, como [nombreMostrado]: la app ya sabe dibujar una
     * biografia vacia —la de quien no puso ninguna— y no hace falta un caso
     * nuevo. Distinguir "no tiene" de "no te deja verla" seria, ademas, un
     * dato deducible sobre sus ajustes.
     */
    val biografia: String = "",
)

/** Aceptar o rechazar una solicitud de mensaje. */
@Serializable
data class DecidirSolicitudReq(val aceptar: Boolean)

/** Lo que el usuario puede editar de si mismo. */
@Serializable
data class PerfilReq(
    val nombreMostrado: String,
    val estadoTexto: String,
)

/**
 * Quien puede ver cada cosa.
 *
 * "conocidos" = quien ya tiene una conversacion directa abierta contigo. Es el
 * equivalente a "mis contactos" de WhatsApp, pero sin agenda del telefono.
 *
 * Nada de esto se aplica solo en la UI: el servidor lo verifica en cada
 * peticion. Ocultar un boton no es una regla de privacidad.
 */
@Serializable
data class Privacidad(
    /** todos | conocidos | nadie */
    val foto: String = TODOS,
    /** todos | conocidos | nadie */
    val estado: String = TODOS,
    /** todos | conocidos — 'nadie' no existe aqui a proposito. */
    val escribe: String = TODOS,
    /** todos | conocidos | nadie */
    val grupos: String = TODOS,
    /**
     * Quien puede llamarme. Modulo K.6.
     *
     * Por defecto **conocidos**, al contrario que el resto de los ajustes. Una
     * llamada no es un mensaje: suena, interrumpe y despierta. Que cualquiera
     * pueda hacer sonar tu telefono de madrugada es un vector de acoso que un
     * mensaje no tiene, asi que aqui el valor seguro es el defecto.
     */
    val llamadas: String = CONOCIDOS,

    /**
     * Quien ve mi ultima conexion y si estoy en linea. L.1.
     *
     * Un solo ajuste para las dos cosas: separarlos da la combinacion absurda
     * de "no ves cuando estuve, pero si que estoy ahora", que no protege nada.
     *
     * Y es **reciproco**: quien lo apaga tampoco ve la de los demas. Sin esa
     * regla el ajuste seria un espejo de una sola direccion -ver sin ser
     * visto-, que es precisamente para lo que se usaria.
     */
    val ultimaVez: String = CONOCIDOS,

    /**
     * Quien ve mi nombre mostrado.
     *
     * El username NO se puede ocultar: es la direccion con la que existis en
     * la plataforma. Lo que se oculta es el nombre de verdad.
     */
    val nombre: String = TODOS,

    /**
     * Quien me encuentra buscando mi username.
     *
     * `nadie` no vuelve a nadie inalcanzable: quien ya tiene conversacion
     * sigue escribiendo, y un enlace de invitacion sigue funcionando. Lo que
     * deja de funcionar es que un desconocido te encuentre tecleando.
     */
    val busqueda: String = TODOS,

    /**
     * Si mando confirmaciones de lectura.
     *
     * Booleano y no de tres niveles porque es reciproco: apagarlo deja de
     * mandarlos y deja de mostrarlos.
     */
    val lectura: Boolean = true,

    /**
     * Quien ve mis historias. Modulo O.
     *
     * Columna propia y no reutilizar [estado]: son dos cosas distintas. La
     * frase del perfil la ve cualquiera que abra tu ficha; una historia es
     * contenido que se publica, y mucha gente quiere el perfil abierto y las
     * historias cerradas. Mezclarlas obligaria a elegir.
     *
     * Por defecto **conocidos**, al contrario que casi todo lo demas de aqui.
     * Una historia es contenido, no un dato del perfil: que aparezca ante
     * cualquiera que tenga tu username es mas de lo que alguien espera al
     * publicar por primera vez, y el defecto de un ajuste de privacidad se
     * elige pensando en quien no lo va a tocar.
     */
    val historias: String = CONOCIDOS,

    /**
     * Si aviso cuando estoy escribiendo.
     *
     * Reciproco tambien, y por el mismo motivo. Va aparte de [lectura] porque
     * son dos cosas distintas: una dice que ya leiste -y eso crea la
     * expectativa de que contestes-, la otra que estas contestando ahora.
     */
    val escribiendo: Boolean = true,

    /**
     * Quien ve mi biografia. §3 del brief.
     *
     * Iba pegada a [estado] y son dos cosas. El estado es una frase que cambia
     * cada semana ("de viaje"); la biografia dice quien sos y suele llevar
     * donde trabajas. Quien la escribio para sus contactos no la escribio para
     * cualquiera que le busque el usuario.
     */
    val biografia: String = TODOS,

    /**
     * Quien puede hacerme videollamadas. §3 del brief.
     *
     * Se aplica **ademas** de [llamadas], no en su lugar: si el audio ya esta
     * cerrado, el video tambien. Separarlos tiene sentido en la otra
     * direccion, que es la que la gente quiere: aceptar la voz de alguien no
     * es aceptar que te vea la cara ni lo que tenes detras, y esa diferencia
     * importa justo con quien menos confianza hay.
     *
     * Nace en `conocidos` como [llamadas], y por lo mismo: quien no toca nunca
     * los ajustes es justo quien mas necesita que el defecto sea el seguro.
     */
    val videollamadas: String = CONOCIDOS,

    /**
     * Si aviso cuando estoy grabando una nota de voz. §3 del brief.
     *
     * Hoy grabar emitia el mismo aviso que teclear, y son dos cosas: "esta
     * escribiendo" dice que hay algo en camino; "esta grabando" dice ademas
     * que tiene el microfono abierto ahora mismo. Hay gente a la que no le
     * importa lo primero y no quiere anunciar lo segundo.
     *
     * Booleano y no nivel, igual que [escribiendo]: es un aviso que se manda o
     * no; un "solo a mis contactos" no significa nada cuando el aviso solo
     * viaja dentro de una conversacion que ya existe.
     */
    val grabando: Boolean = true,

    /**
     * Si acepto solicitudes de quien no puede escribirme. §3 del brief.
     *
     * Con [escribe] en `conocidos`, un desconocido recibe un 403 y se acabo.
     * Eso protege, y tambien deja fuera a quien tenia algo legitimo que decir.
     *
     * Con esto encendido puede mandar **una solicitud**: la conversacion nace
     * marcada, vive aparte de la bandeja normal, y quien la recibe decide.
     * Aceptar la vuelve un chat cualquiera; rechazar la borra.
     *
     * No tiene efecto si [escribe] es `todos`: ahi no hay a quien dejar fuera.
     *
     * ## Por que nace APAGADO, al contrario que el resto
     *
     * Porque encenderlo por defecto **cambiaria el significado de un ajuste que
     * ya existia**. Quien puso [escribe] en `conocidos` lo puso para que no le
     * escriban desconocidos, y con esto encendido de fabrica le empezarian a
     * entrar solicitudes sin haber tocado nada. Un ajuste de privacidad no se
     * relaja en una actualizacion.
     *
     * Se vio al correr las pruebas: siete afirmaciones que llevaban meses en
     * verde —"un desconocido no puede abrir conversacion con ella"— se
     * pusieron en rojo. No eran pruebas viejas: eran el contrato anterior
     * avisando de que lo estaba rompiendo.
     */
    val solicitudes: Boolean = false,
) {
    companion object {
        const val TODOS = "todos"
        const val CONOCIDOS = "conocidos"
        const val NADIE = "nadie"

        /**
         * L.1 · "Todos menos estas personas", o "solo estas personas".
         *
         * No es un valor como los otros tres: es un puntero a una lista. Por
         * eso existe [ExcepcionesPrivacidad] y por eso este nivel no cabe en
         * los ajustes booleanos ni en `escribe`.
         *
         * Con la lista vacia se comporta como `nadie` en modo `solo`: si un
         * fallo dejara las excepciones sin leer, el resultado es no mostrar el
         * dato, nunca mostrarselo a todos.
         */
        const val PERSONALIZADO = "personalizado"

        val NIVELES = listOf(TODOS, CONOCIDOS, NADIE, PERSONALIZADO)
        val NIVELES_ESCRIBE = listOf(TODOS, CONOCIDOS)

        /**
         * Los ajustes que admiten lista de excepciones.
         *
         * `escribe` NO esta, a proposito: una lista blanca de "quien me puede
         * escribir" convierte la cuenta en un club cerrado, y para eso ya
         * estan los bloqueos. `lectura` y `escribiendo` tampoco: son
         * booleanos reciprocos, y una lista de "a quien si le aviso" es el
         * espejo de una sola direccion que la reciprocidad evita.
         */
        val PERSONALIZABLES = listOf(
            "foto", "estado", "nombre", "grupos", "llamadas", "busqueda", "ultima_vez",
            // Las historias son de los ajustes donde la lista se usa mas: lo
            // habitual no es "todos" ni "nadie" sino "todos menos tres".
            "historias",
            // Los dos nuevos del §3 que son niveles. `grabando` y
            // `solicitudes` NO estan: "todos menos Fulano" no significa nada
            // sobre un interruptor de si/no.
            "biografia", "videollamadas",
        )

        /** "todos menos la lista". */
        const val MODO_SALVO = "salvo"

        /** "solo la lista". */
        const val MODO_SOLO = "solo"

        fun etiqueta(nivel: String) = when (nivel) {
            TODOS -> "Todos"
            PERSONALIZADO -> "Personalizado"
            CONOCIDOS -> "Solo con quien ya hablo"
            else -> "Nadie"
        }
    }
}

// ============================================================
//  Conversaciones
// ============================================================

@Serializable
data class DirectaReq(val usernameDestino: String)

@Serializable
data class GrupoReq(val nombre: String, val usernames: List<String>)

@Serializable
data class ConversacionResumen(
    val id: String,
    val tipo: String,              // "directa" | "grupo" | "canal"
    val nombre: String,            // username del otro, o nombre del grupo
    val participantes: List<UsuarioPublico>,
    /** Mi rol aqui. La UI lo usa para saber que mostrar, pero NO para autorizar. */
    val miRol: String = "miembro",
    val miJerarquia: Int = 10,
    /** Preferencias personales. -1 = silenciado para siempre, null = sin silencio. */
    val silenciadoHasta: Long? = null,
    val archivado: Boolean = false,
    val fijado: Boolean = false,
    /**
     * Si esta conversacion todavia es una solicitud sin decidir.
     *
     * Quien la recibio la ve aparte de la bandeja normal y decide. Quien la
     * mando la ve marcada: es lo honesto, porque hasta que la acepten sus
     * mensajes no llegan como los de un chat cualquiera.
     */
    val esSolicitud: Boolean = false,
)

@Serializable
data class MiembrosReq(val usernames: List<String>)

// ============================================================
//  WebSocket
// ============================================================

/** Cliente -> servidor. */
@Serializable
sealed interface Subida {

    /**
     * Entrega un sobre, con UNA COPIA POR DISPOSITIVO destino.
     *
     * Aqui se ve el precio real del cifrado de extremo a extremo. Antes el
     * servidor recibia un cuerpo y lo copiaba a N buzones. Ahora no puede:
     * cada copia esta cifrada para un dispositivo distinto y el servidor no
     * tiene con que producir las otras. Solo enruta.
     *
     * El remitente pide la lista de destinos antes de cifrar. Si entre ese
     * momento y el envio entra alguien al grupo, faltara su copia: el servidor
     * lo dice en [Bajada.Aceptado.sinCopia] y el cliente completa. No se
     * inventa una entrega que no puede hacer.
     */
    @Serializable
    @SerialName("enviar")
    data class Enviar(
        val sobreId: String,
        val conversacionId: String,
        val creadoEn: Long,
        val copias: List<CopiaCifrada>,
    ) : Subida

    /** Confirma recepcion: el servidor BORRA el sobre del buzon. */
    @Serializable
    @SerialName("acuse")
    data class Acuse(val sobreIds: List<String>) : Subida

    /** Confirma eventos del sistema; el servidor los borra. */
    @Serializable
    @SerialName("acuse_evento")
    data class AcuseEvento(val eventoIds: List<String>) : Subida

    /**
     * L.1 · Confirma que se LEYERON esos mensajes.
     *
     * Es un mensaje distinto de [Acuse] y no un campo suyo, porque son dos
     * hechos distintos que ocurren en momentos distintos:
     *
     *  - El acuse de ENTREGA borra el sobre del buzon. Ocurre cuando el
     *    mensaje llega al aparato, sin que nadie lo haya visto. Es lo que hace
     *    que el servidor no acumule historial.
     *  - El acuse de LECTURA ocurre cuando la persona abre el chat, que puede
     *    ser horas despues, o nunca.
     *
     * Juntarlos habria significado una de dos cosas malas: o el sobre se borra
     * cuando se lee -y un mensaje no leido se queda en el servidor para
     * siempre-, o "leido" aparece en cuanto llega al telefono, que es mentir.
     */
    @Serializable
    @SerialName("acuse_lectura")
    data class AcuseLectura(val conversacionId: String, val mensajeIds: List<String>) : Subida

    /**
     * L.1 · "Estoy escribiendo".
     *
     * ## Por que no se guarda en ninguna parte
     *
     * Es la senal mas efimera del sistema: vale tres segundos y despues es
     * mentira. Guardarla seria acumular filas que caducan antes de que alguien
     * las lea, y encima serian filas que dicen a que hora estaba tecleando
     * cada persona, que es exactamente el tipo de metadato que este proyecto
     * evita generar. El servidor la reenvia y la olvida.
     *
     * ## Por que el ajuste de privacidad lo aplican los CLIENTES
     *
     * Al contrario que las confirmaciones de lectura -que se guardan, y por
     * eso su regla vive en el servidor-, aqui cada extremo aplica **su propio**
     * ajuste: quien lo tiene apagado no manda la senal, y quien lo tiene
     * apagado no la muestra. Poner la comprobacion en el servidor obligaria a
     * leer la privacidad de la base en cada rafaga de tecleo para decidir algo
     * que no se persiste; y lo unico que un cliente modificado conseguiria
     * saltandose la regla es dar SU propia informacion, que ya era suya.
     */
    @Serializable
    @SerialName("escribiendo")
    data class Escribiendo(
        val conversacionId: String,
        /**
         * Si lo que esta pasando es grabar una nota de voz, no teclear.
         *
         * Un campo y no un tipo nuevo porque es la misma senal con dos
         * variantes: efimera, sin fila en la base, con el mismo limitador y el
         * mismo reenvio. Lo unico que cambia es que la decide otro ajuste
         * —ver `Privacidad.grabando`— y que la pantalla escribe otra frase.
         */
        val grabando: Boolean = false,
    ) : Subida

    @Serializable
    @SerialName("ping")
    data object Ping : Subida
}

/** Servidor -> cliente. */
@Serializable
sealed interface Bajada {

    /** Un sobre dirigido a este dispositivo. */
    @Serializable
    @SerialName("entrega")
    data class Entrega(
        /**
         * Id de la FILA DEL BUZON. Es lo que se acusa, y es distinto para cada
         * destino: con cifrado punta a punta cada uno recibe bytes propios y
         * necesita su propia fila.
         */
        val sobreId: String,
        /**
         * Id del MENSAJE: el que genero quien lo escribio, y el mismo para
         * todos los que lo reciben.
         *
         * Son dos cosas y hubo que separarlas. El id del buzon se deriva por
         * destino (`base + i`), asi que usarlo como identidad del mensaje hacia
         * que en un grupo de tres el mismo mensaje quedara guardado con un id
         * distinto en cada telefono. Eso rompia todo lo que apunta a un mensaje
         * por su id: los votos de una encuesta, el acuse de entrega -que
         * volvia al emisor con un id que no tenia-, responder y reaccionar.
         *
         * En una conversacion directa los dos valores coinciden, porque hay un
         * solo destino y `derivar(base, 0) == base`. Por eso el defecto no se
         * veia en chats de dos.
         *
         * Vacio por compatibilidad con sobres encolados antes de V26: el
         * cliente cae en `sobreId`, que es el comportamiento anterior.
         */
        val mensajeId: String = "",
        val conversacionId: String,
        val origenUsuarioId: String,
        val origenUsername: String,
        /**
         * Dispositivo que lo envio.
         *
         * Hace falta para descifrar: una sesion de Signal es entre DOS
         * dispositivos, no entre dos personas. Sin esto no se sabe con que
         * sesion abrir el sobre.
         */
        val origenDispositivo: String,
        val creadoEn: Long,
        val cuerpo: String,
        /** Tipo de mensaje de Signal. Ver [TipoCifrado]. */
        val tipo: Int = TipoCifrado.PLANO,
    ) : Bajada

    /** El servidor acepto el envio. Equivale a la palomita gris. */
    @Serializable
    @SerialName("aceptado")
    data class Aceptado(
        val sobreId: String,
        /**
         * Dispositivos que debian recibir copia y no venia ninguna.
         *
         * Pasa cuando alguien entro a la conversacion despues de que el
         * remitente pidio la lista de destinos. El cliente vuelve a pedirla y
         * manda las copias que faltan; el `sobreId` derivado hace que el
         * reenvio no duplique nada.
         */
        val sinCopia: List<String> = emptyList(),
    ) : Bajada

    /**
     * Alguien leyo un mensaje mio. Palomita doble en cian.
     *
     * Llega por el socket y no como evento persistente: si el remitente no
     * esta conectado, al volver pide el estado con la conversacion. Guardar un
     * evento por lectura seria una fila por mensaje y por persona que solo
     * sirve para pintar un color.
     */
    @Serializable
    @SerialName("leido")
    data class Leido(
        val conversacionId: String,
        val mensajeIds: List<String>,
        val porQuien: String,
    ) : Bajada

    /**
     * Alguien esta escribiendo en una conversacion.
     *
     * No trae "dejo de escribir", y es deliberado: un aviso de fin se puede
     * perder -la app se cierra, la red se corta- y entonces el indicador se
     * queda encendido para siempre. El receptor lo apaga solo despues de unos
     * segundos sin recibir otro; asi el peor caso es que se apague tarde, no
     * que se quede mintiendo.
     */
    @Serializable
    @SerialName("escribiendo")
    data class Escribiendo(
        val conversacionId: String,
        val username: String,
        /** Si esta grabando una nota de voz en vez de teclear. */
        val grabando: Boolean = false,
    ) : Bajada

    /** El servidor entrego el sobre al destinatario. Palomita doble. */
    @Serializable
    @SerialName("entregado")
    data class Entregado(val sobreId: String) : Bajada

    @Serializable
    @SerialName("error")
    data class ErrorMsg(val sobreId: String?, val motivo: String) : Bajada

    /**
     * Aviso generado por el SERVIDOR, no por otro usuario.
     *
     * Va en claro a proposito: son metadatos que el servidor ya conoce (quien
     * esta en que grupo). No puede ir cifrado extremo a extremo porque el
     * servidor no tiene las claves para fabricarlo.
     */
    @Serializable
    @SerialName("evento")
    data class Evento(
        val eventoId: String,
        /** agregado_grupo | sacado_grupo | grupo_renombrado */
        val tipo: String,
        val conversacionId: String,
        val nombreConversacion: String,
        /** Quien hizo la accion. */
        val actor: String,
        val creadoEn: Long,
        val detalle: String? = null,
    ) : Bajada

    @Serializable
    @SerialName("pong")
    data object Pong : Bajada
}

@Serializable
data class ErrorResp(val motivo: String)

/** Base64 sin dependencias externas, disponible en JVM y Android (API 26+). */
object Base64Util {
    fun enc(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun dec(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}

/** L.1: ids de mis mensajes que ya fueron leidos en una conversacion. */
@Serializable
data class MensajesLeidos(val mensajeIds: List<String> = emptyList())

/**
 * L.1 · Las excepciones de un ajuste personalizado.
 *
 * `modo` dice como leer la lista: `salvo` es una lista negra -todos menos
 * estos- y `solo` una lista blanca. Las dos formas existen porque son las dos
 * maneras en que la gente piensa la privacidad, y con una sola la otra se
 * vuelve absurda: una lista negra no puede expresar "solo mi familia" sin
 * enumerar la plataforma entera.
 */
@Serializable
data class ExcepcionesPrivacidad(
    val ajuste: String,
    val modo: String = Privacidad.MODO_SALVO,
    val usernames: List<String> = emptyList(),
)

@Serializable
data class TodasLasExcepciones(val ajustes: List<ExcepcionesPrivacidad> = emptyList())
