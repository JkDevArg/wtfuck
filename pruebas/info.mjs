// "Info del mensaje" en grupos (V46): a quien le llego y quien lo leyo.
//
// Lo que se comprueba:
//  - acusar un sobre de grupo anota la entrega, y leer anota la lectura;
//  - quien no comparte confirmaciones sale como entregado, nunca como leido;
//  - si quien pregunta las apago, no ve ninguna lectura (son reciprocas);
//  - solo lo pide quien lo escribio: a cualquier otro, 404;
//  - en una directa no se guarda la entrega y la info no existe;
//  - quien entro al grupo despues del mensaje no cuenta como pendiente.
import { execSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
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
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);
const psql = (sql) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`, { stdio: 'pipe' },
).toString().trim();
const juego = (n) => ({
  registrationId: 5000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1568)), firma: b64(randomBytes(64)) },
  unicas: Array.from({ length: 4 }, (_, i) => ({ keyId: 100 + i, publica: b64(randomBytes(33)) })),
});
let n = 0;
async function reg(u) {
  const r = await post('/v1/registro', null, {
    username: 'i' + S + u, password: 'clave-larga-123', etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-info-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  const q = { t: r.b?.token, id: r.b?.usuarioId, dev: r.b?.dispositivoId, user: 'i' + S + u };
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

async function mandar(quien, ws, conv, texto) {
  const id = crypto.randomUUID();
  await post('/v1/mensajes', quien.t, { mensajeId: id, conversacionId: conv, menciones: [] });
  const ds = ((await get(`/v1/conversaciones/${conv}/destinos`, quien.t)).b?.destinos) ?? [];
  ws.send(JSON.stringify({
    type: 'enviar', sobreId: id, conversacionId: conv, creadoEn: Date.now(),
    copias: [{ destinos: ds.map((d) => d.dispositivoId), cuerpo: b64(texto), tipo: 0 }],
  }));
  await esperar(900);
  return id;
}
const sobreDe = (ws, mensajeId) => ws.recibidos.find((x) => x.type === 'entrega' && x.mensajeId === mensajeId)?.sobreId;
const acusar = async (ws, mensajeId) => { ws.send(JSON.stringify({ type: 'acuse', sobreIds: [sobreDe(ws, mensajeId)] })); await esperar(500); };
const leer = async (ws, conv, mensajeId) => { ws.send(JSON.stringify({ type: 'acuse_lectura', conversacionId: conv, mensajeIds: [mensajeId] })); await esperar(500); };
const de = (info, q) => info.miembros.find((m) => m.username === q.user);

const duena = await reg('duena');
const ana = await reg('ana');
const beto = await reg('beto');
const carla = await reg('carla');

// Beto no comparte confirmaciones de lectura.
let r = await get('/v1/perfil/privacidad', beto.t);
await put('/v1/perfil/privacidad', beto.t, { ...r.b, lectura: false });

r = await post('/v1/conversaciones/grupo', duena.t, { nombre: 'Info ' + S, usernames: [ana.user, beto.user] });
const G = r.b?.id;
ck('se crea el grupo', !!G, JSON.stringify(r.b).slice(0, 120));

const wsD = await abrir(duena.t), wsA = await abrir(ana.t), wsB = await abrir(beto.t);

console.log('\n=== recien mandado: nadie ===');
const m1 = await mandar(duena, wsD, G, 'hola grupo');
r = await get(`/v1/mensajes/${m1}/info`, duena.t);
ck('la autora puede pedir la info', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('salen los dos miembros, sin ella', r.b?.miembros?.length === 2, JSON.stringify(r.b?.miembros));
ck('y sin entregas todavia', r.b?.miembros?.every((m) => m.entregadoEn == null && m.leidoEn == null));

console.log('\n=== acusar es "entregado" ===');
await acusar(wsA, m1);
r = await get(`/v1/mensajes/${m1}/info`, duena.t);
ck('Ana: entregado', de(r.b, ana)?.entregadoEn > 0, JSON.stringify(de(r.b, ana)));
ck('Ana: todavia no leido', de(r.b, ana)?.leidoEn == null);
ck('Beto: todavia nada', de(r.b, beto)?.entregadoEn == null);

console.log('\n=== leer es "leido" ===');
await leer(wsA, G, m1);
r = await get(`/v1/mensajes/${m1}/info`, duena.t);
ck('Ana: leido', de(r.b, ana)?.leidoEn > 0, JSON.stringify(de(r.b, ana)));
ck('Ana sale primero: los que leyeron arriba', r.b?.miembros?.[0]?.username === ana.user);

console.log('\n=== quien no comparte lecturas ===');
await acusar(wsB, m1);
await leer(wsB, G, m1);
r = await get(`/v1/mensajes/${m1}/info`, duena.t);
ck('Beto: entregado', de(r.b, beto)?.entregadoEn > 0, JSON.stringify(de(r.b, beto)));
ck('Beto: nunca leido, aunque leyo', de(r.b, beto)?.leidoEn == null);
ck('y su lectura ni se guardo',
   psql(`SELECT count(*) FROM lectura WHERE mensaje_id = '${m1}' AND usuario_id = '${beto.id}'`) === '0');

console.log('\n=== solo la autora ===');
r = await get(`/v1/mensajes/${m1}/info`, ana.t);
ck('a otro miembro: 404', r.s === 404, String(r.s));
r = await get(`/v1/mensajes/${m1}/info`, carla.t);
ck('a alguien de afuera: 404', r.s === 404, String(r.s));
r = await get(`/v1/mensajes/${crypto.randomUUID()}/info`, duena.t);
ck('un mensaje que no existe: 404', r.s === 404, String(r.s));

console.log('\n=== quien entro despues no cuenta ===');
await esperar(1100);
r = await post(`/v1/conversaciones/${G}/miembros`, duena.t, { usernames: [carla.user] });
ck('Carla entra al grupo', r.s === 200, String(r.s));
r = await get(`/v1/mensajes/${m1}/info`, duena.t);
ck('y no aparece en la info del mensaje viejo', !de(r.b, carla), JSON.stringify(r.b?.miembros));

console.log('\n=== reciprocidad: si la autora las apaga ===');
r = await get('/v1/perfil/privacidad', duena.t);
await put('/v1/perfil/privacidad', duena.t, { ...r.b, lectura: false });
r = await get(`/v1/mensajes/${m1}/info`, duena.t);
ck('ya no ve ninguna lectura', r.b?.miembros?.every((m) => m.leidoEn == null), JSON.stringify(r.b?.miembros));
ck('y la respuesta lo dice', r.b?.lecturasVisibles === false);
ck('las entregas si', de(r.b, ana)?.entregadoEn > 0);
r = await get('/v1/perfil/privacidad', duena.t);
await put('/v1/perfil/privacidad', duena.t, { ...r.b, lectura: true });

console.log('\n=== en una directa no ===');
r = await post('/v1/conversaciones/directa', duena.t, { usernameDestino: ana.user });
const D = r.b?.id;
const m2 = await mandar(duena, wsD, D, 'hola a solas');
await acusar(wsA, m2);
ck('acusar en una directa no guarda la entrega',
   psql(`SELECT count(*) FROM entrega WHERE mensaje_id = '${m2}'`) === '0');
r = await get(`/v1/mensajes/${m2}/info`, duena.t);
ck('y la info no existe', r.s === 404, String(r.s));

console.log('\n=== lo propio no es una entrega ===');
ck('ninguna fila de la autora',
   psql(`SELECT count(*) FROM entrega WHERE usuario_id = '${duena.id}'`) === '0');

for (const w of [wsD, wsA, wsB]) w.close();
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
