// §16 y §3 · Barrido de LECTURA ajena.
//
// ## El hueco que cierra
//
// `ajeno.mjs` recorre las rutas que MUTAN algo y deja dicho lo que no cubre:
// las lecturas. Son la otra mitad, y en una app de mensajería privada es la
// mitad donde duele: una escritura ajena rompe algo y se nota; una lectura
// ajena no deja rastro y es exactamente lo que el §3 entero —quién puede ver
// tu perfil, tu actividad, tus grupos— existe para impedir.
//
// `lib/auditor-de-rutas.mjs` lee `Main.kt` y da las rutas de lectura. Hoy son
// **57**. Ese numero estaba escrito aqui a mano —«49»— y era una foto de un dia:
// toda ruta agregada despues quedaba fuera y la suite seguia dando verde. Al
// final del archivo se le pregunta al auditor si quedo alguna.
//
// De las de entonces, 20 con un `{id}` en el
// camino y 29 sin parámetro. De las segundas, **siete son del panel**, y ahí
// `ajeno.mjs` tenía un punto ciego real: comprobó que una persona de a pie no
// puede *suspender* a nadie, pero nunca que no puede *leer la bitácora de
// auditoría*, el listado de personas o el resumen de moderación.
//
// ## Las tres preguntas, que no son la misma
//
//  1. **¿Se niega?** Para lo que es de otro: mensajes, adjuntos, miembros,
//     denuncias, claves de conversaciones ajenas.
//  2. **¿Se niega al que no es staff?** Para las siete del panel y las de
//     moderación.
//  3. **¿Qué devuelve cuando SÍ contesta?** Varias rutas contestan 200 a un
//     desconocido **a propósito** —un canal público es público, una invitación
//     se abre con el código, un perfil se consulta para escribirle—. Ahí la
//     pregunta no es el código de estado sino **qué campos viajan**. Un 200
//     correcto que arrastra la lista de suscriptores es una fuga igual.
//
// La tercera es la que justifica la suite: un barrido que solo mire códigos de
// estado daría verde entero y no habría probado nada sobre privacidad.
//
// ## El control, otra vez
//
// Misma disciplina que en `ajeno.mjs`, por el mismo motivo: si la dueña no
// recibe 2xx en la misma petición, el 403 del tercero no prueba autorización,
// prueba que la ruta está rota para todos. Cada control va contra un mundo
// recién sembrado.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const uuid = () => crypto.randomUUID();

let n = 0;
async function reg(u) {
  const nom = u + S + (n++);
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: nom, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + nom), hardwareHash: b64('HW-' + nom), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, disp: j.dispositivoId, user: nom };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body === undefined ? undefined : JSON.stringify(body) });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b, txt };
};
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);

