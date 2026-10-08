// W5 · Registro desde la web (y por ahi, iPhone).
//
// Una cuenta que nace en un navegador pasa dos puertas que la app no pasa: una
// invitacion WEB (de un solo uso, que reparte cualquier usuario con cupo) y un
// correo verificado con un codigo de 6 digitos. El correo se guarda como
// huella, nunca en claro. Se comprueba:
//
//  1. Las invitaciones web: de un uso, 7 dias, cupo de 5 vigentes, solo las
//     mias se revocan.
//  2. Que pedir el codigo sin invitacion valida no mande nada: esta ruta no
//     tiene sesion y no puede servir para mandarle correos a cualquiera.
//  3. Correos malformados y desechables, rechazados.
//  4. Que el registro web sin codigo correcto no gaste ni la invitacion ni el
//     codigo, y que con todo correcto el navegador quede de PRINCIPAL.
//  5. Que un correo ya usado no sea un oraculo.
//  6. Que la app Android siga registrandose como siempre, sin correo.
//  7. Recuperar una cuenta web en otro navegador, con correo + codigo de
//     recuperacion, y que el viejo quede revocado.
//
// El servidor tiene que estar en modo desarrollo (arrancar-servidor.ps1): sin
// servicio de correo, el codigo vuelve en `codigoDePrueba`.
import { randomBytes } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (b) => Buffer.from(b).toString('base64');

async function call(m, ruta, t, body) {
  const h = { 'Content-Type': 'application/json' };
  if (t) h.Authorization = 'Bearer ' + t;
  const r = await fetch(BASE + ruta, { method: m, headers: h, body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}

async function regApp(u) {
  const r = await call('POST', '/v1/registro', null, {
    username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 'tel',
    identidadPub: b64('k' + u + S), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { s: r.s, t: r.b?.token, id: r.b?.usuarioId, user: u + S };
}

const regWeb = (u, correo, codigoCorreo, invitacion, extra = {}) => call('POST', '/v1/registro', null, {
  username: u, password: 'clave-larga-123', etiquetaDispositivo: 'Safari en iPhone',
  identidadPub: b64(randomBytes(33)), hardwareHash: b64(randomBytes(32)), hardwareNivel: 'NAVEGADOR',
  correo, codigoCorreo, codigoInvitacion: invitacion, ...extra,
});

console.log('\n=== el servidor dice que se puede ===');
let r = await call('GET', '/v1/registro/modo');
ck('registroWeb = true (modo desarrollo)', r.b?.registroWeb === true, JSON.stringify(r.b));

console.log('\n=== 1 · invitaciones web ===');
const ana = await regApp('rwa');
const beto = await regApp('rwb');
ck('ana y beto se registran desde la app, sin correo ni invitacion', ana.s === 200 && beto.s === 200, `${ana.s} ${beto.s}`);

r = await call('POST', '/v1/registro/invitaciones-web', ana.t);
const inv1 = r.b?.codigo;
ck('ana crea una invitacion web', r.s === 200 && /^[A-Z2-9]{12}$/.test(inv1 ?? ''), JSON.stringify(r.b));
ck('de alcance web y un solo uso', r.b?.alcance === 'web' && r.b?.usosMax === 1, JSON.stringify(r.b));
const dias = (r.b?.expiraEn - Date.now()) / 86_400_000;
ck('vence en 7 dias', dias > 6.9 && dias <= 7.01, String(dias));

r = await call('GET', '/v1/registro/invitaciones-web', ana.t);
ck('ana ve la suya y le quedan 4', r.b?.invitaciones?.length === 1 && r.b?.disponibles === 4, JSON.stringify(r.b));
r = await call('GET', '/v1/registro/invitaciones-web', beto.t);
ck('beto no ve las de ana', r.b?.invitaciones?.length === 0, JSON.stringify(r.b));

const otras = [];
for (let i = 0; i < 4; i++) otras.push((await call('POST', '/v1/registro/invitaciones-web', ana.t)).b?.codigo);
ck('cinco vigentes', otras.every(Boolean));
r = await call('POST', '/v1/registro/invitaciones-web', ana.t);
ck('la sexta: 429, cupo de 5 vigentes', r.s === 429, `${r.s} ${JSON.stringify(r.b)}`);
r = await call('DELETE', `/v1/registro/invitaciones-web/${otras[3]}`, beto.t);
ck('beto no puede revocar una de ana: 404', r.s === 404, String(r.s));
r = await call('DELETE', `/v1/registro/invitaciones-web/${otras[3]}`, ana.t);
ck('ana revoca una suya', r.s === 204, String(r.s));
r = await call('POST', '/v1/registro/invitaciones-web', ana.t);
ck('y entonces puede crear otra', r.s === 200, String(r.s));
r = await call('POST', '/v1/registro/invitaciones-web');
ck('sin sesion: 401', r.s === 401, String(r.s));

console.log('\n=== 2 y 3 · pedir el codigo al correo ===');
const correo = `persona.${S}@ejemplo.com`;
r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'registro' });
ck('sin invitacion: 400, no se manda nada', r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);
r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'registro', codigoInvitacion: 'ABCDEFGHJKMN' });
ck('con una invitacion inventada: 403', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);
r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'registro', codigoInvitacion: otras[3] });
ck('con una invitacion revocada: 403', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);
for (const [que, malo] of [['sin arroba', 'nadie.ejemplo.com'], ['sin dominio', 'a@b'], ['con espacios', 'a b@c.com'], ['desechable', `x${S}@mailinator.com`], ['subdominio desechable', `x${S}@m.yopmail.com`]]) {
  r = await call('POST', '/v1/registro/correo', null, { correo: malo, proposito: 'registro', codigoInvitacion: inv1 });
  ck(`rechaza un correo ${que}`, r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);
}
r = await call('POST', '/v1/registro/correo', null, { correo: '  Persona.' + S + '@Ejemplo.com ', proposito: 'registro', codigoInvitacion: inv1 });
const cod1 = r.b?.codigoDePrueba;
ck('con todo bien: 200 y el codigo (de prueba, en desarrollo)', r.s === 200 && /^\d{6}$/.test(cod1 ?? ''), `${r.s} ${JSON.stringify(r.b)}`);
r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'registro', codigoInvitacion: inv1 });
ck('pedir otro enseguida: 429 con la espera', r.s === 429, `${r.s} ${JSON.stringify(r.b)}`);

