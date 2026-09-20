// Modulo N · Que nadie pueda inyectar eventos por el bus.
//
// ## El hallazgo que fija esta suite
//
// Un `Bajada.Evento` viaja EN CLARO y el cliente lo **obedece**:
// `mensaje_retirado` borra un mensaje del telefono, `expulsado` lo marca fuera
// de un grupo, `dispositivo_revocado` y `sancion` cambian el estado de la
// cuenta. Eso es correcto: el servidor es quien tiene autoridad para decir esas
// cosas.
//
// Lo que convertia al bus en un camino de entrada es que la primera version
// publicaba `instancia|json` sin firmar. Quien pudiera publicar en
// `wtfuck:disp:<uuid>` —el Redis quedaba expuesto en el host— podia meterle a
// un cliente conectado un evento que nunca ocurrio. Y el id de un dispositivo
// no es secreto: se lo dan a cualquier participante de la conversacion para
// poder cifrarle.
//
// Aqui se hace exactamente ese ataque y se comprueba que no pasa nada.
//
// ## Por que se habla RESP a mano
//
// Para no agregarle al repositorio una dependencia de cliente de Redis que solo
// usaria esta prueba. `PUBLISH` en RESP son cuatro lineas; el resto del
// protocolo no hace falta.
import net from 'node:net';

const A = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const REDIS_HOST = process.env.WTFUCK_REDIS_HOST ?? '127.0.0.1';
const REDIS_PORT = Number(process.env.WTFUCK_REDIS_PORT ?? 6380);
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

/** `PUBLISH canal mensaje` hablando RESP a mano. Devuelve cuantos lo recibieron. */
function publicar(canal, mensaje) {
  return new Promise((res, rej) => {
    const s = net.createConnection({ host: REDIS_HOST, port: REDIS_PORT }, () => {
      const bulk = (x) => `$${Buffer.byteLength(x)}\r\n${x}\r\n`;
      s.write(`*3\r\n${bulk('PUBLISH')}${bulk(canal)}${bulk(mensaje)}`);
    });
    let d = '';
    s.on('data', (c) => {
      d += c;
      if (d.includes('\r\n')) { s.end(); res(d.trim()); }
    });
    s.on('error', rej);
    setTimeout(() => { s.destroy(); rej(new Error('sin respuesta de Redis')); }, 3000);
  });
}

async function reg(u) {
  const r = await fetch(A + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dispositivo: j.dispositivoId, user: u + S };
}

function abrir(token) {
  const ws = new WebSocket(`${A.replace(/^http/, 'ws')}/v1/ws?token=${token}`);
  const recibidos = [];
  const listo = new Promise((res, rej) => {
    ws.addEventListener('open', () => res());
    ws.addEventListener('error', () => rej(new Error('no se pudo abrir el socket')));
  });
  ws.addEventListener('message', (e) => {
    try { recibidos.push(JSON.parse(e.data)); } catch { /* ignorar */ }
  });
  return { listo, recibidos, cerrar: () => ws.close() };
}

// Sin Redis alcanzable no hay ataque que hacer, y fingir que paso seria peor
// que omitirlo.
let hayRedis = true;
try {
  await publicar('wtfuck:prueba', 'ping');
} catch {
  hayRedis = false;
}

if (!hayRedis) {
  console.log('\n=== OMITIDA: no hay Redis alcanzable ===');
  console.log(`    ${REDIS_HOST}:${REDIS_PORT} no responde. Para correr esta suite:`);
  console.log('    1. docker compose up -d redis');
  console.log('    2. servidor con WTFUCK_REDIS_URL y WTFUCK_BUS_SECRETO');
  console.log('\n=== 0 pasan, 0 fallan ===');
  process.exit(0);
}

const victima = await reg('iv');
const s = abrir(victima.t);
await s.listo;
await new Promise((r) => setTimeout(r, 500));

const canal = `wtfuck:disp:${victima.dispositivo}`;

