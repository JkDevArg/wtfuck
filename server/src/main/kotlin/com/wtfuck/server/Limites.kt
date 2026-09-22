package com.wtfuck.server

import com.wtfuck.protocol.EventoSeguridad
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Limites de ritmo.
 *
 * Hay dos limitadores y no uno, porque hay dos problemas distintos:
 *
 *  - [Limitador]: en memoria, ventana deslizante. Para rafagas. Reiniciar el
 *    servidor perdona la rafaga en curso, y eso esta bien: el castigo dura
 *    segundos, y quien espera un reinicio para mandar tres mensajes mas no es
 *    un problema.
 *
 *  - [Cupos]: en la base, ventanas redondeadas. Para lo que se cuenta por hora
 *    o por dia. Aqui reiniciar NO debe perdonar: si el limite de denuncias
 *    diarias se olvidara en cada despliegue, bastaria con esperar uno.
 *
 * Meter todo en la base seria una escritura extra en cada mensaje enviado.
 * Meter todo en memoria regalaria los limites largos en cada reinicio. La
 * division es por la duracion del limite, no por su importancia.
 */

// ============================================================
//  En memoria: rafagas
// ============================================================

data class Regla(val cuantas: Int, val ventana: Duration)

object Limitador {

    /**
     * Los numeros.
     *
     * Estan altos a proposito para lo que una persona hace de verdad: 30
     * mensajes por minuto es imposible de sostener escribiendo, y trivial para
     * un script. Un limite que molesta al usuario normal se acaba subiendo
     * hasta que deja de servir.
     */
    val ENVIAR_MENSAJE get() = efectiva("enviar_mensaje", Regla(30, Duration.ofMinutes(1)))

    /**
     * Avisos de "estoy escribiendo".
     *
     * El cliente manda uno cada cuatro segundos como maximo, asi que 40 por
     * minuto cubre diez conversaciones a la vez. El limite no esta por carga
     * -no toca la base- sino porque es la senal mas facil de usar como
     * zumbido: sin tope, un cliente modificado puede hacer aparecer y
     * desaparecer "escribiendo..." treinta veces por segundo en el telefono de
     * otra persona.
     */
    val ESCRIBIENDO get() = efectiva("escribiendo", Regla(40, Duration.ofMinutes(1)))
    val CREAR_GRUPO get() = efectiva("crear_grupo", Regla(10, Duration.ofMinutes(10)))
    val CREAR_CANAL get() = efectiva("crear_canal", Regla(20, Duration.ofHours(1)))
    val PUBLICAR_CANAL get() = efectiva("publicar_canal", Regla(20, Duration.ofMinutes(10)))
    val BUSCAR get() = efectiva("buscar", Regla(60, Duration.ofMinutes(1)))

    /**
     * Guardar la ficha de empresa. **La rafaga, no el uso sostenido.**
     *
     * La ficha es lo unico que una cuenta escribe y otra gente lee como dato
     * de la plataforma, y cada guardado deja una fila de auditoria y retira la
     * verificacion. Sin ningun tope, un script la reescribe a la velocidad de
     * la red.
     *
     * 30 en diez minutos parece mucho para un limite de abuso, y lo es a
     * proposito: **este limite no es el que protege del abuso**. Lo que
     * protege de la rotacion sostenida es [Cupos.FICHAS_POR_DIA], que vive en
     * la base. Este de aqui solo corta la rafaga, y por eso puede ser holgado:
     * llenar un formulario de siete campos y guardarlo varias veces mientras
     * se corrigen erratas es lo normal, y un tope que salta al tercer guardado
     * convierte corregir una coma en un error.
     */
    val FICHA_EMPRESA get() = efectiva("ficha_empresa", Regla(30, Duration.ofMinutes(10)))

