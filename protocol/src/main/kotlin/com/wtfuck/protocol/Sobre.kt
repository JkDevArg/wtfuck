package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * EL CONTRATO.
 *
 * Este archivo lo compilan el servidor y la app. Si cambias un campo, ambos
 * lados dejan de compilar en el mismo build. Esa es exactamente la intencion.
 *
 * Regla que sostiene todo el diseno: un Sobre NO sabe por donde viaja.
 * Puede salir por WebSocket, por malla Bluetooth, o quedarse esperando en
 * disco. El despachador decide. Por eso `msg off` (fase 7) se agrega
 * registrando un transporte mas, sin tocar nada de esto.
 */

// ============================================================
//  Cifrado del cuerpo
// ============================================================

/**
 * Tipos de cuerpo cifrado.
 *
 * Los tres primeros son los de libsignal y sus numeros son los suyos, no
 * inventados aqui: el cliente los recibe de `CiphertextMessage.getType()` y los
 * usa para decidir con que clase deserializar. PLANO es el de la fase de
 * depuracion, sin cifrado.
 */
object TipoCifrado {
    /** Sin cifrar. Solo mientras se depura el transporte. */
    const val PLANO = 0

    /** SignalMessage: sesion ya establecida (Double Ratchet). */
    const val SESION = 2

    /** PreKeySignalMessage: el primer mensaje, que abre la sesion. */
    const val PREPARADO = 3

    /** SenderKeyMessage: mensaje de grupo con clave de emisor. */
    const val GRUPO = 7
}

/**
 * Un cuerpo cifrado y los dispositivos a los que sirve.
 *
 * Normalmente `destinos` tiene UNO: con sesiones por pares, cada dispositivo
 * recibe bytes distintos y no hay forma de compartirlos.
 *
 * Con clave de emisor (Sender Keys, modulo E.4) la lista tiene MUCHOS: el
 * mensaje de grupo se cifra una sola vez y esos mismos bytes valen para todo
 * el que ya tenga la clave. Ahi esta el ahorro real: un grupo de veinte pasa
 * de subir veinte cuerpos a subir uno.
 *
 * El `tipo` viaja junto al cuerpo porque quien recibe necesita saber con que
 * clase de mensaje de Signal se trata antes de intentar abrirlo, y el servidor
 * no puede decirselo: para el son bytes opacos.
 */
@Serializable
data class CopiaCifrada(
    val destinos: List<String>,
    /** Base64 del cuerpo cifrado. */
    val cuerpo: String,
    val tipo: Int = TipoCifrado.PLANO,
) {
    companion object {
        /** Atajo para el caso de un solo destino, que es el mas comun. */
        fun para(dispositivoId: String, cuerpo: String, tipo: Int) =
            CopiaCifrada(listOf(dispositivoId), cuerpo, tipo)
    }
}

// ============================================================
//  El sobre
// ============================================================

@Serializable
data class Sobre(
    /** UUIDv7 generado por el CLIENTE. Ordenado por tiempo e idempotente:
     *  si un reintento llega dos veces, el servidor descarta el duplicado. */
    val id: String,

    val conversacionId: String,
    val origenDispositivo: String,

    /** Milisegundos epoch segun el remitente. Es una pista, no una verdad:
     *  el reloj del emisor no es confiable.
     *
     *  Aqui decia que "el orden real lo da `id`", y no era cierto: `id` es un
     *  UUIDv7 que genera el MISMO cliente, con el MISMO reloj. Un telefono con
     *  la hora atrasada enterraba sus respuestas entre los mensajes viejos.
     *
     *  Es la hora de AUTORIA y tiene que seguir siendolo: es lo que permite
     *  que un mensaje escrito sin red se muestre de cuando se escribio (msg
     *  off, V8). Por eso no la reemplaza la hora del servidor. Lo que se hace:
     *  el cliente la sella ya CORREGIDA con su desfase contra el servidor
     *  (`Reloj` en la app), y el servidor recorta lo que venga del futuro. */
    val creadoEn: Long,

    /**
     * El contenido, OPACO, con una copia por dispositivo destino.
     *
     * Era un solo `cuerpo` mientras no habia cifrado. Con E2EE tiene que ser
     * una lista: cada copia esta cifrada para un dispositivo distinto y no hay
     * forma de derivar una de otra. El servidor no interpreta ninguna.
     *
     * Que el sobre lleve las copias -y no el transporte- es lo que mantiene la
     * regla de que un sobre no sabe por donde viaja: la malla de la fase 7
     * recibira exactamente esto.
     */
    val copias: List<CopiaCifrada>,
)

/**
 * La historia que un mensaje contesta.
 *
 * ## Va COPIADA, y eso importa
 *
 * Una historia dura 24 horas; la respuesta se queda en el chat para siempre.
 * Si la cita fuera solo un id, a las 24 horas el hilo quedaria contestando a
 * nada. Por eso viaja el trozo de texto y la miniatura, igual que con la cita
 * de un mensaje.
 *
 * ## Lo que NO garantiza
 *
 * El servidor no ve esto —va cifrado— y por tanto no lo valida: quien responde
 * puede **inventarse** la cita. No es grave, porque quien la recibe es el autor
 * de la historia y sabe cuales son las suyas, pero de ahi sale una regla del
 * cliente: antes de dibujar la cita se comprueba que esa historia exista y sea
 * propia; si no, se dibuja el mensaje a secas. Ver `ContenidoSeguro`.
 */
@Serializable
data class CitaHistoria(
    val historiaId: String,
    val clase: String,
    /** Un trozo del texto, o el pie si era foto. */
    val previa: String = "",
    /** Miniatura JPEG en base64, si la historia era foto o video. */
    val miniatura: String = "",
)