console.log('\n=== 4 · registrarse desde la web ===');
const webUser = 'rwweb' + S;
r = await regWeb(webUser, '', '', inv1);
ck('sin correo: rechazado', r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);
r = await regWeb(webUser, correo, '000000', inv1);
ck('con un codigo equivocado: rechazado', r.s === 400 && /no coincide/i.test(JSON.stringify(r.b)), `${r.s} ${JSON.stringify(r.b)}`);
r = await regWeb(webUser, correo, cod1, '');
ck('sin invitacion: rechazado', r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);
r = await regWeb(webUser, correo, cod1, inv1);
const web = { t: r.b?.token, id: r.b?.usuarioId, d: r.b?.dispositivoId };
ck('con todo: la cuenta nace en el navegador', r.s === 200 && !!web.t, `${r.s} ${JSON.stringify(r.b)}`);
ck('el codigo equivocado de antes no gasto ni la invitacion ni el codigo', r.s === 200);
r = await call('GET', '/v1/dispositivos', web.t);
const yoWeb = (r.b?.dispositivos ?? []).find((d) => d.esEste);
ck('y el navegador es el aparato PRINCIPAL', yoWeb?.principal === true, JSON.stringify(yoWeb));
ck('de nivel NAVEGADOR', (yoWeb?.nivelHardware ?? yoWeb?.nivel) === 'NAVEGADOR', JSON.stringify(yoWeb));

r = await call('GET', '/v1/registro/invitaciones-web', ana.t);
const usada = (r.b?.invitaciones ?? []).find((x) => x.codigo === inv1);
ck('la invitacion de ana quedo usada', usada?.usos === 1, JSON.stringify(usada));
r = await call('POST', '/v1/registro/correo', null, { correo: `otra.${S}@ejemplo.com`, proposito: 'registro', codigoInvitacion: inv1 });
ck('y ya no sirve para pedir otro codigo: 403', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);

