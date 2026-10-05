// Modulo O · Historias.
//
// ## Lo que esta suite tiene que fijar
//
// Una historia es contenido que se publica a una audiencia y caduca. Eso son
// tres cosas que pueden salir mal por separado:
//
//  1. **La audiencia.** Sale de la privacidad de quien publica, y es el unico
//     ajuste de este proyecto que se resuelve AL REVES: no "puede este
//     observador ver lo mio" sino "a quienes les toca lo mio". Un error de
//     direccion ahi publica a gente que no debia verlo, y no hay forma de
//     enterarse mirando la pantalla.
//  2. **La caducidad.** Se filtra en la consulta y no con un barrendero, asi
//     que hay que comprobar que una historia vencida desaparece de verdad.
//  3. **Las vistas.** Ver una historia es leer un mensaje: si no respetara el
//     ajuste de confirmaciones de lectura, las historias serian la puerta de
//     atras del interruptor que alguien apago a proposito.
//
// ## Lo que NO prueba, dicho
//
// El contenido. Una historia viaja cifrada por el buzon igual que un mensaje
// de grupo, y el servidor no puede abrirla: aqui se prueba el metadato, la
// audiencia y los permisos. Que el sobre llegue y se descifre es lo mismo que
// ya cubren `e2e.mjs` y `claves.mjs`.
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
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body === undefined ? undefined : JSON.stringify(body) });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);
const del = (r, t) => call('DELETE', r, t);

