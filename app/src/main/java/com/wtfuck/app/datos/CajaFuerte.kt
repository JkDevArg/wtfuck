package com.wtfuck.app.datos

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Envolver un secreto con una clave que no sale del aparato.
 *
 * ## Qué problema resuelve
 *
 * Todo lo que esta app guarda en `SharedPreferences` termina en un XML dentro
 * de `/data/data/com.wtfuck.app/shared_prefs/`. El recinto de Android lo
 * protege de otras apps, y `allowBackup="false"` lo deja fuera de las copias
 * de seguridad — pero no de un aparato con root, de una imagen forense ni de
 * un volcado del almacenamiento.
 *
 * Envuelto con una clave de `AndroidKeyStore`, ese XML deja de servir solo: la
 * clave que lo abre **no es exportable**, vive en el almacén del sistema (en
 * hardware, donde lo haya) y no se puede copiar junto con el archivo.
 *
 * ## Por qué esto existe como objeto aparte
 *
 * Porque ya se hacía, pero para una sola cosa. La frase de paso de SQLCipher
 * se guardaba envuelta así desde el principio, y el **token de sesión** —que
 * da acceso completo a la cuenta contra el servidor— se guardaba en texto
 * plano en la carpeta de al lado. Dos secretos, el mismo sitio, dos niveles de
 * protección, y ninguna razón escrita para la diferencia.
 *
 * Las diferencias de protección sin motivo declarado son accidentes, no
 * decisiones. Sacar esto a un objeto con nombre hace que el siguiente secreto
 * que alguien guarde tenga un sitio evidente al que ir.
 *
 * ## Lo que no resuelve
 *
 * Con la app corriendo y desbloqueada, cualquiera que ejecute código dentro de
 * este proceso puede pedirle al Keystore que descifre. Esto protege el
 * **archivo en reposo**, no el proceso vivo. Para lo segundo haría falta
 * `setUserAuthenticationRequired`, y eso obligaría a poner la huella cada vez
 * que la app abre un socket.
 */
class CajaFuerte(private val alias: String) {

    /** Envuelve. Devuelve `iv:cifrado`, los dos en Base64. */
    fun cerrar(datos: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, clave()) }
        val ct = c.doFinal(datos)
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun cerrarTexto(texto: String): String = cerrar(texto.toByteArray())

    /**
     * Abre, o lanza.
     *
     * Lanza de verdad —no devuelve null— porque quien llama tiene que decidir
     * qué significa: para la base de datos significa que el historial local es
     * irrecuperable, y para la sesión significa volver a entrar. Tragarse la
     * excepción aquí convertiría las dos cosas en la misma.
     */
    fun abrir(guardado: String): ByteArray {
        val partes = guardado.split(":", limit = 2)
        require(partes.size == 2) { "formato invalido" }
        val iv = Base64.decode(partes[0], Base64.NO_WRAP)
        val ct = Base64.decode(partes[1], Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMACION).apply {
            init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, iv))
        }
        return c.doFinal(ct)
    }

    fun abrirTexto(guardado: String): String? =
        runCatching { String(abrir(guardado)) }.getOrNull()

    /**
     * Tira la clave.
     *
     * Hace falta para poder recuperarse: una clave del Keystore **se puede
     * invalidar** —cambio de credenciales del aparato en algunos fabricantes,
     * una restauración, una actualización del sistema que rota el almacén—, y
     * entonces todo lo envuelto con ella es ruido para siempre. Sin una forma
     * de empezar de nuevo, la app no arranca nunca más y la única salida es
     * desinstalarla.
     */
    fun tirar() {
        runCatching {
            KeyStore.getInstance(ALMACEN).apply { load(null) }.deleteEntry(alias)
        }
    }

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance(ALMACEN).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ALMACEN).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // NO se pone `setUnlockedDeviceRequired(true)`, y la
                    // tentación es fuerte: con eso, lo envuelto no se abre
                    // mientras el teléfono está bloqueado, que es justo el
                    // escenario en que se pierde.
                    //
                    // Rompería la app. Con la pantalla bloqueada tiene que
                    // seguir funcionando un aviso push —abrir el socket exige
                    // el token—, una llamada entrante y el compartido de
                    // ubicación en vivo, que escribe en la base cada medio
                    // minuto durante horas. Las tres dejarían de andar
                    // exactamente cuando el teléfono está en el bolsillo, que
                    // es como se usa un teléfono.
                    //
                    // Endurecer hasta romper la función no es endurecer; es
                    // apagarla y llamarlo seguridad.
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ALMACEN = "AndroidKeyStore"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
    }
}
