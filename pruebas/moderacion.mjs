// Modulo G + H: moderacion y panel.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que el nivel de PLATAFORMA sea un ambito aparte del de conversacion:
//     ser dueno de un grupo no da acceso a la cola, y ser staff no da nada
//     dentro de un grupo.
//  2. Que la evidencia en claro entre SOLO por la ruta de denuncia y se borre
//     al cerrar el caso.
//  3. Que la escalada la decida el contador de advertencias y no el moderador.
//  4. Que una suspension con vencimiento VENZA.
//  5. Que la jerarquia impida que dos moderadores se sancionen mutuamente.
import { execSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

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
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);
const uuid = () => crypto.randomUUID();

// El arranque del staff NO es una ruta a proposito: una ruta de "hazme
// administrador" protegida por un secreto termina abierta en produccion. Aqui
// se hace lo mismo que hace la variable de entorno al levantar el servidor.
const sembrar = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

const jefe = await reg('gj');      // propietario (100)
const modera = await reg('gm');    // moderador (50)
const mod2 = await reg('gn');      // otro moderador, para probar jerarquia
const acosa = await reg('ga');     // el denunciado
const victima = await reg('gv');   // quien denuncia
const ajeno = await reg('gx');     // no esta en la conversacion

sembrar(jefe.user, 100);

console.log('\n=== el nivel de plataforma es un ambito aparte ===');
let r = await get('/v1/moderacion/cola', victima.t);
ck('quien no es staff no ve la cola', r.s === 404, String(r.s));
r = await get('/v1/panel/resumen', victima.t);
ck('ni el panel', r.s === 404, String(r.s));

r = await put(`/v1/panel/usuarios/${modera.user}/staff`, victima.t, { nivel: 50 });
ck('ni puede nombrar staff', r.s === 404, String(r.s));

r = await put(`/v1/panel/usuarios/${modera.user}/staff`, jefe.t, { nivel: 50 });
ck('el propietario nombra moderador', r.s === 200 && r.b.staffNivel === 50, JSON.stringify(r.b).slice(0, 140));
r = await put(`/v1/panel/usuarios/${mod2.user}/staff`, jefe.t, { nivel: 50 });
ck('y a un segundo moderador', r.s === 200 && r.b.staffNivel === 50);

r = await get('/v1/moderacion/cola', modera.t);
ck('un moderador SI ve la cola', r.s === 200, String(r.s));

r = await put(`/v1/panel/usuarios/${acosa.user}/staff`, modera.t, { nivel: 50 });
ck('un moderador NO puede nombrar staff (hace falta administrador)', r.s === 404, String(r.s));

r = await put(`/v1/panel/usuarios/${acosa.user}/staff`, jefe.t, { nivel: 100 });
ck('nadie puede dar un nivel igual al propio', r.s === 403, String(r.s));

r = await put(`/v1/panel/usuarios/${jefe.user}/staff`, jefe.t, { nivel: 0 });
ck('ni cambiar su propio nivel', r.s === 400, String(r.s));

console.log('\n=== una conversacion para tener algo que denunciar ===');
r = await post('/v1/conversaciones/directa', victima.t, { usernameDestino: acosa.user });
ck('se abre una directa', r.s === 200, JSON.stringify(r.b).slice(0, 140));
const DIRECTA = r.b.id;

r = await post('/v1/conversaciones/grupo', acosa.t, { nombre: 'Grupo turbio ' + S, usernames: [victima.user] });
ck('se crea un grupo', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
const GRUPO = r.b.id;

const MSG = uuid();
r = await post('/v1/mensajes', acosa.t, { mensajeId: MSG, conversacionId: GRUPO });
ck('el denunciado manda un mensaje en el grupo', r.s === 200, JSON.stringify(r.b).slice(0, 140));

console.log('\n=== G.1 denunciar ===');
r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'inventado', motivo: 'spam' });
ck('un tipo desconocido se rechaza', r.s === 400, String(r.s));

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'usuario', objetivoUsuario: acosa.user, motivo: 'inventado' });
ck('un motivo fuera de la lista se rechaza', r.s === 400, String(r.s));

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'usuario', objetivoUsuario: victima.user, motivo: 'spam' });
ck('no se puede denunciar a uno mismo', r.s === 400, String(r.s));

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'usuario', objetivoUsuario: 'nadie' + S, motivo: 'spam' });
ck('un usuario inexistente da 404', r.s === 404, String(r.s));

