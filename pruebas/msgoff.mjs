// La promesa de `msg off`: un mensaje escrito sin red y entregado horas
// despues debe mostrarse con la hora de QUIEN LO ESCRIBIO, no con la de la
// entrega. Si no, la conversacion queda desordenada y con horas falsas.
//
// Este caso solo aparece cuando quien recibe estaba desconectado, asi que la
// prueba fuerza exactamente eso.
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


const ana = await reg('oa');
const beto = await reg('ob');
const conv = (await post('/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user })).b.id;

// Ocho horas atras: es el caso real de un mensaje que esperó a tener red.
const HACE_8H = Date.now() - 8 * 60 * 60 * 1000;

console.log('\n=== quien recibe esta desconectado ===');
const wsAna = await abrir(ana.t);
await esperar(400);

const mid = crypto.randomUUID();
let r = await post('/v1/mensajes', ana.t, { mensajeId: mid, conversacionId: conv });
ck('el mensaje se registra', r.s === 200, String(r.s));

wsAna.send(JSON.stringify({
  type: 'enviar', sobreId: mid, conversacionId: conv,
  creadoEn: HACE_8H, copias: await copiasPara(ana.t, conv, 'escrito sin red'),
}));
await esperar(1200);
const aceptado = wsAna.recibidos.find((x) => x.type === 'aceptado');
ck('el servidor lo acepta y lo guarda para despues', !!aceptado, JSON.stringify(wsAna.recibidos).slice(0, 160));

console.log('\n=== recien ahora se conecta ===');
const wsBeto = await abrir(beto.t);
await esperar(1500);

const entrega = wsBeto.recibidos.find((x) => x.type === 'entrega');
ck('recibe el sobre que quedo pendiente', !!entrega, JSON.stringify(wsBeto.recibidos).slice(0, 160));
ck('el cuerpo llega intacto', entrega?.cuerpo === b64('escrito sin red'), String(entrega?.cuerpo));

// El corazon de la prueba.
const desvio = entrega ? Math.abs(entrega.creadoEn - HACE_8H) : Infinity;
ck('la hora entregada es la del AUTOR, no la de la entrega', desvio < 1000,
   `esperado ~${HACE_8H}, llego ${entrega?.creadoEn} (desvio ${desvio} ms)`);
ck('y NO es la hora actual', desvio < Math.abs(Date.now() - HACE_8H) / 2,
   `llego ${entrega?.creadoEn}, ahora ${Date.now()}`);

console.log('\n=== el orden lo da el autor, no la entrega ===');
// Dos mensajes escritos en un orden y enviados en el orden contrario: quien
// recibe tiene que poder ordenarlos por su hora de autoria.
const viejo = crypto.randomUUID();
const nuevo = crypto.randomUUID();
await post('/v1/mensajes', ana.t, { mensajeId: viejo, conversacionId: conv });
await post('/v1/mensajes', ana.t, { mensajeId: nuevo, conversacionId: conv });
wsBeto.recibidos.length = 0;

// Se manda primero el mas nuevo.
wsAna.send(JSON.stringify({ type: 'enviar', sobreId: nuevo, conversacionId: conv, creadoEn: HACE_8H + 60000, copias: await copiasPara(ana.t, conv, 'segundo') }));
await esperar(500);
wsAna.send(JSON.stringify({ type: 'enviar', sobreId: viejo, conversacionId: conv, creadoEn: HACE_8H, copias: await copiasPara(ana.t, conv, 'primero') }));
await esperar(1500);

const dos = wsBeto.recibidos.filter((x) => x.type === 'entrega');
ck('llegan los dos', dos.length === 2, String(dos.length));
const porHora = [...dos].sort((a, b) => a.creadoEn - b.creadoEn);
ck('ordenados por su hora quedan en el orden en que se escribieron',
   porHora.length === 2 && porHora[0].cuerpo === b64('primero') && porHora[1].cuerpo === b64('segundo'),
   JSON.stringify(porHora.map((x) => [x.creadoEn, Buffer.from(x.cuerpo, 'base64').toString()])));

wsAna.close();
wsBeto.close();
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
