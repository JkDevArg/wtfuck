// Modulo P · Tipos de cuenta y ficha de empresa.
//
// Tres tipos excluyentes y una ficha publica. Lo que se prueba aqui no es que
// se guarden los datos: es **quien puede cambiar que**, que son tres preguntas
// distintas y se contestan distinto.
//
//   1. Elegir el tipo propio (normal <-> empresa): la propia persona.
//   2. Otorgar `desarrollador`: solo staff. Auto-otorgarselo seria una
//      escalada de privilegio con otro nombre.
//   3. Verificar una empresa: solo staff. Cualquiera escribe el nombre de un
//      banco en su ficha; nadie se pone solo el distintivo que dice que
//      alguien lo comprobo.
//
// Y la puerta de la beta, que se cierra EN EL SERVIDOR: mientras el modulo
// este en pruebas, quien no esta en la lista recibe 404 aunque llame a la ruta
// a mano. Esconder el boton y dejar la ruta abierta es exactamente lo que el
// §16 del brief prohibe.
//
// Uso:  node pruebas/cuentas.mjs
//
// Necesita el servidor levantado con WTFUCK_PROPIETARIO=joaquin (lo hace
// `pruebas/arrancar-servidor.ps1`), porque la beta sale de ahi por defecto.

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

// El propietario esta en la beta por WTFUCK_PROPIETARIO. Se usa su cuenta real
// y no una inventada: la beta se resuelve por username, asi que una cuenta
// nueva no sirve para probar el lado de dentro.
const PROPIETARIO = process.env.WTFUCK_PROPIETARIO ?? 'joaquin';
// El username fijo que `arrancar-servidor.ps1` mete en WTFUCK_CUENTAS_BETA.
const BETA = 'beta_pruebas';

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

const fuera = await reg('cp');
ck('hay una cuenta normal fuera de la beta', !!fuera.t);

const staff = await reg('cs');
sql(`UPDATE usuario SET staff_nivel = 80 WHERE username = '${staff.user}'`);
ck('y una cuenta de administrador', !!staff.t);

// La cuenta de DENTRO de la beta. La lista se lee del entorno y por username,
// asi que la suite no puede meterse sola: `arrancar-servidor.ps1` incluye un
// username fijo, `beta_pruebas`, y aqui se toma prestado renombrando una cuenta
// recien creada. Asi se prueban los dos lados de la puerta y no solo uno.
sql(`DELETE FROM usuario WHERE username = '${BETA}'`);
const dentro = await reg('cb');
sql(`UPDATE usuario SET username = '${BETA}' WHERE username = '${dentro.user}'`);
dentro.user = BETA;
ck('y una cuenta dentro de la beta', !!dentro.t);

// Si el servidor no se levanto con `beta_pruebas` en WTFUCK_CUENTAS_BETA, la
// mitad de esta suite no puede comprobar nada: se OMITE en vez de fallar. Una
// suite roja por una variable de entorno que falta ensucia la senal de las que
// estan rojas de verdad. Ver la nota del runner en `correr.mjs`.
const puerta = await get('/v1/cuenta/tipo', dentro.t);
if (puerta.b?.puedeElegirTipo !== true) {
  console.log('\n=== OMITIDA: el servidor no tiene beta_pruebas en WTFUCK_CUENTAS_BETA ===');
  console.log('    Levantalo con pruebas/arrancar-servidor.ps1, que ya la incluye.');
  process.exit(0);
}

// ---------------------------------------------------------------------------
//  1 · Toda cuenta nace normal, y todas pueden preguntarlo
// ---------------------------------------------------------------------------
console.log('\n=== 1 · el tipo por defecto ===');

let r = await get('/v1/cuenta/tipo', fuera.t);
ck('cualquiera puede leer su tipo de cuenta', r.s === 200, JSON.stringify(r.b));
ck('y nace como normal', r.b?.tipo === 'normal', JSON.stringify(r.b));
ck('no es desarrollador', r.b?.esDesarrollador === false);
ck('no trae ficha de empresa', r.b?.empresa == null);

