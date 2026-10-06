# Comunicarse sin red móvil, sin WiFi y sin datos

Investigación del 6 de octubre de 2026: código del proyecto y fuentes públicas
(enlaces al final).

**Qué está verificado:**

- **El hallazgo principal:** se verificó leyendo el código. No se ejecutó en
  dos teléfonos.
- **El resto:** sale de documentación y reportes públicos. Lo que no se pudo
  confirmar está marcado como tal.

## TL;DR

**El "modo cerca" de hoy recibe por Bluetooth pero no puede ENVIAR sin
internet.** El despachador registra cada mensaje en el servidor por HTTP antes
de elegir el transporte. Sin red, ese registro falla, el bucle hace `return` y
nunca llega a `TransporteCerca` (`Repositorio.despacharSinCandado`: la llamada
a `api.registrarMensaje` y su `return` en el fallo de red).

Orden recomendado:

1. **Que el modo cerca funcione de verdad** (esfuerzo bajo-medio): entregar por
   Bluetooth sin el registro previo y registrar al volver la red, más lo de la
   Fase 0. Probarlo con **dos teléfonos reales en modo avión**. Sin esto, todo
   lo demás se construye sobre algo que no anda.
2. **Bluetooth LE sin emparejar, con identificadores que rotan, y luego "mula"
   entre contactos** (guardar, llevar y entregar, como Briar). Sin Google Play
   Services.
3. **Opcional: un nodo LoRa/Meshtastic de 20 a 40 USD**, emparejado por
   Bluetooth, para kilómetros de alcance. Solo texto.

En paralelo, porque es barato: declarar la app como optimizada para datos
satelitales (Android 16+), sin prometer nada. En la práctica el operador decide
qué apps pasan.

**No conviene:**

- una malla pública por inundación como función por defecto;
- depender de Nearby Connections, que exige Play Services;
- USSD.

## 1. Qué hace hoy wtfuck sin internet

- **`Malla.kt` no tiene que ver con esto.** Decide quién ofrece a quién en una
  llamada de grupo WebRTC. La "malla" de mensajería de la fase 7 nunca se
  construyó.
- **"msg off" es una cola offline**, no comunicación entre teléfonos. Lo escrito
  sin red queda PENDIENTE y sale cuando vuelve la red, con la hora de quien lo
  escribió. Ver `docs/01-ARQUITECTURA.md` y `pruebas/msgoff.mjs`.
- **El modo cerca** (`TransporteCerca.kt`, `protocol/.../Cerca.kt`,
  `CercaDialogo.kt`, `docs/evidencias/modo-cerca/`):
  - Bluetooth Classic RFCOMM, un enlace a la vez y un solo salto;
  - mueve los mismos sobres cifrados que el servidor, y solo con quien ya hay
    sesión;
  - empieza apagado y se enciende desde un diálogo;
  - el enlace entre dos teléfonos **nunca se verificó**, porque los emuladores
    no comparten radio.

**Límites que no estaban documentados**, encontrados leyendo el código:

| # | Problema |
|---|---|
| 1 | **No envía sin internet**: el registro HTTP va antes que el transporte (el hallazgo principal). |
| 2 | Nada vuelve a despachar la cola cuando el enlace pasa a `ENLAZADO`. |
| 3 | Los destinos de cada conversación se guardan en memoria durante 30 s. Tras reiniciar la app sin red no hay a quién mandar. |
| 4 | Solo intenta con aparatos **ya emparejados** en Ajustes, aunque el diálogo dice "sin emparejar". Android no deja leer la propia MAC de Bluetooth, así que sin emparejar hace falta ponerse visible a mano o intercambiar la dirección por otro canal. |
| 5 | No hay servicio en primer plano `connectedDevice`, y la cola en segundo plano exige red. Por eso el diálogo pide "deja esta pantalla abierta". |
| 6 | No hay acuse por el enlace. Lo que sale por Bluetooth queda PENDIENTE y se reenvía. |
| 7 | Bug latente en grupos: el envío por cerca llega a un solo aparato, pero `confirmarEnvio` marca la clave de emisor como repartida a todos. Los demás no podrían abrir los siguientes mensajes del grupo. Hoy no se dispara por el punto 1, pero aparecerá en cuanto ese punto se arregle. |
| 8 | El saludo del enlace (usuarioId, @usuario, aparato) viaja en claro, antes de autenticar a quien se conecta. El registro SDP con nombre y UUID fijos delata que la app está activa. |

