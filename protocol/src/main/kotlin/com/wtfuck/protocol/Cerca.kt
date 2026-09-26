package com.wtfuck.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Hablar sin servidor, entre dos telefonos que estan cerca.
 *
 * ## Por que esto encaja sin retorcer nada
 *
 * El servidor de esta app es un buzon tonto: mueve sobres cifrados que no puede
 * abrir. Un enlace Bluetooth puede mover **exactamente los mismos sobres**. No
 * hace falta otro formato, ni otro cifrado, ni confiar en el enlace — porque
 * tampoco se confia en el servidor.
 *
 * Eso es lo que convierte "mensajeria sin internet" en un transporte mas en vez
 * de en un producto aparte.
 *
 * ## El limite honesto: solo con quien ya hablaste
 *
 * El cifrado de Signal necesita una sesion, y abrir una por primera vez exige
 * las claves publicas del otro, que viven en el servidor. Sin internet no hay
 * forma de pedirlas.
 *
 * Asi que esto sirve entre gente que **ya tiene sesion**: se hablaron alguna
 * vez con internet. No es un defecto que se pueda arreglar aqui; es de donde
 * salen las claves.
 *
 * ## Y por eso se rechazan los mensajes que ABREN sesion
 *
 * Ver [aceptable]. Un `PreKeySignalMessage` establece una sesion con la
 * identidad que traiga dentro. Por el servidor eso esta bien: hay una cuenta
 * detras, con su contrasena y su dispositivo registrado. Por el aire no hay
 * nada de eso — cualquiera con una radio y esta app modificada podria abrir una
 * sesion a nombre de quien quiera y aparecer en la pantalla de alguien.
 *
 * Detectarlo despues seria posible —cambiaria la huella y saltaria el aviso de
 * "la clave de seguridad cambio"— pero un aviso que se lee despues de haber
 * leido el mensaje llega tarde. Aqui directamente no se acepta.
 */
@Serializable
sealed interface MensajeCerca {

    /**
     * Lo primero que se manda al conectar: quien dice ser cada lado.
     *
     * **Es una afirmacion, no una prueba**, y da igual: sirve para saber a que
     * dispositivo dirigir los sobres, y quien no sea quien dice no va a poder
     * abrir ninguno. Lo que autentica es el cifrado, no el saludo — igual que
     * pasa con el servidor, que tampoco puede abrir lo que mueve.
     */
    @Serializable
    @SerialName("saludo")
    data class Saludo(
        val usuarioId: String,
        val username: String,
        val dispositivoId: String,
        /** Para poder cambiar el formato sin que dos versiones se peleen. */
        val version: Int = VERSION,
    ) : MensajeCerca

    /**
     * Un sobre, tal cual viajaria por el buzon.
     *
     * Los campos son los de `Bajada.Entrega` porque quien recibe hace con el
     * exactamente lo mismo: descifrar con la sesion del origen y guardar. Un
     * formato propio habria obligado a un segundo camino de entrada, que es un
     * segundo sitio donde equivocarse con contenido ajeno.
     */
    @Serializable
    @SerialName("sobre")
    data class Sobre(
        val sobreId: String,
        val mensajeId: String,
        val conversacionId: String,
        val origenUsuarioId: String,
        val origenUsername: String,
        val origenDispositivo: String,
        /** A que aparato va. Un sobre que no es para mi se descarta. */
        val destinoDispositivo: String,
        /** Base64 del cuerpo cifrado, igual que por el buzon. */
        val cuerpo: String,
        val tipo: Int,
        val creadoEn: Long,
    ) : MensajeCerca

    companion object {
        const val VERSION = 1
    }
}

/**
 * El marco de las tramas: cuatro bytes de largo y despues el contenido.
 *
 * ## Por que hace falta un marco
 *
 * RFCOMM es un flujo de bytes, no de mensajes: lo que se escribe de una vez
 * puede llegar partido en tres, y tres escrituras pueden llegar juntas. Sin un
 * largo por delante, quien lee no sabe donde termina un sobre y empieza el
 * siguiente, y el primer JSON partido rompe todo lo que venga detras.
 */
object Trama {

    /**
     * Lo mas grande que se acepta leer.
     *
     * Un sobre cifrado no pasa de 64 KiB —el servidor lo rechaza por encima de
     * eso— y el relleno lo deja como mucho en 60 KiB mas la cabecera. 128 KiB
     * deja margen de sobra y sigue siendo un tope.
     *
     * El tope es lo que impide que alguien mande `0x7FFFFFFF` como largo y este
     * lado intente reservar dos gigabytes. Es la primera linea que se lee de
     * un desconocido, asi que es donde tiene que estar el limite.
     */
    const val MAXIMO = 128 * 1024

