// El cupo de mensajes por persona (Limitador.ENVIAR_MENSAJE, 30 por minuto).
//
// Estaba declarado y era ajustable desde el panel, pero ninguna ruta lo
// aplicaba. Lo que se comprueba:
//  - los primeros 30 de una persona pasan;
//  - el 31 recibe 429 y el motivo dice cuantos segundos esperar, que es lo que
//    el cliente lee para reintentar solo (ver `Difusiones.esperaDe`);
//  - el cupo es de ESA persona: otra sigue escribiendo.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
async function call(m, ruta, t, body) {
  const h = { 'Content-Type': 'application/json' };
  if (t) h.Authorization = 'Bearer ' + t;
  const r = await fetch(BASE + ruta, { method: m, headers: h, body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}
async function reg(u) {
  const r = await call('POST', '/v1/registro', null, {
    username: 'cm' + S + u, password: 'clave-larga-123', etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-cupo-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { t: r.b?.token, user: 'cm' + S + u };
}
const mandar = (quien, conv) =>
  call('POST', '/v1/mensajes', quien.t, { mensajeId: crypto.randomUUID(), conversacionId: conv, menciones: [] });

const ana = await reg('ana');
const beto = await reg('beto');
const D = (await call('POST', '/v1/conversaciones/directa', ana.t, { usernameDestino: beto.user })).b?.id;
ck('hay conversacion', !!D);

console.log('\n=== el cupo de una persona ===');
let pasaron = 0, primero429 = null;
for (let i = 0; i < 31; i++) {
  const r = await mandar(ana, D);
  if (r.s === 200) pasaron++;
  else if (r.s === 429 && primero429 == null) primero429 = { i, r };
}
ck('los primeros 30 pasan', pasaron === 30, String(pasaron));
ck('el 31 recibe 429', primero429?.i === 30, JSON.stringify(primero429?.i));
ck('y el motivo dice cuanto esperar', /\d+ segundos/.test(primero429?.r.b?.motivo ?? ''), JSON.stringify(primero429?.r.b));

console.log('\n=== es de esa persona ===');
const r = await mandar(beto, D);
ck('otra persona sigue escribiendo', r.s === 200, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail ? 1 : 0);
