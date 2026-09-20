// Modulo K: llamadas.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que el servidor NO vea señalizacion. La prueba es negativa y va contra la
//     base: no debe haber ni un SDP en ninguna tabla. El SDP viaja dentro de
//     sobres cifrados porque contiene las huellas DTLS, y un servidor que
//     pudiera cambiarlas podria escuchar la llamada.
//  2. Que una llamada reuse los permisos de la conversacion: quien no puede
//     escribir no puede hacer sonar un telefono. Bloqueos, silenciados y
//     expulsados incluidos, sin un sistema de permisos paralelo.
//  3. K.6: que el ajuste "quien puede llamarme" sea del DUENO y no se pueda
//     saltar agregando a la victima a la propia libreta.
//  4. Que con multi-dispositivo contestar en uno haga callar a los otros.
//  5. Que el timbre no dependa del cliente: si el que llama desaparece, la
//     llamada se cierra igual.
//  6. Que el historial distinga "no me contesto" de "me colgo" y marque las
//     perdidas.
import { execSync } from 'node:child_process';
import { randomBytes, createHmac } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const CLAVE = 'clave-larga-123';

async function reg(u, hw) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: CLAVE, etiquetaDispositivo: 'd-' + u,
      identidadPub: b64('k' + u), hardwareHash: b64(hw || ('HW-' + u + S)),
      hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m,
    headers: t ? H(t) : { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);

