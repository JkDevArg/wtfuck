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
const del = (r, t) => call('DELETE', r, t);

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

// En una DIRECTA, colgar uno la termina para los dos.
//
// Es la regla que distingue una directa de un grupo: aqui quedarse solo es
// que la otra persona colgo, y no hay nadie mas que pueda entrar. Sin esto,
// el que no colgo se queda mirando una pantalla que ya no es una llamada.
ck('en una directa, que cuelgue UNO la cierra entera',
   psql(`SELECT estado FROM llamada WHERE id='${LL1}'`) === 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LL1}'`));
r = await get('/v1/llamadas/en-curso', beto.t);
ck('y al otro tampoco le queda ninguna viva', r.s === 204, String(r.s));

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
// Y NO es de grupo. La pantalla lo necesita para dos cosas: dibujar el icono
// de una persona en vez del de un grupo, y decidir si devolver la llamada es
// redialar -una directa- o abrir la hoja para elegir -un grupo, donde el tope
// de la malla son cuatro-.
ck('una directa NO se marca como de grupo', h1?.esGrupo === false, String(h1?.esGrupo));

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
// En un GRUPO, quedarse solo NO la cierra, y es el cambio del modulo AX.
//
// Antes esta misma linea afirmaba lo contrario —"una llamada de uno no es una
// llamada"— y para una directa sigue siendo cierto. Para un grupo no: alguien
// llega tarde, alguien se corta y vuelve, y cerrar al instante haria imposible
// ser el primero en entrar.
//
// No se queda viva para siempre: `cerrarLlamadasSolitarias` la cierra a los
// cinco minutos, y eso se prueba aparte en `SoloEnLlamadaTest` moviendo el
// reloj de la base — aqui habria que esperarlos de verdad.
ck('en un grupo, quedarse solo NO la cierra: se puede esperar a que entren',
   psql(`SELECT estado FROM llamada WHERE id='${LLG}'`) === 'en_curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLG}'`));

// Y cuando se va tambien el ultimo, ahi si.
r = await post(`/v1/llamadas/${LLG}/terminar`, ana.t, { motivo: 'colgada' });
ck('y cuando se va el ultimo, la llamada termina',
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
// Y el barrido AVISA, que es la mitad que faltaba.
//
// Cerraba la llamada en la base y no se lo decia a nadie: la pantalla del que
// llamaba se quedaba en "Llamando..." para una llamada que ya no existia y el
// otro telefono seguia sonando. Se vio en un emulador. La prueba de arriba
// mira la FILA, y una prueba que afirma sobre un estado no afirma sobre el
// aviso: por eso hacia falta esta.
ck('el barrido emite llamada_terminada a quien llamaba',
   psql(`SELECT count(*) FROM evento_pendiente e JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
        `WHERE e.tipo='llamada_terminada' AND d.usuario_id='${ana.id}' AND e.detalle LIKE '%${LLT}%'`) !== '0',
   psql(`SELECT count(*) FROM evento_pendiente e JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
        `WHERE e.tipo='llamada_terminada' AND d.usuario_id='${ana.id}' AND e.detalle LIKE '%${LLT}%'`));
ck('y tambien a quien estaba sonando',
   psql(`SELECT count(*) FROM evento_pendiente e JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
        `WHERE e.tipo='llamada_terminada' AND d.usuario_id='${beto.id}' AND e.detalle LIKE '%${LLT}%'`) !== '0');
ck('con el motivo real, no uno generico',
   psql(`SELECT count(*) FROM evento_pendiente WHERE tipo='llamada_terminada' ` +
        `AND detalle LIKE '%${LLT}%' AND detalle LIKE '%sin_respuesta%'`) !== '0');

r = await get('/v1/llamadas/historial', beto.t);
ck('y del otro lado queda como perdida',
   (r.b.llamadas || []).find((x) => x.id === LLT)?.perdida === true,
   JSON.stringify((r.b.llamadas || []).find((x) => x.id === LLT)).slice(0, 140));

console.log('\n=== AF: llamada de grupo eligiendo a quien ===');
//
// El defecto que esto cubre: el servidor llamaba a TODOS los de la
// conversacion y rechazaba la llamada entera si pasaban del tope. O sea que un
// grupo de cinco NO PODIA tener una llamada nunca, ni entre tres de sus
// miembros. El tope de la malla se aplicaba al grupo en vez de a la llamada.
const emi = await reg('kg');