/**
 * Lo que va DENTRO del cuerpo, una vez descifrado.
 * Solo los clientes conocen este tipo. El servidor nunca lo deserializa.
 */
@Serializable
sealed interface Carga {

    @Serializable
    data class Texto(
        val cuerpo: String,
        /** Id del mensaje citado. */
        val respondeA: String? = null,
        /**
         * Copia del texto citado. Viaja junto a proposito: el receptor puede no
         * tener el mensaje original (entro despues al grupo, o lo borro).
         */
        val respondeTexto: String? = null,
        val respondeAutor: String? = null,
        /** Si es un reenvio, de quien venia. */
        val reenviadoDe: String? = null,
        /**
         * La historia que este mensaje contesta, si contesta una.
         *
         * Responder a una historia **es** mandar un mensaje directo: no hay
         * ruta nueva ni buzon nuevo. Lo unico propio es esta cita, y por eso
         * viaja dentro del sobre como el resto del contenido.
         */
        val historia: CitaHistoria? = null,
        /**
         * "Enviar sin sonido", como en Telegram: llega y se notifica, pero el
         * telefono de quien lo recibe no suena ni vibra. Va DENTRO del sobre:
         * el servidor no sabe ni eso de un mensaje. Un cliente viejo lo
         * ignora y suena, que es lo que hacia siempre.
         */
        val silencioso: Boolean = false,
        /**
         * La vista previa del primer enlace, armada por quien ENVIA. Va dentro
         * del sobre: quien recibe no visita el sitio, y el servidor no sabe
         * que enlace se mando. Ver `VistaPreviaHtml` en la app.
         */
        val previa: VistaPreviaEnlace? = null,
        /**
         * Modo cerca, fase 1: la clave de baliza de quien escribe, en base64.
         * Va solo en chats directos y solo hasta que ese chat tiene la clave
         * vigente. Un cliente viejo no la conoce y la ignora: por eso viaja
         * aqui y no en un tipo de carga nuevo, que un cliente viejo mostraria
         * como "(no se pudo descifrar)". Ver `MiBaliza`.
         */
        val baliza: String? = null,
    ) : Carga

    /**
     * Texto nuevo de un mensaje ya enviado.
     *
     * El contenido editado va por aqui, dentro del sobre, porque es contenido.
     * La AUTORIZACION para editar se pide aparte por HTTP: el servidor tiene
     * que poder verificarla sin leer esto.
     */
    @Serializable
    data class Edicion(val mensajeId: String, val textoNuevo: String) : Carga

    /** Fase 6. El archivo vive cifrado en S3/MinIO; aqui viaja solo como
     *  llegar a el y como abrirlo. El binario nunca pasa por Postgres. */
    @Serializable
    data class Media(
        val url: String,
        val claveDescifrado: ByteArray,
        val mime: String,
        val bytes: Long,
    ) : Carga

    /** Fase 5. Altas, bajas y cambios de rol en un grupo. */
    @Serializable
    data class EventoGrupo(
        val accion: String,   // "agrego" | "salio" | "renombro" | "promovio"
        val actorId: String,
        val objetivoId: String? = null,
        val valor: String? = null,
    ) : Carga

    /** Acuse de recibo o de lectura. */
    @Serializable
    data class Acuse(val sobreId: String, val tipo: TipoAcuse) : Carga

    /**
     * Un mensaje de grupo con la clave de emisor pegada (modulo E.4).
     *
     * Para poder abrir los mensajes de grupo de alguien hay que tener antes su
     * clave de emisor, y esa clave solo puede viajar cifrada por pares. La
     * forma obvia seria mandar dos sobres -primero la clave, despues el
     * mensaje- pero entonces habria que garantizar que llegan en ese orden, y
     * con un buzon que reentrega eso no se puede garantizar.
     *
     * Asi que van juntos en UN sobre: quien recibe procesa la clave y despues
     * abre `interior`. No hay orden que respetar porque no hay dos cosas.
     */
    @Serializable
    data class ConClaveGrupo(
        /** Base64 del SenderKeyDistributionMessage. */
        val distribucion: String,
        val interior: Carga,
    ) : Carga

    /**
     * Modulo J.4. Un trozo de historial que otro dispositivo MIO me reenvia.
     *
     * ## Por que es un tipo de carga y no un mensaje normal
     *
     * Porque el receptor tiene que tratarlo distinto en tres cosas, y ninguna
     * se puede deducir mirando un `Texto`:
     *
     *  1. **No se notifica.** Son mensajes viejos. Un aparato recien vinculado
     *     que suena cuarenta veces al terminar de sincronizar es un defecto.
     *  2. **No cuenta como no leido.** Ya los leiste, en el otro aparato.
     *  3. **Conserva el autor y la fecha originales.** Un mensaje reenviado
     *     por mi telefono no es mio ni es de ahora; si se guardara con mi
     *     nombre y la hora del reenvio, el historial quedaria falsificado.
     *
     * Por eso cada entrada lleva su propio autor y su propia fecha, y no se
     * toman del sobre que las transporta.
     */
    @Serializable
    data class Historial(
        val conversacionId: String,
        val mensajes: List<MensajeHistorico> = emptyList(),
        /** Si quedan mas lotes en camino. Sirve para mostrar el progreso. */
        val hayMas: Boolean = false,
    ) : Carga

