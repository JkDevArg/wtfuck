package com.wtfuck.app.datos

import com.wtfuck.app.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * Fijado de certificado (cert pinning) del canal con el servidor.
 *
 * ## Que hace
 *
 * Ata la app a la clave publica del certificado de TU servidor. Aunque alguien
 * logre que el telefono confie en una CA falsa -un proxy corporativo, un equipo
 * intervenido, una CA comprometida-, la conexion se corta si el certificado no
 * es el tuyo. Cubre el canal sensible: la API y el WebSocket (donde viajan el
 * token de sesion y el routing de los mensajes).
 *
 * ## Por que es OPT-IN (apagado por defecto)
 *
 * El pinning es un arma de doble filo. Si el certificado del servidor cambia a
 * una clave que no esta fijada y la app no se actualizo, **la app deja de
 * conectar para todos** hasta que instalen un APK nuevo. En una app que se
 * reparte fuera de una tienda, eso es un ladrillo remoto autoinfligido.
 *
 * Por eso solo se activa a proposito, al compilar:
 *
 *     ./gradlew :app:assembleRelease -Papi=apiwtf.hackl4bs.com \
 *        -Ppin=KNX/BKM3mELOZcbebFrbu52j2tVEuKQmhAqDmt+Sphk=,<pin-de-respaldo>
 *
 * Sin `-Ppin`, `PIN_HASHES` viene vacio y esto no hace nada: validacion de CA
 * normal, como siempre.
 *
 * ## Como no dispararse en el pie
 *
 *  - **Siempre un pin de RESPALDO** ademas del principal. El respaldo es la
 *    clave de un certificado que aun no usas pero controlas (o el intermedio/
 *    raiz de tu CA). Si tienes que rotar la clave del servidor, emites con la
 *    de respaldo y nadie queda fuera.
 *  - **Renueva reusando la clave** (`certbot --reuse-key`): asi la renovacion
 *    de Let's Encrypt cada 90 dias NO cambia la clave y el pin sigue valiendo.
 *  - El pin es del SPKI (la clave publica), no del archivo del certificado: por
 *    eso sobrevive a una renovacion que reusa la clave.
 */
object Pinning {

    /**
     * Aplica el pinning al builder, si hay pines configurados. El host se saca
     * de la URL del servidor: se fija el mismo host con el que se habla.
     */
    fun aplicar(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val hashes = BuildConfig.PIN_HASHES
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (hashes.isEmpty()) return builder

        val host = runCatching { java.net.URI(BuildConfig.SERVIDOR).host }.getOrNull()
            ?: return builder

        val pinner = CertificatePinner.Builder().apply {
            for (h in hashes) {
                // OkHttp quiere el prefijo "sha256/". Se acepta con o sin el,
                // para que copiar el pin de openssl no sea un juego de prefijos.
                add(host, if (h.startsWith("sha256/")) h else "sha256/$h")
            }
        }.build()
        return builder.certificatePinner(pinner)
    }
}
