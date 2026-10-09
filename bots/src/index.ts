// Arranca un bot con la configuracion del entorno.
//
//   cd bots && npm install
//   node --env-file=.env node_modules/.bin/tsx src/index.ts
//
// Modos (WTFUCK_BOT_MODO):
//   eco    - devuelve lo que le escribas (fase 1).
//   charla - responde con IA (Groq). Necesita GROQ_API_KEY.
import { Bot, type Modo } from './bot.ts';
import { Groq } from './ia.ts';
import { join } from 'node:path';

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

// En modo charla, la IA. Un bot de charla no usa cola (cualquiera escribe
// cuando quiere); uno de herramienta si.
const esCharla = modo === 'charla';
const ia = esCharla
  ? new Groq({
      apiKey: env('GROQ_API_KEY'),
      modelo: env('GROQ_MODELO', 'llama-3.3-70b-versatile'),
      base: process.env['GROQ_BASE'],
    })
  : undefined;

const SYSTEM_POR_DEFECTO =
  'Sos un asistente dentro de wtfuck, una app de mensajeria privada. Respondé en ' +
  'español rioplatense/peruano, breve y al grano, en texto plano (sin markdown). ' +
  'Si no sabés algo, decilo.';

const bot = new Bot({
  base: env('WTFUCK_BASE', 'http://localhost:8300'),
  datos: join(import.meta.dirname, '..', 'datos', env('WTFUCK_BOT_USUARIO')),
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
});

await bot.arrancar();

for (const senal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(senal, () => {
    log('Cerrando...');
    bot.cerrar();
    process.exit(0);
  });
}
