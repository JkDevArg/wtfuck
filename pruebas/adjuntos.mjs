import { webcrypto as wc } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

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
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);

const jefe = await reg('dj');
const socio = await reg('ds');
const fuera = await reg('df');

const g = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Media D', usernames: [socio.user] });
const G = g.b.id;

/** Cifra en el cliente, como hara la app: clave y nonce nunca llegan al servidor. */
async function cifrar(texto) {
  const clave = await wc.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  const nonce = wc.getRandomValues(new Uint8Array(12));
  const cifrado = await wc.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, clave, Buffer.from(texto));
  const bruta = await wc.subtle.exportKey('raw', clave);
  return { bytes: Buffer.from(cifrado), clave: Buffer.from(bruta).toString('base64'), nonce: Buffer.from(nonce).toString('base64') };
}

console.log('\n=== D.2 reservar / subir / confirmar ===');
const foto = await cifrar('contenido de una imagen '.repeat(50));

let r = await post('/v1/adjuntos', jefe.t, {
  conversacionId: G, clase: 'imagen', bytes: foto.bytes.length,
  mime: 'image/jpeg', nombre: 'foto.jpg', ancho: 1200, alto: 800,
});
ck('se reserva un adjunto y llega una URL firmada', r.s === 200 && r.b.urlSubida?.includes('X-Amz-Signature'), JSON.stringify(r.b)?.slice(0, 160));
const res1 = r.b;

const subida = await fetch(res1.urlSubida, { method: 'PUT', body: foto.bytes });
ck('el archivo cifrado se sube directo al almacen', subida.status === 200, String(subida.status));

r = await post(`/v1/adjuntos/${res1.adjuntoId}/confirmar`, jefe.t);
ck('se confirma y llega la URL de descarga', r.s === 200 && r.b.urlDescarga?.includes('X-Amz-Signature'), JSON.stringify(r.b)?.slice(0, 120));
ck('el servidor guarda el tamano REAL, no el declarado', r.b.bytes === foto.bytes.length, `${r.b.bytes} vs ${foto.bytes.length}`);
const info1 = r.b;

const bajada = await fetch(info1.urlDescarga);
const bytesBajados = Buffer.from(await bajada.arrayBuffer());
ck('se descarga el mismo contenido cifrado', bajada.status === 200 && bytesBajados.equals(foto.bytes));

// Solo el cliente puede abrirlo: el servidor nunca vio la clave.
const claveImp = await wc.subtle.importKey('raw', Buffer.from(foto.clave, 'base64'), 'AES-GCM', false, ['decrypt']);
const claro = await wc.subtle.decrypt({ name: 'AES-GCM', iv: Buffer.from(foto.nonce, 'base64') }, claveImp, bytesBajados);
ck('descifra correctamente con la clave del cliente', Buffer.from(claro).toString().startsWith('contenido de una imagen'));

console.log('\n=== permisos y limites ===');
r = await post('/v1/adjuntos', fuera.t, { conversacionId: G, clase: 'imagen', bytes: 1000 });
ck('quien no pertenece al grupo no puede reservar', r.s === 404 || r.s === 403, String(r.s));

r = await post('/v1/adjuntos', jefe.t, { conversacionId: G, clase: 'sticker', bytes: 5 * 1024 * 1024 });
ck('un sticker de 5 MB se rechaza (limite 2 MB)', r.s === 413, String(r.s));

r = await post('/v1/adjuntos', jefe.t, { conversacionId: G, clase: 'video', bytes: 5 * 1024 * 1024 });
ck('un video de 5 MB si entra (limite 64 MB)', r.s === 200, String(r.s));

r = await post('/v1/adjuntos', jefe.t, { conversacionId: G, clase: 'holograma', bytes: 100 });
ck('una clase inventada se rechaza', r.s === 400, String(r.s));

r = await post('/v1/adjuntos', jefe.t, { conversacionId: G, clase: 'imagen', bytes: 0 });
ck('tamano cero se rechaza', r.s === 400, String(r.s));

console.log('\n=== mentir sobre el tamano no sirve ===');
const grande = await cifrar('x'.repeat(3 * 1024 * 1024));
r = await post('/v1/adjuntos', jefe.t, { conversacionId: G, clase: 'sticker', bytes: 1024 });
ck('se reserva un sticker declarando 1 KB', r.s === 200);
const trampa = r.b;
await fetch(trampa.urlSubida, { method: 'PUT', body: grande.bytes });
r = await post(`/v1/adjuntos/${trampa.adjuntoId}/confirmar`, jefe.t);
ck('al confirmar, el servidor mide el archivo real y lo rechaza', r.s === 413, String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== configuracion del grupo ===');
await put(`/v1/conversaciones/${G}/config`, jefe.t, { nombre: 'Media D', permitirMedia: false });
r = await post('/v1/adjuntos', socio.t, { conversacionId: G, clase: 'imagen', bytes: 1000 });
ck('con multimedia desactivada no se puede subir', r.s === 403, String(r.s));
r = await post('/v1/adjuntos', socio.t, { conversacionId: G, clase: 'sticker', bytes: 1000 });
ck('los stickers siguen permitidos', r.s === 200, String(r.s));
await put(`/v1/conversaciones/${G}/config`, jefe.t, { nombre: 'Media D', permitirMedia: true });

console.log('\n=== acceso a adjuntos ajenos ===');
r = await get(`/v1/adjuntos/${info1.adjuntoId}`, socio.t);
ck('un miembro del grupo obtiene la URL', r.s === 200 && r.b.urlDescarga?.length > 0);
r = await get(`/v1/adjuntos/${info1.adjuntoId}`, fuera.t);
ck('alguien de fuera NO obtiene la URL aunque sepa el id', r.s === 403 || r.s === 404, String(r.s));

r = await post(`/v1/adjuntos/${info1.adjuntoId}/confirmar`, socio.t);
ck('no se puede confirmar un adjunto de otra persona', r.s === 403, String(r.s));

console.log('\n=== D.7 uso de almacenamiento ===');
r = await get('/v1/adjuntos/uso', jefe.t);
ck('se reporta el uso y la cuota', r.s === 200 && r.b.bytes > 0 && r.b.cuotaBytes > 0, JSON.stringify(r.b));
ck('solo cuentan los adjuntos confirmados', r.b.archivos === 1, JSON.stringify(r.b));

const usoSocio = await get('/v1/adjuntos/uso', socio.t);
ck('el uso es por persona', usoSocio.b.bytes === 0, JSON.stringify(usoSocio.b));

console.log('\n=== D.4 nota honesta ===');
r = await post('/v1/adjuntos', jefe.t, {
  conversacionId: G, clase: 'documento', bytes: 500, mime: 'application/pdf', nombre: 'ejecutable.exe',
});
ck('el servidor acepta el nombre declarado sin verificarlo (va cifrado)', r.s === 200,
  'esto es esperado: con E2EE el servidor no puede leer la firma del archivo');

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
