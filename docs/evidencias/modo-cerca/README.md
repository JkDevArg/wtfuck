# Módulo AZ · Modo cerca — mensajería por Bluetooth, sin internet

Mandar y recibir mensajes entre dos teléfonos que están a unos metros, sin
servidor y sin señal.

---

## Por qué esto cabe sin retorcer nada

El servidor de wtfuck es un **buzón tonto**: mueve sobres cifrados que no puede
abrir, y el historial vive sólo en los clientes. Un enlace Bluetooth puede
mover **exactamente los mismos sobres**.

Eso es lo que convierte "mensajería sin internet" en *un transporte más* en vez
de en un producto aparte. No hace falta otro formato, ni otro cifrado, ni
confiar en el enlace — porque tampoco se confía en el servidor.

La interfaz `Transporte` y el sitio en la lista estaban reservados desde la
fase 2, con un comentario que decía qué faltaba. Ese sitio es el que ocupa
`TransporteCerca`: el despachador, la cola de salida, el esquema y la pantalla
de chat **no cambian**.

## El límite honesto: sólo con quien ya hablaste

El cifrado de Signal necesita una sesión, y abrir una por primera vez exige las
claves públicas del otro, que viven en el servidor. Sin internet no hay forma
de pedirlas.

No es un defecto que se pueda arreglar en este módulo; es de dónde salen las
claves. Por eso el diálogo lo dice con todas las letras, en la pantalla, antes
de encender — sin esa frase se enciende, no pasa nada, y no hay manera de saber
si está roto o si es así.

---

## La decisión de seguridad: por el aire NO se abre una sesión nueva

Es la defensa central del módulo, y está en
[`protocol/Cerca.kt`](../../../protocol/src/main/kotlin/com/wtfuck/protocol/Cerca.kt).

```kotlin
fun aceptable(tipo: Int, haySesion: Boolean): Boolean = when (tipo) {
    TipoCifrado.SESION -> haySesion
    TipoCifrado.GRUPO  -> haySesion
    TipoCifrado.PREPARADO -> false   // abre sesión: por el aire, nunca
    TipoCifrado.PLANO     -> false
    else -> false
}
```

Un `PreKeySignalMessage` establece una sesión **con la identidad que traiga
dentro**. Por el servidor eso está bien: hay una cuenta detrás, con su
contraseña y su dispositivo registrado. Por el aire no hay nada de eso —
cualquiera con una radio y esta app modificada podría abrir una sesión a nombre
de quien quisiera y aparecer en la pantalla de alguien.

Detectarlo *después* sería posible (cambiaría la huella y saltaría el aviso de
"la clave de seguridad cambió"), pero un aviso que se lee después de haber
leído el mensaje llega tarde. Aquí directamente no se acepta.

`else -> false` importa igual: la lista es cerrada. Aceptar lo desconocido es
como un tipo nuevo se cuela sin que nadie lo decida.

## Enlace sin emparejar, a propósito

`listenUsingInsecureRfcommWithServiceRecord` / `createInsecureRfcomm…`: no se
pide PIN ni emparejamiento.

No es un atajo. Emparejar cifraría el enlace, y el enlace **ya no necesita
cifrado**: lo que viaja son sobres que sólo el destinatario puede abrir, igual
que por el servidor. Exigir emparejamiento añadiría un diálogo del sistema, un
código que comparar y un motivo para abandonar, a cambio de proteger algo que
ya está protegido.

Lo que sí protege el enlace es lo de arriba: qué se acepta al otro lado.

## Los dos lados escuchan y buscan a la vez

La alternativa era que uno haga de servidor y el otro de cliente, y eso obliga
a que las dos personas se pongan de acuerdo en quién es cuál **justo cuando no
tienen por dónde ponerse de acuerdo**. Con los dos haciendo las dos cosas, el
primero que conecte gana y el otro deja de intentar.

## El marco de las tramas

RFCOMM es un flujo de bytes, no de mensajes: lo que se escribe de una vez puede
llegar partido en tres, y tres escrituras pueden llegar juntas. Sin un largo
por delante, el primer JSON partido rompe todo lo que venga detrás.

Cuatro bytes de largo, tope de 128 KiB. El tope es lo que impide que alguien
mande `0x7FFFFFFF` como largo y este lado intente reservar dos gigabytes — es
la **primera línea que se lee de un desconocido**, así que es donde tiene que
estar el límite.

## Prioridad 10: después del buzón, no antes

El buzón llega a **todos** los destinos y esto sólo al que está enfrente. Con
internet, mandar por aquí sería entregarle a uno y dejar a los demás esperando.

Y si el aparato enlazado no está entre los destinos del sobre, `entregar`
**falla a propósito**: el sobre se queda en la cola para cuando haya internet.
Darlo por entregado sería perder el mensaje para todos los demás.

## `neverForLocation` en el manifiesto

