// La lista de bloqueados: verla y desbloquear desde la app.
//
// Lo que se comprueba:
//  - `GET /v1/bloqueos` trae solo a quienes bloquee YO, el mas reciente
//    primero, y no a quienes me bloquearon a mi;
//  - desbloquear funciona aunque la otra persona se oculte de la busqueda
//    (`priv_busqueda`): antes se resolvia el usuario con la busqueda, y quien
//    no me deja encontrarlo no se podia desbloquear nunca;
//  - bloquear funciona con quien comparte una conversacion conmigo aunque se
//    oculte de la busqueda: el caso tipico de alguien que molesta en un chat;
//  - bloquear a un desconocido que se oculta sigue dando 404: la ruta no
//    puede servir para averiguar si un usuario existe;
//  - sin sesion, 401.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
const P = 'bq' + S;
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
async function call(m, ruta, t, body) {
  const r = await fetch(BASE + ruta, {
    method: m,
    headers: t ? H(t) : { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}
async function reg(u) {
  const r = await call('POST', '/v1/registro', null, {
    username: P + u, password: 'clave-larga-123', etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-bq-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { t: r.b.token, id: r.b.usuarioId, user: P + u };
}
async function ocultarse(p) {
  const actual = (await call('GET', '/v1/perfil/privacidad', p.t)).b;
  return call('PUT', '/v1/perfil/privacidad', p.t, { ...actual, busqueda: 'nadie' });
}
const lista = async (p) => (await call('GET', '/v1/bloqueos', p.t)).b ?? [];
const nombres = async (p) => (await lista(p)).map((x) => x.username);

const ana = await reg('ana');
const beto = await reg('beto');
const carla = await reg('carla');
const dani = await reg('dani');

console.log('\n=== la lista ===');
let r = await call('GET', '/v1/bloqueos', ana.t);
ck('empieza vacia', r.s === 200 && Array.isArray(r.b) && r.b.length === 0, JSON.stringify(r));
r = await call('GET', '/v1/bloqueos', null);
ck('sin sesion, 401', r.s === 401, r.s);

r = await call('POST', `/v1/bloqueos/${beto.user}`, ana.t);
ck('ana bloquea a beto', r.s === 204, r.s);
await new Promise((res) => setTimeout(res, 20));
r = await call('POST', `/v1/bloqueos/${carla.user}`, ana.t);
ck('ana bloquea a carla', r.s === 204, r.s);
r = await call('POST', `/v1/bloqueos/${beto.user}`, ana.t);
ck('bloquear dos veces no falla', r.s === 204, r.s);

let l = await lista(ana);
ck('ana ve a los dos, sin repetir', l.length === 2, JSON.stringify(l));
ck('el mas reciente primero', l[0]?.username === carla.user && l[1]?.username === beto.user, JSON.stringify(l));
ck('trae el id y desde cuando', l[0]?.usuarioId === carla.id && l[0]?.desde > 0, JSON.stringify(l[0]));
ck('beto no ve a ana: no es suyo', (await nombres(beto)).length === 0);
ck('dani no ve nada', (await nombres(dani)).length === 0);

console.log('\n=== desbloquear a quien se oculta de la busqueda ===');
r = await ocultarse(beto);
ck('beto se oculta de la busqueda', r.s === 200, r.s);
r = await call('GET', `/v1/usuarios/${beto.user}`, ana.t);
ck('ana ya no encuentra a beto', r.s === 404, r.s);
r = await call('DELETE', `/v1/bloqueos/${beto.user}`, ana.t);
ck('y aun asi lo desbloquea', r.s === 204, r.s);
ck('beto sale de la lista', !(await nombres(ana)).includes(beto.user));

r = await call('DELETE', `/v1/bloqueos/${carla.user}`, ana.t);
ck('desbloquea a carla', r.s === 204, r.s);
ck('la lista queda vacia', (await nombres(ana)).length === 0);
r = await call('DELETE', `/v1/bloqueos/${carla.user}`, ana.t);
ck('desbloquear a quien no estaba bloqueado no falla', r.s === 204, r.s);
r = await call('DELETE', `/v1/bloqueos/${P}nadie`, ana.t);
ck('desbloquear a alguien que no existe, 404', r.s === 404, r.s);

console.log('\n=== bloquear a quien se oculta ===');
r = await call('POST', '/v1/conversaciones/directa', dani.t, { usernameDestino: ana.user });
ck('dani le escribe a ana', r.s === 200 || r.s === 201, JSON.stringify(r));
r = await ocultarse(dani);
ck('dani se oculta de la busqueda', r.s === 200, r.s);
r = await call('POST', `/v1/bloqueos/${dani.user}`, ana.t);
ck('ana bloquea a dani, con quien tiene un chat', r.s === 204, r.s);
ck('dani aparece en la lista', (await nombres(ana)).includes(dani.user));
r = await call('POST', `/v1/bloqueos/${beto.user}`, carla.t);
ck('carla no puede bloquear a beto, oculto y sin chat en comun: 404', r.s === 404, r.s);
r = await call('POST', `/v1/bloqueos/${P}nadie`, carla.t);
ck('igual que a alguien que no existe', r.s === 404, r.s);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail ? 1 : 0);