await put('/v1/claves', emi.t, juego(5));
const grupoAF = (await post('/v1/conversaciones/grupo', ana.t, {
  nombre: 'Grupo grupoAF ' + S,
  usernames: [beto.user, ceci.user, dani.user, emi.user],
})).b.id;

r = await post('/v1/llamadas', ana.t, { conversacionId: grupoAF });
ck('sin elegir, un grupo de cinco sigue sin caber: el tope es real', r.s === 409, String(r.s));
ck('y el motivo explica por que, no solo que no',
   /malla|video|medios|hasta 4/i.test(r.b?.motivo || ''), JSON.stringify(r.b).slice(0, 160));

r = await post('/v1/llamadas', ana.t, {
  conversacionId: grupoAF, conVideo: true, invitados: [beto.id, ceci.id],
});
ck('ELIGIENDO a dos, la llamada sale: un grupo grande ya puede llamar', r.s === 200,
   String(r.s) + JSON.stringify(r.b).slice(0, 160));
const LLAF = r.b.llamadaId;
const destAF = (r.b.destinos || []).map((d) => d.username);
ck('los destinos traen a los elegidos', destAF.includes(beto.user) && destAF.includes(ceci.user),
   JSON.stringify(destAF));
// La parte que de verdad importa: una oferta cifrada que llega hace sonar el
// telefono sin preguntarle al servidor. Si los destinos trajeran a todo el
// grupo, elegir invitados no serviria absolutamente de nada.
ck('y NO a los que no se eligio: si no, les sonaria el telefono igual',
   !destAF.includes(dani.user) && !destAF.includes(emi.user), JSON.stringify(destAF));
ck('en la base quedan 3 participantes y no 5',
   psql(`SELECT count(*) FROM llamada_participante WHERE llamada_id='${LLAF}'`) === '3',
   psql(`SELECT count(*) FROM llamada_participante WHERE llamada_id='${LLAF}'`));
ck('y a los no elegidos no se les emitio nada',
   psql(`SELECT count(*) FROM llamada_participante WHERE llamada_id='${LLAF}' AND usuario_id='${dani.id}'`) === '0');

// Quien contesta cierra la malla con los DESTINOS que le devuelve el servidor.
// Si ahi viniera el grupo entero, el segundo en contestar desharia la eleccion.
r = await post(`/v1/llamadas/${LLAF}/contestar`, beto.t);
ck('un invitado contesta', r.s === 200, String(r.s));
const destAFB = (r.b.destinos || []).map((d) => d.username);
ck('y sus destinos son los de la LLAMADA, no los del grupo',
   destAFB.includes(ana.user) && destAFB.includes(ceci.user) &&
   !destAFB.includes(dani.user) && !destAFB.includes(emi.user), JSON.stringify(destAFB));
// Y de paso queda asentado el efecto de la regla nueva: que se vaya QUIEN
// LLAMO no corta a los que quedan. Con beto dentro y ceci todavia sonando,
// la llamada sigue.
await post(`/v1/llamadas/${LLAF}/terminar`, ana.t, { motivo: 'colgada' });
ck('que se vaya quien llamo no corta a los que quedan',
   psql(`SELECT estado FROM llamada WHERE id='${LLAF}'`) !== 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LLAF}'`));
await post(`/v1/llamadas/${LLAF}/terminar`, beto.t, { motivo: 'colgada' });
await post(`/v1/llamadas/${LLAF}/terminar`, ceci.t, { motivo: 'rechazada' });
ck('y cuando se van todos, si termina',
   psql(`SELECT estado FROM llamada WHERE id='${LLAF}'`) === 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LLAF}'`));

console.log('\n=== AF: la lista de invitados NO se cree ===');
// Llega del cliente, asi que es una peticion y no una orden.
r = await post('/v1/llamadas', ana.t, { conversacionId: directa, invitados: [ceci.id] });
ck('invitar a alguien que no esta en la conversacion se rechaza', r.s === 403, String(r.s));
ck('y no crea ninguna llamada',
   psql(`SELECT count(*) FROM llamada WHERE conversacion_id='${directa}' AND estado<>'terminada'`) === '0');

