package com.wtfuck.app

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wtfuck.app.datos.Hardware
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * W5e · Captura una cadena de atestacion REAL de este aparato y se la manda al
 * servidor de desarrollo, que la verifica, anota el veredicto y -con
 * `WTFUCK_ATESTACION_VOLCAR`- la guarda como fixture.
 *
 * No es una prueba de la app: es la herramienta para conseguir cadenas de
 * telefonos de verdad, que no se pueden fabricar. Se corre a mano, SIN
 * `connectedAndroidTest` (que desinstala la app al terminar y borra sus datos):
 *
 *   adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk
 *   adb shell am instrument -w -e servidor http://10.0.2.2:8300 \
 *       com.wtfuck.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * En un telefono fisico: `adb reverse tcp:8300 tcp:8300` y `-e servidor
 * http://127.0.0.1:8300`. Registra una cuenta descartable `cap...` en el
 * servidor de desarrollo.
 */
@RunWith(AndroidJUnit4::class)
class AtestacionCapturaTest {

    private fun pedir(url: String, cuerpo: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write((cuerpo ?: "").toByteArray()) }
        val codigo = c.responseCode
        val texto = (if (codigo < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        Log.i(TAG, "POST $url -> $codigo $texto")
        return JSONObject(texto.ifBlank { "{}" }).put("_codigo", codigo)
    }

    @Test
    fun capturaUnaCadenaYLaMandaAlServidor() {
        val base = InstrumentationRegistry.getArguments().getString("servidor") ?: "http://10.0.2.2:8300"
        val reto = pedir("$base/v1/atestacion/reto", null).getString("reto")
        val cadena = Hardware.atestar(reto)
        Log.i(TAG, "Cadena de ${cadena.size} certificados en ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        assertTrue("El Keystore no devolvio cadena", cadena.isNotEmpty())

        val azar = SecureRandom()
        fun b64(n: Int) = Base64.getEncoder().encodeToString(ByteArray(n).also { azar.nextBytes(it) })
        val emulador = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu")
        val r = pedir(
            "$base/v1/registro",
            JSONObject()
                .put("username", "cap" + Integer.toString(azar.nextInt(Int.MAX_VALUE), 36).take(8))
                .put("password", "clave-larga-de-captura")
                .put("etiquetaDispositivo", "captura ${Build.MODEL}")
                .put("identidadPub", b64(33))
                .put("hardwareHash", b64(32))
                .put("hardwareNivel", if (emulador) "SOFTWARE_DEV" else "TEE")
                .put("atestacion", JSONArray(cadena))
                .toString(),
        )
        assertTrue("El servidor rechazo el registro: $r", r.getInt("_codigo") == 200)
    }

    private companion object {
        const val TAG = "AtestacionCaptura"
    }
}
