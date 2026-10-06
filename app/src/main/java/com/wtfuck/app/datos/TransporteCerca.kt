package com.wtfuck.app.datos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.util.Log
import com.wtfuck.app.BuildConfig
import com.wtfuck.protocol.CharlaCerca
import com.wtfuck.protocol.ClaseBt
import com.wtfuck.protocol.MensajeCerca
import com.wtfuck.protocol.valeLaPenaIntentar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mensajería entre dos teléfonos que están cerca, sin internet.
 *
 * ## Qué es esto en una frase
 *
 * Otro camino para los mismos sobres. El servidor de esta app es un buzón
 * tonto que mueve bytes que no puede abrir; este enlace hace lo mismo a diez
 * metros. Los sobres que llegan por aquí entran por donde entran los del buzón.
 *
 * ## Qué cambió en la fase 0 (ver `docs/11-SIN-INTERNET.md`)
 *
 *  - **Ya no es un `Transporte` del despachador.** Estaba en la misma lista que
 *    el WebSocket y eso tenía dos defectos: sin red el despachador moría en el
 *    registro HTTP antes de llegar aquí -recibía pero nunca ENVIABA-, y con el
 *    WebSocket caído pero HTTP vivo mandaba la copia de UNO y daba el resto por
 *    hecho. Ahora el repositorio tiene un camino propio para cuando no hay
 *    servidor (`despacharPorCerca`), y lo demás -ediciones, historial,
 *    llamadas- no lo toca nunca.
 *  - **Enlace cifrado y solo con aparatos emparejados.** El código ya solo
 *    buscaba entre los emparejados del sistema: la promesa de "sin emparejar"
 *    no se cumplía. Con RFCOMM seguro el enlace lo cifra y autentica la clave
 *    del emparejamiento, y el saludo -usuario, aparato- deja de viajar en claro
 *    para cualquiera que se conecte. Quitar el emparejamiento es la fase 1
 *    (balizas BLE), no un ajuste de este archivo.
 *  - **Acuses por el enlace** ([MensajeCerca.Acuse]).
 *  - **Un servicio en primer plano** mientras esté encendido, para que el
 *    enlace no muera al apagar la pantalla, y **se apaga solo** tras media hora
 *    sin nadie: una radio escuchando no puede quedarse olvidada.
 *
 * ## Por qué RFCOMM y no BLE
 *
 * Porque un sobre pesa: entre 256 bytes y 60 KiB. Por BLE eso son cientos de
 * paquetes y una máquina de estados para rearmarlos; RFCOMM es un flujo con
 * cientos de kbit/s. El precio es el alcance -unos diez metros- y el consumo.
 *
 * ## Las dos cosas que NO hace
 *
 *  - **No sirve con alguien con quien nunca hablaste.** Abrir una sesión de
 *    Signal necesita las claves públicas del otro, y viven en el servidor.
 *  - **No reemplaza al servidor para el resto.** Avisos, llamadas, canales,
 *    adjuntos e historial siguen necesitándolo. Esto mueve mensajes de texto.
 */
