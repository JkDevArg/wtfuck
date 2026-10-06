// Enlace de contacto (V48): "agregame" con un codigo al azar y revocable.
//
// Lo que se comprueba:
//  - crear, leer, cambiar y apagar el propio enlace; el viejo deja de servir;
//  - quien lo tiene encuentra a la cuenta aunque se haya ocultado de la
//    busqueda, y ve solo lo que esa cuenta le muestra a un desconocido;
//  - con "quien me escribe: conocidos" y SIN solicitudes, el enlace abre la
//    conversacion como SOLICITUD -nunca como normal-, y sin enlace sigue el
//    portazo; un enlace de OTRA persona no sirve de llave;
//  - un bloqueo gana: el enlace responde como uno que no existe;
//  - la pagina publica no dice de quien es.

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
const CLAVE = 'clave-larga-123';
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
async function call(m, ruta, t, body) {
  const r = await fetch(BASE + ruta, { method: m, headers: t ? H(t) : { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b, h: r.headers };
}
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);
const put = (r, t, b) => call('PUT', r, t, b);
const del = (r, t) => call('DELETE', r, t);
async function reg(u) {
  const r = await post('/v1/registro', null, {
    username: 'e' + S + u, password: CLAVE, etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-enlace-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { t: r.b?.token, id: r.b?.usuarioId, user: 'e' + S + u };
}
async function ajuste(quien, cambios) {
  const actual = (await get('/v1/perfil/privacidad', quien.t)).b;
  return put('/v1/perfil/privacidad', quien.t, { ...actual, ...cambios });
}

const ana = await reg('ana');      // la duena del enlace
const beto = await reg('beto');    // un desconocido que recibe el enlace
const carla = await reg('carla');  // otra desconocida, sin enlace
const dario = await reg('dario');  // tiene su propio enlace

console.log('\n=== el propio enlace ===');
let r = await get('/v1/perfil/enlace', ana.t);
ck('al principio no hay enlace', r.s === 200 && r.b?.codigo == null, JSON.stringify(r.b));
r = await post('/v1/perfil/enlace', ana.t);
const c1 = r.b?.codigo;
ck('crearlo devuelve un codigo de 22 caracteres', /^[A-Za-z0-9_-]{22}$/.test(c1 ?? ''), String(c1));
r = await get('/v1/perfil/enlace', ana.t);
ck('y queda guardado', r.b?.codigo === c1);
r = await get('/v1/perfil/enlace', beto.t);
ck('el de otra cuenta no se ve desde la mia', r.b?.codigo == null, JSON.stringify(r.b));
r = await get('/v1/perfil/enlace');
ck('sin sesion no se lee', r.s === 401, String(r.s));

console.log('\n=== encontrar con el enlace aunque este oculta ===');
await put('/v1/perfil', ana.t, { nombreMostrado: 'Ana Secreta', estadoTexto: '' });
ck('Ana tiene un nombre (que no le mostrara a desconocidos)',
  JSON.stringify((await get('/v1/perfil', ana.t)).b).includes('Ana Secreta'));
await ajuste(ana, { busqueda: 'nadie', nombre: 'conocidos', escribe: 'conocidos', solicitudes: false });
r = await get('/v1/usuarios/' + ana.user, beto.t);
ck('por usuario, Ana no aparece (se oculto)', r.s === 404, String(r.s));
r = await get('/v1/enlaces/' + c1, beto.t);
ck('con el enlace, Beto la encuentra', r.s === 200 && r.b?.username === ana.user, `${r.s} ${JSON.stringify(r.b).slice(0, 120)}`);
ck('y solo ve lo que Ana le muestra a un desconocido', !JSON.stringify(r.b).includes('Ana Secreta'), JSON.stringify(r.b?.nombreMostrado));
r = await get('/v1/enlaces/' + c1);
ck('sin sesion no se resuelve', r.s === 401, String(r.s));
r = await get('/v1/enlaces/' + 'A'.repeat(22), beto.t);
ck('un codigo inventado da 404', r.s === 404, String(r.s));
r = await get('/v1/enlaces/' + encodeURIComponent("' OR 1=1 --"), beto.t);
ck('uno con forma rara tambien, sin tocar la base', r.s === 404, String(r.s));

console.log('\n=== escribir por el enlace: como solicitud ===');
r = await post('/v1/conversaciones/directa', carla.t, { usernameDestino: ana.user });
ck('sin enlace, el portazo de siempre', r.s === 403, String(r.s));
r = await post('/v1/conversaciones/directa', carla.t, { usernameDestino: ana.user, enlace: (await post('/v1/perfil/enlace', dario.t)).b?.codigo });
ck('el enlace de OTRA persona no sirve de llave', r.s === 403, String(r.s));
r = await post('/v1/conversaciones/directa', beto.t, { usernameDestino: ana.user, enlace: c1 });
ck('con el enlace de Ana, la conversacion se abre', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 120)}`);
ck('pero como SOLICITUD: decide ella', r.b?.esSolicitud === true, JSON.stringify(r.b?.esSolicitud));

console.log('\n=== cambiarlo invalida el viejo ===');
r = await post('/v1/perfil/enlace', ana.t);
const c2 = r.b?.codigo;
ck('cambiarlo da otro codigo', !!c2 && c2 !== c1);
r = await get('/v1/enlaces/' + c1, carla.t);
ck('el viejo ya no lleva a nadie', r.s === 404, String(r.s));
r = await post('/v1/conversaciones/directa', carla.t, { usernameDestino: ana.user, enlace: c1 });
ck('ni sirve para escribir', r.s === 403, String(r.s));
r = await get('/v1/enlaces/' + c2, carla.t);
ck('el nuevo si', r.s === 200, String(r.s));

console.log('\n=== un bloqueo gana ===');
await post(`/v1/bloqueos/${carla.user}`, ana.t);
r = await get('/v1/enlaces/' + c2, carla.t);
ck('bloqueada, el enlace responde como si no existiera', r.s === 404, String(r.s));
r = await post('/v1/conversaciones/directa', carla.t, { usernameDestino: ana.user, enlace: c2 });
ck('y tampoco abre conversacion', r.s === 403, String(r.s));

console.log('\n=== apagarlo ===');
r = await del('/v1/perfil/enlace', ana.t);
ck('apagarlo responde 204', r.s === 204, String(r.s));
r = await get('/v1/enlaces/' + c2, beto.t);
ck('y deja de llevar a nadie', r.s === 404, String(r.s));
r = await get('/v1/perfil/enlace', ana.t);
ck('y ya no hay enlace', r.b?.codigo == null);

console.log('\n=== la pagina publica ===');
r = await call('GET', '/c/' + c2);
const html = String(r.b);
ck('se sirve sin sesion', r.s === 200 && html.includes('<!doctype html>'), String(r.s));
ck('ofrece abrir la app', html.includes('intent://c/' + c2));
ck('NO dice de quien es', !html.includes(ana.user));
ck('trae una politica de contenido cerrada', (r.h.get('content-security-policy') ?? '').includes("default-src 'none'"));
r = await call('GET', '/c/' + encodeURIComponent('<script>alert(1)</script>'));
ck('un codigo con forma rara no se refleja en la pagina', !String(r.b).includes('<script>'), String(r.b).slice(0, 80));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail ? 1 : 0);