r = await call('POST', '/v1/registro/invitaciones-web', web.t);
ck('la cuenta web tambien puede invitar', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);
const invWeb = r.b?.codigo;

console.log('\n=== 5 · un correo ya usado no es un oraculo ===');
r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'registro', codigoInvitacion: otras[0] });
ck('la respuesta es 200, igual que con un correo nuevo', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);
ck('y no trae codigo: lo que sale es un aviso al correo', !r.b?.codigoDePrueba, JSON.stringify(r.b));
r = await regWeb('rwotro' + S, correo, '123456', otras[0]);
ck('registrar otra cuenta con ese correo: rechazado', r.s === 400 || r.s === 409, `${r.s} ${JSON.stringify(r.b)}`);

console.log('\n=== 6 · la app Android, como siempre ===');
const cami = await regApp('rwc');
ck('se registra sin correo ni invitacion web', cami.s === 200, String(cami.s));
r = await call('POST', '/v1/registro', null, {
  username: 'rwd' + S, password: 'clave-larga-123', etiquetaDispositivo: 'x',
  identidadPub: b64('kd' + S), hardwareHash: b64('HWd' + S), hardwareNivel: 'NAVEGADOR',
});
ck('pero NAVEGADOR sin correo ni invitacion: rechazado', r.s >= 400, `${r.s} ${JSON.stringify(r.b)}`);

console.log('\n=== 7 · recuperar la cuenta web en otro navegador ===');
const verificador = randomBytes(32);
r = await call('PUT', '/v1/cuenta/recuperacion', web.t, { password: 'clave-larga-123', verificadorB64: b64(verificador) });
ck('la cuenta web fija su codigo de recuperacion', r.s === 204 || r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'recuperar', username: 'alguien-mas' + S });
ck('con otro usuario: 200 y sin codigo (no es un oraculo)', r.s === 200 && !r.b?.codigoDePrueba, `${r.s} ${JSON.stringify(r.b)}`);
r = await call('POST', '/v1/registro/correo', null, { correo, proposito: 'recuperar', username: webUser });
const codRec = r.b?.codigoDePrueba;
ck('con el suyo: llega el codigo', r.s === 200 && /^\d{6}$/.test(codRec ?? ''), `${r.s} ${JSON.stringify(r.b)}`);

const nuevo = {
  username: webUser, telefono: '', codigo: '', passwordNueva: 'otra-clave-larga-456',
  etiquetaDispositivo: 'Safari nuevo', identidadPub: b64(randomBytes(33)), hardwareHash: b64(randomBytes(32)),
  hardwareNivel: 'NAVEGADOR', correo,
};
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, { ...nuevo, codigoCorreo: codRec, verificadorB64: b64(randomBytes(32)) });
ck('con un codigo de recuperacion equivocado: 403', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, { ...nuevo, codigoCorreo: codRec, verificadorB64: b64(verificador) });
ck('con correo + codigo + recuperacion: entra el navegador nuevo', r.s === 200 && !!r.b?.token, `${r.s} ${JSON.stringify(r.b)}`);
const t2 = r.b?.token;
r = await call('GET', '/v1/dispositivos', t2);
const ds = r.b?.dispositivos ?? [];
ck('el nuevo es el principal y el viejo ya no esta', ds.length === 1 && ds[0].esEste && ds[0].principal, JSON.stringify(ds));
r = await call('GET', '/v1/dispositivos', web.t);
ck('la sesion del navegador viejo se cerro', r.s === 401, String(r.s));

r = await call('POST', '/v1/registro/correo', null, { correo: `nueva.${S}@ejemplo.com`, proposito: 'registro', codigoInvitacion: invWeb });
const codX = r.b?.codigoDePrueba;
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, { ...nuevo, correo: `nueva.${S}@ejemplo.com`, codigoCorreo: codX, verificadorB64: b64(verificador) });
ck('un codigo de REGISTRO no sirve para recuperar', r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
