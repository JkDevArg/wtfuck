# Bots de wtfuck

Un **cliente headless** de wtfuck: un proceso en Node que se conecta como una
cuenta más, descifra lo que le mandan y responde, todo con el mismo cifrado de
punta a punta que la app y la web. Un bot es un participante como cualquiera; el
servidor sigue sin poder leer nada.

Reutiliza, sin copiar:

- el **mismo WASM de libsignal** que la web (`../web/cripto/pkg`), con el almacén
  Signal del bot en un archivo (`datos/<usuario>/almacen.json`);
- el **mismo contrato** (`../web/app/src/datos/protocolo.ts`): si el protocolo
  cambia, el bot se rompe junto con la app y la web, que es lo que se quiere.

## Fases

1. **Marco + cola** (esto). Un bot de prueba que hace eco y tiene cola de
   turnos, para probar el cifrado de ida y vuelta y que un bot de herramientas
   atienda de a uno.
2. Bot de charla con IA (Groq u otro compatible con OpenAI).
3. Bots de herramienta (nmap, nuclei, nikto, nessus) **con alcance obligatorio**:
   solo objetivos autorizados, operadores en una lista, todo registrado. Ver el
   plan en `../docs/14-BOTS.md`.

## Antes de correr

- El WASM tiene que estar compilado: `bash ../web/cripto/construir.sh`.
- Un servidor de wtfuck accesible (en local, el de desarrollo en `:8300`).

## Correr el bot de prueba (fase 1)

```bash
cd bots
npm install
cp .env.ejemplo .env     # edita usuario y contraseña
node --env-file=.env node_modules/.bin/tsx src/index.ts
```

O exportando las variables y `npm run bot`. La primera vez el bot **registra su
cuenta** solo; después reconecta con la misma identidad (el almacén y la sesión
viven en `datos/<usuario>/`, que está en `.gitignore`: son las credenciales del
bot).

Desde otra cuenta, búscalo por su `@usuario` y escríbele. Comandos: `/ayuda`,
`/turno`, `/tarea N` (simula un trabajo de N segundos para probar la cola),
`/fin`.

## Bot de charla con IA (fase 2)

En el `.env`, `WTFUCK_BOT_MODO=charla` y la key de Groq:

```bash
WTFUCK_BOT_MODO=charla
GROQ_API_KEY=gsk_...        # console.groq.com, sin tarjeta
GROQ_MODELO=openai/gpt-oss-20b
```

Responde con IA, recuerda la conversación (acotada, separada por chat) y avisa
una vez que el texto sale del cifrado hacia el proveedor. Comandos: `/ayuda`,
`/olvida` (borra lo hablado). No usa cola: cualquiera chatea cuando quiere. El
proveedor es intercambiable con `GROQ_BASE` (cualquier API compatible con
OpenAI).

Groq cambia su catalogo seguido (saco los Llama del free tier en 2026). Los
gratis hoy son `openai/gpt-oss-20b` (el que viene por defecto) y
`openai/gpt-oss-120b`. Para ver los que TU cuenta tiene:

```bash
curl https://api.groq.com/openai/v1/models -H "Authorization: Bearer $GROQ_API_KEY"
```

Si un modelo se retira, Groq responde 400 `model_decommissioned`: cambia
`GROQ_MODELO` por uno de la lista.

Para probar la key rápido, sin wtfuck:

```bash
curl https://api.groq.com/openai/v1/chat/completions \
  -H "Authorization: Bearer $GROQ_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"openai/gpt-oss-20b","messages":[{"role":"user","content":"hola"}]}'
```

## Bot de herramientas con nmap (fase 3)

Corre nmap **solo contra destinos autorizados**, **solo para operadores**, de a
uno, y todo queda en una bitacora. En el `.env`:

```bash
WTFUCK_BOT_MODO=herramienta
WTFUCK_BOT_SCOPE=/ruta/a/scope.json     # ver scope.ejemplo.json
WTFUCK_BOT_OPERADORES=tuusuario,otro    # @usuarios que pueden escanear
```

El **alcance** (`scope.json`) dice contra qué puede escanear: se niega por
defecto, un host cubre sus subdominios, una IP/CIDR lo que caiga dentro, y la
exclusion gana. Un objetivo fuera de alcance se rechaza y se anota.

Comandos: `/nmap <objetivo> [perfil]` (solo operadores), `/scope`, `/perfiles`,
`/turno`, `/fin`. Perfiles: `rapido`, `normal`, `servicios`, `completo`.

Guardarrailes, a proposito:

- el operador elige un **perfil** (un nombre), nunca flags sueltos;
- se ejecuta con argv (sin shell): el objetivo es un argumento, no hay donde
  inyectar;
- una sola herramienta a la vez (la cola);
- todo en `datos/<bot>/bitacora.jsonl`: quien, que, contra que, cuando.

**nmap tiene que estar instalado** en la maquina del bot (en el VPS,
`apt install nmap`). Para probar el flujo sin instalarlo, `WTFUCK_BOT_NMAP_FALSO=true`
usa un nmap de mentira.

> El runner deberia correr en un VPS **separado** del de mensajeria: la IP que
> escanea no es la del chat.

## Pruebas

```bash
npm test        # la lógica de la cola (sin red)
npm run typecheck
```

## Nota sobre producción

En el servidor de desarrollo el bot se registra como `SOFTWARE_DEV`. En
producción ese nivel se rechaza (`docs/04-DEVICE-BINDING.md`), así que la cuenta
del bot hay que crearla de otra forma: queda para cuando se despliegue.