const { execSync } = await import('node:child_process');
const sql = (q) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -t -c "${q}"`,
  { encoding: 'utf8' },
).trim();

/** Publica y devuelve el id. El contenido no pasa por aqui: va cifrado. */
async function publicar(quien, clase = 'texto') {
  const id = uuid();
  const r = await post('/v1/historias', quien.t, { historiaId: id, clase });
  return { id, r };
}

/** Deja la privacidad de alguien en un nivel concreto. */
async function nivel(quien, valor) {
  const actual = (await get('/v1/perfil/privacidad', quien.t)).b;
  return put('/v1/perfil/privacidad', quien.t, { ...actual, historias: valor });
}

const veLaDe = async (quien, historiaId) =>
  ((await get('/v1/historias', quien.t)).b?.historias || []).some((h) => h.historiaId === historiaId);

// ---------------------------------------------------------------------------
//  Siembra: una autora y tres relaciones distintas con ella
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

const autora = await reg('ha');
// Habla con ella: conversacion directa abierta en los dos sentidos.
const charla = await reg('hb');
// La tiene agendada pero nunca hablaron.
const agenda = await reg('hc');
// Ni la conoce.
const ajena = await reg('hd');

const d = await post('/v1/conversaciones/directa', autora.t, { usernameDestino: charla.user });
ck('hay una conversacion directa con quien habla', d.s === 200, String(d.s));

const g = await post('/v1/contactos', agenda.t, { username: autora.user });
ck('y alguien la tiene agendada sin haber hablado', g.s === 200 || g.s === 204, String(g.s));

// ---------------------------------------------------------------------------
//  1 · La audiencia sale de la privacidad
// ---------------------------------------------------------------------------
console.log('\n=== 1 · a quien le llega, segun el nivel ===');

await nivel(autora, 'nadie');
const nadie = await publicar(autora);
ck('con "nadie" se publica igual', nadie.r.s === 200, String(nadie.r.s));
ck('pero no le llega ni a quien habla con ella', !(await veLaDe(charla, nadie.id)));
ck('ni a quien la tiene agendada', !(await veLaDe(agenda, nadie.id)));
ck('y el servidor dice que fue a cero personas',
   nadie.r.b?.destinatarios === 0, JSON.stringify(nadie.r.b?.destinatarios));

await nivel(autora, 'conocidos');
const cono = await publicar(autora);
ck('con "conocidos" le llega a quien tiene conversacion abierta', await veLaDe(charla, cono.id));
ck('pero NO a quien solo la tiene agendada', !(await veLaDe(agenda, cono.id)));
ck('ni a una desconocida', !(await veLaDe(ajena, cono.id)));

await nivel(autora, 'todos');
const todos = await publicar(autora);
ck('con "todos" le llega a quien habla', await veLaDe(charla, todos.id));
ck('y tambien a quien solo la tiene agendada', await veLaDe(agenda, todos.id));
// Esta es la mitad que da sentido a la otra: "todos" NO es toda la plataforma.
// No se puede cifrar una historia contra gente con la que nunca hubo un
// intercambio de claves, y fingir que se publico para ellos seria mentir.
ck('pero "todos" NO alcanza a una desconocida', !(await veLaDe(ajena, todos.id)));

// ---------------------------------------------------------------------------
//  2 · Personalizado, con sus dos modos
// ---------------------------------------------------------------------------
console.log('\n=== 2 · personalizado: lista blanca y lista negra ===');

const exc = async (quien, modo, otros) =>
  put('/v1/perfil/privacidad/excepciones', quien.t,
      { ajuste: 'historias', modo, usernames: otros });

await nivel(autora, 'personalizado');
let r = await exc(autora, 'salvo', [charla.user]);
ck('se guarda una lista negra', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);

const salvo = await publicar(autora);
ck('con "salvo", quien esta en la lista NO la ve', !(await veLaDe(charla, salvo.id)));
ck('y quien no esta en la lista si', await veLaDe(agenda, salvo.id));

await exc(autora, 'solo', [charla.user]);
const solo = await publicar(autora);
ck('con "solo", quien esta en la lista SI la ve', await veLaDe(charla, solo.id));
ck('y quien no esta, no', !(await veLaDe(agenda, solo.id)));

// ---------------------------------------------------------------------------
//  3 · La audiencia se congela al publicar
// ---------------------------------------------------------------------------
console.log('\n=== 3 · la audiencia se congela ===');

await nivel(autora, 'conocidos');
const antes = await publicar(autora);
const tarde = await reg('he');
await post('/v1/conversaciones/directa', autora.t, { usernameDestino: tarde.user });

ck('quien llega DESPUES no ve lo de antes', !(await veLaDe(tarde, antes.id)));
// No es una simplificacion: la historia se cifro contra los aparatos que
// existian entonces, y quien no estaba no tiene con que abrirla. Mostrarsela en
// la lista seria ofrecerle algo que no puede leer.
const despues = await publicar(autora);
ck('pero si lo que se publica despues', await veLaDe(tarde, despues.id));

// ---------------------------------------------------------------------------
//  4 · Caducar
// ---------------------------------------------------------------------------
console.log('\n=== 4 · caduca sola, sin barrendero ===');

const viva = await publicar(autora);
ck('recien publicada se ve', await veLaDe(charla, viva.id));
ck('y dura 24 horas',
   Math.round((viva.r.b.expiraEn - viva.r.b.creadaEn) / 3_600_000) === 24,
   String((viva.r.b.expiraEn - viva.r.b.creadaEn) / 3_600_000));

// Se envejece a mano en vez de esperar: lo que se prueba es que la consulta
// filtra por caducidad, no que el reloj avanza.
sql(`UPDATE historia SET expira_en = now() - interval '1 minute' WHERE id = '${viva.id}'`);
ck('vencida deja de verse en el mismo instante', !(await veLaDe(charla, viva.id)));
const miasTrasVencer = (await get('/v1/historias/mias', autora.t)).b?.historias || [];
ck('y tampoco aparece entre las mias',
   !miasTrasVencer.some((h) => h.historiaId === viva.id));

// ---------------------------------------------------------------------------
//  5 · Vistas
// ---------------------------------------------------------------------------
console.log('\n=== 5 · quien la vio ===');

const conVista = await publicar(autora);
r = await post(`/v1/historias/${conVista.id}/vista`, charla.t);
ck('quien la recibio puede marcarla vista', r.s === 204, String(r.s));

const misTras = (await get('/v1/historias/mias', autora.t)).b?.historias || [];
const mia = misTras.find((h) => h.historiaId === conVista.id);
ck('la autora ve que la vio una persona', mia?.vistas === 1, JSON.stringify(mia?.vistas));

r = await get(`/v1/historias/${conVista.id}/vistas`, autora.t);
ck('y puede ver quien', r.b?.vistas?.[0]?.username === charla.user,
   JSON.stringify(r.b).slice(0, 120));

// Marcar dos veces no cuenta dos veces.
await post(`/v1/historias/${conVista.id}/vista`, charla.t);
r = await get(`/v1/historias/${conVista.id}/vistas`, autora.t);
ck('marcarla dos veces no la cuenta dos veces', (r.b?.vistas || []).length === 1,
   String((r.b?.vistas || []).length));

// ---------------------------------------------------------------------------
//  6 · Las vistas respetan las confirmaciones de lectura
// ---------------------------------------------------------------------------
console.log('\n=== 6 · las historias NO son la puerta de atras de "sin confirmaciones" ===');

const discreta = await reg('hf');
await post('/v1/conversaciones/directa', autora.t, { usernameDestino: discreta.user });
const pActual = (await get('/v1/perfil/privacidad', discreta.t)).b;
r = await put('/v1/perfil/privacidad', discreta.t, { ...pActual, lectura: false });
ck('se pueden apagar las confirmaciones', r.s === 200, String(r.s));

const paraDiscreta = await publicar(autora);
ck('le llega igual', await veLaDe(discreta, paraDiscreta.id));
r = await post(`/v1/historias/${paraDiscreta.id}/vista`, discreta.t);
ck('marcarla no da error', r.s === 204, String(r.s));

r = await get(`/v1/historias/${paraDiscreta.id}/vistas`, autora.t);
const vio = (r.b?.vistas || []).some((v) => v.username === discreta.user);
ck('pero NO queda registrada: ver una historia es leer un mensaje', !vio,
   JSON.stringify(r.b).slice(0, 140));

// Y la reciprocidad: quien no manda confirmaciones tampoco las recibe.
const suya = await publicar(discreta);
await post(`/v1/historias/${suya.id}/vista`, autora.t);
r = await get(`/v1/historias/${suya.id}/vistas`, discreta.t);
ck('quien las apago tampoco ve quien vio las suyas',
   (r.b?.vistas || []).length === 0, JSON.stringify(r.b).slice(0, 110));
ck('y se le DICE por que, en vez de devolver un vacio ambiguo',
   r.b?.motivo === 'sin_lectura', JSON.stringify(r.b?.motivo));

// ---------------------------------------------------------------------------
//  7 · Autorizacion
// ---------------------------------------------------------------------------
console.log('\n=== 7 · lo ajeno es ajeno ===');

const dela = await publicar(autora);

r = await post(`/v1/historias/${dela.id}/vista`, ajena.t);
ck('quien no la recibio no puede marcarla vista', r.s === 404, String(r.s));

r = await get(`/v1/historias/${dela.id}/vistas`, charla.t);
ck('quien la recibio NO ve la lista de vistas ajena', r.s === 404, String(r.s));

r = await del(`/v1/historias/${dela.id}`, charla.t);
ck('ni puede retirar una historia que no es suya', r.s === 404, String(r.s));
ck('y sigue viva despues del intento', await veLaDe(charla, dela.id));

r = await del(`/v1/historias/${dela.id}`, autora.t);
ck('la autora si la retira', r.s === 204, String(r.s));
ck('y deja de verse', !(await veLaDe(charla, dela.id)));
r = await del(`/v1/historias/${dela.id}`, autora.t);
ck('retirarla dos veces da 404', r.s === 404, String(r.s));

r = await post('/v1/historias', autora.t, { historiaId: uuid(), clase: 'inventada' });
ck('una clase que no existe se rechaza', r.s === 400, String(r.s));

// El editor de estados agrego los de audio: una grabacion sobre un color.
const deAudio = uuid();
r = await post('/v1/historias', autora.t, { historiaId: deAudio, clase: 'audio' });
ck('un estado de audio se acepta', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b));
r = await post('/v1/adjuntos', autora.t, { historiaId: deAudio, clase: 'audio', bytes: 4096, mime: 'audio/mp4', nombre: 'voz.m4a' });
ck('y su archivo de audio se puede reservar', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b));
await del(`/v1/historias/${deAudio}`, autora.t);
r = await post('/v1/historias', autora.t, { historiaId: 'no-es-uuid', clase: 'texto' });
ck('un id que no es uuid tambien', r.s === 400, String(r.s));

const sinSesion = await fetch(BASE + '/v1/historias');
ck('sin sesion no se ve nada', sinSesion.status === 401, String(sinSesion.status));

// ---------------------------------------------------------------------------
//  8 · Reintentar no duplica
// ---------------------------------------------------------------------------
console.log('\n=== 8 · publicar dos veces el mismo id ===');

const repe = uuid();
const p1 = await post('/v1/historias', autora.t, { historiaId: repe, clase: 'texto' });
const p2 = await post('/v1/historias', autora.t, { historiaId: repe, clase: 'texto' });
ck('el segundo intento no falla', p2.s === 200, String(p2.s));
ck('y devuelve la misma historia', p1.b?.historiaId === p2.b?.historiaId);
const cuantas = sql(`SELECT count(*) FROM historia WHERE id = '${repe}'`);
ck('hay UNA sola fila en la base', cuantas === '1', cuantas);

// ---------------------------------------------------------------------------
//  9 · Un bloqueo tapa las historias en los dos sentidos
// ---------------------------------------------------------------------------
console.log('\n=== 9 · bloquear tapa las historias, tambien las de antes ===');

const bloq = await reg('hg');
await post('/v1/conversaciones/directa', autora.t, { usernameDestino: bloq.user });
const previa = await publicar(autora);
ck('antes de bloquear, la ve', await veLaDe(bloq, previa.id));

r = await post(`/v1/bloqueos/${autora.user}`, bloq.t);
ck('se bloquea', r.s === 200 || r.s === 204, String(r.s));
// Filtrar solo al publicar dejaria vivas las de antes del bloqueo, que es
// justamente lo que alguien quiere que desaparezca.
ck('despues de bloquear, deja de ver hasta la de ANTES', !(await veLaDe(bloq, previa.id)));

// ---------------------------------------------------------------------------
//  10 · Los destinos, que es lo que el cliente necesita para cifrar
// ---------------------------------------------------------------------------
console.log('\n=== 10 · los destinos para cifrar ===');

await nivel(autora, 'nadie');
r = await get('/v1/historias/destinos', autora.t);
ck('con "nadie" no hay a quien cifrarle', (r.b?.destinos || []).length === 0,
   String((r.b?.destinos || []).length));

await nivel(autora, 'todos');
r = await get('/v1/historias/destinos', autora.t);
const destinos = r.b?.destinos || [];
ck('con "todos" hay destinos', destinos.length > 0, String(destinos.length));
ck('cada uno trae lo que hace falta para cifrar',
   destinos.every((x) => x.dispositivoId && x.identidad && x.registrationId !== undefined),
   JSON.stringify(destinos[0] || {}).slice(0, 140));
ck('y NO se incluye mi propio aparato',
   !destinos.some((x) => x.username === autora.user),
   JSON.stringify(destinos.map((x) => x.username)).slice(0, 120));

// ---------------------------------------------------------------------------
//  11 · Los sobres: el unico camino por el que viaja el contenido
// ---------------------------------------------------------------------------
//
//  Una historia no pertenece a ninguna conversacion, asi que sus sobres van por
//  una ruta propia. Eso la convierte en una superficie nueva: si no comprobara
//  la audiencia, seria una forma de meterle bytes en el buzon a cualquiera
//  saltandose las conversaciones **y los bloqueos**.
console.log('\n=== 11 · los sobres van a la audiencia y a nadie mas ===');

await nivel(autora, 'todos');
const conSobres = await publicar(autora);
const dests = (await get('/v1/historias/destinos', autora.t)).b?.destinos || [];
ck('hay destinos a los que cifrarle', dests.length > 0, String(dests.length));

const cuerpo = b64('esto-iria-cifrado');
r = await post(`/v1/historias/${conSobres.id}/sobres`, autora.t, {
  copias: dests.map((d) => ({ destinos: [d.dispositivoId], cuerpo, tipo: 0 })),
});
ck('se encolan los sobres', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
ck('y no queda ningun destino sin copia',
   (r.b?.sinCopia || []).length === 0, JSON.stringify(r.b?.sinCopia));

// La mitad que importa: cubrir solo a algunos NO se rellena con nada inventado,
// se DICE cuales faltan.
const otra = await publicar(autora);
r = await post(`/v1/historias/${otra.id}/sobres`, autora.t, { copias: [] });
ck('sin copias, el servidor dice cuantos faltan y no inventa',
   (r.b?.sinCopia || []).length === dests.length,
   `${(r.b?.sinCopia || []).length} de ${dests.length}`);

// Un aparato fuera de la audiencia se descarta en silencio: es lo que impide
// usar esta ruta como canal hacia cualquiera.
const fuera = await reg('hz');
const suDisp = sql(`SELECT id FROM dispositivo WHERE usuario_id = '${fuera.id}' LIMIT 1`);
const tercera = await publicar(autora);
r = await post(`/v1/historias/${tercera.id}/sobres`, autora.t, {
  copias: [{ destinos: [suDisp], cuerpo, tipo: 0 }],
});
ck('un aparato fuera de la audiencia no da error', r.s === 200, String(r.s));
const colados = sql(
  `SELECT count(*) FROM sobre_pendiente WHERE destino_dispositivo = '${suDisp}'`);
ck('pero NO le entra ningun sobre', colados === '0', colados);

// Y no se pueden mandar sobres de una historia ajena.
r = await post(`/v1/historias/${conSobres.id}/sobres`, charla.t, {
  copias: [{ destinos: [dests[0].dispositivoId], cuerpo, tipo: 0 }],
});
ck('nadie manda sobres de una historia que no es suya', r.s === 404, String(r.s));

// Un cuerpo que no cabe en un sobre se rechaza antes de tocar la base.
r = await post(`/v1/historias/${conSobres.id}/sobres`, autora.t, {
  copias: [{ destinos: [dests[0].dispositivoId], cuerpo: b64('x'.repeat(70_000)), tipo: 0 }],
});
ck('un cuerpo desmedido se rechaza', r.s === 400, String(r.s));

// Y el sobre queda SIN conversacion, que es lo que V29 permite.
const sinConv = sql(
  `SELECT count(*) FROM sobre_pendiente WHERE mensaje_id = '${conSobres.id}' AND conversacion_id IS NULL`);
ck('los sobres de una historia no cuelgan de ninguna conversacion',
   Number(sinConv) > 0, sinConv);

// ---------------------------------------------------------------------------
//  12 · Adjuntos: una historia con foto o video
// ---------------------------------------------------------------------------
//
//  Un adjunto cuelga de una conversacion **o** de una historia, y de ahi sale
//  quien puede bajarlo. Son dos preguntas distintas -participante de un chat,
//  destinatario de una historia- y por eso son dos columnas con un CHECK que
//  exige exactamente una: sin dueno no hay a quien preguntarle, y con dos gana
//  el camino mas permisivo.
console.log('\n=== 12 · adjuntos de historia ===');

await nivel(autora, 'conocidos');
const conFoto = await publicar(autora, 'imagen');

r = await post('/v1/adjuntos', autora.t, {
  historiaId: conFoto.id, clase: 'imagen', bytes: 4096, mime: 'image/jpeg',
});
ck('la autora reserva un adjunto para su historia', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
const r0 = r.b ?? {};
const adjId = r0.adjuntoId;
ck('y viene con una URL de subida', (r.b?.urlSubida || '').length > 0);

// El CHECK de la base: exactamente un dueno.
const duenos = sql(
  `SELECT count(*) FROM adjunto WHERE id = '${adjId}' AND historia_id IS NOT NULL AND conversacion_id IS NULL`);
ck('el adjunto cuelga de la historia y de ninguna conversacion', duenos === '1', duenos);

// Nadie mas le cuelga archivos a una historia ajena. Una historia es de una
// persona: no hay roles ni permisos por clase que discutir.
r = await post('/v1/adjuntos', charla.t, {
  historiaId: conFoto.id, clase: 'imagen', bytes: 4096,
});
ck('nadie mas le cuelga un archivo a una historia ajena', r.s === 404, String(r.s));

// Y no se puede pedir adjunto para una historia que no existe.
r = await post('/v1/adjuntos', autora.t, { historiaId: uuid(), clase: 'imagen', bytes: 10 });
ck('ni para una historia inventada', r.s === 404, String(r.s));

// Subir y CONFIRMAR. Este paso tiene prueba propia porque no tenerla costo
// caro: reservar y leer funcionaban, y confirmar reventaba con un 500 al leer
// `conversacion_id` -que ahora puede ser nulo- en un tipo que no admitia nulo.
// Como la barrida no confirmaba, la suite pasaba entera y el fallo aparecia
// recien con la app en la mano.
const subida = await fetch(r0.urlSubida ?? '', { method: 'PUT', body: Buffer.alloc(4096, 7) });
ck('el archivo se sube al almacen', subida.status === 200, String(subida.status));

r = await post(`/v1/adjuntos/${adjId}/confirmar`, autora.t);
ck('y se confirma sin reventar', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);

// --- quien puede BAJARLO, que es la mitad que importa ------------------
r = await get(`/v1/adjuntos/${adjId}`, autora.t);
ck('la autora puede leer su propio adjunto', r.s === 200, String(r.s));

r = await get(`/v1/adjuntos/${adjId}`, charla.t);
ck('quien esta en la audiencia tambien', r.s === 200, String(r.s));

// Si esto no se comprobara, el archivo de una historia seria publico para
// cualquiera que adivinara el id, y no serviria de nada cifrar el sobre.
r = await get(`/v1/adjuntos/${adjId}`, ajena.t);
ck('quien NO estaba en la audiencia no lo baja', r.s === 404, String(r.s));

// --- la caducidad alcanza tambien al archivo --------------------------
sql(`UPDATE historia SET expira_en = now() - interval '1 minute' WHERE id = '${conFoto.id}'`);
r = await get(`/v1/adjuntos/${adjId}`, charla.t);
ck('vencida la historia, su archivo deja de bajarse', r.s === 404, String(r.s));

// --- retirar una historia se lleva su archivo -------------------------
const otraFoto = await publicar(autora, 'imagen');
r = await post('/v1/adjuntos', autora.t, {
  historiaId: otraFoto.id, clase: 'imagen', bytes: 2048,
});
const adj2 = r.b?.adjuntoId;
await del(`/v1/historias/${otraFoto.id}`, autora.t);
r = await get(`/v1/adjuntos/${adj2}`, charla.t);
ck('retirada la historia, su archivo tampoco se baja', r.s === 404, String(r.s));

// --- un adjunto de chat sigue funcionando como siempre ----------------
// Lo que se agrego no puede haber roto el camino que ya existia.
const gr = await post('/v1/conversaciones/grupo', autora.t, {
  nombre: 'Con foto', usernames: [charla.user],
});
r = await post('/v1/adjuntos', autora.t, {
  conversacionId: gr.b.id, clase: 'imagen', bytes: 1024,
});
ck('un adjunto de conversacion se sigue reservando igual', r.s === 200, String(r.s));
const soloConv = sql(
  `SELECT count(*) FROM adjunto WHERE id = '${r.b.adjuntoId}' AND conversacion_id IS NOT NULL AND historia_id IS NULL`);
ck('y cuelga de la conversacion y de ninguna historia', soloConv === '1', soloConv);

// ---------------------------------------------------------------------------
//  13 · Responder una historia es escribir un mensaje, no un permiso nuevo
// ---------------------------------------------------------------------------
//
//  Responder a una historia **es** abrir el chat directo con quien la publico.
//  No hay ruta nueva que autorizar: la cita viaja dentro del sobre cifrado y el
//  servidor no la ve —ni puede—. Lo que si decide el servidor es lo de siempre:
//  ¿puede esta persona escribirme? Eso lo contesta `priv_escribe`.
//
//  Y los dos ajustes NO significan lo mismo por "conocido":
//
//    - `historias: conocidos` es la **mitad estricta**: solo con quien hay
//      conversacion directa abierta.
//    - `historias: todos` es todo el conjunto de quienes me conocen, que
//      incluye a quien me tiene en su libreta sin haber hablado nunca.
//    - `escribe: conocidos` mira la conversacion directa.
//
//  De ahi sale un caso que la interfaz TIENE que manejar: **se puede ver una
//  historia y no poder contestarla**. Publicar para todos no obliga a aceptar
//  mensajes de todos, y esta bien que no obligue; lo que no puede pasar es que
//  el boton de responder se quede en silencio cuando el servidor dice 403.
console.log('\n=== 13 · responder una historia ===');

const publica = await reg('hr');
const lectora = await reg('hs');

// La lectora la agenda, sin hablarle nunca: eso la mete en el conjunto de
// quienes la conocen, pero NO abre conversacion directa.
r = await post('/v1/contactos', lectora.t, { username: publica.user });
ck('la lectora agenda a quien publica, sin hablarle', r.s === 200 || r.s === 204, String(r.s));

let pr = (await get('/v1/perfil/privacidad', publica.t)).b;
r = await put('/v1/perfil/privacidad', publica.t, { ...pr, historias: 'todos', escribe: 'conocidos' });
ck('publica para todos y acepta mensajes solo de conocidos', r.s === 200, String(r.s));

const abierta = await publicar(publica, 'texto');
ck('la historia sale con audiencia', abierta.r.b?.destinatarios > 0, JSON.stringify(abierta.r.b));
ck('y la lectora la VE', await veLaDe(lectora, abierta.id));

// EL CASO. Ve la historia y no puede contestarla.
r = await post('/v1/conversaciones/directa', lectora.t, { usernameDestino: publica.user });
ck('pero NO puede abrir el chat para responderla', r.s === 403, String(r.s));
ck('y el servidor dice por que, no falla en silencio',
   String(r.b?.motivo || '').includes('no acepta mensajes'), JSON.stringify(r.b));

// --- con conversacion abierta, responder es un mensaje mas -------------
r = await put('/v1/perfil/privacidad', publica.t, { ...pr, historias: 'todos', escribe: 'todos' });
ck('si acepta mensajes de todos, ya se puede', r.s === 200, String(r.s));

r = await post('/v1/conversaciones/directa', lectora.t, { usernameDestino: publica.user });
ck('se abre el chat directo', r.s === 200, String(r.s));
const chat = r.b?.id;

// El servidor registra el metadato y **no sabe** que este mensaje contesta a
// una historia: la cita va dentro del sobre. No hace falta que lo sepa.
r = await post('/v1/mensajes', lectora.t, { mensajeId: uuid(), conversacionId: chat });
ck('y la respuesta se registra por el camino de siempre', r.s === 200, String(r.s));

// --- bloquear corta las dos cosas de una vez ---------------------------
// Es la unica herramienta que separa ver de responder sin tocar ajustes, y
// tiene que cortar los dos lados: dejar de ver las historias Y dejar de poder
// escribir. Si cortara solo uno, bloquear no serviria de nada.
r = await post(`/v1/bloqueos/${lectora.user}`, publica.t);
ck('se bloquea a la lectora', r.s === 200 || r.s === 204, String(r.s));

const trasBloqueo = await publicar(publica, 'texto');
ck('bloqueada, deja de ver las historias', !(await veLaDe(lectora, trasBloqueo.id)));

r = await post('/v1/mensajes', lectora.t, { mensajeId: uuid(), conversacionId: chat });
ck('y deja de poder escribir en el chat que ya tenia', r.s >= 400, String(r.s));

// --- y al reves: aceptar mensajes no publica historias -----------------
await call('DELETE', `/v1/bloqueos/${lectora.user}`, publica.t);
r = await put('/v1/perfil/privacidad', publica.t, { ...pr, historias: 'nadie', escribe: 'todos' });
ck('acepta mensajes de todos y no publica para nadie', r.s === 200, String(r.s));

const callada = await publicar(publica, 'texto');
ck('la lectora ya NO ve la historia', !(await veLaDe(lectora, callada.id)));
ck('y sin embargo si puede escribirle',
   (await post('/v1/conversaciones/directa', lectora.t,
               { usernameDestino: publica.user })).s === 200);

// ---------------------------------------------------------------------------
//  14 · Sin sobre no hay historia: cerrar el reparto
// ---------------------------------------------------------------------------
//
//  El metadato se registra ANTES que los sobres —el servidor lo exige para
//  poder validar los destinos—, asi que si el cifrado falla para alguien, esa
//  persona se queda con una historia que **aparece en su lista y no se puede
//  abrir**. Eso no es un detalle: es lo que se ve en la pantalla como "no se
//  pudo descifrar esta historia", y dura 24 horas.
//
//  Al cerrar el reparto, quien no recibio NINGUNA copia sale de la audiencia.
//  Por persona y no por aparato: quien tiene dos telefonos y recibio copia en
//  uno sigue viendola, porque si puede abrirla.
console.log('\n=== 14 · sin sobre no hay historia ===');

const emisora = await reg('hu');
const conSobre = await reg('hv');
const olvidada = await reg('hw');

// Las dos hablan con ella: las dos entran en la audiencia.
await post('/v1/conversaciones/directa', emisora.t, { usernameDestino: conSobre.user });
await post('/v1/conversaciones/directa', emisora.t, { usernameDestino: olvidada.user });
await nivel(emisora, 'conocidos');

const repartida = await publicar(emisora, 'texto');
ck('la historia sale con las dos en la audiencia',
   repartida.r.b?.destinatarios === 2, JSON.stringify(repartida.r.b));

// Se manda copia SOLO a una. Es lo que pasa de verdad cuando no se pudo abrir
// sesion con el otro aparato.
const dst = (await get('/v1/historias/destinos', emisora.t)).b?.destinos || [];
const suyo = dst.find((d) => d.username === conSobre.user);
r = await post(`/v1/historias/${repartida.id}/sobres`, emisora.t, {
  copias: [{ destinos: [suyo.dispositivoId], cuerpo: b64('solo-para-una'), tipo: 0 }],
});
ck('el servidor acepta el reparto parcial', r.s === 200, String(r.s));
ck('y dice a quien no le llego', (r.b?.sinCopia || []).length === 1, JSON.stringify(r.b));

// Lo que importa: la que no recibio copia deja de tenerla anunciada.
ck('quien recibio sobre la ve', await veLaDe(conSobre, repartida.id));
ck('quien NO recibio sobre ya no la ve', !(await veLaDe(olvidada, repartida.id)));

const quedan = sql(
  `SELECT count(*) FROM historia_destino WHERE historia_id = '${repartida.id}'`);
ck('y la audiencia guardada queda en una sola persona', quedan === '1', quedan);

// El autor no pierde la suya: no esta en su propia audiencia y no se toca.
const mias = (await get('/v1/historias/mias', emisora.t)).b?.historias || [];
ck('la autora sigue teniendo su historia', mias.some((h) => h.historiaId === repartida.id));

// --- un reparto por tandas no debe borrar a quien falta ---------------
// Si el cliente parte el envio, las tandas intermedias van con
// `ultimoLote: false`. Sin eso, la primera tanda se llevaria por delante a
// todo aquel a quien todavia no le tocaba.
const porTandas = await publicar(emisora, 'texto');
const dst2 = (await get('/v1/historias/destinos', emisora.t)).b?.destinos || [];
const uno = dst2.find((d) => d.username === conSobre.user);

r = await post(`/v1/historias/${porTandas.id}/sobres`, emisora.t, {
  copias: [{ destinos: [uno.dispositivoId], cuerpo: b64('tanda-1'), tipo: 0 }],
  ultimoLote: false,
});
ck('una tanda intermedia se acepta', r.s === 200, String(r.s));

const trasTanda = sql(
  `SELECT count(*) FROM historia_destino WHERE historia_id = '${porTandas.id}'`);
ck('y NO retira a nadie todavia', trasTanda === '2', trasTanda);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
