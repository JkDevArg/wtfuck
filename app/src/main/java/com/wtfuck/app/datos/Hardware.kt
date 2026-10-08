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
 * ni con root sale del chip. Eso PERMITIRIA que el servidor verificara el
 * aparato en vez de creerle, pero hoy no lo hace: la cadena de atestacion no se
 * envia, y lo que llega al servidor es el nivel que calcula [nivelDe], o sea una
 * declaracion. Un cliente modificado puede mandar el que quiera.
 *
 * Ver docs/04-DEVICE-BINDING.md: lo que el servidor verifica hoy, y lo que
 * haria falta para verificar de verdad.
 */
object Hardware {

    private const val ALIAS = "wtfuck_identidad_v1"
    private const val TAG = "Hardware"

    data class Identidad(
        /** Clave publica en formato X.509, Base64. */
        val identidadPub: String,
        /** Base64 de SHA-256("wtfuck:v1:" || SSAID). Ver el comentario de [identidad]. */
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
        // La clave del Keystore sirve para otra cosa: saber en que nivel vive
        // (ver [nivelDe]). Identidad continua y prueba de hardware son dos cosas
        // distintas. Y "prueba" es mucho decir mientras la cadena no viaje: hoy
        // el servidor recibe el nivel declarado, no algo que pueda comprobar.
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
            // OJO: este reto NO protege nada. Un reto impide reciclar una cadena
            // vieja solo si lo emite el SERVIDOR y luego lo busca dentro de la
            // cadena; este se inventa aqui y la cadena nunca se envia. Queda
            // para que el Keystore genere la atestacion, y como recordatorio de
            // donde iria el reto de verdad. Ver docs/04-DEVICE-BINDING.md.
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
     * acepta ese nivel solo cuando WTFUCK_PERMITIR_SOFTWARE_DEV=true (el
     * defecto es false). Es lo que la app CREE de si misma y lo que declara: el
     * servidor no tiene forma de comprobarlo.
     */
    /**
     * W5e · La cadena de atestacion para UN alta (registro, vinculo o
     * recuperacion), con el reto que emitio el servidor.
     *
     * Una clave NUEVA cada vez, y se borra al terminar: el reto va dentro del
     * certificado que firma el chip, asi que la cadena sirve para esta peticion
     * y para ninguna otra. La clave de identidad (`ALIAS`) no se toca.
     *
     * Primero intenta StrongBox (el chip aparte, en los telefonos que lo
     * tienen) y si no, el TEE. Si el telefono no puede atestar -o el servidor
     * no da reto, porque es viejo- devuelve una lista vacia: decide el servidor
     * si eso alcanza (`WTFUCK_ATESTACION`).
     */
    fun atestar(retoB64: String?): List<String> {
        if (retoB64.isNullOrBlank()) return emptyList()
        val alias = "wtfuck_atestacion_" + java.util.UUID.randomUUID()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return try {
            val reto = Base64.decode(retoB64, Base64.NO_WRAP)
            fun generar(strongbox: Boolean) {
                val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(reto)
                if (strongbox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    .apply { initialize(b.build()) }
                    .generateKeyPair()
            }
            try {
                generar(strongbox = true)
            } catch (e: Exception) {
                // StrongBoxUnavailableException y parecidas: sin StrongBox, al TEE.
                generar(strongbox = false)
            }
            ks.getCertificateChain(alias)
                ?.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo atestar: ${e.message}")
            emptyList()
        } finally {
            runCatching { ks.deleteEntry(alias) }
        }
    }

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