## 2. Las opciones, comparadas

| Opción | Alcance | Velocidad | ¿Hardware? | ¿En segundo plano? | ¿Play Services? | Esfuerzo | Riesgo |
|---|---|---|---|---|---|---|---|
| BT Classic (lo actual) | ~10 m | cientos de kbps | No | Solo con servicio en primer plano encendido por la persona | No | Bajo (arreglar) | MAC fija rastreable, saludo en claro |
| BLE: anuncio + GATT / L2CAP | ~10-50 m (no medido) | decenas de kbps (GATT), más con L2CAP | No | Parcial: el escaneo con filtro sigue con la pantalla apagada | No | Medio | Bajo, si los ids rotan |
| Nearby Connections | ~100 m | Alta | No | No documentado | **Sí** | Bajo-medio | Deja fuera a quien no tiene Play Services |
| Wi-Fi Direct | decenas de m | Mbps | No | Malo: un diálogo por cada conexión | No | Alto | Mala experiencia |
| Wi-Fi Aware | rango Wi-Fi | Alta | No | Exige Wi-Fi y ubicación | No | Medio | Pocos aparatos lo soportan |
| Malla pública multi-salto (tipo BitChat) | ~300 m reportado | Muy baja | No | Malo | No | Alto | **Alto**: metadatos, spam, batería |
| Mula entre contactos (tipo Briar) | lo que se mueva la gente | horas | No | Como BLE | No | Medio | Medio, con cuotas |
| Nodo LoRa/Meshtastic | 1-3 km en ciudad, más en campo | ~1 kbps; 233 B por paquete | **Sí, 20-40 USD** | Bueno: el nodo retransmite solo | No | Medio-alto | Cabeceras en claro, tema regulatorio |
| SMS con el sobre cifrado | red celular (necesita señal) | 3-4 SMS por sobre | No | Recibir sí | No | Medio | Metadatos al operador, necesita el teléfono, permisos restringidos fuera de la tienda |
| Satélite directo al teléfono | cielo abierto | baja | Teléfono y operador compatibles | Lo decide el sistema | No | Bajo (declararse) | El operador elige qué apps pasan |
| USSD | — | — | — | — | — | — | Descartado: es una sesión con el operador, no entre personas |

## 3. Lo esencial de cada frente

**Malla.**

- Bridgefy adoptó libsignal en 2020 y en 2021-22 seguía siendo rastreable y
  atacable. El paper es "Adopting libsignal is not enough". Es la lección más
  directa para wtfuck: **el protocolo alrededor del cifrado importa tanto como
  el cifrado** (el saludo, las balizas, la identidad).
- BitChat (2025): se reportó suplantación a las pocas semanas y no tenía
  auditoría externa. No hay evidencia independiente de que funcionara en las
  protestas donde se descargó.
- Briar solo sincroniza directo entre contactos. No pasa mensajes por
  desconocidos.

**LoRa / Meshtastic.**

- La app habla con el nodo por Bluetooth LE, en protobuf. Una app propia puede
  usar su propio puerto.
- Cada paquete lleva como máximo 233 bytes útiles: solo texto, y comprimido.
- Las cabeceras de radio viajan en claro. El contenido lo sigue protegiendo
  Signal; los metadatos de radio no.
- En Perú la banda es 915-928 MHz (PNAF, RM 0597-2023-MTC). **No se verificó**
  si un nodo importado para uso personal necesita homologación del MTC.

