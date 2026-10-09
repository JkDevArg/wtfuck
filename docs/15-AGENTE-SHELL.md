# Agente con shell (sandbox)

El salto del catálogo de herramientas a un **agente con shell real**: la IA corre
cualquier comando en un contenedor efímero para hacer pentesting de verdad
(encadena, reacciona a la salida, no está atada a un menú). Decidido con el
usuario (2026-10-09), modelo **autónomo con tope + egress al scope**, imagen Kali.

## Por qué el aislamiento solo no alcanza

Meter el shell en un contenedor protege el **host** (el VPS), pero no resuelve
los dos riesgos reales de un agente con shell en un bot de chat:

1. **Prompt injection → RCE.** El bot recibe mensajes de terceros y la IA lee
   salida de herramientas (banners, HTML). Si la IA decide qué ejecutar a partir
   de ese texto, quien le hable al bot —o un banner envenenado— puede dirigir el
   shell. Es el riesgo #1 de agentes (OWASP LLM: *Excessive Agency*, *Prompt
   Injection*).
2. **Terceros.** Un shell con red libre puede escanear/atacar cualquier IP de
   internet, no solo lo autorizado. Es el "escáner masivo contra terceros":
   problema legal del operador y del VPS.

## La baranda dura: egress confinado al scope

En vez de limitar *qué comando* corre (allowlist), limitamos *a dónde llega la
red* del contenedor. La IA puede correr lo que quiera; la red solo alcanza los
objetivos que el **admin aprobó** (ver [13-...]/operadores). Así un injection o
un error no pueden tocar terceros ni exfiltrar.

Egress (se arma al crear la sesión, desde el alcance aprobado del operador):
- Política por defecto `OUTPUT DROP` (y `INPUT`/`FORWARD` DROP salvo established).
- Permite loopback y established/related.
- Permite DNS (53) solo a los resolvers del contenedor (para resolver nombres).
- Permite OUTPUT **solo** a las IPs/CIDR del scope (los hosts se resuelven a IP
  al inicio; si el objetivo cambia de IP, se re-crea la sesión).
- **Deniega siempre** metadatos de nube (169.254.0.0/16), loopback y, salvo que
  el scope los incluya explícitamente, los rangos privados (RFC1918) — para que
  el contenedor no pivotee a la LAN del VPS. Reusa las exclusiones del admin.
- **Scope vacío ⇒ egress vacío**: el contenedor no conecta a nada. Falla seguro.

## Confinamiento del contenedor

`docker run --rm` (efímero, se destruye al terminar la sesión), con:
- `--cap-drop ALL`, y solo `--cap-add NET_RAW` (nmap/masscan) y `NET_ADMIN`
  (aplicar las iptables de egress). Nunca `--privileged`.
- `--security-opt no-new-privileges`, `--pids-limit`, `--memory`, `--cpus`.
- rootfs normal + `--tmpfs /tmp` (varias tools escriben ahí).
- Red propia; el entrypoint aplica el egress antes de ceder el control.

## El bucle del agente (autónomo con tope)

ReAct acotado: la IA recibe el objetivo y el historial (comando → salida), y
devuelve **un comando** por vez (JSON `{accion:"comando"|"terminado"}`). El bot lo
ejecuta en el sandbox, captura la salida (con tope de bytes y timeout) y se la
devuelve **como datos, no como instrucciones**. Repite hasta `terminado` o hasta
los topes: máximo de comandos por sesión, timeout por comando y tiempo total.
Cada comando va a la **bitácora**. Una sesión a la vez (la cola), y bajo el
mismo **rate limit** y la **aprobación del admin** que el resto.

La salida cruda es entrada no confiable: se encapsula y el system prompt lo dice,
pero la baranda que de verdad corta el daño es el **egress**, no el prompt.

## Motor inyectable

`sandbox.ts` define `MotorContenedor` (crear/ejecutar/destruir). En producción es
Docker (`motorDocker`); en las pruebas, uno falso. Así la lógica (armado del
egress desde el scope, bucle del agente, límites) se prueba sin Docker, y el
sandbox real se valida en el VPS Linux.

## Config (entorno)

- `WTFUCK_BOT_SANDBOX=true` — activa el modo agente-shell (si no, sigue el catálogo).
- `WTFUCK_BOT_IMAGEN` — imagen del contenedor (default `kalilinux/kali-rolling`).
- `WTFUCK_BOT_MAX_COMANDOS` (ej. 15), `WTFUCK_BOT_COMANDO_TIMEOUT_S`,
  `WTFUCK_BOT_SESION_TIMEOUT_S` — los topes.
- `WTFUCK_BOT_MEM`, `WTFUCK_BOT_CPUS`, `WTFUCK_BOT_PIDS` — límites del contenedor.

## Estado

| Parte | Estado |
|---|---|
| Diseño | **Hecho** (este doc) |
| `sandbox.ts` (motor + egress) | en curso |
| `agente.ts` (bucle ReAct) | pendiente |
| Integración en el bot | pendiente |
| Prueba real en el VPS (Kali) | pendiente (la corre el usuario) |
