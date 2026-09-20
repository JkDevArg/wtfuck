package com.wtfuck.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import com.wtfuck.app.ui.decodificarLuminancia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * J.6 · El decodificador de QR, probado sin cámara.
 *
 * Lo que de verdad falla en un escáner no es zxing: es el pegamento —el
 * `rowStride`, el recorte, el tamaño que se le declara—. Con `ImageProxy` en
 * la firma haría falta un dispositivo para comprobarlo y esa parte quedaría
 * probada sólo a mano; por eso el decodificador es una función pura y esto es
 * un test de JVM.
 */
class EscanerQrTest {

    /**
     * Genera el QR y lo entrega como un plano de luminancia, igual que lo daría
     * la cámara: un byte por píxel, filas alineadas a `rowStride`.
     *
     * `relleno` simula la alineación real de la cámara, que es justo el detalle
     * que rompe un escáner mal escrito.
     */
    private fun planoDeQr(texto: String, relleno: Int = 0): Triple<ByteArray, Int, Int> {
        val lado = 200
        val matriz = QRCodeWriter().encode(
            texto,
            BarcodeFormat.QR_CODE,
            lado,
            lado,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val stride = lado + relleno
        val bytes = ByteArray(stride * lado)
        for (y in 0 until lado) {
            for (x in 0 until lado) {
                // Negro = 0, blanco = 255. Es lo que mide un sensor.
                bytes[y * stride + x] = if (matriz.get(x, y)) 0 else 255.toByte()
            }
            // El relleno queda en 0 (negro) a proposito: si el decodificador lo
            // tomara como parte de la imagen, el resultado cambiaria.
        }
        return Triple(bytes, stride, lado)
    }

    @Test
    fun `lee un codigo de vinculacion`() {
        val (bytes, stride, lado) = planoDeQr("48NH-DQSW")
        val leido = decodificarLuminancia(bytes, stride, lado, lado, MultiFormatReader())
        assertEquals("48NH-DQSW", leido)
    }

    @Test
    fun `lee igual con filas alineadas, que es lo que manda la camara`() {
        // 48 bytes de relleno por fila: una alineacion plausible de un sensor.
        // Sin respetar el stride, esto produce una imagen inclinada y el QR no
        // decodifica nunca.
        val (bytes, stride, lado) = planoDeQr("48NH-DQSW", relleno = 48)
        val leido = decodificarLuminancia(bytes, stride, lado, lado, MultiFormatReader())
        assertEquals("48NH-DQSW", leido)
    }

    @Test
    fun `un cuadro sin codigo devuelve null y no lanza`() {
        // Es el caso NORMAL: la mayoria de los cuadros no tienen ningun QR. Si
        // esto lanzara, el analizador se caeria treinta veces por segundo.
        val bytes = ByteArray(200 * 200) { 128.toByte() }
        assertNull(decodificarLuminancia(bytes, 200, 200, 200, MultiFormatReader()))
    }

    @Test
    fun `el mismo lector sirve para varios cuadros seguidos`() {
        // `decodeWithState` guarda estado entre llamadas; sin el `reset` que
        // hace el decodificador, el segundo cuadro puede fallar o devolver lo
        // del anterior.
        val lector = MultiFormatReader()
        val (vacio, _, _) = Triple(ByteArray(200 * 200) { 128.toByte() }, 200, 200)
        assertNull(decodificarLuminancia(vacio, 200, 200, 200, lector))

        val (bytes, stride, lado) = planoDeQr("ABCD-1234")
        assertEquals("ABCD-1234", decodificarLuminancia(bytes, stride, lado, lado, lector))

        assertNull(decodificarLuminancia(vacio, 200, 200, 200, lector))

        val (otros, s2, l2) = planoDeQr("ZZZZ-9999")
        assertEquals("ZZZZ-9999", decodificarLuminancia(otros, s2, l2, l2, lector))
    }
}
