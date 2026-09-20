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
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);

const jefe = await reg('cj');
const mod = await reg('cm');
const socio = await reg('cs');
const otro = await reg('co');

const g = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Chat C', usernames: [mod.user, socio.user] });
const G = g.b.id;
await post(`/v1/conversaciones/${G}/miembros/${mod.id}/rol`, jefe.t, { rolClave: 'moderador' });

const nuevo = async (quien, extra = {}) => {
  const id = uuid();
  const r = await post('/v1/mensajes', quien.t, { mensajeId: id, conversacionId: G, ...extra });
  return { id, r };
};

console.log('\n=== C.1 registro de metadatos ===');
let { id: m1, r } = await nuevo(socio);
ck('se registra el metadato de un mensaje', r.s === 200 && r.b.autorUsername === socio.user, JSON.stringify(r.b));
ck('el metadato NO contiene el texto', !('texto' in (r.b || {})) && !('cuerpo' in (r.b || {})), JSON.stringify(Object.keys(r.b || {})));

r = await post('/v1/mensajes', otro.t, { mensajeId: uuid(), conversacionId: G });
ck('quien no pertenece al grupo no puede registrar', r.s === 404 || r.s === 403, String(r.s));

console.log('\n=== C.2 responder ===');
let { id: m2, r: r2 } = await nuevo(mod, { respondeA: m1 });
ck('se responde a un mensaje existente', r2.s === 200 && r2.b.respondeA === m1, JSON.stringify(r2.b?.respondeA));

r = await post('/v1/mensajes', mod.t, { mensajeId: uuid(), conversacionId: G, respondeA: uuid() });
ck('no se puede responder a un mensaje de otra conversacion', r.s === 400, String(r.s));

console.log('\n=== C.3 reacciones ===');
r = await post('/v1/mensajes/reaccion', jefe.t, { mensajeId: m1, emoji: '👍', poner: true });
ck('se agrega una reaccion', r.s === 200 && r.b.reacciones.length === 1 && r.b.reacciones[0].total === 1, JSON.stringify(r.b?.reacciones));

await post('/v1/mensajes/reaccion', mod.t, { mensajeId: m1, emoji: '👍', poner: true });
r = await post('/v1/mensajes/reaccion', socio.t, { mensajeId: m1, emoji: '🔥', poner: true });
ck('las reacciones se agrupan y cuentan', r.b.reacciones.length === 2 && r.b.reacciones[0].total === 2, JSON.stringify(r.b?.reacciones));
ck('se sabe si la reaccion es mia', r.b.reacciones.find(x => x.emoji === '🔥').mia === true);
ck('y quienes reaccionaron', r.b.reacciones.find(x => x.emoji === '👍').quienes.length === 2, JSON.stringify(r.b.reacciones[0].quienes));

r = await post('/v1/mensajes/reaccion', jefe.t, { mensajeId: m1, emoji: '👍', poner: false });
ck('se quita una reaccion', r.b.reacciones.find(x => x.emoji === '👍').total === 1);

r = await post('/v1/mensajes/reaccion', jefe.t, { mensajeId: m1, emoji: 'esto no es un emoji sino texto largo', poner: true });
ck('una reaccion demasiado larga se rechaza', r.s === 400, String(r.s));

console.log('\n=== C.4 editar ===');
r = await post(`/v1/mensajes/${m1}/editar`, socio.t);
ck('el autor puede editar su mensaje', r.s === 204, String(r.s));

r = await post(`/v1/mensajes/${m1}/editar`, jefe.t);
ck('NADIE mas puede editar, ni el propietario', r.s === 403, String(r.s));

console.log('\n=== C.5 retirar para todos ===');
const { id: m3 } = await nuevo(socio);
r = await post(`/v1/mensajes/${m3}/retirar`, socio.t);
ck('el autor retira su propio mensaje', r.s === 204, String(r.s));

r = await post(`/v1/mensajes/${m3}/editar`, socio.t);
ck('un mensaje retirado ya no se puede editar', r.s === 400, String(r.s));

const { id: m4 } = await nuevo(socio);
r = await post(`/v1/mensajes/${m4}/retirar`, mod.t);
ck('un moderador retira el mensaje de un miembro', r.s === 204, String(r.s));

