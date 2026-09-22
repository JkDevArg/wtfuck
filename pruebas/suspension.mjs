// Que puede y que no puede hacer una cuenta suspendida.
//
// ## La politica del proyecto, y donde estaba el agujero
//
// Una suspension **no corta la sesion**, a proposito: corta lo que produce
// contenido y deja entrar a ver POR QUE, porque una sancion que no se explica
// no corrige nada. Esa decision esta bien y no se toca.
//
// El problema era el alcance. La suspension se comprobaba en `Autz.puede`, que
// es autorizacion **de conversacion**. Todo lo que no pasa por ahi se quedaba
// fuera: el nombre, el estado, la biografia, la foto, la portada, el tipo de
// cuenta y la ficha de empresa. Es decir, **justo lo que otros ven** — un
// suspendido por suplantar a alguien podia seguir editando el perfil con el
// que suplantaba.
//
// Lo encontro una auditoria de solo lectura del modulo P, que lo marco como
// "defecto de todo el proyecto, no de P". Lo era.
//
// Uso:  node pruebas/suspension.mjs

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
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);

const { execSync } = await import('node:child_process');
const sql = (q) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -t -c "${q}"`,
  { encoding: 'utf8' },
).trim();

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

const jefe = await reg('sa');
sql(`UPDATE usuario SET staff_nivel = 80 WHERE username = '${jefe.user}'`);

const reo = await reg('sb');
const mira = await reg('sc');

// Se le pone nombre y biografia ANTES de suspenderlo: lo que se prueba es que
// no pueda CAMBIARLOS despues, no que no tuviera nada.
let r = await put('/v1/perfil', reo.t, { nombreMostrado: 'Antes', estadoTexto: 'Antes' });
ck('la cuenta pone su nombre antes de la sancion', r.s === 200, String(r.s));

r = await post(`/v1/panel/usuarios/${reo.user}/suspender`, jefe.t, {
  motivo: 'Suplantar a una institucion', horas: 24,
});
ck('un administrador la suspende', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 80)}`);

// `::text` no es adorno: psql en modo -t imprime los booleanos como `t`/`f`
// y con el cast salen `true`/`false`. Sin el, la comparacion falla por el
// formato y no por el dato, que es la clase de rojo que hace perder una hora.
const suspendida = sql(
  `SELECT (suspendido_en IS NOT NULL)::text FROM usuario WHERE username = '${reo.user}'`);
ck('y queda suspendida en la base', suspendida === 'true', suspendida);

// ---------------------------------------------------------------------------
//  1 · Lo que SI puede: entrar y enterarse
// ---------------------------------------------------------------------------
console.log('\n=== 1 · la sesion sigue viva a proposito ===');

// Esta es la mitad deliberada: una sancion que echa a la calle sin explicacion
// no corrige nada. La cuenta entra, pero entra a leer.
r = await get('/v1/perfil', reo.t);
ck('la sesion sigue valiendo: puede entrar', r.s === 200, String(r.s));

r = await get('/v1/moderacion/mi-estado', reo.t);
ck('y puede ver por que la sancionaron', r.s === 200, String(r.s));
ck('con el motivo, que es lo unico que hace util la pantalla',
   String(JSON.stringify(r.b)).includes('Suplantar'), JSON.stringify(r.b).slice(0, 140));

// ---------------------------------------------------------------------------
//  2 · Lo que NO puede: tocar lo que otros ven
// ---------------------------------------------------------------------------
console.log('\n=== 2 · el perfil publico queda congelado ===');

// EL AGUJERO: todo esto se guardaba sin mirar la suspension, porque no pasa por
// `Autz.puede`. Un suspendido por suplantacion seguia editando el perfil con el
// que suplantaba.
r = await put('/v1/perfil', reo.t, { nombreMostrado: 'Banco Nacional', estadoTexto: 'Oficial' });
ck('no puede cambiar su nombre ni su estado', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);
ck('y se le dice que esta suspendida',
   String(r.b?.motivo || '').toLowerCase().includes('suspend'), JSON.stringify(r.b?.motivo));

const nombreAhora = sql(
  `SELECT coalesce(nombre_mostrado,'') FROM usuario WHERE username = '${reo.user}'`);
ck('el nombre sigue siendo el de antes', nombreAhora === 'Antes', nombreAhora);