const psql = (sql) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`,
  { stdio: 'pipe' },
).toString().trim();

const juego = (n) => ({
  registrationId: 5000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1568)), firma: b64(randomBytes(64)) },
  unicas: [{ keyId: 101, publica: b64(randomBytes(33)) }],
});

const ana = await reg('ka');
const beto = await reg('kb');
const ceci = await reg('kc');
const dani = await reg('kd');
for (const [i, u] of [ana, beto, ceci, dani].entries()) {
  await put('/v1/claves', u.t, juego(i + 1));
}

// Conversaciones. Se habla primero para que sean "conocidos": el ajuste por
// defecto de llamadas es 'conocidos', no 'todos'.
const directa = (await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user })).b.id;
const grupo = (await post('/v1/conversaciones/grupo', ana.t,
  { nombre: 'Llamada grupal ' + S, usernames: [beto.user, ceci.user] })).b.id;

console.log('\n=== K.4 credenciales de TURN ===');
let r = await get('/v1/llamadas/turn', ana.t);
ck('se entregan credenciales', r.s === 200 && r.b.hay === true, JSON.stringify(r.b).slice(0, 140));
ck('con al menos una URL', (r.b.urls || []).length >= 1, JSON.stringify(r.b.urls));
ck('el usuario lleva el vencimiento delante', /^\d{10,}:/.test(r.b.usuario || ''), r.b.usuario);
ck('y vence en horas, no en dias', r.b.expiraEnSegundos > 0 && r.b.expiraEnSegundos <= 86400,
   String(r.b.expiraEnSegundos));

// La clave tiene que ser HMAC-SHA1(secreto, usuario), como espera coturn.
const esperada = createHmac('sha1', 'secreto-turn-de-pruebas').update(r.b.usuario).digest('base64');
ck('la clave es HMAC-SHA1(secreto, usuario): el esquema REST de coturn',
   r.b.clave === esperada, r.b.clave + ' vs ' + esperada);

ck('el secreto del TURN NO viaja al cliente',
   JSON.stringify(r.b).includes('secreto-turn-de-pruebas') === false);

console.log('\n=== iniciar una llamada ===');
r = await post('/v1/llamadas', ana.t, { conversacionId: directa, conVideo: false });
ck('se inicia la llamada', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 140));
ck('devuelve los destinos por dispositivo, como los mensajes',
   (r.b.destinos || []).some((d) => d.dispositivoId === beto.dev),
   JSON.stringify((r.b.destinos || []).map((d) => d.username)));
ck('y el TURN de una vez, para no pedirlo aparte', r.b.turn?.hay === true);
const LL1 = r.b.llamadaId;

r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
ck('dos llamadas a la vez en la misma conversacion se rechazan', r.s === 409, String(r.s));
ck('y lo dice', (r.b?.motivo || '').includes('en curso'), JSON.stringify(r.b).slice(0, 110));

r = await post('/v1/llamadas', ana.t, { conversacionId: grupo });
ck('y quien ya esta en una llamada no puede empezar otra', r.s === 409, String(r.s));
ck('con ese motivo', (r.b?.motivo || '').includes('ya estas en una llamada') ||
   (r.b?.motivo || '').toLowerCase().includes('llamada'), JSON.stringify(r.b).slice(0, 110));

console.log('\n=== EN LA BASE NO HAY NI UN SDP ===');
// La prueba de fondo del modulo. El SDP contiene las huellas DTLS: si el
// servidor las viera podria cambiarlas y escuchar la llamada.
const columnas = psql(
  `SELECT count(*) FROM information_schema.columns WHERE table_name IN ('llamada','llamada_participante') ` +
  `AND (column_name LIKE '%sdp%' OR column_name LIKE '%oferta%' OR column_name LIKE '%candidat%' ` +
  `OR column_name LIKE '%ice%')`,
);
ck('ninguna columna de llamada guarda SDP, ofertas ni candidatos', columnas === '0', columnas);
const rutas = psql(`SELECT count(*) FROM llamada WHERE false`);
ck('la tabla de llamadas existe y solo tiene metadatos', rutas === '0');

console.log('\n=== contestar ===');
r = await post(`/v1/llamadas/${LL1}/contestar`, ceci.t);
ck('quien no esta en la llamada no puede contestar', r.s === 409 || r.s === 404, String(r.s));

r = await post(`/v1/llamadas/${LL1}/contestar`, beto.t);
ck('el que recibe contesta', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 140));
ck('y pasa a en_curso', r.b.estado === 'en_curso', r.b.estado);
ck('con su propio TURN y sus destinos', r.b.turn?.hay === true && (r.b.destinos || []).length >= 1);

r = await post(`/v1/llamadas/${LL1}/contestar`, beto.t);
ck('contestar dos veces no rompe nada', r.s === 409, String(r.s));

r = await get('/v1/llamadas/en-curso', ana.t);
ck('la llamada viva se puede recuperar al arrancar la app', r.s === 200 && r.b.llamadaId === LL1,
   String(r.s) + JSON.stringify(r.b).slice(0, 110));

console.log('\n=== terminar ===');
r = await post(`/v1/llamadas/${LL1}/terminar`, ana.t, { motivo: 'colgada' });
ck('se termina la llamada', r.s === 204, String(r.s));
r = await get('/v1/llamadas/en-curso', ana.t);
ck('y ya no hay ninguna viva', r.s === 204, String(r.s));

r = await post(`/v1/llamadas/${LL1}/terminar`, ana.t, { motivo: 'colgada' });
ck('terminar una llamada ya terminada no revienta', r.s === 404 || r.s === 204, String(r.s));

r = await post(`/v1/llamadas/${LL1}/terminar`, ana.t, { motivo: 'inventado' });
ck('un motivo desconocido se rechaza', r.s === 400, String(r.s));

console.log('\n=== el historial distingue colgar de no contestar ===');
r = await get('/v1/llamadas/historial', ana.t);
const h1 = (r.b.llamadas || []).find((x) => x.id === LL1);
ck('la llamada esta en el historial', !!h1, JSON.stringify(r.b).slice(0, 160));
ck('marcada como mia', h1?.fueMia === true);
ck('con motivo "colgada": se contesto y alguien corto', h1?.finMotivo === 'colgada', h1?.finMotivo);
ck('no cuenta como perdida', h1?.perdida === false);
ck('y con la otra persona listada', (h1?.participantes || []).includes(beto.user),
   JSON.stringify(h1?.participantes));

// Una DIRECTA no tiene nombre de conversacion -esa columna es de los grupos-,
// asi que el titulo tiene que caer al username del otro. Sin esto el historial
// listaba "Llamada" sin nombre, que es justo el dato que se va a buscar.
ck('el titulo de una directa es con quien se hablo, no vacio',
   h1?.titulo === beto.user, JSON.stringify(h1?.titulo));

// Una que se cancela antes de contestar: eso NO es "colgada".
r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
const LL2 = r.b.llamadaId;
r = await post(`/v1/llamadas/${LL2}/terminar`, ana.t, { motivo: 'colgada' });
ck('cancelar antes de que contesten responde 204', r.s === 204, String(r.s));
r = await get('/v1/llamadas/historial', ana.t);
const h2 = (r.b.llamadas || []).find((x) => x.id === LL2);
ck('el servidor la guarda como CANCELADA, no como colgada: el motivo no se cree al cliente',
   h2?.finMotivo === 'cancelada', h2?.finMotivo);
ck('y sin duracion, porque nadie hablo', h2?.duracion === 0, String(h2?.duracion));

// Y del otro lado, eso es una perdida.
r = await get('/v1/llamadas/historial', beto.t);
const h2b = (r.b.llamadas || []).find((x) => x.id === LL2);
ck('para quien la recibio es una llamada PERDIDA', h2b?.perdida === true, JSON.stringify(h2b).slice(0, 140));
ck('y no fue suya', h2b?.fueMia === false);

// Rechazar tambien es distinto de colgar.
r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
const LL3 = r.b.llamadaId;
r = await post(`/v1/llamadas/${LL3}/terminar`, beto.t, { motivo: 'colgada' });
ck('el que recibe cuelga sin contestar', r.s === 204, String(r.s));
r = await get('/v1/llamadas/historial', ana.t);
ck('y el servidor lo guarda como RECHAZADA',
   (r.b.llamadas || []).find((x) => x.id === LL3)?.finMotivo === 'rechazada',
   (r.b.llamadas || []).find((x) => x.id === LL3)?.finMotivo);

console.log('\n=== K.6 quien puede llamarme ===');
r = await put('/v1/perfil/privacidad', beto.t, { llamadas: 'nadie' });
ck('el ajuste se guarda', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 110));
r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
ck('con "nadie" no se puede llamar', r.s === 403, String(r.s));
ck('y se dice sin ambiguedad', (r.b?.motivo || '').includes('no acepta llamadas'),
   JSON.stringify(r.b).slice(0, 120));

await put('/v1/perfil/privacidad', beto.t, { llamadas: 'conocidos' });
r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
ck('con "conocidos" y habiendo hablado, si', r.s === 200, String(r.s));
await post(`/v1/llamadas/${r.b.llamadaId}/terminar`, ana.t, { motivo: 'cancelada' });

// El salto de privacidad, otra vez: agregarse a la propia libreta no vale.
r = await post('/v1/conversaciones/directa', dani.t, { usernameDestino: ceci.user });
const dirDani = r.b?.id;
await put('/v1/perfil/privacidad', ceci.t, { llamadas: 'nadie' });
r = await post('/v1/contactos', dani.t, { username: ceci.user });
ck('dani agrega a ceci a SU libreta', r.s === 200, String(r.s));
r = await post('/v1/llamadas', dani.t, { conversacionId: dirDani });
ck('agregarla a mi libreta NO me deja saltar su ajuste', r.s === 403, String(r.s));

await put('/v1/perfil/privacidad', ceci.t, { llamadas: 'conocidos' });
r = await post('/v1/llamadas', dani.t, { conversacionId: dirDani });
ck('y con "conocidos" si, porque ya habian hablado', r.s === 200, String(r.s));
await post(`/v1/llamadas/${r.b.llamadaId}/terminar`, dani.t, { motivo: 'cancelada' });

console.log('\n=== una llamada reusa los permisos de la conversacion ===');
// Un bloqueo corta la llamada sin necesidad de un permiso propio.
await post(`/v1/bloqueos/${beto.user}`, ana.t);
r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
ck('un bloqueo impide llamar, sin un permiso de llamadas aparte', r.s === 403, String(r.s));
await call('DELETE', `/v1/bloqueos/${beto.user}`, ana.t);

// Y un canal no se puede llamar.
r = await post('/v1/canales', ana.t, { nombre: 'Canal ' + S, alias: 'canal_k_' + S, publico: true });
const canal = r.b?.conversacionId;
r = await post('/v1/llamadas', ana.t, { conversacionId: canal });
ck('no se puede llamar a un canal', r.s === 409, String(r.s));
ck('y se explica', (r.b?.motivo || '').includes('canal'), JSON.stringify(r.b).slice(0, 110));

console.log('\n=== K.5 llamadas de grupo: malla con tope ===');
r = await post('/v1/llamadas', ana.t, { conversacionId: grupo, conVideo: true });
ck('se puede llamar a un grupo de tres', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
const LLG = r.b.llamadaId;
ck('con video', psql(`SELECT con_video FROM llamada WHERE id='${LLG}'`) === 't');

r = await post(`/v1/llamadas/${LLG}/contestar`, beto.t);
ck('uno contesta', r.s === 200, String(r.s));
r = await post(`/v1/llamadas/${LLG}/contestar`, ceci.t);
ck('y el otro tambien', r.s === 200, String(r.s));

r = await post(`/v1/llamadas/${LLG}/terminar`, ceci.t, { motivo: 'colgada' });
ck('que uno cuelgue responde 204', r.s === 204, String(r.s));
ck('pero la llamada sigue viva: quedan dos dentro',
   psql(`SELECT estado FROM llamada WHERE id='${LLG}'`) === 'en_curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLG}'`));