const { id: m5 } = await nuevo(jefe);
r = await post(`/v1/mensajes/${m5}/retirar`, mod.t);
ck('pero NO el del propietario (jerarquia)', r.s === 403, String(r.s));

const { id: m6 } = await nuevo(mod);
r = await post(`/v1/mensajes/${m6}/retirar`, socio.t);
ck('un miembro no retira el mensaje de otro', r.s === 403, String(r.s));

console.log('\n=== C.7 fijar ===');
const { id: m7 } = await nuevo(socio);
r = await post(`/v1/mensajes/${m7}/fijar`, socio.t, { fijar: true });
ck('un miembro no puede fijar', r.s === 403, String(r.s));

r = await post(`/v1/mensajes/${m7}/fijar`, mod.t, { fijar: true });
ck('un moderador si puede fijar', r.s === 204, String(r.s));

let fj = await get(`/v1/conversaciones/${G}/fijados`, socio.t);
ck('el mensaje aparece en los fijados', fj.s === 200 && fj.b.length === 1 && fj.b[0].id === m7, JSON.stringify(fj.b?.length));

r = await post(`/v1/mensajes/${m7}/fijar`, mod.t, { fijar: false });
fj = await get(`/v1/conversaciones/${G}/fijados`, socio.t);
ck('se puede desfijar', fj.b.length === 0);

console.log('\n=== C.8 menciones ===');
const { id: m8, r: r8 } = await nuevo(socio, { menciones: [mod.user, 'fantasma_inexistente'] });
ck('se registra un mensaje con menciones', r8.s === 200);

console.log('\n=== C.9 mensajes temporales ===');
r = await put(`/v1/conversaciones/${G}/temporales`, socio.t, { segundos: 3600 });
ck('un miembro no configura los temporales', r.s === 403, String(r.s));

r = await put(`/v1/conversaciones/${G}/temporales`, jefe.t, { segundos: 3600 });
ck('el propietario si', r.s === 204, String(r.s));

const { r: rTemp } = await nuevo(socio);
ck('los mensajes nuevos nacen con vencimiento', rTemp.b.expiraEn > Date.now(), JSON.stringify(rTemp.b?.expiraEn));

r = await put(`/v1/conversaciones/${G}/temporales`, jefe.t, { segundos: 5 });
ck('un tiempo menor al minimo se rechaza', r.s === 400, String(r.s));

r = await put(`/v1/conversaciones/${G}/temporales`, jefe.t, { segundos: null });
ck('se pueden volver permanentes', r.s === 204, String(r.s));
const { r: rPerm } = await nuevo(socio);
ck('y los nuevos ya no vencen', rPerm.b.expiraEn === null, JSON.stringify(rPerm.b?.expiraEn));

console.log('\n=== restricciones aplicadas a mensajes ===');
await post(`/v1/conversaciones/${G}/miembros/${socio.id}/silenciar`, mod.t, { minutos: 60 });
r = await post('/v1/mensajes', socio.t, { mensajeId: uuid(), conversacionId: G });
ck('un silenciado no puede registrar mensajes', r.s === 403, String(r.s));
r = await post('/v1/mensajes/reaccion', socio.t, { mensajeId: m1, emoji: '😀', poner: true });
ck('y tampoco puede reaccionar: silenciar es una sancion', r.s === 403, String(r.s));

// El modo anuncio es OTRA cosa: regula quien habla, no sanciona.
await post(`/v1/conversaciones/${G}/miembros/${socio.id}/silenciar`, mod.t, { minutos: 0 });
await fetch(BASE + `/v1/conversaciones/${G}/miembros/${socio.id}/silenciar`, { method: 'DELETE', headers: H(mod.t) });
await put(`/v1/conversaciones/${G}/config`, jefe.t, { nombre: 'Chat C', soloAdmins: true });
r = await post('/v1/mensajes', socio.t, { mensajeId: uuid(), conversacionId: G });
ck('en modo anuncio un miembro no puede enviar', r.s === 403, String(r.s));
r = await post('/v1/mensajes/reaccion', socio.t, { mensajeId: m1, emoji: '😀', poner: true });
ck('pero SI puede reaccionar: el modo anuncio no sanciona', r.s === 200, String(r.s));

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