r = await post('/v1/llamadas', ana.t, {
  conversacionId: grupoAF, invitados: [beto.id, ceci.id, dani.id, emi.id],
});
ck('elegir a cuatro -cinco contandome- se rechaza por el tope', r.s === 409, String(r.s));

r = await post('/v1/llamadas', ana.t, { conversacionId: grupoAF, invitados: ['no-es-un-uuid'] });
ck('un id que no es un uuid da 400 y no un 500', r.s === 400, String(r.s));
ck('y el mensaje habla de una PERSONA, no de una conversacion',
   /persona/i.test(r.b?.motivo || ''), JSON.stringify(r.b).slice(0, 120));

r = await post('/v1/llamadas', ana.t, { conversacionId: grupoAF, invitados: [ana.id] });
ck('invitarme solo a mi mismo no es una llamada', r.s === 409, String(r.s));

console.log('\n=== AF: elegir no sirve para rodear un bloqueo ===');
// Cuando se llamaba al grupo entero, un bloqueo era ruido de fondo: la llamada
// iba dirigida a la conversacion. Elegir a una persona es senalarla, y hacer
// sonar su telefono por la via de un grupo compartido seria rodear el bloqueo.
r = await post(`/v1/bloqueos/${ana.user}`, emi.t);
ck('emi bloquea a ana', r.s === 204, String(r.s));

r = await post('/v1/llamadas', ana.t, { conversacionId: grupoAF, invitados: [beto.id, emi.id] });
ck('ana igual puede llamar al grupo', r.s === 200, String(r.s));
const LLAFB = r.b.llamadaId;
const destBl = (r.b.destinos || []).map((d) => d.username);
ck('pero a quien la bloqueo NO le suena', !destBl.includes(emi.user), JSON.stringify(destBl));
ck('y al otro si', destBl.includes(beto.user), JSON.stringify(destBl));
ck('ni queda como participante de la llamada',
   psql(`SELECT count(*) FROM llamada_participante WHERE llamada_id='${LLAFB}' AND usuario_id='${emi.id}'`) === '0');
// El silencio es deliberado: decir "esa persona te bloqueo" revela justo lo
// que un bloqueo esconde.
ck('y sin decirselo a ana, que es lo que un bloqueo esconde', r.s === 200, String(r.s));
await post(`/v1/llamadas/${LLAFB}/terminar`, ana.t, { motivo: 'colgada' });

r = await post('/v1/llamadas', ana.t, { conversacionId: grupoAF, invitados: [emi.id] });
ck('si el unico elegido la bloqueo, no hay llamada', r.s === 409, String(r.s));
await del(`/v1/bloqueos/${ana.user}`, emi.t);

console.log('\n=== AF: un rechazo no corta el timbre de los demas ===');
//
// Se vio en un emulador: se llamo a dos, el primero declino y la llamada
// termino entera con el segundo todavia sonando. La condicion para seguir
// viva era `dentro >= 2 && estado == 'en_curso'`, o sea que mientras SONABA
// cualquier rechazo la mataba.
r = await post('/v1/llamadas', ana.t, { conversacionId: grupoAF, invitados: [beto.id, ceci.id] });
ck('ana llama a dos', r.s === 200, String(r.s));
const LLR = r.b.llamadaId;

r = await post(`/v1/llamadas/${LLR}/terminar`, beto.t, { motivo: 'rechazada' });
ck('el primero rechaza', r.s === 204, String(r.s));
ck('y la llamada SIGUE VIVA: el otro todavia suena',
   psql(`SELECT estado FROM llamada WHERE id='${LLR}'`) === 'sonando',
   psql(`SELECT estado FROM llamada WHERE id='${LLR}'`));
ck('con el que rechazo marcado como rechazo',
   psql(`SELECT estado FROM llamada_participante WHERE llamada_id='${LLR}' AND usuario_id='${beto.id}'`) === 'rechazo');
ck('y el otro sigue sonando',
   psql(`SELECT estado FROM llamada_participante WHERE llamada_id='${LLR}' AND usuario_id='${ceci.id}'`) === 'sonando');

