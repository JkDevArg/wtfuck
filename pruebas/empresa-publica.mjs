// Modulo P.2 · La ficha de empresa la ve la gente, no solo su dueno.
//
// ## El defecto que esta suite cierra
//
// El modulo P guardaba los siete campos de la ficha y los dibujaba en el perfil
// PROPIO. `UsuarioPublico` no los llevaba, asi que nadie mas podia verlos: un
// modo empresa que solo ve su dueno es un formulario, no un perfil. Lo destapo
// llenar los siete campos a mano en el emulador y buscar despues la tarjeta
// desde la otra cuenta, donde no habia nada.
//
// ## Lo que se fija aqui
//
//   1. Un tercero lee los siete campos.
//   2. `verificada` viaja aparte, y NO se deduce de que la ficha exista.
//   3. Ver una ficha no es tener una: quien esta fuera de la beta la ve igual.
//   4. Volver a cuenta personal la quita de la VISTA, no solo de la tabla.
//   5. La ficha no pasa por los ajustes de privacidad, y eso es una decision
//      declarada: declararse empresa es una declaracion hacia afuera.
//
// Uso:  node pruebas/empresa-publica.mjs

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

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
  return { t: j.token, id: j.usuarioId, user: nom };
}

const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m, headers: H(t), body: body === undefined ? undefined : JSON.stringify(body),
  });
  const txt = await r.text();
  let b = null;
  try { b = JSON.parse(txt); } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const put = (r, t, b) => call('PUT', r, t, b);