**Satélite.**

- Android 15+ trae SMS, MMS y RCS del sistema por satélite.
- En Android 16+ una app puede declararse optimizada para datos satelitales.
  Quien decide qué apps pasan es el operador.
- En Perú, Entel + Starlink: SMS comercial desde diciembre de 2025, y WhatsApp
  solo en un piloto (abril de 2026). **No se pudo confirmar** que haya datos
  comerciales para apps de terceros.

**SMS.**

- Un sobre mínimo son 3 o 4 SMS.
- Fuera de la tienda, el permiso de SMS exige que la persona active "ajustes
  restringidos" a mano.
- Le da al operador el número y el grafo de contactos, y choca con un diseño
  donde el teléfono se guarda solo como hash.

**Sin ninguna señal** solo quedan:

- la radio local (metros);
- la gente que lleva los mensajes (horas);
- LoRa (kilómetros, con hardware);
- el satélite, si el teléfono y el operador lo soportan.

Si no hay nada de eso, lo honesto es la cola actual, con un mensaje claro de que
saldrá cuando haya alguien o algo.

## 4. Propuesta por fases

**Fase 0: que el modo cerca funcione (esfuerzo bajo-medio).**

1. Si el registro HTTP falla por red y el enlace cerca está activo, entregar
   igual por cerca. Marcar el mensaje como "registro pendiente" y registrarlo al
   volver la red: el servidor ya acepta una hora de creación en el pasado.
2. Guardar los destinos de cada conversación en la base local.
3. Volver a despachar la cola cuando el enlace pasa a `ENLAZADO`.
4. Acuse por el enlace, y entrega registrada **por aparato**: `confirmarEnvio`
   solo marca a los aparatos que de verdad recibieron.
5. Mandar el saludo después de autenticar el enlace, con un handshake tipo
   Noise y claves intercambiadas por Signal mientras hubo red.
6. Servicio en primer plano `connectedDevice` mientras el modo esté encendido,
   con autoapagado.
7. Mientras no exista la Fase 1, que el diálogo diga que hay que emparejar en
   Ajustes.
8. Prueba con **dos teléfonos reales en modo avión**, en un chat directo y en
   uno de grupo. Al volver la red: sin duplicados, y los demás aparatos lo
   reciben.

**Fase 1: Bluetooth LE sin emparejar (medio).**

- Cada aparato reparte por Signal una "clave de baliza" a sus contactos.
- Anuncia un HMAC de esa clave y la época, truncado: los contactos lo reconocen
  y para un extraño es ruido.
- Transporte L2CAP, con GATT como respaldo.
- Varios a la vez, para un grupo en la misma sala.
- Sin Google Play Services.

**Fase 2: mula entre contactos (medio).**

- Solo se lleva lo que entrega un contacto autenticado.
- Cuotas por contacto, caducidad de 24 a 72 h, deduplicación y un solo
  intermediario al principio.
- Un sobre exterior que oculte la conversación y el origen, sobre el diseño de
  `docs/evidencias/sealed-sender/DISENO.md`.

**Fase 3: LoRa, opcional (medio-alto).**

- Cliente BLE propio contra la API de Meshtastic, sin depender de su app.
- Formato binario compacto, fragmentado en paquetes de 233 B. Solo texto.
- Canal con clave propia.
- Validar la región y la homologación con el MTC antes de nada.
- Piloto con 3 a 5 nodos.

**En paralelo y barato:**

- declarar la app optimizada para datos satelitales;
- un "modo ráfaga" para redes restringidas: sin socket permanente y sin
  descarga automática de fotos.

**Lo que Android no deja hacer, aunque se quiera:**

- Un servicio en primer plano no se puede iniciar desde segundo plano. El modo
  cerca lo tiene que encender la persona; no se puede activar solo cuando se va
  la señal con la app cerrada.
- Un teléfono ajeno no puede despertar por Bluetooth a una app que el sistema
  mató.
