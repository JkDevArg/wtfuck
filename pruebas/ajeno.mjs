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
// `auditor-de-rutas.mjs` lee Main.kt y da las rutas mutantes con un `{id}` en el
// camino. Esas son la superficie: si una sola resuelve el id sin comprobar quien
// lo pide, hay acceso a un objeto ajeno. Esta suite las recorre con un tercero
// que no tiene ninguna relacion con el objeto y exige que el servidor se niegue.
//
// El auditor es codigo y no un comentario, y eso es el punto. Antes este numero
// estaba escrito aqui a mano —«45 rutas»— y era una foto de un dia: toda ruta
// agregada despues quedaba fuera del barrido y la suite seguia dando verde. Al
// final del archivo se le pregunta al auditor si quedo alguna.
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
const { readFileSync } = await import('node:fs');
const { rutas: auditor, normalizar } = await import('./lib/auditor-de-rutas.mjs');
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

  // Comunidad, historia y advertencia: tres objetos de la duena que el barrido
  // no tocaba. Los dos primeros son de modulos posteriores al ultimo recuento
  // de rutas, que es exactamente el agujero que el auditor viene a cerrar.
  const com = await post('/v1/comunidades', duena.t, {
    nombre: 'Comunidad ' + etiqueta, grupos: [G],
  });
  const COMUNIDAD = com.b?.comunidad?.id;

  const HISTORIA = uuid();
  await post('/v1/historias', duena.t, { historiaId: HISTORIA, clase: 'texto' });

  // La clave de un limite no es un objeto: es un nombre fijo del sistema. Va
  // en el mundo igual, para que la plantilla de la fila lleve interpolacion y
  // el auditor la reconozca como el `{clave}` de la ruta.
  const LIMITE = 'enviar_mensaje';

  return {
    duena, socio, G, DIRECTA, M, CANAL, LLAMADA, INV, ROL, ADJ, CONSOLA,
    DENUNCIA, SESION, COMUNIDAD, HISTORIA, LIMITE,
  };
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

// Un codigo que no existe, y da igual que no exista: la peticion se tiene que
// negar por QUIEN la hace, antes de mirar si el codigo esta en la base. Si
// alguna vez empezara a contestar por no encontrarlo en vez de por falta de
// permiso, la diferencia no se veria aqui -las dos son 404- pero si en
// invitaciones.mjs, que revoca uno de verdad y comprueba que deja de servir.
const CODIGO_FALSO = 'ABCDEFGHJKMN';

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
  // El control lo hace un miembro que NO es el autor: abrir el propio es un
  // 400 por diseno, no un acceso legitimo.
  { n: 'avisar que se abrio un "ver una vez" de un chat ajeno', m: 'POST', ruta: (w) => `/v1/mensajes/${w.M}/abierto`,
    quien: (w) => w.socio },
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

  // --- comunidades (modulo AD) -----------------------------------------
  //
  // Nunca habian pasado por aqui: son posteriores al recuento de rutas que
  // encabezaba esta suite, y ese recuento era un comentario, no un auditor.
  { n: 'renombrar una comunidad ajena', m: 'PUT',
    ruta: (w) => `/v1/comunidades/${w.COMUNIDAD}`,
    cuerpo: () => ({ nombre: 'Tomada', descripcion: 'mia ahora' }) },
  { n: 'meter un grupo en una comunidad ajena', m: 'POST',
    ruta: (w) => `/v1/comunidades/${w.COMUNIDAD}/grupos`,
    cuerpo: (w) => ({ grupos: [w.G] }),
    sinControl: 'el grupo de la duena ya esta en su comunidad' },
  { n: 'sacar un grupo de una comunidad ajena', m: 'DELETE',
    ruta: (w) => `/v1/comunidades/${w.COMUNIDAD}/grupos/${w.G}` },

  // --- historias --------------------------------------------------------
  { n: 'borrar la historia de otra persona', m: 'DELETE',
    ruta: (w) => `/v1/historias/${w.HISTORIA}` },
  { n: 'subir sobres para la historia de otra persona', m: 'POST',
    ruta: (w) => `/v1/historias/${w.HISTORIA}/sobres`,
    cuerpo: () => ({ sobres: [] }),
    sinControl: 'la duena ya subio los suyos al publicarla' },

  // --- panel: sin ser staff --------------------------------------------
  { n: 'verificar una cuenta sin ser staff', m: 'PUT',
    ruta: (w) => `/v1/panel/cuentas/${w.duena.user}/verificar`,
    cuerpo: () => ({ verificada: true }), soloPanel: true },
  { n: 'cambiar un limite del sistema sin ser staff', m: 'PUT',
    ruta: (w) => `/v1/panel/limites/${w.LIMITE}`,
    cuerpo: () => ({ tope: 99999, ventanaSegundos: 1 }), soloPanel: true },
  { n: 'borrar un limite del sistema sin ser staff', m: 'DELETE',
    ruta: (w) => `/v1/panel/limites/${w.LIMITE}`, soloPanel: true },

  // --- invitaciones de registro (modulo BC) ----------------------------
  //
  // `sinControl` porque el control necesitaria un codigo que exista, y crear
  // uno exige ser administrador: el mundo de control no tiene ninguno. Que un
  // administrador SI pueda revocar lo cubre invitaciones.mjs de punta a punta
  // -crear, revocar, y comprobar que el revocado deja de servir-.
  //
  // Lo que se exige AQUI es lo otro: que una cuenta de a pie no pueda anular
  // el codigo de otro. En un servidor cerrado eso seria poder cerrarle la
  // puerta a quien esta invitado, sin ser nadie.
  { n: 'revocar un codigo de invitacion sin ser staff', m: 'DELETE',
    ruta: () => `/v1/registro/invitaciones/${CODIGO_FALSO}`, soloPanel: true,
    sinControl: 'crear el codigo del control exige ser administrador, y el mundo de control no lo es' },
];