r = await post('/v1/moderacion/denuncias', victima.t, {
  tipo: 'mensaje', objetivoMensaje: MSG, motivo: 'acoso', detalle: 'me amenazo',
  evidencia: [
    { autor: acosa.user, enviadoEn: Date.now() - 6000, contenido: 'te voy a encontrar' },
    { autor: victima.user, enviadoEn: Date.now() - 3000, contenido: 'dejame en paz' },
  ],
});
ck('se denuncia un mensaje con evidencia', r.s === 200 && r.b.estado === 'pendiente', JSON.stringify(r.b).slice(0, 160));
const DEN_MSG = r.b.id;

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'mensaje', objetivoMensaje: MSG, motivo: 'spam' });
ck('la misma persona no denuncia dos veces el mismo mensaje', r.s === 409, String(r.s));

r = await post('/v1/moderacion/denuncias', ajeno.t, { tipo: 'mensaje', objetivoMensaje: MSG, motivo: 'spam' });
ck('quien no esta en la conversacion no puede denunciar ese mensaje', r.s === 404, String(r.s));

r = await post('/v1/moderacion/denuncias', acosa.t, { tipo: 'mensaje', objetivoMensaje: MSG, motivo: 'spam' });
ck('no se denuncia un mensaje propio', r.s === 400, String(r.s));

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'grupo', objetivoConversacion: GRUPO, motivo: 'estafa' });
ck('se denuncia un grupo', r.s === 200, String(r.s));
const DEN_GRUPO = r.b.id;

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'grupo', objetivoConversacion: DIRECTA, motivo: 'spam' });
ck('una conversacion directa no se denuncia como grupo', r.s === 404, String(r.s));

r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'mensaje', motivo: 'spam' });
ck('tipo mensaje sin mensaje se rechaza', r.s === 400, String(r.s));

console.log('\n=== G.2 la cola de revision ===');
// Una segunda persona denuncia al mismo: es lo que convierte un caso en un
// patron, y lo que la cola tiene que dejar ver sin abrir cada denuncia.
r = await post('/v1/moderacion/denuncias', ajeno.t, { tipo: 'usuario', objetivoUsuario: acosa.user, motivo: 'acoso' });
ck('otra persona denuncia al mismo usuario', r.s === 200, String(r.s));

// La cola se recorre CON EL CURSOR, y no pidiendo un limite grande.
//
// Ordena por fecha ascendente -lo mas viejo primero, que es el orden correcto
// para triar- y el limite esta topado en 200. Las denuncias que crea esta suite
// quedan pendientes, asi que cada corrida deja unas cuantas mas en la base de
// desarrollo; al pasar del tope, la denuncia que la suite acaba de crear caia
// fuera de la pagina y la prueba fallaba por la PROFUNDIDAD ACUMULADA de la
// cola y no por nada del servidor. Pedir `limite=500` no lo arreglaba: se
// recorta a 200 en silencio y solo corre el problema mas lejos.
//
// Paginar de verdad lo vuelve independiente del historial Y prueba el cursor.
async function colaEntera(token, tam) {
  const todas = [];
  let cursor = '';
  // El tope de vueltas es un cinturon contra un cursor roto que no avanza, y
  // por eso se calcula sobre lo que hay que recorrer en vez de ser un numero
  // fijo. Con `400` fijo y paginas de tres, el techo eran 1200 filas: el dia
  // que la cola de desarrollo paso de 1200 -hoy tiene 1217- la pasada de tres
  // en tres se cortaba sola y la prueba fallaba con "1200 vs 1217", que se lee
  // como un defecto del cursor y no lo era. **Otra vez dependencia del
  // corpus**, y en la misma funcion que advierte de ella.
  const tope = Math.ceil(50_000 / tam) + 10;
  for (let vuelta = 0; ; vuelta++) {
    // Y si el cinturon se activa, se ROMPE en vez de devolver media cola: una
    // lista truncada en silencio hace que la comparacion de abajo mienta sobre
    // que fue lo que fallo.
    if (vuelta >= tope) throw new Error(`colaEntera: ${tope} vueltas de ${tam} sin terminar`);
    const pagina = (await get(`/v1/moderacion/cola?limite=${tam}${cursor}`, token)).b?.denuncias || [];
    todas.push(...pagina);
    if (pagina.length < tam) break;
    // El cursor es el ID de la ultima fila, no su fecha: `creadaEn` viaja
    // truncado a milisegundos y la columna es de microsegundos, asi que un
    // corte armado con la fecha del cliente vuelve a incluir esa misma fila.
    cursor = `&despues=${pagina[pagina.length - 1].id}`;
  }
  return todas;
}

