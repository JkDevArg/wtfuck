const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const WS = 'ws://localhost:8300/v1/ws';
const S = Math.random().toString(36).slice(2, 7);

let ok = 0, fail = 0;
const check = (nombre, cond, extra = '') => {
  if (cond) { ok++; console.log(`  PASA  ${nombre}`); }
  else { fail++; console.log(`  FALLA ${nombre} ${extra}`); }
};

const b64 = (s) => Buffer.from(s).toString('base64');
const uuid = () => crypto.randomUUID();

async function post(ruta, body, token) {
  const r = await fetch(BASE + ruta, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: JSON.stringify(body),
  });
  return { status: r.status, body: await r.json().catch(() => null) };
}
async function get(ruta, token) {
  const r = await fetch(BASE + ruta, { headers: { Authorization: 'Bearer ' + token } });
  return { status: r.status, body: await r.json().catch(() => null) };
}

function abrir(token) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`${WS}?token=${encodeURIComponent(token)}`);
    ws.recibidos = [];
    ws.onmessage = (e) => ws.recibidos.push(JSON.parse(e.data));
    ws.onopen = () => res(ws);
    ws.onerror = rej;
  });
}
const esperar = (ws, tipo, ms = 3000) => new Promise((res, rej) => {
  const t0 = Date.now();
  const i = setInterval(() => {
    const m = ws.recibidos.find((x) => x.type === tipo);
    if (m) { clearInterval(i); res(m); }
    else if (Date.now() - t0 > ms) { clearInterval(i); rej(new Error('timeout esperando ' + tipo)); }
  }, 30);
});
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

// Con E2EE el socket ya no lleva un cuerpo: lleva una copia por dispositivo
// destino. Estas pruebas van en claro (tipo 0) porque lo que comprueban es el
// ENRUTADO y los permisos, no la criptografia: el cifrado real se ejercita en
// la app, contra libsignal.
async function copiasPara(token, conv, texto) {
  const r = await get(`/v1/conversaciones/${conv}/destinos`, token);
  const ds = (r.body && r.body.destinos) || [];
  // Una sola copia para todos los destinos: sin cifrar los bytes se comparten.
  return ds.length === 0 ? [] : [{ destinos: ds.map((d) => d.dispositivoId), cuerpo: b64(texto), tipo: 0 }];
}


const reg = (u, hw, nivel = 'SOFTWARE_DEV') => post('/v1/registro', {
  username: u, password: 'clave-larga-123', etiquetaDispositivo: 'AVD ' + u,
  identidadPub: b64('idpub-' + u), hardwareHash: b64(hw), hardwareNivel: nivel,
});

console.log('\n=== FASE 1: registro y vinculo de hardware ===');
const a = await reg(`ana${S}`, `HW-A-${S}`);
check('registro de ana', a.status === 200 && a.body.token, JSON.stringify(a.body));
const b = await reg(`beto${S}`, `HW-B-${S}`);
check('registro de beto', b.status === 200 && b.body.token);

const dup = await reg(`carla${S}`, `HW-A-${S}`);
check('mismo hardware -> 409 (anti-duplicado)', dup.status === 409, JSON.stringify(dup.body));

const malUser = await reg('ab', `HW-C-${S}`);
check('username invalido -> 400', malUser.status === 400);

const login = await post('/v1/sesion', { username: `ana${S}`, password: 'clave-larga-123', hardwareHash: b64(`HW-A-${S}`) });
check('login desde su propio hardware', login.status === 200 && login.body.token);

const loginOtroHw = await post('/v1/sesion', { username: `ana${S}`, password: 'clave-larga-123', hardwareHash: b64('HW-INTRUSO') });
check('login desde otro hardware -> 403', loginOtroHw.status === 403, JSON.stringify(loginOtroHw.body));

const loginMalPass = await post('/v1/sesion', { username: `ana${S}`, password: 'incorrecta', hardwareHash: b64(`HW-A-${S}`) });
check('contrasena incorrecta -> 401', loginMalPass.status === 401);

const sinToken = await get('/v1/conversaciones', 'token-basura');
check('token invalido -> 401', sinToken.status === 401);

const TA = a.body.token, TB = b.body.token;

console.log('\n=== FASE 2: conversacion directa y mensaje ===');
const conv = await post('/v1/conversaciones/directa', { usernameDestino: `beto${S}` }, TA);
check('crear conversacion directa', conv.status === 200 && conv.body.id, JSON.stringify(conv.body));
const CID = conv.body.id;

const convDup = await post('/v1/conversaciones/directa', { usernameDestino: `beto${S}` }, TA);
check('crear la misma directa dos veces devuelve la misma', convDup.body?.id === CID);

const noExiste = await post('/v1/conversaciones/directa', { usernameDestino: 'fantasma999' }, TA);
check('conversacion con usuario inexistente -> 404', noExiste.status === 404);

const wsA = await abrir(TA);
const wsB = await abrir(TB);
await dormir(300);