/**
 * Rutas mutantes con id que NO se atacan, y por que.
 *
 * Una exencion sin motivo es una ruta olvidada con otro nombre. Cada una dice
 * que tiene de distinto; si alguien agrega una ruta y no la cubre, tiene que
 * venir aqui a escribir la razon, y escribirla obliga a pensarla.
 */
const EXENTAS = [
  ['PUT', '/v1/perfil/{}', 'el parametro es un CAMPO, no un objeto: siempre edita el perfil de quien pide'],
  ['POST', '/v1/bloqueos/{}', 'bloquear a cualquiera esta permitido a proposito: no hay objeto ajeno'],
  ['DELETE', '/v1/bloqueos/{}', 'idem: se desbloquea lo que uno mismo bloqueo'],
  ['DELETE', '/v1/contactos/{}', 'el contacto es de quien pide por construccion'],
  ['POST', '/v1/invitaciones/{}', 'el codigo ES la credencial: usarlo teniendolo es justo el caso legitimo'],
  ['POST', '/v1/canales/{}/suscribir', 'suscribirse a un canal publico es abierto por diseno'],
  ['POST', '/v1/canales/{}/desuscribir', 'idem, y solo afecta a la suscripcion de quien pide'],
  ['POST', '/v1/canales/{}/comentarios', 'comentar en un canal publico es abierto; lo cubre canales.mjs'],
  ['POST', '/v1/historias/{}/vista', 'marcar vista una historia que ya se puede ver no toma nada ajeno'],
  ['PUT', '/v1/conversaciones/{}/solicitud', 'la solicitud es la de quien pide; lo cubre privacidad-fina.mjs'],
  // Esta se movio, no se perdono: una advertencia solo existe despues de
  // resolver una denuncia, y sembrar denuncias en los veinte mundos de control
  // inunda la cola global y tira abajo `moderacion.mjs`. Se ataca alli, donde
  // la advertencia ya existe: un tercero y el moderador que la puso reciben
  // 404, igual que un id inventado.
  ['POST', '/v1/moderacion/advertencias/{}/reconocer', 'se ataca en moderacion.mjs, donde la advertencia existe'],
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
// ============================================================
//  Y que no quede ninguna fuera SIN QUE NADIE SE ENTERE
// ============================================================
//
// Esta suite abria diciendo que un auditor daba 45 rutas mutantes con id. Ese
// numero estaba en un COMENTARIO: era una foto de un dia. Toda ruta agregada
// despues quedaba fuera del barrido y la suite seguia dando verde.
//
// Ahora el auditor existe, lee Main.kt, y esto le pregunta. Hoy son 54.
console.log('\n=== ninguna ruta mutante con id queda sin mirar ===');
{
  const { rutas, sinResolver } = auditor();
  ck('el auditor resuelve TODAS las rutas del servidor', sinResolver.length === 0,
     sinResolver.map((x) => `${x.metodo} ${x.expr}`).join(' | '));

  const fuente = readFileSync(new URL(import.meta.url), 'utf8');
  const bloque = fuente.slice(fuente.indexOf('const FILAS = ['));
  const cubiertas = new Set();
  for (const m of bloque.matchAll(/ruta:\s*\(w?\)\s*=>\s*`([^`]*)`/g)) {
    cubiertas.add(m[1].replace(/\$\{[^}]*\}/g, '{}'));
  }
  const eximidas = new Set(EXENTAS.map(([, ruta]) => ruta));

  const conId = rutas.filter((r) => r.muta && r.conId);
  const huerfanas = conId
    .map((r) => ({ m: r.metodo, p: normalizar(r.patron) }))
    .filter((r) => !cubiertas.has(r.p) && !eximidas.has(r.p));

  ck(`las ${conId.length} rutas mutantes con id estan cubiertas o eximidas`,
     huerfanas.length === 0,
     huerfanas.map((r) => `${r.m} ${r.p}`).join(' | '));

  // Y al reves: una exencion que ya no corresponde a ninguna ruta es basura
  // que se queda ahi tapando el hueco siguiente.
  const patrones = new Set(conId.map((r) => normalizar(r.patron)));
  const muertas = EXENTAS.filter(([, ruta]) => !patrones.has(ruta));
  ck('ninguna exencion apunta a una ruta que ya no existe', muertas.length === 0,
     muertas.map(([m, r]) => `${m} ${r}`).join(' | '));

  console.log(`  ${conId.length} mutantes con id · ${cubiertas.size} atacadas · ${EXENTAS.length} eximidas`);
}

console.log('  NO cubre: rutas GET -eso es lectura y lo miran privacidad.mjs y');
console.log('  moderacion.mjs-, ni el contenido de los sobres, que el servidor');
console.log('  no puede leer ni con el permiso mas alto.');

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
if (fail > 0) process.exit(1);