r = await post(`/v1/llamadas/${LLR}/contestar`, ceci.t);
ck('el segundo todavia puede contestar: era lo que se perdia', r.s === 200, String(r.s));
ck('y ahora si esta en curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLR}'`) === 'en_curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLR}'`));
// Cuelgan LOS DOS, y hace falta que sea asi desde el modulo AX.
//
// Con solo ana colgando, ceci se quedaba sola en una llamada de GRUPO — que
// ahora es un estado valido y dura cinco minutos— y esa llamada viva impedia
// empezar la siguiente en la misma conversacion. La suite fallaba dos pruebas
// mas abajo con un 400 que no tenia nada que ver.
await post(`/v1/llamadas/${LLR}/terminar`, ana.t, { motivo: 'colgada' });
await post(`/v1/llamadas/${LLR}/terminar`, ceci.t, { motivo: 'colgada' });

// Y el limite del otro lado: si rechaza el ULTIMO que sonaba, no queda nadie
// que pueda contestar y la llamada si termina.
// Llama beto y no ana: el limitador deja 20 llamadas por usuario cada diez
// minutos, y esta suite ya hace muchas. Repartirlas es parte de no pelearse
// con un limite que existe a proposito.
r = await post('/v1/llamadas', beto.t, { conversacionId: grupoAF, invitados: [ana.id, ceci.id] });
const LLR2 = r.b.llamadaId;
await post(`/v1/llamadas/${LLR2}/terminar`, ana.t, { motivo: 'rechazada' });
r = await post(`/v1/llamadas/${LLR2}/terminar`, ceci.t, { motivo: 'rechazada' });
ck('si rechaza el ultimo que sonaba, la llamada termina', r.s === 204, String(r.s));
ck('con motivo rechazada',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLR2}'`) === 'rechazada',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLR2}'`));

// Y cancelar siendo quien llama termina igual, aunque los demas suenen: sin
// nadie dentro no hay llamada a la que entrar.
r = await post('/v1/llamadas', ceci.t, { conversacionId: grupoAF, invitados: [ana.id, beto.id] });
const LLR3 = r.b.llamadaId;
r = await post(`/v1/llamadas/${LLR3}/terminar`, ceci.t, { motivo: 'colgada' });
ck('quien llama cancela y se termina, aunque los dos siguieran sonando', r.s === 204, String(r.s));
ck('y queda como cancelada, no como colgada',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLR3}'`) === 'cancelada',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLR3}'`));

console.log('\n=== AF: abrir la app no mata la llamada entrante ===');
//
// El otro defecto del emulador, y el peor: al arrancar, la app pedia
// /en-curso y colgaba lo que viniera. Con un aviso de llamada, abrir la app
// -que es lo que hace cualquiera- mataba la llamada antes de que sonara.
r = await post('/v1/llamadas', dani.t, { conversacionId: grupoAF, invitados: [beto.id] });
const LLE = r.b.llamadaId;
ck('dani llama a beto', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));

r = await get('/v1/llamadas/en-curso', beto.t);
ck('a quien le suena, /en-curso se la devuelve', r.s === 200 && r.b.llamadaId === LLE,
   String(r.s) + JSON.stringify(r.b).slice(0, 110));
ck('y dice que le esta SONANDO, no que esta dentro', r.b.miEstado === 'sonando', r.b.miEstado);
// Es el dato del que depende no colgarla: sin el, la app no puede distinguir
// esto de una llamada en la que ya estaba y cuyo medio se perdio.

r = await get('/v1/llamadas/en-curso', dani.t);
ck('a quien llamo le dice que esta dentro', r.b.miEstado === 'dentro', r.b.miEstado);

r = await post(`/v1/llamadas/${LLE}/contestar`, beto.t);
ck('contesta', r.s === 200, String(r.s));
r = await get('/v1/llamadas/en-curso', beto.t);
ck('y ya figura dentro', r.b.miEstado === 'dentro', r.b.miEstado);
const destE = (r.b.destinos || []).map((d) => d.username);
ck('los destinos de /en-curso tambien son los de la llamada',
   destE.includes(dani.user) && !destE.includes(ceci.user) && !destE.includes(emi.user),
   JSON.stringify(destE));
