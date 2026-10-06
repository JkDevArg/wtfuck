package com.wtfuck.app.datos

import android.util.Base64
import android.util.Log
import com.wtfuck.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * E2EE real, con libsignal (modulo E.2).
 *
 * Esto es lo que convierte la promesa del proyecto en algo comprobable: desde
 * aqui, el cuerpo que llega a `sobre_pendiente` es ruido para el servidor y
 * para cualquiera que lea esa tabla.
 *
 * **Sesiones por pares, tambien en grupos.** Un mensaje a un grupo de diez se
 * cifra diez veces, una por dispositivo. Es lo que hacia Signal antes de las
 * Sender Keys y sigue haciendo en grupos chicos: cuesta N cifrados de unos
 * cientos de bytes, y a cambio no hay clave compartida que haya que rotar cada
 * vez que alguien sale del grupo. Las Sender Keys son una optimizacion de
 * ESCALA, no de seguridad, y entran despues (E.4) cuando haya con que medir si
 * hacen falta.
 *
 * **La direccion criptografica es el DISPOSITIVO, no la persona.** El `name`
 * del SignalProtocolAddress es el id del dispositivo y el deviceId siempre 1.
 * Suena raro viniendo de Signal, donde el name es la persona, pero aqui es lo
 * correcto: la regla del proyecto es un usuario por dispositivo, los ids de
 * dispositivo son UUID y no enteros, y de este modo el dia que haya varios
 * dispositivos por persona (modulo J) cada uno ya es una sesion aparte sin
 * tocar nada.
 */
