package com.wtfuck.interop

import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.ClaveFirmada
import com.wtfuck.protocol.ClavePublica
import com.wtfuck.protocol.PaqueteClaves
import com.wtfuck.protocol.PublicarClavesReq
import com.wtfuck.protocol.Relleno
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W0 de la versión web: ¿el WebAssembly del navegador habla el mismo Signal
 * que el teléfono?
 *
 * Del lado "Android" está `libsignal-client` 0.86.5, la misma libsignal de la
 * app con los nativos de escritorio, usada IGUAL que `CifradorSignal`: mismas
 * llamadas, misma dirección por dispositivo (deviceId 1), mismo armado del
 * paquete. Del lado "web" está `web/cripto/pkg`, el WebAssembly que cargará el
 * navegador, manejado desde Node.
 *
 * Lo que tiene que salir bien para seguir con la versión web:
 *  - abrir sesión (PQXDH con Kyber1024) en los dos sentidos;
 *  - seguir hablando con el ratchet, también fuera de orden;
 *  - un mensaje de la app tal cual viaja (Relleno + Carga en JSON);
 *  - grupos con clave de emisor, en los dos sentidos;
 *  - la misma huella de 60 dígitos de los dos lados.
 */
class LibsignalWebTest {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private lateinit var web: LadoWeb

