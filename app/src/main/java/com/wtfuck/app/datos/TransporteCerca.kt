package com.wtfuck.app.datos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.wtfuck.protocol.MensajeCerca
import com.wtfuck.protocol.Transporte
import com.wtfuck.protocol.Trama
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Mensajería entre dos teléfonos que están cerca, sin internet.
 *
 * ## Qué es esto en una frase
 *
 * Otro transporte para los mismos sobres. El servidor de esta app es un buzón
 * tonto que mueve bytes que no puede abrir; este enlace hace lo mismo a diez
 * metros. Por eso no hay un formato nuevo, ni un cifrado nuevo, ni un camino
 * de entrada nuevo — los sobres que llegan por aquí entran por donde entran
 * los del buzón.
 *
 * ## Por qué RFCOMM y no BLE
 *
 * Porque un sobre pesa. Con el relleno del módulo AR, el más chico son 256
 * bytes y el más grande 60 KiB; por BLE, con una MTU de unos cientos de bytes,
 * eso son cientos de paquetes y una máquina de estados para rearmarlos. RFCOMM
 * es un flujo de bytes con cientos de kbit/s y una API de socket — que es
 * justo lo que hace falta cuando ya se tiene el dato listo para mandar.
 *
 * El precio es el alcance —unos diez metros— y que consume más. Para "estamos
 * en la misma sala y no hay señal", que es el caso, alcanza.
 *
 * ## Las dos cosas que NO hace, y hay que decirlas
 *
 *  - **No sirve con alguien con quien nunca hablaste.** Abrir una sesión de
 *    Signal necesita las claves públicas del otro, y esas viven en el
 *    servidor. Sin internet no hay de dónde sacarlas.
 *  - **No reemplaza al servidor para el resto.** Los avisos de sistema, las
 *    llamadas, los canales y el historial siguen necesitándolo. Esto mueve
 *    mensajes, que es lo que importa cuando no hay señal.
 */