    // ============================================================
    //  Modulo K: señalizacion de llamadas
    // ============================================================
    //
    // ## Por que la señalizacion va POR AQUI y no por una ruta HTTP
    //
    // Porque el SDP contiene la **huella del certificado DTLS** de cada lado,
    // y esa huella es lo unico que hace que el cifrado del medio signifique
    // algo. El medio va cifrado con DTLS-SRTP de fabrica, pero si el servidor
    // pudiera cambiar las huellas montaria dos llamadas -una con cada lado- y
    // escucharia todo, con cada tramo perfectamente cifrado *contra el
    // servidor*.
    //
    // Metiendo el SDP en un sobre cifrado, el servidor mueve bytes opacos y no
    // puede cambiar una huella que no puede leer. Es lo que hace Signal.
    //
    // El precio es que la señalizacion hereda el buzon: si el otro esta
    // desconectado, la oferta lo espera. Para una llamada eso da igual -no se
    // puede llamar a quien no esta-, y el sobre vence solo cuando la llamada
    // se marca sin respuesta.

    /**
     * La oferta: "te estoy llamando, y este es mi SDP".
     *
     * Su llegada ES el timbre. No hace falta un aviso aparte para que suene,
     * porque este sobre llega por el mismo socket y en el mismo instante.
     */
    @Serializable
    data class LlamadaOferta(
        val llamadaId: String,
        val sdp: String,
        val conVideo: Boolean = false,
    ) : Carga

    @Serializable
    data class LlamadaRespuesta(
        val llamadaId: String,
        val sdp: String,
    ) : Carga

    /**
     * Un candidato ICE.
     *
     * Van de a uno y a medida que aparecen (*trickle ICE*) en vez de esperar a
     * tenerlos todos: juntarlos ahorraria sobres y agregaria un segundo o dos
     * al tiempo hasta que se oye la voz, que es justo lo que se nota.
     */
    @Serializable
    data class LlamadaCandidato(
        val llamadaId: String,
        val candidato: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
    ) : Carga

    /**
     * Modulo AV · Alguien empezo o dejo de presentar su pantalla.
     *
     * ## Por que hace falta avisar
     *
     * Los fotogramas llegan por la MISMA pista de video que la camara —el modo
     * cine cambia el capturador, no la pista, para no tener que renegociar— y
     * desde fuera son indistinguibles. Sin este aviso, del otro lado una
     * pantalla compartida llegaria como una cara con una forma rara: recortada
     * al cuadro de la rejilla, sin decir de quien es ni que es.
     *
     * Con el aviso, quien recibe sabe que tiene que dibujarla ENTERA —una
     * pantalla recortada pierde justo los bordes, que es donde estan los
     * controles— y ponerle nombre.
     *
     * No lleva nada mas. Ni que app es, ni que se esta mirando: eso es
     * asunto de quien presenta, y el otro lado ya lo va a ver.
     */
    @Serializable
    data class LlamadaPantalla(
        val llamadaId: String,
        val activo: Boolean,
    ) : Carga

    /**
     * Fin de la llamada.
     *
     * Viaja cifrado como todo lo demas **y ademas** se avisa por HTTP. No es
     * duplicar por gusto: el sobre le dice al otro telefono que corte ya, y la
     * ruta HTTP es la que deja el registro en el historial. Si solo fuera el
     * sobre, una llamada cortada sin red quedaria "en curso" para siempre.
     */
    @Serializable
    data class LlamadaFin(
        val llamadaId: String,
        val motivo: String,
    ) : Carga

    // ============================================================
    //  Modulo M: contenido con estructura
    // ============================================================
    //
    // Hasta aqui todo el contenido era texto, un archivo, o senalizacion. Lo
    // que sigue son cargas que el receptor tiene que *entender*, no solo
    // mostrar: una ubicacion se abre en un mapa, un contacto abre un chat, una
    // encuesta se cuenta.
    //
    // Todas viajan cifradas como cualquier otra carga. El servidor no sabe que
    // una de ellas es una encuesta salvo por lo que el cliente declare en
    // [RegistrarMensajeReq.clase], y eso se declara SOLO para poder autorizar.

    /**
     * Un punto en el mapa.
     *
     * No lleva nombre de calle ni direccion: eso exigiria preguntarselo a un
     * servicio de geocodificacion, es decir, contarle a un tercero donde esta
     * la persona justo cuando esta compartiendo donde esta. Van las coordenadas
     * y la etiqueta que haya escrito quien la manda, y el telefono que recibe
     * decide con que app abrirla.
     *
     * `precisionM` es el radio en metros que reporto el GPS. Se muestra porque
     * "estoy aqui, con 2 km de margen" y "estoy aqui, con 5 m" son dos mensajes
     * distintos y el segundo no deberia parecerse al primero.
     */
    @Serializable
    data class Ubicacion(
        val lat: Double,
        val lon: Double,
        val precisionM: Int = 0,
        /** Lo que escribio quien la comparte. Nunca viene de un tercero. */
        val etiqueta: String = "",
        /**
         * Si quien la manda acepta que se dibuje un mapa de este punto.
         *
         * Lo mismo que en [UbicacionEnVivo.conMapa] y por lo mismo: la
         * posicion que llegaria al servidor de baldosas es la de quien
         * comparte, asi que el permiso es suyo. Con la diferencia de que aqui
         * es UN momento y no hasta 24 horas de recorrido, que es exactamente
         * por que aqui el modo oculto no dibuja nada en lugar de dibujar un
         * rastro: un punto solo sin fondo no es informacion, es un circulo.
         */
        val conMapa: Boolean = false,
    ) : Carga

