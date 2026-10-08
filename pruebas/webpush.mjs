// Version web, W4c · Avisos con el navegador cerrado (Web Push).
//
// Lo que se comprueba SIEMPRE, con el servidor arrancado por
// `arrancar-servidor.ps1` (que le da claves VAPID de desarrollo):
//
//  1. Que la configuracion para el navegador traiga la clave PUBLICA y nada mas.
//  2. Que el servidor no acepte cualquier URL como endpoint: le va a hacer un
//     POST, y aceptar cualquiera seria una SSRF (metadatos de la nube, la red
//     interna, un host parecido a uno de verdad).
//
// Y con `-ConPushDeMentira` y `node pruebas/stub-fcm.mjs` levantado, lo que de
// verdad importa: **que el aviso vaya VACIO**, firmado con VAPID, y que una
// suscripcion vencida (410) se borre sola.
import { createPublicKey, verify } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const STUB = process.env.STUB_BASE ?? 'http://localhost:8399';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const esperar = (ms) => new Promise((res) => setTimeout(res, ms));

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dispositivo: j.dispositivoId, user: u + S };
}
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m, headers: { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const recibeAvisos = async (q) =>
  ((await call('GET', '/v1/dispositivos', q.t)).b?.dispositivos ?? []).find((d) => d.esEste)?.recibeAvisos;

const ana = await reg('wa');
const beto = await reg('wb');

console.log('\n=== la configuracion para el navegador ===');
let r = await call('GET', '/v1/push/web', ana.t);
ck('responde 200', r.s === 200, String(r.s));
const cfg = r.b ?? {};
ck('Web Push disponible (claves de desarrollo de arrancar-servidor.ps1)', cfg.disponible === true, JSON.stringify(cfg));
ck('trae solo la clave publica', Object.keys(cfg).sort().join(',') === 'clavePublica,disponible', Object.keys(cfg).join(','));
const pub = Buffer.from(cfg.clavePublica ?? '', 'base64url');
ck('es un punto P-256 sin comprimir', pub.length === 65 && pub[0] === 4, `${pub.length} bytes`);
r = await fetch(BASE + '/v1/push/web');
ck('sin sesion es 401', r.status === 401, String(r.status));

console.log('\n=== solo endpoints de servicios de push: el servidor les hace un POST ===');
const malos = [
  ['los metadatos de la nube', 'http://169.254.169.254/latest/meta-data/iam'],
  ['la red interna', 'https://10.0.0.5/admin/reiniciar'],
  ['un host cualquiera', 'https://ejemplo-malo.test/wp/1'],
  ['un host que se parece', 'https://fcm.googleapis.com.ejemplo-malo.test/fcm/send/1'],
  ['un sufijo sin el punto', 'https://malonotify.windows.com/w/1'],
  ['el de verdad por http', 'http://fcm.googleapis.com/fcm/send/abcdef'],
  ['el de verdad en otro puerto', 'https://fcm.googleapis.com:8443/fcm/send/abcdef'],
  ['con usuario en la URL', 'https://x@fcm.googleapis.com/fcm/send/abcdef'],
  ['algo que no es una URL', 'no-es-una-url-para-nada'],
];
for (const [que, url] of malos) {
  r = await call('PUT', '/v1/push', ana.t, { token: url, proveedor: 'webpush' });
  ck(`rechaza ${que}`, r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);
}
ck('y ninguno quedo registrado', (await recibeAvisos(ana)) === false);

const buenos = [
  ['Chrome', 'https://fcm.googleapis.com/fcm/send/dXJsLWRlLXBydWViYQ:APA91b'],
  ['Firefox', 'https://updates.push.services.mozilla.com/wpush/v2/gAAAAABprueba'],
  ['Safari', 'https://web.push.apple.com/QGprueba'],
  ['Edge', 'https://wns2-par02p.notify.windows.com/w/?token=BQYAAABprueba'],
];
for (const [quien, url] of buenos) {
  r = await call('PUT', '/v1/push', beto.t, { token: url + S, proveedor: 'webpush' });
  ck(`acepta el de ${quien}`, r.s === 204, `${r.s} ${JSON.stringify(r.b)}`);
}
// Beto no tiene que quedar con un endpoint de verdad: si algo lo despertara,
// el servidor le haria un POST a Google.
await call('DELETE', '/v1/push', beto.t);

// ------------------------------------------------------------------
//  Solo con el stub
// ------------------------------------------------------------------
const stubVivo = await fetch(STUB + '/limpiar', { method: 'POST' }).then((x) => x.ok).catch(() => false);
const conStub = stubVivo && (await call('PUT', '/v1/push', ana.t, { token: `${STUB}/wp/ana-${S}`, proveedor: 'webpush' })).s === 204;

if (!conStub) {
  console.log('\n=== el aviso en si: OMITIDO ===');
  console.log('    Hace falta el servidor con -ConPushDeMentira y `node pruebas/stub-fcm.mjs`.');
} else {
  async function avisosA(endpoint, ms) {
    let hallados = [];
    for (let i = 0; i < ms / 200 && hallados.length === 0; i++) {
      await esperar(200);
      const rr = await fetch(STUB + '/recibidos').then((x) => x.json()).catch(() => []);
      hallados = rr.filter((x) => x.ruta === 'webpush' && x.endpoint === endpoint);
    }
    return hallados;
  }

  console.log('\n=== que viaja en el aviso ===');
  ck('ana registra su endpoint (el stub hace de servicio de push)', (await recibeAvisos(ana)) === true);
  // Beto agrega a ana a un grupo; ana no tiene socket: hay que ir a buscarla.
  r = await call('POST', '/v1/conversaciones/grupo', beto.t, { nombre: 'Grupo web push', usernames: [ana.user] });
  const conv = r.b?.conversacionId ?? r.b?.id;
  ck('se crea el grupo que la agrega', !!conv, JSON.stringify(r.b).slice(0, 140));

  const avisos = await avisosA(`/wp/ana-${S}`, 5000);
  ck('llego un aviso al servicio de push', avisos.length > 0);
  const a = avisos[0] ?? { cabeceras: {} };
  const hay = avisos.length > 0;

  // LO IMPORTANTE DE TODO ESTO.
  ck('el aviso va VACIO: cero bytes de cuerpo', hay && a.bytes === 0, String(a.bytes));
  ck('sin Content-Encoding: no hay nada cifrado que entregar', hay && a.cabeceras['content-encoding'] === '', a.cabeceras['content-encoding']);
  ck('ni el usuario, ni el grupo, ni quien escribe en ninguna cabecera',
     hay && ![ana.id, conv, beto.user, ana.user].some((x) => JSON.stringify(a.cabeceras).includes(x)));

  ck('Urgency alta', a.cabeceras.urgency === 'high', a.cabeceras.urgency);
  ck('TTL de un dia', a.cabeceras.ttl === '86400', a.cabeceras.ttl);
  ck('Topic fijo: el servicio reemplaza el aviso que no entrego en vez de acumular', a.cabeceras.topic === 'avisos', a.cabeceras.topic);

  console.log('\n=== la firma VAPID ===');
  const m = /^vapid t=([^,]+), k=(.+)$/.exec(a.cabeceras.authorization ?? '');
  ck('Authorization: vapid t=..., k=...', !!m, a.cabeceras.authorization?.slice(0, 40));
  if (m) {
    const [h, c, f] = m[1].split('.');
    const cab = JSON.parse(Buffer.from(h, 'base64url'));
    const claims = JSON.parse(Buffer.from(c, 'base64url'));
    ck('k es la clave publica que se le dio al navegador', m[2] === cfg.clavePublica);
    ck('ES256', cab.alg === 'ES256', JSON.stringify(cab));
    ck('aud es el origen del servicio de push', claims.aud === STUB, claims.aud);
    const resta = claims.exp - Date.now() / 1000;
    ck('vence en menos de 24 h (el maximo que aceptan)', resta > 3600 && resta <= 24 * 3600, String(resta));
    ck('sub es un contacto', /^(mailto:|https:)/.test(claims.sub), claims.sub);
    const llave = createPublicKey({
      key: { kty: 'EC', crv: 'P-256', x: pub.subarray(1, 33).toString('base64url'), y: pub.subarray(33).toString('base64url') },
      format: 'jwk',
    });
    const firma = Buffer.from(f, 'base64url');
    ck('la firma es R||S de 64 bytes (P1363, no DER)', firma.length === 64, String(firma.length));
    ck('y verifica con la clave publica',
       verify('sha256', Buffer.from(`${h}.${c}`), { key: llave, dsaEncoding: 'ieee-p1363' }, firma));
  }

  console.log('\n=== una suscripcion vencida se borra sola ===');
  const carla = await reg('wc');
  r = await call('PUT', '/v1/push', carla.t, { token: `${STUB}/wp/vencido-${S}`, proveedor: 'webpush' });
  ck('carla registra un endpoint que el servicio ya dio de baja', r.s === 204, String(r.s));
  await call('POST', '/v1/conversaciones/grupo', beto.t, { nombre: 'Grupo vencido', usernames: [carla.user] });
  ck('se intenta el aviso', (await avisosA(`/wp/vencido-${S}`, 5000)).length > 0);
  let sigue = true;
  for (let i = 0; i < 15 && sigue; i++) { await esperar(200); sigue = await recibeAvisos(carla); }
  ck('el 410 borra el endpoint: no se le vuelve a intentar', sigue === false);

  console.log('\n=== baja ===');
  r = await call('DELETE', '/v1/push', ana.t);
  ck('la baja responde 204', r.s === 204, String(r.s));
  ck('y deja de recibir avisos', (await recibeAvisos(ana)) === false);
}

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
