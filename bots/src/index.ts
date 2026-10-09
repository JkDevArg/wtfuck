// Arranca un bot con la configuracion del entorno. Fase 1: un bot de prueba
// que hace eco y tiene cola de turnos.
//
//   cd bots && npm install
//   WTFUCK_BOT_USUARIO=botprueba WTFUCK_BOT_PASSWORD=... npm run bot
//
// Las variables (ver .env.ejemplo) se cargan con `node --env-file`; tsx las
// toma del entorno. No hay dependencia extra para leer el .env.
import { Bot } from './bot.ts';
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
const log = (m: string) => console.log(`[${new Date().toISOString().slice(11, 19)}] (${nombre}) ${m}`);

const bot = new Bot({
  base: env('WTFUCK_BASE', 'http://localhost:8300'),
  // Cada bot guarda su almacen y su sesion en bots/datos/<usuario>/.
  datos: join(import.meta.dirname, '..', 'datos', env('WTFUCK_BOT_USUARIO')),
  username: env('WTFUCK_BOT_USUARIO'),
  password: env('WTFUCK_BOT_PASSWORD'),
  etiqueta: env('WTFUCK_BOT_ETIQUETA', 'bot'),
  nivel: env('WTFUCK_BOT_NIVEL', 'SOFTWARE_DEV'),
  nombre,
  conCola: env('WTFUCK_BOT_COLA', 'true') === 'true',
  inactividadMs: Number(env('WTFUCK_BOT_INACTIVIDAD_S', '600')) * 1000,
  log,
});

await bot.arrancar();

for (const senal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(senal, () => {
    log('Cerrando...');
    bot.cerrar();
    process.exit(0);
  });
}