    /**
     * Modulo AM · Una ubicacion que se sigue moviendo.
     *
     * ## Que la diferencia de [Ubicacion]
     *
     * Una ubicacion normal dice "estuve aqui a esta hora" y no cambia nunca.
     * Esta dice "estoy aqui AHORA" y se corrige sola hasta que vence. Son dos
     * cosas distintas y por eso son dos cargas distintas: mezclarlas obligaria
     * a mirar un campo para saber cual de las dos se esta leyendo.
     *
     * ## `hasta` viaja DENTRO del sobre, y eso es lo importante
     *
     * El servidor no puede caducar esto: no ve las coordenadas ni la fecha,
     * porque va cifrado como cualquier mensaje. Asi que el vencimiento viaja
     * en la carga y **lo hacen cumplir los dos lados por su cuenta**:
     *
     *  - quien comparte deja de mandar cuando llega la hora;
     *  - quien recibe deja de mostrarla en vivo aunque no llegue ningun aviso.
     *
     * Ese "aunque no llegue" es el punto. Si el telefono que comparte se queda
     * sin bateria, nadie manda el final, y sin esta fecha la otra pantalla se
     * quedaria mostrando para siempre una posicion de hace horas como si fuera
     * de ahora. Es el mismo razonamiento que los mensajes temporales del
     * modulo C: el que caduca es el dato, no el aviso de que caduco.
     *
     * ## `secuencia`
     *
     * Los sobres pueden llegar desordenados —se reintentan, y la cola no
     * garantiza orden entre reintentos—. Una actualizacion mas vieja que la
     * que ya se tiene se descarta: sin esto, un reintento tardio movería el
     * punto hacia atras en el tiempo.
     */
    @Serializable
    data class UbicacionEnVivo(
        /**
         * El mensaje que abrio el compartido.
         *
         * Es el id del MENSAJE y no uno propio, por la misma razon que en
         * [Edicion]: en esta app el id lo genera quien manda y los dos lados
         * guardan el mismo, asi que una actualizacion puede encontrar su
         * burbuja con un `WHERE id = ?` y sin buscar dentro de un JSON.
         */
        val mensajeId: String,
        val lat: Double,
        val lon: Double,
        val precisionM: Int = 0,
        /** Cuando deja de valer, en epoch ms. */
        val hasta: Long,
        /** Va subiendo. Una actualizacion con secuencia menor se descarta. */
        val secuencia: Int = 0,
        /**
         * Cuando se supo esta posicion, **con el reloj de quien la guarda**.
         *
         * ## No viaja
         *
         * Sale siempre en 0 y lo escribe quien recibe, al guardar. Esta en la
         * carga y no en una columna nueva porque `especialJson` ES el formato
         * de almacenamiento local —esa fue la decision del modulo M, para no
         * pagar una migracion por cada clase de contenido— y esto es un dato
         * de almacenamiento.
         *
         * ## Por que no lo pone quien manda
         *
         * Porque seria su reloj. Un telefono con la hora mal puesta haria que
         * la burbuja dijera "actualizado hace 3 h" de algo que acaba de
         * llegar, o peor, "hace -2 h". El unico reloj en el que se puede
         * confiar para decir "hace cuanto" es el de quien lo esta leyendo.
         *
         * `hasta` si viaja, y es del otro reloj — pero un vencimiento
         * desplazado unos minutos no se nota, y "hace cuanto" se nota entero.
         */
        val recibidaEn: Long = 0,
        /**
         * Si quien comparte acepta que se dibuje un MAPA de verdad.
         *
         * ## Por que lo decide quien comparte
         *
         * Un mapa incrustado se arma pidiendole las baldosas a un tercero, y
         * pedir la baldosa de un lugar le cuenta a ese tercero que alguien
         * esta mirando ESE lugar. La posicion que se filtra no es la de quien
         * mira: es la de quien comparte. Por eso el permiso es suyo, viaja en
         * la carga, y del otro lado no hay forma de saltearlo.
         *
         * En `false` la burbuja dibuja la estela sin fondo: un recorrido sin
         * mapa no le pide nada a nadie. Ver [ModoUbicacion].
         *
         * ## Por que un booleano y no un enum
         *
         * Porque los dos lados pueden tener versiones distintas de la app, y
         * kotlinx falla al decodificar un valor de enum que no conoce. Un
         * booleano no tiene valores desconocidos, y el default `false` hace
         * que una carga vieja —o de una version que no sabe de esto— caiga
         * sola en el modo que no filtra nada.
         */
        val conMapa: Boolean = false,
        /**
         * Por donde paso, **con lo que vio este telefono**.
         *
         * ## No viaja
         *
         * Igual que [recibidaEn], y por una razon mas: son hasta
         * [ESTELA_MAX] puntos, y mandarlos en CADA actualizacion —una cada
         * medio minuto, durante hasta 24 horas— seria repetir toda la
         * historia dentro de cada sobre para redibujar algo que del otro lado
         * ya esta. Cada aparato la arma de lo que le fue llegando.
         *
         * Eso tiene una consecuencia honesta: quien entra tarde, o a quien le
         * mataron la app un rato, ve una estela mas corta. Es lo correcto —
         * la estela dice "esto vi moverse", y afirmar mas seria inventar.
         *
         * El ultimo punto es el mas nuevo, y se recorta por el principio.
         */
        val estela: List<PuntoEstela> = emptyList(),
    ) : Carga

    /**
     * Se dejo de compartir antes de tiempo.
     *
     * No es imprescindible —la fecha de [UbicacionEnVivo.hasta] ya caduca sola—
     * pero sin esto, quien deja de compartir a los dos minutos de un compartido
     * de ocho horas seguiria apareciendo "en vivo" en la otra pantalla durante
     * casi ocho horas, con una posicion congelada. El aviso es la diferencia
     * entre "termino" y "dejo de actualizarse y no sabemos por que".
     */
    @Serializable
    data class UbicacionEnVivoFin(val mensajeId: String) : Carga