const cola = await colaEntera(modera.t, 200);
ck('la cola trae lo pendiente', cola.length >= 2, String(cola.length));

// ATENCION AL TAMANO DE PAGINA. Con `limite=200` y una cola de desarrollo de
// cien y pico, todo entra en UNA pagina: el bucle corta en la primera vuelta,
// el cursor no se usa nunca y las dos comprobaciones de abajo dan verde sin
// haber probado nada. Es el mismo verde falso que ya se colo dos veces en este
// proyecto -la prueba del bus y el barrido de `ajeno.mjs`-.
//
// Por eso la pasada que PRUEBA el cursor va de tres en tres: fuerza decenas de
// paginas pase lo que pase con la profundidad de la cola.
const porTres = await colaEntera(modera.t, 3);
ck('paginar de tres en tres da exactamente la misma cola que de una sola vez',
   porTres.length === cola.length &&
   porTres.every((d, i) => d.id === cola[i].id),
   `${porTres.length} paginando vs ${cola.length} de una`);

// Si el cursor repitiera, un moderador trabajaria dos veces la misma denuncia;
// si se saltara filas, habria denuncias que nadie mira jamas.
const ids = porTres.map((d) => d.id);
ck('el cursor no devuelve la misma denuncia dos veces',
   new Set(ids).size === ids.length, `${ids.length} filas, ${new Set(ids).size} distintas`);

const ordenada = porTres.every((d, i) => i === 0 || porTres[i - 1].creadaEn <= d.creadaEn);
ck('la cola sale de lo mas viejo a lo mas nuevo, tambien entre paginas', ordenada);

// Un cursor que no es un id se ignora y se empieza por el principio: es lo
// unico seguro que se puede hacer con algo que no se entiende.
const basura = (await get('/v1/moderacion/cola?limite=5&despues=no-es-un-uuid', modera.t)).b?.denuncias || [];
ck('un cursor ilegible se ignora y se empieza por el principio',
   basura[0]?.id === cola[0]?.id, `${basura[0]?.id} vs ${cola[0]?.id}`);

// Un id con forma de uuid que no existe CORTA la cola, no la reinicia:
// devolver el principio ante un cursor desconocido es como se arma un bucle
// infinito en el cliente que pagina.
const fantasma = (await get(`/v1/moderacion/cola?limite=5&despues=${crypto.randomUUID()}`, modera.t)).b?.denuncias || [];
ck('un cursor que apunta a una denuncia inexistente termina la cola',
   fantasma.length === 0, String(fantasma.length));

// El cursor respeta el filtro de estado: paginar dentro de 'resuelta' no puede
// arrastrar pendientes.
const res1 = (await get('/v1/moderacion/cola?estado=resuelta&limite=3', modera.t)).b?.denuncias || [];
if (res1.length === 3) {
  const res2 = (await get(`/v1/moderacion/cola?estado=resuelta&limite=3&despues=${res1[2].id}`, modera.t)).b?.denuncias || [];
  ck('paginar dentro de un estado no arrastra denuncias de otro',
     res2.every((d) => d.estado === 'resuelta'), JSON.stringify(res2.map((d) => d.estado)));
} else {
  ck('paginar dentro de un estado no arrastra denuncias de otro', true, 'sin suficientes resueltas');
}