const { execSync } = await import('node:child_process');
const sql = (q) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -t -c "${q}"`,
  { encoding: 'utf8' },
).trim();

/** Cambia un ajuste de privacidad sin pisar los otros catorce. */
const ajuste = async (quien, cambios) => {
  const actual = (await get('/v1/perfil/privacidad', quien.t)).b;
  return put('/v1/perfil/privacidad', quien.t, { ...actual, ...cambios });
};

// El username fijo que `arrancar-servidor.ps1` mete en WTFUCK_CUENTAS_BETA.
const BETA = 'beta_pruebas';

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

// La empresa tiene que estar DENTRO de la beta para poder tener ficha: la
// puerta se cierra por username, asi que se toma prestado el username fijo que
// deja el script de arranque.
sql(`DELETE FROM usuario WHERE username = '${BETA}'`);
const emp = await reg('ep');
sql(`UPDATE usuario SET username = '${BETA}' WHERE username = '${emp.user}'`);
emp.user = BETA;

const puerta = await get('/v1/cuenta/tipo', emp.t);
if (puerta.b?.puedeElegirTipo !== true) {
  console.log('\n=== OMITIDA: el servidor no tiene beta_pruebas en WTFUCK_CUENTAS_BETA ===');
  console.log('    Levantalo con pruebas/arrancar-servidor.ps1, que ya la incluye.');
  process.exit(0);
}

// El tercero: una cuenta cualquiera, FUERA de la beta y SIN conversacion con la
// empresa. Es el observador mas restringido que existe, y es el que importa: si
// la ficha se viera solo con una conversacion abierta, un directorio de
// empresas no podria existir.
const otro = await reg('eo');
ck('hay una empresa dentro de la beta y un tercero fuera', !!emp.t && !!otro.t);

const staff = await reg('es');
sql(`UPDATE usuario SET staff_nivel = 80 WHERE username = '${staff.user}'`);
ck('y un administrador, para el distintivo', !!staff.t);

// ---------------------------------------------------------------------------
//  1 · Sin ficha no hay tarjeta
// ---------------------------------------------------------------------------
console.log('\n=== 1 · sin ficha no hay tarjeta ===');

let r = await get(`/v1/usuarios/${otro.user}`, emp.t);
ck('el perfil publico de una cuenta normal se lee', r.s === 200, String(r.s));
ck('y no trae ficha de empresa', r.b?.empresa == null, JSON.stringify(r.b?.empresa));

// Antes de declararse empresa tampoco la trae. No es que se oculte: no hay.
r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('y la de la futura empresa, todavia tampoco', r.b?.empresa == null);

// ---------------------------------------------------------------------------
//  2 · Los siete campos llegan al tercero
// ---------------------------------------------------------------------------
console.log('\n=== 2 · los siete campos, vistos desde fuera ===');

r = await put('/v1/cuenta/tipo', emp.t, { tipo: 'empresa' });
ck('la cuenta se declara empresa', r.s === 200, JSON.stringify(r.b));

const FICHA = {
  nombreComercial: 'Supay Red Space',
  categoria: 'tecnologia',
  descripcion: 'Laboratorio de seguridad ofensiva. Auditorias tecnicas y formacion.',
  sitioWeb: 'https://supayredspace.dev',
  tamano: '2-10',
  ubicacion: 'Lima, Peru',
  fundadaEn: 2024,
};
r = await put('/v1/cuenta/empresa', emp.t, FICHA);
ck('y guarda la ficha con los siete campos', r.s === 200, JSON.stringify(r.b));

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
const f = r.b?.empresa;
ck('el tercero recibe la ficha', f != null, JSON.stringify(r.b));
ck('  nombre comercial', f?.nombreComercial === FICHA.nombreComercial, String(f?.nombreComercial));
ck('  rubro', f?.categoria === FICHA.categoria, String(f?.categoria));
ck('  descripcion', f?.descripcion === FICHA.descripcion, String(f?.descripcion));
ck('  sitio web', f?.sitioWeb === FICHA.sitioWeb, String(f?.sitioWeb));
ck('  tamano', f?.tamano === FICHA.tamano, String(f?.tamano));
ck('  ubicacion', f?.ubicacion === FICHA.ubicacion, String(f?.ubicacion));
ck('  ano de fundacion', f?.fundadaEn === FICHA.fundadaEn, String(f?.fundadaEn));

// ---------------------------------------------------------------------------
//  3 · Ver una ficha no es tener una
// ---------------------------------------------------------------------------
console.log('\n=== 3 · el tercero la ve sin estar en la beta ===');

// `otro` esta fuera de la beta y no puede declararse empresa. Que igual vea la
// tarjeta es el punto entero: si la LECTURA estuviera tambien tras la puerta,
// durante la beta el modo empresa no serviria para nada.
r = await put('/v1/cuenta/tipo', otro.t, { tipo: 'empresa' });
ck('el tercero NO puede declararse empresa (sigue fuera de la beta)', r.s === 404, String(r.s));

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('y aun asi ve la ficha de quien si lo es',
   r.b?.empresa?.nombreComercial === FICHA.nombreComercial);

// La ruta pide token igual que antes: exponer la ficha no abrio el perfil.
const anon = await fetch(`${BASE}/v1/usuarios/${emp.user}`);
ck('sin sesion no se lee ningun perfil, ficha incluida', anon.status === 401, String(anon.status));

// ---------------------------------------------------------------------------
//  4 · El distintivo viaja aparte de la ficha
// ---------------------------------------------------------------------------
console.log('\n=== 4 · verificada no se deduce de que exista la ficha ===');

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('una ficha recien escrita llega SIN verificar', r.b?.empresa?.verificada === false,
   String(r.b?.empresa?.verificada));

r = await put(`/v1/panel/cuentas/${emp.user}/verificar?valor=true`, staff.t);
ck('staff pone el distintivo', r.s === 200, JSON.stringify(r.b));

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('y el tercero lo ve', r.b?.empresa?.verificada === true, String(r.b?.empresa?.verificada));

// Editar la ficha retira el distintivo, y eso TIENE que verse desde fuera: es
// el unico lado desde el que el distintivo significa algo.
r = await put('/v1/cuenta/empresa', emp.t, { ...FICHA, nombreComercial: 'Otro Nombre SA' });
ck('la empresa edita su ficha', r.s === 200, JSON.stringify(r.b));

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('el nombre nuevo se ve desde fuera', r.b?.empresa?.nombreComercial === 'Otro Nombre SA');
ck('y el distintivo se retiro, tambien visto desde fuera',
   r.b?.empresa?.verificada === false, String(r.b?.empresa?.verificada));

// ---------------------------------------------------------------------------
//  5 · Dos nombres, dos reglas
// ---------------------------------------------------------------------------
console.log('\n=== 5 · el nombre personal se oculta, el comercial no ===');

await put('/v1/perfil', emp.t, { nombreMostrado: 'Nombre Personal', estadoTexto: '' });
await ajuste(emp, { nombre: 'nadie' });

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('el nombre personal se oculta', r.b?.nombreMostrado === '', String(r.b?.nombreMostrado));
// Decision declarada: la ficha NO pasa por los ajustes de privacidad. Un
// interruptor para esconderla seria pedir un modo publico y apagarlo; quien no
// la quiera publica vuelve a cuenta personal, y entonces se borra.
ck('y el nombre comercial sigue a la vista',
   r.b?.empresa?.nombreComercial === 'Otro Nombre SA', String(r.b?.empresa?.nombreComercial));

await ajuste(emp, { nombre: 'todos' });

// ---------------------------------------------------------------------------
//  6 · La ficha llega tambien por la lista de conversaciones
// ---------------------------------------------------------------------------
console.log('\n=== 6 · el mismo perfil, por la otra puerta ===');

// Esta seccion existe por un defecto que las cinco anteriores NO vieron.
//
// `UsuarioPublico` se arma en TRES consultas: dos de perfil y una de
// participantes de una conversacion. Al anadir la ficha se le puso el join a
// las dos primeras y no a la tercera, que arranca de `participante` y tiene
// otro FROM. Nada de eso falla al compilar: falla al LEER la columna 17, y lo
// que se vio fue un 500 al abrir cualquier conversacion y dos suites cayendose
// con `.some is not a function` sobre el cuerpo del error.
//
// Una suite que solo pregunta por el perfil no toca esa consulta. Por eso se
// pide aqui la lista de conversaciones: es el camino por el que el dato llega
// cuando alguien simplemente abre la app.
r = await put('/v1/cuenta/empresa', emp.t, FICHA);
ck('la empresa vuelve a tener ficha', r.s === 200, JSON.stringify(r.b));

r = await call('POST', '/v1/conversaciones/directa', otro.t, { usernameDestino: emp.user });
ck('el tercero abre una conversacion con la empresa', r.s === 200, JSON.stringify(r.b));

r = await get('/v1/conversaciones', otro.t);
ck('y la lista de conversaciones se lee (no 500)', r.s === 200 && Array.isArray(r.b),
   JSON.stringify(r.b).slice(0, 200));

const conEmpresa = (Array.isArray(r.b) ? r.b : [])
  .flatMap((cv) => cv.participantes ?? [])
  .find((p) => p.username === emp.user);
ck('la empresa aparece como participante', conEmpresa != null);
ck('y su ficha viaja tambien por ahi',
   conEmpresa?.empresa?.nombreComercial === FICHA.nombreComercial,
   JSON.stringify(conEmpresa?.empresa));

// ---------------------------------------------------------------------------
//  7 · Dejar de ser empresa la quita de la VISTA
// ---------------------------------------------------------------------------
console.log('\n=== 7 · volver a cuenta personal ===');

r = await put('/v1/cuenta/tipo', emp.t, { tipo: 'normal' });
ck('la cuenta vuelve a personal', r.s === 200, JSON.stringify(r.b));

r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('y el tercero ya no ve ninguna ficha', r.b?.empresa == null, JSON.stringify(r.b?.empresa));

const quedan = sql(
  `SELECT count(*) FROM perfil_empresa e JOIN usuario u ON u.id = e.usuario_id ` +
  `WHERE u.username = '${BETA}'`,
);
ck('la fila tampoco quedo huerfana en la tabla', quedan === '0', quedan);

// La condicion de tipo va DENTRO del join, no en el WHERE: una fila resucitada
// a mano —un bug futuro en `elegirTipo`, una carga de datos, una restauracion
// parcial— no vuelve a la vista por si sola.
sql(
  `INSERT INTO perfil_empresa (usuario_id, nombre_comercial, categoria) ` +
  `SELECT id, 'Fila Huerfana', 'otra' FROM usuario WHERE username = '${BETA}'`,
);
r = await get(`/v1/usuarios/${emp.user}`, otro.t);
ck('y una fila huerfana metida a mano NO se muestra',
   r.b?.empresa == null, JSON.stringify(r.b?.empresa));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