// La lectura NO exige beta a proposito: si tambien fuera 404, el cliente no
// podria distinguir "no puedo" de "fallo la red", y son dos cosas.
ck('quien esta fuera de la beta LEE, pero no puede elegir',
   r.b?.puedeElegirTipo === false, JSON.stringify(r.b));

// ---------------------------------------------------------------------------
//  2 · La puerta de la beta se cierra en el SERVIDOR
// ---------------------------------------------------------------------------
console.log('\n=== 2 · la beta no es un boton escondido ===');

// Esto es el §16 en una linea: la ruta se descubre leyendo el APK, asi que
// tiene que decir que no por si misma.
r = await put('/v1/cuenta/tipo', fuera.t, { tipo: 'empresa' });
ck('quien no esta en la beta no cambia su tipo aunque llame a la ruta', r.s === 404, String(r.s));

r = await put('/v1/cuenta/empresa', fuera.t, { nombreComercial: 'Fantasma', categoria: 'otra' });
ck('ni guarda una ficha de empresa', r.s === 404, String(r.s));

const sigueNormal = sql(
  `SELECT tipo_cuenta FROM usuario WHERE username = '${fuera.user}'`);
ck('y en la base sigue siendo normal', sigueNormal === 'normal', sigueNormal);

// 404 y no 403: un 403 confirmaria que la funcionalidad existe, y en una beta
// eso ya es informacion.
ck('responde 404 y no 403, para no confirmar que existe', r.s === 404, String(r.s));

// ---------------------------------------------------------------------------
//  3 · `desarrollador` no se pide: se otorga
// ---------------------------------------------------------------------------
console.log('\n=== 3 · nadie se hace desarrollador a si mismo ===');

// Se prueba con la cuenta de staff, que SI podria tener tentacion de hacerlo
// por la via de autoservicio. La ruta de autoservicio lo rechaza igual.
r = await put('/v1/cuenta/tipo', staff.t, { tipo: 'desarrollador' });
ck('ni siquiera un administrador se lo da por la via de autoservicio',
   r.s === 403 || r.s === 404, String(r.s));

