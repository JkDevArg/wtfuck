// L.1: el resto de la privacidad, presencia y confirmaciones de lectura.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que la presencia sea RECIPROCA: quien oculta su ultima conexion no ve la
//     de nadie. Sin eso, el ajuste es un espejo de una sola direccion.
//  2. Que ocultarse de la busqueda no vuelva a nadie inalcanzable: quien ya
//     habla con vos sigue pudiendo.
//  3. Que las confirmaciones de lectura sean reciprocas en las DOS mitades: el
//     que lee y el que escribio.
//  4. Que ocultar el nombre caiga al username y no deje un hueco.
import { execSync } from 'node:child_process';

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
const put = (r, t, b) => call('PUT', r, t, b);

// La presencia se escribe al conectar el socket; en las pruebas se pone a mano
// porque abrir un WebSocket por usuario para comprobar una columna es un coste
// que no compra nada.
const presenciaHace = (username, minutos) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET ultima_vez = now() - interval '${minutos} minutes' WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

const ana = await reg('la');
const beto = await reg('lb');
const extrano = await reg('lc');

const perfil = async (quien, deQuien) => get(`/v1/usuarios/${deQuien.user}`, quien.t);
const guardarPriv = (quien, cambios) => put('/v1/perfil/privacidad', quien.t, cambios);

let r = await get('/v1/perfil/privacidad', ana.t);
ck('la privacidad trae los ajustes nuevos',
   r.b.ultimaVez !== undefined && r.b.nombre !== undefined &&
   r.b.busqueda !== undefined && r.b.lectura !== undefined, JSON.stringify(r.b));
ck('la ultima conexion empieza en "conocidos", no en "todos"',
   r.b.ultimaVez === 'conocidos', r.b.ultimaVez);
const PRIV = r.b;

console.log('\n=== presencia ===');
presenciaHace(ana.user, 5);
r = await perfil(beto, ana);
ck('por defecto un extrano NO ve la ultima conexion de ana', r.b.ultimaVez === 0,
   String(r.b.ultimaVez));

await guardarPriv(ana, { ...PRIV, ultimaVez: 'todos' });
r = await perfil(beto, ana);
ck('con "todos" si la ve', r.b.ultimaVez > 0, String(r.b.ultimaVez));
ck('y no dice que esta en linea, porque no tiene socket abierto',
   r.b.enLinea === false, String(r.b.enLinea));

console.log('\n=== y es reciproca ===');
await guardarPriv(beto, { ...PRIV, ultimaVez: 'nadie' });
r = await perfil(beto, ana);
ck('quien OCULTA la suya no ve la de los demas, aunque el otro la comparta',
   r.b.ultimaVez === 0, String(r.b.ultimaVez));

r = await perfil(ana, beto);
ck('y el que la comparte tampoco ve la del que la oculta', r.b.ultimaVez === 0,
   String(r.b.ultimaVez));

await guardarPriv(beto, { ...PRIV, ultimaVez: 'todos' });
r = await perfil(beto, ana);
ck('al volver a compartirla, vuelve a verla', r.b.ultimaVez > 0, String(r.b.ultimaVez));

r = await perfil(ana, ana);
ck('el propio perfil siempre trae la propia ultima conexion', r.b.ultimaVez > 0);

console.log('\n=== quien ve mi nombre ===');
await put('/v1/perfil', ana.t, { nombreMostrado: 'Ana Real', estadoTexto: 'x' });
r = await perfil(extrano, ana);
ck('por defecto el nombre se ve', r.b.nombreMostrado === 'Ana Real', r.b.nombreMostrado);

await guardarPriv(ana, { ...PRIV, ultimaVez: 'todos', nombre: 'nadie' });
r = await perfil(extrano, ana);
ck('con "nadie" el nombre viene vacio y la app cae al usuario',
   r.b.nombreMostrado === '', JSON.stringify(r.b.nombreMostrado));
ck('pero el username sigue ahi: es la direccion, no se puede ocultar',
   r.b.username === ana.user, r.b.username);

r = await perfil(ana, ana);
ck('y yo sigo viendo mi propio nombre', r.b.nombreMostrado === 'Ana Real');

console.log('\n=== quien me encuentra por mi usuario ===');
await guardarPriv(ana, { ...PRIV, ultimaVez: 'todos', busqueda: 'nadie' });
r = await perfil(extrano, ana);
ck('un extrano ya no la encuentra', r.s === 404, String(r.s));
ck('y responde como si no existiera, no "no puedo decirte"',
   (r.b?.motivo || '').includes('No existe'), r.b?.motivo);

// Pero quien ya habla con ella sigue alcanzandola.
r = await post('/v1/conversaciones/directa', beto.t, { usernameDestino: ana.user });
const DIRECTA = r.b?.id;
ck('beto ya tenia como abrir una directa antes del cambio', !!DIRECTA, JSON.stringify(r.b));

await guardarPriv(ana, { ...PRIV, ultimaVez: 'todos', busqueda: 'conocidos' });
r = await perfil(beto, ana);
ck('con "conocidos", quien ya habla con ella si la encuentra', r.s === 200, String(r.s));
r = await perfil(extrano, ana);
ck('y un extrano no', r.s === 404, String(r.s));

r = await get('/v1/conversaciones', beto.t);
ck('la conversacion existente sigue en la lista: ocultarse no es bloquear',
   (r.b || []).some((x) => x.id === DIRECTA));

console.log('\n=== confirmaciones de lectura ===');
await guardarPriv(ana, { ...PRIV, busqueda: 'todos', ultimaVez: 'todos' });

// beto escribe a ana. Se registra el metadato del mensaje por la ruta HTTP.
const MSG1 = crypto.randomUUID();
r = await post('/v1/mensajes', beto.t, { mensajeId: MSG1, conversacionId: DIRECTA });
ck('beto manda un mensaje', r.s === 200 || r.s === 202, String(r.s));

r = await get(`/v1/conversaciones/${DIRECTA}/leidos`, beto.t);
ck('todavia no esta leido', !(r.b.mensajeIds || []).includes(MSG1), JSON.stringify(r.b));

// Ana lo lee. El acuse va por socket, pero la consulta HTTP es la que usa la
// app al abrir el chat; se comprueba el efecto por la via que se puede probar
// sin WebSocket: la tabla.
execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"INSERT INTO lectura (mensaje_id, usuario_id) VALUES ('${MSG1}', '${ana.id}') ON CONFLICT DO NOTHING"`,
  { stdio: 'pipe' },
);
r = await get(`/v1/conversaciones/${DIRECTA}/leidos`, beto.t);
ck('tras leerlo, el remitente lo ve como leido',
   (r.b.mensajeIds || []).includes(MSG1), JSON.stringify(r.b));

r = await get(`/v1/conversaciones/${DIRECTA}/leidos`, ana.t);
ck('y ana NO ve como "leidos" los mensajes de beto: solo los propios',
   !(r.b.mensajeIds || []).includes(MSG1), JSON.stringify(r.b));

console.log('\n=== validacion ===');
r = await guardarPriv(ana, { ...PRIV, ultimaVez: 'a veces' });
ck('un nivel inventado se rechaza', r.s === 400, String(r.s));
r = await guardarPriv(ana, { ...PRIV, busqueda: '' });
ck('y un nivel vacio tambien', r.s === 400, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
