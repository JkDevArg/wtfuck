// "Ver una vez" abierto (V47): el aviso a quien lo mando y a mis aparatos.
//
// Lo que se comprueba:
//  - al abrirlo, a quien lo mando le llega `una_vez_abierta` con el id;
//  - a mi otro aparato tambien, para que lo cierre;
//  - si quien lo abre no comparte confirmaciones, al autor no le llega, pero
//    a mis aparatos si;
//  - quien lo escribio no puede "abrir" el suyo, y alguien de afuera tampoco.
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
const put = (r, t, b) => call('PUT', r, t, b);
const juego = (n) => ({
  registrationId: 6000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1568)), firma: b64(randomBytes(64)) },
  unicas: Array.from({ length: 4 }, (_, i) => ({ keyId: 100 + i, publica: b64(randomBytes(33)) })),
});
let n = 0;
async function reg(u) {
  const r = await post('/v1/registro', null, {
    username: 'u' + S + u, password: CLAVE, etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-unavez-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  const q = { t: r.b?.token, id: r.b?.usuarioId, dev: r.b?.dispositivoId, user: 'u' + S + u };
  await put('/v1/claves', q.t, juego(++n));
  return q;
}
const abrir = (t) => new Promise((res, rej) => {
  const w = new WebSocket(BASE.replace('http', 'ws') + '/v1/ws?token=' + encodeURIComponent(t));
  w.recibidos = [];
  w.onmessage = (e) => w.recibidos.push(JSON.parse(e.data));
  w.onopen = () => res(w);
  w.onerror = rej;
});
const esperar = (ms) => new Promise((r) => setTimeout(r, ms));
const aviso = (ws, id) => ws.recibidos.find((x) => x.type === 'evento' && x.tipo === 'una_vez_abierta' && x.detalle === id);

const ana = await reg('ana');
const beto = await reg('beto');
const carla = await reg('carla');

// Beto tiene un segundo aparato.
let r = await post('/v1/dispositivos/codigo', beto.t, { password: CLAVE });
r = await post('/v1/dispositivos/vincular', null, {
  username: beto.user, codigo: r.b?.codigo, etiquetaDispositivo: 'tablet',
  identidadPub: b64('k-beto2'), hardwareHash: b64('HW-unavez-beto2-' + S), hardwareNivel: 'SOFTWARE_DEV',
});
const beto2 = { t: r.b?.token };
ck('Beto vincula una tablet', !!beto2.t, String(r.s));

r = await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user });
const D = r.b?.id;
const wsA = await abrir(ana.t), wsB2 = await abrir(beto2.t), wsC = await abrir(carla.t);

console.log('\n=== Beto lo abre ===');
const m1 = crypto.randomUUID();
await post('/v1/mensajes', ana.t, { mensajeId: m1, conversacionId: D, menciones: [] });
r = await post(`/v1/mensajes/${m1}/abierto`, beto.t);
ck('avisar que se abrio responde 204', r.s === 204, String(r.s) + JSON.stringify(r.b));
await esperar(800);
ck('a Ana le llega el aviso con el id', !!aviso(wsA, m1), JSON.stringify(wsA.recibidos.map((x) => x.tipo ?? x.type)));
ck('dice quien lo abrio', aviso(wsA, m1)?.actor === beto.user);
ck('a la tablet de Beto tambien: la cierra', !!aviso(wsB2, m1));
ck('a Carla, que no esta en el chat, nada', !aviso(wsC, m1));

console.log('\n=== reciprocidad ===');
r = await get('/v1/perfil/privacidad', beto.t);
await put('/v1/perfil/privacidad', beto.t, { ...r.b, lectura: false });
const m2 = crypto.randomUUID();
await post('/v1/mensajes', ana.t, { mensajeId: m2, conversacionId: D, menciones: [] });
await post(`/v1/mensajes/${m2}/abierto`, beto.t);
await esperar(800);
ck('sin confirmaciones, Ana no se entera', !aviso(wsA, m2));
ck('pero la tablet de Beto si: tiene que cerrarlo igual', !!aviso(wsB2, m2));

console.log('\n=== quien no puede ===');
r = await post(`/v1/mensajes/${m1}/abierto`, ana.t);
ck('la autora no "abre" el suyo', r.s === 400, String(r.s));
r = await post(`/v1/mensajes/${m1}/abierto`, carla.t);
ck('alguien de afuera no puede', r.s === 403 || r.s === 404, String(r.s));
r = await post(`/v1/mensajes/${crypto.randomUUID()}/abierto`, beto.t);
ck('un mensaje que no existe: 404', r.s === 404, String(r.s));

for (const w of [wsA, wsB2, wsC]) w.close();
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