await post(`/v1/llamadas/${LLE}/terminar`, dani.t, { motivo: 'colgada' });
await post(`/v1/llamadas/${LLE}/terminar`, beto.t, { motivo: 'colgada' });

console.log('\n=== AF: quien llama se entera de lo que hace cada uno ===');
//
// Lo abrio el propio AF: que uno rechace ya no corta el timbre de los demas,
// asi que la llamada sigue... y antes de esto quien llamaba no se enteraba de
// nada. En una llamada de tres, B declinaba y la pantalla de A decia
// "llamando" por B durante los 45 segundos del timbre.
//
// El aviso NO cierra nada, y por eso es un tipo propio: un cliente que recibe
// `llamada_terminada` cuelga, y aqui la llamada sigue viva.
// Gente nueva y grupo nuevo: `ana` ya gasto sus veinte llamadas por diez
// minutos mas arriba. Contar cuantas lleva seria atar esta seccion a las de
// antes, y agregar una fila alli rompería ésta.
const pa = await reg('kp');
const pb = await reg('kq');
const pc = await reg('kr');
const pd = await reg('ks');
for (const [i, u] of [pa, pb, pc, pd].entries()) await put('/v1/claves', u.t, juego(10 + i));
const grupoP = (await post('/v1/conversaciones/grupo', pa.t, {
  nombre: 'Grupo avisos ' + S, usernames: [pb.user, pc.user, pd.user],
})).b.id;

r = await post('/v1/llamadas', pa.t, { conversacionId: grupoP, invitados: [pb.id, pc.id] });
ck('pa llama a dos', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
const LLP = r.b.llamadaId;

const avisosDe = (usuarioId, estado) => psql(
  `SELECT count(*) FROM evento_pendiente e JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
  `WHERE e.tipo='llamada_participante' AND d.usuario_id='${usuarioId}' ` +
  `AND e.detalle LIKE '%${LLP}%' AND e.detalle LIKE '%"${estado}"%'`);

r = await post(`/v1/llamadas/${LLP}/contestar`, pb.t);
ck('pb contesta', r.s === 200, String(r.s));

// La FOTO del momento de entrar. Hace falta porque los avisos cuentan
// CAMBIOS: quien se une a una llamada que ya empezo se perdio los anteriores,
// y alguien que entro antes no emite uno nuevo para el recien llegado. Sin
// esto, en la pantalla de pb los demas se quedarian en "sonando" para
// siempre.
ck('al contestar se recibe en que anda cada uno', r.b.estados?.[pa.user] === 'dentro',
   JSON.stringify(r.b.estados));
ck('incluido quien todavia suena', r.b.estados?.[pc.user] === 'sonando', JSON.stringify(r.b.estados));
ck('y sin uno mismo: la pantalla cuenta quien MAS esta',
   r.b.estados?.[pb.user] === undefined, JSON.stringify(r.b.estados));
ck('ni gente del grupo que no fue invitada', r.b.estados?.[pd.user] === undefined,
   JSON.stringify(r.b.estados));
ck('y a pa le llega que pb ENTRO', avisosDe(pa.id, 'dentro') !== '0', avisosDe(pa.id, 'dentro'));
ck('a pc, que sigue sonando, tambien', avisosDe(pc.id, 'dentro') !== '0');
ck('pero no se le manda a pb mismo', avisosDe(pb.id, 'dentro') === '0', avisosDe(pb.id, 'dentro'));

r = await post(`/v1/llamadas/${LLP}/terminar`, pc.t, { motivo: 'rechazada' });
ck('pc rechaza', r.s === 204, String(r.s));
ck('y a pa le llega que RECHAZO, no que la llamada termino',
   avisosDe(pa.id, 'rechazo') !== '0', avisosDe(pa.id, 'rechazo'));
// Y quien llegue AHORA ve el rechazo en la foto, no solo en los avisos.
r = await get('/v1/llamadas/en-curso', pb.t);
ck('la foto de /en-curso refleja el rechazo', r.b.estados?.[pc.user] === 'rechazo',
   JSON.stringify(r.b.estados));
ck('la llamada sigue viva: el aviso no la cierra',
   psql(`SELECT estado FROM llamada WHERE id='${LLP}'`) === 'en_curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLP}'`));
