# wtfuck

Mensajería privada, cerrada, cifrada de extremo a extremo.
Registro por **username** — sin teléfono ni correo obligatorios.

Tres módulos de compilación, todo Kotlin, para que un cambio en el contrato
rompa **los dos lados en el mismo build**:

```
protocol/   contrato compartido servidor <-> app (Kotlin puro)
server/     Ktor + PostgreSQL + MinIO
app/        Android nativo, Kotlin + Compose
pruebas/    suites de integración contra el servidor real
docs/       decisiones de arquitectura, hoja de ruta, API, despliegue
```

## Estado

Versión publicada: **0.6.3**. La **0.6.4**, sin publicar todavía, trae el modo
cerca sin emparejar (Bluetooth LE) y la pantalla de Bloqueados. Lo que cambió en cada versión está en
[CHANGELOG.md](CHANGELOG.md), que se genera desde la pantalla de Novedades de la
app con `python despliegue/changelog.py`.

Servidor y app funcionando, verificados en dos emuladores. Hay **2609 pruebas
en verde** en la configuración mínima (una instancia, sin Redis):

- 1846 de integración, en 48 suites;
- 87 de JUnit en el servidor;
- 676 en la app.

Las dos suites del bus se omiten cuando les falta el entorno, y el runner las
marca `OMIT` en vez de `OK`. Las pruebas JUnit del servidor necesitan la misma
base que el servidor local (`WTFUCK_DB_URL`); sin ella fallan al conectar, no
por el código.

Tres de ellas **hacen ataques de verdad**:

- `bus-inyeccion.mjs` publica un evento falso directo en Redis y comprueba que
  el cliente no lo obedece.
- `ajeno.mjs` recorre las 37 rutas que **escriben** algo con un id y las ataca
  con un tercero sin ninguna relación con el objeto.
- `ajeno-lectura.mjs` hace lo mismo con las **lecturas**, que es la mitad donde
  duele: una escritura ajena rompe algo y se nota, una lectura ajena no deja
  rastro. Incluye las ocho del panel y la escalera de staff 50 < 80 < 100.

Las tres están validadas al revés —fallan contra el build anterior al arreglo—,
porque una prueba de seguridad que no falla contra el código vulnerable no
prueba nada.

Y hay otras que no atacan al servidor sino **al cliente**: `ContenidoHostilTest`
y `MiniaturaHostilTest` comprueban qué pasa cuando el sobre no lo escribió esta
app. Es el punto donde el cifrado de extremo a extremo se cobra su precio: el
servidor no puede mirar dentro de un sobre, así que **no hay nadie más** entre
quien lo escribe y quien lo dibuja.

`gifs.mjs` cubre el único sitio donde este servidor sale a internet por su
cuenta, que por eso mismo es el único donde puede ser empujado a pedirle algo a
quien no debe.

