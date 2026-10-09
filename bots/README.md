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
GROQ_MODELO=llama-3.3-70b-versatile
```

Responde con IA, recuerda la conversación (acotada, separada por chat) y avisa
una vez que el texto sale del cifrado hacia el proveedor. Comandos: `/ayuda`,
`/olvida` (borra lo hablado). No usa cola: cualquiera chatea cuando quiere. El
proveedor es intercambiable con `GROQ_BASE` (cualquier API compatible con
OpenAI).

Para probar la key rápido, sin wtfuck:

```bash
curl https://api.groq.com/openai/v1/chat/completions \
  -H "Authorization: Bearer $GROQ_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"llama-3.3-70b-versatile","messages":[{"role":"user","content":"hola"}]}'
```

## Pruebas

```bash
npm test        # la lógica de la cola (sin red)
npm run typecheck
```

## Nota sobre producción

En el servidor de desarrollo el bot se registra como `SOFTWARE_DEV`. En
producción ese nivel se rechaza (`docs/04-DEVICE-BINDING.md`), así que la cuenta
del bot hay que crearla de otra forma: queda para cuando se despliegue.