    fun escribir(salida: OutputStream, datos: ByteArray) {
        require(datos.size <= MAXIMO) { "trama de ${datos.size} bytes, maximo $MAXIMO" }
        val n = datos.size
        salida.write(
            byteArrayOf(
                (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(),
            )
        )
        salida.write(datos)
        salida.flush()
    }

    /**
     * Lee una trama entera, o lanza.
     *
     * Lanza y no devuelve null a proposito: un largo imposible o un flujo
     * cortado a la mitad no son "no hay nada todavia", son un enlace que ya no
     * sirve. Devolver null invitaria a seguir leyendo del mismo sitio, y lo
     * unico que se puede hacer con un flujo desincronizado es cerrarlo.
     */
    fun leer(entrada: InputStream): ByteArray {
        val cabecera = leerExacto(entrada, 4)
        val n = ((cabecera[0].toInt() and 0xFF) shl 24) or
            ((cabecera[1].toInt() and 0xFF) shl 16) or
            ((cabecera[2].toInt() and 0xFF) shl 8) or
            (cabecera[3].toInt() and 0xFF)
        if (n < 0 || n > MAXIMO) {
            throw IllegalArgumentException("Largo de trama invalido: $n")
        }
        return leerExacto(entrada, n)
    }

    private fun leerExacto(entrada: InputStream, cuantos: Int): ByteArray {
        val buf = ByteArray(cuantos)
        var leidos = 0
        while (leidos < cuantos) {
            // `read` puede devolver menos de lo pedido y eso es normal, no un
            // error: hay que insistir hasta completar. La primera version de
            // esto en cualquier proyecto suele asumir que lee todo de una vez,
            // y funciona hasta que el mensaje pasa del tamano de un paquete.
            val n = entrada.read(buf, leidos, cuantos - leidos)
            if (n < 0) throw EOFException("El otro lado cerro a mitad de una trama")
            leidos += n
        }
        return buf
    }
}

/**
 * Si un sobre que llego por el aire se puede aceptar.
 *
 * Solo los que continuan una sesion que ya existe. Ver la nota de
 * [MensajeCerca] para el porque: por el aire no hay ninguna cuenta detras, y un
 * mensaje que ABRE sesion permitiria a cualquiera con una radio aparecer en la
 * pantalla de alguien con el nombre que quisiera.
 *
 * @param tipo el de [TipoCifrado], tal como vino.
 * @param haySesion si este telefono ya tiene una sesion con ese origen.
 */
fun aceptable(tipo: Int, haySesion: Boolean): Boolean = when (tipo) {
    // Continua una sesion existente: solo sirve si de verdad existe.
    TipoCifrado.SESION -> haySesion
    // Mensaje de grupo con clave de emisor. Tambien exige sesion previa: la
    // clave de emisor llega DENTRO de un sobre por pares, asi que sin sesion
    // no hay forma de haberla recibido.
    TipoCifrado.GRUPO -> haySesion
    // Abre sesion. Por el aire, nunca.
    TipoCifrado.PREPARADO -> false
    // Sin cifrar. Existe para depurar el transporte contra un servidor de
    // desarrollo; por el aire no tiene ningun sentido y seria un agujero.
    TipoCifrado.PLANO -> false
    else -> false
}

/**
 * Las clases mayores de aparato Bluetooth que importan aqui.
 *
 * Son los mismos numeros que `android.bluetooth.BluetoothClass.Device.Major`,
 * copiados para que esto viva en el modulo del protocolo —Kotlin puro— y se
 * pueda probar sin un emulador. Son constantes del estandar, no de Android: no
 * cambian.
 */
object ClaseBt {
    const val COMPUTADORA = 0x0100
    const val TELEFONO = 0x0200
    const val RED = 0x0300
    const val AUDIO_VIDEO = 0x0400
    const val PERIFERICO = 0x0500
    const val IMAGEN = 0x0600
    const val VESTIBLE = 0x0700
    const val JUGUETE = 0x0800
    const val SALUD = 0x0900
    const val SIN_CATEGORIA = 0x1F00
}

/**
 * Si vale la pena intentar un enlace con un aparato ya emparejado.
 *
 * ## El defecto que esto arregla, y por que el emulador no podia verlo
 *
 * La busqueda recorre los emparejados del sistema cada pocos segundos. En un
 * emulador esa lista esta VACIA —`Bonded devices: 0`, las radios virtuales
 * estan aisladas— asi que el bucle no tocaba nada y todo parecia bien.
 *
 * En un telefono de verdad esa lista son los audifonos, el carro, el reloj, el
 * parlante. Intentarles un `connect()` de RFCOMM cada pocos segundos le pega a
 * un enlace de audio que esta en uso: corta, cambia de perfil o directamente
 * se oye. Nadie relacionaria eso con una app de mensajeria.
 *
 * ## Que se deja pasar
 *
 * Telefonos, computadoras (por las tablets) y los que no declaran categoria.
 * Todo lo demas se descarta **sin abrir un socket**, que es lo que evita el
 * dano: un audifono no va a tener esta app corriendo, asi que el intento no
 * podia salir bien de todos modos — solo molestar.
 *
 * No es una comprobacion de seguridad y no se usa como tal: quien quiera
 * mentir sobre su clase puede. Es para no romper el audio de quien enciende
 * esto, que es un problema distinto y mas probable.
 */
fun valeLaPenaIntentar(claseMayor: Int): Boolean = when (claseMayor) {
    ClaseBt.TELEFONO -> true
    // Las tablets se anuncian como computadora, y una tablet con la app es un
    // destino legitimo.
    ClaseBt.COMPUTADORA -> true
    // Sin categoria: se intenta. Es el unico caso donde equivocarse por no
    // intentar seria peor que por intentar — un telefono que no declara su
    // clase quedaria fuera para siempre y nadie sabria por que.
    ClaseBt.SIN_CATEGORIA -> true
    else -> false
}
