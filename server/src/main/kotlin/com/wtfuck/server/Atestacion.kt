package com.wtfuck.server

import com.wtfuck.protocol.ModoAtestacion
import com.wtfuck.protocol.NivelHardware
import com.wtfuck.protocol.RetoAtestacion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.sql.Connection
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * W5e · Key Attestation de Android, del lado del servidor.
 *
 * Contrato y motivo: `protocol/.../Atestacion.kt` y docs/04-DEVICE-BINDING.md.
 * Lo que aqui se comprueba, en este orden, y por que cada cosa:
 *
 * 1. **La cadena firma hasta una raiz de Google** (`/atestacion/raices.pem`,
 *    bajadas de https://android.googleapis.com/attestation/root). Se compara
 *    la CLAVE PUBLICA de la raiz, no el certificado: Google reemitio la raiz
 *    RSA con la misma clave, y los telefonos viejos traen la emision anterior.
 * 2. **Ningun certificado esta revocado** (lista de estado de Google, en cache).
 *    No es opcional: las claves de atestacion de algunos fabricantes se
 *    filtraron, y con ellas se fabrican cadenas "validas".
 * 3. **El reto es uno que emitio este servidor, vivo y sin usar** -y se quema-.
 *    Sin esto, una cadena capturada una vez serviria para siempre.
 * 4. **Nivel TEE o StrongBox** (`attestationSecurityLevel`), leido de la
 *    cadena: ese, y no el que declara el cliente, es el que se guarda.
 * 5. **La clave nacio dentro** (`origin = GENERATED`), no se importo.
 * 6. **Arranque verificado y bootloader bloqueado** (`rootOfTrust`).
 * 7. **Es NUESTRA app**: el paquete y la huella del certificado con que se firmo
 *    el APK (`attestationApplicationId`). Es lo que deja fuera a un cliente
 *    modificado corriendo en un telefono legitimo.
 *
 * ## Modos (`WTFUCK_ATESTACION`)
 *
 * `registrar` por defecto: verifica, anota el resultado en el aparato y NO
 * rechaza. Es para medir cuantos telefonos reales fallan y por que antes de
 * exigirla -activarla de golpe convierte un defecto de seguridad en uno de
 * disponibilidad-. `exigir` rechaza. `apagada` no mira.
 *
 * ## Lo que no hace
 *
 * No da un identificador del aparato: las claves de atestacion se rotan
 * justamente para que no sirvan de huella. El `hardwareHash` sigue saliendo
 * del SSAID; lo que cambia es que ahora lo manda una app sin modificar en un
 * telefono con arranque verificado.
 */
object Atestacion {

    private val bitacora = LoggerFactory.getLogger("Atestacion")

    val modo: String = System.getenv("WTFUCK_ATESTACION")?.trim()?.lowercase()
        ?.takeIf { it in setOf(ModoAtestacion.APAGADA, ModoAtestacion.REGISTRAR, ModoAtestacion.EXIGIR) }
        ?: ModoAtestacion.REGISTRAR

    const val PAQUETE = "com.wtfuck.app"
    private const val OID_ATESTACION = "1.3.6.1.4.1.11129.2.1.17"
    private val VIDA_RETO: Duration = Duration.ofMinutes(5)

    /**
     * Huellas SHA-256 (hex) de los certificados con que se firma el APK. Sin
     * esto se comprueba el paquete pero no la firma, y el resultado lo dice.
     * `apksigner verify --print-certs app.apk` da la huella.
     */
    private val firmas: Set<String> = System.getenv("WTFUCK_ATESTACION_FIRMAS")
        ?.split(',')?.map { it.trim().lowercase().replace(":", "") }?.filter { it.length == 64 }?.toSet()
        ?: emptySet()

    // ------------------------------------------------------------------
    //  Raices
    // ------------------------------------------------------------------

    private val cf = CertificateFactory.getInstance("X.509")

    /**
     * Las claves publicas de las raices de confianza. Se le pueden sumar otras
     * con `WTFUCK_ATESTACION_RAICES_EXTRA` (ruta a un PEM): SOLO para pruebas,
     * con la raiz del Keystore del emulador. Lo grita en la bitacora.
     */
    private val raices: List<X509Certificate> by lazy {
        val base = Atestacion::class.java.getResourceAsStream("/atestacion/raices.pem")!!.use { leerPem(it.readBytes()) }
        val extra = System.getenv("WTFUCK_ATESTACION_RAICES_EXTRA")?.takeIf { it.isNotBlank() }?.let { ruta ->
            bitacora.warn("WTFUCK_ATESTACION_RAICES_EXTRA: se confia en raices que NO son de Google ($ruta). Solo para pruebas.")
            leerPem(java.io.File(ruta).readBytes())
        } ?: emptyList()
        base + extra
    }

    private fun leerPem(bytes: ByteArray): List<X509Certificate> =
        cf.generateCertificates(ByteArrayInputStream(bytes)).map { it as X509Certificate }

    // ------------------------------------------------------------------
    //  Revocados
    // ------------------------------------------------------------------

    private val urlEstado = System.getenv("WTFUCK_ATESTACION_ESTADO")?.takeIf { it.isNotBlank() }
        ?: "https://android.googleapis.com/attestation/status"
    private data class Estado(val revocados: Set<String>, val cuando: Long)
    private val estado = AtomicReference(Estado(emptySet(), 0))
    private val cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Los numeros de serie revocados, en hex minuscula como los publica Google.
     * Se renueva cada 6 horas; si falla, se sigue con la ultima que se tuvo. Si
     * NUNCA se pudo bajar, devuelve null: no saber no es lo mismo que "nada
     * revocado", y el resultado lo anota.
     */
    private fun revocados(): Set<String>? {
        val e = estado.get()
        if (System.currentTimeMillis() - e.cuando < 6 * 3600_000L) return e.revocados
        return runCatching {
            val resp = cliente.send(
                HttpRequest.newBuilder(URI.create(urlEstado)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            require(resp.statusCode() == 200) { "HTTP ${resp.statusCode()}" }
            val entradas = json.parseToJsonElement(resp.body()).jsonObject["entries"]!!.jsonObject.keys
            Estado(entradas.map { it.lowercase() }.toSet(), System.currentTimeMillis()).also { estado.set(it) }.revocados
        }.getOrElse {
            bitacora.warn("No se pudo bajar la lista de revocados ($urlEstado): ${it.message}")
            if (e.cuando == 0L) null else e.revocados
        }
    }

    // ------------------------------------------------------------------
    //  Retos
    // ------------------------------------------------------------------

    private val azar = SecureRandom()

    fun emitirReto(): RetoAtestacion = Db.tx { c ->
        // Limpieza de paso, de vez en cuando: un reto vencido no sirve para nada.
        if (azar.nextInt(50) == 0) {
            c.prepareStatement("DELETE FROM reto_atestacion WHERE expira_en < now() - interval '1 hour'")
                .use { it.executeUpdate() }
        }
        val reto = ByteArray(32).also { azar.nextBytes(it) }
        c.prepareStatement(
            "INSERT INTO reto_atestacion (reto, expira_en) VALUES (?, now() + make_interval(secs => ?))"
        ).use { st ->
            st.setBytes(1, reto)
            st.setDouble(2, VIDA_RETO.seconds.toDouble())
            st.executeUpdate()
        }
        RetoAtestacion(Base64.getEncoder().encodeToString(reto), VIDA_RETO.seconds.toInt(), modo)
    }

    /** Lo quema. Devuelve si estaba vivo y sin usar. */
    private fun quemarReto(c: Connection, reto: ByteArray): Boolean =
        c.prepareStatement(
            """UPDATE reto_atestacion SET usado_en = now()
                WHERE reto = ? AND usado_en IS NULL AND expira_en > now()"""
        ).use { st -> st.setBytes(1, reto); st.executeUpdate() == 1 }

    // ------------------------------------------------------------------
    //  Verificar
    // ------------------------------------------------------------------

    /** Lo que se leyo de una cadena. `motivo == null` es que paso todo. */
    data class Veredicto(
        val motivo: String?,
        val nivel: String? = null,
        val arranqueVerificado: Boolean = false,
        val bloqueado: Boolean = false,
        val paquete: String? = null,
        val firmaConocida: Boolean? = null,
        val revocacionComprobada: Boolean = false,
    ) {
        val valida get() = motivo == null

        /** Lo que se guarda en `dispositivo.atestacion`. */
        fun resumen(): String = if (valida) {
            "verificada:$nivel" + (if (firmaConocida == null) ",firma-sin-configurar" else "") +
                (if (!revocacionComprobada) ",sin-lista-de-revocados" else "")
        } else {
            "fallida:$motivo"
        }
    }

    /**
     * La cadena entera: firmas, raiz, revocados y extension. Quema el reto si
     * lo encuentra -aunque algo posterior falle-, porque un reto ya presentado
     * no tiene que poder reintentarse con otra cadena.
     */
    fun verificar(c: Connection, cadenaB64: List<String>): Veredicto {
        if (cadenaB64.isEmpty()) return Veredicto("sin-cadena")
        volcar(cadenaB64)
        if (cadenaB64.size > 10) return Veredicto("cadena-demasiado-larga")
        val cadena = runCatching {
            cadenaB64.map { cf.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(it))) as X509Certificate }
        }.getOrElse { return Veredicto("cadena-ilegible") }

        // 1. Cada uno firmado por el siguiente, y el ultimo, por una raiz de Google.
        for (i in 0 until cadena.size - 1) {
            val ok = runCatching { cadena[i].verify(cadena[i + 1].publicKey) }.isSuccess
            if (!ok) return Veredicto("firma-rota-en-$i")
        }
        val ultimo = cadena.last()
        // O la cadena termina EN la raiz (misma clave publica), o termina en un
        // certificado firmado por ella.
        val raiz = raices.any { it.publicKey.encoded.contentEquals(ultimo.publicKey.encoded) } ||
            raices.any { r -> runCatching { ultimo.verify(r.publicKey) }.isSuccess }
        if (!raiz) return Veredicto("raiz-desconocida")
        // La hoja de una atestacion trae fechas que no significan nada (a veces
        // vence en 1970); los intermedios y la raiz si tienen que estar vigentes.
        for (cert in cadena.drop(1)) {
            if (runCatching { cert.checkValidity() }.isFailure) return Veredicto("certificado-vencido")
        }

        // 2. Revocados.
        val lista = revocados()
        if (lista != null) {
            val revocado = cadena.firstOrNull { it.serialNumber.toString(16).lowercase() in lista }
            if (revocado != null) return Veredicto("revocado:${revocado.serialNumber.toString(16)}")
        }

        // La extension viene en la hoja. Algunos telefonos ponen una hoja sin
        // ella (la de la clave) y la atestacion en el certificado siguiente.
        val conExtension = cadena.firstOrNull { it.getExtensionValue(OID_ATESTACION) != null }
            ?: return Veredicto("sin-extension")
        val d = runCatching { Descripcion.leer(Der.octetos(conExtension.getExtensionValue(OID_ATESTACION))) }
            .getOrElse { return Veredicto("extension-ilegible") }

        // 3. El reto.
        if (!quemarReto(c, d.reto)) return Veredicto("reto-desconocido-o-usado")

        // 4 a 7.
        val nivel = when (d.nivelSeguridad) {
            2 -> NivelHardware.STRONGBOX
            1 -> NivelHardware.TEE
            else -> return Veredicto("nivel-software")
        }
        val base = Veredicto(
            motivo = null, nivel = nivel,
            arranqueVerificado = d.raizDeConfianza?.estado == 0,
            bloqueado = d.raizDeConfianza?.bloqueado == true,
            paquete = d.app?.paquetes?.firstOrNull(),
            firmaConocida = if (firmas.isEmpty()) null else d.app?.firmas?.any { it in firmas } == true,
            revocacionComprobada = lista != null,
        )
        return when {
            d.origen != 0 -> base.copy(motivo = "clave-importada")
            d.raizDeConfianza == null -> base.copy(motivo = "sin-raiz-de-confianza")
            d.raizDeConfianza.estado != 0 -> base.copy(motivo = "arranque-no-verificado")
            !d.raizDeConfianza.bloqueado -> base.copy(motivo = "bootloader-desbloqueado")
            d.app == null -> base.copy(motivo = "sin-app")
            PAQUETE !in d.app.paquetes -> base.copy(motivo = "otra-app")
            base.firmaConocida == false -> base.copy(motivo = "firma-desconocida")
            else -> base
        }
    }

    /**
     * SOLO para pruebas: guarda cada cadena recibida en la carpeta de
     * `WTFUCK_ATESTACION_VOLCAR`, para sacar de ahi los fixtures (cadenas de un
     * emulador y de telefonos reales). Una cadena no es un secreto -es publica
     * por diseno- pero no tiene por que quedar en disco en produccion.
     */
    private val carpetaVolcado: java.io.File? = System.getenv("WTFUCK_ATESTACION_VOLCAR")
        ?.takeIf { it.isNotBlank() }?.let { java.io.File(it).apply { mkdirs() } }
        ?.also { bitacora.warn("WTFUCK_ATESTACION_VOLCAR: se guardan las cadenas en $it. Solo para pruebas.") }

    private fun volcar(cadena: List<String>) {
        val dir = carpetaVolcado ?: return
        runCatching {
            java.io.File(dir, "cadena-${System.currentTimeMillis()}.json")
                .writeText(cadena.joinToString(",", "[", "]") { "\"$it\"" })
        }
    }

    // ------------------------------------------------------------------
    //  Aplicarlo a un alta
    // ------------------------------------------------------------------

    /** El nivel con el que queda el aparato, y lo que se anota de la atestacion. */
    data class Evaluacion(val nivel: String, val resultado: String?)

    /**
     * Para un registro, un vinculo o una recuperacion. `nivelDeclarado` ya paso
     * `Repo.nivelPara*`.
     *
     * - Navegador: no hay atestacion posible; sus puertas son otras (correo e
     *   invitacion). Se anota `no-aplica`.
     * - `SOFTWARE_DEV` (emulador con el interruptor abierto): se anota, no se
     *   exige. En produccion ese nivel ya lo rechazo `nivelDeTelefono`.
     * - Telefono: se verifica. En `exigir`, sin cadena valida, 403; el nivel que
     *   queda es el LEIDO de la cadena. En `registrar`, se anota y se queda el
     *   declarado.
     */
    fun evaluar(c: Connection, cadena: List<String>, nivelDeclarado: String): Evaluacion {
        if (modo == ModoAtestacion.APAGADA) return Evaluacion(nivelDeclarado, null)
        if (nivelDeclarado == NivelHardware.NAVEGADOR) return Evaluacion(nivelDeclarado, "no-aplica")
        val v = verificar(c, cadena)
        if (nivelDeclarado == NivelHardware.SOFTWARE_DEV) return Evaluacion(nivelDeclarado, v.resumen())
        if (modo == ModoAtestacion.EXIGIR) {
            if (!v.valida) {
                bitacora.info("Atestacion rechazada: {}", v.motivo)
                throw ErrorNegocio(
                    403,
                    "No se pudo comprobar que este telefono y esta app sean genuinos (${v.motivo}). " +
                        "Actualiza la app; si el telefono tiene el bootloader desbloqueado o un sistema modificado, " +
                        "no se puede registrar.",
                )
            }
            return Evaluacion(v.nivel!!, v.resumen())
        }
        return Evaluacion(nivelDeclarado, v.resumen())
    }

    /** Lo anota en el aparato recien creado o recuperado. */
    fun anotar(c: Connection, dispositivoId: java.util.UUID, e: Evaluacion) {
        if (e.resultado == null) return
        c.prepareStatement(
            "UPDATE dispositivo SET atestacion = ?, atestacion_en = now() WHERE id = ?"
        ).use { st ->
            st.setString(1, e.resultado.take(200))
            st.setObject(2, dispositivoId)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    //  La extension (KeyDescription)
    // ------------------------------------------------------------------

    data class RaizDeConfianza(val bloqueado: Boolean, val estado: Int)
    data class App(val paquetes: List<String>, val firmas: List<String>)

    /**
     * Lo que importa de `KeyDescription`
     * (https://source.android.com/docs/security/features/keystore/attestation).
     *
     * ```
     * KeyDescription ::= SEQUENCE {
     *   attestationVersion INTEGER, attestationSecurityLevel ENUMERATED,
     *   keyMintVersion INTEGER, keyMintSecurityLevel ENUMERATED,
     *   attestationChallenge OCTET STRING, uniqueId OCTET STRING,
     *   softwareEnforced AuthorizationList, hardwareEnforced AuthorizationList }
     * ```
     *
     * De las AuthorizationList, por etiqueta: `origin` [702], `rootOfTrust`
     * [704] y `attestationApplicationId` [709]. Se buscan en las DOS listas: el
     * fabricante decide en cual van, y `attestationApplicationId` suele ir en
     * la de software.
     */
    data class Descripcion(
        val version: Int,
        val nivelSeguridad: Int,
        val reto: ByteArray,
        val origen: Int?,
        val raizDeConfianza: RaizDeConfianza?,
        val app: App?,
    ) {
        companion object {
            fun leer(bytes: ByteArray): Descripcion {
                val seq = Der(bytes).leer().hijos()
                require(seq.size >= 8) { "KeyDescription incompleta" }
                val software = seq[6].hijos()
                val hardware = seq[7].hijos()
                fun etiqueta(n: Int) = hardware.firstOrNull { it.contexto == n } ?: software.firstOrNull { it.contexto == n }

                val origen = etiqueta(702)?.hijos()?.firstOrNull()?.entero()
                val raiz = etiqueta(704)?.hijos()?.firstOrNull()?.hijos()?.let {
                    RaizDeConfianza(bloqueado = it[1].booleano(), estado = it[2].entero())
                }
                val app = etiqueta(709)?.hijos()?.firstOrNull()?.let { octeto ->
                    val partes = Der(octeto.valor).leer().hijos()
                    App(
                        paquetes = partes[0].hijos().map { String(it.hijos()[0].valor) },
                        firmas = partes[1].hijos().map { it.valor.joinToString("") { b -> "%02x".format(b) } },
                    )
                }
                return Descripcion(
                    version = seq[0].entero(),
                    nivelSeguridad = seq[1].entero(),
                    reto = seq[4].valor,
                    origen = origen,
                    raizDeConfianza = raiz,
                    app = app,
                )
            }
        }
    }
}

/**
 * Un lector DER minimo, lo justo para la extension de atestacion.
 *
 * El JDK trae uno (`sun.security.util.DerValue`), pero es interno y no se
 * puede usar sin abrir modulos; una libreria de ASN.1 entera para leer cuatro
 * campos seria mas superficie que esto. Soporta etiquetas de numero alto (las
 * de la AuthorizationList pasan de 30) y largos de forma larga. Todo lo que no
 * cuadra lanza: la extension se da por ilegible y la atestacion falla.
 */
class Der(private val b: ByteArray, private var i: Int = 0, private val fin: Int = b.size) {

    class Nodo(val clase: Int, val construido: Boolean, val numero: Int, val valor: ByteArray) {
        /** El numero si es una etiqueta de contexto ([n]), si no -1. */
        val contexto: Int get() = if (clase == 2) numero else -1

        fun hijos(): List<Nodo> {
            require(construido) { "no es construido" }
            val d = Der(valor)
            val l = mutableListOf<Nodo>()
            while (d.quedan()) l += d.leer()
            return l
        }

        fun entero(): Int {
            require(valor.isNotEmpty() && valor.size <= 8) { "entero invalido" }
            return BigInteger(valor).toInt()
        }

        fun booleano(): Boolean {
            require(valor.size == 1) { "booleano invalido" }
            return valor[0] != 0.toByte()
        }
    }

    fun quedan() = i < fin

    fun leer(): Nodo {
        require(i < fin) { "fin inesperado" }
        val t = b[i++].toInt() and 0xFF
        val clase = t shr 6
        val construido = (t and 0x20) != 0
        var numero = t and 0x1F
        if (numero == 0x1F) {
            numero = 0
            var n = 0
            do {
                require(i < fin && n++ < 4) { "etiqueta invalida" }
                val x = b[i++].toInt() and 0xFF
                numero = (numero shl 7) or (x and 0x7F)
            } while (x and 0x80 != 0)
        }
        require(i < fin) { "fin inesperado" }
        var largo = b[i++].toInt() and 0xFF
        if (largo and 0x80 != 0) {
            val n = largo and 0x7F
            require(n in 1..3 && i + n <= fin) { "largo invalido" }
            largo = 0
            repeat(n) { largo = (largo shl 8) or (b[i++].toInt() and 0xFF) }
        }
        require(largo >= 0 && i + largo <= fin) { "largo fuera de rango" }
        val valor = b.copyOfRange(i, i + largo)
        i += largo
        return Nodo(clase, construido, numero, valor)
    }

    companion object {
        /** `getExtensionValue` devuelve el valor envuelto en un OCTET STRING. */
        fun octetos(envuelto: ByteArray): ByteArray {
            val n = Der(envuelto).leer()
            require(n.clase == 0 && n.numero == 4) { "no es OCTET STRING" }
            return n.valor
        }
    }
}

/** La huella SHA-256 en hex, para la bitacora y las pruebas. */
internal fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