| Módulo | Qué es | Estado |
|---|---|---|
| 0 | Esquema, auth, chat 1:1, cola offline, perfil, privacidad | ✅ |
| A | RBAC: 34 permisos, bloqueos, restricciones, auditoría | ✅ |
| B | Grupos avanzados: config, roles propios, invitaciones, solicitudes | ✅ |
| C | Mensajes ricos: responder, reaccionar, editar, retirar, fijar, temporales | ✅ |
| D | Multimedia: almacén cifrado, cuotas, descarga automática | ✅ |
| **E** | **E2EE con libsignal**: PQXDH + Double Ratchet + Sender Keys | ✅ |
| F | Canales, con aprobación del dueño de la plataforma | ✅ |
| G | Moderación: denuncias, advertencias, sanciones, antispam | ✅ |
| H | Panel administrativo: cola, personas, grupos, límites, bitácora | ✅ |
| I | Identidad: teléfono verificado, recuperación, 2FA, sesiones | ✅ |
| J | Multi-dispositivo: hasta 8, vinculación por QR, historial | ✅ |
| K | Llamadas WebRTC de audio y video, con ventana flotante | ✅ |
| L | Interfaz completa, notificaciones, consola web de administración | ✅ |
| M | Ubicación, contacto, encuestas, eventos, buscar en el chat | ✅ |
| N | Push, tema claro, tablet/escritorio, segunda instancia, accesibilidad, bus firmado | ✅ |
| O | Historias: texto, foto y video; responder; quién la vio; 24 h | ✅ |
| P | Tipos de cuenta: personal, desarrollador y empresa con ficha | ✅ beta |
| Q | 15 ajustes de privacidad, solicitudes de mensaje y métricas del panel | ✅ |
| R | La suspensión congela el perfil público; auditar a prueba de fallos | ✅ |
| P.2 | La ficha de empresa se ve desde fuera, no solo en el perfil propio | ✅ |
| P.3 | Límites de ritmo en las escrituras de autoservicio del módulo P | ✅ |
| S | Lista de chats: botón Nuevo y selección múltiple en lote | ✅ |
| T | Nombres de contacto en vez de @usuario, y menciones visibles | ✅ |
| U | Bloqueo de la app con huella o PIN, y estados de error honestos | ✅ |
| V | Depuración visual de la lista de chats | ✅ |
| W | Las hojas de formulario se abren enteras (el botón quedaba fuera) | ✅ |
| X | Privacidad no muestra ni guarda ajustes que no pudo leer | ✅ |
| Y | Stickers propios: packs, favoritos, recientes, emoji y animados | ✅ |
| Y.3 | Un panel con tres pestañas: emojis, GIFs y stickers separados | ✅ |

**Lo que falta, y por qué:**

| Qué | Por qué |
|---|---|
| **Una credencial de Firebase** | El push está construido y probado contra un FCM de mentira; falta el proyecto, la cuenta de servicio y su clave. Se ponen en el servidor y el próximo arranque de cada teléfono se registra solo: **no hay que recompilar**. Hasta entonces, con la app **cerrada** no hay proceso que despertar. Ver `docs/09-DESPLIEGUE.md` |
| ~~`WTFUCK_GIPHY_KEY`~~ | **Configurada.** Vive en `.env` (ignorado por git); hay plantilla en `.env.ejemplo`. En producción la inyecta el despliegue |
| SFU para llamadas de más de 4 | La malla tiene techo declarado: con N participantes son N-1 conexiones por aparato |
| Cliente web de **mensajería** | **En camino.** La fase W0 está hecha: libsignal 0.86.5 compilada a WebAssembly habla con la del teléfono (`docs/evidencias/web-w0/`). Plan y modelo de confianza en `docs/12-VERSION-WEB.md`. En web ya está la consola de **administración**, que es otra cosa |

Lo que **salió** de esta lista: el tema claro (se rederivó la escala midiendo,
no se invirtió la oscura), la interfaz de tablet y escritorio, y el `Hub` fuera
del proceso para levantar una segunda instancia.

## Cómo levantarlo

```bash
docker compose up -d
```

Levanta PostgreSQL (5433) y MinIO (9000/9001). El servidor aplica las
migraciones y crea el bucket al arrancar.

```bash
./gradlew :server:installDist && WTFUCK_PERMITIR_SOFTWARE_DEV=true ./server/build/install/server/bin/server
```

`WTFUCK_PERMITIR_SOFTWARE_DEV=true` deja entrar a los emuladores, que no tienen
enclave seguro. Sin ella el servidor los **rechaza**: es el defecto de producción
desde el 2026-10-08 (ver `docs/04-DEVICE-BINDING.md`). `pruebas/arrancar-servidor.ps1`
ya la pone.

El puerto por defecto es **8300**: en Windows, Hyper-V reserva rangos al azar en
cuanto Docker Desktop arranca y 8081-8180 cae ahí a menudo.

```bash
.\pruebas\conectar-emuladores.ps1
```

Abre los **dos** túneles que hacen falta, en todos los emuladores conectados:

| Túnel | Para qué | Síntoma si falta |
|---|---|---|
| `tcp:8088 → tcp:8300` | el servidor | las pantallas que dependen de la red salen vacías |
| `tcp:9000 → tcp:9000` | el almacén (MinIO) | el texto funciona y **sólo fallan fotos y vídeos** |

No es opcional y hay que repetirlo **en cada arranque del emulador**: el AVD no
alcanza `10.0.2.2` desde la app aunque el ping funcione. El del almacén tiene
que ser 9000 a los dos lados, porque la URL de subida va firmada y la firma
incluye el host. Ver las notas de `app/build.gradle.kts` y `Almacen.kt`.

Detalle completo, variables de entorno y el paso a producción:
[`docs/09-DESPLIEGUE.md`](docs/09-DESPLIEGUE.md).

## Las pruebas

```bash
./gradlew :server:test :app:testDebugUnitTest
```

```bash
node pruebas/correr.mjs
```

Las de integración hablan con el servidor **real** contra la base real. Son
lentas y por eso encuentran cosas que un mock no encontraría: el límite
configurable que se guardaba bien y no se aplicaba durante 30 s lo encontró la
prueba que pedía por la ruta, no la que llamaba a la función.

Dos suites necesitan algo más y lo dicen en vez de fingir: `push.mjs` omite la
parte del envío si el servidor no tiene push configurado, y `bus.mjs` se omite
entera si no hay una **segunda instancia** escuchando. Un cruce entre instancias
marcado en verde sin haberse hecho sería el peor resultado posible.

## Documentos

- [`docs/01-ARQUITECTURA.md`](docs/01-ARQUITECTURA.md) — módulos, capa de transporte, qué hace escalable el diseño
- [`docs/03-DESIGN-SYSTEM.md`](docs/03-DESIGN-SYSTEM.md) — paleta, contrastes medidos, semántica de estado
- [`docs/04-DEVICE-BINDING.md`](docs/04-DEVICE-BINDING.md) — una cuenta por hardware: cómo, y hasta dónde llega
- [`docs/05-PLAN-COMPLETO.md`](docs/05-PLAN-COMPLETO.md) — plan y modelo de permisos
- [`docs/06-HOJA-DE-RUTA.md`](docs/06-HOJA-DE-RUTA.md) — paso a paso rastreable, con las decisiones y los defectos encontrados
- [`docs/07-COBERTURA-DEL-BRIEF.md`](docs/07-COBERTURA-DEL-BRIEF.md) — el brief punto por punto: qué está, qué no y dónde
- [`docs/08-API.md`](docs/08-API.md) — las rutas
- [`docs/09-DESPLIEGUE.md`](docs/09-DESPLIEGUE.md) — local y producción

## Cuatro decisiones que explican todo lo demás

1. **El servidor es un buzón tonto.** Guarda sobres opacos y los borra al
   confirmarse la entrega. No guarda historial ni lee contenido. Eso lo hace
   privado y horizontalmente escalable a la vez — y también es lo que limita lo
   que el panel de administración puede hacer, por diseño y no por falta de
   pantallas.

   Tiene **dos excepciones declaradas**, y sólo dos: el contenido de los canales
   públicos, y el texto de un mensaje denunciado, que lo entrega en claro quien
   denuncia porque su teléfono ya lo descifró y es el único que puede.

2. **Un sobre no sabe por dónde viaja.** La app encola; el despachador elige
   transporte. El modo cerca (`msg off`) usa la misma cola: sin red, el texto
   sale por Bluetooth al aparato enlazado y el mensaje sigue pendiente hasta
   que el servidor lo registra para los demás. Ver `docs/11-SIN-INTERNET.md`.

3. **Los permisos no viajan en el token.** Se resuelven contra la base en cada
   petición, por eso degradar a un administrador surte efecto de inmediato en
   vez de esperar a que venza su sesión.

4. **La dirección de Signal es el dispositivo, no la persona.** Una sesión de
   cifrado es entre dos aparatos. Todo el multi-dispositivo sale de tomarse esa
   frase en serio: una copia del mensaje por dispositivo destino, no por
   persona.