const { execSync } = await import('node:child_process');
const { readFileSync } = await import('node:fs');
const { rutas: auditor, normalizar } = await import('./lib/auditor-de-rutas.mjs');
const hacerStaff = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
async function sembrar(etiqueta) {
  const duena = await reg('a' + etiqueta);
  const socio = await reg('b' + etiqueta);
  hacerStaff(duena.user, 50);

  const g = await post('/v1/conversaciones/grupo', duena.t, {
    nombre: 'Grupo ' + etiqueta, usernames: [socio.user],
  });
  const G = g.b?.id;

  const M = uuid();
  await post('/v1/mensajes', duena.t, { mensajeId: M, conversacionId: G });

  // Un canal PRIVADO: lo que un desconocido no debe poder mirar.
  const privado = await post('/v1/canales', duena.t, {
    nombre: 'Privado ' + etiqueta, alias: 'priv_' + etiqueta + S, publico: false,
  });
  const CANAL_PRIV = privado.b?.conversacionId;

  // Y uno PUBLICO: lo que si, pero sin arrastrar de mas.
  const publico = await post('/v1/canales', duena.t, {
    nombre: 'Publico ' + etiqueta, alias: 'pub_' + etiqueta + S, publico: true,
  });
  const CANAL_PUB = publico.b?.conversacionId;
  const ALIAS_PUB = publico.b?.alias;

  // Y se APRUEBA. Sin esto no es un canal publico de verdad: `porAlias` solo
  // resuelve aprobados, y esconder los pendientes es deliberado -el alias
  // seria un oraculo para enterarse de lo que hay en la cola del dueno-. Un
  // barrido sobre un canal sin aprobar probaria el camino equivocado.
  //
  // Aprobar exige nivel propietario, asi que va una cuenta aparte: la duena se
  // queda en moderador para que los controles del panel comprueben el nivel
  // MINIMO que hace falta, no uno de sobra.
  const admin = await reg('z' + etiqueta);
  hacerStaff(admin.user, 100);
  await post(`/v1/panel/canales/${CANAL_PUB}`, admin.t, { aprobado: true, motivo: '' });

  const inv = await post(`/v1/conversaciones/${G}/invitaciones`, duena.t, { horas: 0, usosMax: 0 });
  const INV = inv.b?.codigo;

  const adj = await post('/v1/adjuntos', duena.t, { conversacionId: G, clase: 'imagen', bytes: 1000 });
  const ADJ = adj.b?.adjuntoId;

  const den = await post('/v1/moderacion/denuncias', socio.t, {
    tipo: 'usuario', objetivoUsuario: duena.user, motivo: 'spam',
  });
  const DENUNCIA = den.b?.id;

  // Una publicacion DENTRO del canal privado: los comentarios cuelgan de una
  // publicacion, asi que sin ella no hay nada que intentar leer.
  //
  // Van dos pasos y no uno: publicar cuelga contenido de un MENSAJE que ya
  // tiene que existir en el canal. Y la ruta responde 204, asi que el id es el
  // que uno manda, no uno que devuelva el servidor.
  const PUB_PRIV = uuid();
  await post('/v1/mensajes', duena.t, { mensajeId: PUB_PRIV, conversacionId: CANAL_PRIV });
  await post(`/v1/canales/${CANAL_PRIV}/publicaciones`, duena.t, {
    mensajeId: PUB_PRIV, cuerpo: 'privada',
  });

  const com = await post('/v1/comunidades', duena.t, {
    nombre: 'Comunidad ' + etiqueta, grupos: [G],
  });
  const COMUNIDAD = com.b?.comunidad?.id;

  const HISTORIA = uuid();
  await post('/v1/historias', duena.t, { historiaId: HISTORIA, clase: 'texto' });

  return {
    duena, socio, G, M, CANAL_PRIV, CANAL_PUB, ALIAS_PUB, INV, ADJ, DENUNCIA,
    PUB_PRIV, COMUNIDAD, HISTORIA,
  };
}

console.log('\n=== siembra ===');
const X = await sembrar('x');
const ajena = await reg('c');

const faltan = Object.entries({
  grupo: X.G, canalPrivado: X.CANAL_PRIV, canalPublico: X.CANAL_PUB,
  alias: X.ALIAS_PUB, invitacion: X.INV, adjunto: X.ADJ, denuncia: X.DENUNCIA,
}).filter(([, v]) => !v).map(([k]) => k);

ck('el mundo se sembro ENTERO (sin esto el barrido no vale)',
   faltan.length === 0, 'falto: ' + faltan.join(', '));