    /**
     * Una tarjeta de contacto, y solo de gente de esta plataforma.
     *
     * No se comparte la agenda del telefono: mandar el numero de un tercero es
     * entregar el dato personal de alguien que no esta en la conversacion y no
     * dio permiso. Lo que viaja es un `username` de aqui, que es publico dentro
     * de la app y con el que el receptor puede -o no- abrir un chat, sujeto a
     * la privacidad de esa persona, que se comprueba al abrirlo y no ahora.
     */
    /**
     * Lo que queda en el chat cuando termina una llamada.
     *
     * ## NUNCA viaja
     *
     * Esta es la diferencia con todas las demas cargas, y es deliberada: cada
     * telefono escribe la suya cuando la llamada termina de su lado. No se
     * manda ningun sobre.
     *
     * Tres razones, en orden de peso:
     *
     *  1. **Los dos lados ya saben todo lo que hace falta.** La duracion, si
     *     hubo video y quien llamo estan en el estado local de la llamada.
     *     Mandar un sobre para contar algo que el otro ya sabe es trabajo y
     *     superficie por nada.
     *  2. **Quien no contesto no puede recibir un sobre a tiempo.** Una
     *     llamada perdida tiene que aparecer en el chat de quien estaba sin
     *     señal, y ese es justo el caso en que un sobre no llega.
     *  3. **Nadie puede inventar tu historial de llamadas.** Por eso
     *     `ClaseContenido.LLAMADA` NO esta en `VALIDAS`: si alguien la metiera
     *     en un sobre, el servidor lo rechaza con un 400. Un registro de
     *     llamadas que se puede falsificar desde afuera vale menos que ninguno.
     *
     * El costo, dicho: las duraciones de los dos lados pueden diferir en uno o
     * dos segundos, porque cada uno cuenta desde que conecto lo suyo. Es lo
     * que hacen las apps que uno usa todos los dias, y nadie lo nota.
     */
    @Serializable
    data class ResumenLlamada(
        val conVideo: Boolean,
        /** La inicie yo. Decide la flecha y de que lado va la burbuja. */
        val saliente: Boolean,
        /** El de `EstadoLlamada.motivoFin`. Vacio si termino normal. */
        val motivoFin: String = "",
        /** Cuanto duro CONECTADA. Cero es "nunca llego a conectar". */
        val segundos: Long = 0,
    ) : Carga

    @Serializable
    data class Contacto(
        val username: String,
        /** Nombre tal como lo veia quien comparte. Es una pista, no la verdad. */
        val nombre: String = "",
    ) : Carga

    /**
     * Una encuesta.
     *
     * ## Quien cuenta los votos
     *
     * Los clientes. No hay otra opcion: el servidor no puede leer los votos, y
     * un recuento que el no puede hacer no puede pedirsele. Cada aparato recibe
     * los votos de todos y suma. Consecuencia directa y declarada: **una
     * encuesta E2EE no puede ser anonima**, porque el voto llega dentro de un
     * sobre firmado por la sesion de quien vota. Por eso esta app no ofrece la
     * opcion "encuesta anonima": seria mentir en una etiqueta.
     *
     * ## Por que no hay "cerrar la encuesta" del lado del servidor
     *
     * `cierraEn` es una fecha que cada cliente respeta al pintar y al votar.
     * No es un candado: quien modifique su cliente puede mandar un voto tarde,
     * y los demas lo van a ver llegar tarde y descartarlo. Un candado de verdad
     * exigiria que el servidor supiera que ese sobre es un voto y de que
     * encuesta, que es exactamente lo que no queremos que sepa.
     */
    @Serializable
    data class Encuesta(
        val pregunta: String,
        val opciones: List<String>,
        /** Si se puede marcar mas de una. */
        val multiple: Boolean = false,
        /** Epoch ms. 0 = no cierra. */
        val cierraEn: Long = 0,
    ) : Carga

    /**
     * Un evento con confirmacion de asistencia.
     *
     * Es una encuesta con las opciones puestas -voy / no voy / quizas- mas la
     * fecha y el lugar. Comparte la maquinaria de votos a proposito: son el
     * mismo problema, y dos implementaciones del recuento serian dos sitios
     * donde el numero puede salir distinto.
     */
    @Serializable
    data class Evento(
        val titulo: String,
        /** Epoch ms de cuando empieza. */
        val cuandoMs: Long,
        val lugar: String = "",
        val nota: String = "",
    ) : Carga

    /**
     * Un voto sobre una encuesta o un evento.
     *
     * Reemplaza por completo el voto anterior de esa persona -no se acumula- y
     * por eso lleva la lista entera de lo que eligio y no "sume uno a la opcion
     * 2": con un buzon que reentrega, un incremento que llega dos veces cuenta
     * dos veces, y una lista que llega dos veces sigue diciendo lo mismo.
     *
     * Una lista vacia es como se retira un voto.
     */
    /**
     * El contenido de una historia. Modulo O.
     *
     * Viaja por el buzon como cualquier otra carga, pero **no es un mensaje**:
     * no va a ninguna conversacion, no cuenta como no leido y no se dibuja en
     * ningun chat. El cliente la reconoce por el tipo, la guarda en su tabla de
     * historias y sale.
     *
     * El `historiaId` es el mismo que el servidor conoce, asi que el cliente
     * puede casar el contenido que descifra con el metadato que pidio por HTTP
     * —quien la publico, cuando caduca, si ya la vio—.
     */
    @Serializable
    data class Historia(
        val historiaId: String,
        val clase: String,
        /** El texto, o el pie si algun dia lleva imagen. */
        val texto: String = "",
        /**
         * Color de fondo para una historia de texto, como `#RRGGBB`.
         *
         * Lo elige quien publica. Va aqui y no se deriva del texto porque es
         * una decision de quien escribe, igual que en cualquier app que tenga
         * esto.
         */
        val fondo: String = "",
        /**
         * El archivo, cuando la historia es una foto o un video.
         *
         * Se reutiliza [CargaAdjunto] entera en vez de inventar campos nuevos:
         * trae la clave, el nonce y la miniatura, que es exactamente lo que
         * hace falta, y de paso el cliente puede bajar y descifrar una historia
         * con el mismo codigo que ya usa para un adjunto de chat.
         *
         * La miniatura viaja DENTRO del sobre, asi que una historia con foto se
         * ve al instante y el archivo completo se baja solo al abrirla. En una
         * fila de anillos eso es la diferencia entre que la pantalla responda o
         * no.
         */
        val adjunto: CargaAdjunto? = null,
    ) : Carga