        @BeforeClass
        @JvmStatic
        fun arrancar() {
            val raiz = File(System.getProperty("wtfuck.web") ?: "web")
            val wasm = File(raiz, "cripto/pkg/wtfuck_cripto_bg.wasm")
            assumeTrue(
                "Falta el WebAssembly ($wasm). Ármalo con web/cripto/construir.sh.",
                wasm.isFile,
            )
            val node = runCatching {
                ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS)
            }.getOrDefault(false)
            assumeTrue("No hay Node en el PATH.", node)
            web = LadoWeb(File(raiz, "interop/lado-web.mjs"))
            println("WebAssembly con libsignal ${web.pedir("version")["libsignal"]!!.jsonPrimitive.content}")
        }

        @AfterClass
        @JvmStatic
        fun cerrar() {
            if (::web.isInitialized) web.cerrar()
        }

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        private fun deB64(s: String): ByteArray = Base64.getDecoder().decode(s)

        /** La direccion de Signal es el dispositivo, con deviceId 1, como en la app. */
        private fun dir(d: String) = SignalProtocolAddress(d, 1)
    }

    /** El "teléfono": libsignal de la JVM, armado como `CifradorSignal`. */
    private class Telefono(val dispositivo: String) {
        val identidad: IdentityKeyPair = IdentityKeyPair.generate()
        val almacen = InMemorySignalProtocolStore(identidad, KeyHelper.generateRegistrationId(false))

        /** Lo mismo que `CifradorSignal.prepararClaves`. */
        fun publicar(firmadaId: Int, kyberId: Int, unicaId: Int): PublicarClavesReq {
            val par = almacen.identityKeyPair
            val pf = ECKeyPair.generate()
            val ff = par.privateKey.calculateSignature(pf.publicKey.serialize())
            almacen.storeSignedPreKey(firmadaId, SignedPreKeyRecord(firmadaId, System.currentTimeMillis(), pf, ff))
            val pk = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val fk = par.privateKey.calculateSignature(pk.publicKey.serialize())
            almacen.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, System.currentTimeMillis(), pk, fk))
            val pu = ECKeyPair.generate()
            almacen.storePreKey(unicaId, PreKeyRecord(unicaId, pu))
            return PublicarClavesReq(
                registrationId = almacen.localRegistrationId,
                identidad = b64(par.publicKey.serialize()),
                firmada = ClaveFirmada(firmadaId, b64(pf.publicKey.serialize()), b64(ff)),
                kyber = ClaveFirmada(kyberId, b64(pk.publicKey.serialize()), b64(fk)),
                unicas = listOf(ClavePublica(unicaId, b64(pu.publicKey.serialize()))),
            )
        }

        /** Lo mismo que `CifradorSignal.construir`. */
        fun abrirSesion(con: String, p: PaqueteClaves) {
            val paquete = PreKeyBundle(
                p.registrationId,
                1,
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
            SessionBuilder(almacen, dir(con)).process(paquete)
        }

        fun cifrar(para: String, claro: ByteArray): CiphertextMessage = SessionCipher(almacen, dir(para)).encrypt(claro)

        fun descifrar(de: String, tipo: Int, cuerpo: String): ByteArray {
            val c = SessionCipher(almacen, dir(de))
            return when (tipo) {
                CiphertextMessage.PREKEY_TYPE -> c.decrypt(PreKeySignalMessage(deB64(cuerpo)))
                CiphertextMessage.WHISPER_TYPE -> c.decrypt(SignalMessage(deB64(cuerpo)))
                else -> error("tipo $tipo")
            }
        }
    }

    /** El paquete que armaría el servidor con lo publicado (`GET /v1/claves/{d}`). */
    private fun paqueteDe(dispositivo: String, p: PublicarClavesReq) = PaqueteClaves(
        usuarioId = "u-$dispositivo",
        username = dispositivo,
        dispositivoId = dispositivo,
        registrationId = p.registrationId,
        identidad = p.identidad,
        firmada = p.firmada,
        kyber = p.kyber,
        unica = p.unicas.firstOrNull(),
    )

    private fun webNuevo(nombre: String) = web.pedir("nuevo", "nombre" to nombre)

    private fun webCifrar(nombre: String, para: String, claro: ByteArray): Pair<Int, String> {
        val r = web.pedir("cifrar", "nombre" to nombre, "dispositivo" to para, "claro" to b64(claro))
        return r["tipo"]!!.jsonPrimitive.int to r["cuerpo"]!!.jsonPrimitive.content
    }

    private fun webDescifrar(nombre: String, de: String, tipo: Int, cuerpo: String): ByteArray {
        val r = web.pedir("descifrar", "nombre" to nombre, "dispositivo" to de, "tipo" to tipo, "cuerpo" to cuerpo)
        return deB64(r["claro"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la web abre sesion con el telefono y se hablan`() {
        val tel = Telefono("tel-1")
        webNuevo("web")
        val paquete = paqueteDe("tel-1", tel.publicar(firmadaId = 1, kyberId = 1, unicaId = 1))
        web.pedir("abrirSesion", "nombre" to "web", "dispositivo" to "tel-1", "paquete" to json.encodeToJsonElement(PaqueteClaves.serializer(), paquete))

        val (t1, c1) = webCifrar("web", "tel-1", "hola desde la web, con ñ y 🙂".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, t1, "el primero lleva la prekey (PQXDH)")
        assertEquals("hola desde la web, con ñ y 🙂", String(tel.descifrar("web-1", t1, c1)))

        val r = tel.cifrar("web-1", "recibido en el teléfono".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, r.type)
        assertEquals("recibido en el teléfono", String(webDescifrar("web", "tel-1", r.type, b64(r.serialize()))))
    }

    @Test
    fun `el telefono abre sesion con la web usando lo que la web publica`() {
        val tel = Telefono("tel-2")
        webNuevo("web2")
        val pedido = web.pedir(
            "claves", "nombre" to "web2", "firmadaId" to 7, "kyberId" to 9, "desde" to 100, "cuantas" to 3,
        )
        // Si los nombres de campo no fueran los del contrato, esto revienta.
        val publicadas = json.decodeFromJsonElement(PublicarClavesReq.serializer(), pedido)
        assertEquals(3, publicadas.unicas.size)
        assertEquals(listOf(100, 101, 102), publicadas.unicas.map { it.keyId })

        tel.abrirSesion("web2-1", paqueteDe("web2-1", publicadas))
        val m = tel.cifrar("web2-1", "hola web".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, m.type)
        assertEquals("hola web", String(webDescifrar("web2", "tel-2", m.type, b64(m.serialize()))))

        val (t, c) = webCifrar("web2", "tel-2", "hola teléfono".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, t)
        assertEquals("hola teléfono", String(tel.descifrar("web2-1", t, c)))
    }

    @Test
    fun `el ratchet aguanta mensajes fuera de orden en los dos sentidos`() {
        val tel = Telefono("tel-3")
        webNuevo("web3")
        val p = paqueteDe("tel-3", tel.publicar(2, 2, 2))
        web.pedir("abrirSesion", "nombre" to "web3", "dispositivo" to "tel-3", "paquete" to json.encodeToJsonElement(PaqueteClaves.serializer(), p))
        val primero = webCifrar("web3", "tel-3", "0".toByteArray())
        tel.descifrar("web3-1", primero.first, primero.second)

        repeat(3) { ronda ->
            val deLaWeb = (1..4).map { webCifrar("web3", "tel-3", "w$ronda-$it".toByteArray()) }
            for (i in listOf(3, 0, 2, 1)) {
                val (t, c) = deLaWeb[i]
                assertEquals("w$ronda-${i + 1}", String(tel.descifrar("web3-1", t, c)))
            }
            val delTel = (1..4).map { tel.cifrar("web3-1", "t$ronda-$it".toByteArray()) }
            for (i in listOf(1, 3, 0, 2)) {
                val m = delTel[i]
                assertEquals("t$ronda-${i + 1}", String(webDescifrar("web3", "tel-3", m.type, b64(m.serialize()))))
            }
        }
    }

    @Test
    fun `un mensaje de la app viaja tal cual, con relleno y en JSON`() {
        val tel = Telefono("tel-4")
        webNuevo("web4")
        val p = paqueteDe("tel-4", tel.publicar(3, 3, 3))
        web.pedir("abrirSesion", "nombre" to "web4", "dispositivo" to "tel-4", "paquete" to json.encodeToJsonElement(PaqueteClaves.serializer(), p))
        val texto = "x".repeat(5_000) + " fin"
        val claro = Relleno.poner(json.encodeToString(Carga.serializer(), Carga.Texto(cuerpo = texto)).toByteArray())
        val (t, c) = webCifrar("web4", "tel-4", claro)
        val abierto = tel.descifrar("web4-1", t, c)
        val carga = json.decodeFromString(Carga.serializer(), String(Relleno.quitar(abierto)))
        assertEquals(texto, (carga as Carga.Texto).cuerpo)
    }

    @Test
    fun `grupos con clave de emisor en los dos sentidos`() {
        val tel = Telefono("tel-5")
        webNuevo("web5")

        // Del teléfono a la web: la clave de emisor viaja (en la app, por pares
        // dentro de Carga.ConClaveGrupo) y después un cuerpo para todos.
        val distTel = UUID.randomUUID()
        val skdmTel = GroupSessionBuilder(tel.almacen).create(dir("tel-5"), distTel)
        web.pedir("procesarDistribucion", "nombre" to "web5", "remitente" to "tel-5", "skdm" to b64(skdmTel.serialize()))
        for (i in 1..3) {
            val m = GroupCipher(tel.almacen, dir("tel-5")).encrypt(distTel, "grupo desde el teléfono $i".toByteArray())
            assertEquals(CiphertextMessage.SENDERKEY_TYPE, m.type)
            val r = web.pedir("descifrarGrupo", "nombre" to "web5", "remitente" to "tel-5", "cuerpo" to b64(m.serialize()))
            assertEquals("grupo desde el teléfono $i", String(deB64(r["claro"]!!.jsonPrimitive.content)))
        }

        // De la web al teléfono.
        val distWeb = UUID.randomUUID().toString()
        val skdmWeb = web.pedir("crearDistribucion", "nombre" to "web5", "miDispositivo" to "web5-1", "distId" to distWeb)
        GroupSessionBuilder(tel.almacen).process(
            dir("web5-1"),
            SenderKeyDistributionMessage(deB64(skdmWeb["skdm"]!!.jsonPrimitive.content)),
        )
        for (i in 1..3) {
            val r = web.pedir(
                "cifrarGrupo", "nombre" to "web5", "miDispositivo" to "web5-1", "distId" to distWeb,
                "claro" to b64("grupo desde la web $i".toByteArray()),
            )
            val abierto = GroupCipher(tel.almacen, dir("web5-1")).decrypt(deB64(r["cuerpo"]!!.jsonPrimitive.content))
            assertEquals("grupo desde la web $i", String(abierto))
        }
    }

    @Test
    fun `la huella es la misma en el telefono y en la web`() {
        val tel = Telefono("tel-6")
        val webIdentidad = webNuevo("web6")["identidad"]!!.jsonPrimitive.content
        val p = paqueteDe("tel-6", tel.publicar(4, 4, 4))
        web.pedir("abrirSesion", "nombre" to "web6", "dispositivo" to "tel-6", "paquete" to json.encodeToJsonElement(PaqueteClaves.serializer(), p))

        val delTel = NumericFingerprintGenerator(5200).createFor(
            1,
            "uid-tel".toByteArray(),
            tel.identidad.publicKey,
            "uid-web".toByteArray(),
            IdentityKey(deB64(webIdentidad)),
        )
        val deLaWeb = web.pedir(
            "huella", "nombre" to "web6", "miUsuario" to "uid-web", "otroUsuario" to "uid-tel", "otroDispositivo" to "tel-6",
        )
        val digitos = deLaWeb["digitos"]!!.jsonPrimitive.content
        assertEquals(60, digitos.length)
        assertEquals(delTel.displayableFingerprint.displayText, digitos)
        // Y el QR de la web lo valida el teléfono, como al escanearlo.
        assertTrue(delTel.scannableFingerprint.compareTo(deB64(deLaWeb["escaneable"]!!.jsonPrimitive.content)))
    }

    /** Node con el WebAssembly, un pedido por línea. */
    private class LadoWeb(script: File) {
        private val proceso: Process = ProcessBuilder("node", script.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        private val entrada: BufferedWriter = proceso.outputStream.bufferedWriter()
        private val salida: BufferedReader = proceso.inputStream.bufferedReader()
        private var siguiente = 1

        @Synchronized
        fun pedir(op: String, vararg campos: Pair<String, Any>): JsonObject {
            val id = siguiente++
            val pedido = buildJsonObject {
                put("id", id)
                put("op", op)
                for ((k, v) in campos) when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is JsonElement -> put(k, v)
                    else -> error("tipo no soportado: ${v::class}")
                }
            }
            entrada.write(pedido.toString())
            entrada.newLine()
            entrada.flush()
            val linea = salida.readLine() ?: error("el lado web se cerró (¿falló al cargar el WebAssembly?)")
            val r = json.parseToJsonElement(linea).jsonObject
            check(r["id"]!!.jsonPrimitive.int == id) { "respuesta desordenada: $linea" }
            r["error"]?.let { throw AssertionError("lado web, $op: ${it.jsonPrimitive.content}") }
            return r["ok"]!!.jsonObject
        }

        fun cerrar() {
            runCatching { entrada.close() }
            if (!proceso.waitFor(5, TimeUnit.SECONDS)) proceso.destroy()
        }
    }
}
