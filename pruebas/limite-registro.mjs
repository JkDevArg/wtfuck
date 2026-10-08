// El limite de ritmo del registro (`POST /v1/registro`).
//
// ## El hueco
//
// La ruta no tenia ningun limite. Nivel, hash de hardware e identidad los
// declara el cliente -el servidor no verifica atestacion, ver
// docs/04-DEVICE-BINDING.md- asi que con el registro abierto, que es el
// defecto, un script inventa un hash por cuenta y crea las que quiera.
//
// ## Lo que se puso, y lo que esta suite comprueba
//
//   - Rafaga por red (`registro_red`, en memoria): corta la avalancha antes de
//     tocar la base.
//   - Cupo diario por red (`registro_red_dia`, en la base): el techo de verdad,
//     que un reinicio no perdona.
//   - "Red" = la IPv4, o el /64 de una IPv6.
//   - Los dos cuentan INTENTOS, tambien los 409: si no, la ruta seria un
//     oraculo gratis de usernames.
//   - Van ANTES de validar el cuerpo, y DESPUES de la puerta de invitacion.
//   - La IP sale de la ULTIMA entrada de X-Forwarded-For, y solo si la conexion
//     viene de un proxy propio. Antes era la primera, que la escribe el
//     cliente: cada intento estrenaba cupo, y se podia gastar el de otro.
//
// Los numeros por defecto son altos a proposito (ver `Limitador.REGISTRO_RED`:
// el NAT de un campus), asi que para verlos saltar se BAJAN desde el panel y
// se restauran al final, pase lo que pase.
//
// ## Como simula varias IPs desde localhost
//
// Desde 127.0.0.1 la conexion viene de "un proxy propio", y entonces cuenta la
// cabecera X-Forwarded-For. Es exactamente lo que hace Caddy delante del
// servidor. Las IPs son de rangos reservados para pruebas (198.18.0.0/15 y
// 2001:db8::/32) y al azar en cada corrida: el cupo diario vive en la base y
// una IP repetida empezaria la corrida siguiente con el cupo gastado.
//
// ## La seccion de invitacion necesita otra instancia
//
// El orden "puerta antes que limite" solo se ve con el registro cerrado:
//
//   WTFUCK_PUERTO=8302 WTFUCK_REGISTRO=invitacion  (ver invitaciones.mjs)
//   WTFUCK_BASE_INV=http://localhost:8302 node pruebas/limite-registro.mjs
//
// Si no esta, la seccion lo DICE y no cuenta como pasada.
//
// Uso:  node pruebas/limite-registro.mjs

