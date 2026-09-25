package com.wtfuck.server

import com.wtfuck.protocol.Bajada
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Modulo N · Lo que hace falta para levantar una SEGUNDA instancia.
 *
 * ## El problema, en una frase
 *
 * Los sockets viven en la memoria del proceso. Con una instancia eso es
 * correcto y es lo mas rapido que hay. Con dos, un mensaje para alguien
 * conectado a la instancia B **no llega** si lo manda alguien conectado a la A:
 * el `Hub` de A mira su mapa, no lo encuentra, y deja el sobre en la base. La
 * persona lo recibe recien al reconectar, que puede ser horas.
 *
 * Estuvo declarado como pendiente desde el modulo 0 y con razon: no hacia falta
 * hasta que hubiera una segunda instancia. Esto es lo que hace que la haya.
 *
 * ## Lo que se publica, y lo que NO
 *
 * Se publica el `Bajada` **tal como iba a salir por el socket**, que para un
 * mensaje es el sobre ya cifrado. Redis mueve los mismos bytes opacos que movia
 * Postgres: no se agrega un sitio donde el contenido este en claro. Quien
 * administre el Redis ve lo mismo que quien administre la base, que es nada.
 *
 * ## Presencia: por que con vencimiento y no con un booleano
 *
 * Es la trampa que el comentario del `Hub` ya nombraba: una marca de "esta
 * conectado" que un proceso deja puesta al morir se queda en `true` para
 * siempre, y entonces la plataforma miente justo sobre el dato que la gente usa
 * para decidir si la estan ignorando.
 *
 * Asi que la marca **vence**: cada instancia renueva las de sus propios sockets
 * cada [REFRESCO_S] segundos y duran [VIDA_S]. Si una instancia muere, sus
 * marcas se apagan solas en menos de un minuto y medio. Es peor que instantaneo
 * y muchisimo mejor que para siempre, y el limite esta dicho.
 *
 * ## Lo que se FIRMA, y por que hizo falta
 *
 * Un `Bajada.Evento` viaja en claro y el cliente lo **obedece**:
 * `mensaje_retirado` borra un mensaje, `expulsado` te marca fuera de un grupo,
 * `dispositivo_revocado` y `sancion` cambian el estado de la cuenta. Eso es
 * correcto —el servidor es quien tiene autoridad para decir esas cosas— pero
 * convierte a este bus en un **camino de entrada**: cualquiera que pueda
 * publicar en `wtfuck:disp:<uuid>` podria inyectarle a un cliente conectado un
 * evento que no ocurrio. Y el id de un dispositivo no es secreto: se lo dan a
 * cualquier participante de la conversacion para poder cifrarle.
 *
 * La version inicial de este archivo no lo tenia en cuenta y publicaba
 * `instancia|json` sin mas. Ahora cada mensaje lleva un **HMAC-SHA256** con un
 * secreto compartido por las instancias, y lo que no verifica se descarta.
 *
 * Lo que esto arregla y lo que no, dicho:
 *
 *  - **Arregla la inyeccion**, que es la mitad grave: sin el secreto no se
 *    puede fabricar un evento que un cliente acepte.
 *  - **No oculta los metadatos.** Quien lea el Redis ve que el dispositivo X
 *    recibio algo a tal hora. El contenido sigue siendo el sobre cifrado, que
 *    no puede abrir, pero el trafico se ve.
 *  - **No impide la denegacion.** Quien escriba en ese Redis puede borrar
 *    claves de presencia. Por eso el Redis va en red privada y con contrasena;
 *    el HMAC es defensa en profundidad, no el unico control.
 *
 * Se falla **cerrado**: con `WTFUCK_REDIS_URL` y sin `WTFUCK_BUS_SECRETO` el
 * bus no arranca y lo dice. Un bus a medio autenticar es peor que ninguno,
 * porque se despliega creyendo que esta protegido.
 *
 * ## Sin configurar
 *
 * `activo = false` y todo es un no-op: exactamente el comportamiento de una sola
 * instancia, que es el caso normal. No hay que levantar Redis para usar wtfuck.
 */
object Bus {

    private val bitacora = LoggerFactory.getLogger("bus")

    private val url = System.getenv("WTFUCK_REDIS_URL")?.takeIf { it.isNotBlank() }

    /** Quien soy, para no procesar lo que yo mismo publique. */
    private val yo = UUID.randomUUID().toString()

    /**
     * El secreto que comparten las instancias para firmar lo que ponen en el
     * bus. Obligatorio si hay `WTFUCK_REDIS_URL`.
     */
    private val secreto = System.getenv("WTFUCK_BUS_SECRETO")?.takeIf { it.isNotBlank() }

