// @todos y la marca `mencionado` de cada entrega.
//
// Una mencion avisa aunque el grupo este silenciado. Por eso quien decide si
// un mensaje menciona a alguien es el SERVIDOR, y no el telefono leyendo el
// texto: si no, cualquiera saltaria el silencio de todos escribiendo "@todos".
//
// Lo que se comprueba:
//  - `@todos` lo acepta solo de quien puede fijar mensajes en el grupo;
//  - la entrega dice `mencionado` en vivo Y en la que espera en el buzon;
//  - una mencion normal marca solo a esa persona;
//  - en una directa `@todos` no significa nada;
//  - nadie puede registrarse como "todos".
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
async function reg(u) {
  const r = await call('POST', '/v1/registro', null, {
    username: 't' + S + u, password: 'clave-larga-123', etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-todos-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { t: r.b?.token, id: r.b?.usuarioId, user: 't' + S + u, s: r.s, b: r.b };
}
const abrir = (t) => new Promise((res, rej) => {
  const w = new WebSocket(BASE.replace('http', 'ws') + '/v1/ws?token=' + encodeURIComponent(t));
  w.recibidos = [];
  w.onmessage = (e) => w.recibidos.push(JSON.parse(e.data));
  w.onopen = () => res(w);
  w.onerror = rej;
});
const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

// Escribe `texto` en `conv` declarando sus menciones, como hace la app.
async function escribir(quien, ws, conv, texto) {
  const id = crypto.randomUUID();
  const menciones = [...texto.toLowerCase().matchAll(/@([a-z0-9_]{3,24})/g)].map((m) => m[1]);
  const r = await call('POST', '/v1/mensajes', quien.t, { mensajeId: id, conversacionId: conv, menciones });
  const ds = ((await call('GET', `/v1/conversaciones/${conv}/destinos`, quien.t)).b?.destinos) ?? [];
  ws.send(JSON.stringify({
    type: 'enviar', sobreId: id, conversacionId: conv, creadoEn: Date.now(),
    copias: [{ destinos: ds.map((d) => d.dispositivoId), cuerpo: b64(texto), tipo: 0 }],
  }));
  await esperar(900);
  return { id, registro: r.s };
}
const entregaDe = (ws, id) => ws.recibidos.find((x) => x.type === 'entrega' && x.mensajeId === id);

const duena = await reg('duena');
const ana = await reg('ana');
const beto = await reg('beto');

console.log('\n=== nadie puede llamarse "todos" ===');
let r = await call('POST', '/v1/registro', null, {
  username: 'todos', password: 'clave-larga-123', etiquetaDispositivo: 't',
  identidadPub: b64('ktodos' + S), hardwareHash: b64('HW-todos-reservado-' + S), hardwareNivel: 'SOFTWARE_DEV',
});
ck('registrarse como "todos" es 400', r.s === 400, String(r.s) + ' ' + JSON.stringify(r.b));
r = await call('POST', '/v1/registro', null, {
  username: 'TODOS', password: 'clave-larga-123', etiquetaDispositivo: 't',
  identidadPub: b64('ktodos2' + S), hardwareHash: b64('HW-todos-reservado2-' + S), hardwareNivel: 'SOFTWARE_DEV',
});
ck('tampoco en mayusculas', r.s === 400, String(r.s));

r = await call('POST', '/v1/conversaciones/grupo', duena.t, { nombre: 'Grupo todos ' + S, usernames: [ana.user, beto.user] });
const grupo = r.b?.conversacionId ?? r.b?.id;
ck('se crea el grupo', !!grupo, JSON.stringify(r.b).slice(0, 120));

const wsDuena = await abrir(duena.t);
const wsAna = await abrir(ana.t);
await esperar(400);
// Beto queda SIN socket: su entrega espera en el buzon.

console.log('\n=== @todos de quien puede fijar ===');
let m = await escribir(duena, wsDuena, grupo, 'reunion a las 5 @todos');
ck('el mensaje se registra', m.registro === 200, String(m.registro));
let e = entregaDe(wsAna, m.id);
ck('a ana le llega en vivo', !!e, JSON.stringify(wsAna.recibidos.slice(-3)).slice(0, 160));
ck('y marcado como mencion', e?.mencionado === true, JSON.stringify(e));

const wsBeto = await abrir(beto.t);
await esperar(1200);
e = entregaDe(wsBeto, m.id);
ck('a beto le llega del buzon al conectarse', !!e);
ck('tambien marcado como mencion', e?.mencionado === true, JSON.stringify(e));
ck('a quien lo escribio no le llega su propia mencion', !entregaDe(wsDuena, m.id)?.mencionado);

console.log('\n=== @todos de quien NO puede fijar ===');
m = await escribir(ana, wsAna, grupo, 'oigan @todos');
ck('el mensaje sale igual', m.registro === 200 && !!entregaDe(wsDuena, m.id), String(m.registro));
ck('pero a la duena no la marca', entregaDe(wsDuena, m.id)?.mencionado === false, JSON.stringify(entregaDe(wsDuena, m.id)));
ck('ni a beto', entregaDe(wsBeto, m.id)?.mencionado === false);

console.log('\n=== una mencion normal marca solo a esa persona ===');
m = await escribir(ana, wsAna, grupo, `@${beto.user} mira esto`);
ck('a beto si', entregaDe(wsBeto, m.id)?.mencionado === true, JSON.stringify(entregaDe(wsBeto, m.id)));
ck('a la duena no', entregaDe(wsDuena, m.id)?.mencionado === false);

console.log('\n=== en una directa @todos no significa nada ===');
r = await call('POST', '/v1/conversaciones/directa', duena.t, { usernameDestino: ana.user });
const directa = r.b?.id;
m = await escribir(duena, wsDuena, directa, 'hola @todos');
ck('llega sin marca de mencion', entregaDe(wsAna, m.id)?.mencionado === false, JSON.stringify(entregaDe(wsAna, m.id)));

for (const w of [wsDuena, wsAna, wsBeto]) w.close();
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
