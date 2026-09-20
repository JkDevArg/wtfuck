package com.wtfuck.app.datos

import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * El almacen de estado criptografico que libsignal usa (modulo E.3).
 *
 * libsignal no guarda nada: pide. Todo el estado del Double Ratchet -sesiones,
 * cadenas, claves- vive aqui, y esta clase es el unico puente entre la
 * biblioteca y la base cifrada del telefono.
 *
 * Hay una decision importante escrita en `isTrustedIdentity`: la primera vez
 * que se ve a alguien se confia (TOFU). No hay alternativa sin una autoridad
 * central que certifique identidades, que es justo lo que este proyecto no
 * quiere tener. Lo que SI se hace es dejar constancia del cambio para que la
 * persona pueda verificar la huella por fuera del canal.
 */
class AlmacenSignal(private val dao: SignalDao) : SignalProtocolStore {

    private val TAG = "AlmacenSignal"

    /** Cache de mi propio par: se pide en cada operacion y no cambia. */
    @Volatile
    private var propia: IdentityKeyPair? = null

    @Volatile
    private var registro: Int = 0

    private fun SignalProtocolAddress.clave(): String = "$name:$deviceId"

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    // ------------------------------------------------------------------
    //  Identidad propia
    // ------------------------------------------------------------------

    /**
     * Crea la identidad si no existe. Idempotente.
     *
     * Se genera UNA sola vez por instalacion. Generarla de nuevo cambiaria la
     * huella y haria que a todos los contactos les salte el aviso de que la
     * clave cambio, que es exactamente la alarma que no hay que dar en falso.
     */
    fun asegurarIdentidad(): IdentidadPropiaEnt {
        dao.identidadPropia()?.let { fila ->
            propia = IdentityKeyPair(fila.parClaves)
            registro = fila.registrationId
            return fila
        }
        val par = IdentityKeyPair.generate()
        // El identificador de registro es de 14 bits por convencion de Signal.
        // Nunca 0: ese valor significa "sin publicar" en el servidor.
        val reg = (1..16380).random()
        val fila = IdentidadPropiaEnt(parClaves = par.serialize(), registrationId = reg)
        dao.guardarIdentidadPropia(fila)
        propia = par
        registro = reg
        android.util.Log.i(TAG, "Identidad criptografica creada")
        return fila
    }

    /** La fila de estado, con los proximos ids de clave a usar. */
    fun identidadPropiaActual(): IdentidadPropiaEnt = dao.identidadPropia() ?: asegurarIdentidad()

    override fun getIdentityKeyPair(): IdentityKeyPair =
        propia ?: IdentityKeyPair(asegurarIdentidad().parClaves).also { propia = it }

    override fun getLocalRegistrationId(): Int {
        if (registro == 0) asegurarIdentidad()
        return registro
    }

    // ------------------------------------------------------------------
    //  Identidades ajenas
    // ------------------------------------------------------------------

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val dir = address.clave()
        val previa = dao.identidadRemota(dir)
        val nueva = identityKey.serialize()