class CifradorSignal(
    private val almacen: AlmacenSignal,
    private val dao: SignalDao,
    private val api: ApiCliente,
    private val sesion: Sesion,
) : Cifrador {

    private val TAG = "CifradorSignal"

    override val etiqueta = "Cifrado de extremo a extremo"

    /**
     * Serializa el acceso a libsignal.
     *
     * El Double Ratchet es estado mutable: cifrar avanza la cadena y guarda la
     * sesion. Dos corrutinas cifrando a la vez sobre la misma sesion la
     * corrompen, y el sintoma seria mensajes que el otro lado no puede abrir
     * nunca mas. Un mutex es barato comparado con eso.
     */
    private val candado = Mutex()

    private fun dir(dispositivoId: String) = SignalProtocolAddress(dispositivoId, 1)

    /**
     * Se pregunta por el DISPOSITIVO y no por la persona.
     *
     * Una sesion de Signal es entre dos aparatos, no entre dos cuentas: la
     * misma persona con telefono y tablet son dos sesiones. Preguntarlo por
     * usuario daria "si" para alguien con quien se hablo desde otro aparato,
     * y por ese hueco entraria justo lo que la regla quiere dejar fuera.
     */
    override suspend fun haySesionCon(usuarioId: String, dispositivoId: String): Boolean =
        runCatching { almacen.containsSession(dir(dispositivoId)) }.getOrDefault(false)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun deB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ------------------------------------------------------------------
    //  Publicacion de claves (E.1)
    // ------------------------------------------------------------------

    /**
     * Publica el juego de claves si hace falta, y repone las de un solo uso.
     *
     * Se llama al arrancar con sesion. Reponer ANTES de quedarse en cero es lo
     * que evita el caso molesto: quien se queda sin prekeys unicas sigue
     * recibiendo mensajes, pero las sesiones nuevas pierden algo de garantia
     * hacia adelante.
     */
    override suspend fun prepararClaves() = withContext(Dispatchers.IO) {
        candado.withLock {
            val yo = almacen.asegurarIdentidad()
            val nunca = yo.publicadoEn == 0L

            val faltan = if (nunca) {
                PREKEYS_OBJETIVO
            } else {
                val estado = runCatching { api.estadoClaves() }.getOrNull() ?: return@withLock
                if (estado.unicasDisponibles >= estado.minimo) 0
                else estado.objetivo - estado.unicasDisponibles
            }

            if (!nunca && faltan <= 0) return@withLock

            val par = almacen.identityKeyPair
            val actual = almacen.identidadPropiaActual()

            // La firmada y la kyber se rotan cada vez que se publica el juego
            // completo. Rotarlas es lo que limita la ventana en la que una
            // clave comprometida sirve para algo.
            val firmadaId = actual.proximoFirmadaId
            val parFirmada = ECKeyPair.generate()
            val firmaFirmada = par.privateKey.calculateSignature(parFirmada.publicKey.serialize())
            almacen.storeSignedPreKey(
                firmadaId,
                SignedPreKeyRecord(firmadaId, System.currentTimeMillis(), parFirmada, firmaFirmada),
            )

            val kyberId = actual.proximoKyberId
            val parKyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val firmaKyber = par.privateKey.calculateSignature(parKyber.publicKey.serialize())
            almacen.storeKyberPreKey(
                kyberId,
                KyberPreKeyRecord(kyberId, System.currentTimeMillis(), parKyber, firmaKyber),
            )

            val desde = actual.proximoPreKeyId
            val unicas = (0 until faltan.coerceAtLeast(0)).map { i ->
                val id = desde + i
                val p = ECKeyPair.generate()
                almacen.storePreKey(id, PreKeyRecord(id, p))
                ClavePublica(id, b64(p.publicKey.serialize()))
            }

            api.publicarClaves(
                PublicarClavesReq(
                    registrationId = almacen.localRegistrationId,
                    identidad = b64(par.publicKey.serialize()),
                    firmada = ClaveFirmada(firmadaId, b64(parFirmada.publicKey.serialize()), b64(firmaFirmada)),
                    kyber = ClaveFirmada(kyberId, b64(parKyber.publicKey.serialize()), b64(firmaKyber)),
                    unicas = unicas,
                )
            )

            dao.avanzarFirmadaId(firmadaId + 1)
            dao.avanzarKyberId(kyberId + 1)
            dao.avanzarPreKeyId(desde + unicas.size)
            dao.marcarPublicado(System.currentTimeMillis())
            Log.i(TAG, "Claves publicadas: ${unicas.size} de un solo uso")
        }
    }

    // ------------------------------------------------------------------
    //  Cifrar
    // ------------------------------------------------------------------

    override suspend fun cifrar(
        conversacionId: String,
        esGrupo: Boolean,
        destinos: List<DestinoDispositivo>,
        carga: Carga,
    ): List<CopiaCifrada> = withContext(Dispatchers.IO) {
        if (destinos.isEmpty()) return@withContext emptyList()

        // Abrir sesiones necesita red y NO puede hacerse con el candado tomado:
        // bloquearia el cifrado de cualquier otro chat mientras se espera al
        // servidor. Primero se resuelve lo que falta, despues se cifra.
        val sinSesion = destinos.filter { !almacen.containsSession(dir(it.dispositivoId)) }
        for (d in sinSesion) abrirSesion(d)

        if (esGrupo) cifrarGrupo(conversacionId, destinos, carga)
        else cifrarPorPares(destinos, carga)
    }

    override val curvaCerca: com.wtfuck.protocol.Curva = object : com.wtfuck.protocol.Curva {
        override fun par(): Pair<ByteArray, ByteArray> {
            val kp = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
            return kp.privateKey.serialize() to kp.publicKey.publicKeyBytes
        }

        override fun acordar(privada: ByteArray, publica: ByteArray): ByteArray =
            org.signal.libsignal.protocol.ecc.ECPrivateKey(privada)
                .calculateAgreement(org.signal.libsignal.protocol.ecc.ECPublicKey.fromPublicKeyBytes(publica))
    }

    override fun identidadCerca(dispositivoId: String): ByteArray? =
        runCatching { almacen.getIdentity(dir(dispositivoId))?.publicKey?.publicKeyBytes }.getOrNull()

    override fun acordarConMiIdentidad(publica: ByteArray): ByteArray =
        almacen.identityKeyPair.privateKey
            .calculateAgreement(org.signal.libsignal.protocol.ecc.ECPublicKey.fromPublicKeyBytes(publica))

    override suspend fun cifrarSoloPara(destino: DestinoDispositivo, carga: Carga): CopiaCifrada? =
        withContext(Dispatchers.IO) {
            if (!almacen.containsSession(dir(destino.dispositivoId))) return@withContext null
            cifrarPorPares(listOf(destino), carga).firstOrNull()
                // Una sesion que todavia no recibio respuesta cifra como
                // "preparado" -abre sesion-, y por el aire eso se rechaza
                // siempre. Mejor no mandarlo que mandar algo que no va a entrar.
                ?.takeIf { it.tipo == TipoCifrado.SESION }
        }

    /**
     * Uno a uno: un cuerpo por dispositivo.
     *
     * Tambien se usa dentro de los grupos, para repartir la clave de emisor:
     * esa clave solo puede viajar por un canal que ya este cifrado por pares.
     */
    private suspend fun cifrarPorPares(
        destinos: List<DestinoDispositivo>,
        carga: Carga,
    ): List<CopiaCifrada> {
        val claro = Relleno.poner(
            jsonApp.encodeToString(Carga.serializer(), carga).toByteArray(),
        )
        return candado.withLock {
            destinos.mapNotNull { d -> cifrarUno(d, claro) }
        }
    }

    /** Un cuerpo para un dispositivo. Exige el candado tomado. */
    private fun cifrarUno(d: DestinoDispositivo, claro: ByteArray): CopiaCifrada? {
        val direccion = dir(d.dispositivoId)
        if (!almacen.containsSession(direccion)) {
            // No se pudo abrir sesion con este dispositivo -no publico claves
            // todavia, o no hubo red. Se omite la copia y el servidor la
            // reportara como faltante; no se manda basura que el otro lado no
            // pueda abrir.
            Log.w(TAG, "Sin sesion con ${d.username}, se omite su copia")
            return null
        }
        return runCatching {
            val msg = SessionCipher(almacen, direccion).encrypt(claro)
            CopiaCifrada.para(d.dispositivoId, b64(msg.serialize()), msg.type)
        }.getOrElse {
            Log.w(TAG, "No se pudo cifrar para ${d.username}: ${it.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    //  Grupos: clave de emisor (E.4)
    // ------------------------------------------------------------------

    /**
     * Cifra un mensaje de grupo con clave de emisor.
     *
     * Se cifra UNA vez y esos bytes valen para todo el que ya tenga la clave:
     * un grupo de veinte pasa de subir veinte cuerpos a subir uno.
     *
     * A quien todavia no la tiene se le manda por pares un [Carga.ConClaveGrupo]
     * con la clave y el mensaje juntos. Van juntos a proposito: mandarlos en
     * dos sobres exigiria garantizar que llegan en orden, y con un buzon que
     * reentrega eso no se puede garantizar.
     */
    private suspend fun cifrarGrupo(
        conversacionId: String,
        destinos: List<DestinoDispositivo>,
        carga: Carga,
    ): List<CopiaCifrada> {
        val miDispositivo = sesion.dispositivoId ?: return cifrarPorPares(destinos, carga)
        val claro = Relleno.poner(
            jsonApp.encodeToString(Carga.serializer(), carga).toByteArray(),
        )

        return candado.withLock {
            val fila = dao.distribucion(conversacionId)
            val vigentes = destinos.map { it.dispositivoId }.toSet()
            val repartidaA = fila?.repartidaA?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

            // ROTACION. Si alguien que tenia la clave ya no esta en el grupo,
            // hay que estrenar una: quien salio se quedo con la anterior y con
            // ella podria seguir abriendo lo que se hable de ahora en adelante.
            // Es la unica desventaja real de las claves de emisor frente al
            // cifrado por pares, y se paga aqui.
            val salieron = repartidaA.filter { it !in vigentes }
            val rotar = fila == null || salieron.isNotEmpty()
            if (salieron.isNotEmpty()) {
                Log.i(TAG, "Rotando la clave de emisor de $conversacionId: salieron ${salieron.size}")
            }

            val distId = if (rotar) UUID.randomUUID() else UUID.fromString(fila!!.distribucionId)
            val yaLaTienen = if (rotar) emptySet() else repartidaA

            val miDir = dir(miDispositivo)
            val constructor = GroupSessionBuilder(almacen)

            // `create` primero y `encrypt` despues: asi la clave que se reparte
            // sirve tambien para este mensaje, no solo para los siguientes.
            val skdm = runCatching { constructor.create(miDir, distId) }.getOrElse {
                Log.w(TAG, "No se pudo crear la clave de emisor: ${it.message}")
                return@withLock cifrarPorParesBloqueado(destinos, claro)
            }
            val mensajeGrupo = runCatching { GroupCipher(almacen, miDir).encrypt(distId, claro) }.getOrElse {
                Log.w(TAG, "No se pudo cifrar para el grupo: ${it.message}")
                return@withLock cifrarPorParesBloqueado(destinos, claro)
            }

            // Se guarda la distribucion pero NO se marca como repartida: eso
            // lo hace `confirmarEnvio`, cuando el servidor acepta.
            dao.guardarDistribucion(
                DistribucionGrupoEnt(conversacionId, distId.toString(), yaLaTienen.joinToString(",")),
            )

            val copias = mutableListOf<CopiaCifrada>()

            val conClave = destinos.filter { it.dispositivoId in yaLaTienen }
            if (conClave.isNotEmpty()) {
                copias += CopiaCifrada(
                    conClave.map { it.dispositivoId },
                    b64(mensajeGrupo.serialize()),
                    TipoCifrado.GRUPO,
                )
            }

            val sinClave = destinos.filter { it.dispositivoId !in yaLaTienen }
            if (sinClave.isNotEmpty()) {
                val envuelto = jsonApp.encodeToString(
                    Carga.serializer(),
                    Carga.ConClaveGrupo(b64(skdm.serialize()), carga),
                ).toByteArray()
                sinClave.forEach { d -> cifrarUno(d, envuelto)?.let { copias += it } }
            }

            copias
        }
    }

    /** Ultimo recurso si la clave de emisor falla: por pares, con el candado ya tomado. */
    private fun cifrarPorParesBloqueado(
        destinos: List<DestinoDispositivo>,
        claro: ByteArray,
    ): List<CopiaCifrada> = destinos.mapNotNull { cifrarUno(it, claro) }

    /**
     * El servidor acepto: recien ahora la clave cuenta como repartida.
     *
     * Marcarla antes y que el envio fallara dejaria al otro lado sin poder
     * abrir ningun mensaje de grupo: el proximo iria sin la clave porque
     * constaria como entregada.
     */
    override suspend fun confirmarEnvio(
        conversacionId: String,
        destinos: List<DestinoDispositivo>,
    ) {
        if (destinos.isEmpty()) return
        withContext(Dispatchers.IO) {
            candado.withLock {
                val fila = dao.distribucion(conversacionId) ?: return@withLock
                val ya = fila.repartidaA.split(',').filter { it.isNotBlank() }.toMutableSet()
                ya += destinos.map { it.dispositivoId }
                dao.guardarDistribucion(fila.copy(repartidaA = ya.joinToString(",")))
            }
        }
    }

    /**
     * Abre una sesion criptografica con un dispositivo (X3DH / PQXDH).
     *
     * Pide su paquete de claves publicas al servidor y lo procesa. El servidor
     * consume una prekey de un solo uso al entregarlo, asi que esto no se
     * llama a la ligera: solo cuando de verdad no hay sesion.
     */
    private suspend fun abrirSesion(d: DestinoDispositivo) {
        val paquete = runCatching { api.paqueteClaves(d.dispositivoId) }.getOrElse {
            Log.w(TAG, "No se pudo traer el paquete de ${d.username}: ${it.message}")
            return
        }
        candado.withLock {
            runCatching {
                SessionBuilder(almacen, dir(d.dispositivoId)).process(construir(paquete))
                Log.i(TAG, "Sesion abierta con ${d.username}")
            }.onFailure {
                Log.w(TAG, "El paquete de ${d.username} no sirvio: ${it.message}")
            }
        }
    }

    private fun construir(p: PaqueteClaves): PreKeyBundle = PreKeyBundle(
        p.registrationId,
        1,                                   // deviceId: ver la nota de la clase
        p.unica?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
        p.unica?.let { ECPublicKey(deB64(it.publica)) },
        p.firmada.keyId,
        ECPublicKey(deB64(p.firmada.publica)),
        deB64(p.firmada.firma),
        IdentityKey(deB64(p.identidad)),
        p.kyber.keyId,
        KEMPublicKey(deB64(p.kyber.publica)),
        deB64(p.kyber.firma),
    )

    // ------------------------------------------------------------------
    //  Descifrar
    // ------------------------------------------------------------------

    override suspend fun descifrar(
        conversacionId: String,
        origen: OrigenSobre,
        bytes: ByteArray,
    ): Carga = withContext(Dispatchers.IO) {
        val claro = candado.withLock {
            val direccion = dir(origen.dispositivoId)
            when (origen.tipo) {
                TipoCifrado.PREPARADO -> SessionCipher(almacen, direccion).decrypt(PreKeySignalMessage(bytes))
                TipoCifrado.SESION -> SessionCipher(almacen, direccion).decrypt(SignalMessage(bytes))
                TipoCifrado.GRUPO -> GroupCipher(almacen, direccion).decrypt(bytes)
                else -> throw IllegalArgumentException("Tipo de cuerpo no soportado: ${origen.tipo}")
            }
        }

        val carga = jsonApp.decodeFromString(
            Carga.serializer(),
            String(Relleno.quitar(claro)),
        )

        // Si venia con la clave de emisor pegada, se procesa la clave y se
        // sigue con lo de adentro. El resto de la app no se entera de que
        // existen las claves de emisor, y eso es deliberado.
        if (carga is Carga.ConClaveGrupo) {
            candado.withLock {
                runCatching {
                    GroupSessionBuilder(almacen).process(
                        dir(origen.dispositivoId),
                        SenderKeyDistributionMessage(deB64(carga.distribucion)),
                    )
                    Log.i(TAG, "Clave de emisor de ${origen.username} registrada")
                }.onFailure {
                    Log.w(TAG, "Clave de emisor invalida de ${origen.username}: ${it.message}")
                }
            }
            return@withContext carga.interior
        }
        carga
    }

    // ------------------------------------------------------------------
    //  Huella (E.5)
    // ------------------------------------------------------------------

    /**
     * Huella comparable entre dos identidades.
     *
     * Son los digitos que dos personas se leen por telefono o comparan por QR.
     * Si coinciden, nadie se puso en medio; si no coinciden, alguien lo hizo.
     * Es el unico chequeo que no depende de confiar en el servidor, y por eso
     * existe.
     */
    fun huellaCon(otroUsuarioId: String, otroDispositivoId: String, miUsuarioId: String): Par? {
        val suya = almacen.getIdentity(dir(otroDispositivoId)) ?: return null
        val mia = almacen.identityKeyPair.publicKey
        val gen = org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator(ITERACIONES_HUELLA)
        val h = gen.createFor(
            VERSION_HUELLA,
            miUsuarioId.toByteArray(),
            mia,
            otroUsuarioId.toByteArray(),
            suya,
        )
        return Par(h.displayableFingerprint.displayText, b64(h.scannableFingerprint.serialized))
    }

    data class Par(val digitos: String, val escaneable: String)

    /** Si ya se verifico a mano la identidad de ese dispositivo. */
    fun verificada(dispositivoId: String): Boolean =
        dao.identidadRemota("$dispositivoId:1")?.verificada == true

    fun marcarVerificada(dispositivoId: String, verificada: Boolean) =
        dao.resolverCambio("$dispositivoId:1", verificada)

    fun cambioSinResolver(dispositivoId: String): Boolean =
        dao.identidadRemota("$dispositivoId:1")?.cambio == true

    companion object {
        /** Version del formato de huella. La 1 es la unica que existe. */
        private const val VERSION_HUELLA = 1

        /**
         * Iteraciones del derivado de la huella.
         *
         * 5200 es el valor que usa Signal. No es un numero mio y no conviene
         * cambiarlo: dos implementaciones con iteraciones distintas producen
         * huellas distintas para las mismas claves, y entonces comparar deja de
         * significar nada.
         */
        private const val ITERACIONES_HUELLA = 5200
    }
}
