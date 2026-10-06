package com.wtfuck.app.datos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.wtfuck.app.BuildConfig
import com.wtfuck.protocol.Apreton
import com.wtfuck.protocol.Baliza
import com.wtfuck.protocol.CharlaCerca
import com.wtfuck.protocol.ClaseBt
import com.wtfuck.protocol.MensajeCerca
import com.wtfuck.protocol.Sello
import com.wtfuck.protocol.valeLaPenaIntentar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mensajería entre teléfonos que están cerca, sin internet.
 *
 * ## Qué es esto en una frase
 *
 * Otro camino para los mismos sobres. El servidor de esta app es un buzón
 * tonto que mueve bytes que no puede abrir; este enlace hace lo mismo a diez
 * metros. Los sobres que llegan por aquí entran por donde entran los del buzón.
 *
 * ## Dos formas de encontrarse
 *
 *  - **Fase 1, sin emparejar (Android 12+).** Cada teléfono anuncia por
 *    Bluetooth LE una baliza que solo sus contactos reconocen (`Baliza`), y el
 *    que reconoce a alguien abre un canal L2CAP y hace el apretón de manos
 *    (`Apreton`): cifrado y autenticado con las identidades de Signal, sin
 *    pasar por los ajustes del sistema. Hasta [MAX_ENLACES] a la vez, para un
 *    grupo en la misma sala.
 *  - **Fase 0, emparejados.** RFCOMM seguro con los aparatos emparejados en
 *    los ajustes de Android. Sigue para Android anteriores y para quien todavía
 *    no recibió la baliza del otro.
 *
 * Ver `docs/11-SIN-INTERNET.md` y `docs/evidencias/modo-cerca-fase0/`.
 *
 * ## Lo que NO hace
 *
 *  - **No sirve con alguien con quien nunca hablaste.** Hacen falta su
 *    identidad de Signal (para el apretón y para cifrar) y, sin emparejar,
 *    su baliza: las dos llegan con internet.
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
    /** Hay un enlace nuevo y ya se sabe con quién: momento de mandar lo que espera. */
    private val alEnlazar: suspend () -> Unit,
    /** Se cortó el enlace con ese aparato. */
    private val alCortar: (String) -> Unit,
    /** Lo que hace falta para el enlace sin emparejar. `null` = solo fase 0. */
    private val llaves: LlavesCerca? = null,
) {
    private val TAG = "Cerca"

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _estado = MutableStateFlow(Estado.APAGADO)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val _conQuienes = MutableStateFlow<List<String>>(emptyList())
    /** Con quiénes hay enlace ahora (usernames). */
    val conQuienes: StateFlow<List<String>> = _conQuienes.asStateFlow()

    private val _conQuien = MutableStateFlow<String?>(null)
    /** El primero de [conQuienes], o `null`. */
    val conQuien: StateFlow<String?> = _conQuien.asStateFlow()

    enum class Estado { APAGADO, ESCUCHANDO, ENLAZADO }

    /** Un enlace vivo. */
    private class Enlace(
        val charla: CharlaCerca,
        val cerrar: () -> Unit,
        /** Si lo abrí yo: decide cuál queda si hay dos con el mismo aparato. */
        val soyIniciador: Boolean,
        val porBle: Boolean,
    )

    private val enlaces = CopyOnWriteArrayList<Enlace>()
    private val trabajos = CopyOnWriteArrayList<Job>()
    private var servidor: BluetoothServerSocket? = null
    private var servidorBle: BluetoothServerSocket? = null
    private var servidorTcp: java.net.ServerSocket? = null

    /** Cuándo se intentó llamar por BLE a cada aparato: no se insiste en cada anuncio. */
    private val intentos = ConcurrentHashMap<String, Long>()

    /** Cuándo hubo enlace por última vez, para apagarse solo. */
    @Volatile private var ultimaVezAcompanado = 0L

    private val adaptador: BluetoothAdapter?
        get() = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter

    /** Los aparatos del otro lado que ya saludaron. */
    val pares: List<MensajeCerca.Saludo> get() = enlaces.mapNotNull { it.charla.suyo }

    /** Si hay al menos un enlace con alguien que ya saludó. */
    fun disponible(): Boolean = pares.isNotEmpty()

    /**
     * Si este teléfono puede usar el modo sin emparejar: Android 12 o más
     * -antes, buscar por BLE exige el permiso de ubicación-, un adaptador que
     * sepa anunciar y una identidad de Signal para el apretón.
     */
    @SuppressLint("MissingPermission")
    fun sinEmparejarDisponible(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            llaves?.curva != null &&
            runCatching { adaptador?.bluetoothLeAdvertiser != null }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Encender y apagar
    // ------------------------------------------------------------------

    /** Si este aparato tiene radio Bluetooth. Hay tablets y emuladores que no. */
    fun hayRadio(): Boolean = puente() != null || adaptador != null

    /**
     * Si la radio está encendida. Se pregunta y no se enciende sola: prender la
     * radio de alguien sin avisar es del sistema, no de una app de mensajería.
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
        if (p != null) {
            Log.w(TAG, "PUENTE DE PRUEBAS: TCP ${p.first} / ${p.second} en vez de Bluetooth")
            trabajos += ambito.launch { escucharTcp(p.first) }
            trabajos += ambito.launch { buscarTcp(p.second) }
        } else {
            // Fase 0: emparejados, por RFCOMM seguro.
            trabajos += ambito.launch { escuchar() }
            trabajos += ambito.launch { buscar() }
            // Fase 1: sin emparejar, por BLE. La comprobacion de version va aqui
            // a la vista y no solo dentro de `sinEmparejarDisponible`: es la
            // que deja llamar a las funciones de Android 12.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sinEmparejarDisponible()) {
                trabajos += ambito.launch { escucharBle() }
                trabajos += ambito.launch { buscarBle() }
            }
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
        runCatching { servidorBle?.close() }
        runCatching { servidorTcp?.close() }
        servidor = null
        servidorBle = null
        servidorTcp = null
        enlaces.toList().forEach { cortar(it) }
        intentos.clear()
        ServicioCerca.detener(ctx)
    }

    /**
     * Media hora sin nadie del otro lado y se apaga. Se enciende para una sala
     * y un momento; dejarlo escuchando toda la noche porque alguien se olvidó
     * es justo lo que el modo apagado por defecto quiere evitar.
     */
    private suspend fun vigilarSoledad() {
        while (_estado.value != Estado.APAGADO) {
            delay(60_000)
            if (enlaces.isNotEmpty()) ultimaVezAcompanado = System.currentTimeMillis()
            if (System.currentTimeMillis() - ultimaVezAcompanado > SOLEDAD_MS) {
                Log.i(TAG, "Media hora sin nadie: se apaga solo")
                apagar()
            }
        }
    }

    // ------------------------------------------------------------------
    // Fase 0: emparejados, por RFCOMM seguro
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun escuchar() {
        val a = adaptador ?: return
        while (_estado.value != Estado.APAGADO) {
            // SEGURO: el enlace lo cifra y lo autentica la clave que Android
            // guardó al emparejar.
            val s = runCatching { a.listenUsingRfcommWithServiceRecord(SERVICIO, UUID_APP) }
                .getOrNull() ?: return
            servidor = s
            val cliente = runCatching { s.accept() }.getOrNull()
            runCatching { s.close() }
            if (cliente == null) continue
            // Solo un aparato emparejado: con RFCOMM seguro uno sin emparejar
            // dispararía el diálogo del sistema.
            if (cliente.remoteDevice?.bondState != BluetoothDevice.BOND_BONDED || lleno()) {
                runCatching { cliente.close() }
                continue
            }
            ambito.launch {
                atender(cliente.inputStream, cliente.outputStream, { runCatching { cliente.close() } },
                    soyIniciador = false, porBle = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun buscar() {
        val a = adaptador ?: return
        while (_estado.value != Estado.APAGADO) {
            if (!lleno()) {
                for (d in runCatching { a.bondedDevices }.getOrNull().orEmpty()) {
                    if (_estado.value == Estado.APAGADO || lleno()) break
                    // Los audífonos, el carro y el reloj se descartan ANTES de
                    // abrir un socket: un `connect()` contra un enlace de audio
                    // en uso se oye. Ver `valeLaPenaIntentar`.
                    val clase = runCatching { d.bluetoothClass?.majorDeviceClass }
                        .getOrNull() ?: ClaseBt.SIN_CATEGORIA
                    if (!valeLaPenaIntentar(clase)) continue
                    intentar(d)
                }
            }
            delay(15_000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun intentar(d: BluetoothDevice) {
        val s = runCatching { d.createRfcommSocketToServiceRecord(UUID_APP) }.getOrNull() ?: return
        try {
            s.connect()
        } catch (e: IOException) {
            // Lo normal: ese aparato no tiene esta app escuchando.
            runCatching { s.close() }
            return
        }
        ambito.launch {
            atender(s.inputStream, s.outputStream, { runCatching { s.close() } }, soyIniciador = true, porBle = false)
        }
    }

    // ------------------------------------------------------------------
    // Fase 1: sin emparejar, por Bluetooth LE
    // ------------------------------------------------------------------

    /**
     * Abre el canal L2CAP que atiende las llamadas y anuncia la baliza con su
     * número. Inseguro a propósito: el cifrado no lo pone el sistema -que
     * exigiría emparejar- sino el apretón.
     */
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private suspend fun escucharBle() {
        val a = adaptador ?: return
        val s = runCatching { a.listenUsingInsecureL2capChannel() }
            .getOrElse { Log.w(TAG, "No se pudo abrir el canal L2CAP: ${it.message}"); return }
        servidorBle = s
        trabajos += ambito.launch { anunciar(s.psm) }
        while (_estado.value != Estado.APAGADO) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            if (lleno()) { runCatching { c.close() }; continue }
            ambito.launch { atenderBle(c.inputStream, c.outputStream) { runCatching { c.close() } } }
        }
    }

    /**
     * La baliza, que cambia cada cuarto de hora. Sin el nombre del aparato: el
     * nombre de Bluetooth suele ser el de la persona.
     */
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private suspend fun anunciar(psm: Int) {
        val anunciante = adaptador?.bluetoothLeAdvertiser ?: return
        val ll = llaves ?: return
        while (_estado.value != Estado.APAGADO) {
            val ahora = System.currentTimeMillis()
            val version = ll.versionBaliza()
            val token = Baliza.token(ll.miBaliza(), Baliza.epoca(ahora))
            val datos = AdvertiseData.Builder()
                .addManufacturerData(Baliza.EMPRESA, Baliza.anuncio(token, psm))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
            val ajustes = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                // Conectable: el que llama abre una conexión LE para el canal.
                .setConnectable(true)
                .build()
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "No se pudo anunciar la baliza: $errorCode")
                }
            }
            runCatching { anunciante.startAdvertising(ajustes, datos, cb) }
                .onFailure { Log.w(TAG, "No se pudo anunciar: ${it.message}") }
            try {
                // Hasta el próximo cuarto de hora, y un segundo más. O hasta
                // que la clave rote -al bloquear a alguien-: seguir anunciando
                // la vieja hasta fin del cuarto de hora le dejaría a esa
                // persona ver que sigo cerca, aunque ya no pueda enlazarse.
                val hasta = ahora - ahora % Baliza.EPOCA_MS + Baliza.EPOCA_MS + 1_000
                while (System.currentTimeMillis() < hasta &&
                    ll.versionBaliza() == version &&
                    _estado.value != Estado.APAGADO
                ) delay(3_000)
            } finally {
                runCatching { anunciante.stopAdvertising(cb) }
            }
        }
    }

    /**
     * Busca balizas de contactos. Solo se filtra por la marca: reconocer a
     * quién pertenece cada una es cuenta de este teléfono, con las claves que
     * conoce. Un anuncio que no se reconoce no lleva a ninguna conexión.
     */
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private suspend fun buscarBle() {
        val escaner = adaptador?.bluetoothLeScanner ?: return
        val ll = llaves ?: return
        val vistos = Channel<ScanResult>(64, BufferOverflow.DROP_OLDEST)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                vistos.trySend(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { vistos.trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "La búsqueda BLE falló: $errorCode")
            }
        }
        val filtro = ScanFilter.Builder()
            .setManufacturerData(Baliza.EMPRESA, Baliza.PREFIJO, byteArrayOf(-1, -1))
            .build()
        val ajustes = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { escaner.startScan(listOf(filtro), ajustes, cb) }
            .onFailure { Log.w(TAG, "No se pudo buscar por BLE: ${it.message}"); return }
        var indice = emptyMap<String, String>()
        var indiceEn = 0L
        try {
            for (r in vistos) {
                if (_estado.value == Estado.APAGADO) break
                val a = Baliza.leer(r.scanRecord?.getManufacturerSpecificData(Baliza.EMPRESA)) ?: continue
                val ahora = System.currentTimeMillis()
                // El índice se rehace cada minuto: cambia el cuarto de hora y
                // llegan claves nuevas de contactos.
                if (ahora - indiceEn > 60_000) {
                    indice = Baliza.indice(ll.conocidas(), ahora)
                    indiceEn = ahora
                }
                val aparato = indice[a.token] ?: continue
                if (pares.any { it.dispositivoId == aparato } || lleno()) continue
                // Un anuncio se oye varias veces por segundo: un intento cada
                // veinte segundos por aparato.
                if (ahora - (intentos[aparato] ?: 0L) < 20_000) continue
                intentos[aparato] = ahora
                ambito.launch { llamarBle(r.device, a.psm, aparato) }
            }
        } finally {
            runCatching { escaner.stopScan(cb) }
        }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private suspend fun llamarBle(d: BluetoothDevice, psm: Int, aparato: String) {
        val ll = llaves ?: return
        val curva = ll.curva ?: return
        val suBaliza = ll.conocidas()[aparato] ?: return
        val suIdentidad = ll.identidadDe(aparato) ?: run {
            Log.w(TAG, "Tengo la baliza de $aparato pero no su identidad: no se llama")
            return
        }
        val s = runCatching { d.createInsecureL2capChannel(psm) }.getOrNull() ?: return
        // Un apretón que no termina en quince segundos se corta: cerrar el
        // socket es lo único que desbloquea una lectura de Bluetooth.
        val vigia = ambito.launch { delay(15_000); runCatching { s.close() } }
        val sello = runCatching {
            s.connect()
            Apreton(curva).llamar(
                s.inputStream, s.outputStream,
                yo = sesion.dispositivoId.orEmpty(), el = aparato,
                suBaliza = suBaliza, suIdentidad = suIdentidad, miEstatico = ll::miEstatico,
            )
        }.onFailure {
            Log.i(TAG, "No se pudo enlazar con $aparato: ${it.message}")
        }.getOrNull()
        vigia.cancel()
        if (sello == null) { runCatching { s.close() }; return }
        atender(s.inputStream, s.outputStream, { runCatching { s.close() } },
            soyIniciador = true, porBle = true, sello = sello, autenticado = aparato)
    }

    private suspend fun atenderBle(entrada: InputStream, salida: OutputStream, cerrar: () -> Unit) {
        val ll = llaves ?: return cerrar()
        val curva = ll.curva ?: return cerrar()
        val vigia = ambito.launch { delay(15_000); cerrar() }
        val r = runCatching {
            Apreton(curva).atender(
                entrada, salida,
                yo = sesion.dispositivoId.orEmpty(), miBaliza = ll.miBaliza(),
                identidadDe = ll::identidadDe, miEstatico = ll::miEstatico,
            )
        }.onFailure {
            // Un extraño, alguien sin la identidad del otro o un apretón a
            // medias: se corta sin más. No se dice por qué al otro lado.
            Log.i(TAG, "Apretón rechazado: ${it.message}")
        }.getOrNull()
        vigia.cancel()
        if (r == null) return cerrar()
        val (quien, sello) = r
        atender(entrada, salida, cerrar, soyIniciador = false, porBle = true, sello = sello, autenticado = quien)
    }

    // ------------------------------------------------------------------
    // El puente de pruebas: TCP en vez de Bluetooth, SOLO en debug
    // ------------------------------------------------------------------
    //
    // Por si las radios de los emuladores no se ven. Con un archivo
    // `puente-cerca.txt` ("escucho:conecto") en la carpeta INTERNA de la app,
    // escrito con `adb shell run-as` -solo funciona con una app depurable-,
    // el enlace va por dos puertos TCP que `adb reverse`/`adb forward` cruzan.
    // Ver `pruebas/puente-cerca.sh`. En la versión publicada esto no existe:
    // `BuildConfig.DEBUG` es falso y R8 se lleva la rama entera.

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
            if (lleno()) { runCatching { c.close() }; continue }
            ambito.launch {
                atender(c.getInputStream(), c.getOutputStream(), { runCatching { c.close() } },
                    soyIniciador = false, porBle = false)
            }
        }
    }

    private suspend fun buscarTcp(puerto: Int) {
        while (_estado.value != Estado.APAGADO) {
            if (enlaces.isEmpty()) {
                val c = runCatching { java.net.Socket("127.0.0.1", puerto) }.getOrNull()
                if (c != null) {
                    ambito.launch {
                        atender(c.getInputStream(), c.getOutputStream(), { runCatching { c.close() } },
                            soyIniciador = true, porBle = false)
                    }
                }
            }
            delay(3_000)
        }
    }

    // ------------------------------------------------------------------
    // El enlace
    // ------------------------------------------------------------------

    private fun lleno(): Boolean = enlaces.size >= MAX_ENLACES

    private suspend fun atender(
        entrada: InputStream,
        salida: OutputStream,
        cerrar: () -> Unit,
        soyIniciador: Boolean,
        porBle: Boolean,
        sello: Sello? = null,
        autenticado: String? = null,
    ) {
        val c = CharlaCerca(
            entrada, salida,
            MensajeCerca.Saludo(
                usuarioId = sesion.usuarioId.orEmpty(),
                username = sesion.username.orEmpty(),
                dispositivoId = sesion.dispositivoId.orEmpty(),
            ),
            sello = sello,
            autenticado = autenticado,
        )
        val e = Enlace(c, cerrar, soyIniciador, porBle)
        enlaces += e
        _estado.value = Estado.ENLAZADO
        ultimaVezAcompanado = System.currentTimeMillis()
        Log.i(TAG, "Enlazado (${if (porBle) "BLE sin emparejar" else "emparejado"})")
        if (c.saludar()) {
            runCatching {
                c.escuchar(
                    alSaludo = { s ->
                        if (!quedarseCon(e, s)) {
                            cerrar()
                            return@escuchar
                        }
                        publicar()
                        Log.i(TAG, "Del otro lado: @${s.username}")
                        runCatching { alEnlazar() }
                    },
                    alSobre = { s -> runCatching { alRecibir(s) }.getOrDefault(false) },
                    alAcuse = { a -> runCatching { alAcuse(a) } },
                )
            }.onFailure { Log.i(TAG, "Enlace terminado: ${it.message}") }
        }
        cortar(e)
    }

    /**
     * Si ya había un enlace con ese aparato -los dos se llamaron a la vez-,
     * queda UNO. Gana el que abrió el de id menor: los dos lados conocen los
     * dos ids, así que los dos eligen el mismo y cierran el otro.
     */
    private fun quedarseCon(e: Enlace, s: MensajeCerca.Saludo): Boolean {
        val otros = enlaces.filter { it !== e && it.charla.suyo?.dispositivoId == s.dispositivoId }
        if (otros.isEmpty()) return true
        val miId = sesion.dispositivoId.orEmpty()
        val ganaEste = e.soyIniciador == (miId < s.dispositivoId)
        if (ganaEste) otros.forEach { it.cerrar() }
        return ganaEste
    }

    private fun cortar(e: Enlace) {
        val quien = e.charla.suyo?.dispositivoId
        runCatching { e.cerrar() }
        if (!enlaces.remove(e)) return
        quien?.let { d -> runCatching { alCortar(d) } }
        publicar()
        if (_estado.value != Estado.APAGADO) {
            _estado.value = if (enlaces.isEmpty()) Estado.ESCUCHANDO else Estado.ENLAZADO
        }
    }

    private fun publicar() {
        val nombres = pares.map { it.username }.distinct()
        _conQuienes.value = nombres
        _conQuien.value = nombres.firstOrNull()
    }

    // ------------------------------------------------------------------
    // Mandar
    // ------------------------------------------------------------------

    /** Corta los enlaces con esa persona: al bloquearla. */
    fun cortarCon(username: String) {
        enlaces.filter { it.charla.suyo?.username.equals(username, ignoreCase = true) }.forEach { cortar(it) }
    }

    /**
     * Manda un sobre por el enlace de su aparato, si lo hay.
     *
     * @return `false` si no se pudo. Quien llama lo deja en la cola: el enlace
     *   se cae cada vez que alguien se aleja.
     */
    fun mandar(sobre: MensajeCerca.Sobre): Boolean {
        val e = enlaces.firstOrNull { it.charla.suyo?.dispositivoId == sobre.destinoDispositivo } ?: return false
        if (!e.charla.mandar(sobre)) {
            Log.w(TAG, "No se pudo mandar por el enlace")
            cortar(e)
            return false
        }
        return true
    }

    private companion object {
        const val SERVICIO = "wtfuck-cerca"

        /** Media hora sin nadie del otro lado. Ver [vigilarSoledad]. */
        const val SOLEDAD_MS = 30 * 60 * 1000L

        /** Enlaces a la vez: un grupo en la misma sala, no una red. */
        const val MAX_ENLACES = 4

        /**
         * El UUID del servicio RFCOMM. Fijo y propio de esta app: distingue un
         * teléfono con wtfuck de uno sin. No es un secreto ni protege nada.
         */
        val UUID_APP: UUID = UUID.fromString("7c9f1a2e-4b6d-4e8a-9f3c-b1a2c3000001")
    }
}
