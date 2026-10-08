package com.wtfuck.server

import com.wtfuck.protocol.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private val bitacora = LoggerFactory.getLogger("wtfuck")

object Config {
    /**
     * 8300, y el numero tiene historia.
     *
     * Primero fue 8080: lo tenia Docker. Se cambio a 8088 y funciono hasta que
     * Docker Desktop volvio a arrancar: en Windows, Hyper-V se RESERVA rangos
     * de puertos enteros al levantar, y ese dia se quedo con 8081-8180. El
     * sintoma es un "Address already in use" con NADA escuchando en el puerto,
     * que es lo que hace perder la tarde. Se ve con:
     *
     *     netsh int ipv4 show excludedportrange protocol=tcp
     *
     * Los rangos cambian en cada reinicio, asi que cambiar el numero no es la
     * solucion: la solucion es que el fallo lo DIGA. Ver `diagnosticarPuerto`.
     *
     * Del lado del emulador nada cambia: `adb reverse tcp:8088 tcp:8300` deja
     * el 8088 en el telefono y solo mueve el del anfitrion.
     */
    val puerto = System.getenv("WTFUCK_PUERTO")?.toIntOrNull() ?: 8300
    val dbUrl = System.getenv("WTFUCK_DB_URL") ?: "jdbc:postgresql://localhost:5433/wtfuck"
    val dbUser = System.getenv("WTFUCK_DB_USER") ?: "wtfuck"

    /**
     * La clave de la base.
     *
     * El valor por defecto es el de desarrollo, y eso es comodo hasta que un
     * despliegue se olvida de pasar la variable: entonces el servidor manda
     * `wtfuck_dev` contra una base de produccion y Postgres contesta
     *
     *     FATAL: password authentication failed for user "wtfuck"
     *
     * que se lee como "la contrasena no coincide" y lleva a sospechar del
     * volumen de Postgres —que graba la suya al inicializar y nunca mas— en
     * vez de a una variable que nadie paso. Borrar el volumen no arregla
     * nada, y esa pista falsa costo un rato.
     *
     * Por eso [claveDeDesarrollo] existe: para poder avisarlo al arrancar.
     */
    const val CLAVE_DEV = "wtfuck_dev"
    val dbPass = System.getenv("WTFUCK_DB_PASS") ?: CLAVE_DEV

    /** Si se esta usando la clave de desarrollo sin haberla declarado. */
    val claveDeDesarrollo = System.getenv("WTFUCK_DB_PASS") == null

    /**
     * Acepta aparatos que se declaran `SOFTWARE_DEV` (sin enclave seguro:
     * emuladores). Ver docs/04-DEVICE-BINDING.md
     *
     * El defecto es `false`, y fue `true` hasta el 2026-10-08. Con `true` por
     * defecto, un despliegue que se olvidara la variable quedaba abierto a
     * emuladores sin que nada lo dijera: fail-open. Produccion la fijaba en
     * `false` a mano, asi que la seguridad dependia de que nadie se saltara una
     * linea de un docker-compose. Ahora el olvido cierra, y quien necesita
     * emuladores -`pruebas/arrancar-servidor.ps1`- la pone en `true` a la vista.
     *
     * OJO con lo que esto protege y lo que no: el nivel lo DECLARA el cliente
     * y el servidor no verifica ninguna cadena de atestacion. Este interruptor
     * frena a la app honesta corriendo en un emulador; un cliente modificado
     * que escriba "TEE" pasa igual. Ver la seccion "Lo que el servidor
     * verifica hoy" del documento.
     */
    val permitirSoftwareDev = leerPermitirSoftwareDev(System.getenv("WTFUCK_PERMITIR_SOFTWARE_DEV"))

    /**
     * Solo `true` (sin importar mayusculas) abre. Cualquier otra cosa -ausente,
     * vacia, "1", "si", una errata- cierra: ante la duda, el lado seguro.
     */
    fun leerPermitirSoftwareDev(valor: String?): Boolean = valor?.trim()?.toBoolean() ?: false
}

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Hub de conexiones vivas.
 *
 * En memoria a proposito: una sola instancia. Para correr N servidores esto se
 * reemplaza por un registro en Redis (dispositivo -> instancia) + pub/sub.
 * Nada mas del diseno cambia, porque la verdad esta en `sobre_pendiente`.
 */
object Hub {
    private val vivos = ConcurrentHashMap<UUID, Channel<Bajada>>()

    /**
     * Cuantos aparatos tiene conectados cada persona. L.1.
     *
     * Es un contador y no un booleano porque con multi-dispositivo cerrar el
     * telefono no te deja desconectado si la tablet sigue abierta. Y es en
     * memoria y no una columna: una columna `en_linea` se queda en `true` para
     * siempre en cuanto un proceso muera sin limpiarla, y entonces la
     * plataforma miente justo sobre el dato que la gente usa para decidir si
     * la estan ignorando.
     */
    private val porUsuario = ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicInteger>()

    fun conectar(dispositivo: UUID, usuario: UUID): Channel<Bajada> {
        val ch = Channel<Bajada>(capacity = 256)
        vivos.put(dispositivo, ch)?.close()
        porUsuario.computeIfAbsent(usuario) { java.util.concurrent.atomic.AtomicInteger() }
            .incrementAndGet()
        // Modulo N: se avisa al bus de que este proceso se hace cargo de ese
        // socket. Sin bus es un no-op.
        Bus.registrar(dispositivo, usuario)
        return ch
    }

    fun desconectar(dispositivo: UUID, usuario: UUID, ch: Channel<Bajada>) {
        if (vivos[dispositivo] === ch) vivos.remove(dispositivo)
        porUsuario[usuario]?.let { if (it.decrementAndGet() <= 0) porUsuario.remove(usuario) }
        ch.close()
        // El tercer argumento es lo que el bus no puede saber: si a ESTE
        // proceso le queda otro aparato de la misma cuenta.
        Bus.olvidar(dispositivo, usuario, porUsuario.containsKey(usuario))
    }

    /**
     * Aparatos revocados mientras estaban conectados.
     *
     * Revocar invalidaba el token, pero el socket que ya estaba abierto seguia
     * vivo: el aparato no se enteraba hasta reconectar. Ahora se cierra en el
     * acto con 1008, el mismo codigo que un token invalido, que es lo que el
     * cliente entiende como "ya no estas vinculado".
     *
     * Solo en ESTE proceso: con varias instancias, el socket puede estar en
     * otra. Ahi lo corta igual el proximo intento de reconexion.
     */
    private val expulsados = ConcurrentHashMap.newKeySet<UUID>()

    fun expulsar(dispositivo: UUID) {
        expulsados += dispositivo
        vivos.remove(dispositivo)?.close()
    }

    fun fueExpulsado(dispositivo: UUID): Boolean = dispositivo in expulsados

    /** Los sockets que sostiene ESTE proceso. Los renueva el barrido. */
    fun mios(): Collection<UUID> = vivos.keys

    /** Las personas con algun socket en ESTE proceso. */
    fun personasMias(): Collection<UUID> = porUsuario.keys

    /**
     * Entrega algo que llego por el bus desde otra instancia.
     *
     * No vuelve a publicar: si el socket ya no esta aqui, el sobre sigue en la
     * base y se toma al reconectar. Republicarlo seria un rebote entre
     * instancias por un aparato que no esta en ninguna.
     */
    fun entregarDelBus(dispositivo: UUID, msg: Bajada) {
        vivos[dispositivo]?.trySend(msg)
    }

    /** Aqui o en cualquier otra instancia. */
    fun enLinea(dispositivo: UUID) =
        vivos.containsKey(dispositivo) || Bus.enLinea(dispositivo)

    /**
     * Si esa PERSONA tiene algun aparato conectado ahora mismo, aqui o en
     * cualquier otra instancia.
     */
    fun conectado(usuario: UUID?) =
        usuario != null && (porUsuario.containsKey(usuario) || Bus.conectado(usuario))

    /**
     * Entrega inmediata si esta conectado. Si no, el sobre espera en la base.
     *
     * Y ademas, modulo N: si NO esta conectado se pide despertarlo. Este es el
     * unico sitio donde tiene sentido enganchar el push, porque es el unico
     * lugar del servidor que sabe la diferencia entre "esta escuchando" y "hay
     * que ir a buscarlo".
     *
     * El aviso NO lleva contenido —ver `Push`—, asi que no importa cual de los
     * dos avisos disparo el despertar: el telefono se conecta y baja todo lo
     * pendiente, que es lo mismo que hace al arrancar.
     */
    fun empujar(dispositivo: UUID, msg: Bajada): Boolean {
        val local = vivos[dispositivo]?.trySend(msg)?.isSuccess ?: false
        if (local) {
            vigilarAcuse(dispositivo, msg)
            return true
        }

        // No esta aqui: puede estar en otra instancia. El bus devuelve si
        // habia alguien suscrito, asi que solo se recurre al push cuando NO
        // esta conectado en ningun proceso.
        if (Bus.publicar(dispositivo, msg)) {
            vigilarAcuse(dispositivo, msg)
            return true
        }

        Push.despertar(dispositivo)
        return false
    }

