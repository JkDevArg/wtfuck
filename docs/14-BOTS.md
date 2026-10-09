# Bots

Bots de wtfuck: cuentas con un programa detrás en vez de una persona. Un bot es
un **cliente headless** (`bots/`) que se conecta por el mismo WebSocket, tiene
su identidad Signal, descifra lo que le mandan y responde cifrado. El servidor
no puede leer nada; el bot, que es un participante, sí.

## Tipos

- **De charla:** el texto va a una IA y la respuesta vuelve cifrada. Un chat con
  un bot de IA **sale de la burbuja E2EE hacia el proveedor de la IA**: el bot lo
  puede leer (es un participante), el servidor no. Hay que avisarlo en el chat,
  como con los canales públicos.
- **De herramienta:** corren nmap, nuclei, nikto, nessus. Son de doble uso, así
  que van con guardarraíles (abajo).

## El modelo, decidido con el usuario (2026-10-08)

- **La IA orquesta, no tiene shell.** La IA **propone** ("corre nuclei contra
  X"); un envoltorio por herramienta valida y ejecuta, con flags de una lista
  blanca. Nunca `exec(lo_que_diga_la_IA)`: eso sería ejecución de comandos desde
  un mensaje de chat.
- **Términos + alcance declarado por operador** (modelo afinado 2026-10-08). En
  vez de un scope global del admin, cada operador **acepta los términos** y
  **declara** los objetivos que atesta estar autorizado a auditar. Esa aceptación
  y cada declaración quedan en la bitácora con usuario y fecha: es el respaldo
  real (quién dijo que tenía permiso sobre qué, y cuándo), no un disclaimer
  genérico. Se descartó el "scope ilimitado + disclaimer" que se pidió: un
  disclaimer no autoriza a escanear a terceros que nunca aceptaron nada, y sin
  alcance el bot sería una botonera de escaneo masivo desde la IP del VPS.
- **Exclusiones del admin, no anulables.** Metadatos de nube (169.254/16) y
  loopback (127/8) no se pueden declarar ni con `/alcance`. El intento también se
  audita.
- **Cola de turnos.** Un bot de herramienta atiende de a uno: mientras alguien
  tiene el turno, los demás esperan y se les avisa la posición y cuándo les toca.
- **Operadores.** Los bots de herramienta solo los invocan cuentas autorizadas.
- **Aislamiento.** El runner corre en un VPS **separado** del de mensajería: la
  IP que escanea no es la del chat. Cada herramienta, en su contenedor.
- **Bitácora.** Quién pidió qué, contra qué, cuándo.

## IA

- **Proveedor:** Groq (gratis, rápido, **no entrena** con lo que recibe; ver la
  comparación en el chat del 2026-10-08). El runner lo deja como variable
  (API compatible con OpenAI), así se cambia sin tocar el resto.
- **Privacidad:** aunque Groq no entrene, la salida de un escaneo igual viaja a
  un tercero. Para trabajo con cliente, la IA recibe **resúmenes y metadatos**,
  no el volcado crudo.
- **Local:** el VPS de 8 GB sin GPU no corre un modelo local decente (3B lento,
  7B no entra con las herramientas). Por eso la IA va en línea.

## Estado

| Fase | Qué | Estado |
|---|---|---|
| 1 | Marco del runner + cola de turnos + cliente E2EE headless | **Hecha** (2026-10-08). Bot de eco probado de punta a punta contra el servidor local, cola incluida |
| 2 | Bot de charla con IA (Groq) | **Hecha** (2026-10-08). Probada de punta a punta contra un Groq de mentira local; falta una corrida con la key real |
| 3 | Bots de herramienta con scope (nmap primero) | **Hecha** (2026-10-08). nmap con términos + alcance por operador, exclusiones no anulables, cola y bitacora; probado de punta a punta en el emulador con un nmap de mentira |
| 4 | Orquestador IA (lenguaje natural → acción) | **Hecha** (2026-10-08). gpt-oss-120b con metodología PTES/WSTG; agencia acotada (solo propone, no ejecuta). Falta la corrida e2e contra Groq real |
| 5 | Aprobación del admin + agente de recon + listas | **Hecha** (2026-10-08). Alcance con aprobación (control de dos personas, probado en el emulador); agente de reconocimiento paso a paso con confirmación; rate limit; cargar objetivos desde un archivo de texto. Falta e2e del recon (Groq real) y de la carga por adjunto |

## Fase 1, lo que quedó

- `bots/` (Node + TypeScript), que reutiliza el WASM de `web/cripto` y el
  contrato de `web/app/src/datos/protocolo.ts`.
- Cliente headless: registra/reconecta la cuenta del bot, publica claves, abre
  el socket, descifra y responde por pares (sirve en directas y grupos).
- Cola de turnos (`cola.ts`), con pruebas (`cola.test.ts`, 5/5).
- Bot de prueba: eco + `/ayuda` `/turno` `/tarea N` `/fin`.

**Probado contra el servidor local:** el bot `@boteco` se registró y conectó;
desde una cuenta se le mandó `/ayuda` (descifrado y respondido), eco de un texto,
y la cola — con una segunda cuenta en "posición 1" mientras la primera tenía el
turno, y el aviso "te toca" al soltarlo con `/fin`.

**Sin probar:** despliegue en el VPS (la cuenta del bot en producción no puede
ser `SOFTWARE_DEV`); las herramientas y la IA (fases 2 y 3).

## Fase 2, lo que quedó

- `ia.ts`: interfaz `IA` y el cliente `Groq` (API compatible con OpenAI). El
  proveedor es intercambiable por variable (`GROQ_BASE`).
- `bot.ts` modo `charla`: historial por conversacion (acotado, aislado entre
  chats), aviso de privacidad una vez por conversacion, y `/olvida` para borrar
  lo hablado. Un bot de charla no usa cola.
- Pruebas: `ia.test.ts` (peticion, parseo, 429, errores — 6) con `fetch`
  simulado; la logica de la cola sigue en `cola.test.ts`.

**Probado de punta a punta** contra un Groq de mentira local (que ejercita el
cliente real): el mensaje llega a la IA con el system prompt, la respuesta
vuelve cifrada, el aviso de privacidad sale una vez, el historial crece
(2 → 4 turnos) y `/olvida` lo reinicia (vuelve a 2).

**Falta:** una corrida con la `GROQ_API_KEY` real (la pone el usuario en el
`.env`); el despliegue en el VPS.

## Fase 3, lo que quedó

- `scope.ts`: el alcance (permitidos/excluidos; host+subdominios, IP, CIDR; se
  niega por defecto; la exclusion gana). Pruebas en `scope.test.ts` (7).
- `herramientas.ts`: el envoltorio de nmap. El operador elige un **perfil**
  (rapido/normal/servicios/completo), nunca flags libres; se ejecuta con argv
  (spawn sin shell); el ejecutor es inyectable (real o de mentira). Topes de
  tiempo y de salida. Pruebas en `herramientas.test.ts` (5).
- `bitacora.ts`: JSONL append-only (quien, que, contra que, cuando, resultado).
- `operadores.ts`: términos + alcance por operador (acepta, declara, quita;
  valida forma y exclusiones del admin; persiste con usuario y fecha). Pruebas en
  `operadores.test.ts` (9).
- `bot.ts` modo `herramienta`: comandos `/terminos`, `/acepto`, `/alcance`,
  `/nmap`, `/perfiles`, `/turno`, `/fin`; solo operadores invitados; una
  herramienta a la vez (la cola); cada caso anotado.

**Probado de punta a punta en el emulador** (app real, nmap de mentira) contra el
servidor local:
- un comando antes de aceptar → devuelve los términos;
- `/acepto` → aceptación registrada;
- `/nmap scanme.nmap.org` aceptado pero sin declarar → rechazado por alcance;
- `/alcance scanme.nmap.org` → declarado; luego el escaneo corre con el argv
  correcto (`-T4 -Pn --top-ports 1000 scanme.nmap.org`, objetivo como último arg);
- `/nmap google.com` (no declarado) → rechazado;
- `/alcance 169.254.169.254` (metadatos de nube) → rechazado, no anulable.

La bitácora quedó con todos los eventos (aceptación, declaración, ejecutado,
rechazado-scope ×2) con operador y timestamp.

## Fase 4, el orquestador IA

- `orquestador.ts`: convierte lenguaje natural del operador en **una decisión
  estructurada** usando la IA solo como traductor de intención. Se activa en el
  modo `herramienta` si hay `GROQ_API_KEY` (modelo por defecto `gpt-oss-120b`).
- **Agencia acotada** (OWASP LLM Top 10 — *Excessive Agency*): la IA recibe un
  **menú cerrado** de herramientas+perfiles y un system prompt con metodología
  (PTES, OWASP WSTG) y reglas duras. Devuelve JSON `{ejecutar|responder}`. La IA
  sabe de metodología y puede sugerir próximos pasos en texto, pero **no arma
  comandos ni elige flags**.
- **No se saltea el alcance.** Lo que la IA propone pasa por la **misma puerta**
  que un comando manual (`correrObjetivo → operadores.permitido()`): mismo
  alcance, exclusiones, cola y bitácora. Herramienta/perfil/objetivo inválidos no
  disparan ejecución (caen a `responder`). La IA nunca declara alcance ni asume
  permiso; si falta, lo pide en texto. El acto de autorizar sigue siendo humano.
- Pruebas: `orquestador.test.ts` (9) con IA de mentira — parseo tolerante a
  markdown, validación contra el registro, y que nada raro dispara ejecución.

Pruebas: **41 en total** (cola 5, ia 6, scope 7, herramientas 5, operadores 9,
orquestador 9).

**Falta:** la corrida e2e contra Groq real (el usuario pone la key rotada en el
`.env`); correr nmap de verdad (instalarlo en el VPS); más herramientas (nuclei,
nikto, nessus), que entran solas al menú del orquestador.

## Fase 5, aprobación del admin + agente de recon + listas

Tres pedidos del usuario (2026-10-08), construidos sin romper ningún guardarraíl:

**1. Alcance con aprobación del admin (control de dos personas).** Pedir un
objetivo ya no lo habilita: queda `pendiente` hasta que un **admin** (distinto del
que pide, nunca auto-aprobación) lo `aprueba`. Recién ahí se puede escanear. Dos
personas tienen que estar de acuerdo. Comandos de admin: `/pendientes`,
`/aprobar <op> <obj>`, `/rechazar <op> <obj>` (`WTFUCK_BOT_ADMIN`). El bot avisa
al admin de un pedido nuevo y al operador cuando se aprueba. El estado (quién
pidió/aprobó y cuándo) queda en `operadores.json` y en la bitácora.

**Probado de punta a punta en el emulador:** `/alcance test.hackl4bs.com` → queda
⏳ y avisa al admin; `/aprobar xampl3 …` (siendo el mismo) → rechazado ("no podés
aprobar tus propios pedidos"); `/nmap` sobre el pendiente → rechazado ("pendiente
de aprobación"); `/nmap` sobre uno aprobado → corre. La migración del formato
viejo (`alcance: string[]`) a objetivos `aprobado` también se verificó.

**2. Agente de reconocimiento paso a paso.** "Reconocé X" → la IA arma un plan de
pasos (de menos a más intrusivo, máx 6); el bot lo corre **de a un paso, con tu
confirmación** entre cada uno (nunca autónomo). Cada paso streamea progreso
(`Paso k/n`, corriendo, resultado) y pasa por la misma puerta de siempre
(`permitido` + cola + bitácora). El objetivo tiene que estar aprobado antes de
empezar. `sí`/`no`/`/cancelar` controlan el avance sin gastar IA.

**3. Rate limit / un paso a la vez.** Ventana deslizante por operador sobre las
peticiones a la IA: si escribís de más, frena ("vas muy rápido") en vez de encolar
trabajo y buguearse. Mientras un paso corre, los mensajes reciben "esperá".
Ajustable por entorno: `WTFUCK_BOT_RATE_MAX` (default 8 peticiones) y
`WTFUCK_BOT_RATE_VENTANA_S` (default 60 s). Un valor inválido cae al default.

**4. Cargar objetivos desde un archivo de texto.** Si mandás un `.txt`/`.csv`/
`.list`, el bot lo baja, lo descifra (AES-256-GCM, espejo de
`web/app/src/datos/archivos.ts`) y saca los objetivos. **Solo texto seguro** (mime
`text/*` o extensión conocida; binarios rechazados), con tope de tamaño (256 KiB)
y de objetivos (50), deduplicados y clasificados (basura e inyecciones se
descartan). Cada objetivo se **pide** (pendiente de aprobación): un adjunto no
saltea el control, es solo una forma cómoda de cargar la lista. `adjuntos.ts`
tiene el descifrado y el parseo (8 tests).

Pruebas: **67 en total** (cola 5, ia 6, scope 7, herramientas 13, operadores 15,
orquestador 13, adjuntos 8).

## Catálogo de herramientas (2026-10-08)

`herramientas.ts` pasó de un nmap suelto a un **catálogo** con una fábrica
genérica. Cada herramienta declara su binario, cómo recibe el objetivo
(posicional o tras una flag), un subcomando opcional y sus perfiles de flags
fijos. **Agregar una herramienta es agregar una entrada al catálogo**: no hay
código nuevo por herramienta, así que no hay forma de colar una ejecución
arbitraria. El modelo de seguridad no cambia: perfil fijo (no flags libres),
argv sin shell, objetivo como un solo argumento (y `clasificar()` garantiza que
no empiece con `-`).

| Herramienta | Uso | Objetivo |
|---|---|---|
| nmap | puertos/servicios/scripts | host/IP |
| masscan | puertos a gran escala | IP/rango |
| rustscan | descubrimiento rápido de puertos | host/IP (`-a`) |
| naabu | descubrimiento de puertos | host/IP (`-host`) |
| amass | activos y subdominios | dominio (`enum -d`) |
| subfinder | subdominios (pasivo) | dominio (`-d`) |
| findomain | subdominios | dominio (`-t`) |
| theharvester | OSINT público | dominio (`-d`) |
| bbot | recon/descubrimiento | dominio (`-t`) |
| dnsx | consultas/validación DNS | dominio (`-d`) |
| dnsenum | enumeración DNS | dominio |
| fierce | infraestructura DNS | dominio (`--domain`) |
| whois | registro del dominio | dominio |

Comandos: `/<nombre> <objetivo> [perfil]`, `/herramientas` lista el menú con
descripciones y perfiles. El orquestador recibe todo el catálogo y las
descripciones entran al prompt para que la IA elija bien. `WTFUCK_BOT_HERRAMIENTAS`
limita el menú (vacío = todo); `WTFUCK_BOT_HERRAMIENTAS_FALSAS` usa un ejecutor de
mentira (reemplaza a `WTFUCK_BOT_NMAP_FALSO`, que sigue por compat).

**Probado en el emulador:** `/herramientas` lista las 13; `/whois` (objetivo
posicional) y `/subfinder` (objetivo con `-d`) corren con el argv correcto y bajo
el mismo alcance aprobado.

**recon-ng y spiderfoot quedan fuera a propósito:** son frameworks
interactivos/servidores, no herramientas de "un comando + perfil + argv de un
disparo". Meterlas con seguridad pide otra integración (recon-ng con *resource
scripts*; spiderfoot por su API/servidor), no una fila del catálogo.

## Metodología que sabe el orquestador (fuentes)

El system prompt del auditor (`orquestador.ts`) se apoya en estándares públicos,
no en un "prompt mágico" copiado:

- **PTES** (Penetration Testing Execution Standard): las 7 fases
  (pre-engagement → intelligence gathering → threat modeling → vuln analysis →
  exploitation → post-exploitation → reporting). <http://www.pentest-standard.org>
- **OWASP WSTG** (Web Security Testing Guide): catálogo de pruebas web;
  Information Gathering (4.1). <https://owasp.org/www-project-web-security-testing-guide/>
- **OWASP Top 10 for LLM Apps** — *Excessive Agency* y *Prompt Injection*: por
  qué la IA tiene conocimiento amplio pero agencia acotada, y por qué su salida
  se valida siempre. <https://owasp.org/www-project-top-10-for-large-language-model-applications/>