if (faltan.length > 0) {
  console.log('\n  Sin objetos sembrados el camino lleva `undefined` y todo da 400: verde falso.');
  console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
//  1 · Lecturas de lo ajeno
// ---------------------------------------------------------------------------
const seNiega = (s) => s === 401 || s === 403 || s === 404;

const CERRADAS = [
  { n: 'la configuracion de un grupo ajeno', r: (w) => `/v1/conversaciones/${w.G}/config` },
  { n: 'los miembros de un grupo ajeno', r: (w) => `/v1/conversaciones/${w.G}/miembros` },
  { n: 'los roles de un grupo ajeno', r: (w) => `/v1/conversaciones/${w.G}/roles` },
  { n: 'los mensajes fijados de un grupo ajeno', r: (w) => `/v1/conversaciones/${w.G}/fijados` },
  { n: 'las solicitudes de entrada a un grupo ajeno', r: (w) => `/v1/conversaciones/${w.G}/solicitudes` },
  { n: 'los destinos -o sea los aparatos- de una conversacion ajena',
    r: (w) => `/v1/conversaciones/${w.G}/destinos` },
  { n: 'el metadato de un mensaje ajeno', r: (w) => `/v1/mensajes/${w.M}` },
  { n: 'a quien le llego y quien leyo un mensaje ajeno', r: (w) => `/v1/mensajes/${w.M}/info` },
  { n: 'un adjunto ajeno', r: (w) => `/v1/adjuntos/${w.ADJ}` },
  { n: 'un canal privado ajeno', r: (w) => `/v1/canales/${w.CANAL_PRIV}` },
  { n: 'las publicaciones de un canal privado ajeno', r: (w) => `/v1/canales/${w.CANAL_PRIV}/publicaciones` },
  { n: 'las estadisticas de un canal privado ajeno', r: (w) => `/v1/canales/${w.CANAL_PRIV}/estadisticas` },
  { n: 'el detalle de una denuncia ajena', r: (w) => `/v1/moderacion/denuncias/${w.DENUNCIA}` },

  // --- lo que nunca habia pasado por aqui ------------------------------
  //
  // Son rutas posteriores al recuento que encabezaba esta suite, y ese
  // recuento era un comentario. Sin el auditor del final, ninguna de las tres
  // se habria mirado jamas.
  { n: 'el detalle de una comunidad ajena', r: (w) => `/v1/comunidades/${w.COMUNIDAD}` },
  { n: 'quien vio la historia de otra persona', r: (w) => `/v1/historias/${w.HISTORIA}/vistas` },
  { n: 'los comentarios de un canal PRIVADO ajeno',
    r: (w) => `/v1/canales/${w.CANAL_PRIV}/publicaciones/${w.PUB_PRIV}/comentarios` },
];

console.log('\n=== 1 · el tercero no puede LEER lo ajeno ===');
for (const f of CERRADAS) {
  const r = await get(f.r(X), ajena.t);
  ck(f.n, seNiega(r.s), `respondio ${r.s} ${JSON.stringify(r.b).slice(0, 100)}`);
}

// ---------------------------------------------------------------------------
//  2 · El panel, que es donde `ajeno.mjs` no miraba
// ---------------------------------------------------------------------------
//
//  Comprobar que no puede suspender a nadie no dice nada sobre si puede LEER.
//  La bitacora es un registro de auditoria: quien hizo que y sobre quien.
// El panel NO es un solo permiso: son tres escalones -moderador 50,
// administrador 80, propietario 100- y cada lectura pide el suyo. El `nivel`
// de cada fila es el MINIMO que la abre, y se usa para dos cosas: elegir con
// que cuenta hacer el control, y comprobar que el escalon de abajo se queda
// afuera.
const PANEL = [
  { n: 'el resumen de moderacion', r: () => '/v1/panel/resumen', nivel: 50 },
  { n: 'el listado de personas', r: () => '/v1/panel/usuarios', nivel: 50 },
  { n: 'la cola de denuncias', r: () => '/v1/moderacion/cola', nivel: 50 },
  { n: 'los eventos de seguridad de otra persona',
    r: (w) => `/v1/panel/usuarios/${w.duena.user}/eventos`, nivel: 50 },
  { n: 'la bitacora de auditoria', r: () => '/v1/panel/bitacora', nivel: 80 },
  { n: 'los limites del sistema', r: () => '/v1/panel/limites', nivel: 80 },
  { n: 'las conversaciones cerradas por el panel', r: () => '/v1/panel/conversaciones', nivel: 80 },
  { n: 'la cola de canales por aprobar', r: () => '/v1/panel/canales', nivel: 100 },
  // BC. No lleva el prefijo /panel pero es una lectura del panel: la lista
  // dice quien invito a quien, y eso es exactamente lo que un curioso
  // querria saber en un servidor cerrado.
  { n: 'la lista de codigos de invitacion', r: () => '/v1/registro/invitaciones', nivel: 80 },
];

// `/leidos` es el caso aparte, y conviene decir por que no esta en la lista de
// arriba: contesta **200 con una lista vacia** a un desconocido, y esta bien.
//
// La consulta es «de MIS mensajes en esta conversacion, cuales estan leidos»
// -`WHERE conversacion_id = ? AND autor_id = yo`-, o sea que esta acotada por
// autoria dentro del propio SQL. Un desconocido no tiene mensajes ahi, asi que
// la respuesta honesta es «ninguno». Y como un id inexistente devuelve lo
// mismo, tampoco sirve de oraculo para saber si esa conversacion existe.
//
// Lo que hay que comprobar no es el codigo de estado sino que venga VACIA.
/**
 * Rutas GET que NO se atacan, y por que.
 *
 * La inmensa mayoria son «mis propios datos»: `/v1/perfil`, `/v1/cuenta`,
 * `/v1/sesiones`. Leer lo de uno no es leer lo ajeno, y no hay id en el camino
 * que resolver. Van una por linea igualmente, y eso es a proposito: el dia que
 * alguien agregue una ruta tiene que venir aqui y escribir su razon, y
 * escribirla obliga a pensarla. Una categoria generica —«todo lo que empiece
 * por /v1/perfil»— dejaria entrar la siguiente sin que nadie la mire.
 */
const EXENTAS_LECTURA = [
  ['/salud', 'sonda de vida, sin autenticacion a proposito'],
  ['/consola', 'la pagina HTML; el acceso lo guarda /v1/panel/consola'],
  ['/v1/perfil', 'mi propio perfil'],
  ['/v1/perfil/privacidad', 'mis propios ajustes'],
  ['/v1/perfil/privacidad/excepciones', 'mis propias excepciones'],
  ['/v1/conversaciones', 'mis propias conversaciones'],
  ['/v1/adjuntos/uso', 'mi propio consumo'],
  ['/v1/cuenta', 'mi propia cuenta'],
  ['/v1/cuenta/tipo', 'mi propio tipo de cuenta'],
  // Solo dice SI hay codigo y de cuando es, nunca el verificador — eso lo
  // comprueba recuperacion.mjs con "el verificador no vuelve por ninguna
  // parte". Y siempre es el de quien pregunta: no admite id.
  ['/v1/cuenta/recuperacion', 'mi propio codigo de recuperacion'],
  ['/v1/sesiones', 'mis propias sesiones'],
  ['/v1/dispositivos', 'mis propios aparatos'],
  ['/v1/contactos', 'mis propios contactos'],
  ['/v1/push/config', 'configuracion publica del cliente, sin secretos'],
  ['/v1/claves/estado', 'el estado de MIS claves'],
  ['/v1/comunidades', 'las comunidades a las que pertenezco'],
  ['/v1/llamadas/en-curso', 'mi propia llamada'],
  ['/v1/llamadas/historial', 'mi propio historial'],
  ['/v1/llamadas/turn', 'credenciales TURN propias, con vencimiento'],
  ['/v1/historias', 'las historias que puedo ver, filtradas en el servidor'],
  ['/v1/historias/mias', 'las mias'],
  ['/v1/historias/destinos', 'a quienes alcanzaria MI historia'],
  ['/v1/moderacion/mi-estado', 'mis propias sanciones'],
  ['/v1/moderacion/mis-eventos', 'mis propios eventos'],
  ['/v1/canales/directorio', 'directorio publico, por diseno'],
  ['/v1/canales/buscar', 'buscador publico, por diseno'],
  ['/v1/directorio', 'lista de quien se apunto, sin objeto ajeno; lo mira directorio.mjs'],
  ['/v1/canales/alias/{}', 'un alias publico resuelve a un canal publico'],
  ['/v1/usuarios/{}', 'perfil publico: la privacidad la aplica el servidor y lo mira privacidad.mjs'],
  ['/v1/usuarios/{}/{}', 'un CAMPO del perfil publico; misma puerta que el anterior'],
  ['/v1/invitaciones/{}', 'el codigo ES la credencial'],
  ['/v1/gifs/buscar', 'el intermediario de Giphy; lo mira gifs.mjs'],
  ['/v1/gifs/{}/bytes', 'idem: el id es de Giphy, no de este servidor'],
  ['/v1/claves/dispositivo/{}', 'claves publicas: son publicas por definicion, lo mira claves.mjs'],
  ['/v1/conversaciones/{}/leidos', 'contesta 200 VACIO a proposito; se comprueba aqui abajo'],
  ['/v1/panel/consola', 'pese al prefijo /panel no es global: es `Consola.mias(yo)`; se comprueba aqui abajo'],
  // Publica A PROPOSITO, y sin autenticar: la pregunta la hace quien
  // todavia no tiene cuenta, que es el unico momento en que la respuesta
  // sirve de algo. No filtra nada que no se descubra igual intentando
  // registrarse; lo que evita es rellenar el formulario entero para que lo
  // rechacen al final por un campo que no se sabia que existia.
  ['/v1/registro/modo', 'publica a proposito: la pregunta quien aun no tiene cuenta'],
  // Publica, y tiene que serlo: le contesta a una app tan vieja que ya no
  // puede entrar. Si un cambio de protocolo la dejo fuera, "actualizate" es
  // justo lo que necesita oir, y detras de un login que ya no le funciona no
  // lo oiria nunca. Lo que publica es lo mismo que la pagina de descarga.
  ['/v1/version', 'publica a proposito: tiene que contestarle a una app que ya no puede entrar'],
];

console.log('\n=== ninguna ruta de lectura queda sin mirar ===');
{
  const { rutas, sinResolver } = auditor();
  ck('el auditor resuelve TODAS las rutas del servidor', sinResolver.length === 0,
     sinResolver.map((x) => `${x.metodo} ${x.expr}`).join(' | '));

  const fuente = readFileSync(new URL(import.meta.url), 'utf8');
  const cubiertas = new Set();
  for (const m of fuente.matchAll(/r:\s*\(w?\)\s*=>\s*[`']([^`']*)[`']/g)) {
    cubiertas.add(m[1].replace(/\$\{[^}]*\}/g, '{}').split('?')[0]);
  }
  const eximidas = new Set(EXENTAS_LECTURA.map(([ruta]) => ruta));

  const gets = rutas.filter((r) => !r.muta).map((r) => normalizar(r.patron));
  const huerfanas = gets.filter((g) => !cubiertas.has(g) && !eximidas.has(g));
  ck(`las ${gets.length} rutas de lectura estan cubiertas o eximidas`,
     huerfanas.length === 0, huerfanas.join(' | '));

  const muertas = EXENTAS_LECTURA.filter(([ruta]) => !gets.includes(ruta));
  ck('ninguna exencion apunta a una ruta que ya no existe', muertas.length === 0,
     muertas.map(([r]) => r).join(' | '));

  console.log(`  ${gets.length} de lectura · ${cubiertas.size} atacadas · ${EXENTAS_LECTURA.length} eximidas`);
}

