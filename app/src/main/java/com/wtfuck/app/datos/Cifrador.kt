package com.wtfuck.app.datos

import com.wtfuck.protocol.Base64Util
import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.Relleno
import com.wtfuck.protocol.CopiaCifrada
import com.wtfuck.protocol.DestinoDispositivo
import com.wtfuck.protocol.TipoCifrado

/**
 * De donde viene un sobre. Lo que hace falta para poder abrirlo.
 *
 * Lleva el DISPOSITIVO y no solo la persona porque una sesion de Signal es
 * entre dos dispositivos: dos telefonos de la misma persona son dos sesiones
 * distintas y no se pueden confundir.
 */
data class OrigenSobre(
    val usuarioId: String,
    val username: String,
    val dispositivoId: String,
    val tipo: Int,
)

/**
 * La frontera del cifrado.
 *
 * Todo lo que sale de la app pasa por `cifrar`; todo lo que entra, por
 * `descifrar`. El resto del codigo solo maneja [Carga] en claro y bytes
 * opacos, nunca las dos cosas a la vez.
 *
 * `cifrar` devuelve una LISTA porque con cifrado de extremo a extremo no hay
 * un cuerpo que el servidor pueda copiar: hay uno por dispositivo destino. Esa
 * firma es la que obliga al resto del sistema a respetarlo.
 */
interface Cifrador {

    /**
     * Cifra para todos los destinos.
     *
     * `esGrupo` no es cosmetico: decide el esquema. Uno a uno usa sesiones por
     * pares -un cuerpo por dispositivo-; un grupo usa clave de emisor, y
     * entonces un mismo cuerpo sirve para todos los que ya la tengan.
     */
    suspend fun cifrar(
        conversacionId: String,
        esGrupo: Boolean,
        destinos: List<DestinoDispositivo>,
        carga: Carga,
    ): List<CopiaCifrada>

    suspend fun descifrar(conversacionId: String, origen: OrigenSobre, bytes: ByteArray): Carga

    /**
     * Si ya hay una sesion con ese aparato.
     *
     * Existe por el transporte de cerca (modulo AZ): por el aire solo se
     * aceptan sobres que CONTINUAN una sesion, nunca los que abren una. Sin
     * poder preguntarlo, esa regla no se puede aplicar.
     *
     * Por defecto `false`: un cifrador que no sabe contestar tiene que hacer
     * que se rechace, no que se acepte.
     */
    suspend fun haySesionCon(usuarioId: String, dispositivoId: String): Boolean = false

    /**
     * Una copia para UN aparato, por pares, solo si ya hay sesion con el.
     *
     * Es lo que usa el modo cerca sin red. Por pares aunque sea un grupo: la
     * clave de emisor lleva la cuenta de a quien se repartio, y cifrar para un
     * solo miembro la haria creer que salieron todos los demas -y la rotaria
     * en cada mensaje-. Sin red no se abre sesion: si no la hay, `null`.
     *
     * Por defecto `null`: un cifrador que no sabe hacerlo no manda nada.
     */
    suspend fun cifrarSoloPara(destino: DestinoDispositivo, carga: Carga): CopiaCifrada? = null

    /**
     * Modo cerca, fase 1: la curva de las claves efimeras del enlace. `null`
     * en un cifrador sin identidad -el de desarrollo-, y entonces no hay enlace
     * sin emparejar. Ver `Apreton`.
     */
    val curvaCerca: com.wtfuck.protocol.Curva? get() = null

    /** La identidad publica de un aparato (32 bytes), si la conozco. */
    fun identidadCerca(dispositivoId: String): ByteArray? = null

    /** DH entre MI identidad y una clave publica. La privada no sale de aqui. */
    fun acordarConMiIdentidad(publica: ByteArray): ByteArray =
        throw UnsupportedOperationException("Este cifrador no tiene identidad")

    /**
     * El transporte acepto el envio.
     *
     * Existe por las claves de emisor: hasta que el servidor no acepta, no se
     * puede dar por repartida la clave. Si se marcara antes y el envio fallara,
     * el proximo mensaje iria sin la clave y quien recibe no podria abrirlo
     * nunca. Para el cifrado por pares no hace nada.
     */
    suspend fun confirmarEnvio(conversacionId: String, destinos: List<DestinoDispositivo>) {}

    /** Se llama al arrancar con sesion: publica o repone claves si hace falta. */
    suspend fun prepararClaves() {}

    /** Nombre corto para la UI: la persona tiene derecho a saber que protege su chat. */
    val etiqueta: String
}

/**
 * SIN CIFRADO. El cuerpo viaja como JSON.
 *
 * Existe solo para depurar el transporte: permite mirar en la base del
 * servidor que llego y comparar con lo que se mando. El servidor igual no lo
 * interpreta -lo guarda como bytea opaco-, pero un administrador de la base SI
 * podria leerlo.
 *
 * NO usar en produccion. [CifradorSignal] es el que cumple la promesa.
 */
class CifradorPlano : Cifrador {

    override val etiqueta = "Sin cifrar (desarrollo)"

    override suspend fun cifrar(
        conversacionId: String,
        esGrupo: Boolean,
        destinos: List<DestinoDispositivo>,
        carga: Carga,
    ): List<CopiaCifrada> {
        // Un solo cuerpo para todos: sin cifrar, todos los destinos pueden
        // compartir los mismos bytes.
        if (destinos.isEmpty()) return emptyList()
        // Tambien aqui, aunque este cifrador no cifre: si el modo de
        // desarrollo no rellenara, los sobres tendrian tamanos distintos en
        // desarrollo y en produccion, y una prueba que mida tamanos no
        // valdria para nada.
        val cuerpo = Base64Util.enc(
            Relleno.poner(jsonApp.encodeToString(Carga.serializer(), carga).toByteArray()),
        )
        return listOf(CopiaCifrada(destinos.map { it.dispositivoId }, cuerpo, TipoCifrado.PLANO))
    }

    override suspend fun descifrar(
        conversacionId: String,
        origen: OrigenSobre,
        bytes: ByteArray,
    ): Carga = jsonApp.decodeFromString(Carga.serializer(), String(Relleno.quitar(bytes)))
}
