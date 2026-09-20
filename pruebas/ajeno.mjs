// §16 · Barrido de acceso ajeno sobre toda ruta que mute algo con un id.
//
// ## Por que esta suite existe aparte
//
// El brief cierra el §16 con una frase que no es decorativa: «Nunca confiar
// unicamente en permisos enviados por el cliente». Las suites por modulo
// comprueban eso EN EL CAMINO QUE LES TOCA -grupos.mjs mira los roles de grupo,
// canales.mjs los canales-, y cada una lo hace bien. Lo que ninguna hace es
// preguntarse si quedo alguna ruta fuera.
//
// Un auditor de rutas sobre Main.kt da 127 rutas, 78 de ellas mutantes y 45 con
// un `{id}` en el camino. Esas son la superficie: si una sola resuelve el id sin
// comprobar quien lo pide, hay acceso a un objeto ajeno. Esta suite las recorre
// con un tercero que no tiene ninguna relacion con el objeto y exige que el
// servidor se niegue.
//
// ## El control es la mitad importante
//
// «C recibe algo que no es 2xx» no prueba nada por si solo: un 400 porque el
// cuerpo estaba mal da el mismo verde que un 403 por autorizacion, y la suite
// pasaria contra un servidor que no comprueba nada. Es exactamente el error que
// casi se cuela en `bus-inyeccion.mjs`, y aqui se colo de verdad en el primer
// intento: nueve filas daban verde porque el objeto ni siquiera se habia
// sembrado y el id iba como `undefined`.
//
// Por eso cada fila se corre DOS veces:
//
//   1. **El ataque**: el tercero contra el objeto ajeno. Debe negarse.
//   2. **El control**: el dueno legitimo, MISMO metodo y MISMO cuerpo, contra un
//      objeto equivalente suyo. Debe funcionar.
//
// Si el control no da 2xx la fila cuenta como FALLA, no como verde: no se sabe
// si el ataque se nego por autorizacion o porque la peticion estaba mal
// construida, y no saberlo es peor que fallar.
//
// Cada control se corre contra un mundo RECIEN SEMBRADO, uno por fila. Con un
// solo mundo compartido el orden de las filas cambia el resultado -`salir` deja
// a la duena fuera del grupo y las tres filas de mensajes que venian despues
// fallaban con «No perteneces a esta conversacion»-, y una suite cuyo resultado
// depende del orden de sus filas no es una suite.
//
// ## Que se considera negarse
//
// 401, 403 y 404. Un 404 donde podria haber un 403 es a menudo MEJOR: no
// confirma que el objeto exista.
//
// Un **409 no cuenta como negarse**, y la distincion importa: 409 significa que
// la accion se entendio y se rechazo por una razon de negocio, o sea que el id
// ajeno llego hasta la logica. Un 5xx tampoco: eso no es negarse, es reventar.
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
  return { s: r.status, b };
};
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);

// El staff se siembra por SQL, igual que en l8.mjs y h3.mjs: no hay ninguna
// ruta que conceda staff sin ser staff, y eso es justamente lo que esta suite
// comprueba mas abajo.
const { execSync } = await import('node:child_process');
const hacerStaff = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

// ---------------------------------------------------------------------------
//  Siembra: un mundo entero, todo de `duena`.
// ---------------------------------------------------------------------------

/**
 * Crea grupo, chat directo, mensaje, canal, llamada, invitacion, rol propio,
 * adjunto, consola y denuncia. Se llama una vez para el mundo que se ataca y
 * una vez por cada fila de control, para que el orden no influya.
 */