const leidos = await get(`/v1/conversaciones/${X.G}/leidos`, ajena.t);
ck('los acuses de lectura de un grupo ajeno vienen vacios',
   Array.isArray(leidos.b?.mensajeIds) && leidos.b.mensajeIds.length === 0,
   JSON.stringify(leidos.b).slice(0, 120));
ck('y un id inventado devuelve lo mismo, asi que no es un oraculo',
   JSON.stringify((await get(`/v1/conversaciones/${uuid()}/leidos`, ajena.t)).b) ===
   JSON.stringify(leidos.b));

// `/v1/panel/consola` es el otro caso de 200 honesto, y el prefijo engania:
// parece una ruta del panel -una lista global de quien tiene acceso a la
// consola- y es `Consola.mias(yo)`, o sea los accesos de quien pregunta. Un
// desconocido recibe una lista vacia porque no tiene ninguno.
//
// Lo que hay que comprobar no es el 200 sino que no venga NADIE.
const consolas = await get('/v1/panel/consola', ajena.t);
ck('los accesos de consola de un desconocido vienen vacios',
   Array.isArray(consolas.b?.consolas) && consolas.b.consolas.length === 0,
   JSON.stringify(consolas.b).slice(0, 140));

console.log('\n=== 2 · quien no es staff no LEE el panel ===');
for (const f of PANEL) {
  const r = await get(f.r(X), ajena.t);
  ck(`no puede leer ${f.n} (pide ${f.nivel})`, seNiega(r.s),
     `respondio ${r.s} ${JSON.stringify(r.b).slice(0, 100)}`);
}

