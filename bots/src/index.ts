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
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Bot, type Modo } from './bot.ts';
import { Groq } from './ia.ts';
import { Bitacora } from './bitacora.ts';
import { crearHerramientas, ejecutorReal, type Ejecutor } from './herramientas.ts';
import { Operadores } from './operadores.ts';
import { Orquestador } from './orquestador.ts';

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

// --- Modo herramienta: terminos + alcance declarado, operadores, bitacora, nmap ---
const TERMINOS_POR_DEFECTO = [
  'TÉRMINOS DE USO — bot de herramientas de seguridad',
  '',
  'Al usar este bot declarás y aceptás que:',
  '1. Solo vas a auditar sistemas para los que tenés AUTORIZACIÓN por escrito de',
  '   su dueño. Escanear sin permiso puede ser un delito.',
  '2. Vos sos el único responsable de lo que escanees y de sus consecuencias.',
  '   Quien opera este bot y wtfuck NO se responsabilizan por el uso que le des.',
  '3. Cada objetivo que agregás a tu alcance es una declaración tuya de que',
  '   estás autorizado a auditarlo. Queda registrado con tu usuario y la fecha.',
  '4. Hay destinos excluidos por el administrador que no se pueden auditar.',
  '',
  'Si estás de acuerdo, escribí /acepto.',
].join('\n');

let herramientas;
let operadores;
let bitacora;
let terminos;
let orquestador;
if (modo === 'herramienta') {
  bitacora = new Bitacora(join(datos, 'bitacora.jsonl'));
  const excluidos = env('WTFUCK_BOT_EXCLUIDOS', '169.254.0.0/16,127.0.0.0/8')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  const invitados = env('WTFUCK_BOT_OPERADORES', '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  const abierto = env('WTFUCK_BOT_ABIERTO', 'false') === 'true';
  const admins = env('WTFUCK_BOT_ADMIN', '')
    .split(',')
    .map((s) => s.trim().toLowerCase().replace(/^@/, ''))
    .filter(Boolean);
  if (abierto) console.warn('WTFUCK_BOT_ABIERTO=true: cualquiera que acepte los terminos puede usarlo. La unica proteccion es la atestacion registrada y la aprobacion del admin.');
  else if (invitados.length === 0 && admins.length === 0) console.warn('Sin WTFUCK_BOT_OPERADORES/ADMIN y sin modo abierto: nadie podra usar el bot.');
  if (admins.length === 0) console.warn('Sin WTFUCK_BOT_ADMIN: nadie puede aprobar alcances, asi que nadie podra escanear. Configuralo.');

  operadores = new Operadores(join(datos, 'operadores.json'), excluidos, abierto, invitados, admins);

  const terminosRuta = process.env['WTFUCK_BOT_TERMINOS'];
  terminos = terminosRuta && existsSync(terminosRuta) ? readFileSync(terminosRuta, 'utf8') : TERMINOS_POR_DEFECTO;

  // Un ejecutor de mentira para probar el flujo sin tener las herramientas
  // instaladas: devuelve el argv que se habria corrido (asi se ve que el objetivo
  // va aparte, sin shell). WTFUCK_BOT_NMAP_FALSO se mantiene por compatibilidad.
  const falso = env('WTFUCK_BOT_HERRAMIENTAS_FALSAS', env('WTFUCK_BOT_NMAP_FALSO', 'false')) === 'true';
  const ejecutor: Ejecutor = falso
    ? async (cmd, args) => {
        await new Promise((r) => setTimeout(r, 1200));
        return { codigo: 0, salida: `[${cmd} de MENTIRA] argv: ${args.join(' ')}`, recortado: false, vencio: false };
      }
    : ejecutorReal;

  const cfgHerr = {
    timeoutMs: Number(env('WTFUCK_BOT_TIMEOUT_S', '300')) * 1000,
    maxBytes: Number(env('WTFUCK_BOT_MAXBYTES', '20000')),
  };
  // WTFUCK_BOT_HERRAMIENTAS=nmap,subfinder,... limita el menu; vacio = todo el catalogo.
  const quiere = env('WTFUCK_BOT_HERRAMIENTAS', '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  herramientas = crearHerramientas(quiere, ejecutor, cfgHerr);
  if (!herramientas.length) {
    console.error('No se configuro ninguna herramienta valida. Revisa WTFUCK_BOT_HERRAMIENTAS.');
    process.exit(1);
  }
  console.log(`Herramientas: ${herramientas.map((h) => h.nombre).join(', ')}.`);

  // Orquestador (opcional): si hay GROQ_API_KEY, el bot ademas entiende
  // lenguaje natural y PROPONE acciones. Nunca se saltea el alcance: lo que la
  // IA propone pasa por la misma validacion que un comando manual.
  const claveIA = process.env['GROQ_API_KEY'];
  if (claveIA) {
    const iaAuditor = new Groq({ apiKey: claveIA, modelo: env('GROQ_MODELO', 'openai/gpt-oss-120b'), base: process.env['GROQ_BASE'] });
    orquestador = new Orquestador(iaAuditor, herramientas, process.env['WTFUCK_BOT_SYSTEM_EXTRA']);
    console.log('Orquestador IA activo (modelo ' + env('GROQ_MODELO', 'openai/gpt-oss-120b') + '). Se puede hablar en lenguaje natural.');
  } else {
    console.log('Sin GROQ_API_KEY: el bot funciona solo por comandos (/nmap, /alcance...).');
  }
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
  herramientas,
  operadores,
  bitacora,
  terminos,
  orquestador,
  // Rate limit de peticiones a la IA por operador. Un valor invalido cae al
  // default (nunca NaN, que desactivaria el limite sin avisar).
  rateMax: Number(env('WTFUCK_BOT_RATE_MAX', '8')) || 8,
  rateVentanaMs: (Number(env('WTFUCK_BOT_RATE_VENTANA_S', '60')) || 60) * 1000,
});

await bot.arrancar();

for (const senal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(senal, () => {
    log('Cerrando...');
    bot.cerrar();
    process.exit(0);
  });
}
