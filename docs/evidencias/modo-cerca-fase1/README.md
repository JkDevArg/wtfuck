# Modo cerca, fase 1: Bluetooth LE sin emparejar

Antecedentes:

- la investigación, en `docs/11-SIN-INTERNET.md`;
- la fase 0, en `docs/evidencias/modo-cerca-fase0/`.

## El problema

La fase 0 entrega sin internet, pero **exige emparejar los teléfonos** en los
ajustes de Bluetooth de Android. El cifrado del enlace lo ponía el
emparejamiento: RFCOMM seguro, y se rechazaba a cualquier aparato no
emparejado.

Para quitar el emparejamiento hay que resolver dos cosas que el sistema deja
de dar:

1. **Reconocerse sin delatarse.** Un anuncio Bluetooth lo oye cualquiera. Si
   dijera "soy @ana", cualquiera con un escáner sabría quién está en la sala y
   la podría seguir de sala en sala.
2. **Un enlace cifrado y autenticado** sin la clave del emparejamiento.

## El diseño

Todo el protocolo está en `protocol/.../CercaBle.kt`. Es Kotlin puro y se
prueba en la JVM sin radio. La curva X25519 se inyecta: en el teléfono la
pone libsignal y en las pruebas el JDK.

### La baliza: lo que se anuncia (`Baliza`)

- **Clave de baliza.** Cada aparato tiene 32 bytes al azar (`MiBaliza`),
  guardados con el Keystore igual que la frase de las copias.
- **Quién la recibe.** Solo los contactos de **chats directos** y mis otros
  aparatos (la nota para mí). Va dentro de los mensajes normales, cifrada de
  punta a punta: es un campo opcional `baliza` en `Carga.Texto` y
  `CargaAdjunto`. Viaja **una vez por versión de la clave y por chat**
  (`baliza_enviada`); no va en cada mensaje.
  - **Por qué no en grupos:** un grupo tiene gente con la que no hablo.
  - **Por qué un campo y no un tipo de carga nuevo:** un cliente 0.6.3 ignora
    los campos que no conoce. Un tipo de carga desconocido le habría mostrado
    "(no se pudo descifrar)".
- **Qué se anuncia.** El anuncio lleva solo datos de fabricante, sin nombre de
  aparato:

  ```text
  0xFFFF | 0x57 'W' | versión 1 | token (8 bytes) | PSM L2CAP (2 bytes)
  ```

  - `token = HMAC-SHA256(clave, "wtfuck/baliza/v1" || época)`, truncado.
  - Una época dura 15 minutos.
  - `0xFFFF` es el identificador que el Bluetooth SIG reserva para pruebas.
- **Cómo se reconoce.** Un contacto calcula los tokens de cada clave que
  conoce, para la época anterior, la actual y la siguiente (por si los relojes
  difieren), y busca coincidencias. Para un extraño, el token es ruido que
  cambia cada cuarto de hora, sin forma de unir dos épocas. Android, además,
  rota por su cuenta la dirección Bluetooth del anuncio.
- **Lo que SÍ se ve:** que hay un teléfono con wtfuck cerca. La marca `0x57 01`
  es fija porque el escáner necesita algo por donde filtrar.

### El apretón de manos (`Apreton`)

La confianza sale de las **claves de identidad de Signal** que los dos ya
tienen del otro. Es la misma condición que el modo cerca ya tenía: "solo con
quien ya hablaste".

**El secreto del enlace** se deriva con HKDF a partir de tres cosas:

- `DH(identidad mía, identidad suya)`: solo esos dos aparatos lo pueden
  calcular. Es lo que autentica.
- `DH(efímera mía, efímera suya)`: claves nuevas en cada enlace. Lo grabado
  hoy sigue cerrado aunque una identidad se filtre mañana.
- Un nonce de cada lado, para que dos enlaces nunca compartan claves.

**Los tres mensajes:**

1. Quien llama manda su efímera, su nonce y **quién es**. Esto último va
   cifrado con una clave derivada de la baliza de quien atiende, así que quien
   solo escucha no se entera de quién llama.