r = await put('/v1/cuenta', reo.t, { biografia: 'Entidad financiera regulada' });
ck('ni su biografia', r.s === 403, String(r.s));

const bioAhora = sql(
  `SELECT coalesce(biografia,'') FROM usuario WHERE username = '${reo.user}'`);
ck('y la biografia no se toco', bioAhora === '', bioAhora);

// Una imagen es lo mas visible de un perfil: un logo ajeno vale mas que
// cualquier texto para hacerse pasar por alguien.
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  Buffer.alloc(64, 7),
]);
let rr = await fetch(BASE + '/v1/perfil/avatar', {
  method: 'PUT',
  headers: { Authorization: 'Bearer ' + reo.t, 'Content-Type': 'application/octet-stream' },
  body: png,
});
ck('ni su foto de perfil', rr.status === 403, String(rr.status));

rr = await fetch(BASE + '/v1/perfil/portada', {
  method: 'PUT',
  headers: { Authorization: 'Bearer ' + reo.t, 'Content-Type': 'application/octet-stream' },
  body: png,
});
ck('ni su portada', rr.status === 403, String(rr.status));

// ---------------------------------------------------------------------------
//  3 · Tampoco publicar historias
// ---------------------------------------------------------------------------
console.log('\n=== 3 · una historia es contenido ===');

// Una historia la ve la audiencia entera. Que un suspendido pudiera publicar
// seria la sancion mas facil de saltarse que hay.
r = await post('/v1/historias', reo.t, { historiaId: uuid(), clase: 'texto' });
ck('no puede publicar una historia', r.s === 403, `${r.s} ${JSON.stringify(r.b)}`);

// ---------------------------------------------------------------------------
//  4 · Ni cambiar el tipo de cuenta ni la ficha de empresa
// ---------------------------------------------------------------------------
console.log('\n=== 4 · el tipo de cuenta y la ficha ===');

// Estas dos rutas responden 404 a quien esta fuera de la beta, asi que aqui se
// comprueba lo que se puede: que la suspension no se convierta en un 200. Si
// alguna vez la beta se abre, la prueba pasa a medir el 403 sin tocarla.
r = await put('/v1/cuenta/tipo', reo.t, { tipo: 'empresa' });
ck('el tipo de cuenta no se cambia suspendida', r.s === 403 || r.s === 404, String(r.s));

r = await put('/v1/cuenta/empresa', reo.t, { nombreComercial: 'Banco', categoria: 'finanzas' });
ck('ni la ficha de empresa', r.s === 403 || r.s === 404 || r.s === 409, String(r.s));

// ---------------------------------------------------------------------------
//  5 · Al levantar la sancion, todo vuelve
// ---------------------------------------------------------------------------
console.log('\n=== 5 · restaurar devuelve el perfil ===');

r = await post(`/v1/panel/usuarios/${reo.user}/restaurar`, jefe.t);
ck('un administrador la restaura', r.s === 200, String(r.s));

r = await put('/v1/perfil', reo.t, { nombreMostrado: 'Despues', estadoTexto: 'Despues' });
ck('y vuelve a poder editar su perfil', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 80)}`);

const nombreFinal = sql(
  `SELECT coalesce(nombre_mostrado,'') FROM usuario WHERE username = '${reo.user}'`);
ck('con el cambio guardado', nombreFinal === 'Despues', nombreFinal);

// --- una suspension vencida no sigue castigando -----------------------
// Sin la condicion de `suspendido_hasta`, la columna seria decorativa: la
// sancion temporal duraria para siempre.
sql(`UPDATE usuario SET suspendido_en = now() - interval '48 hours', suspendido_hasta = now() - interval '1 hour' WHERE username = '${reo.user}'`);
r = await put('/v1/perfil', reo.t, { nombreMostrado: 'Vencida', estadoTexto: 'x' });
ck('una suspension VENCIDA no bloquea nada', r.s === 200, String(r.s));

// --- y la sancion no se contagia --------------------------------------
sql(`UPDATE usuario SET suspendido_en = now(), suspendido_hasta = now() + interval '24 hours' WHERE username = '${reo.user}'`);
r = await put('/v1/perfil', mira.t, { nombreMostrado: 'Ajena', estadoTexto: 'x' });
ck('otra cuenta sin sancion no se ve afectada', r.s === 200, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
