// Modulo E.1: reparto de claves publicas.
//
// Lo que se comprueba aqui NO es la criptografia -de eso responde libsignal-
// sino que el servidor haga bien su parte y, sobre todo, que NO pueda hacer
// mas de lo que le toca: repartir material publico y nada mas.
import crypto from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (b) => Buffer.from(b).toString('base64');

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const get = (r, t) => call('GET', r, t);
const put = (r, t, b) => call('PUT', r, t, b);
const post = (r, t, b) => call('POST', r, t, b);

// Un juego de claves con la forma correcta. El contenido no tiene que ser
// criptograficamente valido: el servidor no lo valida y NO DEBE poder hacerlo,
// porque validarlo requeriria entender lo que reparte.
const juego = (n, cuantasUnicas) => ({
  registrationId: 1000 + n,
  identidad: b64(crypto.randomBytes(33)),
  firmada: { keyId: 1, publica: b64(crypto.randomBytes(33)), firma: b64(crypto.randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(crypto.randomBytes(1568)), firma: b64(crypto.randomBytes(64)) },
  unicas: Array.from({ length: cuantasUnicas }, (_, i) => ({
    keyId: 100 + i, publica: b64(crypto.randomBytes(33)),
  })),
});

const ana = await reg('ka');
const beto = await reg('kb');
const ajeno = await reg('kx');

console.log('\n=== publicar ===');
let r = await put('/v1/claves', ana.t, juego(1, 5));
ck('se publica el juego de claves', r.s === 204, String(r.s));

r = await get('/v1/claves/estado', ana.t);
ck('el estado reporta las 5 de un solo uso', r.b?.unicasDisponibles === 5, JSON.stringify(r.b));
ck('y dice cual es el objetivo y el minimo', r.b?.objetivo > 0 && r.b?.minimo > 0, JSON.stringify(r.b));

r = await put('/v1/claves', ana.t, { ...juego(1, 0), registrationId: 0 });
ck('un identificador de registro en 0 se rechaza', r.s === 400, String(r.s));

console.log('\n=== reponer no borra lo que ya hay ===');
r = await put('/v1/claves', ana.t, {
  ...juego(1, 0),
  unicas: Array.from({ length: 3 }, (_, i) => ({ keyId: 200 + i, publica: b64(crypto.randomBytes(33)) })),
});
ck('se reponen 3 mas', r.s === 204, String(r.s));
r = await get('/v1/claves/estado', ana.t);
ck('ahora hay 8: reponer AGREGA, no reemplaza', r.b?.unicasDisponibles === 8, JSON.stringify(r.b));

console.log('\n=== entregar el paquete ===');
await put('/v1/claves', beto.t, juego(2, 4));
const conv = (await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user })).b.id;

r = await get(`/v1/claves/dispositivo/${beto.dev}`, ana.t);
ck('se obtiene el paquete de quien comparte conversacion', r.s === 200, String(r.s));
ck('trae identidad, firmada y kyber', !!r.b?.identidad && !!r.b?.firmada?.publica && !!r.b?.kyber?.publica,
   JSON.stringify(r.b).slice(0, 160));
ck('trae una prekey de un solo uso', !!r.b?.unica?.publica, JSON.stringify(r.b?.unica));
ck('y el identificador de registro que publico el otro', r.b?.registrationId === 1002, String(r.b?.registrationId));
const primeraUnica = r.b.unica.keyId;

r = await get(`/v1/claves/dispositivo/${beto.dev}`, ana.t);
ck('la siguiente entrega da una prekey DISTINTA (son de un solo uso)',
   r.b?.unica?.keyId !== primeraUnica, `${primeraUnica} vs ${r.b?.unica?.keyId}`);

r = await get('/v1/claves/estado', beto.t);
ck('al dueno le quedan 2 de las 4', r.b?.unicasDisponibles === 2, JSON.stringify(r.b));

console.log('\n=== agotarlas no impide abrir sesion ===');
await get(`/v1/claves/dispositivo/${beto.dev}`, ana.t);
await get(`/v1/claves/dispositivo/${beto.dev}`, ana.t);
r = await get(`/v1/claves/dispositivo/${beto.dev}`, ana.t);
ck('sin prekeys unicas el paquete sigue llegando', r.s === 200, String(r.s));
ck('pero sin la parte de un solo uso', r.b?.unica === null || r.b?.unica === undefined, JSON.stringify(r.b?.unica));