    /**
     * Cambiar el tipo de cuenta propio (`normal` <-> `empresa`).
     *
     * Presupuesto **aparte** del de la ficha, y la separacion importa: si
     * compartieran cuenta, agotar el de la ficha impediria volver a cuenta
     * personal, que es justamente como uno se quita la ficha de encima. Un
     * limite que bloquea la salida no es un limite, es una trampa.
     *
     * No lleva cupo diario porque no hay nada publico que rotar —es una
     * columna con tres valores—: lo unico rotable es la ficha, y escribirla
     * sigue costando su propio cupo.
     *
     * **20 por hora, y el numero empezo en 10.** La ventana de una hora es
     * larga para un limitador de rafagas, y quien esta decidiendo de verdad
     * hace varias vueltas dentro de ella: declararse empresa, llenar la ficha,
     * mirarla desde otra cuenta, volver a personal, probar otra vez. Quien
     * desarrolla contra esta API hace exactamente eso. El dato que lo dejo
     * claro es que `pruebas/cuentas.mjs` consume **9** cambios de tipo en una
     * sola pasada haciendo uso legitimo del modulo: con 10, el margen era de
     * uno, y la primera prueba que alguien anadiera habria fallado con un 429
     * que no se parece en nada a su causa.
     *
     * Entre 10 y 20 no hay diferencia de seguridad —las dos cifras dicen "no
     * sos un script"— y si la hay de usabilidad, asi que gana la holgura.
     */
    val TIPO_CUENTA get() = efectiva("tipo_cuenta", Regla(20, Duration.ofHours(1)))
    val SUBIR_ARCHIVO get() = efectiva("subir_archivo", Regla(40, Duration.ofMinutes(5)))

    /**
     * Ingreso: se limitan los FALLOS, no los intentos, y con dos reglas.
     *
     * La primera version contaba intentos por IP, 10 cada 15 minutos, y estaba
     * mal por dos motivos distintos:
     *
     *  1. Detras de un NAT -un campus, una oficina- comparten IP cientos de
     *     personas. Diez ingresos cada quince minutos dejaba afuera a una
     *     facultad entera por usar bien la app.
     *  2. Contar los ingresos CORRECTOS no protege de nada. Quien prueba
     *     contrasenas falla; el que acierta no necesita reintentar.
     *
     * Por eso ahora son dos reglas sobre fallos:
     *
     *  - [FALLOS_POR_USUARIO] protege UNA cuenta de que le prueben claves.
     *    Es la estricta, porque nadie se equivoca ocho veces en su propia
     *    contrasena en quince minutos.
     *  - [FALLOS_POR_IP] atrapa al que rocia muchos usuarios desde un sitio.
     *    Es holgada a proposito: tiene que tolerar el NAT.
     */
    val FALLOS_POR_USUARIO get() = efectiva("fallos_por_usuario", Regla(8, Duration.ofMinutes(15)))
    val FALLOS_POR_IP get() = efectiva("fallos_por_ip", Regla(50, Duration.ofMinutes(15)))

    /**
     * Pedir un codigo por SMS. Dos reglas, y la division importa.
     *
     * Cada peticion manda un mensaje de verdad a una persona de verdad y
     * **cuesta dinero**, asi que el limite no es por carga: sin el, la ruta es
     * un generador de SMS gratis para quien la encuentre.
     *
     * Pero el limite fuerte tiene que ser **por destino**, no por IP. Cinco
     * codigos a cinco personas distintas desde una red compartida es un martes
     * normal; cinco codigos al MISMO numero es acosar a alguien con mensajes.
     * La primera version tenia 10/hora por IP y era el mismo error que ya se
     * habia cometido con el ingreso: detras de un NAT deja afuera a todo un
     * edificio. Lo destapo la regresion, otra vez.
     *
     * El 10 por destino sale de mirar las dos puntas. Contra el abuso no cambia
     * casi nada: la espera de 60 segundos entre codigos -que vive en la base y
     * no en memoria- ya pone el techo de flujo, asi que la diferencia entre 5 y
     * 10 mensajes por hora no es lo que salva a nadie de ser molestado. Para el
     * usuario legitimo si cambia: verificar un numero equivocandose al teclear,
     * mas recuperar la cuenta, mas un SMS que no llego, se come cinco intentos
     * sin hacer nada raro.
     */
    val PEDIR_CODIGO_DESTINO get() = efectiva("pedir_codigo_destino", Regla(10, Duration.ofHours(1)))
    val PEDIR_CODIGO_IP get() = efectiva("pedir_codigo_ip", Regla(60, Duration.ofHours(1)))

