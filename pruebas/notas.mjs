// "Nota para mi": la conversacion de una sola persona (V45).
//
// Lo que se comprueba:
//  - el POST la crea una vez y despues devuelve la misma, aunque se pida
//    muchas veces a la vez;
//  - aparece en la lista de su duena y en la de nadie mas;
//  - los destinos son los OTROS aparatos de la misma persona, y lo que se
//    apunta en uno le llega al otro;
//  - una copia dirigida a un tercero se descarta: no es un canal para meter
//    bytes en el buzon de nadie;
//  - nadie ajeno la puede leer ni escribir;
//  - no se le puede agregar gente, invitar, crear roles ni llamar;
//  - el temporizador se puede poner, como en una directa;
//  - no se puede denunciar como si fuera un grupo.
import { execSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
const CLAVE = 'clave-larga-123';
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
async function call(m, ruta, t, body) {
  const r = await fetch(BASE + ruta, { method: m, headers: t ? H(t) : { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);
const psql = (sql) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`, { stdio: 'pipe' },
).toString().trim();
const juego = (n) => ({
  registrationId: 4000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1568)), firma: b64(randomBytes(64)) },
  unicas: Array.from({ length: 4 }, (_, i) => ({ keyId: 100 + i, publica: b64(randomBytes(33)) })),
});
async function reg(u) {
  const r = await post('/v1/registro', null, {
    username: 'n' + S + u, password: CLAVE, etiquetaDispositivo: 'principal',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-notas-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { t: r.b?.token, id: r.b?.usuarioId, dev: r.b?.dispositivoId, user: 'n' + S + u };
}
const abrir = (t) => new Promise((res, rej) => {
  const w = new WebSocket(BASE.replace('http', 'ws') + '/v1/ws?token=' + encodeURIComponent(t));
  w.recibidos = [];
  w.onmessage = (e) => w.recibidos.push(JSON.parse(e.data));
  w.onopen = () => res(w);
  w.onerror = rej;
});
const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

const ana = await reg('ana');
const beto = await reg('beto');
await call('PUT', '/v1/claves', ana.t, juego(1));
await call('PUT', '/v1/claves', beto.t, juego(2));

console.log('\n=== se crea una sola vez ===');
let r = await post('/v1/conversaciones/notas', ana.t);
ck('el POST la crea', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 160));
const N = r.b?.id;
ck('con tipo notas', r.b?.tipo === 'notas', r.b?.tipo);
ck('y se llama "Nota para mí"', r.b?.nombre === 'Nota para mí', r.b?.nombre);
ck('sin nadie mas adentro', Array.isArray(r.b?.participantes) && r.b.participantes.length === 0,
   JSON.stringify(r.b?.participantes));
ck('con el rol mas bajo que escribe: miembro', r.b?.miRol === 'miembro', r.b?.miRol);
r = await post('/v1/conversaciones/notas', ana.t);
ck('pedirla otra vez devuelve LA MISMA', r.b?.id === N, r.b?.id + ' vs ' + N);

const varias = await Promise.all(Array.from({ length: 6 }, () => post('/v1/conversaciones/notas', beto.t)));
const ids = new Set(varias.map((x) => x.b?.id));
ck('seis pedidos a la vez: todos 200', varias.every((x) => x.s === 200), varias.map((x) => x.s).join(','));
ck('y todos con el mismo id', ids.size === 1, [...ids].join(','));
ck('en la base hay UNA por persona',
   psql(`SELECT count(*) FROM conversacion WHERE clave_directa = 'notas:${beto.id}'`) === '1');
const NB = [...ids][0];
ck('la de cada persona es distinta', NB !== N);

console.log('\n=== la lista ===');
r = await get('/v1/conversaciones', ana.t);
ck('aparece en la lista de su duena, una vez', r.b.filter((c) => c.id === N).length === 1);
r = await get('/v1/conversaciones', beto.t);
ck('y no en la de nadie mas', !r.b.some((c) => c.id === N));

console.log('\n=== un solo aparato: se guarda sin mandar copias ===');
r = await get(`/v1/conversaciones/${N}/destinos`, ana.t);
ck('sin otros aparatos no hay destinos', r.s === 200 && r.b.destinos.length === 0, JSON.stringify(r.b).slice(0, 120));
const wsA = await abrir(ana.t);
const m1 = crypto.randomUUID();
r = await post('/v1/mensajes', ana.t, { mensajeId: m1, conversacionId: N, menciones: [] });
ck('se registra un mensaje', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
wsA.send(JSON.stringify({ type: 'enviar', sobreId: m1, conversacionId: N, creadoEn: Date.now(), copias: [] }));
await esperar(700);
const ac1 = wsA.recibidos.find((x) => x.type === 'aceptado' && x.sobreId === m1);
ck('y el envio sin copias se acepta, sin faltantes', ac1 && ac1.sinCopia.length === 0, JSON.stringify(ac1));

console.log('\n=== dos aparatos: lo de uno le llega al otro ===');
r = await post('/v1/dispositivos/codigo', ana.t, { password: CLAVE });
r = await post('/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: r.b?.codigo, etiquetaDispositivo: 'tablet',
  identidadPub: b64('k-ana2'), hardwareHash: b64('HW-notas-ana2-' + S), hardwareNivel: 'SOFTWARE_DEV',
});
ck('se vincula una tablet', r.s === 200, String(r.s));
const ana2 = { t: r.b?.token, dev: r.b?.dispositivoId };
await call('PUT', '/v1/claves', ana2.t, juego(3));
r = await post('/v1/conversaciones/notas', ana2.t);
ck('la tablet recibe la MISMA nota', r.b?.id === N, r.b?.id);
r = await get('/v1/conversaciones', ana2.t);
ck('y la ve en su lista', r.b.some((c) => c.id === N));

r = await get(`/v1/conversaciones/${N}/destinos`, ana.t);
ck('desde el telefono, el destino es la tablet y nada mas',
   r.b.destinos.length === 1 && r.b.destinos[0].dispositivoId === ana2.dev, JSON.stringify(r.b.destinos));
r = await get(`/v1/conversaciones/${N}/destinos`, ana2.t);
ck('desde la tablet, el telefono', r.b.destinos.length === 1 && r.b.destinos[0].dispositivoId === ana.dev);

const wsA2 = await abrir(ana2.t);
const wsB = await abrir(beto.t);
const m2 = crypto.randomUUID();
await post('/v1/mensajes', ana.t, { mensajeId: m2, conversacionId: N, menciones: [] });
wsA.send(JSON.stringify({
  type: 'enviar', sobreId: m2, conversacionId: N, creadoEn: Date.now(),
  // La segunda copia apunta a Beto: el servidor la tiene que tirar.
  copias: [
    { destinos: [ana2.dev], cuerpo: b64('lista del super'), tipo: 0 },
    { destinos: [beto.dev], cuerpo: b64('colado'), tipo: 0 },
  ],
}));
await esperar(1200);
const e2 = wsA2.recibidos.find((x) => x.type === 'entrega' && x.mensajeId === m2);
ck('la tablet recibe la nota', !!e2, JSON.stringify(wsA2.recibidos.map((x) => x.type)));
ck('con el cuerpo tal cual (el servidor no lo toca)', e2 && Buffer.from(e2.cuerpo, 'base64').toString() === 'lista del super');
ck('Beto no recibe nada, aunque la copia lo nombraba',
   !wsB.recibidos.some((x) => x.type === 'entrega' && x.mensajeId === m2), JSON.stringify(wsB.recibidos.map((x) => x.type)));
ck('ni le queda en el buzon',
   psql(`SELECT count(*) FROM sobre_pendiente WHERE destino_dispositivo = '${beto.dev}' AND conversacion_id = '${N}'`) === '0');

console.log('\n=== nadie ajeno entra ===');
r = await get(`/v1/conversaciones/${N}/destinos`, beto.t);
ck('Beto no puede pedir sus destinos', r.s === 403 || r.s === 404, String(r.s));
r = await post('/v1/mensajes', beto.t, { mensajeId: crypto.randomUUID(), conversacionId: N, menciones: [] });
ck('ni registrar un mensaje ahi', r.s === 403 || r.s === 404, String(r.s));
const m3 = crypto.randomUUID();
wsB.send(JSON.stringify({
  type: 'enviar', sobreId: m3, conversacionId: N, creadoEn: Date.now(),
  copias: [{ destinos: [ana.dev, ana2.dev], cuerpo: b64('intruso'), tipo: 0 }],
}));
await esperar(700);
ck('ni mandar sobres por el socket', wsB.recibidos.some((x) => x.type === 'error' && x.sobreId === m3)
   && !wsA2.recibidos.some((x) => x.type === 'entrega' && x.mensajeId === m3), JSON.stringify(wsB.recibidos.slice(-2)));

console.log('\n=== no es un grupo ===');
r = await post(`/v1/conversaciones/${N}/miembros`, ana.t, { usernames: [beto.user] });
ck('no se puede agregar a nadie', r.s === 403, String(r.s));
ck('y Beto sigue afuera', !(await get('/v1/conversaciones', beto.t)).b.some((c) => c.id === N));
r = await post(`/v1/conversaciones/${N}/invitaciones`, ana.t, { horas: 0, usosMax: 0 });
ck('ni crear un enlace de invitacion', r.s === 403, String(r.s));
r = await post(`/v1/conversaciones/${N}/roles`, ana.t, { clave: 'x' + S, nombre: 'X', jerarquia: 20, permisos: [] });
ck('ni crear roles', r.s === 403, String(r.s));
r = await post('/v1/llamadas', ana.t, { conversacionId: N, conVideo: false });
ck('ni llamar: no hay a quien', r.s === 409, String(r.s) + JSON.stringify(r.b).slice(0, 80));

console.log('\n=== lo que si ===');
r = await call('PUT', `/v1/conversaciones/${N}/temporales`, ana.t, { segundos: 3600 });
ck('se le puede poner temporizador, como a una directa', r.s === 204 || r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 100));
r = await call('PUT', `/v1/conversaciones/${N}/temporales`, beto.t, { segundos: 60 });
ck('pero no alguien de afuera', r.s === 403 || r.s === 404, String(r.s));

console.log('\n=== moderacion ===');
r = await post('/v1/moderacion/denuncias', beto.t, { tipo: 'grupo', objetivoConversacion: N, motivo: 'spam' });
ck('no se denuncia como grupo: no lo es', r.s === 404, String(r.s));

console.log('\n=== la nota no abre la puerta a una directa con uno mismo ===');
r = await post('/v1/conversaciones/directa', ana.t, { usernameDestino: ana.user });
ck('una directa consigo misma sigue rechazada', r.s === 400, String(r.s));

for (const w of [wsA, wsA2, wsB]) w.close();
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
