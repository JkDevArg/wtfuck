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

// El barrido corre cada 10 s en el servidor.
await new Promise((res) => setTimeout(res, 12000));

ck('la conversacion ya no esta',
   psql(`SELECT count(*) FROM conversacion WHERE id='${TEMP}'`) === '0');
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

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