const mia = cola.find((d) => d.id === DEN_MSG);
ck('la denuncia del mensaje esta en la cola', !!mia);
ck('la cola dice quien denuncio', mia?.denunciante === victima.user, mia?.denunciante);
ck('y a quien', mia?.objetivoUsuario === acosa.user, mia?.objetivoUsuario);
// Dos, no tres: la denuncia del GRUPO no apunta a una persona, asi que no
// suma al contador de nadie. Es correcto y conviene que el test lo fije.
ck('la cola cuenta cuantas denuncias acumula el denunciado', mia?.denunciasDelObjetivo === 2,
   String(mia?.denunciasDelObjetivo));
ck('y cuanta evidencia hay, sin mostrarla', mia?.evidencias === 2, String(mia?.evidencias));
ck('la cola NO trae el texto de la evidencia', !JSON.stringify(mia || {}).includes('te voy a encontrar'));

r = await get(`/v1/moderacion/denuncias/${DEN_MSG}`, victima.t);
ck('quien no es staff no abre el detalle', r.s === 404, String(r.s));

r = await get(`/v1/moderacion/denuncias/${DEN_MSG}`, modera.t);
ck('el moderador abre el detalle', r.s === 200, String(r.s));
ck('y ahi SI esta el texto que entrego el denunciante',
   (r.b.evidencia || []).some((e) => e.contenido === 'te voy a encontrar'),
   JSON.stringify(r.b.evidencia).slice(0, 160));
ck('la evidencia conserva el orden', r.b.evidencia?.[0]?.autor === acosa.user);

console.log('\n=== tomar: dos moderadores no trabajan la misma denuncia ===');
r = await post(`/v1/moderacion/denuncias/${DEN_MSG}/tomar`, modera.t);
ck('el primero la toma', r.s === 200 && r.b.estado === 'en_revision', JSON.stringify(r.b).slice(0, 140));
ck('y queda como revisor', r.b.revisor === modera.user, r.b.revisor);

r = await post(`/v1/moderacion/denuncias/${DEN_MSG}/tomar`, mod2.t);
ck('el segundo recibe 409', r.s === 409, String(r.s));

console.log('\n=== G.3 y G.4 advertir y acumular ===');
r = await post(`/v1/moderacion/denuncias/${DEN_MSG}/resolver`, modera.t,
               { accion: 'advertir', nota: 'amenazas directas' });
ck('advertir resuelve la denuncia', r.s === 200 && r.b.accion === 'advertir', JSON.stringify(r.b).slice(0, 160));
ck('y deja UNA advertencia viva', r.b.advertenciasVigentes === 1, String(r.b.advertenciasVigentes));
ck('una sola advertencia no escala', r.b.escaladaAutomatica === null, String(r.b.escaladaAutomatica));

r = await post(`/v1/moderacion/denuncias/${DEN_MSG}/resolver`, modera.t, { accion: 'advertir' });
ck('una denuncia cerrada no se resuelve dos veces', r.s === 409, String(r.s));

r = await get(`/v1/moderacion/denuncias/${DEN_MSG}`, modera.t);
ck('al cerrar, la evidencia en claro se borra', (r.b.evidencia || []).length === 0,
   JSON.stringify(r.b.evidencia));
ck('pero la denuncia queda como resuelta', r.b.cabecera.estado === 'resuelta', r.b.cabecera.estado);

// Dos advertencias mas para llegar al tope de tres.
for (const n of [2, 3]) {
  r = await post('/v1/moderacion/denuncias', victima.t, {
    tipo: 'usuario', objetivoUsuario: acosa.user, motivo: 'acoso', detalle: 'ronda ' + n,
  });
  ck('se puede volver a denunciar tras cerrar la anterior ' + n, r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 90));
  const d = r.b.id;
  r = await post(`/v1/moderacion/denuncias/${d}/resolver`, modera.t, { accion: 'advertir', nota: 'ronda ' + n });
  if (n === 2) {
    ck('la segunda advertencia suma', r.b.advertenciasVigentes === 2, String(r.b.advertenciasVigentes));
    ck('y todavia no escala', r.b.escaladaAutomatica === null, String(r.b.escaladaAutomatica));
  } else {
    ck('la tercera advertencia dispara la suspension automatica',
       r.b.escaladaAutomatica === 'suspension_72h', String(r.b.escaladaAutomatica));
  }
}

