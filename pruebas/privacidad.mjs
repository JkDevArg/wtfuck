const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const WS = 'ws://localhost:8300/v1/ws';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]), Buffer.alloc(40, 9)]);

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { token: j.token, H: { Authorization: 'Bearer ' + j.token }, user: u };
}
const J = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const get = async (ruta, t) => { const r = await fetch(BASE + ruta, { headers: { Authorization: 'Bearer ' + t } }); return { s: r.status, b: await r.json().catch(() => null) }; };
const post = async (ruta, body, t) => { const r = await fetch(BASE + ruta, { method: 'POST', headers: J(t), body: JSON.stringify(body) }); return { s: r.status, b: await r.json().catch(() => null) }; };
const put = async (ruta, body, t) => { const r = await fetch(BASE + ruta, { method: 'PUT', headers: J(t), body: JSON.stringify(body) }); return { s: r.status, b: await r.json().catch(() => null) }; };

function abrir(token) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`${WS}?token=${encodeURIComponent(token)}`);
    ws.recibidos = [];
    ws.onmessage = (e) => ws.recibidos.push(JSON.parse(e.data));
    ws.onopen = () => res(ws);
    ws.onerror = rej;
  });
}
const esperar = (ws, tipo, ms = 4000) => new Promise((res, rej) => {
  const t0 = Date.now();
  const i = setInterval(() => {
    const m = ws.recibidos.find((x) => x.type === tipo);
    if (m) { clearInterval(i); res(m); }
    else if (Date.now() - t0 > ms) { clearInterval(i); rej(new Error('timeout ' + tipo)); }
  }, 30);
});
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

const A = await reg('ana' + S);
const B = await reg('beto' + S);
const C = await reg('caro' + S);

// A sube una foto
await fetch(BASE + '/v1/perfil/avatar', { method: 'PUT', headers: A.H, body: PNG });
await put('/v1/perfil', { nombreMostrado: 'Ana', estadoTexto: 'trabajando' }, A.token);

console.log('\n=== PRIVACIDAD: por defecto todo abierto ===');
let p = await get('/v1/perfil/privacidad', A.token);
ck('privacidad arranca en "todos"', p.b.foto === 'todos' && p.b.escribe === 'todos' && p.b.grupos === 'todos', JSON.stringify(p.b));

let visto = await get('/v1/usuarios/ana' + S, C.token);
ck('un desconocido ve la foto y el estado por defecto', visto.b.avatarVersion > 0 && visto.b.estadoTexto === 'trabajando');

console.log('\n=== "Quien ve mi foto" ===');
await put('/v1/perfil/privacidad', { foto: 'nadie', estado: 'todos', escribe: 'todos', grupos: 'todos' }, A.token);
visto = await get('/v1/usuarios/ana' + S, C.token);
ck('con foto=nadie, la version de avatar llega en 0', visto.b.avatarVersion === 0, JSON.stringify(visto.b));
ck('el estado sigue visible (ajustes independientes)', visto.b.estadoTexto === 'trabajando');

let img = await fetch(`${BASE}/v1/usuarios/ana${S}/avatar`, { headers: C.H });
ck('pedir la URL de la foto directamente da 403 (no solo se oculta en la UI)', img.status === 403, String(img.status));

let propia = await fetch(`${BASE}/v1/usuarios/ana${S}/avatar`, { headers: A.H });
ck('el dueno siempre ve su propia foto', propia.status === 200);

console.log('\n=== "Quien ve mi estado" ===');
await put('/v1/perfil/privacidad', { foto: 'todos', estado: 'nadie', escribe: 'todos', grupos: 'todos' }, A.token);
visto = await get('/v1/usuarios/ana' + S, C.token);
ck('con estado=nadie, el estado llega vacio', visto.b.estadoTexto === '');
ck('y la foto vuelve a verse', visto.b.avatarVersion > 0);

const mio = await get('/v1/perfil', A.token);
ck('en mi propio perfil veo todo sin filtrar', mio.b.estadoTexto === 'trabajando' && mio.b.avatarVersion > 0);

console.log('\n=== "conocidos" = con quien ya hablo ===');
await put('/v1/perfil/privacidad', { foto: 'conocidos', estado: 'todos', escribe: 'todos', grupos: 'todos' }, A.token);
visto = await get('/v1/usuarios/ana' + S, C.token);
ck('C todavia no es conocido: no ve la foto', visto.b.avatarVersion === 0);

const conv = await post('/v1/conversaciones/directa', { usernameDestino: 'ana' + S }, C.token);
ck('C puede abrir conversacion con A (escribe=todos)', conv.s === 200, JSON.stringify(conv.b));

