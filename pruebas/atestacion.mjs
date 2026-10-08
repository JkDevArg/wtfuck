// W5e · Key Attestation, vista desde afuera.
//
// Contra el servidor normal (modo `registrar`, el de arranque):
//  1. El reto: 32 bytes, 5 minutos, y dice el modo.
//  2. Que el registro NO se rechace por la atestacion, pero que quede ANOTADO
//     por que fallo: sin cadena, ilegible, demasiado larga, raiz que no es de
//     Google. Esa anotacion es la medicion que decide si se puede exigir.
//  3. Que el navegador quede `no-aplica` (sus puertas son correo e invitacion)
//     y que vincular tambien anote.
//
// Contra una segunda instancia con `WTFUCK_ATESTACION=exigir` (si responde en
// WTFUCK_BASE_EXIGIR, por defecto :8311):
//  4. Un telefono que dice TEE sin una cadena valida: 403.
//  5. El emulador (SOFTWARE_DEV con el interruptor de desarrollo abierto)
//     sigue entrando: el modo exigir no rompe el desarrollo.
//
// Las cadenas REALES (emulador y telefono fisico) se prueban aparte: ver
// docs/evidencias/web-w5/.
import { execSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const EXIGIR = process.env.WTFUCK_BASE_EXIGIR ?? 'http://localhost:8311';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (b) => Buffer.from(b).toString('base64');
const psql = (q) => execSync(`docker exec wtfuck_db psql -U wtfuck -d wtfuck -tAc "${q}"`, { encoding: 'utf8' }).trim();

async function call(base, m, ruta, t, body) {
  const h = { 'Content-Type': 'application/json' };
  if (t) h.Authorization = 'Bearer ' + t;
  const r = await fetch(base + ruta, { method: m, headers: h, body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}

const registrar = (base, u, nivel, atestacion) => call(base, 'POST', '/v1/registro', null, {
  username: u, password: 'clave-larga-123', etiquetaDispositivo: 'tel',
  identidadPub: b64(randomBytes(33)), hardwareHash: b64(randomBytes(32)), hardwareNivel: nivel, atestacion,
});
const anotado = (dispositivoId) => psql(`SELECT coalesce(atestacion, '(nada)') FROM dispositivo WHERE id = '${dispositivoId}'`);

// Un certificado autofirmado: una cadena bien formada cuya raiz NO es de Google.
const dir = mkdtempSync(join(tmpdir(), 'atest-'));
execSync(`openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "${join(dir, 'k.pem')}" -out "${join(dir, 'c.pem')}" -days 2 -subj "/CN=no-es-google" -outform PEM`, { stdio: 'ignore' });
execSync(`openssl x509 -in "${join(dir, 'c.pem')}" -outform DER -out "${join(dir, 'c.der')}"`, { stdio: 'ignore' });
const autofirmado = b64(readFileSync(join(dir, 'c.der')));

console.log('\n=== 1 · el reto ===');
let r = await call(BASE, 'POST', '/v1/atestacion/reto');
ck('200 sin sesion', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);
ck('32 bytes', Buffer.from(r.b?.reto ?? '', 'base64').length === 32, r.b?.reto);
ck('vence en 5 minutos', r.b?.expiraEnSegundos === 300, String(r.b?.expiraEnSegundos));
ck('y dice el modo (registrar por defecto)', r.b?.modo === 'registrar', r.b?.modo);
const r2 = await call(BASE, 'POST', '/v1/atestacion/reto');
ck('cada reto es distinto', r2.b?.reto && r2.b.reto !== r.b?.reto);
const enBase = psql(`SELECT count(*) FROM reto_atestacion WHERE reto = decode('${Buffer.from(r.b.reto, 'base64').toString('hex')}', 'hex') AND usado_en IS NULL`);
ck('queda guardado, sin usar', enBase === '1', enBase);

console.log('\n=== 2 · registrar: no rechaza, pero anota por que ===');
const casos = [
  ['sin cadena (app vieja)', [], 'fallida:sin-cadena'],
  ['una cadena ilegible', ['@@@ no es base64 @@@'], 'fallida:cadena-ilegible'],
  ['once certificados', Array(11).fill(autofirmado), 'fallida:cadena-demasiado-larga'],
  ['una raiz que no es de Google', [autofirmado], 'fallida:raiz-desconocida'],
];
for (const [que, cadena, esperado] of casos) {
  r = await registrar(BASE, 'at' + Math.random().toString(36).slice(2, 8), 'SOFTWARE_DEV', cadena);
  ck(`${que}: el registro pasa`, r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);
  if (r.s === 200) ck(`  y queda anotado "${esperado}"`, anotado(r.b.dispositivoId) === esperado, anotado(r.b.dispositivoId));
}
r = await registrar(BASE, 'attee' + S, 'TEE', []);
ck('un TEE declarado sin cadena tambien pasa en registrar (solo se anota)', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);
const tee = r.b;
if (r.s === 200) ck('  anotado sin-cadena, con el nivel declarado', anotado(tee.dispositivoId) === 'fallida:sin-cadena' &&
  psql(`SELECT hardware_nivel FROM dispositivo WHERE id = '${tee.dispositivoId}'`) === 'TEE');

console.log('\n=== 3 · el navegador y el vinculo ===');
// Vincular un segundo aparato a la cuenta de `tee`.
r = await call(BASE, 'POST', '/v1/dispositivos/codigo', tee?.token, { password: 'clave-larga-123' });
const codigo = r.b?.codigo;
ck('el principal emite un codigo de vinculo', !!codigo, `${r.s} ${JSON.stringify(r.b)}`);
r = await call(BASE, 'POST', '/v1/dispositivos/vincular', null, {
  username: 'attee' + S, codigo, etiquetaDispositivo: 'tablet', identidadPub: b64(randomBytes(33)),
  hardwareHash: b64(randomBytes(32)), hardwareNivel: 'SOFTWARE_DEV', atestacion: [autofirmado],
});
ck('vincular un telefono: pasa y anota', r.s === 200 && anotado(r.b.dispositivoId) === 'fallida:raiz-desconocida',
  `${r.s} ${JSON.stringify(r.b).slice(0, 120)}`);
r = await call(BASE, 'POST', '/v1/dispositivos/codigo', tee?.token, { password: 'clave-larga-123' });
r = await call(BASE, 'POST', '/v1/dispositivos/vincular', null, {
  username: 'attee' + S, codigo: r.b?.codigo, etiquetaDispositivo: 'Chrome', identidadPub: b64(randomBytes(33)),
  hardwareHash: b64(randomBytes(32)), hardwareNivel: 'NAVEGADOR',
});
ck('vincular un navegador: no-aplica', r.s === 200 && anotado(r.b.dispositivoId) === 'no-aplica', `${r.s} ${JSON.stringify(r.b).slice(0, 120)}`);

console.log('\n=== 4 y 5 · modo exigir (segunda instancia) ===');
const viva = await fetch(EXIGIR + '/v1/version').then((x) => x.ok).catch(() => false);
if (!viva) {
  console.log(`    OMITIDO: no hay servidor en ${EXIGIR}. Levantalo con WTFUCK_ATESTACION=exigir y -Puerto 8311.`);
} else {
  r = await call(EXIGIR, 'POST', '/v1/atestacion/reto');
  ck('el reto dice exigir', r.b?.modo === 'exigir', r.b?.modo);
  r = await registrar(EXIGIR, 'atx' + S, 'TEE', []);
  ck('un TEE sin cadena: 403', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);
  ck('  y dice por que', /genuinos/.test(r.b?.motivo ?? '') && /sin-cadena/.test(r.b?.motivo ?? ''), JSON.stringify(r.b));
  r = await registrar(EXIGIR, 'atx2' + S, 'STRONGBOX', [autofirmado]);
  ck('un STRONGBOX con raiz que no es de Google: 403', r.s === 403 && /raiz-desconocida/.test(r.b?.motivo ?? ''), `${r.s} ${JSON.stringify(r.b)}`);
  const existe = psql(`SELECT count(*) FROM usuario WHERE username IN ('atx${S}', 'atx2${S}')`);
  ck('  y no quedo ninguna cuenta a medias', existe === '0', existe);
  r = await registrar(EXIGIR, 'atemu' + S, 'SOFTWARE_DEV', []);
  ck('el emulador (SOFTWARE_DEV en desarrollo) sigue entrando', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);
}

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