console.log('\n=== lo que ve el sancionado ===');
r = await get('/v1/moderacion/mi-estado', acosa.t);
ck('el sancionado puede leer su propio estado', r.s === 200, String(r.s));
ck('ve sus tres advertencias', r.b.advertenciasVigentes === 3, String(r.b.advertenciasVigentes));
ck('ve que esta suspendido', r.b.suspendido === true, String(r.b.suspendido));
ck('y POR QUE', (r.b.suspensionMotivo || '').includes('advertencias'), r.b.suspensionMotivo);
ck('la suspension automatica tiene fecha de fin', r.b.suspendidoHasta > Date.now(), String(r.b.suspendidoHasta));
ck('sabe cual es el tope', r.b.topeAdvertencias === 3, String(r.b.topeAdvertencias));
const ADV = r.b.advertencias?.[0]?.id;
ck('cada advertencia trae su motivo', !!r.b.advertencias?.[0]?.motivo, JSON.stringify(r.b.advertencias?.[0]));
ck('y arranca sin reconocer', r.b.advertencias?.[0]?.reconocida === false);

r = await post(`/v1/moderacion/advertencias/${ADV}/reconocer`, acosa.t);
ck('se puede reconocer una advertencia', r.s === 204, String(r.s));
r = await get('/v1/moderacion/mi-estado', acosa.t);
ck('y queda anotado como leida', r.b.advertencias.find((a) => a.id === ADV)?.reconocida === true);
ck('reconocerla NO la borra', r.b.advertenciasVigentes === 3, String(r.b.advertenciasVigentes));

console.log('\n=== una suspension corta lo que produce contenido ===');
r = await post('/v1/mensajes', acosa.t, { mensajeId: uuid(), conversacionId: GRUPO });
ck('un suspendido no puede mandar mensajes', r.s === 403, String(r.s));
ck('y se le dice que es por la suspension', (r.b?.motivo || '').includes('suspendida'),
   JSON.stringify(r.b).slice(0, 140));

r = await get('/v1/moderacion/mi-estado', acosa.t);
ck('pero SI puede entrar a ver por que: una sancion sin explicacion no corrige nada', r.s === 200);

console.log('\n=== H: suspender y restaurar a mano ===');
r = await post(`/v1/panel/usuarios/${acosa.user}/restaurar`, modera.t);
ck('un moderador no restaura cuentas (hace falta administrador)', r.s === 404, String(r.s));

r = await post(`/v1/panel/usuarios/${acosa.user}/restaurar`, jefe.t);
ck('el administrador restaura', r.s === 200 && r.b.suspendido === false, JSON.stringify(r.b).slice(0, 140));

r = await post('/v1/mensajes', acosa.t, { mensajeId: uuid(), conversacionId: GRUPO });
ck('y vuelve a poder escribir', r.s === 200, String(r.s));

r = await post(`/v1/panel/usuarios/${acosa.user}/suspender`, jefe.t, { motivo: '' });
ck('una suspension sin motivo se rechaza', r.s === 400, String(r.s));

r = await post(`/v1/panel/usuarios/${jefe.user}/suspender`, jefe.t, { motivo: 'prueba' });
ck('nadie se suspende a si mismo', r.s === 400, String(r.s));

r = await post(`/v1/panel/usuarios/${mod2.user}/suspender`, jefe.t, { motivo: 'prueba', horas: 0 });
ck('se puede suspender con vencimiento', r.s === 200, JSON.stringify(r.b).slice(0, 140));
ck('una suspension de 0 horas ya vencio: NO cuenta como activa', r.b.suspendido === false,
   JSON.stringify(r.b).slice(0, 140));
r = await get('/v1/moderacion/cola', mod2.t);
ck('y el moderador con suspension vencida sigue trabajando', r.s === 200, String(r.s));