    @Serializable
    data class Voto(
        /** Id del mensaje de la encuesta o del evento. */
        val consultaId: String,
        /** Indices de las opciones elegidas. */
        val opciones: List<Int> = emptyList(),
    ) : Carga
}

/**
 * Lo que un cliente le DECLARA al servidor sobre el contenido de un sobre.
 *
 * ## Por que existe este agujero en el buzon tonto, y por que es chico
 *
 * El brief pide permisos independientes para "crear encuestas" y "crear
 * eventos". Un permiso que el servidor no puede comprobar no es un permiso: es
 * un boton escondido. Y el servidor no puede comprobarlo mirando el sobre,
 * porque el sobre esta cifrado.
 *
 * La salida es la misma que ya usan los adjuntos: el cliente **declara la
 * clase** en el metadato en claro -donde ya viajan el autor, la conversacion y
 * las menciones- y el servidor autoriza contra eso. No es informacion nueva de
 * naturaleza distinta: el servidor ya sabia que se envio un sobre a esa
 * conversacion; ahora sabe que decia ser una encuesta.
 *
 * ## El limite, dicho claro
 *
 * Un cliente modificado puede declarar `TEXTO` y mandar una encuesta igual. Eso
 * no se puede evitar con E2EE y no se pretende: la garantia que da esto es la
 * misma que da `mensaje.enviar`, ni mas ni menos. Quien puede escribir texto
 * siempre pudo escribir "1) si 2) no" a mano.
 */
/**
 * Topes de lo que se acepta DIBUJAR de un sobre ajeno.
 *
 * ## Por que el unico sitio posible es al pintar
 *
 * Quien crea una encuesta desde esta app no puede pasar de doce opciones, pero
 * eso lo comprueba el cliente que la CREA, y el cliente que la RECIBE no puede
 * dar por sentado que del otro lado habia esta app. El sobre viaja cifrado, asi
 * que el servidor tampoco puede mirarlo — es el buzon tonto funcionando como
 * debe, y el precio es que la validacion de contenido **solo** puede estar en
 * el que recibe.
 *
 * Sin tope, una encuesta fabricada a mano con dos mil opciones —entran de sobra
 * en los 64 KB que admite un sobre— se dibuja entera dentro de una sola fila de
 * la lista y deja la pantalla clavada.
 *
 * ## Por que estan TODOS los campos y no solo las opciones
 *
 * La primera version de esto puso techo a las opciones de la encuesta y a nada
 * mas. El razonamiento de arriba no tiene nada de particular de las opciones:
 * vale igual para la etiqueta de una ubicacion, el titulo de un evento o el
 * nombre de un contacto, y en los 64 KB de un sobre entra un titulo de sesenta
 * mil caracteres igual de bien que dos mil opciones.
 *
 * Una regla aplicada a un campo y olvidada en los otros cinco es el patron que
 * ya aparecio en el servidor con la visibilidad de los canales. Por eso aqui
 * estan los seis.
 *
 * ## Lo que NO lleva tope, y por que
 *
 * El **cuerpo de un mensaje**. Un mensaje es texto libre: eso es el producto,
 * no un descuido. Recortarlo seria la app decidiendo cuanto puede escribir una
 * persona.
 */
object TopesConsulta {
    /** Lo maximo que se ofrece al crear, y lo maximo que se pinta al recibir. */
    const val OPCIONES = 12

    /** Caracteres de una opcion. Mas que esto no se lee, se estorba. */
    const val LARGO_OPCION = 100

    /** La pregunta de una encuesta. */
    const val LARGO_PREGUNTA = 300

    /** Titulo de un evento. */
    const val LARGO_TITULO = 150

    /** Lugar de un evento, y etiqueta de una ubicacion: son la misma cosa. */
    const val LARGO_LUGAR = 200

    /** La nota de un evento, que es el unico campo largo de los seis. */
    const val LARGO_NOTA = 600

    /** El nombre que acompana a un contacto compartido. */
    const val LARGO_NOMBRE = 60
}

/**
 * La forma de un username, en un solo sitio.
 *
 * Vive en `protocol` y no en el servidor porque hacen falta los DOS lados:
 *
 *  - El servidor la usa al registrar, para decidir que nombres existen.
 *  - El cliente la usa al **dibujar un contacto que le mando otra persona**,
 *    que es un caso distinto y menos obvio.
 *
 * Ese segundo caso es el que la trajo aqui. Una tarjeta de contacto pinta
 * `@` + lo que venga en el sobre, y lo que venga en el sobre lo eligio quien lo
 * mando: con `username = "soporte · Administrador"` la tarjeta se lee como una
 * cuenta oficial que no existe. El `@` es una afirmacion sobre identidad, y no
 * se puede poner delante de un texto arbitrario.
 *
 * Con la regla en los dos lados, el cliente puede comprobar antes de pintar si
 * eso que le mandaron **podria** ser una cuenta. Que exista es otra cosa y la
 * resuelve el servidor cuando se toca la tarjeta.
 */