- Los ahorradores de batería de algunos fabricantes matan procesos aunque haya
  servicio en primer plano. Pasa con Honor y Huawei, como ya se vio en la 0.6.2.

## 5. Lo que no conviene hacer

- **Malla pública por inundación por defecto.** Expone metadatos a cualquiera,
  invita al spam y gasta batería. Android la mata en segundo plano y necesita
  mucha gente con la app.
- **Aceptar sobres que abren sesión por cualquier transporte que no sea el
  servidor.** Lo que hoy hace `aceptable()` tiene que valer también para SMS,
  LoRa y las mulas.
- **Depender de Nearby Connections.**
- **Wi-Fi Direct como transporte principal.**
- **Poner el @usuario o un id fijo en el anuncio Bluetooth.**
- Encender las radios solas, o dejarlas escuchando siempre.
- **USSD**, y **SMS sin consentimiento explícito por contacto**.
- Prometer "funciona sin señal" antes de probarlo con dos teléfonos reales.

## 6. Lo que no se pudo verificar

- **El punto 1 en un teléfono real:** se verificó leyendo el código, no
  ejecutándolo.
- **Alcances reales** de BLE y Wi-Fi Direct entre teléfonos actuales.
- **Soporte actual de Wi-Fi Aware:** el dato de Briar es de aparatos viejos.
- **Datos comerciales de Starlink/Entel** para apps en Perú.
- **Tarifas de SMS** entre usuarios.
- **Homologación MTC** de los nodos LoRa y sus precios exactos.

## Fuentes

- **Android:** [Nearby Connections](https://developers.google.com/nearby/connections/strategies) · [cambios en Nearby (2026)](https://developer.android.com/blog/posts/upcoming-changes-to-the-nearby-connections-api) · [Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware) · [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct) · [BLE en segundo plano](https://developer.android.com/develop/connectivity/bluetooth/ble/background) · [tipos de servicio en primer plano](https://developer.android.com/guide/components/fg-service-types) · [cambios de Android 17](https://developer.android.com/about/versions/17/behavior-changes-17) · [satélite en AOSP](https://source.android.com/docs/core/connect/satellite) · [redes restringidas](https://developer.android.com/develop/connectivity/satellite/constrained-networks)
- **Malla:** [Breaking Bridgefy, again (USENIX 2022)](https://www.usenix.org/conference/usenixsecurity22/presentation/albrecht) · [IACR 2021/214](https://eprint.iacr.org/2021/214) · [BitChat whitepaper](https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md) · [Briar: cómo funciona](https://briarproject.org/how-it-works/) · [Briar: investigación de malla pública](https://code.briarproject.org/briar/public-mesh-research/-/wikis/Public-Mesh-Research-Report)
- **Meshtastic:** [API de cliente](https://meshtastic.org/docs/development/device/client-api/) · [mesh.proto](https://github.com/meshtastic/protobufs/blob/master/meshtastic/mesh.proto) · [cifrado](https://meshtastic.org/docs/overview/encryption/) · [CVE-2025-52464](https://www.tenable.com/cve/CVE-2025-52464)
- **Perú:** [PNAF, RM 0597-2023-MTC](https://busquedas.elperuano.pe/normaslegales/aprueban-el-plan-nacional-de-atribucion-de-frecuencias-pna-resolucion-ministerial-n-0597-2023-mtc0103-2179158-1/) · [Entel Direct to Cell](https://dplnews.com/el-servicio-direct-to-cell-de-starlink-ya-esta-en-peru-gracias-a-entel/) · [piloto de WhatsApp por satélite](https://www.convergencialatina.com/Section-Analysis/373661-3-8-Entel_tests_WhatsApp_messaging_in_areas_without_mobile_coverage_using_Starlink)
- **SMS fuera de la tienda:** [permisos de SMS en Android 15+](https://textbee.dev/blog/android-15-send-sms-permission-guide) · [restricciones a apps instaladas fuera de la tienda](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/)
