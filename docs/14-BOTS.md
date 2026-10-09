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
- **Alcance obligatorio.** Cada objetivo se cruza contra un scope declarado
  (dominios/IPs del engagement). Fuera de scope: rechazado y registrado. Es la
  misma disciplina de un engagement autorizado; sin esto el bot sería una
  botonera de escaneo masivo contra terceros desde la IP del VPS.
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
| 3 | Bots de herramienta con scope (nmap primero) | — |

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