    private var cliente: RedisClient? = null
    private var mandos: StatefulRedisConnection<String, String>? = null
    private var suscripcion: StatefulRedisPubSubConnection<String, String>? = null

    val activo: Boolean get() = mandos != null

    /** Segundos que vive una marca de presencia sin renovarse. */
    private const val VIDA_S = 90L

    /** Cada cuanto se renuevan. Un tercio de la vida: tolera perder un turno. */
    const val REFRESCO_S = 30L

    private fun canal(dispositivo: UUID) = "wtfuck:disp:$dispositivo"
    private fun clave(dispositivo: UUID) = "wtfuck:vivo:$dispositivo"

    /**
     * Presencia por PERSONA, y es un conjunto y no una clave.
     *
     * La diferencia importa: lo que la app muestra es "esta persona esta en
     * linea", no "este aparato". Con multi-dispositivo la misma cuenta puede
     * tener el telefono en una instancia y la tablet en otra, y una clave con
     * un solo valor haria que la segunda en escribir borrara la marca de la
     * primera al desconectarse.
     *
     * El conjunto guarda IDS DE INSTANCIA, no de dispositivo: a la pregunta
     * "hay alguna instancia sosteniendo un socket de esta persona" se responde
     * con `SCARD > 0`. Cada instancia pone y saca su propio id.
     *
     * El vencimiento es del conjunto entero y lo renueva cualquier instancia
     * que siga teniendo sockets de esa persona. Si una instancia muere, su id
     * queda de mas en el conjunto, pero la respuesta sigue siendo correcta:
     * hay OTRA viva que lo renueva y que de verdad tiene el socket. Y si mueren
     * todas, nadie renueva y el conjunto se borra solo.
     */
    private fun clavePersona(usuario: UUID) = "wtfuck:vivos_u:$usuario"

    // ============================================================
    //  Ventana deslizante compartida (modulo AP)
    // ============================================================

    /**
     * Cuenta marcas en una ventana de tiempo, **entre todas las instancias**.
     *
     * ## Por que no alcanzaba la de memoria
     *
     * [Limitador] guarda sus marcas en un mapa del proceso, y eso esta bien
     * para lo que fue pensado: una rafaga de mensajes. El propio archivo lo
     * argumenta —"reiniciar perdona la rafaga en curso, y eso esta bien: el
     * castigo dura segundos"—, y para mensajes es cierto.
     *
     * Para PROBAR CONTRASENAS no lo es, por dos motivos que no se parecen:
     *
     *  1. **Se multiplica por instancia.** Esta arquitectura guarda las
     *     sesiones en Redis justamente para poder correr varias copias detras
     *     de un balanceador. Con cuatro copias, "8 fallos cada 15 minutos" son
     *     32, y nada en el codigo lo dice: el numero del limite deja de ser el
     *     limite.
     *  2. **Un reinicio perdona la ventana entera.** Para tres mensajes de mas
     *     da igual; para un ataque de diccionario, un despliegue es un indulto.
     *
     * El propio `Limites.kt` dice que la division es por la DURACION del
     * limite y no por su importancia. Este es el caso donde la importancia
     * manda: la ventana es corta, pero lo que protege no.
     *
     * ## Por que un conjunto ordenado y no un INCR
     *
     * Porque `INCR` + `EXPIRE` es una ventana FIJA, y la de memoria es
     * deslizante. Un limite que se comporta distinto en desarrollo y en
     * produccion es un limite sobre el que nadie puede razonar. Con marcas de
     * tiempo como puntaje, la semantica es exactamente la misma en los dos
     * lados; son tres comandos en vez de uno, sobre unas pocas decenas de
     * elementos.
     *
     * @param contar `false` mira sin consumir — hace falta para los limites de
     *   fallos, donde primero se comprueba y solo se anota si sale mal.
     * @return cuantas marcas hay dentro de la ventana, o `null` si no hay
     *   Redis: entonces quien llama usa el limitador de memoria, que en una
     *   sola instancia es exacto.
     */
    fun marcasEnVentana(clave: String, ventanaMs: Long, contar: Boolean): Long? {
        val m = mandos ?: return null
        val k = "wtfuck:lim:$clave"
        val ahora = System.currentTimeMillis()
        return runCatching {
            val c = m.sync()
            // Primero se tira lo viejo. Si no, el conjunto crece para siempre
            // y el recuento cuenta intentos de anteayer.
            c.zremrangebyscore(
                k,
                io.lettuce.core.Range.create(
                    Double.NEGATIVE_INFINITY, (ahora - ventanaMs).toDouble(),
                ),
            )
            if (contar) {
                // El miembro lleva un azar ademas del instante: dos fallos en
                // el mismo milisegundo son DOS, y con solo el instante de
                // miembro el segundo pisaria al primero sin contarse.
                c.zadd(k, ahora.toDouble(), "$ahora:${UUID.randomUUID()}")
            }
            // El vencimiento se renueva siempre, tambien al solo mirar: una
            // clave sin TTL que nadie vuelve a tocar se queda en Redis para
            // siempre.
            c.pexpire(k, ventanaMs)
            c.zcard(k)
        }.getOrNull()
    }