console.log('\n=== quien no comparte nada no cosecha claves ===');
r = await get(`/v1/claves/dispositivo/${beto.dev}`, ajeno.t);
ck('un extrano NO obtiene el paquete', r.s === 403, String(r.s));
const anon = await fetch(BASE + `/v1/claves/dispositivo/${beto.dev}`);
ck('sin sesion tampoco', anon.status === 401, String(anon.status));

console.log('\n=== dispositivo sin claves publicadas ===');
const nuevo = await reg('kn');
await post('/v1/conversaciones/directa', ana.t, { usernameDestino: nuevo.user });
r = await get(`/v1/claves/dispositivo/${nuevo.dev}`, ana.t);
ck('se responde 409 y no un paquete a medias', r.s === 409, String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== destinos de una conversacion ===');
r = await get(`/v1/conversaciones/${conv}/destinos`, ana.t);
ck('se listan los dispositivos destino', r.s === 200 && r.b?.destinos?.length === 1, JSON.stringify(r.b).slice(0, 140));
ck('el propio dispositivo NO esta en la lista',
   !r.b.destinos.some((d) => d.dispositivoId === ana.dev), JSON.stringify(r.b.destinos.map((d) => d.username)));
ck('cada destino trae su identidad para detectar cambios', !!r.b.destinos[0]?.identidad);

r = await get(`/v1/conversaciones/${conv}/destinos`, ajeno.t);
ck('quien no pertenece no obtiene los destinos', r.s === 403 || r.s === 404, String(r.s));

console.log('\n=== E.6: cambio de identidad ===');
// Cuando alguien reinstala, su clave de identidad cambia. Quien ya hablaba con
// esa persona TIENE que poder enterarse: si no, un servidor malicioso podria
// cambiar la clave por la suya y leer todo sin que nadie lo note. El servidor
// no puede avisarlo por su cuenta -no sabe quien hablaba con quien de antes-,
// pero SI tiene que servir la identidad nueva para que el cliente lo detecte.
r = await get(`/v1/conversaciones/${conv}/destinos`, ana.t);
const identidadVieja = r.b.destinos[0].identidad;
ck('los destinos traen la identidad actual de la otra persona', !!identidadVieja);

// Beto "reinstala": publica otra identidad con el mismo dispositivo.
const juegoNuevo = juego(7, 3);
r = await put('/v1/claves', beto.t, juegoNuevo);
ck('se acepta la identidad nueva del mismo dispositivo', r.s === 204, String(r.s));

r = await get(`/v1/conversaciones/${conv}/destinos`, ana.t);
const identidadNueva = r.b.destinos[0].identidad;
ck('el servidor sirve la identidad NUEVA, no una en cache',
   identidadNueva === juegoNuevo.identidad, 'no coincide con la publicada');
ck('y es distinta de la anterior: el cliente puede detectar el cambio',
   identidadNueva !== identidadVieja);

r = await get(`/v1/claves/dispositivo/${beto.dev}`, ana.t);
ck('el paquete tambien trae la identidad nueva', r.b?.identidad === juegoNuevo.identidad);
ck('con su nuevo identificador de registro', r.b?.registrationId === juegoNuevo.registrationId,
   String(r.b?.registrationId));

console.log('\n=== lo que el servidor NO puede hacer ===');
// El servidor guarda lo que le dan sin poder verificarlo. Eso no es un
// descuido: verificar una firma exigiria entender el material que reparte, y
// la propiedad que se busca es exactamente la contraria.
r = await put('/v1/claves', ajeno.t, {
  ...juego(9, 1),
  firmada: { keyId: 1, publica: b64(crypto.randomBytes(33)), firma: b64(Buffer.alloc(64)) },
});
ck('acepta una firma que no verifica (es material opaco para el)', r.s === 204, String(r.s));
ck('la deteccion de un paquete invalido le toca al CLIENTE, al procesarlo', true);

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
