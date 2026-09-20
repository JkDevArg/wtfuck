// El intermediario de GIFs: el unico sitio donde el servidor sale a internet.
//
// ## Que se comprueba y por que se puede sin clave de Giphy
//
// El `{id}` de `GET /v1/gifs/{id}/bytes` termina interpolado en una URL hacia
// api.giphy.com **con la clave de la API pegada detras**, y del otro lado, en
// el telefono, dentro de un nombre de archivo. Es entrada de usuario: cualquiera
// con sesion pide ese camino con lo que se le ocurra.
//
// La comprobacion de forma va ANTES de mirar si hay clave configurada, y eso es
// lo que hace la suite ejecutable en este entorno, donde `WTFUCK_GIPHY_KEY` no
// esta puesta. La pareja de respuestas es la prueba:
//
//   - un id con forma rara  -> 400, y el servidor no llamo a nadie
//   - un id con forma buena -> 404, porque no hay clave
//
// Antes del arreglo los DOS daban 404: el servidor no distinguia "eso no es un
// id" de "no lo encontre", porque no miraba el id. Esa es exactamente la
// diferencia que esta suite fija.
//
// ## Lo que NO se puede comprobar aqui, dicho
//
// Con la clave puesta habria que comprobar ademas que la URL de descarga que
// devuelve Giphy pasa por la lista blanca de hosts. Eso vive en `GifsTest.kt`
// como prueba de unidad, porque exige respuestas de Giphy y no una peticion a
// este servidor.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

async function reg(u) {
  const nom = u + S;
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: nom, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + nom), hardwareHash: b64('HW-' + nom), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, user: nom };
}

const pedir = async (ruta, token) => {
  const r = await fetch(BASE + ruta, { headers: { Authorization: 'Bearer ' + token } });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};

const yo = await reg('g');

console.log('\n=== la ruta exige sesion ===');
const sin = await fetch(BASE + '/v1/gifs/abc/bytes');
ck('sin token no se puede ni preguntar', sin.status === 401, String(sin.status));

console.log('\n=== un id con forma buena: 404 porque no hay clave ===');
// Esta es la mitad que da sentido a la otra. Si un id valido diera tambien 400,
// la suite estaria pasando por el motivo equivocado.
const bueno = await pedir('/v1/gifs/l0HlBO7eyXzSZkJri/bytes', yo.t);
ck('un id bien formado NO se rechaza por forma',
   bueno.s !== 400, `respondio ${bueno.s} ${JSON.stringify(bueno.b).slice(0, 90)}`);
ck('y sin clave configurada da 404', bueno.s === 404, String(bueno.s));

console.log('\n=== ids que no son ids: 400 antes de llamar a nadie ===');
const HOSTILES = [
  ['recorrido de rutas', '..%2F..%2F..%2Fetc'],
  ['barra dentro', 'a%2Fb'],
  ['query inyectada', 'abc%3Fx%3D1'],
  ['ampersand', 'abc%26limit%3D99'],
  ['fragmento, que cortaria la clave', 'abc%23'],
  ['espacio', 'abc%20def'],
  ['arroba, por si cambiara el host', 'abc%40evil.example'],
  ['dos puntos', 'abc%3A1'],
  ['punto, que daria extension al archivo', 'abc.gif'],
  ['guion bajo y signos', 'abc%2B%2Fd'],
  ['larguisimo', 'a'.repeat(200)],
];

for (const [que, id] of HOSTILES) {
  const r = await pedir(`/v1/gifs/${id}/bytes`, yo.t);
  ck(`${que} da 400`, r.s === 400, `respondio ${r.s} ${JSON.stringify(r.b).slice(0, 80)}`);
}

console.log('\n=== el `..` a secas lo para una capa que no escribimos ===');
// `/v1/gifs/../bytes` no llega nunca al handler: el enrutador normaliza el
// camino a `/v1/bytes`, que no existe, y contesta 404 por su cuenta.
//
// Se deja escrito porque la primera version de esta suite esperaba 400 y
// fallaba. La diferencia importa para entender que se esta probando: en ese
// caso la defensa no es la validacion del id sino el enrutador, y conviene
// saber cual de las dos esta actuando. Lo que se exige aqui es lo unico que
// importa de verdad -que no se llame a Giphy y no se devuelvan bytes-, y eso
// se cumple por los dos caminos.
const puntos = await pedir('/v1/gifs/../bytes', yo.t);
ck('un `..` a secas no devuelve contenido', puntos.s === 404 || puntos.s === 400,
   `respondio ${puntos.s}`);

console.log('\n=== y lo dice, sin filtrar como se arma la peticion ===');
const r400 = await pedir('/v1/gifs/..%2F..%2Fx/bytes', yo.t);
const motivo = r400.b?.motivo ?? '';
ck('el motivo explica que el identificador no vale',
   /identificador/i.test(motivo), motivo);
ck('y NO menciona giphy, la clave ni la URL interna',
   !/giphy|api_key|http/i.test(motivo), motivo);

console.log('\n=== el buscador sin clave lo dice en vez de fingir ===');
const busq = await pedir('/v1/gifs/buscar?q=gato', yo.t);
ck('el buscador contesta 200', busq.s === 200, String(busq.s));
ck('con un aviso de que no esta configurado',
   (busq.b?.aviso || '').length > 0, JSON.stringify(busq.b).slice(0, 120));
ck('y sin resultados inventados',
   (busq.b?.resultados || []).length === 0, JSON.stringify(busq.b?.resultados || []).slice(0, 90));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