// Por la via de staff si, y ese es el unico camino.
r = await put('/v1/panel/cuentas/tipo', staff.t, {
  username: fuera.user, tipo: 'desarrollador',
});
ck('staff si puede otorgarlo', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

const ahoraDev = sql(`SELECT tipo_cuenta FROM usuario WHERE username = '${fuera.user}'`);
ck('y queda escrito en la cuenta', ahoraDev === 'desarrollador', ahoraDev);

r = await get('/v1/cuenta/tipo', fuera.t);
ck('la persona lo ve reflejado', r.b?.esDesarrollador === true, JSON.stringify(r.b));

// Queda en la bitacora: un modo con capacidades tecnicas repartido sin rastro
// no se puede revisar despues.
const enBitacora = sql(
  `SELECT count(*) FROM auditoria WHERE accion = 'cuenta.asignar_tipo'`);
ck('y en la bitacora', Number(enBitacora) > 0, enBitacora);

// Quien NO es staff no reparte tipos.
r = await put('/v1/panel/cuentas/tipo', fuera.t, { username: staff.user, tipo: 'empresa' });
ck('quien no es staff no reparte tipos', r.s === 404, String(r.s));

// ---------------------------------------------------------------------------
//  4 · Dentro de la beta: elegir tipo y llenar la ficha
// ---------------------------------------------------------------------------
console.log('\n=== 4 · dentro de la beta ===');

r = await get('/v1/cuenta/tipo', dentro.t);
ck('quien esta dentro SI puede elegir', r.b?.puedeElegirTipo === true, JSON.stringify(r.b));

// Ser administrador no mete a nadie en una beta: son dos ejes distintos.
r = await put('/v1/cuenta/empresa', staff.t, { nombreComercial: 'X', categoria: 'otra' });
ck('la beta tambien le aplica a staff', r.s === 404, String(r.s));

// El orden manda: primero el tipo, despues la ficha. Al reves, guardar datos
// cambiaria el tipo como efecto secundario, y eso nadie lo audita.
r = await put('/v1/cuenta/empresa', dentro.t, { nombreComercial: 'Acme', categoria: 'tecnologia' });
ck('sin ser empresa todavia, la ficha se rechaza', r.s === 409, `${r.s} ${JSON.stringify(r.b)}`);

r = await put('/v1/cuenta/tipo', dentro.t, { tipo: 'empresa' });
ck('la cuenta pasa a tipo empresa', r.s === 200 && r.b?.tipo === 'empresa', JSON.stringify(r.b));

r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'Acme Peru', categoria: 'tecnologia',
  descripcion: 'Integracion de sistemas', sitioWeb: 'https://acme.example',
  tamano: '11-50', ubicacion: 'Lima', fundadaEn: 2015,
});
ck('y ahora si acepta la ficha', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 120)}`);
ck('con los datos que se mandaron', r.b?.nombreComercial === 'Acme Peru', JSON.stringify(r.b));
ck('y NACE SIN VERIFICAR', r.b?.verificada === false, JSON.stringify(r.b));

r = await get('/v1/cuenta/tipo', dentro.t);
ck('la ficha viaja con las capacidades', r.b?.empresa?.categoria === 'tecnologia', JSON.stringify(r.b));

// --- lo que la ficha NO acepta ---------------------------------------
// Todo esto acaba en un perfil publico que lee otra gente.
r = await put('/v1/cuenta/empresa', dentro.t, { nombreComercial: '', categoria: 'tecnologia' });
ck('una empresa sin nombre no se guarda', r.s === 400, String(r.s));

r = await put('/v1/cuenta/empresa', dentro.t, { nombreComercial: 'A', categoria: 'inventada' });
ck('una categoria fuera de la lista se rechaza', r.s === 400, String(r.s));

r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'A', categoria: 'otra', tamano: '9999999',
});
ck('un tamano fuera de los rangos se rechaza', r.s === 400, String(r.s));

r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'A', categoria: 'otra', fundadaEn: 1200,
});
ck('un ano de fundacion increible se rechaza', r.s === 400, String(r.s));

// Este es el que importa de verdad: ese texto acaba siendo un ENLACE en el
// perfil de alguien, y un `javascript:` ahi es una trampa, no una pagina.
r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'A', categoria: 'otra', sitioWeb: 'javascript:alert(1)',
});
ck('un sitio que no es https se rechaza', r.s === 400, `${r.s} ${JSON.stringify(r.b)}`);

r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'A', categoria: 'otra', sitioWeb: 'http://sin-tls.example',
});
ck('y http a secas tampoco', r.s === 400, String(r.s));

r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'B'.repeat(500), categoria: 'otra',
});
ck('un nombre desmedido se rechaza', r.s === 400, String(r.s));

// Una descripcion larga NO se rechaza: se recorta. Es contenido, no un campo
// con forma, y tirar el guardado entero por pasarse de largo seria perder lo
// que la persona escribio.
r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'Acme Peru', categoria: 'tecnologia', descripcion: 'z'.repeat(2000),
});
ck('una descripcion larga se recorta y no se pierde el guardado', r.s === 200, String(r.s));
ck('recortada al tope', (r.b?.descripcion || '').length === 600, String((r.b?.descripcion || '').length));

// ---------------------------------------------------------------------------
//  5 · El distintivo lo pone staff, y editar lo borra
// ---------------------------------------------------------------------------
console.log('\n=== 5 · verificar no es declarar ===');

r = await put(`/v1/panel/cuentas/${dentro.user}/verificar?valor=true`, staff.t);
ck('un administrador verifica una ficha', r.s === 200, `${r.s} ${JSON.stringify(r.b)}`);

r = await get('/v1/cuenta/tipo', dentro.t);
ck('y la empresa lo ve', r.b?.empresa?.verificada === true, JSON.stringify(r.b?.empresa));

const conFirma = sql(
  `SELECT count(*) FROM perfil_empresa WHERE verificada_por IS NOT NULL`);
ck('con quien lo firmo, para poder preguntarle despues', Number(conFirma) > 0, conFirma);

// EL CASO que hace que la verificacion signifique algo: si editar no la
// borrara, bastaria verificarse con datos limpios y cambiar el nombre despues.
r = await put('/v1/cuenta/empresa', dentro.t, {
  nombreComercial: 'Banco Nacional', categoria: 'finanzas',
});
ck('se puede editar la ficha', r.s === 200, String(r.s));
ck('pero editar BORRA la verificacion', r.b?.verificada === false, JSON.stringify(r.b));

// Quien no es staff no verifica a nadie, ni a si mismo.
r = await put(`/v1/panel/cuentas/${dentro.user}/verificar?valor=true`, dentro.t);
ck('nadie se verifica a si mismo', r.s === 404, String(r.s));

r = await put(`/v1/panel/cuentas/${fuera.user}/verificar?valor=true`, staff.t);
ck('no se verifica una cuenta sin ficha', r.s === 404, String(r.s));

// Quitarlo tambien se puede: un distintivo que no se puede retirar es peor que
// no tenerlo.
await put(`/v1/panel/cuentas/${dentro.user}/verificar?valor=true`, staff.t);
r = await put(`/v1/panel/cuentas/${dentro.user}/verificar?valor=false`, staff.t);
ck('el distintivo se puede retirar', r.s === 200, String(r.s));
const yaNo = sql(
  `SELECT count(*) FROM perfil_empresa p JOIN usuario u ON u.id = p.usuario_id WHERE u.username = '${dentro.user}' AND p.verificada_en IS NULL`);
ck('y se va de verdad', yaNo === '1', yaNo);

// ---------------------------------------------------------------------------
//  6 · Dejar de ser empresa se lleva la ficha
// ---------------------------------------------------------------------------
console.log('\n=== 6 · sin tipo empresa no hay ficha ===');

// Una ficha huerfana seguiria siendo visible desde cualquier consulta que la
// lea por usuario_id sin mirar el tipo, y esa consulta se escribe sola.
r = await put('/v1/cuenta/tipo', dentro.t, { tipo: 'normal' });
ck('la cuenta vuelve a normal', r.s === 200 && r.b?.tipo === 'normal', JSON.stringify(r.b));

const huerfana = sql(
  `SELECT count(*) FROM perfil_empresa p JOIN usuario u ON u.id = p.usuario_id WHERE u.username = '${dentro.user}'`);
ck('y la ficha se va con el tipo', huerfana === '0', huerfana);

// --- quien es desarrollador no se quita el modo solo -------------------
// Si pudiera, el registro de quien lo tiene dejaria de ser fiable: bastaria
// apagarlo un momento para que una revision no lo viera.
sql(`UPDATE usuario SET tipo_cuenta = 'desarrollador' WHERE username = '${dentro.user}'`);
r = await put('/v1/cuenta/tipo', dentro.t, { tipo: 'normal' });
ck('un desarrollador no se quita el modo por su cuenta', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);

r = await put('/v1/panel/cuentas/tipo', staff.t, { username: dentro.user, tipo: 'normal' });
ck('pero staff si se lo quita', r.s === 200, String(r.s));

// ---------------------------------------------------------------------------
//  7 · El CHECK de la base
// ---------------------------------------------------------------------------
console.log('\n=== 7 · un tipo que no existe no entra ===');

let rechazado = false;
try {
  sql(`UPDATE usuario SET tipo_cuenta = 'superadmin' WHERE username = '${fuera.user}'`);
} catch {
  rechazado = true;
}
ck('la base rechaza un tipo inventado', rechazado);

r = await put('/v1/panel/cuentas/tipo', staff.t, { username: fuera.user, tipo: 'superadmin' });
ck('y el servidor tambien, antes de llegar a la base', r.s === 400, String(r.s));

r = await put('/v1/cuenta/tipo', dentro.t, { tipo: 'superadmin' });
ck('tampoco por la via de autoservicio', r.s === 400, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