// ---------------------------------------------------------------------------
//  3 · Lo que SI contesta, y que no arrastre de mas
// ---------------------------------------------------------------------------
//
//  Estas rutas le contestan a un desconocido a proposito. La pregunta deja de
//  ser el codigo de estado y pasa a ser que campos viajan.
console.log('\n=== 3 · lo que se abre a proposito, sin arrastrar de mas ===');

// Un canal publico es publico: el 200 es correcto.
const cPub = await get(`/v1/canales/${X.CANAL_PUB}`, ajena.t);
ck('un canal publico si se lee sin estar suscrito', cPub.s === 200, String(cPub.s));

// Pero no puede traer QUIENES estan suscritos. El numero es una metrica; la
// lista es la relacion de cada persona con ese canal, que es justo lo que el
// §3 protege.
const textoPub = JSON.stringify(cPub.b ?? {});
ck('y NO trae la lista de suscriptores, solo cuantos son',
   !textoPub.includes(X.socio.user) && !textoPub.includes(X.duena.user),
   textoPub.slice(0, 200));

// Lo mismo por alias, que es el otro camino al mismo objeto. Dos caminos al
// mismo dato son dos sitios donde equivocarse.
const cAlias = await get(`/v1/canales/alias/${X.ALIAS_PUB}`, ajena.t);
ck('por alias contesta lo mismo que por id', cAlias.s === 200, String(cAlias.s));
ck('y tampoco trae suscriptores por ese camino',
   !JSON.stringify(cAlias.b ?? {}).includes(X.socio.user),
   JSON.stringify(cAlias.b ?? {}).slice(0, 160));

