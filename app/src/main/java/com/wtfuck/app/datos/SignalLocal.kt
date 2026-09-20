package com.wtfuck.app.datos

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Estado criptografico de Signal, en la base LOCAL cifrada (modulo E.3).
 *
 * Aqui viven las claves privadas y las sesiones. Es lo mas sensible que guarda
 * la app, y por eso va en la misma base con SQLCipher que el historial: una
 * sola clave, un solo lugar que proteger.
 *
 * Todas las consultas son SINCRONAS a proposito. La interfaz `SignalProtocolStore`
 * de libsignal no es suspendida -es una biblioteca de criptografia, no de red-,
 * asi que el almacen tiene que poder responder en el momento. Room lanza una
 * excepcion si alguna de estas se ejecuta en el hilo principal, y eso es una
 * red de seguridad util: si pasa, es un error de quien llama.
 */

/**
 * Mi identidad. Una sola fila.
 *
 * La clave privada esta aqui y no en el Keystore de Android por un motivo
 * concreto: el Double Ratchet necesita operar con ella miles de veces y
 * derivar material nuevo, y el Keystore solo permite firmar y descifrar con lo
 * que guarda, no exportar. Lo que protege a esta fila es SQLCipher, cuya clave
 * SI vive en el Keystore.
 */
@Entity(tableName = "identidad_propia")
data class IdentidadPropiaEnt(
    @PrimaryKey val id: Int = 1,
    /** Serializacion de IdentityKeyPair: publica + privada. */
    val parClaves: ByteArray,
    val registrationId: Int,
    /** Cuando se publicaron las claves al servidor. 0 = nunca. */
    val publicadoEn: Long = 0,
    /** Ids ya usados, para no repetirlos al reponer prekeys. */
    val proximoPreKeyId: Int = 1,
    val proximoFirmadaId: Int = 1,
    val proximoKyberId: Int = 1,
)

/**
 * Identidad de otro dispositivo, tal como la vimos la primera vez.
 *
 * `verificada` es lo que la persona confirmo comparando la huella. Si la
 * identidad cambia despues, se guarda la nueva pero `verificada` vuelve a
 * false: una identidad nueva no hereda la confianza de la anterior, y ese es
 * justamente el aviso que hay que mostrar.
 */
@Entity(tableName = "identidad_remota")
data class IdentidadRemotaEnt(
    /** "usuarioId:deviceId" tal como lo forma SignalProtocolAddress. */
    @PrimaryKey val direccion: String,
    val identidad: ByteArray,
    val vistaEn: Long,
    val verificada: Boolean = false,
    /** Si cambio respecto de la que teniamos. Dispara el aviso en la UI. */
    val cambio: Boolean = false,
)

@Entity(tableName = "sesion_signal")
data class SesionSignalEnt(
    @PrimaryKey val direccion: String,
    val registro: ByteArray,
)

@Entity(tableName = "prekey_local")
data class PreKeyLocalEnt(
    @PrimaryKey val keyId: Int,
    val registro: ByteArray,
)

@Entity(tableName = "prekey_firmada_local")
data class PreKeyFirmadaLocalEnt(
    @PrimaryKey val keyId: Int,
    val registro: ByteArray,
)

@Entity(tableName = "prekey_kyber_local")
data class PreKeyKyberLocalEnt(
    @PrimaryKey val keyId: Int,
    val registro: ByteArray,
    val usada: Boolean = false,
)

/**
 * Clave base ya vista, para detectar repeticiones.
 *
 * Si llega dos veces un mensaje inicial con la misma combinacion de prekeys y
 * la misma clave base, es una repeticion: alguien grabo el mensaje y lo esta
 * reenviando. libsignal pide que el almacen lo detecte, y sin esta tabla esa
 * proteccion simplemente no existe.
 */
@Entity(tableName = "clave_base_vista", primaryKeys = ["kyberId", "preKeyId", "claveBase"])
data class ClaveBaseVistaEnt(
    val kyberId: Int,
    val preKeyId: Int,
    /** Base64 de la clave base del mensaje. */
    val claveBase: String,
)

@Entity(tableName = "clave_emisor", primaryKeys = ["direccion", "distribucionId"])
data class ClaveEmisorEnt(
    val direccion: String,
    val distribucionId: String,
    val registro: ByteArray,
)

/**
 * Identificador de distribucion de un grupo (E.4).
 *
 * Las Sender Keys de libsignal se identifican por un UUID que NO es el id de la
 * conversacion: se puede rotar sin cambiar de grupo, y hay que rotarlo cada vez
 * que alguien sale, porque quien salio se quedo con la clave anterior.
 */
@Entity(tableName = "distribucion_grupo")
data class DistribucionGrupoEnt(
    @PrimaryKey val conversacionId: String,
    val distribucionId: String,
    /** Dispositivos a los que ya se les mando la clave de este emisor. */
    val repartidaA: String = "",
)

@Dao
interface SignalDao {