    /**
     * Descubrir por telefono. Es la unica ruta que responde "esta persona
     * existe", asi que es la que hay que acotar.
     *
     * 20 peticiones de hasta 500 numeros por hora son 10 000 telefonos: de
     * sobra para subir una agenda de verdad -las agendas grandes tienen unos
     * cientos- y nada frente a los mil millones que habria que probar para
     * enumerar. Bajarlo mas romperia el caso legitimo sin mejorar el otro.
     */
    val DESCUBRIR get() = efectiva("descubrir", Regla(20, Duration.ofHours(1)))

    /**
     * Emitir un codigo de vinculacion.
     *
     * **Tiene que ser >= `Dispositivos.MAX_DISPOSITIVOS`**, y esa relacion no
     * es opcional: la primera version tenia 5 aqui y 8 de tope, asi que llegar
     * al tope era imposible en una sola sesion. Dos constantes que se
     * contradicen, y el sintoma habria sido un 429 inexplicable a mitad de
     * configurar los aparatos. Lo destapo la prueba del tope.
     *
     * 12 = los 8 del tope mas margen para los que se teclean mal. Emitir ya
     * exige la contrasena y el segundo factor, asi que este limite es higiene
     * contra el abuso, no la defensa principal.
     */
    val EMITIR_VINCULACION get() = efectiva("emitir_vinculacion", Regla(12, Duration.ofHours(1)))

    /**
     * Iniciar una llamada.
     *
     * Esto no es un limite de carga: una llamada **hace sonar el telefono de
     * otra persona**. Sin tope, colgar y volver a llamar veinte veces es una
     * forma de acoso que no necesita ninguna habilidad tecnica, y el ajuste de
     * privacidad no protege de quien ya esta en tus contactos.
     *
     * 20 en 10 minutos deja margen para el caso real -se corto, vuelvo a
     * llamar, no me oye, cuelgo y llamo- y corta el uso como timbre.
     */
    val INICIAR_LLAMADA get() = efectiva("iniciar_llamada", Regla(20, Duration.ofMinutes(10)))

    /**
     * Consumir un codigo, por IP.
     *
     * ## Este limite NO es la defensa, y confundirlo salio caro
     *
     * La primera version puso 20/hora pensando "este es el que frena la fuerza
     * bruta". No lo es. Lo que hace imposible adivinar un codigo son otras dos
     * cosas, y las dos son por CODIGO y no por IP:
     *
     *   * ocho caracteres de un alfabeto de 31 son 8.5e11 combinaciones;
     *   * cada codigo admite 5 intentos y vive 5 minutos.
     *
     * Con eso, un atacante tiene cinco tiros contra 8.5e11 antes de que el
     * codigo muera. El limite por IP no mueve esa cuenta.
     *
     * Y contarlo bajo por IP repite el error del NAT que ya se cometio dos
     * veces en este proyecto -en el ingreso y en los codigos por SMS-: detras
     * de una red compartida, veinte vinculaciones por hora son de toda la
     * oficina, no de una persona. Lo destapo la regresion, otra vez la misma
     * senal.
     *
     * Queda alto, como higiene contra un bucle descontrolado, y no como
     * defensa: la defensa vive en el codigo mismo.
     */
    val CONSUMIR_VINCULACION get() = efectiva("consumir_vinculacion", Regla(100, Duration.ofHours(1)))

