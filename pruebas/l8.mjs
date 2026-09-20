// L.8: la consola web de administracion.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que un token de consola NO abra las rutas de mensajes. Es la linea que
//     hace que pegar esto en un navegador no sea pegar una sesion entera.
//  2. Que emitirlo exija la contrasena otra vez, y ser staff.
//  3. Que una consola no pueda emitir otra consola: la raiz de confianza es
//     el telefono y eso solo se sostiene si el acceso no se encadena.
//  4. Que caduque, que se pueda revocar, y que revocarla corte al instante.
import { execSync } from 'node:child_process';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 'telefono',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S };
}
const call = async (m, ruta, auth, body) => {
  const r = await fetch(BASE + ruta, {
    method: m,
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const conSesion = (t) => 'Bearer ' + t;
const conConsola = (t) => 'Consola ' + t;

const sembrar = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

const jefe = await reg('ca');
sembrar(jefe.user, 100);
const nadie = await reg('cb');

console.log('\n=== la pagina se sirve sin autenticar, porque no lleva datos ===');
let r = await fetch(BASE + '/consola');
const html = await r.text();
ck('la consola responde', r.status === 200, String(r.status));
ck('y es HTML', (r.headers.get('content-type') || '').includes('text/html'));
ck('sin ningun dato dentro: solo pide el token',
   html.includes('Token de consola') && !html.includes('Bearer '), String(html.length));

console.log('\n=== emitir ===');
r = await call('POST', '/v1/panel/consola', conSesion(nadie.t), { password: 'clave-larga-123' });
ck('quien no es staff no puede emitir una consola', r.s === 404, String(r.s));

r = await call('POST', '/v1/panel/consola', conSesion(jefe.t), { password: 'incorrecta-123' });
ck('con la contrasena mal, no se emite', r.s === 403, String(r.s));

r = await call('POST', '/v1/panel/consola', conSesion(jefe.t), {
  password: 'clave-larga-123', etiqueta: 'Laptop de la oficina',
});
ck('el staff la emite con su contrasena', r.s === 200, JSON.stringify(r.b).slice(0, 120));
const CONSOLA = r.b.token;
ck('el token viene una sola vez y no es corto', (CONSOLA || '').length >= 20);
ck('con su vencimiento en horas', r.b.expiraEnSegundos === 8 * 3600, String(r.b.expiraEnSegundos));

console.log('\n=== la consola abre el panel ===');
r = await call('GET', '/v1/panel/resumen', conConsola(CONSOLA));
ck('ve el resumen', r.s === 200, String(r.s));
ck('con su nivel, para que la interfaz sepa que ofrecer', r.b.miNivel === 100, String(r.b.miNivel));

r = await call('GET', '/v1/moderacion/cola', conConsola(CONSOLA));
ck('y la cola de denuncias: sin eso no serviria para moderar', r.s === 200, String(r.s));

r = await call('GET', '/v1/panel/bitacora', conConsola(CONSOLA));
ck('y la bitacora', r.s === 200, String(r.s));

console.log('\n=== lo que la consola NO puede hacer ===');
r = await call('GET', '/v1/conversaciones', conConsola(CONSOLA));
ck('no lista conversaciones', r.s === 401, String(r.s));

r = await call('POST', '/v1/mensajes', conConsola(CONSOLA), {
  mensajeId: crypto.randomUUID(), conversacionId: crypto.randomUUID(),
});
ck('no manda mensajes', r.s === 401, String(r.s));

r = await call('GET', '/v1/perfil', conConsola(CONSOLA));
ck('ni lee el perfil', r.s === 401, String(r.s));

r = await call('POST', '/v1/panel/consola', conConsola(CONSOLA), { password: 'clave-larga-123' });
ck('y no puede emitir OTRA consola: el acceso no se encadena', r.s === 401, String(r.s));

r = await call('GET', '/v1/panel/consola', conConsola(CONSOLA));
ck('ni listar las consolas abiertas', r.s === 401, String(r.s));

console.log('\n=== emitirla queda anotado ===');
r = await call('GET', '/v1/panel/bitacora?q=consola', conSesion(jefe.t));
ck('la emision esta en la bitacora',
   (r.b.lineas || []).some((l) => l.accion === 'consola.emitida' && l.actor === jefe.user),
   JSON.stringify((r.b.lineas || [])[0]));

console.log('\n=== ver y cerrar desde el telefono ===');
r = await call('GET', '/v1/panel/consola', conSesion(jefe.t));
const abierta = (r.b.consolas || [])[0];
ck('el telefono ve la consola abierta', !!abierta, JSON.stringify(r.b));
ck('con la etiqueta que se le puso', abierta?.etiqueta === 'Laptop de la oficina', abierta?.etiqueta);
ck('y con desde que aparato se emitio', abierta?.emitidaDesde === 'telefono', abierta?.emitidaDesde);

r = await call('DELETE', '/v1/panel/consola/' + abierta.id, conSesion(jefe.t));
ck('se cierra desde el telefono', r.s === 204, String(r.s));

r = await call('GET', '/v1/panel/resumen', conConsola(CONSOLA));
ck('y el token deja de servir EN EL ACTO', r.s === 401, String(r.s));

r = await call('DELETE', '/v1/panel/consola/' + abierta.id, conSesion(jefe.t));
ck('cerrar dos veces la misma se rechaza', r.s === 404, String(r.s));

console.log('\n=== un token caducado no sirve ===');
r = await call('POST', '/v1/panel/consola', conSesion(jefe.t), { password: 'clave-larga-123' });
const OTRA = r.b.token;
execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  // Se mueven las DOS fechas: la base exige que `expira_en > creado_en`, y
  // mover solo el vencimiento violaria ese CHECK -que existe justamente para
  // que no haya tokens nacidos caducados-.
  `"UPDATE token_consola SET creado_en = now() - interval '9 hours', ` +
  `expira_en = now() - interval '1 hour' WHERE usuario_id='${jefe.id}'"`,
  { stdio: 'pipe' },
);
r = await call('GET', '/v1/panel/resumen', conConsola(OTRA));
ck('caducada, responde 401', r.s === 401, String(r.s));

r = await call('GET', '/v1/panel/resumen', conConsola('token-inventado-que-no-existe'));
ck('y un token inventado tambien', r.s === 401, String(r.s));

console.log('\n=== tope de consolas abiertas ===');
execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"DELETE FROM token_consola WHERE usuario_id='${jefe.id}'"`,
  { stdio: 'pipe' },
);
let ultimas = 0;
for (let i = 0; i < 6; i++) {
  r = await call('POST', '/v1/panel/consola', conSesion(jefe.t), { password: 'clave-larga-123' });
  if (r.s === 200) ultimas++;
}
ck('no se pueden abrir mas de cinco a la vez', ultimas === 5, String(ultimas));
ck('y la sexta lo dice con un 409', r.s === 409, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
