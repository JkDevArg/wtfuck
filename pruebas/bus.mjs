// Modulo N · Dos instancias del servidor, un solo mensaje.
//
// ## Que se comprueba, y por que hacen falta DOS servidores
//
// Los sockets viven en la memoria de cada proceso. Con una instancia eso es
// correcto y rapido. Con dos, un mensaje para alguien conectado a la instancia
// B no llega si lo manda alguien conectado a la A: el Hub de A mira su mapa, no
// lo encuentra, y deja el sobre en la base para el proximo reconectar.
//
// Esta suite es la unica que puede ver ese defecto, porque es la unica que abre
// los sockets en instancias DISTINTAS. Con una sola instancia pasaria igual sin
// bus y sin nada.
//
// ## Como se corre
//
//   docker compose up -d redis
//   # instancia A en 8300 y B en 8301, las dos con WTFUCK_REDIS_URL
//   node pruebas/bus.mjs
//
// Si la segunda instancia no responde, la suite lo dice y no finge: marcar
// verde un cruce entre instancias que no se hizo seria el peor resultado.
const A = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const B = process.env.WTFUCK_BASE_B ?? 'http://localhost:8301';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (base, m, ruta, t, body) => {
  const r = await fetch(base + ruta, {
    method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};

async function reg(base, u) {
  const r = await fetch(base + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dispositivo: j.dispositivoId, user: u + S };
}

function abrir(base, token) {
  const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/v1/ws?token=${token}`);
  const recibidos = [];
  const listo = new Promise((res, rej) => {
    ws.addEventListener('open', () => res());
    ws.addEventListener('error', () => rej(new Error('no se pudo abrir el socket')));
  });
  ws.addEventListener('message', (e) => {
    try { recibidos.push(JSON.parse(e.data)); } catch { /* ignorar */ }
  });
  return {
    listo,
    recibidos,
    enviar: (o) => ws.send(JSON.stringify(o)),
    cerrar: () => ws.close(),
    esperar: async (tipo, ms = 6000) => {
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

// La segunda instancia tiene que existir de verdad.
let hayB = true;
try {
  const r = await fetch(B + '/salud');
  if (!r.ok) throw new Error('respondio ' + r.status);
} catch (e) {
  hayB = false;
}

if (!hayB) {
  console.log('\n=== OMITIDA: no hay una segunda instancia ===');
  console.log(`    ${B} no responde. Para correr esta suite:`);
  console.log('    1. docker compose up -d redis');
  console.log('    2. levantar una segunda instancia con WTFUCK_PUERTO=8301');
  console.log('       y las dos con WTFUCK_REDIS_URL=redis://localhost:6380');
  console.log('\n=== 0 pasan, 0 fallan ===');
  process.exit(0);
}

console.log('\n=== las dos instancias ven la MISMA base ===');
// Se registra en A y se usa el token en B: si no compartieran base, el token
// de A no valdria en B y todo lo demas seria humo.
const ana = await reg(A, 'ba');
const beto = await reg(A, 'bb');
let r = await call(B, 'GET', '/v1/perfil', ana.t);
ck('un token emitido por A sirve en B', r.s === 200, JSON.stringify(r.b).slice(0, 120));

// La conversacion se abre ANTES de preguntar por la presencia, y el orden no es
// casual: `enLinea` esta detras de la privacidad de "ultima conexion", que por
// defecto es `conocidos`. Sin una conversacion directa, beto no es conocido de
// ana y el servidor le oculta la presencia -correctamente-, asi que preguntar
// primero daba false por privacidad y no por falta de bus. La primera version
// de esta suite se equivoco exactamente en eso, y el sintoma era identico al
// de un bus que no funciona.
console.log('\n=== un sobre que cruza de instancia ===');
r = await call(B, 'POST', '/v1/conversaciones/directa', beto.t, { usernameDestino: ana.user });
const conv = r.b?.id ?? r.b?.conversacionId;
ck('se abre la conversacion desde B', !!conv, JSON.stringify(r.b).slice(0, 140));

console.log('\n=== presencia cruzada ===');
// Ana conecta su socket a la instancia A. Beto le pregunta a B si esta en
// linea: la respuesta correcta es SI, y solo puede salir del bus.
const sAna = abrir(A, ana.t);
await sAna.listo;
// La marca se pone al conectar; se le da un instante al viaje de ida y vuelta.
await new Promise((res) => setTimeout(res, 600));

r = await call(B, 'GET', `/v1/usuarios/${ana.user}`, beto.t);
ck('B responde el perfil de ana', r.s === 200, JSON.stringify(r.b).slice(0, 120));
ck('y la ve EN LINEA teniendo el socket en A', r.b?.enLinea === true,
   `enLinea=${r.b?.enLinea} (sin bus esto da false)`);

console.log('\n=== un sobre que cruza de instancia ===');
r = await call(B, 'GET', `/v1/conversaciones/${conv}/destinos`, beto.t);
const destinos = r.b?.destinos ?? [];
ck('B conoce el dispositivo de ana', destinos.length === 1, JSON.stringify(destinos).slice(0, 200));

// Beto manda por SU socket, que esta en B. Ana escucha en A.
const sBeto = abrir(B, beto.t);
await sBeto.listo;

const idMensaje = crypto.randomUUID();
r = await call(B, 'POST', '/v1/mensajes', beto.t, {
  mensajeId: idMensaje, conversacionId: conv,
});
ck('se registra el metadato en B', r.s === 200, JSON.stringify(r.b).slice(0, 120));

sBeto.enviar({
  type: 'enviar',
  sobreId: idMensaje,
  conversacionId: conv,
  creadoEn: Date.now(),
  copias: destinos.map((d) => ({
    destinos: [d.dispositivoId],
    cuerpo: b64('sobre-que-cruza'),
    tipo: 0,
  })),
});

const entrega = await sAna.esperar('entrega');
ck('el sobre llega al socket de ana, que esta en la OTRA instancia',
   !!entrega, JSON.stringify(sAna.recibidos).slice(0, 220));
ck('con el id del mensaje que genero beto',
   entrega?.mensajeId === idMensaje, `${entrega?.mensajeId} vs ${idMensaje}`);
ck('y con el cuerpo intacto: el bus mueve bytes opacos, no los reinterpreta',
   entrega?.cuerpo === b64('sobre-que-cruza'), String(entrega?.cuerpo));

console.log('\n=== el acuse vuelve cruzando al revES ===');
// Ana acusa por A; el "entregado" tiene que llegarle a beto, que esta en B.
if (entrega) {
  sAna.enviar({ type: 'acuse', sobreIds: [entrega.sobreId] });
  const entregado = await sBeto.esperar('entregado');
  ck('beto recibe "entregado" desde la otra instancia', !!entregado,
     JSON.stringify(sBeto.recibidos).slice(0, 220));
  ck('con el id de SU mensaje', entregado?.sobreId === idMensaje, String(entregado?.sobreId));
} else {
  ck('beto recibe "entregado" desde la otra instancia', false, 'no hubo entrega que acusar');
  ck('con el id de SU mensaje', false, 'no hubo entrega que acusar');
}

console.log('\n=== al cerrar el socket la presencia se apaga ===');
sAna.cerrar();
await new Promise((res) => setTimeout(res, 800));
r = await call(B, 'GET', `/v1/usuarios/${ana.user}`, beto.t);
// Se borra al desconectar y no se espera el vencimiento: la marca vence a los
// 90 s solo para el caso de que la instancia MUERA sin poder limpiar.
ck('B ya no la ve en linea', r.b?.enLinea === false, `enLinea=${r.b?.enLinea}`);

sBeto.cerrar();

console.log('\n=== el limite de fallos de ingreso es COMPARTIDO ===');
// Es la unica suite que puede ver este defecto, por lo mismo que el resto: es
// la unica que habla con DOS instancias.
//
// El limitador guardaba sus marcas en un mapa del proceso. Con una sola
// instancia el numero era exacto; con dos detras de un balanceador, "8 fallos
// cada 15 minutos" eran 16 sin que nada en el codigo lo dijera, y con cuatro
// copias 32. El limite dejaba de ser el limite justo en el despliegue para el
// que esta arquitectura fue pensada.
//
// La prueba es directa: se gastan los fallos contra A y se comprueba que B ya
// los da por gastados. Antes del arreglo, B empezaba de cero.
const victima = await reg(A, 'victima');
const FALLOS = 8;  // Limitador.FALLOS_POR_USUARIO

const intentar = (base) => fetch(base + '/v1/sesion', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: victima.user, password: 'clave-que-no-es', etiquetaDispositivo: 't',
    identidadPub: b64('k'), hardwareHash: b64('HW-X'), hardwareNivel: 'SOFTWARE_DEV',
  }),
}).then((r) => r.status);

const codigos = [];
for (let i = 0; i < FALLOS; i++) codigos.push(await intentar(A));

// Los primeros tienen que ser 401 y no 429: si ya cortara antes de agotar el
// presupuesto, la prueba de abajo pasaria por el motivo equivocado.
ck('los fallos contra A se rechazan por clave, no por limite',
   codigos.every((c) => c === 401), JSON.stringify(codigos));

const enB = await intentar(B);
ck('B ya sabe que se agotaron los intentos (429, no 401)', enB === 429, `status=${enB}`);

// Y el corte es POR CUENTA: otra persona desde la misma IP tiene que poder
// entrar igual. Un limite que se lleva por delante a quien no hizo nada se
// acaba subiendo hasta que deja de servir.
const ajeno = await reg(A, 'ajeno');
const suyo = await fetch(B + '/v1/sesion', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: ajeno.user, password: 'clave-que-no-es', etiquetaDispositivo: 't',
    identidadPub: b64('k'), hardwareHash: b64('HW-Y'), hardwareNivel: 'SOFTWARE_DEV',
  }),
}).then((r) => r.status);
ck('a otra cuenta desde la misma IP no la corta el limite ajeno', suyo === 401, `status=${suyo}`);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