    /**
     * Arranca si hay URL. Si no hay, se dice una vez y se sigue.
     *
     * No se reintenta la conexion en bucle: si Redis esta configurado y no
     * responde al arrancar, es un problema de despliegue y tiene que verse en el
     * log de arranque, no aparecer a las tres horas.
     */
    fun iniciar(alRecibir: (UUID, Bajada) -> Unit) {
        val u = url
        if (u == null) {
            bitacora.info("Sin WTFUCK_REDIS_URL: una sola instancia. Ver Bus.kt")
            return
        }
        if (secreto == null) {
            // Fallar cerrado. Un bus sin firmar acepta cualquier cosa que
            // llegue por Redis, incluidos eventos que el cliente obedece; y lo
            // peor de arrancar igual seria desplegarlo creyendo que esta
            // protegido.
            bitacora.error(
                "WTFUCK_REDIS_URL esta puesto pero falta WTFUCK_BUS_SECRETO: " +
                    "el bus NO arranca. Sin firmar, cualquiera que alcance el " +
                    "Redis puede inyectar eventos a un cliente conectado."
            )
            return
        }
        runCatching {
            val c = RedisClient.create(u)
            mandos = c.connect()
            val sub = c.connectPubSub()
            sub.addListener(object : RedisPubSubListener<String, String> {
                override fun message(canal: String, mensaje: String) {
                    // Formato: `instancia|firma|json`.
                    //
                    // La instancia va delante para descartar lo propio sin
                    // deserializar, y la firma **antes** del cuerpo para poder
                    // rechazar sin haber parseado nada de lo que mando un
                    // desconocido. Deserializar primero y verificar despues es
                    // el orden que convierte un parser en superficie de ataque.
                    val partes = mensaje.split('|', limit = 3)
                    if (partes.size != 3) return
                    val (origen, firma, cuerpo) = partes
                    if (origen == yo) return

                    if (!firmaValida(origen, cuerpo, firma)) {
                        // No se dice el contenido: si alguien esta probando
                        // inyecciones, el log no tiene por que guardarle los
                        // intentos enteros.
                        bitacora.warn("Mensaje del bus con firma invalida en $canal: se descarta")
                        return
                    }

                    val disp = runCatching {
                        UUID.fromString(canal.removePrefix("wtfuck:disp:"))
                    }.getOrNull() ?: return
                    val bajada = runCatching {
                        json.decodeFromString(Bajada.serializer(), cuerpo)
                    }.getOrNull() ?: return
                    alRecibir(disp, bajada)
                }

                override fun message(patron: String, canal: String, mensaje: String) =
                    message(canal, mensaje)

                override fun subscribed(canal: String, cuenta: Long) = Unit
                override fun psubscribed(patron: String, cuenta: Long) = Unit
                override fun unsubscribed(canal: String, cuenta: Long) = Unit
                override fun punsubscribed(patron: String, cuenta: Long) = Unit
            })
            suscripcion = sub
            cliente = c
            bitacora.info("Bus de instancias listo. Instancia $yo")
        }.onFailure {
            bitacora.error("No se pudo conectar a Redis ($u): ${it.message}")
            mandos = null
            suscripcion = null
        }
    }

    /** Este proceso se hace cargo de ese dispositivo. */
    fun registrar(dispositivo: UUID, usuario: UUID) {
        val m = mandos ?: return
        runCatching {
            suscripcion?.sync()?.subscribe(canal(dispositivo))
            val c = m.sync()
            c.setex(clave(dispositivo), VIDA_S, yo)
            c.sadd(clavePersona(usuario), yo)
            c.expire(clavePersona(usuario), VIDA_S)
        }.onFailure { bitacora.warn("No se pudo registrar $dispositivo en el bus: ${it.message}") }
    }

