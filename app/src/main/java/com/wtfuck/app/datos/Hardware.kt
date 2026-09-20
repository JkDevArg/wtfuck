package com.wtfuck.app.datos

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey

/**
 * Identidad del dispositivo.
 *
 * El par de claves se genera DENTRO del Keystore y la privada no es exportable:
 * ni con root sale del chip. Eso es lo que convierte al dispositivo en algo que
 * el servidor puede verificar en vez de creer.
 *
 * Ver docs/04-DEVICE-BINDING.md para los limites reales de esto.
 */
object Hardware {

    private const val ALIAS = "wtfuck_identidad_v1"
    private const val TAG = "Hardware"

    data class Identidad(
        /** Clave publica en formato X.509, Base64. */
        val identidadPub: String,
        /** Base64 de SHA-256(clave_publica || SSAID). */
        val hardwareHash: String,
        /** STRONGBOX | TEE | SOFTWARE_DEV */
        val nivel: String,
    )

    fun identidad(ctx: Context): Identidad {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: run {
            generar()
            ks.load(null)
            ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        }

        val pub = entry.certificate.publicKey.encoded
        val ssaid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "sin-ssaid"

        // El hash se deriva SOLO del SSAID, a proposito.
        //
        // La clave del Keystore NO entra aqui: se regenera al borrar los datos de
        // la app, y si formara parte del hash, cada "borrar datos" liberaria el
        // vinculo y permitiria una cuenta nueva por hardware. El SSAID, en cambio,
        // sobrevive a eso y solo muere con un factory reset, que es justamente el
        // techo declarado en docs/04-DEVICE-BINDING.md
        //
        // La clave atestada sigue cumpliendo su papel, que es otro: probar que el
        // dispositivo tiene enclave seguro (ver [nivelDe]) y firmar. Identidad
        // continua y prueba de hardware son dos cosas distintas.
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(("wtfuck:v1:" + ssaid).toByteArray())

        return Identidad(
            identidadPub = Base64.encodeToString(pub, Base64.NO_WRAP),
            hardwareHash = Base64.encodeToString(hash, Base64.NO_WRAP),
            nivel = nivelDe(entry.privateKey),
        )
    }

    private fun generar() {
        val reto = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // El reto es lo que impide reciclar una cadena de atestacion vieja.
            .setAttestationChallenge(reto)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /**
     * Donde vive realmente la clave privada.
     *
     * En un emulador esto devuelve SOFTWARE_DEV: no hay TEE. Por eso el servidor
     * acepta ese nivel solo cuando WTFUCK_PERMITIR_SOFTWARE_DEV esta activo.
     */
    private fun nivelDe(privada: PrivateKey): String = try {
        val info = KeyFactory.getInstance(privada.algorithm, "AndroidKeyStore")
            .getKeySpec(privada, KeyInfo::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                else -> "SOFTWARE_DEV"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "TEE" else "SOFTWARE_DEV"
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo determinar el nivel de hardware: ${e.message}")
        "SOFTWARE_DEV"
    }
}