// Segunda condicion de entorno, y hace falta aparte de «hay Redis»: que el
// servidor este SUSCRITO a ese Redis.
//
// Con el contenedor levantado pero el servidor arrancado sin
// `WTFUCK_REDIS_URL`, Redis responde -o sea `hayRedis` da true- pero nadie
// escucha el canal de la victima, y entonces `PUBLISH` devuelve `:0`. En ese
// caso el ataque no llega a ninguna parte y la suite no esta probando nada.
//
// Antes eso se contaba como FALLA. Esta mal por los dos lados: un rojo ahi no
// significa que el arreglo se haya roto -significa que falta levantar la
// segunda instancia-, y un rojo que no distingue «falta entorno» de «hay un
// agujero» es un rojo al que se deja de hacer caso, que es la peor forma de
// perder una prueba de seguridad.
//
// La sonda publica basura en el canal de la victima. Es inofensiva: si algo
// asi llegara a pasar el filtro, las comprobaciones de mas abajo lo cazan.
const sonda = await publicar(canal, 'sonda|sin-firma|{}');
if (/^:0/.test(sonda)) {
  console.log('\n=== OMITIDA: hay Redis pero ninguna instancia suscrita ===');
  console.log(`    PUBLISH a ${canal} devolvio ${sonda}: cero suscriptores.`);
  console.log('    El servidor esta arrancado SIN WTFUCK_REDIS_URL, asi que el');
  console.log('    ataque no llegaria a ningun lado y el verde seria falso.');
  console.log('    Para correr esta suite:');
  console.log('    1. docker compose up -d redis');
  console.log('    2. servidor con WTFUCK_REDIS_URL y WTFUCK_BUS_SECRETO');
  console.log('\n=== 0 pasan, 0 fallan ===');
  s.cerrar();
  process.exit(0);
}

// El evento que se intenta colar. `expulsado` esta elegido a proposito: es de
// los que el cliente OBEDECE, no de los que solo muestra. Al recibirlo marca la
// conversacion como "ya no eres miembro" y esconde el campo de texto.
const evento = JSON.stringify({
  type: 'evento',
  eventoId: crypto.randomUUID(),
  tipo: 'expulsado',
  conversacionId: crypto.randomUUID(),
  nombreConversacion: 'Grupo cualquiera',
  actor: 'atacante',
  creadoEn: Date.now(),
  detalle: null,
});

console.log('\n=== el ataque: publicar un evento sin firma ===');
let r = await publicar(canal, `instancia-falsa|${evento}`);
// `:0` significa CERO suscriptores, o sea que el ataque NO llego a ninguna
// instancia. Darlo por bueno fue el primer error de esta suite: pasaba en verde
// contra el codigo vulnerable porque el mensaje no llegaba a ningun lado.
ck('el ataque llega a una instancia suscrita', /^:[1-9]/.test(r), `PUBLISH devolvio ${r}`);

await new Promise((res) => setTimeout(res, 1200));
ck('pero el cliente NO recibe nada',
   s.recibidos.length === 0, JSON.stringify(s.recibidos).slice(0, 220));

console.log('\n=== con una firma inventada tampoco ===');
r = await publicar(canal, `instancia-falsa|ZmlybWEtaW52ZW50YWRh|${evento}`);
ck('llega a la instancia', /^:[1-9]/.test(r), `PUBLISH devolvio ${r}`);
await new Promise((res) => setTimeout(res, 1200));
ck('y el cliente sigue sin recibir nada',
   s.recibidos.length === 0, JSON.stringify(s.recibidos).slice(0, 220));

console.log('\n=== ni con la firma de OTRO cuerpo ===');
// Si la firma no cubriera el cuerpo entero, una firma valida de cualquier
// mensaje serviria para todos. Se toma una firma con forma correcta y se le
// cambia el cuerpo debajo.
const otra = Buffer.from('x'.repeat(32)).toString('base64');
r = await publicar(canal, `instancia-falsa|${otra}|${evento}`);
ck('llega a la instancia', /^:[1-9]/.test(r), `PUBLISH devolvio ${r}`);
await new Promise((res) => setTimeout(res, 1200));
ck('y sigue sin pasar nada', s.recibidos.length === 0,
   JSON.stringify(s.recibidos).slice(0, 220));

console.log('\n=== el canal sigue vivo para lo legitimo ===');
// Que no se haya roto el bus de paso: un mensaje de verdad tiene que seguir
// llegando. Se comprueba con un evento REAL, generado por el servidor.
const otro = await reg('iw');
r = await fetch(A + '/v1/conversaciones/grupo', {
  method: 'POST',
  headers: { Authorization: 'Bearer ' + otro.t, 'Content-Type': 'application/json' },
  body: JSON.stringify({ nombre: 'Grupo inyeccion', usernames: [victima.user] }),
});
ck('se crea un grupo que la agrega', r.ok, String(r.status));

const hasta = Date.now() + 5000;
while (Date.now() < hasta && s.recibidos.length === 0) {
  await new Promise((res) => setTimeout(res, 100));
}
ck('el evento LEGITIMO si llega', s.recibidos.length > 0,
   'no llego ninguno: el bus quedo roto');

s.cerrar();
console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