ck('y NO se emitio ningun llamada_terminada todavia',
   psql(`SELECT count(*) FROM evento_pendiente WHERE tipo='llamada_terminada' ` +
        `AND detalle LIKE '%${LLP}%'`) === '0');

// Y en el historial queda marcada COMO DE GRUPO, que es de lo que depende que
// la pantalla dibuje un grupo y no una persona.
//
// No se deduce del numero de participantes, y ahi estaba el defecto: una
// llamada de grupo a una sola persona tambien tiene un participante, asi que
// adivinarlo la habria dibujado como una directa.
r = await get('/v1/llamadas/historial', pa.t);
{
  const h = (r.b.llamadas || []).find((x) => x.id === LLP);
  ck('la llamada de grupo queda marcada como de grupo', h?.esGrupo === true, String(h?.esGrupo));
  ck('con el nombre del grupo como titulo', (h?.titulo || '').startsWith('Grupo avisos'),
     JSON.stringify(h?.titulo));
  ck('y con los dos invitados listados', (h?.participantes || []).includes(pb.user) &&
     (h?.participantes || []).includes(pc.user), JSON.stringify(h?.participantes));
  ck('sin la gente del grupo que no fue invitada',
     !(h?.participantes || []).includes(pd.user), JSON.stringify(h?.participantes));
}

// Se cierra la anterior ANTES de abrir la siguiente: dos llamadas vivas en la
// misma conversacion se rechazan con 409, y el 409 se leeria como un fallo del
// aviso.
await post(`/v1/llamadas/${LLP}/terminar`, pa.t, { motivo: 'colgada' });
await post(`/v1/llamadas/${LLP}/terminar`, pb.t, { motivo: 'colgada' });
ck('la anterior queda cerrada', psql(`SELECT estado FROM llamada WHERE id='${LLP}'`) === 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LLP}'`));

// Quien estaba DENTRO y cuelga sale como 'fuera', no como 'rechazo': la
// diferencia es la que separa "dijo que no" de "estuvo y se fue".
r = await post('/v1/llamadas', pd.t, { conversacionId: grupoP, invitados: [pa.id, pb.id] });
ck('pd abre otra', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 110));
const LLP2 = r.b.llamadaId;
await post(`/v1/llamadas/${LLP2}/contestar`, pa.t);
await post(`/v1/llamadas/${LLP2}/contestar`, pb.t);
r = await post(`/v1/llamadas/${LLP2}/terminar`, pa.t, { motivo: 'colgada' });
ck('quien estaba dentro y cuelga sale como fuera, no como rechazo',
   psql(`SELECT count(*) FROM evento_pendiente e JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
        `WHERE e.tipo='llamada_participante' AND d.usuario_id='${pd.id}' ` +
        `AND e.detalle LIKE '%${LLP2}%' AND e.detalle LIKE '%"fuera"%'`) !== '0');
await post(`/v1/llamadas/${LLP2}/terminar`, pd.t, { motivo: 'colgada' });
await post(`/v1/llamadas/${LLP2}/terminar`, pb.t, { motivo: 'colgada' });

console.log('\n=== AJ: una llamada en curso que nadie cerro ===');
//
// El barrido de timbres cierra las que SUENAN, y su comentario dice por que:
// el timbre no puede depender del cliente. Lo mismo vale para una llamada EN
// CURSO y no estaba: si las dos apps mueren sin mandar el "fin", la fila se
// queda en `en_curso` para siempre.
//
// No es teorico. En la base de desarrollo habia 121 asi, la mas vieja de seis
// dias. Y no es solo basura en el historial: `vivaEn` rechaza una llamada
// nueva en esa conversacion, asi que ese grupo no podia llamar nunca mas.
const ab1 = await reg('kt');
const ab2 = await reg('ku');
for (const [i, u] of [ab1, ab2].entries()) await put('/v1/claves', u.t, juego(20 + i));
const directaAb = (await post('/v1/conversaciones/directa', ab1.t,
  { usernameDestino: ab2.user })).b.id;

r = await post('/v1/llamadas', ab1.t, { conversacionId: directaAb });
const LLAB = r.b.llamadaId;
await post(`/v1/llamadas/${LLAB}/contestar`, ab2.t);
ck('la llamada esta en curso', psql(`SELECT estado FROM llamada WHERE id='${LLAB}'`) === 'en_curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLAB}'`));

