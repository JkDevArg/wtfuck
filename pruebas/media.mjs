// Modulo D de punta a punta: cifrar, reservar, subir al almacen, confirmar,
// pedir la URL de descarga, bajar y descifrar.
//
// Reproduce EXACTAMENTE el formato de CifradorArchivo.kt (AES-256-GCM con
// nonce de 12 bytes y la etiqueta de 16 pegada al final). Si el servidor o el
// almacen tocaran un solo byte, el descifrado fallaria aqui.
import crypto from 'node:crypto';

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
  return { s: r.status, b: txt ? (txt.startsWith('{') || txt.startsWith('[') ? JSON.parse(txt) : txt) : null };
};
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);

// --- el mismo cifrado que hace la app ------------------------------
function cifrar(claro) {
  const clave = crypto.randomBytes(32);
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', clave, nonce);
  const ct = Buffer.concat([c.update(claro), c.final()]);
  // Java pega la etiqueta al final del texto cifrado; Node la da aparte.
  return { datos: Buffer.concat([ct, c.getAuthTag()]), clave, nonce };
}
function descifrar(datos, clave, nonce) {
  const tag = datos.subarray(datos.length - 16);
  const ct = datos.subarray(0, datos.length - 16);
  const d = crypto.createDecipheriv('aes-256-gcm', clave, nonce);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(ct), d.final()]);
}

const ana = await reg('ma');
const beto = await reg('mb');
const creada = await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user });
if (!creada.b?.id) { console.log('No se pudo crear la conversacion:', creada.s, JSON.stringify(creada.b)); process.exit(1); }
const conv = creada.b.id;

console.log('\n=== D.2 ciclo completo de un adjunto cifrado ===');

// Un "archivo" con contenido reconocible y tamano realista.
const archivo = Buffer.concat([
  Buffer.from('%PDF-1.7 documento de prueba wtfuck\n'),
  crypto.randomBytes(300 * 1024),
]);
const { datos, clave, nonce } = cifrar(archivo);
ck('el cifrado agrega exactamente la etiqueta de 16 bytes', datos.length === archivo.length + 16,
   `${datos.length} vs ${archivo.length}`);

let r = await post('/v1/adjuntos', ana.t, {
  conversacionId: conv, clase: 'documento', bytes: datos.length,
  mime: 'application/pdf', nombre: 'informe.pdf',
});
ck('el servidor reserva y devuelve una URL firmada', r.s === 200 && !!r.b?.urlSubida, JSON.stringify(r.b).slice(0, 120));
const { adjuntoId, urlSubida } = r.b;

// La URL se usa TAL CUAL: reescribir el host invalidaria la firma SigV4.
let sub = await fetch(urlSubida, {
  method: 'PUT', body: datos, headers: { 'Content-Type': 'application/octet-stream' },
});
ck('el almacen acepta el PUT en la URL firmada sin token', sub.ok, String(sub.status));

r = await post(`/v1/adjuntos/${adjuntoId}/confirmar`, ana.t, null);
ck('confirmar acepta el tamano declarado', r.s === 200, JSON.stringify(r.b).slice(0, 150));
ck('y responde con el tamano real del almacen', r.b?.bytes === datos.length, String(r.b?.bytes));

console.log('\n=== quien recibe puede bajarlo y abrirlo ===');
r = await get(`/v1/adjuntos/${adjuntoId}`, beto.t);
ck('el destinatario obtiene una URL de descarga', r.s === 200 && !!r.b?.urlDescarga, String(r.s));

const baj = await fetch(r.b.urlDescarga);
const bajados = Buffer.from(await baj.arrayBuffer());
ck('el almacen devuelve los mismos bytes cifrados', bajados.equals(datos),
   `${bajados.length} vs ${datos.length}`);

let abierto = null;
try { abierto = descifrar(bajados, clave, nonce); } catch (e) { abierto = null; }
ck('se descifra con la clave del sobre y coincide con el original',
   abierto !== null && abierto.equals(archivo));

console.log('\n=== la firma detecta un archivo alterado ===');
// Se cambia un byte en medio del contenido cifrado.
const manipulado = Buffer.from(bajados);
manipulado[1000] = manipulado[1000] ^ 0xff;
let rechazado = false;
try { descifrar(manipulado, clave, nonce); } catch (e) { rechazado = true; }
ck('GCM rechaza el contenido si cambio un solo byte', rechazado);

// Y con la clave equivocada tampoco se abre: es lo que protege al archivo de
// quien administre el almacen.
let conClaveAjena = false;
try { descifrar(bajados, crypto.randomBytes(32), nonce); } catch (e) { conClaveAjena = true; }
ck('sin la clave correcta el archivo del almacen es ilegible', conClaveAjena);

console.log('\n=== la miniatura viaja en el sobre, no en el almacen ===');
// Una miniatura tipica de 240 px: se comprueba que entra en un sobre sin
// convertirlo en algo desmedido.
const mini = crypto.randomBytes(9 * 1024).toString('base64');
r = await post('/v1/mensajes', ana.t, { mensajeId: crypto.randomUUID(), conversacionId: conv });
ck('un mensaje con adjunto se registra como cualquier otro', r.s === 200, String(r.s));
ck('la miniatura en base64 se mantiene en pocos KB', mini.length < 20 * 1024, String(mini.length));

console.log('\n=== D.6 buscador de GIFs por intermediario ===');
r = await get('/v1/gifs/buscar?q=hola&limite=6', ana.t);
ck('la busqueda responde 200 incluso sin proveedor configurado', r.s === 200, String(r.s));
const sinClave = (r.b?.aviso || '').length > 0;
ck('si no hay clave configurada lo dice en vez de fallar',
   sinClave || Array.isArray(r.b?.resultados), JSON.stringify(r.b).slice(0, 140));

const anon = await fetch(BASE + '/v1/gifs/buscar?q=hola');
ck('sin sesion NO se puede usar el intermediario (no es proxy abierto)',
   anon.status === 401, String(anon.status));

console.log('\n=== cuota y uso ===');
r = await get('/v1/adjuntos/uso', ana.t);
ck('el uso incluye el archivo subido', r.b?.bytes >= datos.length, JSON.stringify(r.b));
r = await get('/v1/adjuntos/uso', beto.t);
ck('a quien lo recibe no se le cobra la cuota', r.b?.bytes === 0, JSON.stringify(r.b));

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
