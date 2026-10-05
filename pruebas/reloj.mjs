// La hora de un mensaje, entre relojes que no coinciden.
//
// Un mensaje se fecha con la hora de AUTORIA (msg off, ver msgoff.mjs). Eso
// deja la fecha en manos del reloj de quien escribe, y un reloj desviado
// desordenaba el chat del otro. Esta suite cubre lo que el servidor pone de
// su parte para corregirlo sin romper msg off:
//
//  - su hora en cada respuesta (X-Hora), para que el telefono mida su desfase;
//  - su hora en el aviso de aceptado (servidorEn), para afinarlo;
//  - y el recorte del FUTURO: nadie escribe un mensaje despues de que llega.
//
// El reloj ATRASADO no se corrige aqui -desde el servidor no se distingue de
// un mensaje escrito sin red- sino en la app, con `Reloj`. Por eso la ultima
// comprobacion: el pasado pasa intacto.
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
const post = async (ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: 'POST', headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const abrir = (t) => new Promise((res, rej) => {
  const w = new WebSocket(`ws://localhost:8300/v1/ws?token=${encodeURIComponent(t)}`);
  w.recibidos = [];
  w.onmessage = (e) => w.recibidos.push(JSON.parse(e.data));
  w.onopen = () => res(w);
  w.onerror = rej;
});
const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

// Con E2EE el socket ya no lleva un cuerpo: lleva una copia por dispositivo
// destino. Estas pruebas van en claro (tipo 0) porque lo que comprueban es el
// ENRUTADO y los permisos, no la criptografia: el cifrado real se ejercita en
// la app, contra libsignal.
async function copiasPara(t, conv, texto) {
  const r = await fetch(BASE + `/v1/conversaciones/${conv}/destinos`, { headers: H(t) });
  if (!r.ok) return [];
  const ds = (await r.json()).destinos || [];
  // Una sola copia para todos los destinos: sin cifrar los bytes se comparten.
  return ds.length === 0 ? [] : [{ destinos: ds.map((d) => d.dispositivoId), cuerpo: b64(texto), tipo: 0 }];
}



const ana = await reg('ra');
const beto = await reg('rb');
const conv = (await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user })).b.id;

console.log('\n=== la hora del servidor en cada respuesta ===');
const antes = Date.now();
const rv = await fetch(BASE + '/v1/version');
const xh = Number(rv.headers.get('x-hora'));
ck('la respuesta trae X-Hora', Number.isFinite(xh) && xh > 0, String(rv.headers.get('x-hora')));
ck('y es la hora de ahora, en milisegundos', Math.abs(xh - antes) < 5000, `${xh} vs ${antes}`);
const rp = await fetch(BASE + '/v1/perfil', { headers: H(ana.t) });
ck('tambien en las rutas con sesion', !!rp.headers.get('x-hora'));

const wsAna = await abrir(ana.t);
const wsBeto = await abrir(beto.t);
await esperar(400);

async function enviar(creadoEn, texto) {
  const id = crypto.randomUUID();
  await post('/v1/mensajes', ana.t, { mensajeId: id, conversacionId: conv });
  wsAna.recibidos.length = 0;
  wsBeto.recibidos.length = 0;
  wsAna.send(JSON.stringify({
    type: 'enviar', sobreId: id, conversacionId: conv, creadoEn,
    copias: await copiasPara(ana.t, conv, texto),
  }));
  await esperar(900);
  return {
    aceptado: wsAna.recibidos.find((x) => x.type === 'aceptado'),
    entrega: wsBeto.recibidos.find((x) => x.type === 'entrega'),
  };
}

console.log('\n=== el aviso de aceptado trae la hora del servidor ===');
let t0 = Date.now();
let { aceptado, entrega } = await enviar(t0, 'normal');
ck('trae servidorEn', typeof aceptado?.servidorEn === 'number' && aceptado.servidorEn > 0, JSON.stringify(aceptado));
ck('y es la de ahora', Math.abs((aceptado?.servidorEn ?? 0) - t0) < 5000, String(aceptado?.servidorEn));
ck('un mensaje con la hora bien llega con esa hora', entrega?.creadoEn === t0, `${entrega?.creadoEn} vs ${t0}`);

console.log('\n=== un reloj adelantado: el futuro se recorta ===');
const UNA_HORA = 60 * 60 * 1000;
t0 = Date.now();
({ aceptado, entrega } = await enviar(t0 + UNA_HORA, 'del futuro'));
ck('el mensaje llega', !!entrega);
ck('no llega fechado en el futuro', (entrega?.creadoEn ?? Infinity) <= (aceptado?.servidorEn ?? 0),
   `${entrega?.creadoEn} > ${aceptado?.servidorEn}`);
ck('llega con la hora en que el servidor lo acepto', Math.abs((entrega?.creadoEn ?? 0) - t0) < 5000,
   `${entrega?.creadoEn} vs ${t0}`);

console.log('\n=== el pasado pasa intacto (msg off) ===');
const HACE_3H = Date.now() - 3 * UNA_HORA;
({ entrega } = await enviar(HACE_3H, 'escrito sin red'));
ck('un mensaje de hace tres horas conserva su hora', entrega?.creadoEn === HACE_3H,
   `${entrega?.creadoEn} vs ${HACE_3H}`);

wsAna.close();
wsBeto.close();
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
