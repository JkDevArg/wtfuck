// V26 · El id del mensaje viaja aparte del id de la fila del buzon.
//
// ## El defecto que fija esta suite
//
// El id de una fila del buzon se deriva por destino: `derivar(base, i)` es
// `base` para el primer destino y `base + i` para los demas. Eso es correcto
// para el buzon -cada destino recibe bytes distintos y necesita su propia
// fila-, pero era lo UNICO que el cliente recibia, y lo guardaba como el id del
// mensaje.
//
// Resultado: en una conversacion de tres o mas dispositivos, el mismo mensaje
// quedaba guardado con un id distinto en cada telefono. En chats DIRECTOS no se
// notaba nunca, porque hay un solo destino y `derivar(base, 0) == base`; por eso
// el defecto sobrevivio a veinte suites de pruebas.
//
// Lo que se comprueba:
//
//  1. Que todos los destinos de un grupo reciban el MISMO `mensajeId`, y que
//     sea el que genero quien envio.
//  2. Que el `sobreId` de cada uno siga siendo distinto: es su fila, y es lo
//     que acusa. Si fueran iguales, el acuse de uno borraria el del otro.
//  3. Que el acuse le devuelva al emisor el id que EL conoce, y no el derivado.
//  4. Que el `mensajeId` coincida con el id de `mensaje_meta`, que es a lo que
//     apuntan las respuestas, las reacciones y los votos.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const WS = BASE.replace(/^http/, 'ws');
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
  return { t: j.token, id: j.usuarioId, dispositivo: j.dispositivoId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);

/** Un socket que junta lo que llega, con una espera acotada. */
function abrir(token) {
  const ws = new WebSocket(`${WS}/v1/ws?token=${token}`);
  const recibidos = [];
  const listo = new Promise((res, rej) => {
    ws.addEventListener('open', () => res());
    ws.addEventListener('error', (e) => rej(new Error('socket: ' + e.message)));
  });
  ws.addEventListener('message', (e) => {
    try { recibidos.push(JSON.parse(e.data)); } catch { /* ignorar */ }
  });
  return {
    listo,
    recibidos,
    enviar: (o) => ws.send(JSON.stringify(o)),
    cerrar: () => ws.close(),
    // Espera hasta que aparezca algo del tipo pedido, o se rinde.
    esperar: async (tipo, ms = 4000) => {
      const hasta = Date.now() + ms;
      while (Date.now() < hasta) {
        const m = recibidos.find((x) => x.type === tipo);
        if (m) return m;
        await new Promise((r) => setTimeout(r, 80));
      }
      return null;
    },
  };
}

const ana = await reg('ia');
const beto = await reg('ib');
const cora = await reg('ic');

let r = await post('/v1/conversaciones/grupo', ana.t, {
  nombre: 'Grupo id', usernames: [beto.user, cora.user],
});
const grupo = r.b.conversacionId ?? r.b.id;
ck('se crea un grupo de tres', !!grupo, JSON.stringify(r.b).slice(0, 120));

// Claves publicadas para los tres: sin ellas el servidor no los lista como
// destinos y el caso de "tres destinos" no se daria.
for (const quien of [ana, beto, cora]) {
  const pre = Array.from({ length: 3 }, (_, i) => ({ id: i + 1, publica: b64('pk' + i + quien.user) }));
  r = await call('PUT', '/v1/claves', quien.t, {
    identidadPub: b64('k' + quien.user),
    firmadaId: 1,
    firmadaPub: b64('spk' + quien.user),
    firmadaFirma: b64('sig' + quien.user),
    kyberId: 1,
    kyberPub: b64('kyb' + quien.user),
    kyberFirma: b64('kybsig' + quien.user),
    prekeys: pre,
  });
  if (r.s !== 200 && r.s !== 204) console.log('    (claves de ' + quien.user + ': ' + r.s + ')');
}

r = await get(`/v1/conversaciones/${grupo}/destinos`, ana.t);
const destinos = r.b.destinos ?? [];
ck('ana ve dos destinos: los otros dos', destinos.length === 2,
   JSON.stringify(destinos).slice(0, 200));

const sBeto = abrir(beto.t);
const sCora = abrir(cora.t);
const sAna = abrir(ana.t);
await Promise.all([sBeto.listo, sCora.listo, sAna.listo]);

// Se registra el metadato y se manda UN sobre con una copia por destino.
const idMensaje = crypto.randomUUID();
r = await post('/v1/mensajes', ana.t, { mensajeId: idMensaje, conversacionId: grupo });
ck('se registra el metadato', r.s === 200, JSON.stringify(r.b).slice(0, 120));
const metaId = r.b.id;

sAna.enviar({
  type: 'enviar',
  sobreId: idMensaje,
  conversacionId: grupo,
  creadoEn: Date.now(),
  copias: destinos.map((d) => ({
    destinos: [d.dispositivoId],
    cuerpo: b64('para-' + d.dispositivoId),
    tipo: 0,
  })),
});

const eBeto = await sBeto.esperar('entrega');
const eCora = await sCora.esperar('entrega');
ck('le llega a beto', !!eBeto, JSON.stringify(sBeto.recibidos).slice(0, 200));
ck('le llega a cora', !!eCora, JSON.stringify(sCora.recibidos).slice(0, 200));

console.log('\n=== el mensajeId es el MISMO para todos ===');
ck('beto recibe el mensajeId del emisor', eBeto?.mensajeId === idMensaje,
   `${eBeto?.mensajeId} vs ${idMensaje}`);
ck('cora recibe el MISMO mensajeId', eCora?.mensajeId === idMensaje,
   `${eCora?.mensajeId} vs ${idMensaje}`);
ck('y coincide con el id de mensaje_meta: es a lo que apuntan votos y respuestas',
   eBeto?.mensajeId === metaId, `${eBeto?.mensajeId} vs ${metaId}`);

console.log('\n=== pero el sobreId de cada uno es DISTINTO ===');
// Es lo que hace que el acuse de uno no borre la fila del otro. Antes de V26
// esto era lo unico que llegaba, y por eso se usaba -mal- como id del mensaje.
ck('los sobreId no se repiten', eBeto?.sobreId !== eCora?.sobreId,
   `${eBeto?.sobreId} / ${eCora?.sobreId}`);
ck('exactamente uno coincide con el id base (derivar(base,0) == base)',
   [eBeto?.sobreId, eCora?.sobreId].filter((x) => x === idMensaje).length === 1,
   `${eBeto?.sobreId} / ${eCora?.sobreId}`);

console.log('\n=== el acuse devuelve al emisor el id que EL conoce ===');
// Se acusa con el destino cuyo sobreId NO es el base: es el caso que estaba
// roto. Antes, el emisor recibia `base + i` y no encontraba que marcar.
const elDerivado = eBeto?.sobreId !== idMensaje ? sBeto : sCora;
const suSobre = eBeto?.sobreId !== idMensaje ? eBeto : eCora;
ck('se elige el destino con id derivado', suSobre?.sobreId !== idMensaje, String(suSobre?.sobreId));

elDerivado.enviar({ type: 'acuse', sobreIds: [suSobre.sobreId] });
const entregado = await sAna.esperar('entregado');
ck('el emisor recibe "entregado"', !!entregado, JSON.stringify(sAna.recibidos).slice(0, 200));
ck('con el id de SU mensaje, no con el derivado',
   entregado?.sobreId === idMensaje, `${entregado?.sobreId} vs ${idMensaje}`);

sBeto.cerrar(); sCora.cerrar(); sAna.cerrar();

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