        if (previa == null) {
            dao.guardarIdentidadRemota(
                IdentidadRemotaEnt(dir, nueva, System.currentTimeMillis()),
            )
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        if (previa.identidad.contentEquals(nueva)) {
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }

        // Cambio. Se guarda la nueva, pero `verificada` vuelve a false: una
        // identidad nueva NO hereda la confianza de la anterior. Y queda
        // marcado `cambio` para que la UI lo diga.
        android.util.Log.w(TAG, "La identidad de $dir cambio")
        dao.guardarIdentidadRemota(
            IdentidadRemotaEnt(dir, nueva, System.currentTimeMillis(), verificada = false, cambio = true),
        )
        return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    /**
     * Confianza al primer uso.
     *
     * Devolver false aqui haria que los mensajes de esa persona no se puedan
     * descifrar hasta que alguien verifique a mano, y en la practica eso hace
     * que la app parezca rota. Se elige confiar y AVISAR, que es lo que hacen
     * WhatsApp y Signal: el mensaje llega, y encima aparece que la clave de
     * seguridad cambio.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        dao.identidadRemota(address.clave())?.let { runCatching { IdentityKey(it.identidad) }.getOrNull() }

    // ------------------------------------------------------------------
    //  Sesiones
    // ------------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        dao.sesion(address.clave())?.let { runCatching { SessionRecord(it.registro) }.getOrNull() }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a ->
            loadSession(a) ?: throw org.signal.libsignal.protocol.NoSessionException(
                "No hay sesion con ${a.clave()}"
            )
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        dao.direccionesDe(name).mapNotNull { it.substringAfterLast(':').toIntOrNull() }
            .filter { it != 1 }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        dao.guardarSesion(SesionSignalEnt(address.clave(), record.serialize()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        dao.sesion(address.clave()) != null

    override fun deleteSession(address: SignalProtocolAddress) = dao.borrarSesion(address.clave())

    override fun deleteAllSessions(name: String) = dao.borrarSesionesDe(name)

    // ------------------------------------------------------------------
    //  Prekeys
    // ------------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val fila = dao.preKey(preKeyId) ?: throw InvalidKeyIdException("No existe la prekey $preKeyId")
        return PreKeyRecord(fila.registro)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        dao.guardarPreKey(PreKeyLocalEnt(preKeyId, record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = dao.preKey(preKeyId) != null

    /**
     * Borra la prekey usada.
     *
     * libsignal llama a esto en cuanto una prekey de un solo uso cumple su
     * funcion. Conservarla romperia la garantia hacia adelante: es justamente
     * su caracter de un solo uso lo que hace que robar el telefono manana no
     * permita leer lo de ayer.
     */
    override fun removePreKey(preKeyId: Int) = dao.borrarPreKey(preKeyId)

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val fila = dao.preKeyFirmada(signedPreKeyId)
            ?: throw InvalidKeyIdException("No existe la prekey firmada $signedPreKeyId")
        return SignedPreKeyRecord(fila.registro)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        dao.preKeysFirmadas().mapNotNull { runCatching { SignedPreKeyRecord(it.registro) }.getOrNull() }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        dao.guardarPreKeyFirmada(PreKeyFirmadaLocalEnt(signedPreKeyId, record.serialize()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        dao.preKeyFirmada(signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = dao.borrarPreKeyFirmada(signedPreKeyId)

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val fila = dao.preKeyKyber(kyberPreKeyId)
            ?: throw InvalidKeyIdException("No existe la prekey kyber $kyberPreKeyId")
        return KyberPreKeyRecord(fila.registro)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        dao.preKeysKyber().mapNotNull { runCatching { KyberPreKeyRecord(it.registro) }.getOrNull() }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        dao.guardarPreKeyKyber(PreKeyKyberLocalEnt(kyberPreKeyId, record.serialize()))
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        dao.preKeyKyber(kyberPreKeyId) != null

    /**
     * Marca la kyber como usada y detecta repeticiones.
     *
     * La segunda parte es la que importa: si ya llego un mensaje inicial con
     * esta misma combinacion de prekeys Y la misma clave base, alguien esta
     * reenviando un mensaje grabado. libsignal delega esa comprobacion al
     * almacen, asi que sin esta tabla la proteccion contra repeticion no
     * existiria.
     */
    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        preKeyId: Int,
        baseKey: ECPublicKey,
    ) {
        val base = b64(baseKey.serialize())
        if (dao.claveBaseVista(kyberPreKeyId, preKeyId, base) > 0) {
            android.util.Log.w(TAG, "Clave base repetida: kyber=$kyberPreKeyId prekey=$preKeyId")
            throw ReusedBaseKeyException("Ese mensaje inicial ya se habia recibido.")
        }
        dao.anotarClaveBase(ClaveBaseVistaEnt(kyberPreKeyId, preKeyId, base))
        dao.marcarKyberUsada(kyberPreKeyId)
    }

    // ------------------------------------------------------------------
    //  Claves de emisor (grupos)
    // ------------------------------------------------------------------

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        dao.guardarClaveEmisor(
            ClaveEmisorEnt(sender.clave(), distributionId.toString(), record.serialize()),
        )
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? =
        dao.claveEmisor(sender.clave(), distributionId.toString())
            ?.let { runCatching { SenderKeyRecord(it.registro) }.getOrNull() }
}
