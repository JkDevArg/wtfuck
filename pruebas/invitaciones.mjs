// Invitaciones de registro (modulo BC).
//
// Corre en LOS DOS MODOS y hace lo que puede en cada uno:
//
//   - Contra el servidor compartido (abierto): comprueba que el modo se
//     anuncia y que una cuenta normal no puede repartir codigos.
//   - Contra uno con WTFUCK_REGISTRO=invitacion: ademas el flujo entero —
//     arranque del propietario, canje, agotamiento, caducidad y revocacion.
//
// Cuando el flujo del canje no se prueba, lo DICE. Una suite que pasa sin
// haber probado lo importante es peor que una que falla.
//
// Para el completo:
//   WTFUCK_PUERTO=8302 WTFUCK_PROPIETARIO=jefe_inv WTFUCK_REGISTRO=invitacion \
//     server/build/install/server/bin/server
//   WTFUCK_BASE=http://127.0.0.1:8302 node pruebas/invitaciones.mjs

const BASE = process.env.WTFUCK_BASE ?? process.env.WTFUCK_URL ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

async function reg(u, codigo = '') {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u),
      hardwareNivel: 'SOFTWARE_DEV', codigoInvitacion: codigo,
    }),
  });
  let j = null;
  try { j = await r.json(); } catch { /* sin cuerpo */ }
  return { estado: r.status, cuerpo: j };
}

// Entrar con una cuenta que YA existe.
//
// Hace falta por el propietario y solo por el: su nombre lo fija
// `WTFUCK_PROPIETARIO` al arrancar el servidor, asi que no se puede
// aleatorizar como el resto. En la segunda corrida contra la misma base ya
// existe, el registro devuelve 409 y la suite entera se caia — no por un
// defecto del servidor, sino por exigir una base virgen. Una suite que solo
// pasa la primera vez deja de correrse.
async function entrar(u) {
  const r = await fetch(BASE + '/v1/sesion', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'clave-larga-123', hardwareHash: b64('HW-' + u) }),
  });
  let j = null;
  try { j = await r.json(); } catch { /* sin cuerpo */ }
  return { estado: r.status, cuerpo: j };
}

async function pedir(metodo, ruta, token, cuerpo) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + ruta, {
    method: metodo, headers: h,
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  });
  let j = null;
  try { j = await r.json(); } catch { /* 204 */ }
  return { estado: r.status, cuerpo: j };
}

// ==================================================================
//  El modo, que se pregunta ANTES del formulario
// ==================================================================

const modo = await pedir('GET', '/v1/registro/modo', null);
// Tiene que responder a quien todavia NO tiene cuenta: ese es el unico
// momento en que la respuesta sirve de algo.
ck('el modo se consulta sin autenticarse', modo.estado === 200, `vino ${modo.estado}`);
ck('y dice si hace falta invitacion',
   typeof modo.cuerpo?.requiereInvitacion === 'boolean', JSON.stringify(modo.cuerpo));

const cerrado = modo.cuerpo?.requiereInvitacion === true;
console.log(`  (servidor en modo ${cerrado ? 'INVITACION' : 'ABIERTO'})`);

// ==================================================================
//  El arranque: el propietario tiene que poder entrar
// ==================================================================

let jefe = null;
// Fuera del `if`: se lee mas abajo, en el bloque del canje.
let yaEstaba = false;
if (cerrado) {
  // Contra un servidor recien montado no hay ninguna cuenta. Si el
  // propietario no puede entrar sin codigo, el despliegue nace BLOQUEADO:
  // hace falta invitacion para entrar y estar dentro para invitar.
  const nombreJefe = process.env.WTFUCK_PROPIETARIO ?? 'jefe_inv';
  const r = await reg(nombreJefe);
  // 409 = la cuenta ya existe de una corrida anterior contra esta misma base.
  // Eso NO desmiente lo que se quiere probar: la excepcion del propietario se
  // cierra sola en cuanto la cuenta se crea, y que exista demuestra que en su
  // momento se pudo. Se entra y se sigue.
  yaEstaba = r.estado === 409;
  jefe = yaEstaba ? (await entrar(nombreJefe)).cuerpo?.token ?? null
                  : r.cuerpo?.token ?? null;
  ck('el propietario entra SIN codigo (si no, el servidor nace bloqueado)',
     !!jefe, `vino ${r.estado}`);
  if (yaEstaba) console.log('  (el propietario ya existia; se entro con su clave)');

  ck('cualquier otro NO entra sin codigo',
     (await reg('colado_' + S)).estado === 400, 'se colo');
}

