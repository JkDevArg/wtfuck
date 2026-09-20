// E.4: una copia, varios destinos.
//
// Con clave de emisor el mensaje de grupo se cifra UNA vez y esos mismos bytes
// valen para todos los que ya la tengan. El servidor tiene que saber repartir
// un cuerpo a N buzones, y tiene que seguir decidiendo EL quienes son destinos
// legitimos: que el cliente mande una lista no la convierte en autorizada.
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
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const abrir = (t) => new Promise((res, rej) => {
  const w = new WebSocket(`ws://localhost:8300/v1/ws?token=${encodeURIComponent(t)}`);
  w.recibidos = [];
  w.onmessage = (e) => w.recibidos.push(JSON.parse(e.data));
  w.onopen = () => res(w);
  w.onerror = rej;
});
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

const jefa = await reg('ca');
const uno = await reg('cb');
const dos = await reg('cc');
const fuera = await reg('cz');

const g = await post('/v1/conversaciones/grupo', jefa.t, {
  nombre: 'Clave de emisor', usernames: [uno.user, dos.user],
});
const G = g.b.id;

const wsUno = await abrir(uno.t);
const wsDos = await abrir(dos.t);
const wsFuera = await abrir(fuera.t);
const wsJefa = await abrir(jefa.t);
await dormir(600);

console.log('\n=== un cuerpo para los dos destinos ===');
const r0 = await get(`/v1/conversaciones/${G}/destinos`, jefa.t);
const devs = r0.b.destinos.map((d) => d.dispositivoId);
ck('el grupo tiene dos dispositivos destino', devs.length === 2, String(devs.length));

const mid = crypto.randomUUID();
await post('/v1/mensajes', jefa.t, { mensajeId: mid, conversacionId: G });
// UNA copia, tipo 7 (clave de emisor), con los dos destinos.
wsJefa.send(JSON.stringify({
  type: 'enviar', sobreId: mid, conversacionId: G, creadoEn: Date.now(),
  copias: [{ destinos: devs, cuerpo: b64('mensaje de grupo'), tipo: 7 }],
}));
await dormir(1500);

const eUno = wsUno.recibidos.find((x) => x.type === 'entrega');
const eDos = wsDos.recibidos.find((x) => x.type === 'entrega');
ck('le llega al primero', !!eUno);
ck('le llega al segundo', !!eDos);
ck('los dos reciben los MISMOS bytes', eUno?.cuerpo === eDos?.cuerpo);
ck('y el tipo de cifrado se conserva', eUno?.tipo === 7 && eDos?.tipo === 7,
   `${eUno?.tipo} / ${eDos?.tipo}`);
ck('cada uno con su propio id de sobre (para acusar por separado)',
   !!eUno && !!eDos && eUno.sobreId !== eDos.sobreId);
ck('la entrega dice de que dispositivo viene, no solo de quien',
   eUno?.origenDispositivo === jefa.dev, String(eUno?.origenDispositivo));

const aceptado = wsJefa.recibidos.find((x) => x.type === 'aceptado');
ck('el servidor acepta y no reporta copias faltantes',
   !!aceptado && (aceptado.sinCopia || []).length === 0, JSON.stringify(aceptado));

console.log('\n=== el cliente no elige a quien le llega ===');
wsFuera.recibidos.length = 0;
wsUno.recibidos.length = 0;
const mid2 = crypto.randomUUID();
await post('/v1/mensajes', jefa.t, { mensajeId: mid2, conversacionId: G });
// Se cuela un dispositivo ajeno en la lista de destinos.
wsJefa.send(JSON.stringify({
  type: 'enviar', sobreId: mid2, conversacionId: G, creadoEn: Date.now(),
  copias: [{ destinos: [...devs, fuera.dev], cuerpo: b64('no es para ti'), tipo: 7 }],
}));
await dormir(1500);

ck('a quien no pertenece al grupo NO le llega nada',
   !wsFuera.recibidos.some((x) => x.type === 'entrega'),
   JSON.stringify(wsFuera.recibidos).slice(0, 120));
ck('y a los que si pertenecen les llega igual',
   wsUno.recibidos.some((x) => x.type === 'entrega'));

console.log('\n=== copias faltantes se reportan, no se inventan ===');
wsJefa.recibidos.length = 0;
const mid3 = crypto.randomUUID();
await post('/v1/mensajes', jefa.t, { mensajeId: mid3, conversacionId: G });
// Se manda a proposito solo a uno de los dos.
wsJefa.send(JSON.stringify({
  type: 'enviar', sobreId: mid3, conversacionId: G, creadoEn: Date.now(),
  copias: [{ destinos: [devs[0]], cuerpo: b64('incompleto'), tipo: 7 }],
}));
await dormir(1500);
const ac3 = wsJefa.recibidos.find((x) => x.type === 'aceptado');
ck('el servidor dice cual copia falto', (ac3?.sinCopia || []).length === 1,
   JSON.stringify(ac3));
ck('y nombra el dispositivo exacto', (ac3?.sinCopia || [])[0] === devs[1],
   JSON.stringify(ac3?.sinCopia));

console.log('\n=== un cuerpo vacio se rechaza ===');
const mid4 = crypto.randomUUID();
await post('/v1/mensajes', jefa.t, { mensajeId: mid4, conversacionId: G });
wsJefa.recibidos.length = 0;
wsJefa.send(JSON.stringify({
  type: 'enviar', sobreId: mid4, conversacionId: G, creadoEn: Date.now(),
  copias: [{ destinos: devs, cuerpo: '', tipo: 7 }],
}));
await dormir(1200);
ck('llega un error y no un sobre vacio',
   wsJefa.recibidos.some((x) => x.type === 'error'), JSON.stringify(wsJefa.recibidos).slice(0, 140));

[wsUno, wsDos, wsFuera, wsJefa].forEach((w) => w.close());
console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