async function sembrar(etiqueta, conDenuncia = false) {
  const duena = await reg('a' + etiqueta);
  const socio = await reg('b' + etiqueta);

  // La consola exige ser moderador: la raiz de confianza es el aparato de un
  // staff. Se siembra aqui para poder atacar un id de consola REAL.
  hacerStaff(duena.user, 50);

  const g = await post('/v1/conversaciones/grupo', duena.t, {
    nombre: 'Grupo ' + etiqueta, usernames: [socio.user],
  });
  const G = g.b?.id;

  const d = await post('/v1/conversaciones/directa', duena.t, { usernameDestino: socio.user });
  const DIRECTA = d.b?.id;

  const M = uuid();
  await post('/v1/mensajes', duena.t, { mensajeId: M, conversacionId: G });

  // El alias solo admite letras, numeros y `_`, de 4 a 32.
  const canal = await post('/v1/canales', duena.t, {
    nombre: 'Canal ' + etiqueta, alias: 'canal_' + etiqueta + S, publico: true,
  });
  const CANAL = canal.b?.conversacionId;

  const llam = await post('/v1/llamadas', duena.t, { conversacionId: DIRECTA, conVideo: false });
  const LLAMADA = llam.b?.llamadaId;

  const inv = await post(`/v1/conversaciones/${G}/invitaciones`, duena.t, { horas: 0, usosMax: 0 });
  const INV = inv.b?.codigo;

  const rol = await post(`/v1/conversaciones/${G}/roles`, duena.t, {
    nombre: 'Curador', jerarquia: 40, permisos: ['mensaje.fijar'],
  });
  const ROL = rol.b?.id;

  const adj = await post('/v1/adjuntos', duena.t, { conversacionId: G, clase: 'imagen', bytes: 1000 });
  const ADJ = adj.b?.adjuntoId;

  const cons = await post('/v1/panel/consola', duena.t, { password: 'clave-larga-123' });
  const CONSOLA = cons.b?.id;

  // La denuncia solo se crea para el mundo que se ATACA. La cola de revision
  // es global y paginada, y sembrar una denuncia en cada uno de los veinte
  // mundos de control la inunda: `moderacion.mjs` busca la suya en la primera
  // pagina y dejaba de encontrarla. Una suite no puede tirar abajo a otra por
  // crear objetos que no usa.
  const den = conDenuncia
    ? await post('/v1/moderacion/denuncias', socio.t, {
        tipo: 'usuario', objetivoUsuario: duena.user, motivo: 'spam',
      })
    : null;
  const DENUNCIA = den?.b?.id;

  const ses = await get('/v1/sesiones', duena.t);
  const SESION = ses.b?.sesiones?.[0]?.id;

  return { duena, socio, G, DIRECTA, M, CANAL, LLAMADA, INV, ROL, ADJ, CONSOLA, DENUNCIA, SESION };
}

console.log('\n=== siembra ===');
const X = await sembrar('x', true);   // el mundo que se ataca, con denuncia
const ajena = await reg('c');   // el tercero: sin relacion con nada ni con nadie

const faltan = Object.entries({
  grupo: X.G, directa: X.DIRECTA, canal: X.CANAL, llamada: X.LLAMADA,
  invitacion: X.INV, rol: X.ROL, adjunto: X.ADJ, consola: X.CONSOLA,
  denuncia: X.DENUNCIA, sesion: X.SESION,
}).filter(([, v]) => !v).map(([k]) => k);

// Esto es la guarda que faltaba la primera vez. Sin ella, un objeto que no se
// sembro manda `undefined` en el camino, el servidor contesta 400 y la fila da
// verde sin haber comprobado ninguna autorizacion.
ck('el mundo atacado se sembro ENTERO (sin esto el barrido no vale)',
   faltan.length === 0, 'falto: ' + faltan.join(', '));