    /**
     * Ya no. Se borra la marca en vez de esperar a que venza.
     *
     * `quedanDeEsaPersona` lo decide el Hub, que es el que sabe si a este
     * proceso le queda otro aparato de la misma cuenta: soltar el id de la
     * instancia mientras todavia sostiene la tablet dejaria a la persona como
     * desconectada teniendo un socket abierto aqui mismo.
     */
    fun olvidar(dispositivo: UUID, usuario: UUID, quedanDeEsaPersona: Boolean) {
        val m = mandos ?: return
        runCatching {
            suscripcion?.sync()?.unsubscribe(canal(dispositivo))
            val c = m.sync()
            // Solo si la marca es MIA: si en el medio la persona se reconecto a
            // otra instancia, borrarla la dejaria como desconectada teniendo el
            // socket abierto en otro sitio.
            if (c.get(clave(dispositivo)) == yo) c.del(clave(dispositivo))
            if (!quedanDeEsaPersona) c.srem(clavePersona(usuario), yo)
        }.onFailure { bitacora.warn("No se pudo soltar $dispositivo del bus: ${it.message}") }
    }

    /** Renueva las marcas de los sockets que este proceso sostiene. */
    fun renovar(dispositivos: Collection<UUID>, usuarios: Collection<UUID>) {
        val m = mandos ?: return
        if (dispositivos.isEmpty() && usuarios.isEmpty()) return
        runCatching {
            val c = m.sync()
            dispositivos.forEach { c.setex(clave(it), VIDA_S, yo) }
            usuarios.forEach {
                c.sadd(clavePersona(it), yo)
                c.expire(clavePersona(it), VIDA_S)
            }
        }.onFailure { bitacora.warn("No se pudieron renovar las marcas: ${it.message}") }
    }

    /**
     * Le pasa el mensaje a la instancia que tenga ese socket.
     *
     * Devuelve `true` solo si habia alguien suscrito. `false` significa "ese
     * aparato no esta conectado en ninguna instancia", y entonces el sobre se
     * queda en la base y se intenta el push, igual que antes.
     */
    fun publicar(dispositivo: UUID, msg: Bajada): Boolean {
        val m = mandos ?: return false
        return runCatching {
            val cuerpo = json.encodeToString(Bajada.serializer(), msg)
            val sobre = "$yo|${firmar(yo, cuerpo)}|$cuerpo"
            (m.sync().publish(canal(dispositivo), sobre) ?: 0L) > 0L
        }.getOrElse {
            bitacora.warn("No se pudo publicar para $dispositivo: ${it.message}")
            false
        }
    }

    /** Si ese aparato tiene socket abierto en CUALQUIER instancia. */
    fun enLinea(dispositivo: UUID): Boolean {
        val m = mandos ?: return false
        return runCatching { m.sync().exists(clave(dispositivo)) > 0 }.getOrDefault(false)
    }

    /**
     * Si esa PERSONA tiene algun aparato conectado en cualquier instancia.
     *
     * Es la que usa la app para el "en linea" de la cabecera del chat, asi que
     * es la que de verdad se nota: sin esto, con dos instancias cada una veia
     * como desconectado a todo el que estuviera en la otra.
     */
    fun conectado(usuario: UUID): Boolean {
        val m = mandos ?: return false
        return runCatching { (m.sync().scard(clavePersona(usuario)) ?: 0L) > 0L }
            .getOrDefault(false)
    }

    /**
     * HMAC-SHA256 sobre `origen|cuerpo`.
     *
     * El origen entra en la firma a proposito: sin el, una instancia podria
     * tomar un mensaje legitimo de otra y republicarlo cambiando el remitente,
     * que es como se sortea el "descarta lo tuyo" para provocar un rebote.
     */
    private fun firmar(origen: String, cuerpo: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secreto!!.toByteArray(), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal("$origen|$cuerpo".toByteArray()))
    }

    /**
     * Comparacion en **tiempo constante**.
     *
     * Un `==` de Strings corta en el primer byte distinto, y eso deja medir
     * cuantos bytes acerto quien prueba. Con un atacante que puede publicar en
     * el bus tantas veces como quiera, medir es suficiente para construir la
     * firma byte a byte.
     */
    private fun firmaValida(origen: String, cuerpo: String, firma: String): Boolean =
        runCatching {
            MessageDigest.isEqual(
                Base64.getDecoder().decode(firma),
                Base64.getDecoder().decode(firmar(origen, cuerpo)),
            )
        }.getOrDefault(false)

    fun cerrar() {
        runCatching { suscripcion?.close() }
        runCatching { mandos?.close() }
        runCatching { cliente?.shutdown() }
    }
}