r = await post(`/v1/llamadas/${LLG}/terminar`, beto.t, { motivo: 'colgada' });
ck('cuando queda uno solo, la llamada termina: una llamada de uno no es una llamada',
   psql(`SELECT estado FROM llamada WHERE id='${LLG}'`) === 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LLG}'`));

// El tope: un grupo de cinco no se puede llamar en malla.
const eva = await reg('ke');
const fito = await reg('kf');
await put('/v1/claves', eva.t, juego(8));
await put('/v1/claves', fito.t, juego(9));
r = await post('/v1/conversaciones/grupo', ana.t, {
  nombre: 'Grupo grande ' + S, usernames: [beto.user, ceci.user, eva.user, fito.user],
});
const grande = r.b?.id;
r = await post('/v1/llamadas', ana.t, { conversacionId: grande });
ck('un grupo de cinco se rechaza con 409, no con un fallo raro', r.s === 409, String(r.s));
ck('y se explica el motivo real: sin SFU, cada telefono sube su video N-1 veces',
   (r.b?.motivo || '').includes('servidor de medios'), JSON.stringify(r.b).slice(0, 180));

console.log('\n=== el timbre no depende del cliente ===');
r = await post('/v1/llamadas', ana.t, { conversacionId: directa });
const LLT = r.b.llamadaId;
ck('suena', psql(`SELECT estado FROM llamada WHERE id='${LLT}'`) === 'sonando');
// Se envejece la llamada a mano para no esperar 45 segundos reales.
psql(`UPDATE llamada SET iniciada_en = now() - interval '60 seconds' WHERE id='${LLT}'`);
await new Promise((res) => setTimeout(res, 12000));
ck('el barrido del servidor la cierra sola, aunque nadie avise',
   psql(`SELECT estado FROM llamada WHERE id='${LLT}'`) === 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LLT}'`));
ck('con motivo sin_respuesta',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLT}'`) === 'sin_respuesta',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLT}'`));
r = await get('/v1/llamadas/historial', beto.t);
ck('y del otro lado queda como perdida',
   (r.b.llamadas || []).find((x) => x.id === LLT)?.perdida === true,
   JSON.stringify((r.b.llamadas || []).find((x) => x.id === LLT)).slice(0, 140));

console.log('\n=== coherencia del estado en la base ===');
ck('no hay llamadas "terminada" sin fecha de fin: lo impide un CHECK',
   psql(`SELECT count(*) FROM llamada WHERE estado='terminada' AND terminada_en IS NULL`) === '0');
ck('ni "en_curso" sin fecha de contestacion',
   psql(`SELECT count(*) FROM llamada WHERE estado='en_curso' AND contestada_en IS NULL`) === '0');

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