class TransporteCerca(
    private val ctx: Context,
    private val sesion: Sesion,
    /** Un sobre que llegó. Devuelve si quedó GUARDADO: solo eso se acusa. */
    private val alRecibir: suspend (MensajeCerca.Sobre) -> Boolean,
    /** El otro lado acusó un sobre mío. */
    private val alAcuse: suspend (MensajeCerca.Acuse) -> Unit,
    /** Hay enlace y ya se sabe con quién: momento de mandar lo que espera. */
    private val alEnlazar: suspend () -> Unit,
    /** Se cortó: lo que estaba "en vuelo" no llegó a acusarse. */
    private val alCortar: () -> Unit,
) {
    private val TAG = "Cerca"

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _estado = MutableStateFlow(Estado.APAGADO)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val _conQuien = MutableStateFlow<String?>(null)
    /** Username del otro lado mientras hay enlace, o `null`. */
    val conQuien: StateFlow<String?> = _conQuien.asStateFlow()

    enum class Estado { APAGADO, ESCUCHANDO, ENLAZADO }

    private var servidor: BluetoothServerSocket? = null
    private var servidorTcp: java.net.ServerSocket? = null
    @Volatile private var cerrarEnlace: (() -> Unit)? = null
    @Volatile private var charla: CharlaCerca? = null
    /** Un solo enlace a la vez: el primero que conecta gana. */
    private val ocupado = AtomicBoolean(false)
    private var trabajos = mutableListOf<Job>()

    /** Cuándo hubo enlace por última vez, para apagarse solo. */
    @Volatile private var ultimaVezAcompanado = 0L

    private val adaptador: BluetoothAdapter?
        get() = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter

    /** Si hay enlace Y el otro ya saludó: recién ahí se sabe a quién mandar. */
    fun disponible(): Boolean = _estado.value == Estado.ENLAZADO && charla?.suyo != null

    /** Quién está del otro lado, si ya saludó. */
    val par: MensajeCerca.Saludo? get() = charla?.suyo

    // ------------------------------------------------------------------
    // Encender y apagar
    // ------------------------------------------------------------------

    /** Si este aparato tiene radio Bluetooth. Hay tablets y emuladores que no. */
    fun hayRadio(): Boolean = puente() != null || adaptador != null

    /**
     * Si la radio está encendida.
     *
     * Se pregunta y no se enciende sola: prender la radio de alguien sin
     * avisar es del sistema, no de una app de mensajería.
     */
    @SuppressLint("MissingPermission")
    fun radioEncendida(): Boolean =
        puente() != null || runCatching { adaptador?.isEnabled == true }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun encender(): Boolean {
        if (_estado.value != Estado.APAGADO) return true
        val p = puente()
        if (p == null) {
            val a = adaptador ?: run { Log.w(TAG, "Este aparato no tiene Bluetooth"); return false }
            if (!a.isEnabled) { Log.w(TAG, "Bluetooth apagado"); return false }
        }
        _estado.value = Estado.ESCUCHANDO
        ultimaVezAcompanado = System.currentTimeMillis()
        // Los dos lados escuchan Y buscan a la vez: el primero que conecta
        // gana. Que uno haga de servidor obligaria a ponerse de acuerdo en
        // quién es quién justo cuando no hay por dónde.
        trabajos += if (p != null) {
            Log.w(TAG, "PUENTE DE PRUEBAS: TCP ${p.first} / ${p.second} en vez de Bluetooth")
            listOf(ambito.launch { escucharTcp(p.first) }, ambito.launch { buscarTcp(p.second) })
        } else {
            listOf(ambito.launch { escuchar() }, ambito.launch { buscar() })
        }
        trabajos += ambito.launch { vigilarSoledad() }
        ServicioCerca.iniciar(ctx)
        return true
    }

    fun apagar() {
        _estado.value = Estado.APAGADO
        trabajos.forEach { it.cancel() }
        trabajos.clear()
        runCatching { servidor?.close() }
        runCatching { servidorTcp?.close() }
        servidor = null
        servidorTcp = null
        cortar()
        ServicioCerca.detener(ctx)
    }

    /**
     * Media hora sin nadie del otro lado y se apaga. Se enciende para una sala
     * y un momento; dejarlo escuchando conexiones toda la noche porque alguien
     * se olvidó es justo lo que el modo apagado por defecto quiere evitar.
     */
    private suspend fun vigilarSoledad() {
        while (_estado.value != Estado.APAGADO) {
            delay(60_000)
            if (_estado.value == Estado.ENLAZADO) ultimaVezAcompanado = System.currentTimeMillis()
            if (System.currentTimeMillis() - ultimaVezAcompanado > SOLEDAD_MS) {
                Log.i(TAG, "Media hora sin nadie: se apaga solo")
                apagar()
            }
        }
    }

    // ------------------------------------------------------------------
    // Bluetooth: el lado que espera y el que busca
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun escuchar() {
        val a = adaptador ?: return
        while (_estado.value != Estado.APAGADO) {
            // SEGURO (con emparejamiento): el enlace lo cifra y lo autentica
            // la clave que Android guardó al emparejar. Ver la nota de la clase.
            val s = runCatching { a.listenUsingRfcommWithServiceRecord(SERVICIO, UUID_APP) }
                .getOrNull() ?: return
            servidor = s
            val cliente = runCatching { s.accept() }.getOrNull()
            runCatching { s.close() }
            if (cliente == null) continue
            // Solo un aparato emparejado. Con RFCOMM seguro uno sin emparejar
            // dispararía el diálogo del sistema; se corta antes.
            if (cliente.remoteDevice?.bondState != BluetoothDevice.BOND_BONDED) {
                runCatching { cliente.close() }
                continue
            }
            if (!ocupado.compareAndSet(false, true)) { runCatching { cliente.close() }; continue }
            atender(cliente.inputStream, cliente.outputStream) { runCatching { cliente.close() } }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun buscar() {
        val a = adaptador ?: return
        while (_estado.value != Estado.APAGADO) {
            if (_estado.value == Estado.ESCUCHANDO) {
                for (d in runCatching { a.bondedDevices }.getOrNull().orEmpty()) {
                    if (_estado.value != Estado.ESCUCHANDO) break
                    // Los audífonos, el carro y el reloj se descartan ANTES de
                    // abrir un socket: un `connect()` contra un enlace de audio
                    // en uso se oye. Ver `valeLaPenaIntentar`.
                    val clase = runCatching { d.bluetoothClass?.majorDeviceClass }
                        .getOrNull() ?: ClaseBt.SIN_CATEGORIA
                    if (!valeLaPenaIntentar(clase)) continue
                    intentar(d)
                }
            }
            // Quince segundos: cada vuelta abre un socket por candidato, y dos
            // personas que se acaban de sentar juntas lo toleran de sobra.
            delay(15_000)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun intentar(d: BluetoothDevice) {
        if (ocupado.get()) return
        val s = runCatching { d.createRfcommSocketToServiceRecord(UUID_APP) }.getOrNull() ?: return
        try {
            s.connect()
        } catch (e: IOException) {
            // Lo normal: ese aparato no tiene esta app escuchando.
            runCatching { s.close() }
            return
        }
        if (!ocupado.compareAndSet(false, true)) { runCatching { s.close() }; return }
        atender(s.inputStream, s.outputStream) { runCatching { s.close() } }
    }

    // ------------------------------------------------------------------
    // El puente de pruebas: TCP en vez de Bluetooth, SOLO en debug
    // ------------------------------------------------------------------
    //
    // Las radios de los emuladores están aisladas: dos emuladores no se ven
    // nunca, y sin esto el modo cerca solo se podía probar con dos teléfonos.
    // Con un archivo `puente-cerca.txt` ("escucho:conecto") en la carpeta de la
    // app, el enlace va por dos puertos TCP que `adb reverse`/`adb forward`
    // cruzan entre los emuladores. Todo lo de arriba del enlace -saludo,
    // sobres, acuses, el despacho sin red- es el código de verdad.
    //
    // El archivo va en la carpeta INTERNA de la app y se escribe con
    // `adb shell run-as`, que solo funciona con una app depurable. Y además
    // `BuildConfig.DEBUG`: en la versión publicada esto no existe -R8 se lleva
    // la rama entera- y un archivo con ese nombre no hace nada.

    private fun puente(): Pair<Int, Int>? {
        if (!BuildConfig.DEBUG) return null
        val f = java.io.File(ctx.filesDir, "puente-cerca.txt")
        val partes = runCatching { f.readText().trim().split(':').map { it.trim().toInt() } }.getOrNull()
            ?: return null
        return if (partes.size == 2) partes[0] to partes[1] else null
    }

    private suspend fun escucharTcp(puerto: Int) {
        val s = runCatching { java.net.ServerSocket(puerto, 1, java.net.InetAddress.getByName("127.0.0.1")) }
            .getOrElse { Log.w(TAG, "Puente: no se pudo escuchar en $puerto: ${it.message}"); return }
        servidorTcp = s
        while (_estado.value != Estado.APAGADO) {
            val c = runCatching { s.accept() }.getOrNull() ?: continue
            if (!ocupado.compareAndSet(false, true)) { runCatching { c.close() }; continue }
            atender(c.getInputStream(), c.getOutputStream()) { runCatching { c.close() } }
        }
    }

    private suspend fun buscarTcp(puerto: Int) {
        while (_estado.value != Estado.APAGADO) {
            if (_estado.value == Estado.ESCUCHANDO && !ocupado.get()) {
                val c = runCatching { java.net.Socket("127.0.0.1", puerto) }.getOrNull()
                if (c != null) {
                    if (ocupado.compareAndSet(false, true)) {
                        atender(c.getInputStream(), c.getOutputStream()) { runCatching { c.close() } }
                    } else {
                        runCatching { c.close() }
                    }
                }
            }
            delay(3_000)
        }
    }

    // ------------------------------------------------------------------
    // El enlace
    // ------------------------------------------------------------------

    private suspend fun atender(entrada: InputStream, salida: OutputStream, cerrar: () -> Unit) {
        cerrarEnlace = cerrar
        _estado.value = Estado.ENLAZADO
        ultimaVezAcompanado = System.currentTimeMillis()
        Log.i(TAG, "Enlazado")
        val c = CharlaCerca(
            entrada, salida,
            MensajeCerca.Saludo(
                usuarioId = sesion.usuarioId.orEmpty(),
                username = sesion.username.orEmpty(),
                dispositivoId = sesion.dispositivoId.orEmpty(),
            ),
        )
        charla = c
        if (c.saludar()) {
            runCatching {
                c.escuchar(
                    alSaludo = { s ->
                        _conQuien.value = s.username
                        Log.i(TAG, "Del otro lado dice ser @${s.username}")
                        runCatching { alEnlazar() }
                    },
                    alSobre = { s -> runCatching { alRecibir(s) }.getOrDefault(false) },
                    alAcuse = { a -> runCatching { alAcuse(a) } },
                )
            }.onFailure { Log.i(TAG, "Enlace terminado: ${it.message}") }
        }
        cortar()
    }

    private fun cortar() {
        runCatching { cerrarEnlace?.invoke() }
        cerrarEnlace = null
        charla = null
        _conQuien.value = null
        runCatching { alCortar() }
        ocupado.set(false)
        if (_estado.value == Estado.ENLAZADO) _estado.value = Estado.ESCUCHANDO
    }

    // ------------------------------------------------------------------
    // Mandar
    // ------------------------------------------------------------------

    /**
     * Manda un sobre por el enlace, si hay y si es para quien está del otro
     * lado.
     *
     * @return `false` si no se pudo. Quien llama lo deja en la cola: el enlace
     *   se cae cada vez que alguien se aleja.
     */
    fun mandar(sobre: MensajeCerca.Sobre): Boolean {
        val c = charla ?: return false
        if (c.suyo?.dispositivoId != sobre.destinoDispositivo) return false
        if (!c.mandar(sobre)) {
            Log.w(TAG, "No se pudo mandar por el enlace")
            cortar()
            return false
        }
        return true
    }

    private companion object {
        const val SERVICIO = "wtfuck-cerca"

        /** Media hora sin nadie del otro lado. Ver [vigilarSoledad]. */
        const val SOLEDAD_MS = 30 * 60 * 1000L

        /**
         * El UUID del servicio RFCOMM. Fijo y propio de esta app: distingue un
         * teléfono con wtfuck de uno sin. No es un secreto ni protege nada.
         */
        val UUID_APP: UUID = UUID.fromString("7c9f1a2e-4b6d-4e8a-9f3c-b1a2c3000001")
    }
}
