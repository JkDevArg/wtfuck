// W1 de la version web: el servidor acepta un navegador como aparato vinculado
// y sirve /web desde el mismo origen. Ver docs/12-VERSION-WEB.md.
//
// Lo que se comprueba:
//  - un navegador entra VINCULANDOSE con el codigo del principal (nivel
//    NAVEGADOR); su token sirve y la lista de aparatos lo muestra;
//  - su sesion vence a los 30 dias, y la de un telefono a los 90;
//  - un navegador NO puede registrarse, ni recuperar la cuenta, ni ser el
//    principal (tampoco la base lo deja), ni autorizar a otros;
//  - vuelve a entrar con usuario y contrasena desde el mismo navegador;
//  - revocarlo desde el telefono corta su sesion;
//  - /web sirve la pagina con CSP estricta y el resto de cabeceras, el .wasm
//    con su tipo, assets/ en cache para siempre e index.html sin cache;
//  - /web sin barra redirige, y salir de la carpeta no sirve nada.
//
// El servidor tiene que servir pruebas/web-de-prueba (lo hace
// pruebas/arrancar-servidor.ps1 si no existe web/app/dist).
import { execSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const CLAVE = 'clave-larga-123';
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
async function call(m, ruta, t, body) {
  const r = await fetch(BASE + ruta, {
    method: m,
    headers: t ? H(t) : { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}
const psql = (sql) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`,
  { stdio: 'pipe' },
).toString().trim();
const juego = (n) => ({
  registrationId: 4000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1569)), firma: b64(randomBytes(64)) },
  unicas: [{ keyId: 1, publica: b64(randomBytes(33)) }],
});
// Los dias que le quedan a la ultima sesion de un aparato.
const diasDeSesion = (dispositivo) => Number(psql(
  `SELECT round(extract(epoch FROM (expira_en - now())) / 86400) FROM sesion WHERE dispositivo_id = '${dispositivo}' ORDER BY expira_en DESC LIMIT 1`,
));

// W5 cambio esta regla: una cuenta PUEDE nacer en la web, pero solo con una
// invitacion web y un correo verificado (pruebas/registro-web.mjs). Sin eso,
// sigue sin poder.
console.log('\n=== un navegador no crea cuentas sin invitacion ni correo ===');
let r = await call('POST', '/v1/registro', null, {
  username: 'wn' + S, password: CLAVE, etiquetaDispositivo: 'Chrome',
  identidadPub: b64('k'), hardwareHash: b64('HW-NAV-REG-' + S), hardwareNivel: 'NAVEGADOR',
});
ck('registrarse desde un navegador sin nada: rechazado', r.s === 400 || r.s === 403, String(r.s));
ck('y dice que hace falta una invitacion', /invitacion/i.test(r.b?.motivo ?? ''), JSON.stringify(r.b));

r = await call('POST', '/v1/registro', null, {
  username: 'wa' + S, password: CLAVE, etiquetaDispositivo: 'telefono de ana',
  identidadPub: b64('ka'), hardwareHash: b64('HW-ANA-' + S), hardwareNivel: 'SOFTWARE_DEV',
});
const ana = { t: r.b.token, dev: r.b.dispositivoId, user: 'wa' + S };
ck('ana se registra en su telefono', r.s === 200 && !!ana.t, String(r.s));
await call('PUT', '/v1/claves', ana.t, juego(1));
ck('la sesion de un telefono dura 90 dias', diasDeSesion(ana.dev) === 90, String(diasDeSesion(ana.dev)));

console.log('\n=== el navegador entra vinculandose ===');
r = await call('POST', '/v1/dispositivos/codigo', ana.t, { password: CLAVE });
ck('el telefono emite el codigo', r.s === 200 && !!r.b?.codigo, String(r.s));
const HW_WEB = b64(randomBytes(32));
r = await call('POST', '/v1/dispositivos/vincular', null, {
  username: ana.user, codigo: r.b.codigo, etiquetaDispositivo: 'Chrome en Windows',
  identidadPub: b64(randomBytes(33)), hardwareHash: HW_WEB, hardwareNivel: 'NAVEGADOR',
});
ck('el navegador se vincula', r.s === 200 && !!r.b?.token, String(r.s) + JSON.stringify(r.b).slice(0, 120));
const web = { t: r.b.token, dev: r.b.dispositivoId };
await call('PUT', '/v1/claves', web.t, juego(2));

r = await call('GET', '/v1/dispositivos', web.t);
const yo = r.b?.dispositivos?.find((d) => d.esEste);
ck('su token sirve', r.s === 200, String(r.s));
ck('la lista lo muestra como NAVEGADOR', yo?.nivelHardware === 'NAVEGADOR', JSON.stringify(yo));
ck('y no es el principal', yo?.principal === false);
ck('su sesion dura 30 dias', diasDeSesion(web.dev) === 30, String(diasDeSesion(web.dev)));

console.log('\n=== un navegador nunca es el principal ===');
r = await call('POST', '/v1/dispositivos/codigo', web.t, { password: CLAVE });
ck('no puede autorizar a otros aparatos', r.s === 403, String(r.s));
r = await call('POST', `/v1/dispositivos/${web.dev}/principal`, ana.t, { password: CLAVE });
ck('el telefono no lo puede hacer principal: 409', r.s === 409, String(r.s));
ck('y dice por que', /navegador/i.test(r.b?.motivo ?? ''), JSON.stringify(r.b));
// La restriccion de la base (V49) se quito en V52, cuando una cuenta paso a
// poder nacer en la web: ahi el navegador ES el principal. Lo que se sigue
// impidiendo -un navegador vinculado que se pone por encima del telefono- lo
// hace `Dispositivos.promover`, comprobado arriba.

console.log('\n=== ni recupera la cuenta ===');
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: '+51900000000', codigo: '000000', verificadorB64: b64('x'),
  passwordNueva: 'una-contrasena-larga', etiquetaDispositivo: 'Chrome',
  identidadPub: b64('k'), hardwareHash: b64(randomBytes(32)), hardwareNivel: 'NAVEGADOR',
});
ck('recuperar desde un navegador: 403', r.s === 403, String(r.s));
ck('con el motivo', /navegador/i.test(r.b?.motivo ?? ''), JSON.stringify(r.b));

console.log('\n=== vuelve a entrar desde el mismo navegador ===');
r = await call('POST', '/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: HW_WEB });
ck('con usuario, contrasena y el mismo hardwareHash', r.s === 200 && r.b?.dispositivoId === web.dev, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('y la sesion nueva tambien dura 30 dias', diasDeSesion(web.dev) === 30, String(diasDeSesion(web.dev)));
r = await call('POST', '/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: b64(randomBytes(32)) });
ck('desde otro navegador sin vincular: 403', r.s === 403, String(r.s));

console.log('\n=== el telefono lo revoca ===');
// Con el socket abierto: revocar tiene que cortarlo en el acto, con 1008
// ("ya no estas vinculado"), y no esperar a que el aparato reconecte.
const socketWeb = new WebSocket(BASE.replace('http', 'ws') + '/v1/ws?token=' + encodeURIComponent(web.t));
await new Promise((res, rej) => { socketWeb.onopen = res; socketWeb.onerror = rej; setTimeout(rej, 5000); });
const cierre = new Promise((res) => { socketWeb.onclose = (e) => res(e.code); setTimeout(() => res('sigue abierto'), 5000); });
r = await call('DELETE', `/v1/dispositivos/${web.dev}`, ana.t);
ck('revocar el navegador', r.s === 204, String(r.s));
const codigoCierre = await cierre;
ck('su socket abierto se cierra en el acto, con 1008', codigoCierre === 1008, String(codigoCierre));
r = await call('GET', '/v1/dispositivos', web.t);
ck('y su token deja de servir', r.s === 401, String(r.s));

console.log('\n=== /web, mismo origen ===');
r = await fetch(BASE + '/web', { redirect: 'manual' });
ck('/web sin barra redirige a /web/', [301, 308].includes(r.status) && (r.headers.get('location') ?? '').endsWith('/web/'),
   `${r.status} ${r.headers.get('location')}`);

r = await fetch(BASE + '/web/');
const html = await r.text();
const csp = r.headers.get('content-security-policy') ?? '';
ck('/web/ sirve la pagina', r.status === 200 && html.includes('wtfuck web'), String(r.status));
// Sirve la app armada (web/app/dist) o la pagina minima de pruebas: el script
// se saca del propio index.html, y el .wasm, de ese script.
const script = (html.match(/\/web\/assets\/[\w.-]+\.js/) ?? [])[0];
ck('index.html carga un script de assets/', !!script, html.slice(0, 200));
ck('como HTML', (r.headers.get('content-type') ?? '').startsWith('text/html'), r.headers.get('content-type'));
ck('CSP: scripts solo de aqui, y compilar WebAssembly', csp.includes("script-src 'self' 'wasm-unsafe-eval'"), csp);
ck('CSP: nada por defecto', csp.includes("default-src 'none'"), csp);
ck('CSP: no se deja meter en un iframe', csp.includes("frame-ancestors 'none'"), csp);
ck('CSP: sin unsafe-inline ni unsafe-eval', !csp.includes('unsafe-inline') && !/'unsafe-eval'/.test(csp), csp);
// W3: la pagina sube y baja los adjuntos DIRECTO del almacen con las URLs
// firmadas. Si la CSP no lo nombrara, los adjuntos fallarian en silencio.
const conectar = (csp.match(/connect-src ([^;]+)/) ?? [])[1] ?? '';
ck('CSP: connect-src permite el almacen de adjuntos', /https?:\/\/[^\s']+/.test(conectar), conectar);
ck('nosniff', r.headers.get('x-content-type-options') === 'nosniff');
ck('sin referer', r.headers.get('referrer-policy') === 'no-referrer');
ck('aislada de otras ventanas (COOP)', r.headers.get('cross-origin-opener-policy') === 'same-origin');
ck('index.html no se guarda en cache', r.headers.get('cache-control') === 'no-cache', r.headers.get('cache-control'));

r = await fetch(BASE + script);
const js = await r.text();
ck('assets/: el script se sirve', r.status === 200 && (r.headers.get('content-type') ?? '').includes('javascript'), `${r.status} ${r.headers.get('content-type')}`);
ck('assets/: en cache para siempre', (r.headers.get('cache-control') ?? '').includes('immutable'), r.headers.get('cache-control'));
ck('assets/: tambien con CSP', (r.headers.get('content-security-policy') ?? '').length > 0);

const wasm = (js.match(/[\w.-]+\.wasm/) ?? [])[0];
ck('el script nombra su WebAssembly', !!wasm);
r = await fetch(BASE + '/web/assets/' + wasm);
ck('el .wasm sale como application/wasm', r.status === 200 && r.headers.get('content-type') === 'application/wasm', `${r.status} ${r.headers.get('content-type')}`);

r = await fetch(BASE + '/web/no-existe.js');
ck('lo que no existe: 404', r.status === 404, String(r.status));
for (const ruta of ['/web/%2e%2e/settings.gradle.kts', '/web/..%2fsettings.gradle.kts', '/web/%2e%2e%2f%2e%2e%2fsettings.gradle.kts']) {
  r = await fetch(BASE + ruta);
  const cuerpo = await r.text();
  ck(`salir de la carpeta no sirve nada: ${ruta}`, r.status !== 200 && !cuerpo.includes('include('), `${r.status}`);
}

// W4c. El service worker solo existe en la app armada (web/app/dist), no en
// la pagina minima de `web-de-prueba`.
r = await fetch(BASE + '/web/sw.js');
if (r.status === 200) {
  const sw = await r.text();
  ck('sw.js se sirve como JavaScript', /javascript/.test(r.headers.get('content-type') ?? ''), r.headers.get('content-type'));
  ck('sw.js sin cache: una version nueva del servidor se toma al recargar', r.headers.get('cache-control') === 'no-cache', r.headers.get('cache-control'));
  ck('sw.js no intercepta peticiones (sin fetch): la pagina viene siempre del servidor',
     !/addEventListener\(\s*['"]fetch/.test(sw) && !/caches\./.test(sw));
  ck('la CSP deja registrar el worker solo desde el mismo origen', /worker-src 'self'/.test(r.headers.get('content-security-policy') ?? ''));
} else {
  console.log('    (sin sw.js: se esta sirviendo la pagina de prueba, no la app armada)');
}

r = await fetch(BASE + '/v1/version');
ck('la API sigue igual al lado', r.status === 200);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail ? 1 : 0);