console.log('\n=== jerarquia: dos moderadores no se sancionan ===');
r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'usuario', objetivoUsuario: mod2.user, motivo: 'spam' });
ck('se puede denunciar a un moderador', r.s === 200, String(r.s));
const DEN_MOD = r.b.id;
r = await post(`/v1/moderacion/denuncias/${DEN_MOD}/resolver`, modera.t, { accion: 'advertir' });
ck('pero un moderador no lo sanciona: mismo nivel', r.s === 403, String(r.s));
r = await post(`/v1/moderacion/denuncias/${DEN_MOD}/resolver`, jefe.t, { accion: 'advertir', nota: 'ojo' });
ck('el propietario si, porque esta por encima', r.s === 200, String(r.s));

console.log('\n=== silenciar y expulsar son por conversacion ===');
r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'grupo', objetivoConversacion: GRUPO, motivo: 'spam', detalle: 'otra' });
const DEN_G2 = r.b?.id;
r = await post(`/v1/moderacion/denuncias/${DEN_G2}/resolver`, modera.t, { accion: 'silenciar' });
ck('una denuncia de grupo sin persona no permite silenciar', r.s === 400, String(r.s));

r = await post(`/v1/moderacion/denuncias/${DEN_GRUPO}/resolver`, modera.t, { accion: 'descartar', nota: 'sin fundamento' });
ck('descartar cierra sin sancion', r.s === 200, String(r.s));
r = await get(`/v1/moderacion/denuncias/${DEN_GRUPO}`, modera.t);
ck('y queda como descartada, no como resuelta', r.b.cabecera.estado === 'descartada', r.b.cabecera.estado);

const MSG2 = uuid();
await post('/v1/mensajes', acosa.t, { mensajeId: MSG2, conversacionId: GRUPO });
r = await post('/v1/moderacion/denuncias', victima.t, { tipo: 'mensaje', objetivoMensaje: MSG2, motivo: 'acoso' });
const DEN_EXP = r.b?.id;
r = await post(`/v1/moderacion/denuncias/${DEN_EXP}/resolver`, modera.t, { accion: 'expulsar', nota: 'fuera' });
ck('expulsar por moderacion funciona', r.s === 200, JSON.stringify(r.b).slice(0, 140));
r = await post('/v1/mensajes', acosa.t, { mensajeId: uuid(), conversacionId: GRUPO });
ck('y el expulsado ya no escribe ahi', r.s !== 200, String(r.s));

console.log('\n=== G.5 limites de ritmo ===');
let ultimo = 0;
for (let i = 0; i < 13; i++) {
  const rr = await fetch(BASE + '/v1/sesion', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'nadie' + S, password: 'x', etiquetaDispositivo: 't', identidadPub: b64('k'), hardwareHash: b64('h'), hardwareNivel: 'SOFTWARE_DEV' }),
  });
  ultimo = rr.status;
  if (rr.status === 429) break;
}
ck('probar contrasenas en rafaga termina en 429', ultimo === 429, String(ultimo));

console.log('\n=== G.6 registro de eventos de seguridad ===');
r = await get('/v1/moderacion/mis-eventos', victima.t);
ck('cada uno ve los eventos de su cuenta', r.s === 200, String(r.s));
const tipos = (r.b.eventos || []).map((e) => e.tipo);
ck('la denuncia queda anotada', tipos.includes('denuncia_creada'), JSON.stringify(tipos).slice(0, 140));

r = await get('/v1/moderacion/mis-eventos', acosa.t);
const tiposA = (r.b.eventos || []).map((e) => e.tipo);
ck('el sancionado ve que le advirtieron', tiposA.includes('advertencia_recibida'), JSON.stringify(tiposA).slice(0, 140));
ck('y que lo suspendieron', tiposA.includes('cuenta_suspendida'));