    // ==================================================================
    //  H.6 · Los limites se pueden ajustar sin recompilar
    // ==================================================================
    //
    // ## Como encaja sin tocar ni un punto de uso
    //
    // Cada regla de arriba dejo de ser un valor y paso a ser un `get()`: el
    // numero por defecto sigue escrito ahi -con su explicacion, que es lo que
    // de verdad importa- y `efectiva` decide si hay algo en la base que lo
    // sobreescriba. Las rutas siguen escribiendo `Limitador.ENVIAR_MENSAJE` y
    // no saben que esto existe.
    //
    // ## Por que hay cache y de cuanto
    //
    // `exigir` corre en CADA peticion. Ir a la base ahi convertiria el
    // limitador -que existe para que la plataforma aguante- en una consulta
    // mas por mensaje, justo cuando hay carga. Con 30 segundos de cache, un
    // cambio tarda medio minuto en verse en toda la plataforma y el camino
    // normal no toca la base nunca. Un cambio hecho desde el panel invalida la
    // cache de ese proceso al instante; medio minuto es el techo para los
    // demas.
    //
    // ## Por que los defectos viven en el codigo y no en una fila
    //
    // Para que una base vacia se comporte exactamente como antes, y para que
    // BORRAR una fila sea volver al valor probado en vez de quedarse sin
    // limite. Un limite que se puede apagar borrando una fila es un limite que
    // alguien apaga.

    private val overrides = ConcurrentHashMap<String, Regla>()

    @Volatile private var overridesCargados = 0L
    private val REFRESCO_MS = 30_000L

    /**
     * Cuando esta puesto, `efectiva` devuelve el valor del codigo y NO mira la
     * base ni la cache.
     *
     * Existe por un defecto real y silencioso: `porDefecto` leia el getter
     * para obtener el valor de fabrica, el getter llamaba a `efectiva`, y
     * `efectiva` refrescaba la cache. Como `ajustarLimite` llama a
     * `porDefecto` **dentro de su transaccion**, ese refresco consultaba la
     * base con el INSERT todavia sin confirmar: leia el estado ANTERIOR y lo
     * dejaba cacheado 30 segundos. El sintoma era el peor posible - la
     * pantalla guardaba el limite nuevo, contestaba "guardado", y el limitador
     * seguia aplicando el viejo medio minuto-. Lo destapo la prueba que
     * comprueba el limite en la ruta de verdad en vez de creerle a la
     * respuesta.
     *
     * Es un ThreadLocal y no un booleano suelto porque hay una peticion por
     * hilo: un flag global lo apagaria para todos los demas.
     */
    private val leyendoDefecto = ThreadLocal.withInitial { false }

    /** El valor que manda: lo de la base si hay, y si no el del codigo. */
    fun efectiva(clave: String, porDefecto: Regla): Regla {
        if (leyendoDefecto.get()) return porDefecto
        refrescarSiToca()
        return overrides[clave] ?: porDefecto
    }

    private fun refrescarSiToca() {
        val ahora = System.currentTimeMillis()
        if (ahora - overridesCargados < REFRESCO_MS) return
        // Se marca ANTES de consultar: si la consulta falla, no se reintenta en
        // cada peticion. Un fallo de base no puede convertirse en una tormenta
        // de consultas que empeore lo que ya esta mal.
        overridesCargados = ahora
        runCatching {
            val nuevos = Db.query { c ->
                c.prepareStatement("SELECT clave, tope, ventana_s FROM limite_config").use { st ->
                    st.executeQuery().use { rs ->
                        rs.mapear { it.getString(1) to Regla(it.getInt(2), Duration.ofSeconds(it.getLong(3))) }
                    }
                }
            }
            overrides.keys.retainAll(nuevos.map { it.first }.toSet())
            nuevos.forEach { (k, r) -> overrides[k] = r }
        }
    }

    /** Lo llama el panel al cambiar algo: el cambio se ve sin esperar. */
    fun invalidarCache() {
        overridesCargados = 0L
    }