visto = await get('/v1/usuarios/ana' + S, C.token);
ck('ya con conversacion, C SI ve la foto', visto.b.avatarVersion > 0, JSON.stringify(visto.b));
img = await fetch(`${BASE}/v1/usuarios/ana${S}/avatar`, { headers: C.H });
ck('y la descarga funciona', img.status === 200);

visto = await get('/v1/usuarios/ana' + S, B.token);
ck('B, que no ha hablado con A, sigue sin ver la foto', visto.b.avatarVersion === 0);

console.log('\n=== "Quien me puede escribir" ===');
await put('/v1/perfil/privacidad', { foto: 'todos', estado: 'todos', escribe: 'conocidos', grupos: 'todos' }, A.token);
const intento = await post('/v1/conversaciones/directa', { usernameDestino: 'ana' + S }, B.token);
ck('B (desconocido) no puede abrir conversacion con A', intento.s === 403, JSON.stringify(intento.b));
ck('y el mensaje de error lo explica', String(intento.b?.motivo || '').includes('no acepta mensajes'));

const reintento = await post('/v1/conversaciones/directa', { usernameDestino: 'ana' + S }, C.token);
ck('C, que ya es conocido, si puede', reintento.s === 200);

const nivelInvalido = await put('/v1/perfil/privacidad', { foto: 'todos', estado: 'todos', escribe: 'nadie', grupos: 'todos' }, A.token);
ck('escribe="nadie" es rechazado (no existe ese nivel)', nivelInvalido.s === 400, String(nivelInvalido.s));

console.log('\n=== "Quien me puede agregar a grupos" ===');
await put('/v1/perfil/privacidad', { foto: 'todos', estado: 'todos', escribe: 'todos', grupos: 'nadie' }, A.token);
const g1 = await post('/v1/conversaciones/grupo', { nombre: 'Sin permiso', usernames: ['ana' + S] }, B.token);
ck('con grupos=nadie, nadie puede agregar a A', g1.s === 403, JSON.stringify(g1.b));

await put('/v1/perfil/privacidad', { foto: 'todos', estado: 'todos', escribe: 'todos', grupos: 'conocidos' }, A.token);
const g2 = await post('/v1/conversaciones/grupo', { nombre: 'Desconocido', usernames: ['ana' + S] }, B.token);
ck('con grupos=conocidos, B (desconocido) sigue sin poder', g2.s === 403);
const g3 = await post('/v1/conversaciones/grupo', { nombre: 'Conocido', usernames: ['ana' + S] }, C.token);
ck('pero C (conocido) si puede', g3.s === 200, JSON.stringify(g3.b));

console.log('\n=== AVISO DE "TE AGREGARON A UN GRUPO" ===');
await put('/v1/perfil/privacidad', { foto: 'todos', estado: 'todos', escribe: 'todos', grupos: 'todos' }, A.token);

// A conectada: debe recibir el aviso en vivo
const wsA = await abrir(A.token);
await dormir(400);
wsA.recibidos.length = 0;

const g4 = await post('/v1/conversaciones/grupo', { nombre: 'Equipo EducaD', usernames: ['ana' + S] }, B.token);
ck('B crea el grupo con A dentro', g4.s === 200);

const ev = await esperar(wsA, 'evento');
ck('A recibe el aviso en vivo', ev.tipo === 'agregado_grupo', JSON.stringify(ev));
ck('el aviso dice quien la agrego', ev.actor === 'beto' + S, ev.actor);
ck('y a que grupo', ev.nombreConversacion === 'Equipo EducaD', ev.nombreConversacion);

// B offline: el aviso lo espera
const g5 = await post('/v1/conversaciones/grupo', { nombre: 'Mientras dormias', usernames: ['beto' + S] }, C.token);
ck('C crea otro grupo con B, que esta desconectado', g5.s === 200, JSON.stringify(g5.b));

const wsB = await abrir(B.token);
const evB = await esperar(wsB, 'evento', 5000);
ck('B recibe el aviso guardado al conectarse', evB.nombreConversacion === 'Mientras dormias', JSON.stringify(evB));

wsB.send(JSON.stringify({ type: 'acuse_evento', eventoIds: [evB.eventoId] }));
await dormir(600);
wsB.close();
await dormir(300);

const wsB2 = await abrir(B.token);
await dormir(900);
ck('tras el acuse el aviso no se repite', wsB2.recibidos.filter((x) => x.type === 'evento').length === 0,
  JSON.stringify(wsB2.recibidos.map((x) => x.type)));

[wsA, wsB2].forEach((w) => { try { w.close(); } catch { } });

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
