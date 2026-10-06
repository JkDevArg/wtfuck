// Corre todas las suites de integracion contra un servidor levantado.
//
// ## Por que las pruebas de integracion no estan en JUnit
//
// Porque lo que comprueban es el CONTRATO visto desde afuera: rutas, codigos de
// estado, cuerpos JSON y el orden en que el servidor autoriza. Una prueba que
// llama a `Autz.exigir` directamente puede pasar con la ruta mal cableada, y eso
// ya paso en este proyecto mas de una vez: el caso de los limites configurables
// -que se guardaban bien y no se aplicaban- lo encontro una prueba que pedia por
// la ruta real, no la que llamaba a la funcion.
//
// Lo que si esta en JUnit es el motor de autorizacion y la criptografia, donde
// importa el camino interno y no la respuesta HTTP.
//
// ## Uso
//
//   node pruebas/correr.mjs                # todas
//   node pruebas/correr.mjs mensajes l1    # solo esas
//
// El servidor tiene que estar escuchando en localhost:8300 con una base de
// datos accesible. Ver `arrancar-servidor.ps1` y docs/09-DESPLIEGUE.md.


import { readdirSync } from 'node:fs';
import { execSync, spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';

// Los limites de ritmo se borran antes de empezar.
//
// Desde que los limites de FALLO viven en Redis (modulo AP) sobreviven al
// reinicio del servidor — que es justamente el punto— y tambien entre una
// corrida de las pruebas y la siguiente. Varias suites fallan ingresos a
// proposito, todas desde la misma IP, y sin esto la segunda corrida dentro de
// la misma ventana empieza con el cupo gastado y falla por el motivo
// equivocado.
//
// Se borra solo `wtfuck:lim:*`. Las sesiones y la presencia no se tocan.
function limpiarLimites() {
  try {
    execSync(
      'docker exec wtfuck_redis sh -c "redis-cli --scan --pattern \'wtfuck:lim:*\' | ' +
      'xargs -r redis-cli del"',
      { stdio: 'ignore' },
    );
  } catch {
    // Sin Redis no hay nada que limpiar.
  }
  // OJO: esto borra los limites de FALLO, que son los que viven en Redis. Los
  // de FRECUENCIA -el del SMS, por ejemplo- viven en la memoria del servidor y
  // de aqui no se alcanzan: dos corridas completas dentro de su ventana hacen
  // caer a `recuperacion`. Ver el aviso del final.
}
limpiarLimites();

const pedidas = process.argv.slice(2).map((s) => s.replace(/\.mjs$/, ''));
// `correr.mjs` es este archivo y los `stub-*` son ayudantes que se levantan a
// mano, no suites. Que el runner intentara correr el stub y lo marcara ROTA fue
// su primera utilidad real.
const esSuite = (f) =>
  f.endsWith('.mjs') && f !== 'correr.mjs' && !f.startsWith('stub-');

const suites = readdirSync(AQUI)
  .filter(esSuite)
  .map((f) => f.replace(/\.mjs$/, ''))
  .filter((n) => pedidas.length === 0 || pedidas.includes(n))
  .sort();

if (suites.length === 0) {
  console.error('Ninguna suite coincide. Disponibles:');
  console.error(
    readdirSync(AQUI).filter(esSuite).join(' '),
  );
  process.exit(2);
}

// El servidor se comprueba UNA vez y antes de nada: veinte suites fallando por
// "connection refused" es veinte veces el mismo error y ninguna pista.
try {
  const r = await fetch(`${BASE}/salud`);
  if (!r.ok) throw new Error(`respondio ${r.status}`);
} catch (e) {
  console.error(`\nNo hay servidor en ${BASE} (${e.message}).`);
  console.error('Levantalo con pruebas/arrancar-servidor.ps1 y volve a correr esto.\n');
  process.exit(2);
}

const correr = (nombre) =>
  new Promise((resolve) => {
    const hijo = spawn(process.execPath, [join(AQUI, `${nombre}.mjs`)], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, WTFUCK_BASE: BASE },
    });
    let salida = '';
    hijo.stdout.on('data', (d) => { salida += d; });
    hijo.stderr.on('data', (d) => { salida += d; });
    hijo.on('close', (codigo) => resolve({ nombre, codigo, salida }));
  });