val FORMA_USERNAME = Regex("^[a-z0-9_]{3,24}$")

/**
 * Lo que se ve de un enlace: titulo, descripcion, sitio y una miniatura.
 *
 * `imagen` es un JPEG chico en base64: la miniatura viaja en el sobre para que
 * quien recibe no tenga que pedirle nada a nadie. Con tope, porque un sobre no
 * es un archivo.
 */
@Serializable
data class VistaPreviaEnlace(
    val url: String,
    val titulo: String = "",
    val descripcion: String = "",
    val sitio: String = "",
    val imagen: String = "",
)

/**
 * La mencion a todo el grupo: `@todos`.
 *
 * Es una palabra de la app, no una cuenta: por eso nadie puede registrarse
 * con ese usuario (`USUARIOS_RESERVADOS`). Si pudiera, `@todos` en un grupo
 * donde esta esa persona seria ambiguo.
 */
const val MENCION_TODOS = "todos"

/** Usuarios que no se pueden registrar: son palabras de la app. */
val USUARIOS_RESERVADOS = setOf(MENCION_TODOS)

object ClaseContenido {
    const val TEXTO = ""
    const val UBICACION = "ubicacion"

    /**
     * Modulo AM. Dos clases y no una, por la misma razon que las cargas: el
     * final no lleva coordenadas y la actualizacion no es un final.
     */
    const val UBICACION_VIVA = "ubicacion_viva"
    const val UBICACION_VIVA_FIN = "ubicacion_viva_fin"

    const val CONTACTO = "contacto"
    const val ENCUESTA = "encuesta"

    /**
     * El rastro de una llamada terminada.
     *
     * Fuera de [VALIDAS] a proposito: se escribe SOLO en el telefono y no
     * viaja. Ver `Carga.ResumenLlamada`. Si alguien la metiera en un sobre, el
     * servidor devuelve 400 — que es lo que impide que a nadie le inventen un
     * historial de llamadas desde afuera.
     */
    const val LLAMADA = "llamada"
    const val EVENTO = "evento"
    const val VOTO = "voto"

    /** Las que el servidor acepta. Cualquier otra cosa es un 400. */
    val VALIDAS = setOf(
        TEXTO, UBICACION, UBICACION_VIVA, UBICACION_VIVA_FIN,
        CONTACTO, ENCUESTA, EVENTO, VOTO,
    )
}

/**
 * Un mensaje dentro de un lote de historial.
 *
 * Lleva su `id` original a proposito: si el aparato nuevo ya lo tenia -porque
 * dos dispositivos respondieron el mismo pedido-, se descarta por id en vez de
 * duplicarse. Sin eso, sincronizar dos veces doblaria el historial.
 */
@Serializable
data class MensajeHistorico(
    val id: String,
    val autor: String,
    val esMio: Boolean,
    val texto: String,
    val creadoEn: Long,
)

@Serializable
enum class TipoAcuse { ENTREGADO, LEIDO }

/**
 * Estado local de un sobre en la cola de salida. Solo existe en el cliente.
 *
 * PENDIENTE es el estado que hace visible `msg off`: el mensaje ya esta
 * escrito y guardado, simplemente todavia no encontro un transporte. En la UI
 * se pinta con [com.wtfuck.app.ui.theme.EstadoPendiente] (ambar).
 */
@Serializable
enum class EstadoEnvio { PENDIENTE, ENVIADO, ENTREGADO, LEIDO, FALLIDO }

/**
 * Un transporte es cualquier cosa capaz de sacar un sobre del dispositivo.
 *
 * TransporteWebSocket — hay internet (wifi, 3G, 4G, 5G; da igual).
 * TransporteCerca     — no hay, pero hay alguien a unos metros (Bluetooth).
 *
 * El despachador los consulta por prioridad y usa el primero disponible.
 */
interface Transporte {
    val nombre: String

    /** Barato y sincrono: no abre conexiones, solo mira el estado actual. */
    fun disponible(): Boolean

    /** Prioridad menor = se intenta primero. WebSocket 0, cerca 10. */
    val prioridad: Int

    suspend fun entregar(sobre: Sobre): Result<Unit>
}

/**
 * Cuanto se puede compartir la ubicacion en vivo.
 *
 * Una lista cerrada y no un numero libre. Con un campo abierto habria que
 * decidir en la pantalla que es demasiado, y "demasiado" no es una pregunta
 * de interfaz: compartir donde estas es la cosa mas sensible que hace esta
 * app, y el tope tiene que estar declarado en el contrato donde se puede
 * discutir.
 *
 * Veinticuatro horas es el techo a proposito. Mas que eso deja de ser
 * "comparti mientras llego" y pasa a ser seguimiento, que es otra cosa y no
 * la que se quiso construir.
 */
/**
 * Un punto por el que paso una ubicacion en vivo.
 *
 * Los nombres son cortos a proposito: esto se guarda hasta
 * [ESTELA_MAX] veces dentro de `especialJson`, y `lat`/`lon`/`en` contra
 * `latitud`/`longitud`/`cuando` son unos 900 bytes menos por compartido.
 */
@Serializable
data class PuntoEstela(
    val lat: Double,
    val lon: Double,
    /** Cuando se supo, con el reloj de quien lo guarda. Igual que `recibidaEn`. */
    val en: Long,
)

/** Cuantos puntos de estela se guardan. Ver [Carga.UbicacionEnVivo.estela]. */
const val ESTELA_MAX = 60