const m1 = uuid();
wsA.send(JSON.stringify({ type: 'enviar', sobreId: m1, conversacionId: CID, creadoEn: Date.now(), copias: await copiasPara(TA, CID, 'hola beto') }));

const acep = await esperar(wsA, 'aceptado');
check('el remitente recibe "aceptado"', acep.sobreId === m1);

const ent = await esperar(wsB, 'entrega');
check('el destinatario recibe la entrega', ent.conversacionId === CID);
check('el cuerpo llega intacto', Buffer.from(ent.cuerpo, 'base64').toString() === 'hola beto');
check('la entrega trae el username del remitente', ent.origenUsername === `ana${S}`);

wsB.send(JSON.stringify({ type: 'acuse', sobreIds: [ent.sobreId] }));
const entregado = await esperar(wsA, 'entregado');
check('el remitente recibe "entregado" tras el acuse', !!entregado.sobreId);

console.log('\n=== FASE 3: buzon offline (msg off, caso a) ===');
wsB.close();
await dormir(400);

const m2 = uuid();
wsA.send(JSON.stringify({ type: 'enviar', sobreId: m2, conversacionId: CID, creadoEn: Date.now(), copias: await copiasPara(TA, CID, 'esto llega cuando vuelvas') }));
await esperar(wsA, 'aceptado');
await dormir(300);

const wsB2 = await abrir(TB);
const guardado = await esperar(wsB2, 'entrega', 4000);
check('el mensaje enviado offline llega al reconectar',
  Buffer.from(guardado.cuerpo, 'base64').toString() === 'esto llega cuando vuelvas');

wsB2.send(JSON.stringify({ type: 'acuse', sobreIds: [guardado.sobreId] }));
await dormir(400);

const wsB3 = await abrir(TB);
await dormir(600);
check('tras el acuse el buzon queda vacio', wsB3.recibidos.filter((x) => x.type === 'entrega').length === 0,
  JSON.stringify(wsB3.recibidos));
wsB3.close();

console.log('\n=== FASE 5: grupos ===');
const c = await reg(`caro${S}`, `HW-C2-${S}`);
const TC = c.body.token;

const grupo = await post('/v1/conversaciones/grupo', { nombre: 'Proyecto EducaD', usernames: [`beto${S}`, `caro${S}`] }, TA);
check('crear grupo', grupo.status === 200 && grupo.body.tipo === 'grupo', JSON.stringify(grupo.body));
check('el grupo lista a sus miembros', grupo.body?.participantes?.length === 2);
const GID = grupo.body.id;

const wsB4 = await abrir(TB);
const wsC = await abrir(TC);
await dormir(400);

const m3 = uuid();
wsA.send(JSON.stringify({ type: 'enviar', sobreId: m3, conversacionId: GID, creadoEn: Date.now(), copias: await copiasPara(TA, GID, 'hola grupo') }));

const gB = await esperar(wsB4, 'entrega');
const gC = await esperar(wsC, 'entrega');
check('el mensaje de grupo llega a los dos miembros',
  Buffer.from(gB.cuerpo, 'base64').toString() === 'hola grupo' &&
  Buffer.from(gC.cuerpo, 'base64').toString() === 'hola grupo');
check('cada destinatario recibe su propio id de sobre', gB.sobreId !== gC.sobreId);

const ajeno = await post('/v1/conversaciones/directa', { usernameDestino: `caro${S}` }, TB);
const wsIntruso = wsC;
const m4 = uuid();
wsIntruso.send(JSON.stringify({ type: 'enviar', sobreId: m4, conversacionId: ajeno.body.id, creadoEn: Date.now(), copias: await copiasPara(TC, ajeno.body.id, 'x') }));
await dormir(500);
check('enviar a una conversacion ajena es aceptado solo si perteneces',
  wsC.recibidos.some((x) => x.type === 'aceptado' && x.sobreId === m4));

const lista = await get('/v1/conversaciones', TA);
check('listar conversaciones devuelve directa y grupo', lista.body?.length === 2, JSON.stringify(lista.body?.map(x => x.tipo)));
check('el grupo conserva su nombre', lista.body?.some((x) => x.nombre === 'Proyecto EducaD'));

const salir = await fetch(`${BASE}/v1/conversaciones/${GID}/salir`, { method: 'POST', headers: { Authorization: 'Bearer ' + TC } });
check('salir del grupo', salir.status === 204);

const m5 = uuid();
wsA.send(JSON.stringify({ type: 'enviar', sobreId: m5, conversacionId: GID, creadoEn: Date.now(), copias: await copiasPara(TA, GID, 'ya no deberias ver esto') }));
await dormir(700);
check('quien salio del grupo ya no recibe mensajes',
  !wsC.recibidos.some((x) => x.type === 'entrega' && Buffer.from(x.cuerpo, 'base64').toString() === 'ya no deberias ver esto'));

[wsA, wsB4, wsC].forEach((w) => { try { w.close(); } catch {} });

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