2. Quien atiende busca esa identidad. Si no la conoce, corta. Si la conoce,
   manda su efímera, su nonce y una prueba de que tiene su clave privada.
3. Quien llama comprueba esa prueba y manda la suya.

**Después del apretón**, cada trama va cerrada con `Sello`:

- AES-256-GCM, con una clave por sentido;
- un contador estricto por trama: una trama repetida, reordenada o borrada
  corta el enlace.

`CharlaCerca` usa el sello cuando lo tiene. Ignora un saludo que no sea del
aparato que autenticó el apretón.

### El transporte (`TransporteCerca`)

- **Solo Android 12 o más nuevo.** Hace falta `BLUETOOTH_ADVERTISE` y el
  canal L2CAP orientado a conexión.
- **Android 8 a 11** sigue con la fase 0: emparejar y usar RFCOMM seguro. Las
  dos fases conviven; la 0 sigue activa para los emparejados.
- **Quien atiende:**
  - abre `listenUsingInsecureL2capChannel` y anuncia su PSM;
  - rehace el anuncio cuando cambia la época o **cuando rota la clave**;
  - comprueba cada 3 s si hace falta.
- **Quien busca:**
  - filtra el escaneo por los datos de fabricante con la marca;
  - rehace el índice de tokens cada minuto;
  - espera 20 s antes de reintentar con el mismo aparato.
- **Quien llama:**
  - abre `createInsecureL2capChannel`. El "insecure" se refiere al
    emparejamiento de Bluetooth; el cifrado lo pone el sello;
  - tiene un vigía de 15 s para el apretón.
- **Varios enlaces:** hasta 4 a la vez, para un grupo en la misma sala.
- **Enlaces duplicados:** si dos aparatos se llaman a la vez, queda el enlace
  que inició el de id menor.
- **Despacho.** `despacharPorCerca` recorre los pares enlazados. Cada sobre
  sale por el enlace de su aparato.

### El bloqueo

Por el aire no hay servidor que aplique el bloqueo. Al bloquear a alguien:

1. Se borran las balizas suyas que yo tenía.
2. **Rota mi clave.** Esa persona se queda con la vieja y deja de reconocerme.
   El anuncio se rehace en el acto, sin esperar a la próxima época. Los demás
   contactos reciben la nueva con el próximo mensaje que les mande.
3. Se agrega a una **lista local de bloqueados para el modo cerca**
   (`wtfuck_cerca_bloqueos`). Sus sobres se descartan y no se le despacha nada.
4. Se corta el enlace con esa persona si estaba abierto.

Desbloquear la quita de la lista local. Cerrar sesión o cambiar de cuenta
borra las balizas, la clave y la lista.

### Base local

Room **36**:

- `baliza(dispositivoId, usuarioId, username, clave, recibidaEn)`: las claves
  de mis contactos;
- `baliza_enviada(conversacionId, version)`: qué versión de mi clave ya tiene
  cada chat.

## Pruebas

**Unitarias: 676, sin fallos.** Las nuevas son 15 casos en `CercaBleTest`:

- **Baliza:**
  - cambia cada cuarto de hora y es distinta para cada clave;
  - el anuncio se lee igual que se escribe;
  - un anuncio ajeno o mal formado no se lee (largo, marca, versión y un PSM
    fuera de 0x80–0xFF);
  - se reconoce la época vecina, pero no una lejana.
- **Apretón:**
  - dos que se conocen se enlazan, y cada uno sabe con quién;
  - quien atiende corta si no conoce a quien llama;
  - sin la baliza de quien atiende no se puede ni decir quién eres;
  - un impostor que dice ser Ana no completa el apretón, aunque sepa la baliza
    de Beto;
  - no basta la baliza de Beto: hace falta su identidad;
  - repetir una primera llamada grabada no sirve.
- **Sello:**
  - una trama alterada no abre;
  - una repetida o fuera de orden tampoco;
  - dos enlaces entre los mismos aparatos no comparten claves.
- **Charla sellada:** entrega el sobre y su acuse, e ignora un saludo de otro
  aparato.