/**
 * La misma carga, sin los campos que son de UN telefono.
 *
 * `recibidaEn` y `estela` estan documentados como "no viajan", y hasta el
 * modulo AN eso se cumplia por accidente: las cargas se armaban de cero, asi
 * que salian en su valor por defecto. Las ACTUALIZACIONES no, porque se arman
 * copiando lo guardado —que ya tiene los dos campos escritos—. `recibidaEn`
 * viajaba, y lo tapaba que del otro lado se pisa al guardar.
 *
 * Con la estela eso dejaria de ser inofensivo: hasta [ESTELA_MAX] puntos en
 * cada sobre, uno cada medio minuto durante hasta 24 horas, repitiendo una
 * historia que del otro lado ya esta.
 *
 * Vive en el contrato y no en el repositorio a proposito: un invariante que
 * se cumple porque nadie lo rompio todavia no es un invariante, y este tiene
 * que estar al lado de los campos que recorta para que quien agregue el
 * tercero lo vea.
 */
fun Carga.UbicacionEnVivo.paraLaRed(): Carga.UbicacionEnVivo =
    copy(recibidaEn = 0L, estela = emptyList())

/**
 * Los dos modos con los que se puede compartir la posicion.
 *
 * No es un campo del protocolo —en la carga viaja el booleano
 * [Carga.UbicacionEnVivo.conMapa], ver alli por que— sino el nombre de la
 * decision, para que la pantalla y las pruebas hablen igual.
 */
enum class ModoUbicacion(val conMapa: Boolean) {
    /**
     * Solo el rastro. No se le pide una imagen a nadie.
     *
     * Se ve por donde paso, cuanto camino y hacia donde va, sin que ningun
     * servidor de mapas se entere de que existe.
     */
    OCULTO(false),

    /** Con el mapa. Le cuesta contarle la posicion al servidor de baldosas. */
    VISIBLE(true),
    ;

    companion object {
        fun de(conMapa: Boolean): ModoUbicacion = if (conMapa) VISIBLE else OCULTO
    }
}

object DuracionUbicacion {
    const val MIN_15 = 15 * 60 * 1000L
    const val MIN_30 = 30 * 60 * 1000L
    const val HORA_1 = 60 * 60 * 1000L
    const val HORAS_8 = 8 * 60 * 60 * 1000L
    const val HORAS_12 = 12 * 60 * 60 * 1000L
    const val HORAS_24 = 24 * 60 * 60 * 1000L

    /** En el orden en que se ofrecen. */
    val OPCIONES = listOf(MIN_15, MIN_30, HORA_1, HORAS_8, HORAS_12, HORAS_24)

    /**
     * Si una duracion es una de las ofrecidas.
     *
     * Lo comprueba quien COMPARTE, no el servidor: el servidor no ve la carga.
     * Sirve para que un error de la app no produzca un compartido de una
     * semana, no para defenderse de nadie.
     */
    fun valida(ms: Long) = ms in OPCIONES
}

/**
 * Como se lee una llamada terminada: un titulo, un detalle y si fue perdida.
 *
 * Aqui y no en la pantalla porque es la unica parte de esto que se puede
 * probar sin un telefono: son reglas, no dibujo. Y hay mas casos de los que
 * parece — cuatro motivos por dos lados, mas el caso normal.
 */
data class LlamadaEnElChat(
    val titulo: String,
    val detalle: String,
    /**
     * Para pintarla distinto. Una llamada perdida que se ve igual que una
     * contestada obliga a leer el detalle para saber si hay algo que devolver,
     * y es la unica de la lista que pide una accion.
     */
    val perdida: Boolean,
)

fun llamadaEnElChat(
    conVideo: Boolean,
    saliente: Boolean,
    motivoFin: String,
    segundos: Long,
): LlamadaEnElChat {
    val que = if (conVideo) "Videollamada" else "Llamada"

    // Conecto: lo unico que importa es cuanto duro. El motivo del final da
    // igual —colgar es como terminan todas— y "Llamada · 5:32 · terminada"
    // solo seria mas larga.
    if (segundos > 0) {
        return LlamadaEnElChat(que, duracionLegible(segundos), perdida = false)
    }

    // No conecto. Aqui el motivo ES la noticia, y NO es el mismo de los dos
    // lados: "no contesto" y "perdida" son el mismo hecho contado por quien
    // llamo y por quien no atendio.
    return if (saliente) {
        when (motivoFin) {
            "rechazada" -> LlamadaEnElChat(que, "Rechazada", perdida = false)
            "sin_respuesta" -> LlamadaEnElChat(que, "Sin respuesta", perdida = false)
            "ocupado" -> LlamadaEnElChat(que, "Ocupado", perdida = false)
            "cancelada" -> LlamadaEnElChat(que, "Cancelada", perdida = false)
            "fallo_red" -> LlamadaEnElChat(que, "Se corto la conexion", perdida = false)
            else -> LlamadaEnElChat(que, "No se establecio", perdida = false)
        }
    } else {
        when (motivoFin) {
            // La rechace yo: no hay nada que devolver, asi que no va en rojo.
            "rechazada" -> LlamadaEnElChat("$que rechazada", "", perdida = false)
            "fallo_red" -> LlamadaEnElChat(que, "Se corto la conexion", perdida = false)
            // Todo lo demas sin contestar es una perdida, incluida la que el
            // otro cancelo antes de que diera tiempo a atender. Para quien la
            // recibe son el mismo hecho: sono y no llego a hablar.
            else -> LlamadaEnElChat("$que perdida", "", perdida = true)
        }
    }
}

/**
 * Una duracion como la escribe un telefono: `0:07`, `5:32`, `1:04:09`.
 *
 * Los minutos llevan cero a la izquierda **solo si hay horas**. Sin esa regla
 * una llamada de cinco minutos y medio se lee "05:32", que parece una hora
 * empezada y no una duracion.
 */
fun duracionLegible(segundos: Long): String {
    val s = if (segundos < 0) 0 else segundos
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