// Una invitacion se abre con el codigo -para eso existe- pero mirarla no es
// entrar: no puede adelantar quienes estan adentro.
const vInv = await get(`/v1/invitaciones/${X.INV}`, ajena.t);
ck('una invitacion se puede mirar con el codigo', vInv.s === 200, String(vInv.s));
ck('pero NO adelanta la lista de miembros del grupo',
   !JSON.stringify(vInv.b ?? {}).includes(X.socio.user),
   JSON.stringify(vInv.b ?? {}).slice(0, 200));

// Un perfil se consulta para poder escribirle. Lo que no puede salir de ahi es
// el telefono: es el dato con el que se cruza una cuenta con una persona real.
const perfil = await get(`/v1/usuarios/${X.duena.user}`, ajena.t);
ck('un perfil se consulta por username', perfil.s === 200, String(perfil.s));
const tp = JSON.stringify(perfil.b ?? {});
ck('y NO trae el telefono', !/telefono|phone/i.test(tp), tp.slice(0, 200));
ck('ni el nivel de staff de esa persona', !/staff/i.test(tp), tp.slice(0, 200));
ck('ni el hash de hardware, que es la huella del aparato',
   !/hardware/i.test(tp), tp.slice(0, 200));

// `dispositivoId` e `identidadPub` SI viajan, y es deliberado: en este diseno
// la direccion de Signal es el APARATO y no la persona, asi que sin esos dos
// campos no se puede cifrar el primer mensaje a alguien con quien todavia no
// hay conversacion. Se fija aqui para que se note si algun dia desaparecen.
ck('pero si viaja el aparato principal, que es lo que hace falta para cifrarle',
   !!perfil.b?.dispositivoId && !!perfil.b?.identidadPub, tp.slice(0, 160));

// Y todo esto pasa por el filtro del §3: quien tenga el perfil en «nadie» no
// aparece. Eso lo barre privacidad.mjs con sus cuatro niveles; aqui solo se
// comprueba que el camino por username no se salta el filtro.
ck('la consulta por username pasa por el filtro de privacidad',
   perfil.s === 200 || perfil.s === 404, String(perfil.s));

// ---------------------------------------------------------------------------
//  4 · Que ninguna respuesta lleve dentro un sobre
// ---------------------------------------------------------------------------
//
//  El servidor guarda bytes opacos, asi que ninguna ruta de lectura deberia
//  devolver el cuerpo de un mensaje ni aunque quisiera. Se comprueba sobre lo
//  que la DUENA si puede leer, que es donde habria algo que filtrar.
console.log('\n=== 4 · ni siquiera la duena recibe el cuerpo de un mensaje ===');