// En serie y no en paralelo: varias suites comparten limitadores de frecuencia
// por IP, y corriendolas juntas se limitan entre ellas y fallan por algo que no
// es lo que estaban probando.
let pasan = 0;
let fallan = 0;
const rotas = [];
const omitidas = [];

for (const s of suites) {
  const r = await correr(s);
  // Las suites se escribieron en dos epocas y tienen dos formatos de resumen.
  // Se aceptan los dos, y si no aparece NINGUNO la suite se trata como rota
  // aunque haya salido con codigo 0: una suite que muere a mitad sin fallar
  // ninguna asercion es exactamente el falso verde que hay que evitar.
  const m = r.salida.match(/=== (\d+) pasan, (\d+) fallan ===/) ??
    r.salida.match(/PASAN: (\d+)\s+FALLAN: (\d+)/);
  const ok = m ? Number(m[1]) : 0;
  const mal = m ? Number(m[2]) : 0;
  pasan += ok;
  fallan += mal;

  if (!m) {
    rotas.push({ ...r, salida: `Sin linea de resumen: la suite no llego al final.
${r.salida}` });
    console.log(`  ROTA  ${s.padEnd(16)} sin resumen`);
    continue;
  }

  if (r.codigo === 0 && mal === 0 && ok === 0) {
    // Una suite que sale bien sin haber comprobado nada se OMITIO: le faltaba
    // algo del entorno -un stub, una segunda instancia- y lo dijo. Marcarla
    // "OK" seria la misma forma de falso verde que este runner vino a evitar,
    // un nivel mas arriba.
    omitidas.push(s);
    console.log(`  OMIT  ${s.padEnd(16)} le falta algo del entorno`);
  } else if (r.codigo === 0 && mal === 0) {
    console.log(`  OK    ${s.padEnd(16)} ${ok} pruebas`);
  } else {
    rotas.push(r);
    console.log(`  FALLA ${s.padEnd(16)} ${ok} pasan, ${mal || '?'} fallan`);
  }
}

// El detalle de lo que fallo va AL FINAL y entero: buscarlo entre la salida de
// veinte suites es lo que hace que nadie lea la salida de las pruebas.
for (const r of rotas) {
  console.log(`\n${'='.repeat(60)}\n  ${r.nombre}\n${'='.repeat(60)}`);
  console.log(r.salida.split('\n').filter((l) => !l.startsWith('  PASA')).join('\n'));
}

const cuantas = suites.length - omitidas.length;
console.log(
  `\n=== ${cuantas} suites \u00b7 ${pasan} pasan, ${fallan} fallan` +
  // Las rotas van en la linea final aunque no sumen "fallan": una suite que
  // murio a mitad no conto sus fallos, y un "0 fallan" con una rota encima es
  // el resumen que todo el mundo lee y nadie cuestiona.
  (rotas.length ? ` \u00b7 ${rotas.length} con problemas (${rotas.map((r) => r.nombre).join(', ')})` : '') +
  (omitidas.length ? ` \u00b7 ${omitidas.length} omitidas (${omitidas.join(', ')})` : '') +
  ' ==='
);
if (rotas.length) {
  // Paso de verdad: dos corridas completas seguidas y `recuperacion` cayo en
  // 400 y null sin decir por que. El limitador de FRECUENCIA (el del SMS, por
  // ejemplo) vive en la memoria del servidor, no en Redis: `limpiarLimites`
  // no lo toca, y solo se vacia reiniciando el servidor o esperando su ventana.
  console.log(
    'Si algo fallo por un 429 ("Vas muy rapido"), el limitador de frecuencia ' +
    'del servidor guarda la corrida anterior en memoria: reinicia el servidor o espera unos minutos.'
  );
}
process.exit(fallan === 0 && rotas.length === 0 ? 0 : 1);