// ==================================================================
//  Quien puede repartir codigos
// ==================================================================

// En modo cerrado la cuenta normal necesita un codigo para existir; en abierto
// se registra sola. Sin esto, en modo cerrado no habria token y las
// comprobaciones de permisos darian 401 —falta de sesion— en vez de 404
// —falta de nivel—, que es otra cosa y no es lo que se quiere probar.
let ana = null;
if (cerrado && jefe) {
  const inv = await pedir('POST', '/v1/registro/invitaciones', jefe, { usos: 1 });
  ana = (await reg('inv_ana_' + S, inv.cuerpo?.codigo ?? '')).cuerpo?.token ?? null;
} else if (!cerrado) {
  ana = (await reg('inv_ana_' + S)).cuerpo?.token ?? null;
}
ck('hay una cuenta normal para probar permisos', !!ana, 'sin token');

if (ana) {
  // 404 y no 403 a proposito: un 403 confirmaria que la ruta existe y que
  // este servidor usa invitaciones, y eso ya es informacion para quien esta
  // probando rutas a ciegas.
  ck('una cuenta normal NO puede crear invitaciones',
     (await pedir('POST', '/v1/registro/invitaciones', ana, { usos: 1 })).estado === 404, '');
  ck('ni listarlas',
     (await pedir('GET', '/v1/registro/invitaciones', ana)).estado === 404, '');
  ck('ni revocarlas',
     (await pedir('DELETE', '/v1/registro/invitaciones/ABCDEFGHJKMN', ana)).estado === 404, '');
}

const sinSesion = await pedir('GET', '/v1/registro/invitaciones', null);
ck('sin sesion tampoco', sinSesion.estado === 401 || sinSesion.estado === 404,
   `vino ${sinSesion.estado}`);

// ==================================================================
//  El flujo del canje. Solo tiene sentido en modo cerrado.
// ==================================================================