    /**
     * "Conectado" no es "escuchando": si el acuse no llega, se despierta igual.
     *
     * Con la app en segundo plano, Android CONGELA el proceso pero el socket
     * sigue abierto: para este servidor el aparato esta conectado, el sobre se
     * mete en el canal y `empujar` devuelve true. Nadie lo lee, no hay acuse, y
     * como "estaba conectado" el push no se pedia nunca. El ping lo detecta,
     * pero a los 60 s, y para entonces el mensaje ya se habia "entregado" a un
     * proceso dormido: llegaba recien cuando la persona abria la app.
     *
     * Ahora, si a los [ESPERA_ACUSE_S] segundos el sobre sigue en el buzon, se
     * pide el push. Un aviso de prioridad alta descongela el proceso, que lee
     * lo que tenia en el socket o reconecta, y notifica. Se consulta la BASE y
     * no un mapa en memoria porque el acuse puede entrar por otra instancia.
     *
     * Solo para entregas: son lo unico que merece despertar a alguien. Que un
     * mensaje propio se marco como leido puede esperar a que abra la app.
     */
    private fun vigilarAcuse(dispositivo: UUID, msg: Bajada) {
        val e = msg as? Bajada.Entrega ?: return
        val id = runCatching { UUID.fromString(e.sobreId) }.getOrNull() ?: return
        runCatching {
            vigia.schedule({
                runCatching { if (Repo.sobreSigue(id)) Push.despertar(dispositivo) }
            }, ESPERA_ACUSE_S, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /**
     * Un solo hilo: cada tarea es una consulta por clave primaria. Daemon para
     * que no impida cerrar el proceso.
     */
    private val vigia = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vigia-acuses").apply { isDaemon = true }
    }

    /**
     * Ocho segundos. Un telefono despierto acusa en menos de uno; el margen es
     * para una red movil lenta, que no debe disparar un push de mas. Y si lo
     * dispara no pasa nada: el aviso va vacio y `Push` ya limita uno por
     * aparato cada pocos segundos.
     */
    private const val ESPERA_ACUSE_S = 8L
}

fun main() {
    // Se avisa ANTES de intentar conectar. Si la clave es la de desarrollo
    // porque nadie paso la variable, el error de Postgres que viene despues
    // no lo dice, y sin esta linea hay que deducirlo.
    if (Config.claveDeDesarrollo && !Config.permitirSoftwareDev) {
        org.slf4j.LoggerFactory.getLogger("wtfuck").warn(
            "WTFUCK_DB_PASS no esta definida: se usara la clave de desarrollo. " +
                "Si la base no la reconoce, el fallo sera " +
                "'password authentication failed' y la causa es esta variable, " +
                "no el volumen de Postgres."
        )
    }
    Db.iniciar(Config.dbUrl, Config.dbUser, Config.dbPass)
    bitacora.info("Base lista. permitirSoftwareDev=${Config.permitirSoftwareDev}")
    // Con `true` se aceptan emuladores. En desarrollo es lo que se quiere; en
    // produccion es el vinculo de hardware desactivado. Que lo grite.
    if (Config.permitirSoftwareDev) {
        bitacora.warn(
            "WTFUCK_PERMITIR_SOFTWARE_DEV=true: se aceptan aparatos sin enclave seguro " +
                "(emuladores). Solo para desarrollo. Ver docs/04-DEVICE-BINDING.md"
        )
    }
    // El chat de texto debe seguir funcionando aunque el almacen este caido.
    Almacen.iniciar()
    bitacora.info("Almacen de adjuntos disponible=${Almacen.disponible()}")
    // Problema de arranque: solo un administrador puede nombrar staff, y al
    // principio no hay ninguno. Se resuelve por variable de entorno y no por
    // una ruta: una ruta de "hazme administrador" protegida por un secreto es
    // la clase de cosa que termina abierta en produccion.
    Panel.sembrarPropietario(System.getenv("WTFUCK_PROPIETARIO"))
    // Modulo N. Sin WTFUCK_REDIS_URL no hace nada: una sola instancia es el
    // caso normal y no debe necesitar Redis para arrancar.
    Bus.iniciar { dispositivo, msg -> Hub.entregarDelBus(dispositivo, msg) }
    arrancarTareas()
    try {
        embeddedServer(Netty, port = Config.puerto, host = "0.0.0.0") { modulo() }
            .start(wait = true)
    } catch (e: java.net.BindException) {
        // Un BindException tiene dos causas muy distintas y el mensaje del
        // sistema no las separa: o hay otro proceso escuchando, o Windows se
        // reservo el rango y no hay NADIE escuchando. La segunda es la que
        // hace perder la tarde, porque `netstat` sale vacio.
        bitacora.error(diagnosticarPuerto(Config.puerto), e)
        throw e
    }
}

/**
 * Por que no se pudo abrir el puerto.
 *
 * Solo en Windows pregunta por los rangos reservados: en Linux esto no existe
 * y el mensaje seria ruido.
 */
private fun diagnosticarPuerto(puerto: Int): String {
    val base = "No se pudo abrir el puerto $puerto."
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        return "$base Ya hay otro proceso escuchando ahi."
    }
    val reservado = runCatching {
        ProcessBuilder("netsh", "int", "ipv4", "show", "excludedportrange", "protocol=tcp")
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readLines()
            .mapNotNull { linea ->
                val n = Regex("""\d+""").findAll(linea).map { it.value.toInt() }.toList()
                if (n.size >= 2 && puerto >= n[0] && puerto <= n[1]) n[0] to n[1] else null
            }
            .firstOrNull()
    }.getOrNull()

    return if (reservado != null) {
        "$base Windows tiene RESERVADO el rango ${reservado.first}-${reservado.second} " +
            "(Hyper-V o WSL lo toman al arrancar Docker), asi que el puerto no se puede " +
            "abrir aunque no haya nada escuchando. Elige otro con WTFUCK_PUERTO, o mira " +
            "los rangos con: netsh int ipv4 show excludedportrange protocol=tcp"
    } else {
        "$base Ya hay otro proceso escuchando ahi."
    }
}

/**
 * El transporte de SMS. Se elige una vez, al arrancar.
 *
 * Global y no inyectado porque el servidor no tiene contenedor de dependencias
 * y meter uno para esto seria desproporcionado. Lo que si importa es que la
 * eleccion quede anotada en la bitacora: desplegar sin pasarela y no darse
 * cuenta es el error que hay que hacer ruidoso.
 */
private val sms: Sms by lazy { SmsFactory.desdeEntorno() }

/**
 * Lo que hay que hacer sin que nadie lo pida.
 *
 * Un hilo demonio con un bucle, no un planificador: son dos tareas y corren una
 * vez por hora. Traer una libreria de scheduling para esto seria mas
 * configuracion que trabajo.
 *
 * La primera es la que importa: **un periodo de gracia que no se ejecuta no es
 * un periodo de gracia**, es una cuenta que quedo inutilizable para siempre.
 */
/**
 * Todo lo que hay que barrer, en un sitio con nombre.
 *
 * ## Por que es publica y esta fuera del hilo
 *
 * Porque lo que se rompio aqui no fue ninguna de las consultas: fue que una de
 * ellas **no se llamaba**. `Repo.barrerExpirados` existia desde el modulo C
 * con su `expira_en` bien puesto, y los sobres se quedaban en la base para
 * siempre porque nadie la invocaba. No lo vio ninguna prueba: el codigo estaba
 * ahi y compilaba.
 *
 * Es la segunda vez en este proyecto (`llamadas.recuperar` fue la primera).
 * Una funcion que existe y no se llama es la unica forma de que un arreglo no
 * arregle nada.
 *
 * Con el cuerpo dentro de un `Thread` anonimo no habia forma de comprobarlo.
 * Sacado aqui, una prueba lo ejecuta y mira la base: si alguien quita una
 * linea de esta funcion, la prueba de ese barrido cae. Eso es lo que no
 * existia.
 *
 * Devuelve lo barrido por cada cosa, para el log y para las pruebas.
 */
fun tareasDeMantenimiento(): Map<String, Int> {
    val cuentas = Identidad.ejecutarEliminacionesVencidas()
    val cupos = Db.tx { c -> Cupos.barrer(c) }
    val codigos = Db.tx { c -> Dispositivos.barrerCodigos(c) }
    // Los sobres que nadie recogio. Un buzon tonto que no olvida no es un
    // buzon tonto: es un archivo.
    val sobres = Repo.barrerExpirados()
    val (seguridad, auditoria) = Repo.barrerRegistros()
    return mapOf(
        "cuentas" to cuentas,
        "cupos" to cupos,
        "codigos" to codigos,
        "sobres" to sobres,
        "seguridad" to seguridad,
        "auditoria" to auditoria,
    )
}

private fun arrancarTareas() {
    Thread({
        // El primer barrido es AL ARRANCAR y no dentro de una hora.
        //
        // Un servidor que vuelve despues de estar caido un dia tiene un dia de
        // cosas vencidas encima, y esperar otra hora para tocarlas no tiene
        // ninguna ventaja. De paso, el log de arranque dice que los barridos
        // existen — que es justo lo que faltaba cuando uno llevaba modulos sin
        // llamarse.
        while (true) {
            runCatching {
                val hecho = tareasDeMantenimiento().filterValues { it > 0 }
                if (hecho.isNotEmpty()) bitacora.info("Mantenimiento: {}", hecho)
            }.onFailure { bitacora.warn("Fallo una tarea periodica: {}", it.message) }

            // Una hora. La precision no importa: la gracia se mide en dias.
            runCatching { Thread.sleep(3_600_000) }.onFailure { return@Thread }
        }
    }, "wtfuck-tareas").apply { isDaemon = true }.start()

    /*
     * Modulo N. Renovar las marcas de presencia de los sockets de este proceso.
     *
     * Va en su propio hilo y con su propio ritmo -30 s- porque no tiene nada que
     * ver con el barrido de una hora: si esto se atrasara, las marcas vencerian
     * y la gente apareceria desconectada teniendo el socket abierto.
     *
     * Sin bus, `renovar` es un no-op y el hilo duerme para siempre sin costo.
     */
    Thread({
        while (true) {
            runCatching { Bus.renovar(Hub.mios(), Hub.personasMias()) }
                .onFailure { bitacora.warn("Fallo renovar la presencia: {}", it.message) }
            runCatching { Thread.sleep(Bus.REFRESCO_S * 1000) }.onFailure { return@Thread }
        }
    }, "wtfuck-presencia").apply { isDaemon = true }.start()

    /*
     * El timbre va en su propio hilo, con su propio ritmo.
     *
     * No puede compartir el bucle horario de arriba: un timbre de 45 segundos
     * cerrado una vez por hora dejaria el telefono del otro sonando hasta 59
     * minutos. Y no puede depender del cliente: si el que llama cierra la app,
     * nadie manda el "cancelada".
     */
    Thread({
        while (true) {
            runCatching {
                // Las abandonadas van en el MISMO hilo: las dos son "el
                // servidor cierra lo que el cliente no cerro", y separarlas en
                // dos hilos seria dos relojes para la misma idea.
                val (abandonadas, avisosAb) = Llamadas.cerrarLlamadasAbandonadas()
                avisosAb.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
                if (abandonadas > 0) {
                    bitacora.info("Cerradas {} llamadas abandonadas.", abandonadas)
                }

                // Y las de grupo donde quedo una sola persona. Va aqui y
                // no en el barrido de una hora porque el limite son CINCO
                // MINUTOS: con el reloj lento, esperar solo en una llamada
                // podrian ser sesenta y cinco.
                runCatching {
                    val (solas, avisosSolo) = Llamadas.cerrarLlamadasSolitarias()
                    avisosSolo.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
                    if (solas > 0) bitacora.info("Cerradas {} llamadas con una sola.", solas)
                }.onFailure { bitacora.warn("Fallo el barrido de solitarias: {}", it.message) }

                // Cada barrido en su PROPIO runCatching.
                //
                // Compartian uno solo, y eso convierte el fallo de cualquiera
                // en el fallo de todos: al agregar el de chats temporales, un
                // CHECK violado en su aviso dejo de barrer tambien los timbres
                // y las llamadas abandonadas —cada diez segundos, durante todo
                // el rato que tardo en verse—. El sintoma no se parecia a la
                // causa: las llamadas dejaron de cerrarse solas por un chat.
                //
                // Y los chats temporales que vencieron.
                //
                // Va en el hilo de 10 segundos y no en el de una hora porque
                // el plazo mas corto que se puede elegir es UNA HORA: con el
                // reloj lento, un chat de una hora podria durar dos, que es el
                // doble de lo que su dueno acepto.
                runCatching {
                    val (chats, avisosChat) = Repo.borrarConversacionesVencidas()
                    avisosChat.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
                    if (chats > 0) bitacora.info("Borrados {} chats temporales vencidos.", chats)
                }.onFailure { bitacora.warn("Fallo el barrido de temporales: {}", it.message) }

                val (cerradas, avisos) = Llamadas.cerrarTimbresVencidos()
                // Los avisos se empujan FUERA de la transaccion, igual que en
                // el resto del servidor. Sin esto, la pantalla del que llamaba
                // se quedaba en "Llamando..." para siempre.
                avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
                if (cerradas > 0) bitacora.info("Cerradas {} llamadas sin respuesta.", cerradas)
            }.onFailure { bitacora.warn("Fallo el barrido de timbres: {}", it.message) }
            runCatching { Thread.sleep(10_000) }.onFailure { return@Thread }
        }
    }, "wtfuck-timbres").apply { isDaemon = true }.start()
}

fun Application.modulo() {
    // La hora de este servidor en cada respuesta, en milisegundos.
    //
    // Es la referencia con la que el telefono corrige su reloj antes de
    // sellar un mensaje (`Reloj` en la app). Sin ella, un telefono con la hora
    // atrasada fechaba sus mensajes en el pasado y quien los recibia los veia
    // enterrados entre los viejos. La cabecera `Date` no sirve: tiene
    // resolucion de un segundo y Netty no la manda sin un plugin.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        call.response.header("X-Hora", System.currentTimeMillis().toString())
    }
    install(ContentNegotiation) { json(json) }
    install(CallLogging) { level = Level.INFO }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
    }
    install(StatusPages) {
        exception<ErrorNegocio> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.codigo), ErrorResp(e.motivo))
        }
        // Un cuerpo mal formado es culpa de quien llama, no del servidor.
        // Sin esto se devolvia 500 y parecia una caida nuestra.
        exception<io.ktor.server.plugins.BadRequestException> { call, e ->
            bitacora.info("Peticion mal formada: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, ErrorResp("La peticion no tiene el formato esperado."))
        }
        exception<Throwable> { call, e ->
            bitacora.error("Fallo no controlado", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResp("Error interno."))
        }
    }

    routing {
        get("/salud") { call.respondText("ok") }

        /*
         * L.8 · La consola web, servida como un archivo y nada mas.
         *
         * Va sin autenticar a proposito: es HTML y JavaScript publicos, sin un
         * dato dentro. Todo lo que muestra lo pide despues con el token de
         * consola que la persona pega, y ese token lo emite su telefono. Poner
         * autenticacion delante del archivo no protegeria nada -no hay nada que
         * proteger- y obligaria a inventar un ingreso web, que es justo lo que
         * el modelo evita.
         */
        // Lo que abre el enlace de contacto fuera de la app. Sin autenticar y sin
        // consultar la base: ver `EnlaceContacto.pagina`.
        get("/c/{codigo}") {
            call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.respondText(
                EnlaceContacto.pagina(call.parameters["codigo"].orEmpty(), System.getenv("WTFUCK_DESCARGA_URL")),
                ContentType.Text.Html,
            )
        }

        // La version web: archivos estaticos, mismo origen. Ver `Web`.
        with(Web) { rutasWeb() }

        get("/consola") {
            val html = Consola::class.java.getResourceAsStream("/consola/index.html")
                ?.bufferedReader()?.use { it.readText() }
            if (html == null) call.respond(HttpStatusCode.NotFound, "Consola no empaquetada.")
            else call.respondText(html, ContentType.Text.Html)
        }

        // Publico y antes del formulario. Ver `Invitaciones.modo`.
        get(RUTA_REGISTRO_MODO) { call.respond(Invitaciones.modo()) }

        // Sin autenticar, y es lo importante de esta ruta: tiene que poder
        // contestarle a una app tan vieja que ya no puede entrar. Si un
        // cambio de protocolo la deja fuera, la respuesta que necesita es
        // "actualizate", y no puede depender de un login que ya no funciona.
        get(RUTA_VERSION) { call.respond(Actualizacion.publicada()) }

        post(RUTA_REGISTRO) {
            val req = call.receive<RegistroReq>()
            val ip = call.ipCliente()

            // El orden de lo que sigue es el contenido de la ruta, y cada
            // cambio de lugar abre algo distinto:
            //
            //  1. La puerta de invitacion, PRIMERO. Quien no tiene invitacion
            //     no gasta el cupo de la red de donde viene; si no, alguien
            //     probando codigos en la red de un campus dejaria sin poder
            //     registrarse a los que si tienen uno. En modo abierto no hace
            //     nada. Ver `Invitaciones.exigirPuerta`.
            //  2. Los dos limites por red, ANTES de validar el cuerpo. Al reves,
            //     un 400 o un 409 saldrian gratis, y el 409 de "ese usuario ya
            //     existe" es un oraculo para enumerar usernames. Leer el JSON
            //     no es validarlo: hace falta para la puerta y no cuesta nada.
            //  3. Validar y dar de alta, en `Repo.registrar`.
            //
            // La rafaga va antes que el cupo diario porque es la barata: en
            // memoria, sin tocar la base. Una avalancha se corta ahi.
            Invitaciones.exigirPuerta(req.username, req.codigoInvitacion)
            val red = Seguridad.redDe(ip)
            Limitador.exigir(null, red, "registro_red", Limitador.REGISTRO_RED)
            Cupos.exigirPorRed(red, ip, "registro", Cupos.REGISTROS_POR_RED_DIA)

            val resp = Repo.registrar(req, ip, call.request.headers["User-Agent"])
            // El alta es el primer evento de la cuenta, y es el que da sentido
            // a todo el resto de la lista: sin el, "Actividad de la cuenta"
            // arranca en cualquier parte y no se puede saber si falta algo.
            Db.tx { c ->
                Seguridad.anotar(
                    c, UUID.fromString(resp.usuarioId), "registro",
                    ip = call.ipCliente(),
                    agente = call.request.headers["User-Agent"],
                )
            }
            call.respond(resp)
        }
        // --- invitaciones de registro (modulo BC) --------------------
        //
        // Autenticadas y solo para administradores. `exigirStaff` contesta 404
        // a quien no llega al nivel, no 403: un 403 confirmaria que la ruta
        // existe, y quien no reparte invitaciones no tiene por que saber que
        // este servidor las usa.
        post(RUTA_INVITACIONES_REGISTRO) {
            call.respond(Invitaciones.crear(call.autenticar(), call.receive()))
        }
        get(RUTA_INVITACIONES_REGISTRO) {
            call.respond(Invitaciones.listar(call.autenticar()))
        }
        delete("$RUTA_INVITACIONES_REGISTRO/{codigo}") {
            Invitaciones.revocar(call.autenticar(), call.parameters["codigo"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }

        post(RUTA_SESION) {
            val ip = call.ipCliente()
            val req = call.receive<SesionReq>()
            val quien = req.username.lowercase().trim()

            // Se comprueba sin consumir cupo: un ingreso correcto no debe
            // gastar el presupuesto de nadie. Ver Limitador.FALLOS_POR_USUARIO.
            Limitador.exigirSinContar(quien, "ingreso_usuario", Limitador.FALLOS_POR_USUARIO)
            Limitador.exigirSinContar(ip, "ingreso_ip", Limitador.FALLOS_POR_IP)

            val resp = try {
                Repo.login(req, ip, call.request.headers["User-Agent"])
            } catch (e: ErrorNegocio) {
                // Un 401 por falta de segundo factor no es un intento
                // fallido de contrasena: la contrasena estuvo bien. Contarlo
                // dejaria a alguien fuera de su propia cuenta por teclear mal
                // el codigo de su autenticador ocho veces.
                val esFalloDeClave = e.codigo == 401 && !e.motivo.contains("dos pasos", true) &&
                    !e.motivo.contains("verificacion en dos", true)
                if (esFalloDeClave) {
                    Limitador.anotarFallo(quien, "ingreso_usuario", Limitador.FALLOS_POR_USUARIO)
                    Limitador.anotarFallo(ip, "ingreso_ip", Limitador.FALLOS_POR_IP)
                    // Se anota sin usuario: puede que ese usuario no exista, y
                    // crear la fila igual es lo que permite ver despues que
                    // alguien estuvo probando nombres.
                    Db.tx { c ->
                        Seguridad.anotar(
                            c, null, "ingreso_fallido", ip = ip,
                            agente = call.request.headers["User-Agent"],
                            detalle = """{"username":"$quien"}""",
                        )
                    }
                }
                throw e
            }

            // Un ingreso correcto tambien se anota, y es el que de verdad
            // importa: "¿desde donde entraron a mi cuenta?" solo se puede
            // responder si los buenos quedan registrados, no solo los malos.
            Db.tx { c ->
                Seguridad.anotar(
                    c, UUID.fromString(resp.usuarioId), "ingreso", ip = ip,
                    agente = call.request.headers["User-Agent"],
                )
            }
            call.respond(resp)
        }

        /*
         * L.1 · Cuales de mis mensajes ya se leyeron.
         *
         * Existe porque el aviso de lectura viaja por el socket y se pierde si
         * el remitente no estaba conectado. Al abrir el chat se pregunta una
         * vez y se pone al dia.
         */
        get("/v1/conversaciones/{id}/leidos") {
            val yo = call.autenticar()
            call.respond(MensajesLeidos(Repo.mensajesLeidos(yo, call.idRuta())))
        }

        // El directorio de Usuarios. Solo quien se apunto: ver `Repo.directorio`
        // y V44. Con el limite de busqueda, porque recorrer la lista entera en
        // bucle es justo lo que no se quiere facilitar.
        get(RUTA_DIRECTORIO) {
            val yo = call.autenticar()
            Limitador.exigir(
                yo.usuarioId, yo.usuarioId.toString(), "buscar", Limitador.BUSCAR,
            )
            val q = call.request.queryParameters["q"].orEmpty()
            val desde = call.request.queryParameters["desde"].orEmpty()
            call.respond(Repo.directorio(yo, q, desde))
        }

        get("$RUTA_USUARIO/{username}") {
            val u = call.parameters["username"].orEmpty()
            val yo = call.autenticar()
            call.respond(Repo.buscar(yo, u) ?: throw ErrorNegocio(404, "No existe el usuario @$u."))
        }

        // --- perfil ---------------------------------------------------

        // --- Enlace de contacto. Ver `EnlaceContacto`. ---------------------
        get(RUTA_MI_ENLACE) { call.respond(MiEnlace(EnlaceContacto.actual(call.autenticar()))) }
        post(RUTA_MI_ENLACE) {
            val yo = call.autenticar()
            Limitador.exigir(yo.usuarioId, yo.usuarioId.toString(), "rotar_enlace", Limitador.ROTAR_ENLACE)
            call.respond(MiEnlace(EnlaceContacto.crear(yo)))
        }
        delete(RUTA_MI_ENLACE) {
            EnlaceContacto.borrar(call.autenticar())
            call.respond(HttpStatusCode.NoContent)
        }
        get("$RUTA_ENLACES/{codigo}") {
            val yo = call.autenticar()
            // El mismo cupo que buscar: es una forma de encontrar a alguien.
            Limitador.exigir(yo.usuarioId, yo.usuarioId.toString(), "resolver_enlace", Limitador.BUSCAR)
            call.respond(EnlaceContacto.resolver(yo, call.parameters["codigo"].orEmpty()))
        }

        get(RUTA_PERFIL) {
            val yo = call.autenticar()
            call.respond(Repo.porId(yo.usuarioId) ?: throw ErrorNegocio(404, "Perfil no encontrado."))
        }

        put(RUTA_PERFIL) {
            val yo = call.autenticar()
            Repo.guardarPerfil(yo, call.receive())
            call.respond(Repo.porId(yo.usuarioId) ?: throw ErrorNegocio(404, "Perfil no encontrado."))
        }

        put("$RUTA_PERFIL/{campo}") {
            val yo = call.autenticar()
            val campo = call.parameters["campo"].orEmpty()
            if (campo !in setOf("avatar", "portada")) throw ErrorNegocio(404, "Recurso desconocido.")
            // Se lee acotado: sin el limite, un cliente podria empujar el heap.
            val bytes = call.receiveStream().readNBytes(1_048_577)
            Repo.guardarImagen(yo, campo, bytes)
            call.respond(Repo.porId(yo.usuarioId)!!)
        }

        // Las imagenes se sirven a cualquier usuario autenticado: son parte del
        // perfil publico, igual que el username.
        get("$RUTA_USUARIO/{username}/{campo}") {
            val yo = call.autenticar()
            val campo = call.parameters["campo"].orEmpty()
            if (campo !in setOf("avatar", "portada")) throw ErrorNegocio(404, "Recurso desconocido.")
            val bytes = Repo.imagen(yo, call.parameters["username"].orEmpty(), campo)
                ?: throw ErrorNegocio(404, "Sin imagen.")
            // Inmutable: la URL lleva ?v=<marca de tiempo>, asi que un cambio
            // genera otra URL y el cache nunca sirve una foto vieja.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respondBytes(bytes, ContentType.Image.Any)
        }

        get(RUTA_PRIVACIDAD) {
            val yo = call.autenticar()
            call.respond(Repo.privacidad(yo.usuarioId))
        }

        put(RUTA_PRIVACIDAD) {
            val yo = call.autenticar()
            call.respond(Repo.guardarPrivacidad(yo, call.receive()))
        }

        /*
         * L.1 · Las listas del nivel `personalizado`.
         *
         * Devuelve SIEMPRE los siete ajustes personalizables, incluso vacios:
         * una lista que solo aparece cuando ya tiene contenido es una lista
         * que nadie descubre.
         */
        get("/v1/perfil/privacidad/excepciones") {
            call.respond(TodasLasExcepciones(Repo.excepciones(call.autenticar())))
        }

        put("/v1/perfil/privacidad/excepciones") {
            val yo = call.autenticar()
            Repo.guardarExcepciones(yo, call.receive())
            call.respond(TodasLasExcepciones(Repo.excepciones(yo)))
        }

        get(RUTA_CONVERSACIONES) { call.respond(Repo.listar(call.autenticar())) }

        post(RUTA_DIRECTA) {
            val yo = call.autenticar()
            val req = call.receive<DirectaReq>()
            call.respond(Repo.crearDirecta(yo, req.usernameDestino, req.duracionMs, req.enlace))
        }

        post(RUTA_NOTAS) { call.respond(Repo.notaParaMi(call.autenticar())) }

        post(RUTA_GRUPOS) {
            val yo = call.autenticar()
            Limitador.exigir(
                yo.usuarioId, yo.usuarioId.toString(), "crear_grupo", Limitador.CREAR_GRUPO,
            )
            val r = Repo.crearGrupo(yo, call.receive())
            // El aviso ya esta guardado; esto solo adelanta la entrega a quien
            // este conectado en este momento.
            r.avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(r.resumen)
        }

        post("$RUTA_CONVERSACIONES/{id}/miembros") {
            val yo = call.autenticar()
            val id = call.idRuta()
            val r = Repo.agregarMiembros(yo, id, call.receive<MiembrosReq>().usernames)
            r.avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(r.resumen)
        }

        // ============================================================
        //  Modulo D: adjuntos
        // ============================================================

        post(RUTA_ADJUNTOS) {
            val yo = call.autenticar()
            Limitador.exigir(
                yo.usuarioId, yo.usuarioId.toString(), "subir", Limitador.SUBIR_ARCHIVO,
            )
            call.respond(Adjuntos.reservar(yo, call.receive()))
        }

        post("$RUTA_ADJUNTOS/{id}/confirmar") {
            call.respond(Adjuntos.confirmar(call.autenticar(), call.idRuta()))
        }

        get("$RUTA_ADJUNTOS/{id}") {
            call.respond(Adjuntos.leer(call.autenticar(), call.idRuta()))
        }

        get("$RUTA_ADJUNTOS/uso") {
            call.respond(Adjuntos.uso(call.autenticar()))
        }

        // ============================================================
        //  Modulo F: canales
        // ============================================================

        post(RUTA_CANALES) {
            val yo = call.autenticar()
            Limitador.exigir(
                yo.usuarioId, yo.usuarioId.toString(), "crear_canal", Limitador.CREAR_CANAL,
            )
            call.respond(Canales.crear(yo, call.receive()))
        }

        /*
         * F.7 · El directorio: los canales aprobados, sin buscar nada.
         *
         * Va antes de /{id} por lo mismo que la busqueda: "directorio" es una
         * ruta literal y no un identificador.
         */
        get("$RUTA_CANALES/directorio") {
            val yo = call.autenticar()
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 60
            call.respond(DirectorioCanales(Canales.directorio(yo, limite)))
        }

        // La busqueda va ANTES de /{id}: si no, "buscar" se tomaria por un id.
        get("$RUTA_CANALES/buscar") {
            val yo = call.autenticar()
            Limitador.exigir(
                yo.usuarioId, yo.usuarioId.toString(), "buscar", Limitador.BUSCAR,
            )
            val q = call.request.queryParameters["q"].orEmpty()
            call.respond(ResultadoBusquedaCanales(Canales.buscar(yo, q)))
        }

        // Igual que arriba: "alias" es una ruta literal, no un identificador.
        get("$RUTA_CANALES/alias/{alias}") {
            val yo = call.autenticar()
            call.respond(Canales.porAlias(yo, call.parameters["alias"].orEmpty()))
        }

        get("$RUTA_CANALES/{id}") {
            call.respond(Canales.leer(call.autenticar(), call.idRuta()))
        }

        put("$RUTA_CANALES/{id}") {
            val yo = call.autenticar()
            call.respond(Canales.configurar(yo, call.idRuta(), call.receive()))
        }

        post("$RUTA_CANALES/{id}/suscribir") {
            call.respond(Canales.suscribir(call.autenticar(), call.idRuta()))
        }

        post("$RUTA_CANALES/{id}/desuscribir") {
            Canales.desuscribir(call.autenticar(), call.idRuta())
            call.respond(HttpStatusCode.NoContent)
        }

        /*
         * La UNICA ruta del sistema que recibe contenido en claro.
         *
         * Esta aparte y con este nombre a proposito: la excepcion tiene que
         * verse en el mapa de rutas y no esconderse dentro de algo que
         * normalmente no guarda contenido. Solo acepta canales publicos.
         */
        post("$RUTA_CANALES/{id}/publicaciones") {
            val yo = call.autenticar()
            val avisos = Canales.guardarContenido(yo, call.idRuta(), call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_CANALES/{id}/publicaciones") {
            val yo = call.autenticar()
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 30
            val antesDe = call.request.queryParameters["antes"]
            call.respond(Canales.publicaciones(yo, call.idRuta(), limite, antesDe))
        }

        /**
         * Modulo AA: el cuerpo de un comentario, en claro.
         *
         * La segunda ruta del sistema que lleva contenido sin cifrar, y esta
         * aparte por lo mismo que la de publicaciones: la excepcion se ve en
         * el mapa de rutas o no se ve.
         *
         * No es una comodidad. Un canal publico no reparte sobres, asi que
         * antes de esta ruta un comentario dejaba su metadato -y su contador-
         * y **perdia el texto**.
         */
        post("$RUTA_CANALES/{id}/comentarios") {
            val yo = call.autenticar()
            val avisos = Canales.guardarComentario(yo, call.idRuta(), call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Los comentarios de una publicacion.
         *
         * La publicacion va en la ruta y no en la query: un comentario es de
         * una publicacion, no del canal, y la ruta tiene que decir de que
         * cuelga.
         */
        get("$RUTA_CANALES/{id}/publicaciones/{pid}/comentarios") {
            val yo = call.autenticar()
            val pid = call.parameters["pid"]
                ?: throw ErrorNegocio(400, "Falta la publicacion.")
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 100
            call.respond(ComentariosResp(Canales.comentarios(yo, call.idRuta(), pid, limite)))
        }

        get("$RUTA_CANALES/{id}/estadisticas") {
            call.respond(Canales.estadisticas(call.autenticar(), call.idRuta()))
        }

        // ============================================================
        //  Modulo AD: comunidades
        // ============================================================
        //
        // Un conjunto de grupos bajo un nombre, mas un canal de anuncios. Lo
        // segundo es lo unico que la hace una comunidad: agrupar chats sin eso
        // es una carpeta, y una carpeta se resuelve en el telefono.
        //
        // No hay ruta para "unirse a una comunidad" y no es un olvido: **la
        // pertenencia se deriva** de estar en alguno de sus grupos. Una ruta
        // para unirse crearia una segunda forma de pertenecer, y dos listas
        // que dicen lo mismo se separan.

        post(RUTA_COMUNIDADES) {
            val yo = call.autenticar()
            call.respond(Comunidades.crear(yo, call.receive()))
        }

        get(RUTA_COMUNIDADES) {
            call.respond(Comunidades.mias(call.autenticar()))
        }

        get("$RUTA_COMUNIDADES/{id}") {
            call.respond(Comunidades.detalle(call.autenticar(), call.idRuta()))
        }

        put("$RUTA_COMUNIDADES/{id}") {
            val yo = call.autenticar()
            call.respond(Comunidades.editar(yo, call.idRuta(), call.receive()))
        }

        post("$RUTA_COMUNIDADES/{id}/grupos") {
            val yo = call.autenticar()
            call.respond(Comunidades.agregarGrupos(yo, call.idRuta(), call.receive()))
        }

        delete("$RUTA_COMUNIDADES/{id}/grupos/{grupo}") {
            val yo = call.autenticar()
            val grupo = call.parameters["grupo"]?.let {
                runCatching { java.util.UUID.fromString(it) }.getOrNull()
            } ?: throw ErrorNegocio(400, "Identificador de grupo invalido.")
            Comunidades.quitarGrupo(yo, call.idRuta(), grupo)
            call.respond(HttpStatusCode.NoContent)
        }

        // ============================================================
        //  Modulo K: llamadas
        // ============================================================
        //
        // Aqui NO hay señalizacion. El SDP y los candidatos ICE viajan dentro
        // de sobres cifrados, por el mismo camino que un mensaje: si el
        // servidor pudiera leerlos podria cambiar las huellas DTLS y escuchar
        // la llamada, con cada tramo perfectamente cifrado contra el.
        //
        // Estas rutas son metadatos: quien llama a quien, quien contesto, y
        // como termino. Ni un SDP pasa por aqui.

        post(RUTA_LLAMADAS) {
            val yo = call.autenticar()
            val (creada, avisos) = Llamadas.iniciar(yo, call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(creada)
        }

        /* La llamada viva, si hay. La pide la app al arrancar: si se cerro en
         * medio de una llamada, al volver tiene que encontrarla. */
        get("$RUTA_LLAMADAS/en-curso") {
            val l = Llamadas.enCurso(call.autenticar())
            if (l == null) call.respond(HttpStatusCode.NoContent) else call.respond(l)
        }

        get("$RUTA_LLAMADAS/historial") {
            val yo = call.autenticar()
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 50
            call.respond(HistorialLlamadas(Llamadas.historial(yo, limite)))
        }

        /* Credenciales de TURN sueltas, para renovar sin reiniciar la llamada:
         * duran horas y una llamada larga puede pasarse de ese plazo. */
        get("$RUTA_LLAMADAS/turn") {
            val yo = call.autenticar()
            call.respond(Llamadas.credencialesTurn(yo.username))
        }

        post("$RUTA_LLAMADAS/{id}/contestar") {
            val yo = call.autenticar()
            val (curso, avisos) = Llamadas.contestar(yo, call.idRuta())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(curso)
        }

        post("$RUTA_LLAMADAS/{id}/terminar") {
            val yo = call.autenticar()
            val avisos = Llamadas.terminar(yo, call.idRuta(), call.receive<TerminarLlamadaReq>().motivo)
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        // ============================================================
        //  Modulo J: varios dispositivos por cuenta
        // ============================================================
        //
        // El reparto por dispositivo funciona desde el modulo E: la direccion
        // de una sesion de Signal siempre fue el dispositivo y `destinos` ya
        // devolvia una lista. Aqui solo se dejo de prohibir el segundo.

        get(RUTA_DISPOSITIVOS) {
            call.respond(DispositivosResp(Dispositivos.listar(call.autenticar())))
        }

        // ------------------------------------------------------------------
        //  Modulo N: push
        // ------------------------------------------------------------------

        /*
         * Lo que el cliente necesita para inicializar Firebase.
         *
         * Exige sesion aunque nada de esto sea secreto: sin sesion seria un
         * endpoint que le cuenta a cualquiera con que proyecto de Firebase
         * trabaja esta instalacion, y no hay razon para regalarlo.
         */
        get("$RUTA_PUSH/config") {
            call.autenticar()
            val cfg = Push.configCliente()
            call.respond(
                if (cfg == null) ConfigPush(disponible = false)
                else ConfigPush(
                    disponible = true,
                    proyectoId = Push.proyectoId().orEmpty(),
                    appId = cfg.first,
                    apiKey = cfg.second,
                    remitenteId = cfg.third,
                )
            )
        }

        /*
         * Lo mismo para la version web: la clave publica VAPID con la que el
         * navegador se suscribe. Ver `WebPush`. Con sesion por la misma razon.
         */
        get(RUTA_PUSH_WEB) {
            call.autenticar()
            val clave = WebPush.clavePublica()
            call.respond(ConfigWebPush(disponible = clave != null, clavePublica = clave.orEmpty()))
        }

        /*
         * Registrar el token de ESTE dispositivo.
         *
         * El dispositivo sale de la SESION y no del cuerpo: si se confiara en el
         * cuerpo, cualquiera podria colgarle su token al aparato de otro y
         * recibir el aviso de que esa persona tiene algo. El aviso no lleva
         * contenido, pero saber cuando alguien recibe mensajes ya es bastante.
         */
        put(RUTA_PUSH) {
            val yo = call.autenticar()
            val req = call.receive<RegistrarPushReq>()
            val token = req.token.trim()
            if (token.length !in 10..4096) {
                throw ErrorNegocio(400, "Token de push invalido.")
            }
            when (req.proveedor) {
                PROVEEDOR_FCM -> Unit
                // La version web: el token es la URL del endpoint, y el servidor
                // le va a hacer un POST. Ver `WebPush.endpointValido`: solo los
                // servicios de push conocidos, o seria una SSRF.
                PROVEEDOR_WEBPUSH -> {
                    if (!WebPush.configurado) {
                        throw ErrorNegocio(409, "Este servidor no tiene avisos para el navegador.")
                    }
                    if (!WebPush.endpointValido(token)) {
                        throw ErrorNegocio(400, "Ese endpoint no es de un servicio de push conocido.")
                    }
                }
                else -> throw ErrorNegocio(400, "Proveedor de push no soportado: ${req.proveedor}")
            }
            Repo.guardarTokenPush(yo.dispositivoId, token, req.proveedor)
            call.respond(HttpStatusCode.NoContent)
        }

        /*
         * Darse de baja. Lo llama la app al cerrar sesion.
         *
         * Hace falta de verdad: sin esto, un telefono del que alguien se fue
         * seguiria recibiendo el aviso de que la cuenta tiene algo nuevo, y en
         * un aparato prestado eso es exactamente lo que no debe pasar.
         */
        delete(RUTA_PUSH) {
            val yo = call.autenticar()
            Repo.borrarTokenPush(yo.dispositivoId)
            call.respond(HttpStatusCode.NoContent)
        }

        /*
         * Emitir el codigo. Lo hace el dispositivo PRINCIPAL y con contrasena.
         *
         * La direccion importa: el codigo lo genera quien YA tiene la cuenta y
         * la persona lo teclea en el aparato nuevo. Al reves -que el nuevo pida
         * aprobacion- es el patron de la estafa de WhatsApp Web.
         */
        post("$RUTA_DISPOSITIVOS/codigo") {
            val yo = call.autenticar()
            val req = call.receive<EliminarCuentaReq>()
            call.respond(Dispositivos.emitirCodigo(yo, req.password))
        }

        /*
         * Consumir el codigo. SIN sesion: el aparato nuevo todavia no tiene.
         *
         * Lleva atestacion de hardware como un registro, porque "un hardware,
         * una cuenta" sigue en pie: vincular no es una puerta lateral para meter
         * una segunda cuenta en el mismo telefono.
         */
        post("$RUTA_DISPOSITIVOS/vincular") {
            val (resp, avisos) = Dispositivos.vincular(
                call.receive(), call.ipCliente(), call.request.headers["User-Agent"],
            )
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(resp)
        }

        delete("$RUTA_DISPOSITIVOS/{id}") {
            val yo = call.autenticar()
            val revocado = call.idRuta()
            val avisos = Dispositivos.revocar(yo, revocado)
            Hub.expulsar(revocado)
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_DISPOSITIVOS/{id}/principal") {
            val yo = call.autenticar()
            val req = call.receive<EliminarCuentaReq>()
            Dispositivos.promover(yo, call.idRuta(), req.password)
            call.respond(HttpStatusCode.NoContent)
        }

        /*
         * J.4. Pedir historial.
         *
         * El servidor NO puede responder esto: nunca tuvo el historial. Solo
         * reparte el aviso, porque es el que sabe quien esta conectado. Quien
         * responde manda los mensajes cifrados por el buzon normal.
         */
        post("$RUTA_DISPOSITIVOS/historial") {
            val yo = call.autenticar()
            val (estado, avisos) = Dispositivos.pedirHistorial(yo)
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(estado)
        }

        /* El que reenvio anota que lo hizo, para no repetirlo en cada arranque. */
        post("$RUTA_DISPOSITIVOS/{id}/historial-enviado") {
            val yo = call.autenticar()
            val cuantos = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            Dispositivos.anotarHistorialEnviado(yo, call.idRuta(), cuantos)
            call.respond(HttpStatusCode.NoContent)
        }

        // ============================================================
        //  Modulo I: identidad y cuenta
        // ============================================================
        //
        // El telefono se guarda SOLO como hash y aun asi recupera cuentas: el
        // numero se recibe cada vez que hace falta usarlo. Ver Identidad.kt.
        //
        // El ingreso sigue siendo por username y contrasena: el telefono es un
        // canal de verificacion, no una credencial.

        /*
         * Pedir un codigo. SIN sesion obligatoria a proposito: recuperar una
         * cuenta es justamente el caso en que no se puede entrar. Para verificar
         * un numero si la exige, y eso se comprueba adentro segun el proposito.
         */
        post("$RUTA_CUENTA/codigo") {
            val yo = runCatching { call.autenticar() }.getOrNull()
            call.respond(Identidad.pedirCodigo(sms, yo, call.receive(), call.ipCliente()))
        }

        post("$RUTA_CUENTA/telefono") {
            val yo = call.autenticar()
            call.respond(Identidad.verificarTelefono(yo, call.receive()))
        }

        delete("$RUTA_CUENTA/telefono") {
            Identidad.quitarTelefono(call.autenticar())
            call.respond(HttpStatusCode.NoContent)
        }

        // Sin sesion, como debe ser: quien recupera no puede entrar.
        post("$RUTA_CUENTA/recuperar") {
            Identidad.recuperar(call.receive(), call.ipCliente())
            call.respond(HttpStatusCode.NoContent)
        }

        // El codigo de recuperacion: la unica salida cuando se pierde el
        // telefono. Ver docs/evidencias/codigo-de-recuperacion/.
        //
        // Fijarlo pide sesion -es una decision sobre TU cuenta, desde dentro-;
        // usarlo no puede pedirla, porque el caso entero es no poder entrar.
        put("$RUTA_CUENTA/recuperacion") {
            val yo = call.autenticar()
            Identidad.fijarRecuperacion(yo, call.receive(), call.ipCliente())
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_CUENTA/recuperacion") {
            call.respond(Identidad.estadoRecuperacion(call.autenticar()))
        }

        // Da de alta un telefono NUEVO. Cuatro puertas: SMS, codigo de
        // recuperacion, segundo factor y contrasena nueva. Ver
        // `RecuperarDispositivoReq`.
        //
        // Con el MISMO limitador que el ingreso, y por el mismo motivo: es una
        // ruta sin sesion que, si acierta, entrega una. Sin cupo, un atacante
        // con el numero clonado podria probar codigos del SMS a ritmo de red.
        // El codigo de recuperacion son 256 bits y no se adivina; el del SMS
        // son seis digitos y si.
        post("$RUTA_CUENTA/recuperar-dispositivo") {
            val ip = call.ipCliente()
            val req = call.receive<RecuperarDispositivoReq>()
            val quien = req.username.lowercase().trim()

            Limitador.exigirSinContar(quien, "ingreso_usuario", Limitador.FALLOS_POR_USUARIO)
            Limitador.exigirSinContar(ip, "ingreso_ip", Limitador.FALLOS_POR_IP)

            val resp = try {
                Identidad.recuperarDispositivo(req, ip, call.request.headers["User-Agent"])
            } catch (e: ErrorNegocio) {
                // Cuenta como intento fallido cualquier rechazo de una PUERTA,
                // no los errores de forma (400 por hardware desconocido o
                // contrasena corta): esos los produce un cliente mal hecho, no
                // alguien probando.
                if (e.codigo == 401 || e.codigo == 403) {
                    Limitador.anotarFallo(quien, "ingreso_usuario", Limitador.FALLOS_POR_USUARIO)
                    Limitador.anotarFallo(ip, "ingreso_ip", Limitador.FALLOS_POR_IP)
                }
                throw e
            }
            call.respond(resp)
        }

        get(RUTA_CUENTA) {
            call.respond(Identidad.estado(call.autenticar()))
        }

        put(RUTA_CUENTA) {
            val yo = call.autenticar()
            call.respond(Identidad.ajustar(yo, call.receive()))
        }

        // --- 2FA -----------------------------------------------------

        post("$RUTA_CUENTA/totp") {
            call.respond(Identidad.iniciarTotp(call.autenticar()))
        }

        post("$RUTA_CUENTA/totp/confirmar") {
            val yo = call.autenticar()
            call.respond(Identidad.confirmarTotp(yo, call.receive()))
        }

        delete("$RUTA_CUENTA/totp") {
            val yo = call.autenticar()
            // La contrasena viaja en el cuerpo y no en la URL: un secreto en
            // una query string termina en los registros de cualquier proxy.
            val req = call.receive<EliminarCuentaReq>()
            Identidad.apagarTotp(yo, req.password)
            call.respond(HttpStatusCode.NoContent)
        }

        // --- eliminar la cuenta --------------------------------------

        post("$RUTA_CUENTA/eliminar") {
            val yo = call.autenticar()
            call.respond(Identidad.pedirEliminacion(yo, call.receive()))
        }

        // --- sesiones ------------------------------------------------

        get(RUTA_SESIONES) {
            call.respond(SesionesResp(Identidad.sesiones(call.autenticar())))
        }

        delete("$RUTA_SESIONES/otras") {
            val n = Identidad.cerrarOtras(call.autenticar())
            call.respond(mapOf("cerradas" to n))
        }

        /*
         * Cerrar la sesion propia. Existe para que el cierre de sesion deje de
         * ser solo del lado del cliente: hasta el modulo I, "salir" tiraba el
         * token y el servidor no se enteraba, asi que un token robado seguia
         * sirviendo noventa dias.
         */
        delete(RUTA_SESIONES) {
            Identidad.salir(call.autenticar())
            call.respond(HttpStatusCode.NoContent)
        }

        delete("$RUTA_SESIONES/{id}") {
            val yo = call.autenticar()
            val avisos = Identidad.cerrarSesion(yo, call.idRuta())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        // --- contactos y descubrimiento ------------------------------

        get(RUTA_CONTACTOS) {
            call.respond(ContactosResp(Identidad.contactos(call.autenticar())))
        }

        post(RUTA_CONTACTOS) {
            val yo = call.autenticar()
            call.respond(ContactosResp(Identidad.guardarContacto(yo, call.receive())))
        }

        delete("$RUTA_CONTACTOS/{username}") {
            val yo = call.autenticar()
            call.respond(ContactosResp(Identidad.borrarContacto(yo, call.parameters["username"].orEmpty())))
        }

        /*
         * Descubrir por telefono, como la agenda de WhatsApp. POST y no GET:
         * los numeros van en el cuerpo porque un dato personal en una query
         * string queda en los registros de cualquier proxy intermedio.
         */
        post("$RUTA_CONTACTOS/descubrir") {
            val yo = call.autenticar()
            call.respond(DescubrirResp(Identidad.descubrir(yo, call.receive(), call.ipCliente())))
        }

        // ============================================================
        //  Modulo G: moderacion
        // ============================================================
        //
        // La ruta de denunciar es la SEGUNDA del sistema que recibe contenido
        // en claro, despues de las publicaciones de canal publico. La
        // diferencia importante: este texto no lo descifro el servidor, lo
        // entrego el denunciante. Es la unica forma de moderar lo que el
        // servidor no puede leer. Ver Moderacion.kt y V12__moderacion.sql.

        post("$RUTA_MODERACION/denuncias") {
            val yo = call.autenticar()
            val (creada, avisos) = Moderacion.denunciar(yo, call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(creada)
        }

        // Mi propio estado. Va antes de /{id} y es la unica ruta de este bloque
        // que un suspendido puede usar de verdad: si no pudiera, no tendria
        // como enterarse de POR QUE dejo de poder escribir.
        get("$RUTA_MODERACION/mi-estado") {
            call.respond(Moderacion.miEstado(call.autenticar()))
        }

        post("$RUTA_MODERACION/advertencias/{id}/reconocer") {
            // 404 si no se toco ninguna fila: la advertencia no es tuya o no
            // existe. Antes respondia 204 en los tres casos, o sea que le
            // decia "hecho" a quien no habia hecho nada.
            val filas = Moderacion.reconocer(call.autenticar(), call.idRuta())
            if (filas == 0) throw ErrorNegocio(404, "Esa advertencia no existe.")
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_MODERACION/mis-eventos") {
            val yo = call.autenticar()
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 50
            call.respond(
                EventosSeguridadResp(Db.query { c -> Seguridad.mios(c, yo.usuarioId, limite) })
            )
        }

        // --- la cola, solo staff -------------------------------------

        get("$RUTA_MODERACION/cola") {
            val yo = call.autenticarPanel()
            val q = call.request.queryParameters
            val estado = q["estado"]
            val limite = q["limite"]?.toIntOrNull() ?: 50
            // Cursor: el ID de la ultima denuncia que recibio quien pregunta.
            // La fecha la resuelve el servidor, con la precision real de la
            // columna; ver el comentario de `Moderacion.cola`.
            val despues = q["despues"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            call.respond(ColaModeracion(Moderacion.cola(yo, estado, limite, despues)))
        }

        get("$RUTA_MODERACION/denuncias/{id}") {
            call.respond(Moderacion.detalle(call.autenticarPanel(), call.idRuta()))
        }

        post("$RUTA_MODERACION/denuncias/{id}/tomar") {
            call.respond(Moderacion.tomar(call.autenticarPanel(), call.idRuta()))
        }

        post("$RUTA_MODERACION/denuncias/{id}/resolver") {
            val yo = call.autenticarPanel()
            val (hecho, avisos) = Moderacion.resolver(yo, call.idRuta(), call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(hecho)
        }

        // ============================================================
        //  Modulo H: panel administrativo
        // ============================================================
        //
        // Lo que este panel NO puede hacer es la parte que importa: no puede
        // mostrar mensajes. Ni buscarlos, ni leerlos, ni exportarlos. No es una
        // decision de la interfaz, es que el servidor solo tiene bytes opacos.

        get("$RUTA_PANEL/resumen") {
            call.respond(Panel.resumen(call.autenticarPanel()))
        }

        /*
         * F.7 · La cola de canales y la decision.
         *
         * Vive en el panel y no en /v1/canales porque no es una operacion
         * sobre un canal propio: es gobierno de la plataforma. Exige nivel
         * propietario, y eso lo comprueba `Canales` en cada llamada.
         */
        /*
         * H.6 · Limites ajustables y bitacora. Nivel administrador.
         */
        /*
         * H.3 · Grupos y canales de la plataforma. Nivel administrador.
         *
         * "cerrar" NO borra nada: el servidor no tiene el contenido. Lo que
         * hace es que nadie pueda escribir mas.
         */
        get("$RUTA_PANEL/conversaciones") {
            val yo = call.autenticarPanel()
            call.respond(
                ConversacionesPanel(
                    Panel.conversaciones(
                        yo,
                        call.request.queryParameters["q"],
                        call.request.queryParameters["cerradas"]?.toBoolean() ?: false,
                    )
                )
            )
        }

        post("$RUTA_PANEL/conversaciones/{id}/cerrar") {
            val yo = call.autenticarPanel()
            val avisos = Panel.cerrarConversacion(yo, call.idRuta(), call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_PANEL/conversaciones/{id}/reabrir") {
            val yo = call.autenticarPanel()
            val avisos = Panel.reabrirConversacion(yo, call.idRuta())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        /*
         * L.8 · Emitir y cerrar consolas web.
         *
         * Estas tres van con `autenticar()` -la sesion del telefono- y NO con
         * `autenticarPanel()`: una consola no puede emitir otra consola ni
         * cerrar sus hermanas. La raiz de confianza es el aparato, y que lo
         * siga siendo depende de que este acceso no se pueda encadenar.
         */
        post("$RUTA_PANEL/consola") {
            val yo = call.autenticar()
            val req = call.receive<EmitirConsolaReq>()
            call.respond(Consola.emitir(yo, req.password, req.etiqueta))
        }

        get("$RUTA_PANEL/consola") {
            call.respond(ConsolasAbiertas(Consola.mias(call.autenticar())))
        }

        delete("$RUTA_PANEL/consola/{id}") {
            Consola.revocar(call.autenticar(), call.idRuta())
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_PANEL/limites") {
            call.respond(LimitesPanel(Panel.limites(call.autenticarPanel())))
        }

        put("$RUTA_PANEL/limites/{clave}") {
            val yo = call.autenticarPanel()
            call.respond(
                Panel.ajustarLimite(yo, call.parameters["clave"].orEmpty(), call.receive())
            )
        }

        delete("$RUTA_PANEL/limites/{clave}") {
            Panel.restaurarLimite(call.autenticarPanel(), call.parameters["clave"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_PANEL/bitacora") {
            val yo = call.autenticarPanel()
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 80
            call.respond(
                Bitacora(Panel.bitacora(yo, limite, call.request.queryParameters["q"]))
            )
        }

        get("$RUTA_PANEL/canales") {
            val estado = call.request.queryParameters["estado"] ?: EstadoCanal.PENDIENTE
            call.respond(ColaCanales(Canales.cola(call.autenticarPanel(), estado)))
        }

        post("$RUTA_PANEL/canales/{id}") {
            val yo = call.autenticarPanel()
            val avisos = Canales.revisar(yo, call.idRuta(), call.receive())
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_PANEL/usuarios") {
            val yo = call.autenticarPanel()
            val q = call.request.queryParameters["q"].orEmpty()
            call.respond(UsuariosPanel(Panel.usuarios(yo, q)))
        }

        get("$RUTA_PANEL/usuarios/{username}/eventos") {
            val yo = call.autenticarPanel()
            call.respond(
                EventosSeguridadResp(Panel.eventosDe(yo, call.parameters["username"].orEmpty()))
            )
        }

        post("$RUTA_PANEL/usuarios/{username}/suspender") {
            val yo = call.autenticarPanel()
            call.respond(Panel.suspender(yo, call.parameters["username"].orEmpty(), call.receive()))
        }

        post("$RUTA_PANEL/usuarios/{username}/restaurar") {
            val yo = call.autenticarPanel()
            call.respond(Panel.restaurar(yo, call.parameters["username"].orEmpty()))
        }

        put("$RUTA_PANEL/usuarios/{username}/staff") {
            val yo = call.autenticarPanel()
            call.respond(Panel.staff(yo, call.parameters["username"].orEmpty(), call.receive()))
        }

        // ============================================================
        //  Modulo E: claves publicas para E2EE
        // ============================================================
        //
        // Todo lo que pasa por aqui es material PUBLICO. Ver Claves.kt.

        put(RUTA_CLAVES) {
            val yo = call.autenticar()
            Claves.publicar(yo, call.receive())
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_CLAVES/estado") {
            call.respond(Claves.estado(call.autenticar()))
        }

        // ============================================================
        //  Modulo O: historias
        // ============================================================
        //
        //  Publicar es en DOS pasos y no en uno, por la misma razon que los
        //  mensajes: el contenido va cifrado y el servidor no puede armarlo.
        //
        //    1. `GET /destinos`  -> a quien le toca, con sus claves.
        //    2. el cliente cifra un sobre por aparato y los manda por el buzon.
        //    3. `POST /historias` -> registra el metadato y CONGELA la audiencia.
        //
        //  El orden importa: si se registrara primero, una historia podria
        //  existir sin sobres y quedar visible en la lista de alguien que no
        //  puede abrirla.

        get("$RUTA_HISTORIAS/destinos") {
            call.respond(Historias.destinos(call.autenticar()))
        }

        post(RUTA_HISTORIAS) {
            call.respond(Historias.publicar(call.autenticar(), call.receive()))
        }

        get(RUTA_HISTORIAS) {
            call.respond(Historias.paraMi(call.autenticar()))
        }

        get("$RUTA_HISTORIAS/mias") {
            call.respond(Historias.mias(call.autenticar()))
        }

        post("$RUTA_HISTORIAS/{id}/sobres") {
            val faltan = Historias.encolarSobres(
                call.autenticar(), call.idRuta(), call.receive(),
            )
            call.respond(SobresPendientesResp(faltan))
        }

        post("$RUTA_HISTORIAS/{id}/vista") {
            Historias.marcarVista(call.autenticar(), call.idRuta())
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_HISTORIAS/{id}/vistas") {
            call.respond(Historias.vistas(call.autenticar(), call.idRuta()))
        }

        delete("$RUTA_HISTORIAS/{id}") {
            Historias.retirar(call.autenticar(), call.idRuta())
            call.respond(HttpStatusCode.NoContent)
        }

        // ============================================================
        //  Modulo P: tipos de cuenta y ficha de empresa
        // ============================================================
        //
        // La puerta de la beta se cierra DENTRO de `Cuentas` y no aqui: si
        // estuviera en la ruta, la siguiente ruta que alguien agregue se
        // olvidaria de ponerla. Ver la nota de `Cuentas`.

        get(RUTA_TIPO_CUENTA) {
            call.respond(Cuentas.mias(call.autenticar()))
        }

        put(RUTA_TIPO_CUENTA) {
            call.respond(Cuentas.elegirTipo(call.autenticar(), call.receive()))
        }

        put(RUTA_EMPRESA) {
            call.respond(Cuentas.guardarFicha(call.autenticar(), call.receive()))
        }

        // Lo de staff va bajo /v1/panel porque es del panel, no de mi cuenta.
        put("$RUTA_PANEL/cuentas/tipo") {
            // Autenticar ANTES de leer el cuerpo. Al reves, un anonimo
            // distingue un 400 -"el cuerpo no tiene la forma esperada"- de un
            // 404, y con eso confirma que la ruta existe y deduce su esquema.
            // Es el mismo oraculo que `exigirStaff` evita devolviendo 404.
            val yo = call.autenticar()
            val req: AsignarTipoReq = call.receive()
            call.respond(mapOf("tipo" to Cuentas.asignarTipo(yo, req)))
        }

        put("$RUTA_PANEL/cuentas/{username}/verificar") {
            val yo = call.autenticar()
            val u = call.parameters["username"].orEmpty()
            // El parametro se pasa SIN validar y lo valida `verificarEmpresa`
            // despues de comprobar que quien llama es staff.
            //
            // Validarlo aqui parecia mas limpio y reintrodujo el mismo oraculo
            // que esta ruta acababa de cerrar, un escalon mas arriba: quien no
            // es staff recibia un 400 "falta valor" en vez del 404 que debe
            // recibir, y con esa diferencia confirmaba que la ruta existe.
            // **El control de acceso va primero que la validacion de entrada,
            // siempre.**
            val v = call.request.queryParameters["valor"]?.toBooleanStrictOrNull()
            call.respond(mapOf("verificada" to Cuentas.verificarEmpresa(yo, u, v)))
        }

        get("$RUTA_CLAVES/dispositivo/{id}") {
            call.respond(Claves.paquete(call.autenticar(), call.idRuta()))
        }

        // §3 del brief: las solicitudes de mensaje. La decide quien la
        // recibio; ver `Repo.decidirSolicitud`.
        put("$RUTA_CONVERSACIONES/{id}/solicitud") {
            val yo = call.autenticar()
            val req: DecidirSolicitudReq = call.receive()
            Repo.decidirSolicitud(yo, call.idRuta(), req.aceptar)
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_CONVERSACIONES/{id}/destinos") {
            call.respond(Claves.destinos(call.autenticar(), call.idRuta()))
        }

        // ============================================================
        //  Modulo D.6: buscador de GIFs (intermediario)
        // ============================================================
        //
        // Exige sesion aunque no toque la base: sin autenticar, este servidor
        // seria un proxy abierto para que cualquiera consuma nuestra cuota.

        get("$RUTA_GIFS/buscar") {
            call.autenticar()
            val q = call.request.queryParameters["q"].orEmpty()
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 24
            call.respond(Gifs.buscar(q, limite))
        }

        get("$RUTA_GIFS/{id}/bytes") {
            call.autenticar()
            val id = call.parameters["id"].orEmpty()
            // El id va a parar a una URL con nuestra clave de API detras, asi
            // que se comprueba ANTES de nada. Se responde 400 y no 404: el
            // problema no es que no exista, es que eso no es un id.
            if (!FORMA_GIF_ID.matches(id)) {
                throw ErrorNegocio(400, "Ese identificador de GIF no es valido.")
            }
            val previa = call.request.queryParameters["previa"]?.toBoolean() ?: false
            val datos = Gifs.bytes(id, previa)
                ?: throw ErrorNegocio(404, "No se pudo obtener ese GIF.")
            call.respondBytes(datos, ContentType("image", "gif"))
        }

        // ============================================================
        //  Modulo C: acciones sobre mensajes
        // ============================================================

        post(RUTA_MENSAJES) {
            val yo = call.autenticar()
            // El cupo de mensajes estaba declarado -y ajustable desde el
            // panel- pero ninguna ruta lo aplicaba: el panel prometia un tope
            // que no existia. Por persona y no por aparato, como dice su
            // KDoc. El cliente trata el 429 como espera, no como rechazo.
            Limitador.exigir(yo.usuarioId, yo.usuarioId.toString(), "enviar_mensaje", Limitador.ENVIAR_MENSAJE)
            call.respond(Mensajes.registrar(yo, call.receive()))
        }

        get("$RUTA_MENSAJES/{id}") {
            call.respond(Mensajes.uno(call.autenticar(), call.idRuta()))
        }

        get("$RUTA_MENSAJES/{id}/info") {
            call.respond(Mensajes.info(call.autenticar(), call.idRuta()))
        }

        post("$RUTA_MENSAJES/{id}/abierto") {
            val yo = call.autenticar()
            Mensajes.unaVezAbierta(yo, call.idRuta()).forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_MENSAJES/{id}/retirar") {
            val yo = call.autenticar()
            Mensajes.retirar(yo, call.idRuta()).forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_MENSAJES/{id}/editar") {
            val yo = call.autenticar()
            Mensajes.editar(yo, call.idRuta()).forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_MENSAJES/{id}/fijar") {
            val yo = call.autenticar()
            val fijar = call.receive<FijarReq>().fijar
            Mensajes.fijar(yo, call.idRuta(), fijar).forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_MENSAJES/reaccion") {
            val yo = call.autenticar()
            val (meta, avisos) = Mensajes.reaccionar(yo, call.receive())
            avisos.forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(meta)
        }

        get("$RUTA_CONVERSACIONES/{id}/fijados") {
            call.respond(Mensajes.fijados(call.autenticar(), call.idRuta()))
        }

        put("$RUTA_CONVERSACIONES/{id}/temporales") {
            val yo = call.autenticar()
            val avisos = Mensajes.configurarTemporales(
                yo, call.idRuta(), call.receive<TemporalesReq>().segundos,
            )
            // Fuera de la transaccion, como el resto: el otro lado necesita
            // enterarse para poder CUMPLIR el temporizador -es su cliente el
            // que borra sus mensajes-, no solo para dibujarlo.
            avisos.forEach { (dispositivo, ev) -> Hub.empujar(dispositivo, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        // ============================================================
        //  Modulo B: grupos avanzados
        // ============================================================

        get("$RUTA_CONVERSACIONES/{id}/config") {
            call.respond(Grupos.config(call.autenticar(), call.idRuta()))
        }

        put("$RUTA_CONVERSACIONES/{id}/config") {
            val yo = call.autenticar()
            call.respond(Grupos.guardarConfig(yo, call.idRuta(), call.receive()))
        }

        get("$RUTA_CONVERSACIONES/{id}/miembros") {
            call.respond(Grupos.miembros(call.autenticar(), call.idRuta()))
        }

        post("$RUTA_CONVERSACIONES/{id}/miembros/{usuario}/rol") {
            val yo = call.autenticar()
            val avisos = Grupos.cambiarRol(
                yo, call.idRuta(), call.idObjetivo(), call.receive<CambiarRolReq>().rolClave,
            )
            avisos.forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_CONVERSACIONES/{id}/miembros/{usuario}/expulsar") {
            val yo = call.autenticar()
            val avisos = Grupos.expulsar(yo, call.idRuta(), call.idObjetivo(), call.receive())
            avisos.forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_CONVERSACIONES/{id}/miembros/{usuario}/silenciar") {
            val yo = call.autenticar()
            val avisos = Grupos.silenciar(yo, call.idRuta(), call.idObjetivo(), call.receive())
            avisos.forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("$RUTA_CONVERSACIONES/{id}/miembros/{usuario}/silenciar") {
            val yo = call.autenticar()
            Grupos.quitarSilencio(yo, call.idRuta(), call.idObjetivo())
            call.respond(HttpStatusCode.NoContent)
        }

        // --- roles ---------------------------------------------------

        get("$RUTA_CONVERSACIONES/{id}/roles") {
            call.respond(Grupos.roles(call.autenticar(), call.idRuta()))
        }

        post("$RUTA_CONVERSACIONES/{id}/roles") {
            val yo = call.autenticar()
            call.respond(Grupos.crearRol(yo, call.idRuta(), call.receive()))
        }

        delete("$RUTA_CONVERSACIONES/{id}/roles/{rolId}") {
            val yo = call.autenticar()
            val rolId = runCatching { UUID.fromString(call.parameters["rolId"]) }.getOrNull()
                ?: throw ErrorNegocio(400, "Identificador de rol invalido.")
            Grupos.eliminarRol(yo, call.idRuta(), rolId)
            call.respond(HttpStatusCode.NoContent)
        }

        // --- invitaciones --------------------------------------------

        post("$RUTA_CONVERSACIONES/{id}/invitaciones") {
            val yo = call.autenticar()
            call.respond(Grupos.crearInvitacion(yo, call.idRuta(), call.receive()))
        }

        delete("$RUTA_CONVERSACIONES/{id}/invitaciones/{codigo}") {
            val yo = call.autenticar()
            Grupos.revocarInvitacion(yo, call.idRuta(), call.parameters["codigo"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }

        get("$RUTA_INVITACIONES/{codigo}") {
            call.respond(Grupos.vistaPrevia(call.autenticar(), call.parameters["codigo"].orEmpty()))
        }

        post("$RUTA_INVITACIONES/{codigo}") {
            val yo = call.autenticar()
            val (r, avisos) = Grupos.usarInvitacion(yo, call.parameters["codigo"].orEmpty())
            avisos.forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(r)
        }

        // --- solicitudes ---------------------------------------------

        get("$RUTA_CONVERSACIONES/{id}/solicitudes") {
            call.respond(Grupos.solicitudes(call.autenticar(), call.idRuta()))
        }

        post("$RUTA_CONVERSACIONES/{id}/solicitudes/{usuario}") {
            val yo = call.autenticar()
            val avisos = Grupos.resolverSolicitud(
                yo, call.idRuta(), call.idObjetivo(), call.receive<ResolverSolicitudReq>().aprobar,
            )
            avisos.forEach { (d, ev) -> Hub.empujar(d, ev) }
            call.respond(HttpStatusCode.NoContent)
        }

        // --- preferencias personales ---------------------------------

        put("$RUTA_CONVERSACIONES/{id}/preferencias") {
            val yo = call.autenticar()
            call.respond(Grupos.guardarPreferencias(yo, call.idRuta(), call.receive()))
        }

        // --- bloqueos ------------------------------------------------

        get(RUTA_BLOQUEOS) {
            val yo = call.autenticar()
            call.respond(Db.query { c -> Autz.bloqueados(c, yo.usuarioId) })
        }

        // Bloquear: por la busqueda O por una conversacion en comun. Ver
        // `Autz.conocidoPorNombre`.
        post("$RUTA_BLOQUEOS/{username}") {
            val yo = call.autenticar()
            val nombre = call.parameters["username"].orEmpty()
            val otro = Repo.buscar(yo, nombre)?.let { UUID.fromString(it.usuarioId) }
                ?: Db.query { c -> Autz.conocidoPorNombre(c, yo.usuarioId, nombre) }
                ?: throw ErrorNegocio(404, "No existe ese usuario.")
            Db.tx { c -> Autz.bloquear(c, yo.usuarioId, otro) }
            call.respond(HttpStatusCode.NoContent)
        }

        // Desbloquear: primero entre MIS bloqueos. Ver `Autz.bloqueadoPorNombre`.
        delete("$RUTA_BLOQUEOS/{username}") {
            val yo = call.autenticar()
            val nombre = call.parameters["username"].orEmpty()
            val otro = Db.query { c -> Autz.bloqueadoPorNombre(c, yo.usuarioId, nombre) }
                ?: Repo.buscar(yo, nombre)?.let { UUID.fromString(it.usuarioId) }
                ?: throw ErrorNegocio(404, "No existe ese usuario.")
            Db.tx { c -> Autz.desbloquear(c, yo.usuarioId, otro) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("$RUTA_CONVERSACIONES/{id}/salir") {
            val yo = call.autenticar()
            Repo.salir(yo, call.idRuta())
            call.respond(HttpStatusCode.NoContent)
        }

        webSocket(RUTA_WS) {
            val token = call.request.queryParameters["token"]
            val yo = token?.let { Repo.autenticar(it) }
            if (yo == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "token invalido"))
                return@webSocket
            }
            atender(yo)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.atender(yo: Auth) {
    val salida = Hub.conectar(yo.dispositivoId, yo.usuarioId)
    Repo.tocarDispositivo(yo.dispositivoId)
    // L.1: la presencia se marca al conectar y al desconectar, no en cada
    // mensaje. Una escritura por mensaje seria un coste constante para un dato
    // que solo se mira de vez en cuando.
    Repo.tocarPresencia(yo.usuarioId)
    // Y la sesion. Se marca aqui y no en cada peticion HTTP para no pagar un
    // UPDATE por request: conectar el socket es la senal fuerte de "esta sesion
    // esta viva", que es lo que la lista de sesiones necesita distinguir.
    Repo.tocarSesion(yo.sesionId)
    bitacora.info("@${yo.username} conectado")

    // Bombea el canal de salida hacia el socket. Una sola corrutina escribe,
    // asi que no hay escrituras concurrentes sobre la misma sesion.
    val escritor = launch {
        for (msg in salida) {
            if (!isActive) break
            send(Frame.Text(json.encodeToString(Bajada.serializer(), msg)))
        }
        // El canal se cierra cuando el mismo aparato abre otro socket, o
        // cuando lo revocan. En el segundo caso, el socket tambien se cierra.
        if (Hub.fueExpulsado(yo.dispositivoId)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "dispositivo revocado"))
        }
    }

    try {
        // Al conectar, drena el buzon. Esto es lo que hace que un mensaje
        // enviado mientras estabas sin red aparezca al volver.
        // Los eventos van primero: si te agregaron a un grupo y ademas te
        // escribieron ahi, el aviso tiene que llegar antes que el mensaje.
        Eventos.pendientes(yo.dispositivoId).forEach { salida.trySend(it) }
        Repo.pendientes(yo.dispositivoId).forEach { salida.trySend(it) }

        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val msg = runCatching { json.decodeFromString(Subida.serializer(), frame.readText()) }.getOrNull()
                ?: continue

            when (msg) {
                is Subida.Ping -> salida.trySend(Bajada.Pong)

                is Subida.Acuse -> {
                    // Al confirmar, el sobre se borra y se avisa al remitente.
                    Repo.acusar(yo.dispositivoId, msg.sobreIds).forEach { (origen, sobreId) ->
                        Hub.empujar(origen, Bajada.Entregado(sobreId))
                    }
                }

                is Subida.AcuseEvento -> Eventos.acusar(yo.dispositivoId, msg.eventoIds)

                is Subida.AcuseLectura -> {
                    // El aviso va a los aparatos del REMITENTE, no a la
                    // conversacion entera: en un grupo, que alguien leyera no
                    // le importa a los otros veinte.
                    val conv = runCatching { UUID.fromString(msg.conversacionId) }.getOrNull()
                    if (conv != null) {
                        Repo.anotarLectura(yo, conv, msg.mensajeIds)
                            .forEach { (dispositivo, ids) ->
                                Hub.empujar(
                                    dispositivo,
                                    Bajada.Leido(msg.conversacionId, ids, yo.username),
                                )
                            }
                    }
                }

                is Subida.Escribiendo -> {
                    // Se reenvia y se olvida: ni una fila en la base. Es la
                    // senal mas efimera del sistema -vale tres segundos- y
                    // guardarla seria acumular metadatos de a que hora teclea
                    // cada persona, que es justo lo que este proyecto no
                    // genera.
                    runCatching {
                        Limitador.exigir(
                            yo.usuarioId, yo.usuarioId.toString(),
                            "escribiendo", Limitador.ESCRIBIENDO,
                        )
                        val conv = UUID.fromString(msg.conversacionId)
                        // El ajuste lo comprueba `destinosDeEscritura`: si esta
                        // apagado devuelve la lista vacia y no se reenvia nada.
                        // Ver la nota de esa funcion.
                        Repo.destinosDeEscritura(yo, conv, msg.grabando)
                            .forEach { dispositivo ->
                                Hub.empujar(
                                    dispositivo,
                                    Bajada.Escribiendo(
                                        msg.conversacionId, yo.username, msg.grabando,
                                    ),
                                )
                            }
                    }
                }

                is Subida.Enviar -> manejarEnvio(yo, msg, salida)
            }
        }
    } catch (e: Exception) {
        bitacora.debug("Socket de @${yo.username} termino: ${e.message}")
    } finally {
        escritor.cancel()
        Hub.desconectar(yo.dispositivoId, yo.usuarioId, salida)
        Repo.tocarDispositivo(yo.dispositivoId)
        Repo.tocarPresencia(yo.usuarioId)
        bitacora.info("@${yo.username} desconectado")
    }
}

private fun manejarEnvio(yo: Auth, msg: Subida.Enviar, salida: Channel<Bajada>) {
    val conv = runCatching { UUID.fromString(msg.conversacionId) }.getOrNull()
    val sobreId = runCatching { UUID.fromString(msg.sobreId) }.getOrNull()
    if (conv == null || sobreId == null) {
        salida.trySend(Bajada.ErrorMsg(msg.sobreId, "Identificador invalido.")); return
    }
    if (!Repo.perteneceA(conv, yo.usuarioId)) {
        salida.trySend(Bajada.ErrorMsg(msg.sobreId, "No perteneces a esa conversacion.")); return
    }

    // Los destinos LEGITIMOS los decide el servidor, no el cliente. Una copia
    // dirigida a un dispositivo que no esta en la conversacion se descarta: si
    // no, cualquiera podria usar este canal para meter bytes en el buzon de
    // alguien con quien no comparte nada.
    val permitidos = Repo.destinos(conv, yo.dispositivoId).associateBy { it.dispositivoId }

    // Una copia puede servir a VARIOS destinos: con clave de emisor el mensaje
    // de grupo se cifra una sola vez y esos bytes valen para todos. Se aplana
    // a un par (destino, cuerpo) por fila del buzon, respetando el orden en
    // que vinieron para que el id derivado sea estable entre reintentos.
    val copias = mutableListOf<Repo.Copia>()
    for (cp in msg.copias) {
        val cuerpo = runCatching { Base64Util.dec(cp.cuerpo) }.getOrNull()
        if (cuerpo == null || cuerpo.isEmpty() || cuerpo.size > 65536) {
            salida.trySend(Bajada.ErrorMsg(msg.sobreId, "Cuerpo invalido o demasiado grande.")); return
        }
        for (crudo in cp.destinos) {
            val destinoId = runCatching { UUID.fromString(crudo) }.getOrNull() ?: continue
            val destino = permitidos[destinoId] ?: continue
            copias += Repo.Copia(destino, cuerpo, cp.tipo)
        }
    }

    // Lo que el cliente no cubrio. No se inventa nada: se le dice cuales
    // faltan para que pida los destinos otra vez y complete.
    val cubiertos = copias.map { it.destino.dispositivoId }.toSet()
    val sinCopia = permitidos.keys.filter { it !in cubiertos }.map { it.toString() }

    // La hora que viaja es la de AUTORIA, la del sobre: es la promesa de msg
    // off -escrito sin red a las 8, entregado a las 18, se muestra de las 8-.
    // Ver `msgoff.mjs` y V8.
    //
    // Lo unico que se corrige aqui es el futuro: nadie escribe un mensaje
    // despues de que llega. Un reloj adelantado dejaba ese mensaje pegado al
    // final del chat de quien lo recibia, por debajo de todo lo que se
    // contestara despues. El reloj ATRASADO no se puede distinguir aqui de un
    // mensaje escrito sin red; ese lo corrige el telefono que escribe, con
    // su desfase contra este servidor (`X-Hora` y `Aceptado.servidorEn`).
    val servidorEn = System.currentTimeMillis()
    val creadoEn = minOf(msg.creadoEn, servidorEn)
    val encolados = Repo.encolar(sobreId, yo.dispositivoId, conv, copias, creadoEn)
    salida.trySend(Bajada.Aceptado(msg.sobreId, sinCopia, servidorEn))

    // Empuja a los que estan conectados ahora mismo. Para los demas el sobre ya
    // esta en la base y lo tomaran al reconectar: esto es solo el atajo rapido.
    //
    // Se recorre `copias` en paralelo a `encolados` porque cada destino recibe
    // SU cuerpo y no un cuerpo comun: es el cambio que trae el cifrado punta a
    // punta y la razon por la que este bucle ya no puede reusar `msg.cuerpo`.
    // A quien menciona, una sola consulta por sobre: el metadato se registro
    // antes por HTTP. Ver `Bajada.Entrega.mencionado`.
    val mencionados = runCatching { Repo.mencionados(UUID.fromString(msg.sobreId)) }.getOrDefault(emptySet())
    encolados.forEachIndexed { i, (destino, sobreIdDerivado) ->
        val cp = copias[i]
        Hub.empujar(
            destino.dispositivoId,
            Bajada.Entrega(
                sobreId = sobreIdDerivado.toString(),
                // El id del mensaje es el que genero el cliente, igual para
                // todos los destinos. Ver V26 y `Bajada.Entrega.mensajeId`.
                mensajeId = msg.sobreId,
                conversacionId = msg.conversacionId,
                origenUsuarioId = yo.usuarioId.toString(),
                origenUsername = yo.username,
                origenDispositivo = yo.dispositivoId.toString(),
                creadoEn = creadoEn,
                cuerpo = Base64Util.enc(cp.cuerpo),
                tipo = cp.tipo,
                mencionado = destino.usuarioId in mencionados,
            ),
        )
    }
}

/**
 * De donde viene la peticion. La regla vive en `Seguridad.ipDeCliente`.
 *
 * `remoteHost` puede devolver un NOMBRE y no una IP -detras de `adb reverse`
 * devuelve "localhost"-, asi que se prefiere `remoteAddress` y el nombre queda
 * como ultimo recurso. Quien lo guarde en una columna `inet` tiene que pasarlo
 * igual por `Seguridad.ipValida`.
 */
private fun io.ktor.server.application.ApplicationCall.ipCliente(): String =
    Seguridad.ipDeCliente(
        request.headers["X-Forwarded-For"],
        request.local.remoteAddress.takeIf { it.isNotBlank() } ?: request.local.remoteHost,
    )

/**
 * L.8 · Autenticacion de las rutas del PANEL.
 *
 * Acepta dos cosas: la sesion normal del telefono, o un token de consola web.
 * Y esta separada de `autenticar()` a proposito: si el token de consola valiera
 * en `autenticar()`, valdria en TODAS las rutas -mensajes incluidos- y un token
 * pegado en un navegador podria leer el buzon. Asi, por construccion, un token
 * de consola solo abre lo que esta funcion protege.
 */
private fun io.ktor.server.application.ApplicationCall.autenticarPanel(): Auth {
    val h = request.headers[HttpHeaders.Authorization]
        ?: throw ErrorNegocio(401, "Falta el token.")
    val bruto = h.trim()
    if (bruto.startsWith("Consola ", ignoreCase = true)) {
        val t = bruto.removePrefix("Consola ").removePrefix("consola ").trim()
        return Consola.autenticar(t)
            ?: throw ErrorNegocio(401, "Consola invalida o expirada.")
    }
    return autenticar()
}

private fun io.ktor.server.application.ApplicationCall.autenticar(): Auth {
    val h = request.headers[HttpHeaders.Authorization]
        ?: throw ErrorNegocio(401, "Falta el token de sesion.")
    val token = h.removePrefix("Bearer ").trim()
    return Repo.autenticar(token) ?: throw ErrorNegocio(401, "Sesion invalida o expirada.")
}

/** Id del usuario objetivo en rutas del tipo .../miembros/{usuario}/accion */
private fun io.ktor.server.application.ApplicationCall.idObjetivo(): UUID =
    runCatching { UUID.fromString(parameters["usuario"]) }.getOrNull()
        ?: throw ErrorNegocio(400, "Identificador de usuario invalido.")

private fun io.ktor.server.application.ApplicationCall.idRuta(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrNull()
        ?: throw ErrorNegocio(400, "Identificador invalido.")
