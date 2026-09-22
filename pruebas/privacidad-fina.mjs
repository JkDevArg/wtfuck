// §3 del brief · Los cuatro ajustes de privacidad que faltaban.
//
// Cada uno separa dos cosas que estaban pegadas:
//
//   1. `biografia`     — la bio no es el estado.
//   2. `videollamadas` — el video no es el audio.
//   3. `grabando`      — grabar no es escribir.
//   4. `solicitudes`   — la alternativa al portazo.
//
// Lo que se prueba aqui no es que se guarden: es que el SERVIDOR los aplique.
// Un ajuste que solo esconde un boton no es un ajuste de privacidad, y el §16
// del brief lo dice con todas las letras.
//
// Uso:  node pruebas/privacidad-fina.mjs

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const uuid = () => crypto.randomUUID();

let n = 0;
async function reg(u) {
  const nom = u + S + (n++);
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: nom, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + nom), hardwareHash: b64('HW-' + nom), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, user: nom };
}

const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m, headers: H(t), body: body === undefined ? undefined : JSON.stringify(body),
  });
  const txt = await r.text();
  let b = null;
  try { b = JSON.parse(txt); } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);

const { execSync } = await import('node:child_process');
const sql = (q) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -t -c "${q}"`,
  { encoding: 'utf8' },
).trim();

/** Cambia un ajuste sin pisar los demas. */
const ajuste = async (quien, cambios) => {
  const actual = (await get('/v1/perfil/privacidad', quien.t)).b;
  return put('/v1/perfil/privacidad', quien.t, { ...actual, ...cambios });
};

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

const duenia = await reg('pa');
const conocida = await reg('pb');
const ajena = await reg('pc');

// `conocida` habla con ella: conversacion directa abierta en los dos sentidos.
let r = await post('/v1/conversaciones/directa', duenia.t, { usernameDestino: conocida.user });
ck('hay una conversacion directa', r.s === 200, String(r.s));
const chat = r.b?.id;

r = await get('/v1/perfil/privacidad', duenia.t);
ck('los cuatro ajustes nuevos vienen en la privacidad',
   r.b?.biografia !== undefined && r.b?.videollamadas !== undefined &&
   r.b?.grabando !== undefined && r.b?.solicitudes !== undefined,
   JSON.stringify(r.b));
ck('la biografia nace en "todos"', r.b?.biografia === 'todos', String(r.b?.biografia));
ck('las videollamadas nacen en "conocidos", como las llamadas',
   r.b?.videollamadas === 'conocidos', String(r.b?.videollamadas));

// ---------------------------------------------------------------------------
//  1 · La biografia no es el estado
// ---------------------------------------------------------------------------
console.log('\n=== 1 · la biografia tiene su propio ajuste ===');

await put('/v1/cuenta/biografia', duenia.t, { biografia: 'Ingeniera de seguridad' });
sql(`UPDATE usuario SET biografia = 'Ingeniera de seguridad' WHERE username = '${duenia.user}'`);

r = await get(`/v1/usuarios/${duenia.user}`, ajena.t);
ck('una desconocida ve la bio con el ajuste en "todos"',
   (r.b?.biografia || '').includes('Ingeniera'), JSON.stringify(r.b?.biografia));

await ajuste(duenia, { biografia: 'nadie' });
r = await get(`/v1/usuarios/${duenia.user}`, ajena.t);
ck('con "nadie" deja de verla', !(r.b?.biografia || '').includes('Ingeniera'), JSON.stringify(r.b?.biografia));

// EL PUNTO: el estado sigue visible. Si los dos ajustes fueran el mismo, este
// caso no existiria y el ajuste nuevo seria decoracion.
sql(`UPDATE usuario SET estado_texto = 'De viaje' WHERE username = '${duenia.user}'`);
await ajuste(duenia, { biografia: 'nadie', estado: 'todos' });
r = await get(`/v1/usuarios/${duenia.user}`, ajena.t);
ck('y el ESTADO se sigue viendo: son dos ajustes distintos',
   (r.b?.estadoTexto || '').includes('De viaje'), JSON.stringify(r.b?.estadoTexto));

await ajuste(duenia, { biografia: 'conocidos' });
r = await get(`/v1/usuarios/${duenia.user}`, conocida.t);
ck('con "conocidos" la ve quien tiene conversacion abierta',
   (r.b?.biografia || '').includes('Ingeniera'), JSON.stringify(r.b?.biografia));
r = await get(`/v1/usuarios/${duenia.user}`, ajena.t);
ck('y no la ve quien no', !(r.b?.biografia || '').includes('Ingeniera'));

// La propia siempre se ve, pase lo que pase con el ajuste.
await ajuste(duenia, { biografia: 'nadie' });
r = await get('/v1/cuenta', duenia.t);
ck('la propia bio se ve siempre, aunque este en "nadie"',
   (r.b?.biografia || '').includes('Ingeniera'), JSON.stringify(r.b?.biografia));

// ---------------------------------------------------------------------------
//  2 · El video no es el audio
// ---------------------------------------------------------------------------
console.log('\n=== 2 · videollamadas aparte de las llamadas ===');

await ajuste(duenia, { llamadas: 'todos', videollamadas: 'nadie' });

const chatAjena = (await post('/v1/conversaciones/directa', ajena.t, { usernameDestino: duenia.user })).b?.id;

r = await post('/v1/llamadas', ajena.t, { conversacionId: chatAjena, conVideo: false });
ck('acepta una llamada de AUDIO de una desconocida', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);

// Se cuelga antes de seguir. Una llamada viva en esa conversacion hace que la
// siguiente de 409 "ya hay una en curso", y entonces la prueba de video
// pasaria por el motivo equivocado: es el error que el proyecto ya conoce de
// las barridas, un verde que no prueba lo que dice.
const enCurso = r.b?.llamadaId ?? r.b?.id;
r = await post(`/v1/llamadas/${enCurso}/terminar`, ajena.t, { motivo: 'colgada' });
ck('y se cuelga para no ensuciar lo que viene', r.s === 204 || r.s === 200, String(r.s));

r = await post('/v1/llamadas', ajena.t, { conversacionId: chatAjena, conVideo: true });
ck('y RECHAZA la de video, con el mismo ajuste de llamadas abierto',
   r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);
ck('y dice cual de los dos ajustes lo impidio',
   String(r.b?.motivo || '').includes('videollamadas'), JSON.stringify(r.b?.motivo));

// Al reves: si el audio esta cerrado, el video tambien. No hay puerta trasera.
await ajuste(duenia, { llamadas: 'nadie', videollamadas: 'todos' });
r = await post('/v1/llamadas', ajena.t, { conversacionId: chatAjena, conVideo: true });
if (r.s === 409) {
  // Si quedo una llamada viva de la comprobacion anterior, se cuelga y se
  // repite: un 409 aqui no dice nada del ajuste, que es lo que se mide.
  const viva = (await get(`/v1/llamadas/en-curso`, ajena.t)).b?.llamadas?.[0]?.llamadaId;
  if (viva) await post(`/v1/llamadas/${viva}/terminar`, ajena.t, { motivo: 'colgada' });
  r = await post('/v1/llamadas', ajena.t, { conversacionId: chatAjena, conVideo: true });
}
ck('si el audio esta cerrado, el video tambien: no hay puerta trasera',
   r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);

await ajuste(duenia, { llamadas: 'conocidos', videollamadas: 'conocidos' });

// ---------------------------------------------------------------------------
//  3 · Grabar no es escribir, y lo decide el SERVIDOR
// ---------------------------------------------------------------------------
console.log('\n=== 3 · el aviso de grabar ===');

// Este es el defecto que se arreglo de paso: `priv_escribiendo` lo miraba SOLO
// el cliente. Un cliente modificado -o viejo- seguia anunciando. Ahora el
// servidor no reenvia lo que su duena apago, venga de donde venga.
//
// El aviso viaja por WebSocket y no deja fila en la base, asi que aqui se
// comprueba lo que SI se puede comprobar sin socket: que los dos ajustes son
// independientes y que se guardan.
r = await ajuste(duenia, { escribiendo: true, grabando: false });
ck('se puede avisar al escribir y NO al grabar', r.s === 200, String(r.s));
ck('y se devuelve tal cual', r.b?.escribiendo === true && r.b?.grabando === false,
   JSON.stringify({ e: r.b?.escribiendo, g: r.b?.grabando }));

const enBase = sql(
  `SELECT priv_escribiendo::text || ',' || priv_grabando::text FROM usuario WHERE username = '${duenia.user}'`);
ck('y queda escrito en la base', enBase === 'true,false', enBase);

r = await ajuste(duenia, { escribiendo: false, grabando: true });
ck('y tambien al reves: grabar si, escribir no',
   r.b?.escribiendo === false && r.b?.grabando === true,
   JSON.stringify({ e: r.b?.escribiendo, g: r.b?.grabando }));

await ajuste(duenia, { escribiendo: true, grabando: true });

// ---------------------------------------------------------------------------
//  4 · Solicitudes: la alternativa al portazo
// ---------------------------------------------------------------------------
console.log('\n=== 4 · solicitudes de mensaje ===');

const pide = await reg('pd');

// Sin solicitudes: el portazo de siempre.
await ajuste(duenia, { escribe: 'conocidos', solicitudes: false });
r = await post('/v1/conversaciones/directa', pide.t, { usernameDestino: duenia.user });
ck('sin solicitudes, un desconocido recibe el portazo', r.s === 403, String(r.s));

// Con solicitudes: la conversacion nace MARCADA en vez de no nacer.
await ajuste(duenia, { escribe: 'conocidos', solicitudes: true });
r = await post('/v1/conversaciones/directa', pide.t, { usernameDestino: duenia.user });
ck('con solicitudes, la conversacion se abre', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
ck('pero nace MARCADA como solicitud', r.b?.esSolicitud === true, JSON.stringify(r.b?.esSolicitud));
const pendiente = r.b?.id;

const marcada = sql(
  `SELECT count(*) FROM conversacion WHERE id = '${pendiente}' AND solicitud_de IS NOT NULL`);
ck('y la marca esta en la base', marcada === '1', marcada);

// EL CASO que hace que el ajuste signifique algo: quien la manda NO puede
// aceptarsela. Si pudiera, seria saltarse el ajuste con una peticion mas.
r = await put(`/v1/conversaciones/${pendiente}/solicitud`, pide.t, { aceptar: true });
ck('quien la manda NO se la acepta a si mismo', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);

const sigueMarcada = sql(
  `SELECT count(*) FROM conversacion WHERE id = '${pendiente}' AND solicitud_de IS NOT NULL`);
ck('y sigue marcada despues del intento', sigueMarcada === '1', sigueMarcada);

// Quien no participa ni se entera de que existe.
r = await put(`/v1/conversaciones/${pendiente}/solicitud`, ajena.t, { aceptar: true });
ck('quien no participa recibe 404, no 403', r.s === 404, String(r.s));

// Quien la recibio si decide.
r = await put(`/v1/conversaciones/${pendiente}/solicitud`, duenia.t, { aceptar: true });
ck('quien la recibio la acepta', r.s === 204 || r.s === 200, String(r.s));

const yaNormal = sql(
  `SELECT count(*) FROM conversacion WHERE id = '${pendiente}' AND solicitud_de IS NULL`);
ck('y deja de ser una solicitud', yaNormal === '1', yaNormal);

// --- rechazar borra, y no deja lista de rechazados ---------------------
const pide2 = await reg('pe');
r = await post('/v1/conversaciones/directa', pide2.t, { usernameDestino: duenia.user });
const segunda = r.b?.id;
ck('entra una segunda solicitud', r.b?.esSolicitud === true, JSON.stringify(r.b?.esSolicitud));

r = await put(`/v1/conversaciones/${segunda}/solicitud`, duenia.t, { aceptar: false });
ck('se rechaza', r.s === 204 || r.s === 200, String(r.s));

const borrada = sql(`SELECT count(*) FROM conversacion WHERE id = '${segunda}'`);
ck('y la conversacion se BORRA: no queda lista de rechazados', borrada === '0', borrada);

// Rechazar no es bloquear: son dos decisiones distintas y juntarlas
// convertiria un "ahora no" en un portazo permanente.
r = await post('/v1/conversaciones/directa', pide2.t, { usernameDestino: duenia.user });
ck('rechazar NO bloquea: se puede volver a pedir', r.s === 200, String(r.s));

// --- con `escribe: todos` no hay solicitudes que valgan ----------------
await ajuste(duenia, { escribe: 'todos', solicitudes: true });
const pide3 = await reg('pf');
r = await post('/v1/conversaciones/directa', pide3.t, { usernameDestino: duenia.user });
ck('si acepta mensajes de todos, la conversacion nace normal', r.s === 200, String(r.s));
ck('y NO marcada como solicitud', r.b?.esSolicitud === false, JSON.stringify(r.b?.esSolicitud));

// --- un bloqueo gana sobre las solicitudes -----------------------------
// Si no, el ajuste seria una forma de saltarse un bloqueo.
await ajuste(duenia, { escribe: 'conocidos', solicitudes: true });
const bloqueado = await reg('pg');
await post(`/v1/bloqueos/${bloqueado.user}`, duenia.t);
r = await post('/v1/conversaciones/directa', bloqueado.t, { usernameDestino: duenia.user });
ck('un bloqueado no manda solicitudes', r.s === 403, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