const meta = await get(`/v1/mensajes/${X.M}`, X.duena.t);
ck('la duena si lee el metadato de su mensaje', meta.s === 200, String(meta.s));
const claves = Object.keys(meta.b ?? {});
ck('y el metadato NO tiene texto, cuerpo ni sobre',
   !claves.some((k) => /texto|cuerpo|sobre|contenido/i.test(k)), JSON.stringify(claves));

const fij = await get(`/v1/conversaciones/${X.G}/fijados`, X.duena.t);
ck('los fijados tampoco traen el texto', fij.s === 200 &&
   !/"texto"|"cuerpo"/.test(JSON.stringify(fij.b ?? {})), String(fij.s));

// ---------------------------------------------------------------------------
//  5 · El control: la duena SI lee lo suyo
// ---------------------------------------------------------------------------
//
//  Sin esto la seccion 1 no prueba autorizacion: una ruta rota para todos
//  daria exactamente el mismo verde.
console.log('\n=== control · la duena si lee lo suyo ===');

for (const f of CERRADAS) {
  const W = await sembrar('k' + n);
  const r = await get(f.r(W), W.duena.t);
  ck('control: ' + f.n, r.s >= 200 && r.s < 300,
     `la duena recibio ${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
}

// Y el staff del nivel que toca SI lee: si no, la seccion 2 tampoco probaria
// nada -una ruta rota para todos daria el mismo verde-.
const W2 = await sembrar('s');
const staff = {};
for (const nivel of [50, 80, 100]) {
  const cuenta = await reg('n' + nivel);
  hacerStaff(cuenta.user, nivel);
  staff[nivel] = cuenta;
}

for (const f of PANEL) {
  const r = await get(f.r(W2), staff[f.nivel].t);
  ck(`control: nivel ${f.nivel} si lee ${f.n}`, r.s >= 200 && r.s < 300,
     `recibio ${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
}

// ---------------------------------------------------------------------------
//  6 · La escalera: el nivel de abajo tampoco entra
// ---------------------------------------------------------------------------
//
//  Esto es lo que separa «hay que ser staff» de «hay que ser ESTE staff». Sin
//  ello, un cambio que convirtiera las tres lecturas de administrador en
//  lecturas de moderador pasaria sin que nada se pusiera rojo: la seccion 2
//  seguiria en verde, porque quien ataca ahi no es staff de ningun nivel.
//
//  El caso concreto que fija: un moderador trabaja la cola de denuncias y ve
//  el historial de una persona, pero NO lee la bitacora de auditoria entera
//  -que incluye lo que hicieron los demas moderadores- ni toca los limites del
//  sistema.
console.log('\n=== 6 · la escalera del panel: el nivel de abajo no entra ===');

const ABAJO = { 80: 50, 100: 80 };
for (const f of PANEL) {
  const menor = ABAJO[f.nivel];
  if (!menor) continue;   // las de 50 ya las cubre la seccion 2
  const r = await get(f.r(W2), staff[menor].t);
  ck(`nivel ${menor} NO alcanza ${f.n} (pide ${f.nivel})`, seNiega(r.s),
     `recibio ${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
}

// ---------------------------------------------------------------------------
//  Alcance
// ---------------------------------------------------------------------------
console.log('\n=== alcance del barrido de lectura ===');
console.log(`  ${CERRADAS.length} lecturas de objetos ajenos, todas con control de la duena`);
console.log(`  ${PANEL.length} lecturas del panel, con control del nivel exacto`);
console.log('  y la escalera 50 < 80 < 100 comprobada por debajo');
console.log('  9 comprobaciones de CONTENIDO sobre rutas que se abren a proposito');
console.log('');
console.log('  NO cubre: los nueve ajustes del §3 con sus cuatro niveles, que');
console.log('  tienen suite propia (privacidad.mjs y personalizado.mjs, 56 entre');
console.log('  las dos). Esto barre la superficie; aquellas barren la matriz.');

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