    // --- identidad propia ---------------------------------------------

    @Query("SELECT * FROM identidad_propia WHERE id = 1")
    fun identidadPropia(): IdentidadPropiaEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarIdentidadPropia(i: IdentidadPropiaEnt)

    @Query("UPDATE identidad_propia SET publicadoEn = :cuando WHERE id = 1")
    fun marcarPublicado(cuando: Long)

    @Query("UPDATE identidad_propia SET proximoPreKeyId = :prox WHERE id = 1")
    fun avanzarPreKeyId(prox: Int)

    @Query("UPDATE identidad_propia SET proximoFirmadaId = :prox WHERE id = 1")
    fun avanzarFirmadaId(prox: Int)

    @Query("UPDATE identidad_propia SET proximoKyberId = :prox WHERE id = 1")
    fun avanzarKyberId(prox: Int)

    // --- identidades ajenas -------------------------------------------

    @Query("SELECT * FROM identidad_remota WHERE direccion = :dir")
    fun identidadRemota(dir: String): IdentidadRemotaEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarIdentidadRemota(i: IdentidadRemotaEnt)

    @Query("SELECT * FROM identidad_remota WHERE cambio = 1")
    fun identidadesCambiadas(): List<IdentidadRemotaEnt>

    @Query("UPDATE identidad_remota SET cambio = 0, verificada = :verificada WHERE direccion = :dir")
    fun resolverCambio(dir: String, verificada: Boolean)

    // --- sesiones -----------------------------------------------------

    @Query("SELECT * FROM sesion_signal WHERE direccion = :dir")
    fun sesion(dir: String): SesionSignalEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarSesion(s: SesionSignalEnt)

    @Query("DELETE FROM sesion_signal WHERE direccion = :dir")
    fun borrarSesion(dir: String)

    @Query("DELETE FROM sesion_signal WHERE direccion LIKE :prefijo || ':%'")
    fun borrarSesionesDe(prefijo: String)

    @Query("SELECT direccion FROM sesion_signal WHERE direccion LIKE :prefijo || ':%'")
    fun direccionesDe(prefijo: String): List<String>

    // --- prekeys ------------------------------------------------------

    @Query("SELECT * FROM prekey_local WHERE keyId = :id")
    fun preKey(id: Int): PreKeyLocalEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarPreKey(k: PreKeyLocalEnt)

    @Query("DELETE FROM prekey_local WHERE keyId = :id")
    fun borrarPreKey(id: Int)

    @Query("SELECT COUNT(*) FROM prekey_local")
    fun cuantasPreKeys(): Int

    @Query("SELECT * FROM prekey_firmada_local WHERE keyId = :id")
    fun preKeyFirmada(id: Int): PreKeyFirmadaLocalEnt?

    @Query("SELECT * FROM prekey_firmada_local")
    fun preKeysFirmadas(): List<PreKeyFirmadaLocalEnt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarPreKeyFirmada(k: PreKeyFirmadaLocalEnt)

    @Query("DELETE FROM prekey_firmada_local WHERE keyId = :id")
    fun borrarPreKeyFirmada(id: Int)

    @Query("SELECT * FROM prekey_kyber_local WHERE keyId = :id")
    fun preKeyKyber(id: Int): PreKeyKyberLocalEnt?

    @Query("SELECT * FROM prekey_kyber_local")
    fun preKeysKyber(): List<PreKeyKyberLocalEnt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarPreKeyKyber(k: PreKeyKyberLocalEnt)

    @Query("UPDATE prekey_kyber_local SET usada = 1 WHERE keyId = :id")
    fun marcarKyberUsada(id: Int)

    // --- repeticion de clave base -------------------------------------

    @Query("SELECT COUNT(*) FROM clave_base_vista WHERE kyberId = :kyber AND preKeyId = :pre AND claveBase = :base")
    fun claveBaseVista(kyber: Int, pre: Int, base: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun anotarClaveBase(c: ClaveBaseVistaEnt)

    // --- claves de emisor (grupos) ------------------------------------

    @Query("SELECT * FROM clave_emisor WHERE direccion = :dir AND distribucionId = :dist")
    fun claveEmisor(dir: String, dist: String): ClaveEmisorEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarClaveEmisor(c: ClaveEmisorEnt)

    @Query("SELECT * FROM distribucion_grupo WHERE conversacionId = :conv")
    fun distribucion(conv: String): DistribucionGrupoEnt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarDistribucion(d: DistribucionGrupoEnt)

    @Query("DELETE FROM distribucion_grupo WHERE conversacionId = :conv")
    fun borrarDistribucion(conv: String)

    /** Borra TODO el estado criptografico. Solo al cerrar sesion. */
    @Query("DELETE FROM sesion_signal")
    fun borrarTodasLasSesiones()

    @Query("DELETE FROM clave_emisor")
    fun borrarTodasLasClavesEmisor()

    @Query("DELETE FROM distribucion_grupo")
    fun borrarTodasLasDistribuciones()
}