    /**
     * Los limites que se pueden ajustar, con su nombre para la pantalla.
     *
     * La lista esta escrita a mano a proposito: no todos los limites del
     * sistema deberian ser ajustables desde una pantalla, y descubrirlos por
     * reflexion convertiria cualquier constante nueva en una palanca publica
     * sin que nadie lo decida.
     */
    val AJUSTABLES: List<Ajustable> = listOf(
        Ajustable("enviar_mensaje", "Enviar mensajes", "Por persona") { ENVIAR_MENSAJE },
        Ajustable(
            "escribiendo", "Avisos de \"escribiendo\"",
            "No toca la base; el tope evita usarlo como zumbido",
        ) { ESCRIBIENDO },
        Ajustable("crear_grupo", "Crear grupos", "Por persona") { CREAR_GRUPO },
        Ajustable("crear_canal", "Crear canales", "Por persona") { CREAR_CANAL },
        Ajustable("publicar_canal", "Publicar en canales", "Por persona") { PUBLICAR_CANAL },
        Ajustable("buscar", "Buscar", "Por persona") { BUSCAR },
        Ajustable("subir_archivo", "Subir archivos", "Por persona") { SUBIR_ARCHIVO },
        Ajustable(
            "fallos_por_usuario", "Ingresos fallidos por cuenta",
            "Protege una cuenta de que le prueben claves",
        ) { FALLOS_POR_USUARIO },
        Ajustable(
            "fallos_por_ip", "Ingresos fallidos por IP",
            "Holgado a proposito: detras de un NAT comparten IP muchas personas",
        ) { FALLOS_POR_IP },
        Ajustable(
            "pedir_codigo_destino", "Codigos SMS por numero",
            "Cada envio cuesta dinero y molesta a una persona real",
        ) { PEDIR_CODIGO_DESTINO },
        Ajustable("pedir_codigo_ip", "Codigos SMS por IP", "Por red") { PEDIR_CODIGO_IP },
        Ajustable(
            "descubrir", "Descubrir por telefono",
            "La unica ruta que responde si alguien existe",
        ) { DESCUBRIR },
        Ajustable(
            "emitir_vinculacion", "Emitir codigos de vinculacion",
            "Tiene que ser mayor o igual que el tope de dispositivos",
        ) { EMITIR_VINCULACION },
        Ajustable(
            "iniciar_llamada", "Iniciar llamadas",
            "Una llamada hace sonar el telefono de otra persona",
        ) { INICIAR_LLAMADA },
        Ajustable(
            "consumir_vinculacion", "Consumir codigos de vinculacion",
            "Higiene, no defensa: la defensa son los 5 intentos por codigo",
        ) { CONSUMIR_VINCULACION },
        Ajustable(
            "ficha_empresa", "Guardar la ficha de empresa",
            "Solo la rafaga; la rotacion sostenida la corta el cupo diario",
        ) { FICHA_EMPRESA },
        Ajustable(
            "tipo_cuenta", "Cambiar el tipo de cuenta propio",
            "Presupuesto aparte del de la ficha, para no bloquear la salida",
        ) { TIPO_CUENTA },
    )

    class Ajustable(
        val clave: String,
        val etiqueta: String,
        val detalle: String,
        /**
         * El valor por defecto se obtiene LLAMANDO al getter con la cache
         * vacia, no guardando una copia: asi no hay dos fuentes de verdad que
         * se puedan desincronizar cuando alguien cambie el numero del codigo.
         */
        val leer: () -> Regla,
    )

    /**
     * El valor de fabrica de una clave, ignorando la base y sin tocar la cache.
     *
     * Lo segundo es lo importante: ver un valor por defecto no puede tener
     * efectos. Ver [leyendoDefecto].
     */
    fun porDefecto(clave: String): Regla? {
        val a = AJUSTABLES.firstOrNull { it.clave == clave } ?: return null
        leyendoDefecto.set(true)
        return try {
            a.leer()
        } finally {
            leyendoDefecto.set(false)
        }
    }