// El alta de la cuenta: es el primer evento y el que da sentido al resto de la
// lista. Sin el, "Actividad de la cuenta" arranca en cualquier parte y no se
// puede saber si falta algo.
ck('el alta de la cuenta queda anotada', tipos.includes('registro'), JSON.stringify(tipos).slice(0, 160));
// Cambiar la clave de identidad se anota para el DUENO de la cuenta, aparte de
// avisarle a quien habla con el. Dos registros del mismo hecho, para dos
// preguntas distintas.
const juegoClaves = (n) => ({
  registrationId: 7000 + n,
  identidad: b64(randomBytes(33)),
  firmada: { keyId: 1, publica: b64(randomBytes(33)), firma: b64(randomBytes(64)) },
  kyber: { keyId: 1, publica: b64(randomBytes(1568)), firma: b64(randomBytes(64)) },
  unicas: [{ keyId: 101, publica: b64(randomBytes(33)) }],
});
await put('/v1/claves', ajeno.t, juegoClaves(1));
r = await get('/v1/moderacion/mis-eventos', ajeno.t);
ck('publicar claves la primera vez NO es un cambio de identidad',
   !(r.b.eventos || []).map((e) => e.tipo).includes('clave_identidad_cambiada'));

await put('/v1/claves', ajeno.t, juegoClaves(2));
r = await get('/v1/moderacion/mis-eventos', ajeno.t);
ck('cambiar la clave de identidad SI queda anotado en la cuenta',
   (r.b.eventos || []).map((e) => e.tipo).includes('clave_identidad_cambiada'),
   JSON.stringify((r.b.eventos || []).map((e) => e.tipo)).slice(0, 160));

r = await get(`/v1/panel/usuarios/${acosa.user}/eventos`, victima.t);
ck('nadie lee los eventos de otra cuenta sin ser staff', r.s === 404, String(r.s));
r = await get(`/v1/panel/usuarios/${acosa.user}/eventos`, modera.t);
ck('un moderador si', r.s === 200 && (r.b.eventos || []).length > 0, String(r.s));

console.log('\n=== H: el panel ===');
r = await get('/v1/panel/resumen', jefe.t);
ck('el resumen responde', r.s === 200, String(r.s));
ck('dice mi nivel para que la interfaz decida', r.b.miNivel === 100, String(r.b.miNivel));
ck('cuenta advertencias vigentes', r.b.advertenciasVigentes >= 3, String(r.b.advertenciasVigentes));
ck('agrupa lo abierto por motivo', typeof r.b.porMotivo === 'object', JSON.stringify(r.b.porMotivo));
ck('cuenta los limites excedidos del dia', r.b.limitesExcedidosHoy >= 1, String(r.b.limitesExcedidosHoy));

// §10: el tablero tambien tiene que decir de que tamano es la plataforma, no
// solo que hay pendiente. Se comprueba por DELTAS y no por valores absolutos:
// la base la comparten todas las pruebas, asi que lo unico que se puede
// afirmar de un contador global es cuanto se movio cuando movimos algo.
console.log('\n=== §10: las metricas de plataforma ===');
const antes = (await get('/v1/panel/resumen', jefe.t)).b;

ck('cuenta las cuentas registradas', antes.usuariosRegistrados >= 6, String(antes.usuariosRegistrados));
ck('cuenta a quien uso la cuenta en los ultimos 7 dias', antes.usuariosActivos7d >= 6, String(antes.usuariosActivos7d));
// Un activo es un registrado; si esto se invierte, se estan contando sesiones
// y no personas, que es justo el error que la consulta evita con DISTINCT.
ck('nadie puede estar activo sin estar registrado',
   antes.usuariosActivos7d <= antes.usuariosRegistrados,
   `${antes.usuariosActivos7d} > ${antes.usuariosRegistrados}`);
ck('cuenta los sobres que pasaron', antes.mensajesEnviados >= 1, String(antes.mensajesEnviados));
// Con una base de pruebas el conteo tiene que ser el de verdad: la estimacion
// solo entra pasado el millon de filas. Si esto fallara, el panel estaria
// dando un numero aproximado sin necesidad.
ck('con pocas filas el conteo es exacto, no estimado',
   antes.mensajesAproximados === false, String(antes.mensajesAproximados));