Sin esa marca, Android exige **además** el permiso de ubicación, porque
históricamente escanear Bluetooth servía para deducir dónde está alguien. Aquí
no se usa para eso, y declararlo evita pedir un permiso que no hace falta.
Pedir de más es como se enseña a la gente a aceptar sin leer.

## Por qué es un diálogo y no un interruptor en Ajustes

Esto no es una preferencia que se deja puesta: se enciende **en un momento y en
un sitio** —sin señal, con la otra persona enfrente— y se apaga al salir. Un
interruptor en Ajustes se queda encendido para siempre, y una radio escuchando
conexiones de cualquiera que pase no puede ser el estado por defecto de una app
de mensajería. Arranca **apagado**.

---

## Pruebas

### 15 pruebas nuevas · `app/src/test/java/com/wtfuck/app/CercaTest.kt`

Cubren lo que decide si un byte ajeno puede hacer daño: la regla de aceptación
y el marco de las tramas. Que dos teléfonos se encuentren **no** está probado
ahí y se dice en el encabezado del archivo — eso necesita dos radios de verdad.

Es donde está el riesgo, de todos modos: un enlace que no conecta es una
función que no anda; un enlace que acepta lo que no debe es otra cosa.

### Inyección de defectos · 8 de 8 cazados

Cada defecto se instaló de a uno en `Cerca.kt` y se corrieron las pruebas:

| # | Defecto instalado | Prueba que lo cazó |
|---|---|---|
| a | `PREPARADO -> true` (se acepta abrir sesión por el aire) | `por el aire NO se abre una sesion nueva` |
| b | `PLANO -> true` (se acepta sin cifrar) | `sin cifrar tampoco, aunque el servidor de desarrollo lo permita` |
| c | `else -> true` (se acepta un tipo inventado) | `un tipo inventado no se acepta` |
| d | `SESION -> true` (se deja de exigir sesión previa) | `pero no si esa sesion no existe` |
| e | se quita el tope al leer | `un largo imposible se rechaza sin reservar memoria` |
| f | el lector se conforma con una lectura parcial | `una trama cortada a la mitad lanza en vez de devolver basura` |
| g | `MAXIMO = 32 KiB` (por debajo del sobre más grande) | `el tope deja pasar el sobre mas grande que existe` |
| h | se quita el tope al escribir | `no se puede escribir mas grande que el tope` |

### Suites completas, después del módulo

```
=== 37 suites · 1548 pasan, 0 fallan ===   (integración)
    431 unitarias, 0 fallan                (app 344 + servidor 87)
```

---

## Qué se verificó en el emulador, y qué NO

Se dice antes: **los emuladores de Android tienen Bluetooth emulado y sus
radios virtuales están aisladas entre sí**. Dos emuladores no se ven.

### Sí se verificó (emulator-5554 y emulator-5556, API 36)

1. **Los permisos del manifiesto son correctos y se conceden.**
   `dumpsys package` los lista con `granted=true`, y con `neverForLocation` no
   hizo falta pedir ubicación.

2. **El diálogo aparece en el menú y funciona.**

   ![en el menú](1-en-el-menu.png)
   ![apagado](2-apagado.png)

3. **`encender()` abre de verdad el socket RFCOMM.** Esto no es un simulacro:
   el stack de Bluetooth de Android aceptó el registro SDP con el UUID y el
   nombre de servicio del módulo, en los dos emuladores.

   ```
   BluetoothSocketManagerBinder: createSocketChannel: type=1,
     serviceName=wtfuck-cerca, uuid=7c9f1a2e-4b6d-4e8a-9f3c-b1a2c3000001
   bt_btif_sock_rfcomm: btsock_rfc_listen:
     Adding listening socket service_name: wtfuck-cerca
   ```

   ![buscando](3-buscando.png)

4. **El buzón sigue funcionando con el modo cerca encendido.** Es la regresión
   que de verdad importaba: `cerca` entró en la lista de transportes, así que
   había que comprobar que no se robe los envíos. Con los dos teléfonos en
   `ESCUCHANDO`, un mensaje salió por el WebSocket y llegó con su aviso.

   ![el buzón sigue](4-el-buzon-sigue-funcionando.png)

5. **Apagar no rompe nada.** Sin `FATAL` en logcat y el proceso vivo en los dos.

### No se verificó, y hace falta hardware

- Que dos teléfonos **se encuentren**. `dumpsys bluetooth_manager` reporta
  `Bonded devices: 0` en los dos emuladores: las radios virtuales están
  aisladas, así que el descubrimiento nunca devuelve nada.
- El saludo, el enlace, y **un sobre cruzando el aire**.

Eso son dos teléfonos de verdad con la app instalada. Todo lo demás —el marco,
la regla de aceptación, la prioridad, el camino de recepción— está probado o
verificado en el emulador.