    // Una marca de tiempo por intento. Se guardan solo las de la ventana viva,
    // asi que la lista de un usuario normal tiene un pun~ado de elementos.
    private val marcas = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /**
     * ¿Puede hacerlo? Cuenta el intento si la respuesta es si.
     *
     * Devuelve los segundos que faltan para poder reintentar, o null si paso.
     * Devolver el tiempo y no solo un booleano es lo que permite responder
     * "espera 12 segundos" en vez de "no", que no le dice nada a nadie.
     */
    fun intentar(clave: String, accion: String, r: Regla): Long? {
        val k = "$accion:$clave"
        val ahora = System.currentTimeMillis()
        val desde = ahora - r.ventana.toMillis()

        val cola = marcas.computeIfAbsent(k) { ArrayDeque() }
        synchronized(cola) {
            while (cola.isNotEmpty() && cola.first() < desde) cola.removeFirst()
            if (cola.size >= r.cuantas) {
                val libera = cola.first() + r.ventana.toMillis()
                return ((libera - ahora) / 1000).coerceAtLeast(1)
            }
            cola.addLast(ahora)
            return null
        }
    }

    /**
     * Version que lanza 429. Es la que usan las rutas.
     *
     * Abre su propia conexion para anotar, y solo cuando el limite salta: el
     * camino normal no toca la base, que es justamente el motivo de tener el
     * contador en memoria. La primera version recibia la conexion de la ruta y
     * como casi ninguna ruta tenia una abierta todavia, se le pasaba null y no
     * se anotaba NADA: el panel contaba cero limites excedidos para siempre.
     *
     * `usuarioId` puede ser null: en el ingreso todavia no hay usuario, y lo
     * unico que se sabe es la IP. Anotarlo sin usuario sigue sirviendo, porque
     * lo que interesa es cuantas veces salto el limite.
     */
    fun exigir(usuarioId: UUID?, clave: String, accion: String, r: Regla) {
        val espera = intentar(clave, accion, r) ?: return

        // Una rafaga aislada es un dedo pesado; la misma rafaga cien veces al
        // dia es otra cosa. Sin registro, las dos se ven igual.
        runCatching {
            Db.tx { c ->
                Seguridad.anotar(
                    c, usuarioId, "limite_excedido",
                    detalle = """{"accion":"$accion","espera_s":$espera}""",
                )
            }
        }
        throw ErrorNegocio(429, "Vas muy rapido. Intenta de nuevo en $espera segundos.")
    }

    /**
     * Mira si queda cupo SIN consumirlo.
     *
     * Hace falta para los limites que cuentan fallos: antes de intentar hay
     * que saber si esta bloqueado, pero el intento solo cuenta si sale mal.
     */
    fun exigirSinContar(clave: String, accion: String, r: Regla) {
        val cola = marcas["$accion:$clave"] ?: return
        val desde = System.currentTimeMillis() - r.ventana.toMillis()
        val vivos = synchronized(cola) { cola.count { it >= desde } }
        if (vivos >= r.cuantas) {
            throw ErrorNegocio(429, "Demasiados intentos fallidos. Espera unos minutos.")
        }
    }

    /** Anota un fallo. No lanza: el error que lo provoco ya se esta lanzando. */
    fun anotarFallo(clave: String, accion: String, r: Regla) {
        intentar(clave, accion, r)
    }

    /** Solo para las pruebas: vuelve a cero. */
    fun olvidarTodo() = marcas.clear()

    /** Cuantos intentos quedan en la ventana. Solo informativo. */
    fun restantes(clave: String, accion: String, r: Regla): Int {
        val cola = marcas["$accion:$clave"] ?: return r.cuantas
        val desde = System.currentTimeMillis() - r.ventana.toMillis()
        synchronized(cola) { return (r.cuantas - cola.count { it >= desde }).coerceAtLeast(0) }
    }
}

// ============================================================
//  En la base: cupos largos
// ============================================================

object Cupos {

    /**
     * Denuncias por dia.
     *
     * Existe por un motivo concreto: denunciar es un arma. Sin cupo, una
     * persona puede abrir cincuenta denuncias contra alguien en una tarde y
     * ahogar la cola de revision hasta que nadie mire ninguna. El cupo no
     * protege al denunciado, protege a la cola.
     */
    const val DENUNCIAS_POR_DIA = 20