ck('cuenta los grupos creados', antes.gruposCreados >= 1, String(antes.gruposCreados));
ck('el almacenamiento viene en bytes y en numero de archivos',
   typeof antes.almacenamientoBytes === 'number' && typeof antes.almacenamientoArchivos === 'number',
   `${antes.almacenamientoBytes} / ${antes.almacenamientoArchivos}`);
ck('no se inventa uso de CPU ni memoria: el servidor no lo mide',
   antes.cpu === undefined && antes.memoria === undefined && antes.usoServidores === undefined,
   JSON.stringify(antes).slice(0, 160));

r = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Grupo de conteo ' + S, usernames: [ajeno.user] });
ck('se crea un grupo para medir el delta', r.s === 200, String(r.s));
const GRUPO_CONTEO = r.b.id;
r = await post('/v1/canales', jefe.t, { nombre: 'Canal de conteo ' + S, publico: false });
ck('se crea un canal para medir el delta', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
r = await post('/v1/mensajes', jefe.t, { mensajeId: uuid(), conversacionId: GRUPO_CONTEO });
ck('se manda un mensaje para medir el delta', r.s === 200, String(r.s));

const despues = (await get('/v1/panel/resumen', jefe.t)).b;
ck('un grupo nuevo mueve el contador de grupos',
   despues.gruposCreados === antes.gruposCreados + 1,
   `${antes.gruposCreados} -> ${despues.gruposCreados}`);
ck('un canal nuevo mueve el contador de canales',
   despues.canalesCreados === antes.canalesCreados + 1,
   `${antes.canalesCreados} -> ${despues.canalesCreados}`);
ck('un canal no se cuenta como grupo, ni al reves',
   despues.gruposCreados - antes.gruposCreados === 1 && despues.canalesCreados - antes.canalesCreados === 1,
   `${despues.gruposCreados - antes.gruposCreados} / ${despues.canalesCreados - antes.canalesCreados}`);
ck('un mensaje nuevo mueve el contador de mensajes',
   despues.mensajesEnviados === antes.mensajesEnviados + 1,
   `${antes.mensajesEnviados} -> ${despues.mensajesEnviados}`);
// El brief separa "suspendidos" de "registrados"; no puede salir la misma
// cifra en las dos casillas porque se copio el campo de al lado.
ck('los suspendidos siguen siendo su propia cuenta',
   despues.usuariosSuspendidos < despues.usuariosRegistrados,
   `${despues.usuariosSuspendidos} / ${despues.usuariosRegistrados}`);

// Son metricas del PANEL: el mismo listón que la cola, ni mas bajo ni mas alto.
r = await get('/v1/panel/resumen', ajeno.t);
ck('quien no es staff no ve las metricas de plataforma', r.s === 404, String(r.s));
r = await get('/v1/panel/resumen', modera.t);
ck('un moderador si las ve, como ve la cola',
   r.s === 200 && r.b.usuariosRegistrados >= 6, String(r.s) + ' ' + String(r.b?.usuariosRegistrados));

r = await get(`/v1/panel/usuarios?q=${acosa.user.slice(0, 4)}`, jefe.t);
ck('se buscan personas', r.s === 200 && (r.b.usuarios || []).length > 0, JSON.stringify(r.b).slice(0, 140));
const ficha = (r.b.usuarios || []).find((u) => u.username === acosa.user);
ck('la ficha trae las advertencias vivas', ficha?.advertenciasVigentes === 3, String(ficha?.advertenciasVigentes));
ck('y cuantas denuncias recibio', (ficha?.denunciasRecibidas || 0) >= 3, String(ficha?.denunciasRecibidas));

r = await get('/v1/panel/usuarios?q=a', jefe.t);
ck('una letra no alcanza para buscar: seria un listado de la plataforma', (r.b.usuarios || []).length === 0,
   String((r.b.usuarios || []).length));

console.log('\n=== lo que el panel NO puede hacer ===');
r = await get(`/v1/panel/usuarios?q=${acosa.user.slice(0, 4)}`, jefe.t);
ck('la ficha de una persona no incluye ni un mensaje',
   JSON.stringify(r.b).includes('cuerpo') === false && JSON.stringify(r.b).includes('mensajes') === false,
   JSON.stringify(r.b).slice(0, 160));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
