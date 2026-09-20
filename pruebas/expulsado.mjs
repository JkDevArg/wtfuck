// Reproduce el caso reportado: a alguien lo sacan del grupo y escribe.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const uuid = () => crypto.randomUUID();

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const post = (r, t, b) => call('POST', r, t, b);

const jefe = await reg('ej');
const pobre = await reg('ep');

const g = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Del que sacan', usernames: [pobre.user] });
const G = g.b.id;
// Un segundo grupo donde SI sigue estando: sirve para probar que un rechazo no
// bloquea los mensajes de otras conversaciones.
const g2 = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Donde sigue', usernames: [pobre.user] });
const G2 = g2.b.id;

console.log('\n=== antes de expulsarlo ===');
let r = await post('/v1/mensajes', pobre.t, { mensajeId: uuid(), conversacionId: G });
ck('puede escribir mientras es miembro', r.s === 200, String(r.s));

console.log('\n=== lo expulsan ===');
r = await post(`/v1/conversaciones/${G}/miembros/${pobre.id}/expulsar`, jefe.t, { motivo: 'prueba' });
ck('el propietario lo expulsa', r.s === 204, String(r.s));

r = await post('/v1/mensajes', pobre.t, { mensajeId: uuid(), conversacionId: G });
ck('al escribir recibe un rechazo definitivo (4xx, no 5xx ni timeout)', r.s >= 400 && r.s < 500, String(r.s));
ck('y el motivo explica que ya no pertenece', /no pertenece|formas parte/i.test(r.b?.motivo || ''), JSON.stringify(r.b));

console.log('\n=== el rechazo no debe contagiar a otros chats ===');
r = await post('/v1/mensajes', pobre.t, { mensajeId: uuid(), conversacionId: G2 });
ck('sigue pudiendo escribir en el grupo donde si esta', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== y tampoco por el socket ===');
const ws = await new Promise((res, rej) => {
  const w = new WebSocket(`ws://localhost:8300/v1/ws?token=${encodeURIComponent(pobre.t)}`);
  w.recibidos = [];
  w.onmessage = (e) => w.recibidos.push(JSON.parse(e.data));
  w.onopen = () => res(w);
  w.onerror = rej;
});
await new Promise((r) => setTimeout(r, 500));
const mid = uuid();
// A quien lo expulsaron ya no obtiene destinos: se manda una copia inventada
// para comprobar que el rechazo NO depende de que el cliente colabore.
ws.send(JSON.stringify({
  type: 'enviar', sobreId: mid, conversacionId: G, creadoEn: Date.now(),
  copias: [{ destinos: [jefe.id], cuerpo: b64('hola'), tipo: 0 }],
}));
await new Promise((r) => setTimeout(r, 1200));
const err = ws.recibidos.find((x) => x.type === 'error');
ck('el socket tambien lo rechaza con un motivo', !!err && /no pertenece/i.test(err.motivo || ''), JSON.stringify(err));
ws.close();

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