// Nadie cuelga. Se envejece la fila trece horas: el tope son doce.
psql(`UPDATE llamada SET contestada_en = now() - interval '13 hours' WHERE id='${LLAB}'`);
await new Promise((res) => setTimeout(res, 12000));

ck('el barrido la cierra sola, aunque nadie avise',
   psql(`SELECT estado FROM llamada WHERE id='${LLAB}'`) === 'terminada',
   psql(`SELECT estado FROM llamada WHERE id='${LLAB}'`));
ck('con motivo fallo_red: la conexion se perdio y nadie lo conto',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLAB}'`) === 'fallo_red',
   psql(`SELECT fin_motivo FROM llamada WHERE id='${LLAB}'`));

// La duracion que queda es el TOPE, no trece horas. El servidor no sabe
// cuando termino de verdad —el medio nunca paso por el— y escribir `now()`
// inventaria una duracion que nada sostiene.
ck('la duracion anotada es el tope, no el tiempo real transcurrido',
   psql(`SELECT round(EXTRACT(EPOCH FROM (terminada_en - contestada_en))/3600) FROM llamada WHERE id='${LLAB}'`) === '12',
   psql(`SELECT EXTRACT(EPOCH FROM (terminada_en - contestada_en))/3600 FROM llamada WHERE id='${LLAB}'`));

// Y nadie queda 'dentro' de una llamada terminada.
ck('no queda nadie dentro',
   psql(`SELECT count(*) FROM llamada_participante WHERE llamada_id='${LLAB}' AND estado='dentro'`) === '0');

// Se avisa, igual que el otro barrido. Cerrar la fila sin decirlo deja las
// pantallas afirmando una llamada que ya no existe.
ck('y se le avisa a los dos lados',
   psql(`SELECT count(DISTINCT d.usuario_id) FROM evento_pendiente e ` +
        `JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
        `WHERE e.tipo='llamada_terminada' AND e.detalle LIKE '%${LLAB}%'`) === '2',
   psql(`SELECT count(DISTINCT d.usuario_id) FROM evento_pendiente e ` +
        `JOIN dispositivo d ON d.id=e.destino_dispositivo ` +
        `WHERE e.tipo='llamada_terminada' AND e.detalle LIKE '%${LLAB}%'`));

// Lo que de verdad importa: esa conversacion vuelve a poder llamar.
r = await post('/v1/llamadas', ab1.t, { conversacionId: directaAb });
ck('y la conversacion vuelve a poder llamar, que era lo que se bloqueaba',
   r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 110));
await post(`/v1/llamadas/${r.b.llamadaId}/terminar`, ab1.t, { motivo: 'colgada' });

// Y una llamada NORMAL no se toca: el tope esta para las abandonadas, no para
// cortar reuniones largas.
r = await post('/v1/llamadas', ab1.t, { conversacionId: directaAb });
const LLOK = r.b.llamadaId;
await post(`/v1/llamadas/${LLOK}/contestar`, ab2.t);
psql(`UPDATE llamada SET contestada_en = now() - interval '2 hours' WHERE id='${LLOK}'`);
await new Promise((res) => setTimeout(res, 12000));
ck('una llamada de dos horas NO se corta: el tope no esta para eso',
   psql(`SELECT estado FROM llamada WHERE id='${LLOK}'`) === 'en_curso',
   psql(`SELECT estado FROM llamada WHERE id='${LLOK}'`));
await post(`/v1/llamadas/${LLOK}/terminar`, ab1.t, { motivo: 'colgada' });
await post(`/v1/llamadas/${LLOK}/terminar`, ab2.t, { motivo: 'colgada' });

console.log('\n=== coherencia del estado en la base ===');
ck('no hay llamadas "terminada" sin fecha de fin: lo impide un CHECK',
   psql(`SELECT count(*) FROM llamada WHERE estado='terminada' AND terminada_en IS NULL`) === '0');
ck('ni "en_curso" sin fecha de contestacion',
   psql(`SELECT count(*) FROM llamada WHERE estado='en_curso' AND contestada_en IS NULL`) === '0');

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