if (!cerrado) {
  console.log('');
  console.log('  ---- el flujo del canje NO se probo ----');
  console.log('  Este servidor esta ABIERTO. Ver la cabecera del archivo.');
  console.log('');
} else if (jefe) {

  const creada = await pedir('POST', '/v1/registro/invitaciones', jefe,
    { usos: 1, diasValida: 7, nota: 'prueba ' + S });
  // Que el propietario sea staff al REGISTRARSE y no solo tras reiniciar el
  // servidor: sin eso recibe 404 aqui y no hay forma de repartir el primer
  // codigo sin un reinicio que nadie documento.
  //
  // OJO: esto solo lo demuestra si la cuenta se acaba de crear. Si ya existia,
  // `sembrarPropietario` la promovio al arrancar el servidor y el 200 de aqui
  // no distingue las dos cosas. Se dice en vez de callarlo: el defecto que
  // esta linea vigila -tener que reiniciar para repartir el primer codigo- es
  // justo el que aparece en un despliegue nuevo, y ahi la cuenta NO existe.
  ck('el propietario es administrador desde que se registra',
     creada.estado === 200, `vino ${creada.estado}`);
  if (yaEstaba) {
    console.log('  ^^^ no concluyente en esta corrida: la cuenta ya existia.');
    console.log('      Para probarlo de verdad hace falta una base sin el propietario.');
  }

  const codigo = creada.cuerpo?.codigo ?? '';
  // Sin 0, O, 1, I ni l: un codigo se dicta por telefono y se copia de una
  // captura, y esas cinco son las que se leen mal.
  ck('el codigo no lleva caracteres que se confundan al dictarlo',
     codigo.length > 0 && !/[01OIl]/.test(codigo), codigo);
  ck('y es largo para que no se adivine', codigo.length >= 10, codigo);
  ck('nace sin usar', creada.cuerpo?.usos === 0, String(creada.cuerpo?.usos));
  ck('con caducidad, porque se pidieron 7 dias',
     (creada.cuerpo?.expiraEn ?? 0) > Date.now(), String(creada.cuerpo?.expiraEn));

  ck('con el codigo SI se entra',
     (await reg('inv1_' + S, codigo)).estado === 200, '');
  // usos=1: el segundo se queda fuera. Es lo que distingue una invitacion
  // personal de un codigo que se pega en un canal.
  ck('un codigo de un solo uso no sirve dos veces',
     (await reg('inv2_' + S, codigo)).estado === 403, '');
  ck('un codigo inventado no vale',
     (await reg('inv3_' + S, 'ABCDEFGHJKMN')).estado === 403, '');
  ck('un codigo vacio tampoco',
     (await reg('inv4_' + S, '')).estado === 400, '');

  // --- revocar ----------------------------------------------------
  const otra = await pedir('POST', '/v1/registro/invitaciones', jefe, { usos: 5 });
  const c2 = otra.cuerpo?.codigo ?? '';
  ck('se revoca', (await pedir('DELETE', '/v1/registro/invitaciones/' + c2, jefe)).estado === 204, '');
  ck('una revocada deja de servir aunque le queden usos',
     (await reg('inv5_' + S, c2)).estado === 403, '');
  ck('revocar dos veces da 404 y no revienta',
     (await pedir('DELETE', '/v1/registro/invitaciones/' + c2, jefe)).estado === 404, '');

  const lista = await pedir('GET', '/v1/registro/invitaciones', jefe);
  const revocada = lista.cuerpo?.find((i) => i.codigo === c2);
  // Revocar NO borra: la fila se llevaria en cascada el rastro de quien entro
  // con ese codigo, que es justo lo que hace falta conservar al revocar algo.
  ck('revocar no borra la fila', !!revocada, 'desaparecio');
  ck('queda marcada como revocada', revocada?.revocada === true, JSON.stringify(revocada));
  ck('el contador refleja quien entro',
     lista.cuerpo?.find((i) => i.codigo === codigo)?.usos === 1, '');

  // --- normalizacion ----------------------------------------------
  const c3 = (await pedir('POST', '/v1/registro/invitaciones', jefe, { usos: 1 })).cuerpo?.codigo ?? '';
  // Quien copia un codigo de una captura lo escribe como puede. Rechazarlo
  // por un espacio o por minusculas seria un no gratuito.
  ck('un codigo con espacios y en minusculas se entiende igual',
     (await reg('inv6_' + S, '  ' + c3.toLowerCase() + ' ')).estado === 200, '');

  // --- limites ----------------------------------------------------
  const cero = await pedir('POST', '/v1/registro/invitaciones', jefe, { usos: 0 });
  ck('cero usos se corrige a uno en vez de crear algo inservible',
     cero.cuerpo?.usosMax === 1, String(cero.cuerpo?.usosMax));
  const enorme = await pedir('POST', '/v1/registro/invitaciones', jefe, { usos: 99999 });
  ck('un numero enorme se recorta',
     (enorme.cuerpo?.usosMax ?? 0) <= 500, String(enorme.cuerpo?.usosMax));
  const sinCad = await pedir('POST', '/v1/registro/invitaciones', jefe, { usos: 1 });
  // Cero y no una fecha lejana: "no caduca" y "caduca en el ano 3000" son
  // cosas distintas, y la segunda obliga a elegir un numero arbitrario.
  ck('sin dias, no caduca', sinCad.cuerpo?.expiraEn === 0, String(sinCad.cuerpo?.expiraEn));
}

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
