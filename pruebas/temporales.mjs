// Modulo AY · Chats y grupos temporales.
//
// ## Que se comprueba, y por que aqui
//
// El borrado de verdad lo hace cada telefono sobre su propia base: el servidor
// no tiene el historial, lo borra al confirmar la entrega. Asi que lo que se
// puede comprobar desde fuera es lo OTRO, que no es menos importante:
//
//  - que el plazo se guarda y vuelve en el listado, que es el unico camino por
//    el que un aparato recien sincronizado se entera;
//  - que un chat temporal es una conversacion APARTE y no convierte la que ya
//    existe;
//  - que al vencer, el servidor borra su rastro de verdad — fila, participantes
//    y sobres pendientes;
//  - y que avisa antes de borrar, porque despues ya no hay a quien avisar.
//
// El reloj se mueve en la base en vez de esperar una hora.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

const { execSync: ejecutar } = await import('node:child_process');
// Se mira la BASE y no solo la respuesta: lo que hay que demostrar es que el
// servidor borra de verdad, y eso no se ve desde fuera.
const psql = (sql) => ejecutar(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`,
  { stdio: 'pipe' },
).toString().trim();

const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};

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

const ana = await reg('ta');
const beto = await reg('tb');

const HORA = 3600000;
const DIA = 24 * HORA;

console.log('\n=== una directa temporal ===');
let r = await call('POST', '/v1/conversaciones/directa', ana.t, {
  usernameDestino: beto.user, duracionMs: DIA,
});
ck('se crea', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
const TEMP = r.b?.id;
ck('y vuelve con su fecha de vencimiento', r.b?.expiraEn > Date.now(), String(r.b?.expiraEn));
ck('que la escribio el SERVIDOR: esta en la base',
   psql(`SELECT expira_en IS NOT NULL FROM conversacion WHERE id='${TEMP}'`) === 't');

console.log('\n=== y NO se lleva puesta la conversacion normal ===');
r = await call('POST', '/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user });
const NORMAL = r.b?.id;
ck('la directa de siempre es otra conversacion', NORMAL && NORMAL !== TEMP,
   `${NORMAL} vs ${TEMP}`);
ck('y esa no vence', r.b?.expiraEn === 0, String(r.b?.expiraEn));
// Es lo que impide que pedir un chat temporal le ponga fecha de borrado a un
// historial que nadie acepto perder.
ck('la normal sigue sin fecha en la base',
   psql(`SELECT expira_en IS NULL FROM conversacion WHERE id='${NORMAL}'`) === 't');

r = await call('POST', '/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user });
ck('y pedirla otra vez devuelve LA MISMA, como siempre', r.b?.id === NORMAL);

console.log('\n=== dos temporales con la misma persona conviven ===');
r = await call('POST', '/v1/conversaciones/directa', ana.t, {
  usernameDestino: beto.user, duracionMs: HORA,
});
ck('se puede abrir otra temporal', r.s === 200 && r.b?.id !== TEMP, String(r.b?.id));

console.log('\n=== el plazo se valida, no se acepta cualquiera ===');
for (const malo of [1, 999, -1, 60000, 30 * DIA]) {
  r = await call('POST', '/v1/conversaciones/directa', ana.t, {
    usernameDestino: beto.user, duracionMs: malo,
  });
  ck(`se rechaza una duracion de ${malo} ms`, r.s === 400, String(r.s));
}

console.log('\n=== un grupo temporal ===');
r = await call('POST', '/v1/conversaciones/grupo', ana.t, {
  nombre: 'Efimero', usernames: [beto.user], duracionMs: HORA,
});
ck('se crea', r.s === 200, String(r.s));
const GRUPO = r.b?.id;
ck('con su fecha', r.b?.expiraEn > Date.now(), String(r.b?.expiraEn));

r = await call('POST', '/v1/conversaciones/grupo', ana.t, {
  nombre: 'De siempre', usernames: [beto.user],
});
ck('y un grupo normal no vence', r.b?.expiraEn === 0, String(r.b?.expiraEn));

console.log('\n=== el listado lo devuelve ===');
// Es el unico camino por el que un aparato recien sincronizado puede enterarse
// de que ese chat vence: no estuvo cuando se creo.
r = await call('GET', '/v1/conversaciones', beto.t);
const visto = (r.b ?? []).find((c) => c.id === TEMP);
ck('el OTRO tambien ve la fecha, sin haberla elegido el',
   visto && visto.expiraEn > Date.now(), JSON.stringify(visto).slice(0, 130));

console.log('\n=== al vencer, el servidor borra su rastro ===');
// Se mueve el reloj de la fila en vez de esperar un dia.
//
// Se mueven LAS DOS fechas, y hace falta: hay un CHECK —`temporal_coherente`—
// que no admite vencer antes de haber nacido. Poner solo `expira_en` en el
// pasado lo violaba, y esta bien que lo haga: una fila asi no tiene sentido.
// Lo que se modela aqui es un chat creado hace una hora que vencio hace un
// minuto, que es una fila perfectamente posible.
psql(`UPDATE conversacion SET creada_en = now() - interval '1 hour', ` +
     `expira_en = now() - interval '1 minute' WHERE id='${TEMP}'`);
ck('quedan participantes antes de barrer',
   Number(psql(`SELECT count(*) FROM participante WHERE conversacion_id='${TEMP}'`)) > 0);

// El barrido corre cada 10 s en el servidor: se SONDEA en vez de dormir 12 s.
//
// Antes era un `setTimeout` de 12 s y fallaba de vez en cuando —solo en la
// corrida completa, nunca al correr esta suite sola—. Con el servidor cargado
// por las 37 suites anteriores, el barrido se atrasa lo justo para pasarse de
// los 12 s. Una prueba intermitente es peor que ninguna: ensena a ignorar los
// fallos, y el dia que este falle de verdad nadie va a mirar.
let borrada = false;
for (let i = 0; i < 30 && !borrada; i++) {
  await new Promise((res) => setTimeout(res, 1000));
  borrada = psql(`SELECT count(*) FROM conversacion WHERE id='${TEMP}'`) === '0';
}

ck('la conversacion ya no esta', borrada);
ck('ni sus participantes: el borrado arrastra lo suyo',
   psql(`SELECT count(*) FROM participante WHERE conversacion_id='${TEMP}'`) === '0');
ck('y la conversacion normal sigue entera',
   psql(`SELECT count(*) FROM conversacion WHERE id='${NORMAL}'`) === '1');

console.log('\n=== y se avisa a quien estaba ===');
// Cerrar en silencio dejaria la pantalla del otro mostrando un chat que ya no
// existe. El evento se emite ANTES del DELETE, porque despues no hay a quien.
const eventos = psql(
  `SELECT count(*) FROM evento_pendiente WHERE tipo='conversacion_vencida'`,
);
ck('quedo un aviso de conversacion vencida', Number(eventos) > 0, eventos);

// ============================================================
//  El temporizador POR MENSAJE
// ============================================================
//
// Otra cosa que el plazo del chat: alli se borra la conversacion entera en una
// fecha; aqui cada mensaje se borra a los N segundos y el chat sigue.
//
// Esto estaba roto a medias y no se notaba. La ruta era de SOLO ESCRITURA: se
// podia encender el temporizador y ningun cliente podia leerlo. Consecuencia:
// solo el emisor se enteraba del vencimiento -se lo devolvia el registro de su
// propio mensaje-, asi que el mensaje temporal desaparecia de SU telefono y se
// quedaba para siempre en el del otro. La app prometia una cosa y hacia la
// mitad, sin error ni aviso. Estas pruebas cubren el camino por el que quien
// recibe se entera.

console.log('\n=== el temporizador por mensaje se puede LEER ===');
r = await call('PUT', `/v1/conversaciones/${NORMAL}/temporales`, ana.t, { segundos: DIA / 1000 });
ck('se puede encender en una directa', r.s === 204, String(r.s) + JSON.stringify(r.b ?? ''));

r = await call('GET', '/v1/conversaciones', ana.t);
let vistoN = (r.b ?? []).find((c) => c.id === NORMAL);
ck('y vuelve en MI listado', vistoN?.temporalesSegundos === DIA / 1000,
   JSON.stringify(vistoN?.temporalesSegundos));

// Este es el que faltaba. Sin el, quien recibe no tiene de donde sacar el
// plazo, y su copia del mensaje se guarda como permanente.
r = await call('GET', '/v1/conversaciones', beto.t);
vistoN = (r.b ?? []).find((c) => c.id === NORMAL);
ck('y en el del OTRO, que es quien tiene que borrar su copia',
   vistoN?.temporalesSegundos === DIA / 1000, JSON.stringify(vistoN?.temporalesSegundos));

console.log('\n=== en una directa lo pone cualquiera de los dos ===');
// Antes exigia `grupo.editar_info`, que un `miembro` no tiene — y en una
// directa los dos son miembros. O sea: no se podia encender en un chat de dos,
// justo donde mas sentido tiene.
r = await call('PUT', `/v1/conversaciones/${NORMAL}/temporales`, beto.t, { segundos: 3600 });
ck('el otro tambien puede cambiarlo', r.s === 204, String(r.s) + JSON.stringify(r.b ?? ''));
r = await call('GET', '/v1/conversaciones', ana.t);
ck('y el cambio se ve', (r.b ?? []).find((c) => c.id === NORMAL)?.temporalesSegundos === 3600);

console.log('\n=== y se avisa al otro lado ===');
// Hace falta para que lo CUMPLA, no solo para dibujarlo: hasta que su telefono
// lo sepa, todo lo que llegue se guarda como permanente.
ck('quedo un aviso de cambio de temporizador',
   Number(psql(`SELECT count(*) FROM evento_pendiente WHERE tipo='conversacion_temporales'`)) > 0);

console.log('\n=== se puede apagar ===');
r = await call('PUT', `/v1/conversaciones/${NORMAL}/temporales`, ana.t, { segundos: null });
ck('apagarlo responde 204', r.s === 204, String(r.s));
r = await call('GET', '/v1/conversaciones', ana.t);
vistoN = (r.b ?? []).find((c) => c.id === NORMAL);
ck('y vuelve como nulo, no como cero',
   vistoN?.temporalesSegundos === null || vistoN?.temporalesSegundos === undefined,
   JSON.stringify(vistoN?.temporalesSegundos));
ck('y en la base queda NULL',
   psql(`SELECT temporales_segundos IS NULL FROM conversacion WHERE id='${NORMAL}'`) === 't');

console.log('\n=== el plazo se valida ===');
for (const malo of [0, 30, 59, -5, 7776001]) {
  r = await call('PUT', `/v1/conversaciones/${NORMAL}/temporales`, ana.t, { segundos: malo });
  ck(`se rechaza un plazo de ${malo} s`, r.s === 400, String(r.s));
}
for (const bueno of [60, 300, 3600, 86400, 604800, 7776000]) {
  r = await call('PUT', `/v1/conversaciones/${NORMAL}/temporales`, ana.t, { segundos: bueno });
  ck(`se acepta un plazo de ${bueno} s`, r.s === 204, String(r.s));
}

console.log('\n=== un ajeno no puede tocarlo ===');
// La regla nueva para las directas es "participar basta", y hay que comprobar
// que sigue siendo una REGLA: sin esto, cualquiera con el id del chat podria
// cambiarle el temporizador a una conversacion que no es suya.
const carla = await reg('tc');
r = await call('PUT', `/v1/conversaciones/${NORMAL}/temporales`, carla.t, { segundos: 3600 });
ck('un tercero recibe 403 o 404, no 204', r.s === 403 || r.s === 404, String(r.s));
r = await call('GET', '/v1/conversaciones', ana.t);
ck('y el plazo no cambio',
   (r.b ?? []).find((c) => c.id === NORMAL)?.temporalesSegundos === 7776000,
   JSON.stringify((r.b ?? []).find((c) => c.id === NORMAL)?.temporalesSegundos));

console.log('\n=== en un grupo sigue siendo cosa de quien administra ===');
r = await call('POST', '/v1/conversaciones/grupo', ana.t, {
  nombre: 'Con reloj', usernames: [beto.user],
});
const GRUPO2 = r.b?.id;
r = await call('PUT', `/v1/conversaciones/${GRUPO2}/temporales`, ana.t, { segundos: 86400 });
ck('quien lo creo puede', r.s === 204, String(r.s));
r = await call('PUT', `/v1/conversaciones/${GRUPO2}/temporales`, beto.t, { segundos: 30 });
ck('un miembro raso no', r.s === 403, String(r.s));
r = await call('GET', '/v1/conversaciones', beto.t);
ck('pero SI lo ve: le afecta a sus mensajes',
   (r.b ?? []).find((c) => c.id === GRUPO2)?.temporalesSegundos === 86400,
   JSON.stringify((r.b ?? []).find((c) => c.id === GRUPO2)?.temporalesSegundos));

console.log('\n=== y el mensaje registrado sale con su vencimiento ===');
r = await call('POST', '/v1/mensajes', ana.t, {
  mensajeId: crypto.randomUUID(), conversacionId: GRUPO2,
});
ck('el registro devuelve expiraEn', r.s === 200 && r.b?.expiraEn > Date.now(),
   String(r.s) + ' ' + JSON.stringify(r.b?.expiraEn));
ck('y esta en la base',
   psql(`SELECT count(*) FROM mensaje_meta WHERE conversacion_id='${GRUPO2}' ` +
        `AND expira_en IS NOT NULL`) === '1');

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
