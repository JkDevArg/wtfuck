# Modo cerca, fase 0: que de verdad envíe sin internet

Antecedentes: la investigación de `docs/11-SIN-INTERNET.md` y el módulo
original en `docs/evidencias/modo-cerca/`.

## El problema

El modo cerca **recibía** por Bluetooth pero **no podía enviar** sin internet.

- **Por qué no enviaba.** El despachador registraba cada mensaje por HTTP antes
  de elegir el camino. Sin red ese registro fallaba, el bucle hacía `return` y
  nunca llegaba al Bluetooth.
- **No se había visto** porque el enlace entre dos teléfonos nunca se había
  probado: las radios de los emuladores están aisladas.
- **Lo encontró la investigación** leyendo el código, junto con siete defectos
  más que también se arreglaron aquí.

## Qué se cambió

| # | Defecto | Arreglo |
|---|---|---|
| 1 | No enviaba sin red: el registro HTTP iba antes que el transporte. | Camino propio sin servidor (`Repositorio.despacharPorCerca`). Se llama cuando no hay WebSocket y cuando el registro falla por red. El mensaje sigue PENDIENTE: con red sale por el camino de siempre a todos los demás. |
| 2 | Nada despachaba al enlazarse. | `alEnlazar`: apenas el otro lado saluda, sale lo que esperaba. |
| 3 | Los destinos vivían 30 s en memoria; sin red no había a quién mandar. | Para este camino no hacen falta: el destino es el aparato que saludó, y el cifrado usa la sesión que ya existe con él. |
| 4 | Solo probaba con aparatos ya emparejados en Ajustes, aunque se presentaba como "sin emparejar". | Ahora es explícito: hay que emparejar. El diálogo lo dice y el enlace usa RFCOMM **seguro**, cifrado y autenticado por la clave del emparejamiento. Se rechaza cualquier conexión de un aparato no emparejado. Quitar el emparejamiento es la fase 1 (balizas BLE). |
| 5 | Con la pantalla apagada o la app de fondo, el enlace moría. | `ServicioCerca`, un servicio en primer plano de tipo `connectedDevice`, con un aviso fijo y un botón "Apagar". Se apaga solo tras media hora sin nadie y no revive si el sistema mata la app (`START_NOT_STICKY`). |
| 6 | No había acuses por el enlace: lo enviado quedaba "en cola" y se reenviaba. | `MensajeCerca.Acuse`. Quien recibe lo manda **solo si guardó** el mensaje. Quien envía lo anota por aparato (`mensaje.cercaEntregado`, Room 35) y la burbuja dice **"por Bluetooth"** con su ícono. |
| 7 | Bug latente en grupos: la clave de emisor se habría dado por repartida a todos tras entregar a uno. | Por el enlace se cifra **por pares** para ese aparato (`Cifrador.cifrarSoloPara`), también en grupos. La clave de emisor no se toca. Cifrar el grupo para un solo miembro le habría hecho creer que salieron todos los demás y la habría rotado en cada mensaje. |
| 8 | El saludo (usuario, aparato) iba en claro a cualquiera que se conectara. | El enlace seguro lo cifra, y solo se acepta a aparatos emparejados. |

**Arreglos de paso:**

- El modo cerca **salió de la lista de transportes** del despachador. Con el
  WebSocket caído se llevaba las ediciones, el historial y la señalización (que
  tienen que llegar a todos) a un solo aparato.
- **Reglas nuevas en la charla del enlace** (`CharlaCerca`, Kotlin puro):
  - un sobre tiene que venir del aparato **que saludó**;
  - antes del saludo no se acepta nada;
  - un acuse solo vale a nombre de quien saludó.
- **Por ahora solo texto.** Un adjunto vive en el almacén del servidor y, sin
  red, quien lo recibe no podría bajarlo. Sale entero cuando vuelve la red.

## Pruebas

**Unitarias: 661, sin fallos.** Las nuevas:

- **`CharlaCercaTest`, 9 casos.** Dos tuberías en la JVM hacen de radio:
  - el saludo;
  - el acuse de lo guardado, y no de lo que no se pudo guardar;
  - un sobre para otro aparato;
  - un sobre antes del saludo;
  - un sobre a nombre de otro;
  - un acuse ajeno;
  - una trama ilegible;
  - el corte del enlace.
- **`EnvioCercaTest`, 7 casos.** Qué conversación puede salir hacia quién
  (directa, grupo, mi otro aparato, canal), que solo sale texto, y la lista de
  aparatos.

### En los emuladores, con el servidor desenchufado

Las radios de los emuladores no se ven. Por eso, **solo en debug**,
`TransporteCerca` puede usar dos puertos TCP en lugar del Bluetooth:

- se activa con un archivo `puente-cerca.txt` escrito con `adb shell run-as`,
  algo que solo funciona con una app depurable;
- `pruebas/puente-cerca.sh` lo pone y lo quita.

Todo lo de arriba del enlace es el código de verdad: saludo, sobres, acuses,
despacho sin red, cifrado y servicio. Lo único simulado es la radio.

| Paso | Resultado |
|---|---|
| Servidor desenchufado (sin `reverse` de 8088/9000) y apps reiniciadas | Las dos dicen "Sin conexión". |
| Modo cerca encendido en las dos | "Conectado con @goblin2026" y "Conectado con @xampl3", con el aviso fijo "Modo cerca encendido" (`2-enlazados-*.png`). |
| xampl3 → goblin2026: "Hola desde el modo cerca sin internet" | En xampl3 la burbuja dice **"por Bluetooth"**, así que llegó el acuse. En goblin2026 aparece en la lista como "1 mensaje sin leer", con notificación (`3`, `4`, `5`). |
| goblin2026 responde "Recibido por Bluetooth" | Llega a xampl3 con "por Bluetooth" del lado de quien envía (`6`). |
| Mensaje al grupo "goblin2026, probador" | Le llega a goblin2026, por pares, y la burbuja dice "por Bluetooth". El otro miembro lo recibirá al volver la red (`7`). |
| xampl3 con la **pantalla apagada** (`mWakefulness=Asleep`) | El mensaje de goblin2026 llega y se acusa igual. Al despertar estaba la notificación (`8`). |
| **Servidor de vuelta** | La cola se registró sola (6 mensajes en ~25 s). Las burbujas pasaron de "por Bluetooth" a doble check. En goblin2026, "Hola desde el modo cerca" aparece **una sola vez**, con **una** notificación: la copia del servidor se descartó como repetida (`9-grupo-con-red`, `10-*`). |
| "Apagar" desde el aviso | Se van el servicio y el aviso. Del otro lado, "Enlace terminado" y vuelta a buscar (`11-aviso-con-apagar.png`). |

### Lo que NO se pudo probar aquí

- **La radio de verdad:** el RFCOMM seguro, el emparejamiento y el rechazo de
  aparatos no emparejados. Hacen falta **dos teléfonos reales** emparejados.
  Con ellos hay que repetir la tabla de arriba en modo avión.
- **El apagado solo a la media hora.** El código está; no se esperó media hora.
- **Los ahorradores de batería de Honor y Huawei**, que pueden matar el
  servicio en primer plano igual.

## Lo que sigue (de `docs/11-SIN-INTERNET.md`)

- **Fase 1:** Bluetooth LE sin emparejar, con balizas que rotan, para no tener
  que emparejar.
- **Fase 2:** que los contactos lleven mensajes de otros, a mano.
- **Fase 3:** LoRa, opcional.