class TransporteCerca(
    private val ctx: Context,
    private val sesion: Sesion,
    /** Qué hacer con un sobre que llegó. Devuelve si se aceptó. */
    private val alRecibir: suspend (MensajeCerca.Sobre) -> Boolean,
) : Transporte {

    // ------------------------------------------------------------------
    // Como transporte
    // ------------------------------------------------------------------

    override val nombre = "cerca"

    /**
     * Prioridad 10: despues del WebSocket.
     *
     * Ocupa el hueco que la fase 2 dejo reservado, con un
     * comentario que decia exactamente lo que faltaba. El despachador, la
     * cola, la interfaz y el esquema no cambian — que era el punto de haberlo
     * dejado cableado.
     *
     * Despues y no antes de internet porque el buzon llega a TODOS los
     * destinos y esto solo al que esta enfrente. Con internet, mandar por aqui
     * seria entregarle a uno y dejar a los demas esperando.
     */
    override val prioridad = 10

    override fun disponible(): Boolean = _estado.value == Estado.ENLAZADO

    /**
     * Entrega la copia que le toca a quien esta enfrente.
     *
     * Un sobre lleva una copia por dispositivo destino; por aqui solo puede
     * salir la del aparato enlazado. Si ese aparato no esta entre los
     * destinos, **falla a proposito**: el sobre se queda en la cola para
     * cuando haya internet. Darlo por entregado seria perder el mensaje para
     * todos los demas.
     */
    override suspend fun entregar(sobre: com.wtfuck.protocol.Sobre): Result<Unit> {
        val destino = dispositivoEnlazado
            ?: return Result.failure(IllegalStateException("Sin enlace"))
        val copia = sobre.copias.firstOrNull { destino in it.destinos }
            ?: return Result.failure(IllegalStateException("El sobre no es para quien esta cerca"))

        val ok = mandar(
            MensajeCerca.Sobre(
                sobreId = sobre.id,
                // El mismo id para los dos: por el buzon el servidor deriva
                // uno por destino, y aqui hay un solo destino, asi que
                // coinciden — que es justo lo que pasa en una directa.
                mensajeId = sobre.id,
                conversacionId = sobre.conversacionId,
                origenUsuarioId = sesion.usuarioId.orEmpty(),
                origenUsername = sesion.username.orEmpty(),
                origenDispositivo = sobre.origenDispositivo,
                destinoDispositivo = destino,
                cuerpo = copia.cuerpo,
                tipo = copia.tipo,
                creadoEn = sobre.creadoEn,
            )
        )
        return if (ok) Result.success(Unit)
        else Result.failure(IllegalStateException("No se pudo escribir en el enlace"))
    }

    private val TAG = "Cerca"

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _estado = MutableStateFlow(Estado.APAGADO)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val _conQuien = MutableStateFlow<String?>(null)
    /** Username del otro lado mientras hay enlace, o `null`. */
    val conQuien: StateFlow<String?> = _conQuien.asStateFlow()

    enum class Estado { APAGADO, ESCUCHANDO, ENLAZADO }

    private var servidor: BluetoothServerSocket? = null
    private var enlace: BluetoothSocket? = null
    private var suyo: MensajeCerca.Saludo? = null

    private val adaptador: BluetoothAdapter?
        get() = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter

    // ------------------------------------------------------------------
    // Encender y apagar
    // ------------------------------------------------------------------

    /** Si este aparato tiene radio Bluetooth. Hay tablets y emuladores que no. */
    fun hayRadio(): Boolean = adaptador != null

    /**
     * Si la radio esta encendida.
     *
     * Se pregunta y no se enciende sola: prender la radio de alguien sin
     * avisar es del sistema, no de una app de mensajeria. La pantalla lo dice
     * y la persona decide.
     */
    @SuppressLint("MissingPermission")
    fun radioEncendida(): Boolean = runCatching { adaptador?.isEnabled == true }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun encender(): Boolean {
        val a = adaptador ?: run {
            Log.w(TAG, "Este aparato no tiene Bluetooth")
            return false
        }
        if (!a.isEnabled) {
            Log.w(TAG, "Bluetooth apagado")
            return false
        }
        if (_estado.value != Estado.APAGADO) return true

        _estado.value = Estado.ESCUCHANDO
        // Se escucha Y se busca a la vez, los dos lados igual.
        //
        // La alternativa era que uno haga de servidor y el otro de cliente, y
        // eso obliga a que las dos personas se pongan de acuerdo en quién es
        // cuál antes de poder hablar — justo cuando no tienen por dónde
        // ponerse de acuerdo. Con los dos haciendo las dos cosas, el primero
        // que conecte gana y el otro deja de intentar.
        ambito.launch { escuchar() }
        ambito.launch { buscar() }
        return true
    }

    fun apagar() {
        _estado.value = Estado.APAGADO
        _conQuien.value = null
        suyo = null
        runCatching { servidor?.close() }
        runCatching { enlace?.close() }
        servidor = null
        enlace = null
    }

    // ------------------------------------------------------------------
    // El lado que espera
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun escuchar() {
        val a = adaptador ?: return
        while (_estado.value != Estado.APAGADO) {
            val s = runCatching {
                // `Insecure` = sin emparejar. Emparejar dos teléfonos para
                // mandarse un mensaje es pedirle a la gente que haga un
                // trámite del sistema operativo en medio de una conversación.
                //
                // Y no hace falta: lo que viaja ya va cifrado de extremo a
                // extremo. El emparejamiento cifraría el enlace, que es
                // exactamente la capa en la que aquí no se confía.
                a.listenUsingInsecureRfcommWithServiceRecord(SERVICIO, UUID_APP)
            }.getOrNull() ?: return
            servidor = s

            val cliente = runCatching { s.accept() }.getOrNull()
            runCatching { s.close() }
            if (cliente == null) continue
            if (enlace != null) { runCatching { cliente.close() }; continue }
            atender(cliente)
        }
    }

    // ------------------------------------------------------------------
    // El lado que busca
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun buscar() {
        val a = adaptador ?: return
        while (_estado.value == Estado.ESCUCHANDO) {
            // Se prueba con los EMPAREJADOS del sistema primero.
            //
            // No porque haga falta emparejar —no hace— sino porque la
            // búsqueda activa de Bluetooth tarda doce segundos, satura la
            // radio y deja la conexión inestable mientras corre. Si los dos
            // teléfonos ya se conocen del sistema, esto conecta en un segundo.
            for (d in runCatching { a.bondedDevices }.getOrNull().orEmpty()) {
                if (_estado.value != Estado.ESCUCHANDO) return
                if (intentar(d)) return
            }
            kotlinx.coroutines.delay(5_000)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun intentar(d: BluetoothDevice): Boolean {
        val s = runCatching { d.createInsecureRfcommSocketToServiceRecord(UUID_APP) }
            .getOrNull() ?: return false
        return try {
            s.connect()
            atender(s)
            true
        } catch (e: IOException) {
            // Lo normal: ese aparato no tiene esta app escuchando. No es un
            // error que haya que contar, es como se descarta un candidato.
            runCatching { s.close() }
            false
        }
    }

    // ------------------------------------------------------------------
    // El enlace
    // ------------------------------------------------------------------

    private suspend fun atender(s: BluetoothSocket) {
        enlace = s
        _estado.value = Estado.ENLAZADO
        Log.i(TAG, "Enlazado")

        val entrada = runCatching { s.inputStream }.getOrNull()
        val salida = runCatching { s.outputStream }.getOrNull()
        if (entrada == null || salida == null) { cortar(); return }

        // El saludo va primero y por los dos lados. Es una AFIRMACIÓN, no una
        // prueba: sirve para saber a qué dispositivo dirigir los sobres, y
        // quien no sea quien dice no va a poder abrir ninguno.
        runCatching {
            Trama.escribir(
                salida,
                jsonApp.encodeToString(
                    MensajeCerca.serializer(),
                    MensajeCerca.Saludo(
                        usuarioId = sesion.usuarioId.orEmpty(),
                        username = sesion.username.orEmpty(),
                        dispositivoId = sesion.dispositivoId.orEmpty(),
                    ),
                ).toByteArray(),
            )
        }.onFailure { cortar(); return }

        while (_estado.value == Estado.ENLAZADO) {
            val crudo = runCatching { Trama.leer(entrada) }.getOrElse {
                Log.i(TAG, "Enlace terminado: ${it.message}")
                cortar()
                return
            }
            val msg = runCatching {
                jsonApp.decodeFromString(MensajeCerca.serializer(), String(crudo))
            }.getOrNull() ?: continue

            when (msg) {
                is MensajeCerca.Saludo -> {
                    suyo = msg
                    _conQuien.value = msg.username
                    Log.i(TAG, "Del otro lado dice ser @${msg.username}")
                }
                is MensajeCerca.Sobre -> {
                    // Un sobre que no es para este aparato se descarta sin
                    // mirarlo. Puede pasar sin mala intención —una app vieja,
                    // un reenvío— y no hay nada sensato que hacer con él.
                    if (msg.destinoDispositivo != sesion.dispositivoId) continue
                    runCatching { alRecibir(msg) }
                }
            }
        }
    }

    private fun cortar() {
        runCatching { enlace?.close() }
        enlace = null
        suyo = null
        _conQuien.value = null
        if (_estado.value != Estado.APAGADO) {
            _estado.value = Estado.ESCUCHANDO
            ambito.launch { buscar() }
        }
    }

    // ------------------------------------------------------------------
    // Mandar
    // ------------------------------------------------------------------

    /**
     * Manda un sobre por el enlace, si hay y si es para quien está del otro
     * lado.
     *
     * @return `false` si no se pudo. Quien llama tiene que dejar el sobre en
     *   la cola: un sobre que se da por enviado y no salió es un mensaje
     *   perdido, y aquí el enlace se cae cada vez que alguien se aleja.
     */
    fun mandar(sobre: MensajeCerca.Sobre): Boolean {
        val s = enlace ?: return false
        if (suyo?.dispositivoId != sobre.destinoDispositivo) return false
        return runCatching {
            Trama.escribir(
                s.outputStream,
                jsonApp.encodeToString(MensajeCerca.serializer(), sobre).toByteArray(),
            )
            true
        }.getOrElse {
            Log.w(TAG, "No se pudo mandar por el enlace: ${it.message}")
            cortar()
            false
        }
    }

    /** El dispositivo que está del otro lado ahora, o `null`. */
    val dispositivoEnlazado: String? get() = suyo?.dispositivoId

    private companion object {
        const val SERVICIO = "wtfuck-cerca"

        /**
         * El UUID del servicio RFCOMM.
         *
         * Es fijo y propio de esta app: es lo que hace que un teléfono
         * cualquiera rechace la conexión y uno con wtfuck la acepte. No es un
         * secreto ni protege nada — cualquiera puede leerlo del APK — sólo
         * distingue.
         */
        val UUID_APP: UUID = UUID.fromString("7c9f1a2e-4b6d-4e8a-9f3c-b1a2c3000001")
    }
}
