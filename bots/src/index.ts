// Arranca un bot con la configuracion del entorno.
//
//   cd bots && npm install
//   node --env-file=.env node_modules/.bin/tsx src/index.ts
//
// Modos (WTFUCK_BOT_MODO):
//   eco         - devuelve lo que le escribas (fase 1).
//   charla      - responde con IA (Groq). Necesita GROQ_API_KEY.
//   herramienta - corre nmap con alcance obligatorio. Necesita WTFUCK_BOT_SCOPE
//                 y WTFUCK_BOT_OPERADORES.
import { join } from 'node:path';
import { Bot, type Modo } from './bot.ts';
import { Groq } from './ia.ts';
import { Bitacora } from './bitacora.ts';
import { crearNmap, ejecutorReal, type Ejecutor } from './herramientas.ts';
import { Scope } from './scope.ts';

function env(nombre: string, porDefecto?: string): string {
  const v = process.env[nombre] ?? porDefecto;
  if (v === undefined) {
    console.error(`Falta la variable ${nombre}. Ver bots/.env.ejemplo.`);
    process.exit(1);
  }
  return v;
}

const nombre = env('WTFUCK_BOT_NOMBRE', 'eco');
const modo = env('WTFUCK_BOT_MODO', 'eco') as Modo;
const log = (m: string) => console.log(`[${new Date().toISOString().slice(11, 19)}] (${nombre}) ${m}`);
const datos = join(import.meta.dirname, '..', 'datos', env('WTFUCK_BOT_USUARIO'));

// --- Modo charla: la IA ---
const ia =
  modo === 'charla'
    ? new Groq({ apiKey: env('GROQ_API_KEY'), modelo: env('GROQ_MODELO', 'openai/gpt-oss-20b'), base: process.env['GROQ_BASE'] })
    : undefined;

const SYSTEM_POR_DEFECTO =
  'Sos un asistente dentro de wtfuck, una app de mensajeria privada. Respondé en ' +
  'español rioplatense/peruano, breve y al grano, en texto plano (sin markdown). ' +
  'Si no sabés algo, decilo.';

// --- Modo herramienta: scope, operadores, bitacora, nmap ---
let herramienta;
let scope;
let operadores: string[] = [];
let bitacora;
if (modo === 'herramienta') {
  scope = Scope.desdeArchivo(env('WTFUCK_BOT_SCOPE'));
  if (scope.vacio) {
    console.error('El alcance (WTFUCK_BOT_SCOPE) esta vacio: el bot no escanearia nada. Agregá destinos permitidos.');
    process.exit(1);
  }
  operadores = env('WTFUCK_BOT_OPERADORES', '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  if (operadores.length === 0) console.warn('WTFUCK_BOT_OPERADORES vacio: nadie podra correr herramientas.');
  bitacora = new Bitacora(join(datos, 'bitacora.jsonl'));

  // Un nmap de mentira para probar el flujo sin tener nmap instalado.
  const ejecutor: Ejecutor =
    env('WTFUCK_BOT_NMAP_FALSO', 'false') === 'true'
      ? async (_cmd, args) => {
          await new Promise((r) => setTimeout(r, 1500));
          return { codigo: 0, salida: `[nmap de MENTIRA] argv: ${args.join(' ')}\n22/tcp open ssh\n80/tcp open http`, recortado: false, vencio: false };
        }
      : ejecutorReal;

  herramienta = crearNmap(ejecutor, {
    timeoutMs: Number(env('WTFUCK_BOT_TIMEOUT_S', '300')) * 1000,
    maxBytes: Number(env('WTFUCK_BOT_MAXBYTES', '20000')),
  });
}

const esCharla = modo === 'charla';
const bot = new Bot({
  base: env('WTFUCK_BASE', 'http://localhost:8300'),
  datos,
  username: env('WTFUCK_BOT_USUARIO'),
  password: env('WTFUCK_BOT_PASSWORD'),
  etiqueta: env('WTFUCK_BOT_ETIQUETA', 'bot'),
  nivel: env('WTFUCK_BOT_NIVEL', 'SOFTWARE_DEV'),
  nombre,
  modo,
  conCola: env('WTFUCK_BOT_COLA', esCharla ? 'false' : 'true') === 'true',
  inactividadMs: Number(env('WTFUCK_BOT_INACTIVIDAD_S', '600')) * 1000,
  log,
  ia,
  systemPrompt: env('WTFUCK_BOT_SYSTEM', SYSTEM_POR_DEFECTO),
  historialMax: Number(env('WTFUCK_BOT_HISTORIAL', '8')),
  herramienta,
  scope,
  operadores,
  bitacora,
});

await bot.arrancar();

for (const senal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(senal, () => {
    log('Cerrando...');
    bot.cerrar();
    process.exit(0);
  });
}
