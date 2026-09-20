// Modulo J: varios dispositivos por cuenta.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que el FAN-OUT sea por dispositivo. Es la prueba de que el diseno del
//     modulo E valia: al enviar, `destinos` tiene que devolver TODOS los
//     dispositivos de todos los participantes, incluidos los OTROS mios -para
//     que mi tablet vea lo que escribi en el telefono- y excluido el que envia.
//  2. Que las dos reglas de V1 se separaron bien: una cuenta puede tener varios
//     dispositivos, pero un telefono NO puede tener dos cuentas.
//  3. Que solo el PRINCIPAL autoriza, y con contrasena. Si cualquiera pudiera,
//     robar un secundario alcanzaria para vincular mas.
//  4. Que el principal no se pueda revocar a si mismo: dejaria la cuenta sin
//     quien autorice y eso no se recupera.
//  5. Que el codigo tenga tope de intentos, vida corta y un solo uso.
//  6. Que revocar mate las sesiones del aparato revocado de inmediato.
import { execSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

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
      username: u + S, password: CLAVE, etiquetaDispositivo: 'principal de ' + u,
      identidadPub: b64('k' + u), hardwareHash: b64(hw), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S, hw: b64(hw) };
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
const del = (r, t, b) => call('DELETE', r, t, b);

const psql = (sql) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`,
  { stdio: 'pipe' },
).toString().trim();

// Juego de claves, para que un dispositivo pueda RECIBIR. Sin esto no tiene
// con que lo cifren y no entra en la lista de destinos.
const juego = (n) => ({
  registrationId: 3000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1568)), firma: b64(randomBytes(64)) },
  unicas: Array.from({ length: 4 }, (_, i) => ({ keyId: 100 + i, publica: b64(randomBytes(33)) })),
});

const ana = await reg('ja', 'HW-ANA-' + S);
const beto = await reg('jb', 'HW-BETO-' + S);
await call('PUT', '/v1/claves', ana.t, juego(1));
await call('PUT', '/v1/claves', beto.t, juego(2));

console.log('\n=== el registro deja un dispositivo principal ===');
let r = await get('/v1/dispositivos', ana.t);
ck('se lista un dispositivo', r.s === 200 && r.b.dispositivos.length === 1,
   JSON.stringify(r.b).slice(0, 160));
ck('y es el principal', r.b.dispositivos[0].principal === true);
ck('y es este', r.b.dispositivos[0].esEste === true);
ck('sin nadie que lo haya vinculado: se autorizo al registrarse',
   r.b.dispositivos[0].vinculadoPor == null, JSON.stringify(r.b.dispositivos[0]));
ck('con claves publicadas', r.b.dispositivos[0].tieneClaves === true);

console.log('\n=== solo el principal autoriza, y con contrasena ===');
r = await post('/v1/dispositivos/codigo', ana.t, { password: 'equivocada' });
ck('con la contrasena mal no se emite codigo', r.s === 403, String(r.s));

r = await post('/v1/dispositivos/codigo', ana.t, { password: CLAVE });
ck('el principal emite el codigo', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('el codigo tiene forma de teclearse (XXXX-XXXX)', /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(r.b.codigo || ''),
   r.b.codigo);
ck('sin caracteres que se confunden', !/[01OIL]/.test((r.b.codigo || '').replace('-', '')), r.b.codigo);
ck('dice quien lo emitio', (r.b.emitidoPor || '').includes('principal'), r.b.emitidoPor);
ck('y cuanto vive', r.b.expiraEnSegundos === 300, String(r.b.expiraEnSegundos));
const COD = r.b.codigo;

console.log('\n=== el codigo no esta en claro en la base ===');
ck('la base guarda solo el hash', psql(
  `SELECT count(*) FROM codigo_vinculacion WHERE codigo_hash::text LIKE '%${COD.slice(0, 4)}%'`,
) === '0');

console.log('\n=== J.2 vincular el segundo dispositivo ===');
const HW2 = b64('HW-ANA2-' + S);
r = await post('/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: 'AAAA-AAAA', etiquetaDispositivo: 'tablet',
  identidadPub: b64('k2'), hardwareHash: HW2, hardwareNivel: 'SOFTWARE_DEV',
});
ck('un codigo que no coincide se rechaza', r.s === 400, String(r.s));
ck('y dice cuantos intentos quedan', (r.b?.motivo || '').includes('intentos'), JSON.stringify(r.b));

r = await post('/v1/dispositivos/vincular', null, {
  username: 'noexiste' + S, codigo: COD, etiquetaDispositivo: 'tablet',
  identidadPub: b64('k2'), hardwareHash: HW2, hardwareNivel: 'SOFTWARE_DEV',
});
ck('un usuario que no existe da el MISMO mensaje que un codigo malo',
   r.s === 400 && (r.b?.motivo || '').includes('no existe o ya vencio'),
   JSON.stringify(r.b).slice(0, 110));

r = await post('/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: COD, etiquetaDispositivo: 'tablet de ana',
  identidadPub: b64('k2'), hardwareHash: HW2, hardwareNivel: 'SOFTWARE_DEV',
});
ck('con el codigo correcto se vincula', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 140));
ck('y devuelve sesion propia', typeof r.b.token === 'string' && r.b.token.length > 20);
ck('dice que la cuenta tiene dos dispositivos', r.b.dispositivos === 2, String(r.b.dispositivos));
const ana2 = { t: r.b.token, dev: r.b.dispositivoId };

r = await post('/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: COD, etiquetaDispositivo: 'otra',
  identidadPub: b64('k3'), hardwareHash: b64('HW-ANA3-' + S), hardwareNivel: 'SOFTWARE_DEV',
});
ck('el mismo codigo no sirve dos veces', r.s === 400, String(r.s));

r = await get('/v1/dispositivos', ana.t);
ck('ahora el principal ve los dos', r.b.dispositivos.length === 2, String(r.b.dispositivos.length));
const tablet = r.b.dispositivos.find((d) => !d.principal);
ck('el segundo NO es principal', !!tablet && tablet.principal === false);
ck('y dice quien lo autorizo', (tablet?.vinculadoPor || '').includes('principal'), tablet?.vinculadoPor);
ck('recien vinculado NO tiene claves: por eso todavia no puede recibir',
   tablet?.tieneClaves === false, JSON.stringify(tablet));

console.log('\n=== un secundario no puede autorizar otros ===');
r = await post('/v1/dispositivos/codigo', ana2.t, { password: CLAVE });
ck('el secundario no emite codigos', r.s === 403, String(r.s));
ck('y dice que hay que usar el principal', (r.b?.motivo || '').includes('principal'),
   JSON.stringify(r.b).slice(0, 120));

console.log('\n=== las dos reglas de V1 eran distintas ===');
// Una cuenta puede tener varios dispositivos...
ck('una cuenta con dos dispositivos activos existe',
   psql(`SELECT count(*) FROM dispositivo d JOIN usuario u ON u.id=d.usuario_id WHERE u.username='${ana.user}' AND d.revocado_en IS NULL`) === '2');

// ...pero un telefono NO puede tener dos cuentas.
r = await post('/v1/dispositivos/codigo', beto.t, { password: CLAVE });
const CODB = r.b.codigo;
r = await post('/v1/dispositivos/vincular', null, {
  username: beto.user, codigo: CODB, etiquetaDispositivo: 'la tablet de ana',
  identidadPub: b64('k4'), hardwareHash: HW2, hardwareNivel: 'SOFTWARE_DEV',
});
ck('un hardware ya usado por otra cuenta se rechaza', r.s === 409, String(r.s));
ck('y lo dice con esas palabras', (r.b?.motivo || '').includes('una cuenta'),
   JSON.stringify(r.b).slice(0, 120));

r = await post('/v1/dispositivos/codigo', ana.t, { password: CLAVE });
r = await post('/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: r.b.codigo, etiquetaDispositivo: 'repetida',
  identidadPub: b64('k5'), hardwareHash: HW2, hardwareNivel: 'SOFTWARE_DEV',
});
ck('vincular dos veces el MISMO telefono a la MISMA cuenta tambien se rechaza',
   r.s === 409 && (r.b?.motivo || '').includes('ya esta vinculado'),
   String(r.s) + JSON.stringify(r.b).slice(0, 110));

console.log('\n=== J.3 el fan-out es por DISPOSITIVO ===');
// La tablet publica sus claves: sin eso no puede recibir nada.
r = await call('PUT', '/v1/claves', ana2.t, juego(9));
ck('la tablet publica sus claves', r.s === 204, String(r.s));

r = await get('/v1/dispositivos', ana.t);
ck('y ahora la lista dice que SI puede recibir',
   r.b.dispositivos.find((d) => d.id === ana2.dev)?.tieneClaves === true);

const conv = (await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user })).b.id;

// Desde el telefono de ana: los destinos tienen que ser beto Y la tablet de
// ana, y NO el telefono que envia.
r = await get(`/v1/conversaciones/${conv}/destinos`, ana.t);
const desdeTelefono = r.b.destinos || [];
ck('al enviar hay DOS destinos: el otro y mi propia tablet', desdeTelefono.length === 2,
   JSON.stringify(desdeTelefono.map((d) => d.username)));
ck('uno es beto', desdeTelefono.some((d) => d.dispositivoId === beto.dev));
ck('el otro es MI tablet: es lo que hace que mi otro aparato vea lo que escribo',
   desdeTelefono.some((d) => d.dispositivoId === ana2.dev));
ck('y el que envia NO esta en sus propios destinos',
   !desdeTelefono.some((d) => d.dispositivoId === ana.dev));

// Y al reves, desde la tablet.
r = await get(`/v1/conversaciones/${conv}/destinos`, ana2.t);
const desdeTablet = r.b.destinos || [];
ck('desde la tablet tambien hay dos', desdeTablet.length === 2,
   JSON.stringify(desdeTablet.map((d) => d.dispositivoId)));
ck('y ahora el excluido es la tablet', !desdeTablet.some((d) => d.dispositivoId === ana2.dev));

// Beto ve los DOS dispositivos de ana: es lo que le obliga a cifrar dos copias.
r = await get(`/v1/conversaciones/${conv}/destinos`, beto.t);
const desdeBeto = r.b.destinos || [];
ck('beto ve los dos dispositivos de ana: dos copias cifradas, no una',
   desdeBeto.filter((d) => d.username === ana.user).length === 2,
   JSON.stringify(desdeBeto.map((d) => d.username)));
ck('cada uno con su propio registrationId', new Set(desdeBeto.map((d) => d.registrationId)).size === desdeBeto.length,
   JSON.stringify(desdeBeto.map((d) => d.registrationId)));
ck('y con su propia identidad: la huella es por dispositivo, no por persona',
   new Set(desdeBeto.map((d) => d.identidad)).size === desdeBeto.length);

console.log('\n=== una persona con dos aparatos se lista UNA vez ===');
// El JOIN a `dispositivo` venia de cuando un usuario tenia exactamente uno.
// Con varios multiplicaba filas y la misma persona aparecia dos veces en cada
// grupo; con cero desaparecia y el chat quedaba "(sin participantes)".
r = await get('/v1/conversaciones', beto.t);
const directa = (r.b || []).find((cv) => cv.id === conv);
const veces = (directa?.participantes || []).filter((u) => u.username === ana.user).length;
ck('ana tiene dos dispositivos pero aparece una sola vez', veces === 1, String(veces));
ck('y con un dispositivo asignado: el principal',
   (directa?.participantes || []).find((u) => u.username === ana.user)?.dispositivoId === ana.dev,
   JSON.stringify((directa?.participantes || []).map((u) => u.username)));

// Y al reves: alguien sin ningun dispositivo activo NO desaparece de la lista.
psql(`UPDATE dispositivo SET revocado_en=now() WHERE usuario_id=(SELECT id FROM usuario WHERE username='${beto.user}')`);
r = await get('/v1/conversaciones', ana.t);
const desdeAna = (r.b || []).find((cv) => cv.id === conv);
ck('alguien que revoco su unico aparato sigue en la lista de participantes',
   (desdeAna?.participantes || []).some((u) => u.username === beto.user),
   JSON.stringify((desdeAna?.participantes || []).map((u) => u.username)));
ck('aunque sin dispositivo asignado',
   (desdeAna?.participantes || []).find((u) => u.username === beto.user)?.dispositivoId === '',
   JSON.stringify((desdeAna?.participantes || []).find((u) => u.username === beto.user)));
// Se deja como estaba para el resto de la suite.
psql(`UPDATE dispositivo SET revocado_en=NULL WHERE usuario_id=(SELECT id FROM usuario WHERE username='${beto.user}') AND revocado_en > now() - interval '1 minute'`);

console.log('\n=== J.4 el historial no lo tiene el servidor ===');
r = await post('/v1/dispositivos/historial', ana2.t);
ck('pedir historial responde', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 110));
ck('y dice que SI hay quien responda', r.b.hayQuienResponda === true, JSON.stringify(r.b));
ck('pero todavia no recibio nada', r.b.recibido === false);

r = await post(`/v1/dispositivos/${ana.dev}/historial-enviado?n=37`, ana2.t);
ck('el que reenvia puede anotarlo', r.s === 204, String(r.s));
r = await post('/v1/dispositivos/historial', ana.t);
ck('y el que lo recibio lo ve reflejado', r.b.recibido === true && r.b.cuantos === 37,
   JSON.stringify(r.b));

r = await post(`/v1/dispositivos/${beto.dev}/historial-enviado?n=5`, ana2.t);
ck('no se puede anotar historial hacia un dispositivo ajeno', r.s === 403, String(r.s));

// Una cuenta con un solo dispositivo no tiene a quien pedirle.
r = await post('/v1/dispositivos/historial', beto.t);
ck('con un solo dispositivo no hay quien responda: arranca vacio y se dice',
   r.b.hayQuienResponda === false, JSON.stringify(r.b));

console.log('\n=== revocar ===');
r = await del(`/v1/dispositivos/${ana.dev}`, ana.t);
ck('el principal NO se puede revocar a si mismo', r.s === 409, String(r.s));
ck('y explica por que', (r.b?.motivo || '').includes('quien autorice'),
   JSON.stringify(r.b).slice(0, 130));

r = await del(`/v1/dispositivos/${beto.dev}`, ana.t);
ck('no se puede revocar un dispositivo de otra cuenta', r.s === 404, String(r.s));

r = await del(`/v1/dispositivos/${ana2.dev}`, ana.t);
ck('el principal revoca el secundario', r.s === 204, String(r.s));

r = await get('/v1/dispositivos', ana2.t);
ck('la sesion del revocado muere de inmediato', r.s === 401, String(r.s));

r = await get(`/v1/conversaciones/${conv}/destinos`, beto.t);
ck('y beto vuelve a ver un solo dispositivo de ana',
   (r.b.destinos || []).filter((d) => d.username === ana.user).length === 1,
   JSON.stringify((r.b.destinos || []).map((d) => d.username)));

ck('las prekeys del revocado se borran: nadie puede abrir sesion contra un aparato que no existe',
   psql(`SELECT count(*) FROM prekey_unica WHERE dispositivo_id='${ana2.dev}'`) === '0');

console.log('\n=== el hardware revocado se libera ===');
r = await post('/v1/dispositivos/codigo', ana.t, { password: CLAVE });
r = await post('/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: r.b.codigo, etiquetaDispositivo: 'tablet otra vez',
  identidadPub: b64('k6'), hardwareHash: HW2, hardwareNivel: 'SOFTWARE_DEV',
});
ck('un telefono revocado se puede volver a vincular: el indice es parcial',
   r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 110));
const ana3 = { t: r.b.token, dev: r.b.dispositivoId };

console.log('\n=== promover a principal ===');
r = await post(`/v1/dispositivos/${ana3.dev}/principal`, ana.t, { password: 'equivocada' });
ck('promover exige la contrasena', r.s === 403, String(r.s));

r = await post(`/v1/dispositivos/${ana3.dev}/principal`, ana.t, { password: CLAVE });
ck('se promueve el secundario', r.s === 204, String(r.s));

ck('y queda UN solo principal: lo garantiza la base',
   psql(`SELECT count(*) FROM dispositivo d JOIN usuario u ON u.id=d.usuario_id WHERE u.username='${ana.user}' AND d.principal AND d.revocado_en IS NULL`) === '1');

r = await post('/v1/dispositivos/codigo', ana3.t, { password: CLAVE });
ck('el nuevo principal ya puede autorizar', r.s === 200, String(r.s));
r = await post('/v1/dispositivos/codigo', ana.t, { password: CLAVE });
ck('y el viejo ya no', r.s === 403, String(r.s));

console.log('\n=== ingresar desde el segundo telefono ===');
r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: HW2 });
ck('se puede ingresar desde el segundo hardware', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 110));
ck('y la sesion es la de ESE dispositivo, no la del primero', r.b.dispositivoId === ana3.dev,
   r.b.dispositivoId + ' vs ' + ana3.dev);

r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: b64('HW-AJENO-' + S) });
ck('desde un telefono sin vincular no se entra', r.s === 403, String(r.s));
ck('y se dice que hay que vincularlo, no que la clave este mal',
   (r.b?.motivo || '').includes('vinculado'), JSON.stringify(r.b).slice(0, 130));

console.log('\n=== tope de dispositivos ===');
// Se llena la cuenta de beto hasta el tope.
let ultimo = 0;
for (let i = 0; i < 10; i++) {
  const c1 = await post('/v1/dispositivos/codigo', beto.t, { password: CLAVE });
  if (c1.s !== 200) { ultimo = c1.s; break; }
  const v = await post('/v1/dispositivos/vincular', null, {
    username: beto.user, codigo: c1.b.codigo, etiquetaDispositivo: 'd' + i,
    identidadPub: b64('kb' + i), hardwareHash: b64('HW-BETO-' + i + '-' + S),
    hardwareNivel: 'SOFTWARE_DEV',
  });
  if (v.s !== 200) { ultimo = v.s; break; }
}
ck('al llegar al tope se rechaza con 409, no con un 500', ultimo === 409, String(ultimo));
const cuantos = psql(`SELECT count(*) FROM dispositivo d JOIN usuario u ON u.id=d.usuario_id WHERE u.username='${beto.user}' AND d.revocado_en IS NULL`);
ck('y el tope es 8', cuantos === '8', cuantos);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