if (faltan.length > 0) {
  console.log('\n  El barrido no se corre con objetos sin sembrar: daria verdes falsos.');
  console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
//  La tabla: una fila por ruta mutante con id.
// ---------------------------------------------------------------------------
//
//  `ruta(w)` y `cuerpo(w)` se construyen con el mundo que toque, para que
//  ataque y control sean la MISMA peticion sobre objetos distintos.
//
//  `soloPanel` marca las acciones privativas del staff: ahi el «dueno
//  legitimo» seria un moderador, y que un moderador SI pueda lo cubre h3.mjs.
//  Lo que exige esta suite es que una persona de a pie no pueda.
//
//  `sinControl` marca las filas donde el dueno no puede repetir la accion
//  sobre su propio objeto, con el motivo escrito. No se dan por verdes: se
//  cuentan aparte y se dicen al final.

const FILAS = [
  // --- grupo: administracion ------------------------------------------
  { n: 'configurar un grupo ajeno', m: 'PUT', ruta: (w) => `/v1/conversaciones/${w.G}/config`,
    cuerpo: () => ({ nombre: 'Tomado', alias: 'tomado_' + Math.random().toString(36).slice(2, 8) }) },
  { n: 'poner mensajes temporales en un grupo ajeno', m: 'PUT',
    ruta: (w) => `/v1/conversaciones/${w.G}/temporales`, cuerpo: () => ({ segundos: 60 }) },
  { n: 'agregar miembros a un grupo ajeno', m: 'POST', ruta: (w) => `/v1/conversaciones/${w.G}/miembros`,
    cuerpo: (w) => ({ usernames: [w.socio.user] }),
    sinControl: 'el socio ya es miembro del mundo de control' },
  { n: 'cambiar el rol de alguien en un grupo ajeno', m: 'POST',
    ruta: (w) => `/v1/conversaciones/${w.G}/miembros/${w.socio.id}/rol`,
    cuerpo: () => ({ rolClave: 'moderador' }) },
  { n: 'silenciar a alguien en un grupo ajeno', m: 'POST',
    ruta: (w) => `/v1/conversaciones/${w.G}/miembros/${w.socio.id}/silenciar`,
    cuerpo: () => ({ minutos: 10 }) },
  { n: 'quitarle el silencio a alguien en un grupo ajeno', m: 'DELETE',
    ruta: (w) => `/v1/conversaciones/${w.G}/miembros/${w.socio.id}/silenciar` },
  { n: 'expulsar de un grupo ajeno', m: 'POST',
    ruta: (w) => `/v1/conversaciones/${w.G}/miembros/${w.socio.id}/expulsar`,
    cuerpo: () => ({ motivo: 'porque si' }) },
  { n: 'crear un rol en un grupo ajeno', m: 'POST', ruta: (w) => `/v1/conversaciones/${w.G}/roles`,
    cuerpo: () => ({ nombre: 'Intruso', jerarquia: 10, permisos: [] }) },
  { n: 'borrar un rol de un grupo ajeno', m: 'DELETE', ruta: (w) => `/v1/conversaciones/${w.G}/roles/${w.ROL}` },
  { n: 'crear una invitacion a un grupo ajeno', m: 'POST',
    ruta: (w) => `/v1/conversaciones/${w.G}/invitaciones`, cuerpo: () => ({ horas: 0, usosMax: 0 }) },
  { n: 'revocar una invitacion ajena', m: 'DELETE',
    ruta: (w) => `/v1/conversaciones/${w.G}/invitaciones/${w.INV}` },
  { n: 'resolver una solicitud de entrada a un grupo ajeno', m: 'POST',
    ruta: (w) => `/v1/conversaciones/${w.G}/solicitudes/${w.socio.id}`, cuerpo: () => ({ aprobar: true }),
    sinControl: 'no hay solicitud pendiente: el socio entro al crearse el grupo' },
  { n: 'guardar preferencias en un chat ajeno', m: 'PUT',
    ruta: (w) => `/v1/conversaciones/${w.G}/preferencias`, cuerpo: () => ({ archivado: true }) },
  { n: 'salir de un grupo del que no se es parte', m: 'POST', ruta: (w) => `/v1/conversaciones/${w.G}/salir` },

  // --- mensajes -------------------------------------------------------
  { n: 'retirar un mensaje ajeno', m: 'POST', ruta: (w) => `/v1/mensajes/${w.M}/retirar` },
  { n: 'editar un mensaje ajeno', m: 'POST', ruta: (w) => `/v1/mensajes/${w.M}/editar` },
  { n: 'fijar un mensaje ajeno', m: 'POST', ruta: (w) => `/v1/mensajes/${w.M}/fijar`,
    cuerpo: () => ({ fijar: true }) },

  // --- adjuntos -------------------------------------------------------
  { n: 'confirmar un adjunto ajeno', m: 'POST', ruta: (w) => `/v1/adjuntos/${w.ADJ}/confirmar`,
    sinControl: 'confirmar exige que el archivo este en el almacen, y aqui solo se reservo' },

  // --- canales --------------------------------------------------------
  { n: 'configurar un canal ajeno', m: 'PUT', ruta: (w) => `/v1/canales/${w.CANAL}`,
    cuerpo: () => ({
      nombre: 'Tomado', descripcion: 'x', publico: true, soloAdmins: true,
      // Un canal publico exige alias: sin el, el control daria 400 por
      // validacion y no probaria nada sobre autorizacion.
      alias: 'tomado_' + Math.random().toString(36).slice(2, 8),
    }) },
  { n: 'publicar en un canal ajeno', m: 'POST', ruta: (w) => `/v1/canales/${w.CANAL}/publicaciones`,
    cuerpo: () => ({ mensajeId: uuid(), cuerpo: b64('hola') }),
    sinControl: 'publicar exige el canal aprobado por el panel' },
  { n: 'suscribirse: no aplica (el canal es publico a proposito)', saltar: true },

  // --- llamadas -------------------------------------------------------
  { n: 'contestar una llamada ajena', m: 'POST', ruta: (w) => `/v1/llamadas/${w.LLAMADA}/contestar`,
    // El control lo hace el SOCIO y no la duena: a quien origina la llamada no
    // le suena, asi que contestarla daria 409 por diseno y no probaria nada.
    quien: (w) => w.socio },
  { n: 'colgar una llamada ajena', m: 'POST', ruta: (w) => `/v1/llamadas/${w.LLAMADA}/terminar`,
    cuerpo: () => ({ motivo: 'colgada' }) },

  // --- dispositivos y sesiones ---------------------------------------
  { n: 'revocar un dispositivo ajeno', m: 'DELETE', ruta: (w) => `/v1/dispositivos/${w.duena.disp}`,
    sinControl: 'el unico aparato es el principal y revocarlo se rechaza por diseno (409)' },
  { n: 'promover un dispositivo ajeno a principal', m: 'POST',
    ruta: (w) => `/v1/dispositivos/${w.duena.disp}/principal`, cuerpo: () => ({ password: 'clave-larga-123' }),
    sinControl: 'ya es el principal' },
  { n: 'marcar historial enviado a un dispositivo ajeno', m: 'POST',
    ruta: (w) => `/v1/dispositivos/${w.duena.disp}/historial-enviado`, cuerpo: () => ({ cuantos: 1 }),
    sinControl: 'el origen y el destino serian el mismo aparato' },
  { n: 'cerrar una sesion ajena', m: 'DELETE', ruta: (w) => `/v1/sesiones/${w.SESION}` },

  // --- consola --------------------------------------------------------
  { n: 'revocar una consola ajena', m: 'DELETE', ruta: (w) => `/v1/panel/consola/${w.CONSOLA}` },

  // --- moderacion y panel: privativas del staff ----------------------
  { n: 'tomar una denuncia sin ser staff', m: 'POST',
    ruta: (w) => `/v1/moderacion/denuncias/${w.DENUNCIA}/tomar`, soloPanel: true },
  { n: 'resolver una denuncia sin ser staff', m: 'POST',
    ruta: (w) => `/v1/moderacion/denuncias/${w.DENUNCIA}/resolver`,
    cuerpo: () => ({ accion: 'descartar', nota: 'x' }), soloPanel: true },
  { n: 'cerrar una conversacion sin ser staff', m: 'POST',
    ruta: (w) => `/v1/panel/conversaciones/${w.G}/cerrar`, cuerpo: () => ({ motivo: 'x' }), soloPanel: true },
  { n: 'reabrir una conversacion sin ser staff', m: 'POST',
    ruta: (w) => `/v1/panel/conversaciones/${w.G}/reabrir`, soloPanel: true },
  { n: 'aprobar un canal sin ser staff', m: 'POST', ruta: (w) => `/v1/panel/canales/${w.CANAL}`,
    cuerpo: () => ({ aprobado: true }), soloPanel: true },
  { n: 'subir un limite del sistema sin ser staff', m: 'PUT',
    ruta: () => '/v1/panel/limites/mensajes_por_minuto',
    cuerpo: () => ({ tope: 999999, ventanaSegundos: 60 }), soloPanel: true },
  { n: 'restaurar un limite del sistema sin ser staff', m: 'DELETE',
    ruta: () => '/v1/panel/limites/mensajes_por_minuto', soloPanel: true },
  { n: 'suspender a alguien sin ser staff', m: 'POST',
    ruta: (w) => `/v1/panel/usuarios/${w.duena.user}/suspender`,
    cuerpo: () => ({ motivo: 'x', dias: 1 }), soloPanel: true },
  { n: 'restaurar a alguien sin ser staff', m: 'POST',
    ruta: (w) => `/v1/panel/usuarios/${w.duena.user}/restaurar`, soloPanel: true },
  { n: 'darse staff a uno mismo', m: 'PUT',
    ruta: () => `/v1/panel/usuarios/${ajena.user}/staff`, cuerpo: () => ({ nivel: 100 }), soloPanel: true },
];

// ---------------------------------------------------------------------------
//  El ataque
// ---------------------------------------------------------------------------
console.log('\n=== §16 · el tercero no puede tocar lo ajeno ===');

// 409 queda FUERA a proposito: significa que la accion se entendio y llego a la
// logica de negocio con un id ajeno, que es justo lo que no debe pasar.
const seNiega = (s) => s === 401 || s === 403 || s === 404;

for (const f of FILAS) {
  if (f.saltar) continue;
  const r = await call(f.m, f.ruta(X), ajena.t, f.cuerpo ? f.cuerpo(X) : undefined);
  ck(f.n, seNiega(r.s), `respondio ${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
}

// ---------------------------------------------------------------------------
//  El control: la MISMA peticion, hecha por quien si puede, en un mundo nuevo
// ---------------------------------------------------------------------------
console.log('\n=== control · la misma peticion, hecha por quien si puede ===');

let i = 0;
for (const f of FILAS) {
  if (f.saltar || f.soloPanel || f.sinControl) continue;
  const W = await sembrar('k' + (i++));
  const quien = f.quien ? f.quien(W) : W.duena;
  const r = await call(f.m, f.ruta(W), quien.t, f.cuerpo ? f.cuerpo(W) : undefined);
  ck('control: ' + f.n, r.s >= 200 && r.s < 300,
     `la duena recibio ${r.s} ${JSON.stringify(r.b).slice(0, 90)}`);
}

// ---------------------------------------------------------------------------
//  Dos comprobaciones que no son de codigo de estado sino de efecto
// ---------------------------------------------------------------------------
//
//  `reconocer` contesta 204 aunque la advertencia no sea tuya, y esta bien: el
//  UPDATE lleva `AND usuario_id = ?`, asi que no cambia nada, y contestar
//  distinto seria decirle a quien pregunta si esa advertencia existe. Lo que
//  hay que comprobar no es el codigo sino que NO PASO NADA.
console.log('\n=== efecto · negarse en silencio tambien es negarse ===');

const rRec = await post(`/v1/moderacion/advertencias/${uuid()}/reconocer`, ajena.t);
ck('reconocer una advertencia que no existe no reventa', rRec.s < 500, String(rRec.s));
ck('y no distingue "no existe" de "no es tuya" (no es un oraculo)',
   rRec.s === 204 || rRec.s === 404, String(rRec.s));

// El tercero sigue sin ser staff despues de haberlo intentado todo.
const rYo = await get('/v1/cuenta', ajena.t);
ck('el tercero NO se hizo staff en ningun momento del barrido',
   !rYo.b?.staffNivel, JSON.stringify(rYo.b?.staffNivel));

// El grupo atacado sigue teniendo a sus dos miembros.
const rM = await get(`/v1/conversaciones/${X.G}/miembros`, X.duena.t);
ck('el grupo atacado conserva a sus dos miembros',
   Array.isArray(rM.b) && rM.b.length === 2, JSON.stringify(rM.b?.length));

// Y sigue llamandose como se llamaba.
const rC = await get(`/v1/conversaciones/${X.G}/config`, X.duena.t);
ck('y conserva su nombre: ninguna de las 35 peticiones lo cambio',
   rC.b?.nombre === 'Grupo x', JSON.stringify(rC.b?.nombre));

// ---------------------------------------------------------------------------
//  Lo que esta suite NO afirma, dicho
// ---------------------------------------------------------------------------
console.log('\n=== alcance del barrido ===');
const atacadas = FILAS.filter((f) => !f.saltar).length;
const conControl = FILAS.filter((f) => !f.saltar && !f.soloPanel && !f.sinControl).length;
const panel = FILAS.filter((f) => f.soloPanel).length;
const sin = FILAS.filter((f) => f.sinControl);

console.log(`  ${atacadas} rutas mutantes con id atacadas por un tercero`);
console.log(`  ${conControl} con control de la duena: en esas el ataque prueba AUTORIZACION`);
console.log(`  ${panel} privativas del panel: el control seria un staff y eso lo cubre h3.mjs`);
console.log(`  ${sin.length} sin control aplicable, cada una con su motivo:`);
for (const f of sin) console.log(`      - ${f.n}: ${f.sinControl}`);
console.log('');
console.log('  NO cubre: rutas GET -eso es lectura y lo miran privacidad.mjs y');
console.log('  moderacion.mjs-, ni el contenido de los sobres, que el servidor');
console.log('  no puede leer ni con el permiso mas alto.');

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
if (fail > 0) process.exit(1);