    /**
     * Guardados de la ficha de empresa por dia.
     *
     * Este es **el limite que importa** de los dos que tiene la ficha, y el
     * motivo no es la carga: es que rotar el nombre comercial es una forma de
     * evadir la moderacion. Se denuncia una ficha que dice "Banco Nacional",
     * y cuando el moderador abre el caso la ficha dice otra cosa. El cupo pone
     * un techo a cuantas veces al dia puede cambiar el dato que otros leen.
     *
     * Va en la base y no en memoria por la misma razon que las denuncias: un
     * limite diario que se olvida en cada despliegue se evade esperando uno.
     *
     * 40 al dia deja de sobra para montar la ficha, corregirla y retocarla
     * durante semanas; lo que no deja es rotarla como mecanica.
     */
    const val FICHAS_POR_DIA = 40

    /**
     * Suma uno y devuelve el total de la ventana. Una sola ida a la base:
     * `ON CONFLICT ... RETURNING` hace el insert-o-incrementa y responde con el
     * valor ya actualizado, asi que no hay hueco entre leer y escribir por el
     * que se cuelen dos peticiones a la vez.
     */
    fun sumar(c: Connection, usuarioId: UUID, accion: String, ventana: Duration): Int {
        val inicio = redondear(Instant.now(), ventana)
        return c.prepareStatement(
            """INSERT INTO contador_uso (usuario_id, accion, ventana, n)
               VALUES (?, ?, ?, 1)
               ON CONFLICT (usuario_id, accion, ventana)
                 DO UPDATE SET n = contador_uso.n + 1
               RETURNING n"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setString(2, accion)
            st.setObject(3, java.sql.Timestamp.from(inicio))
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } ?: 1 }
        }
    }

    fun exigir(c: Connection, usuarioId: UUID, accion: String, tope: Int, ventana: Duration) {
        val n = sumar(c, usuarioId, accion, ventana)
        if (n > tope) {
            Seguridad.anotar(
                c, usuarioId, "limite_excedido",
                detalle = """{"accion":"$accion","tope":$tope,"ventana_h":${ventana.toHours()}}""",
            )
            throw ErrorNegocio(429, "Alcanzaste el limite de $tope por ${etiqueta(ventana)}.")
        }
    }

    /** Barre ventanas que ya no le importan a nadie. */
    fun barrer(c: Connection, masViejasQue: Duration = Duration.ofDays(2)): Int =
        c.prepareStatement("DELETE FROM contador_uso WHERE ventana < ?").use { st ->
            st.setObject(1, java.sql.Timestamp.from(Instant.now().minus(masViejasQue)))
            st.executeUpdate()
        }

    /**
     * Redondea al inicio de la ventana.
     *
     * Ventanas fijas y no deslizantes: una ventana deslizante exigiria guardar
     * cada intento, y para un limite diario eso son miles de filas por usuario
     * para responder una pregunta que un contador responde igual. El precio es
     * que justo en el cambio de ventana caben dos cupos seguidos; para un
     * limite de 20 denuncias diarias, da igual.
     */
    private fun redondear(t: Instant, ventana: Duration): Instant {
        val s = ventana.seconds.coerceAtLeast(1)
        return Instant.ofEpochSecond(t.epochSecond / s * s)
    }

    private fun etiqueta(v: Duration) = when {
        v.toHours() >= 24 -> "dia"
        v.toHours() >= 1 -> "hora"
        else -> "minuto"
    }
}

// ============================================================
//  Registro de eventos de seguridad
// ============================================================

object Seguridad {

    /**
     * Anota algo que le paso a una cuenta.
     *
     * ## El `runCatching` a secas era una trampa, y costo encontrarla
     *
     * La idea era razonable: un fallo al registrar no deberia tumbar la
     * operacion que se estaba registrando. La implementacion estaba mal, y de
     * una forma que no se ve leyendo el codigo.
     *
     * En Postgres, **cuando una sentencia falla dentro de una transaccion, la
     * transaccion entera queda abortada**. Todo lo que venga despues falla, y un
     * `commit()` sobre una transaccion abortada se convierte en un ROLLBACK
     * silencioso. Asi que atrapar la excepcion aqui no protegia la operacion:
     * la BORRABA, y encima sin ruido. La ruta devolvia 200, el usuario veia su
     * codigo de verificacion en pantalla, y en la base no habia ninguna fila.
     *
     * La forma correcta de tener un efecto opcional dentro de una transaccion
     * ajena es un SAVEPOINT: si esto falla, se deshace SOLO esto y la
     * transaccion sigue viva. Es literalmente para lo que existen.
     */
    fun anotar(
        c: Connection,
        usuarioId: UUID?,
        tipo: String,
        ip: String? = null,
        agente: String? = null,
        detalle: String? = null,
    ) {
        // Si la conexion no esta en transaccion, un savepoint no aplica ni hace
        // falta: un fallo solo afecta a esta sentencia.
        val enTransaccion = runCatching { !c.autoCommit }.getOrDefault(false)
        val punto = if (enTransaccion) runCatching { c.setSavepoint("seguridad") }.getOrNull() else null

        try {
            c.prepareStatement(
                """INSERT INTO evento_seguridad (usuario_id, tipo, ip, agente, detalle)
                   VALUES (?, ?, ?::inet, ?, ?::jsonb)"""
            ).use { st ->
                st.setObject(1, usuarioId)
                st.setString(2, tipo)
                st.setString(3, ipValida(ip))
                st.setString(4, agente?.take(200))
                st.setString(5, detalle)
                st.executeUpdate()
            }
            punto?.let { runCatching { c.releaseSavepoint(it) } }
        } catch (e: Exception) {
            // Se deshace solo esta insercion. La transaccion de afuera sigue.
            if (punto != null) runCatching { c.rollback(punto) }
            org.slf4j.LoggerFactory.getLogger("Seguridad")
                .warn("No se pudo anotar el evento {}: {}", tipo, e.message)
        }
    }

    /**
     * Solo una IP literal, nunca un nombre.
     *
     * `'localhost'::inet` **es un error en Postgres**, no un null. Y
     * `remoteHost` de Ktor puede devolver justamente un nombre: detras de
     * `adb reverse` devolvia "localhost", asi que todo lo que pasaba por el
     * emulador rompia la insercion. Con el savepoint ya no seria grave, pero
     * validar aqui evita el ruido en la bitacora y el trabajo perdido.
     *
     * Se valida con una forma textual y no resolviendo el nombre: resolver
     * seria una consulta de DNS dentro de una transaccion de base de datos.
     */
    fun ipValida(crudo: String?): String? {
        val s = crudo?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" } ?: return null
        val esIpv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(s) &&
            s.split('.').all { (it.toIntOrNull() ?: 256) <= 255 }
        // IPv6: hexadecimal y dos puntos, incluida la forma comprimida "::1".
        val esIpv6 = s.contains(':') && Regex("""^[0-9a-fA-F:.]+$""").matches(s)
        return if (esIpv4 || esIpv6) s else null
    }

    /**
     * Lo que le paso a MI cuenta. Solo el dueno lo pide, y por eso no incluye
     * nada de moderacion: quien te denuncio no es un evento de tu cuenta, es
     * una decision sobre ti que vive en `auditoria`.
     *
     * La IP se lee con `host()` y no con `::text`: Postgres normaliza un `inet`
     * a `10.0.0.7/32`, y la mascara de un host suelto es ruido para quien esta
     * mirando desde donde entraron a su cuenta.
     */
    fun mios(c: Connection, usuarioId: UUID, limite: Int = 50): List<EventoSeguridad> =
        c.prepareStatement(
            """SELECT tipo, host(ip), agente, detalle::text,
                      (EXTRACT(EPOCH FROM creado_en) * 1000)::bigint
               FROM evento_seguridad
               WHERE usuario_id = ?
               ORDER BY creado_en DESC
               LIMIT ?"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setInt(2, limite.coerceIn(1, 200))
            st.executeQuery().use { rs ->
                rs.mapear {
                    EventoSeguridad(
                        tipo = it.getString(1),
                        ip = it.getString(2),
                        agente = it.getString(3),
                        detalle = it.getString(4),
                        cuando = it.getLong(5),
                    )
                }
            }
        }
}