import { execSync } from 'node:child_process';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const INV = process.env.WTFUCK_BASE_INV ?? 'http://localhost:8302';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const sql = (q) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -t -A -c "${q}"`,
  { encoding: 'utf8' },
).trim();

const azar = (n) => Math.floor(Math.random() * n);
const ipv4 = () => `198.${18 + azar(2)}.${azar(256)}.${1 + azar(254)}`;
const hex = () => azar(0x10000).toString(16);
/** Un /64 al azar dentro de 2001:db8::/32. Se le pega el resto de la direccion. */
const red64 = () => `2001:db8:${hex()}:${hex()}`;

let n = 0;
const nombre = (p) => `${p}${S}${n++}`;

/**
 * Un intento de registro "desde" `ip`.
 *
 * `cuerpo` permite romper el formulario a proposito. `xff` reemplaza la
 * cabecera entera, para las pruebas de suplantacion.
 */
async function reg(ip, { base = BASE, user = nombre('lr'), codigo = '', xff, cuerpo } = {}) {
  const r = await fetch(base + '/v1/registro', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': xff ?? ip },
    body: JSON.stringify(cuerpo ?? {
      username: user, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + user), hardwareHash: b64('HW-' + user),
      hardwareNivel: 'SOFTWARE_DEV', codigoInvitacion: codigo,
    }),
  });
  let b = null;
  try { b = await r.json(); } catch { /* sin cuerpo */ }
  return { s: r.status, b, user };
}

/** Varios intentos seguidos; devuelve los codigos en orden. */
async function varios(ip, veces, opciones = {}) {
  const r = [];
  for (let i = 0; i < veces; i++) r.push((await reg(ip, opciones)).s);
  return r;
}

async function pedir(metodo, ruta, token, cuerpo, base = BASE) {
  const r = await fetch(base + ruta, {
    method: metodo,
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  });
  let b = null;
  try { b = await r.json(); } catch { /* 204 */ }
  return { s: r.status, b };
}

const ajustar = (t, clave, tope, ventanaSegundos, base = BASE) =>
  pedir('PUT', `/v1/panel/limites/${clave}`, t, { tope, ventanaSegundos }, base);
const restaurar = (t, clave, base = BASE) => pedir('DELETE', `/v1/panel/limites/${clave}`, t, undefined, base);

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

// Desde su propia IP de prueba, para no gastar el cupo de localhost que usan
// todas las demas suites.
const alta = await reg(ipv4(), { user: nombre('ls') });
ck('se crea el administrador de la prueba', alta.s === 200, `${alta.s} ${JSON.stringify(alta.b)}`);
sql(`UPDATE usuario SET staff_nivel = 80 WHERE username = '${alta.user}'`);
const staff = alta.b?.token;

// Un username que ya existe, para los 409.
const tomado = (await reg(ipv4(), { user: nombre('lt') })).user;

const restaurables = [];
try {
  // -------------------------------------------------------------------------
  console.log('\n=== 1 · los dos limites estan en el panel ===');
  // -------------------------------------------------------------------------

  // Por el NAT: el dia que una institucion anuncia la app, toda su gente se
  // registra desde la misma salida a internet, y subir el tope ese dia no
  // puede esperar a un despliegue.
  const panel = await pedir('GET', '/v1/panel/limites', staff);
  const lim = Object.fromEntries((panel.b?.limites ?? []).map((x) => [x.clave, x]));
  ck('el panel lista la rafaga del registro', !!lim.registro_red, JSON.stringify(Object.keys(lim)));
  ck('y el cupo diario', !!lim.registro_red_dia, JSON.stringify(Object.keys(lim)));
  ck('la rafaga por defecto tolera un campus (>= 300 por minuto)',
     lim.registro_red?.topeDefecto >= 300 && lim.registro_red?.ventanaDefectoSegundos === 60,
     JSON.stringify(lim.registro_red));
  ck('el cupo por defecto es por dia',
     lim.registro_red_dia?.ventanaDefectoSegundos === 86400 && lim.registro_red_dia?.topeDefecto >= 1000,
     JSON.stringify(lim.registro_red_dia));

  // -------------------------------------------------------------------------
  console.log('\n=== 2 · la rafaga, por red ===');
  // -------------------------------------------------------------------------

  restaurables.push('registro_red');
  let r = await ajustar(staff, 'registro_red', 3, 60);
  ck('se baja la rafaga a 3 por minuto desde el panel', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

  const A = ipv4();
  const golpes = await varios(A, 5);
  ck('desde una red: 3 altas y despues 429', JSON.stringify(golpes) === '[200,200,200,429,429]',
     JSON.stringify(golpes));
  const r429 = await reg(A);
  ck('el 429 dice cuanto esperar', /\d+ segundos/.test(r429.b?.motivo ?? ''), JSON.stringify(r429.b));

  // El limite va ANTES de validar el cuerpo.
  r = await reg(A, { user: 'x' });
  ck('con la red agotada, un username invalido da 429 y no 400', r.s === 429, `${r.s} ${JSON.stringify(r.b)}`);
  r = await reg(A, { user: tomado });
  ck('y uno que ya existe da 429 y no 409: el oraculo tambien se agota', r.s === 429,
     `${r.s} ${JSON.stringify(r.b)}`);
  // Lo que NO pasa por el limite: un JSON que no se puede leer. Leerlo va
  // antes porque la puerta de invitacion necesita el codigo, y rechazarlo no
  // cuesta nada -ni Argon2 ni base- ni dice nada de nadie. Se deja escrito
  // para que nadie lo tome por un hueco.
  r = await reg(A, { cuerpo: { username: 'y' } });
  ck('un cuerpo que no se puede leer es 400 aun con la red agotada', r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);

  // Otra red no paga por la primera: el limite es por sitio, no global.
  r = await reg(ipv4());
  ck('otra red sigue registrandose', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

  // Los rechazos gastan: si un 409 fuera gratis, enumerar usernames tambien.
  const C = ipv4();
  const fallidos = await varios(C, 3, { user: tomado });
  ck('tres "ese usuario ya existe"', JSON.stringify(fallidos) === '[409,409,409]', JSON.stringify(fallidos));
  r = await reg(C);
  ck('gastan la rafaga: el alta valida que sigue da 429', r.s === 429, `${r.s} ${JSON.stringify(r.b)}`);

  // IPv6: por /64, no por direccion.
  const N = red64();
  const v6 = [];
  for (const sufijo of ['::1', '::2', '::3', '::4']) v6.push((await reg(N + sufijo)).s);
  ck('cuatro direcciones del mismo /64 comparten rafaga', JSON.stringify(v6) === '[200,200,200,429]',
     JSON.stringify(v6));
  r = await reg(red64() + '::1');
  ck('otro /64 no', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

  // X-Forwarded-For: cuenta la ULTIMA entrada, la que escribe nuestro proxy.
  r = await reg(A, { xff: `${ipv4()}, ${A}` });
  ck('anteponer una IP inventada no estrena cupo', r.s === 429, `${r.s} ${JSON.stringify(r.b)}`);

  // Y lo mas grave de la version anterior: gastar el cupo de OTRO poniendo su
  // IP delante. D es la "salida del campus"; quien abusa manda D en la cabecera
  // desde sus propias direcciones.
  const D = ipv4();
  const ajenos = [];
  for (let i = 0; i < 3; i++) ajenos.push((await reg(null, { xff: `${D}, ${ipv4()}` })).s);
  ck('tres altas que nombran a D por delante', JSON.stringify(ajenos) === '[200,200,200]',
     JSON.stringify(ajenos));
  const propios = await varios(D, 3);
  ck('no le gastaron nada a D: sus 3 altas pasan', JSON.stringify(propios) === '[200,200,200]',
     JSON.stringify(propios));

  r = await restaurar(staff, 'registro_red');
  restaurables.splice(restaurables.indexOf('registro_red'), 1);
  ck('se restaura la rafaga', r.s === 204 || r.s === 200, String(r.s));

  // -------------------------------------------------------------------------
  console.log('\n=== 3 · el cupo diario, en la base ===');
  // -------------------------------------------------------------------------

  // La rafaga queda por defecto (300/min): el que contesta aqui es el cupo.
  restaurables.push('registro_red_dia');
  r = await ajustar(staff, 'registro_red_dia', 2, 86400);
  ck('se baja el cupo diario a 2', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

  const F = ipv4();
  const dia = [];
  let ultimo = null;
  for (let i = 0; i < 3; i++) { ultimo = await reg(F); dia.push(ultimo.s); }
  ck('2 altas al dia desde una red, la tercera 429', JSON.stringify(dia) === '[200,200,429]', JSON.stringify(dia));
  ck('y el motivo dice que es el del dia y por red',
     /2 por dia desde esta red/.test(ultimo.b?.motivo ?? ''), JSON.stringify(ultimo.b));

  // Vive en la base, que es lo que hace que un reinicio no lo perdone.
  const contado = Number(sql(`SELECT coalesce(sum(n), 0) FROM contador_red WHERE red = '${F}' AND accion = 'registro'`));
  ck('el contador esta en la base y conto tambien el rechazado', contado === 3, String(contado));
  const evento = Number(sql(
    `SELECT count(*) FROM evento_seguridad WHERE tipo = 'limite_excedido' AND host(ip) = '${F}' ` +
    `AND detalle->>'accion' = 'registro'`,
  ));
  ck('y el exceso queda anotado con la IP', evento >= 1, String(evento));

  r = await reg(ipv4());
  ck('otra red tiene su propio cupo', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

  const H = ipv4();
  const conFallos = [(await reg(H, { user: tomado })).s, (await reg(H, { user: tomado })).s, (await reg(H)).s];
  ck('dos 409 gastan el cupo del dia: el alta valida da 429',
     JSON.stringify(conFallos) === '[409,409,429]', JSON.stringify(conFallos));

  r = await restaurar(staff, 'registro_red_dia');
  restaurables.splice(restaurables.indexOf('registro_red_dia'), 1);
  ck('se restaura el cupo diario', r.s === 204 || r.s === 200, String(r.s));

  // -------------------------------------------------------------------------
  console.log('\n=== 4 · la puerta de invitacion va ANTES del limite ===');
  // -------------------------------------------------------------------------

  let modo = null;
  try {
    const m = await fetch(INV + '/v1/registro/modo');
    modo = m.ok ? await m.json() : null;
  } catch { /* no hay instancia */ }

  if (modo?.requiereInvitacion !== true) {
    console.log(`  NO PROBADO: no hay un servidor con WTFUCK_REGISTRO=invitacion en ${INV}.`);
    console.log('  El orden puerta -> limite queda SIN comprobar en esta corrida.');
  } else {
    // El ajuste se hace en ESA instancia: el panel invalida la cache del
    // proceso que lo recibe, y los demas tardan hasta 30 segundos en verlo.
    restaurables.push('inv:registro_red');
    r = await ajustar(staff, 'registro_red', 2, 60, INV);
    ck('[inv] se baja la rafaga a 2', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

    const inv = await pedir('POST', '/v1/registro/invitaciones', staff, { usos: 5, diasValida: 1, nota: 'limite-registro' }, INV);
    ck('[inv] hay una invitacion con 5 usos', inv.s === 200 && !!inv.b?.codigo, `${inv.s} ${JSON.stringify(inv.b)}`);

    // I es la red del campus. Alguien desde ahi prueba sin invitacion.
    const I = ipv4();
    const sinCodigo = await varios(I, 3, { base: INV });
    const inventado = await varios(I, 3, { base: INV, codigo: 'ABCDEFGHJKMN' });
    ck('[inv] sin codigo: 400, nunca 429', sinCodigo.every((x) => x === 400), JSON.stringify(sinCodigo));
    ck('[inv] con uno inventado: 403, nunca 429', inventado.every((x) => x === 403), JSON.stringify(inventado));

    // Seis rechazos con un tope de 2. Si hubieran gastado, la primera alta
    // legitima ya daria 429.
    const legit = await varios(I, 3, { base: INV, codigo: inv.b?.codigo ?? '' });
    ck('[inv] los rechazados no gastaron: 2 altas con invitacion y despues 429',
       JSON.stringify(legit) === '[200,200,429]', JSON.stringify(legit));

    r = await restaurar(staff, 'registro_red', INV);
    restaurables.splice(restaurables.indexOf('inv:registro_red'), 1);
    ck('[inv] se restaura la rafaga', r.s === 204 || r.s === 200, String(r.s));
  }
} finally {
  // Un limite bajado que se queda bajado tumba las suites que vienen detras
  // con un 429 que no tiene nada que ver con ellas.
  for (const clave of restaurables) {
    const [base, k] = clave.startsWith('inv:') ? [INV, clave.slice(4)] : [BASE, clave];
    await restaurar(staff, k, base).catch(() => {});
    console.log(`  (restaurado ${k} en ${base} tras un fallo)`);
  }
}

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