- **HKDF:** coincide con el vector de prueba del RFC 5869.

### En los emuladores, con Bluetooth LE de verdad

Los dos emuladores (Android 17, API 37) comparten **netsim**, que les da una
radio BLE simulada pero real para la pila de Android. A diferencia de la fase
0, aquí no hubo puente TCP: anuncio, escaneo, L2CAP y apretón pasaron por la
pila Bluetooth del sistema.

| Paso | Resultado |
|---|---|
| Sin emparejar. Cada uno manda un mensaje con red ("te paso mi clave", "y yo la mia") | Las claves de baliza viajan dentro de esos mensajes. |
| Servidor desenchufado y modo cerca encendido en los dos | "Enlazado (BLE sin emparejar)" y "Conectado con @goblin2026" / "@xampl3". El diálogo explica el modo sin emparejar (`1-enlazados-ble-*.png`). Cuando los dos se llamaron a la vez, quedó un solo enlace. |
| xampl3 → goblin2026: "Por BLE sin emparejar" | La burbuja dice **"por Bluetooth"**: llegó y se acusó (`2-enviado-por-ble.png`). |
| goblin2026 responde "Recibido por BLE" | Igual, "por Bluetooth" del lado de quien envía (`3-respuesta-por-ble.png`). |
| goblin2026 escribe con la **pantalla de xampl3 apagada** | Llega igual: "Con la pantalla apagada por BLE". |
| **Servidor de vuelta** | Las burbujas pasan a doble check, sin duplicados (`4-con-red-otra-vez-5554.png`). |
| El anuncio, leído con el escáner | Solo datos de fabricante `0xFFFF`, sin nombre de aparato. |
| **Bloqueo, primer intento** (`5-bloquear.png`) | El enlace se cortó en el acto. Del otro lado: "Apretón rechazado: no conoce mi baliza". **Defecto:** el anuncio seguía con el token de la clave vieja hasta que cambiara la época, y el bloqueado reintentaba cada 20 s. |
| **Arreglo:** el anuncio se rehace cuando cambia la versión de la clave (`LlavesCerca.versionBaliza`) | — |
| **Bloqueo, segundo intento** | Un solo intento fallido en el momento del bloqueo y **ningún reintento** en los más de 3 minutos siguientes: el bloqueado ya no reconoce el anuncio (`6-bloqueo-con-rotacion.log`). |
| Desbloqueo e intercambio de mensajes con red | Se vuelven a enlazar con la clave nueva. |

## Lo que NO se pudo probar aquí

- **Dos teléfonos reales.** netsim es la pila de Android con una radio
  simulada. Faltan:
  - los alcances reales;
  - la interferencia;
  - los fabricantes que limitan el escaneo en segundo plano;
  - la combinación Android 12+ con un Android viejo.
- **El cambio de época con un enlace abierto** (cada 15 minutos). El código
  rehace el anuncio; no se esperó a verlo.
- **Los ahorradores de batería de Honor y Huawei**, igual que en la fase 0.
- **GATT como respaldo** de L2CAP, que proponía la investigación: no se hizo.
  Un aparato sin L2CAP CoC usa la fase 0.

## Límites conocidos

- **Se necesita haber hablado con internet antes.** Hay que tener la
  identidad de Signal del otro y su clave de baliza. Es el mismo límite que la
  fase 0.
- **Un aparato nuevo de un contacto no recibe mi clave** hasta que yo la rote:
  `baliza_enviada` se anota por chat, no por aparato. Con que uno de los dos
  tenga la clave del otro alcanza, porque llama ese.
- ~~**No hay pantalla para desbloquear.**~~ Resuelto después de esta fase:
  pantalla "Bloqueados" en Privacidad, y "Desbloquear" en la ficha. Ver
  `docs/evidencias/bloqueados/`. La misma lista pone al día los bloqueos del
  modo cerca hechos desde otro aparato.
- **Por ahora solo texto,** como en la fase 0.

## Lo que sigue

- **Fase 2:** que los contactos lleven mensajes de otros, a mano ("mula").
- **Fase 3:** LoRa, opcional.
