// Modulo M · Contenido con estructura y reacciones.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que una reaccion por persona sea UNA: la segunda reemplaza a la primera
//     en vez de ponerse al lado. Es el defecto que se reporto.
//  2. Que la misma reaccion de varias personas SI se acumule.
//  3. Que `encuesta.crear` y `evento.crear` se exijan de verdad, contra la
//     clase que el cliente declara. Estaban en el catalogo desde V4 sin nada
//     detras.
//  4. Que una clase inventada sea un 400 y no pase de largo como texto: si
//     pasara, la declaracion no serviria para autorizar nada.
//  5. Que votar no pida permiso propio, y que un restringido no pueda abrir
//     encuestas aunque pueda escribir.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const uuid = () => crypto.randomUUID();

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const post = (r, t, b) => call('POST', r, t, b);
const get = (r, t) => call('GET', r, t);

const ana = await reg('ma');
const beto = await reg('mb');
const cora = await reg('mc');

// Un grupo con los tres: las encuestas y los permisos por rol solo tienen
// sentido donde hay roles.
let r = await post('/v1/conversaciones/grupo', ana.t, {
  nombre: 'Grupo M', usernames: [beto.user, cora.user],
});
const grupo = r.b.conversacionId ?? r.b.id;
ck('se crea el grupo', !!grupo, JSON.stringify(r.b).slice(0, 120));

/** Registra el metadato de un mensaje, como hace el cliente antes de cifrar. */
const registrar = (quien, extra = {}) => post('/v1/mensajes', quien.t, {
  mensajeId: uuid(), conversacionId: grupo, ...extra,
});

console.log('\n=== una reaccion por persona ===');
r = await registrar(ana);
const msg = r.b.id;
ck('se registra un mensaje', !!msg, JSON.stringify(r.b).slice(0, 120));

const reaccionar = (quien, emoji, poner = true) =>
  post('/v1/mensajes/reaccion', quien.t, { mensajeId: msg, emoji, poner });

const cuenta = async (quien) => (await get(`/v1/mensajes/${msg}`, quien.t)).b.reacciones ?? [];

r = await reaccionar(beto, '😂');
ck('beto reacciona', r.s === 200, String(r.s));
let rs = await cuenta(ana);
ck('hay una reaccion', rs.length === 1 && rs[0].emoji === '😂', JSON.stringify(rs));

r = await reaccionar(beto, '❤️');
ck('beto reacciona con otro emoji', r.s === 200, String(r.s));
rs = await cuenta(ana);
ck('SIGUE habiendo una sola: la nueva reemplazo a la anterior',
   rs.length === 1, JSON.stringify(rs));
ck('y es la ultima que eligio', rs[0]?.emoji === '❤️', JSON.stringify(rs));
ck('con total 1, no 2', rs[0]?.total === 1, JSON.stringify(rs));

console.log('\n=== la MISMA reaccion de varias personas si se acumula ===');
r = await reaccionar(cora, '❤️');
rs = await cuenta(ana);
ck('sigue habiendo un solo emoji', rs.length === 1, JSON.stringify(rs));
ck('pero ahora con total 2', rs[0]?.total === 2, JSON.stringify(rs));

r = await reaccionar(ana, '😂');
rs = await cuenta(ana);
ck('un emoji distinto de otra persona se agrega al costado',
   rs.length === 2, JSON.stringify(rs));
ck('cada uno con su cuenta',
   rs.find((x) => x.emoji === '❤️')?.total === 2 &&
   rs.find((x) => x.emoji === '😂')?.total === 1, JSON.stringify(rs));

console.log('\n=== quitar es un interruptor sobre la MIA ===');
r = await reaccionar(cora, '❤️', false);
rs = await cuenta(ana);
ck('cora quita la suya y el total baja',
   rs.find((x) => x.emoji === '❤️')?.total === 1, JSON.stringify(rs));

r = await reaccionar(cora, '😂', false);
rs = await cuenta(ana);
ck('quitar un emoji que no es el mio no le quita el de nadie',
   rs.find((x) => x.emoji === '😂')?.total === 1, JSON.stringify(rs));

console.log('\n=== reenviar: el autor original viaja como USERNAME ===');
// Estuvo roto desde el modulo C: el cliente manda el username -es lo que guarda
// de cada mensaje- y el servidor hacia UUID.fromString con el, asi que TODO
// reenvio se rechazaba con 400. Ninguna prueba lo veia porque ninguna probaba
// `reenviadoDe`, y la unica senal era una burbuja coral en el telefono.
r = await registrar(ana, { reenviadoDe: beto.user });
ck('se acepta un username como origen del reenvio', r.s === 200, JSON.stringify(r.b).slice(0, 140));
ck('y se devuelve como username, no como uuid',
   r.b?.reenviadoDe === beto.user, String(r.b?.reenviadoDe));

r = await registrar(ana, { reenviadoDe: 'nadie_con_este_nombre' });
ck('un autor que no existe NO rechaza el mensaje', r.s === 200, JSON.stringify(r.b).slice(0, 140));
ck('solo se pierde la atribucion', !r.b?.reenviadoDe, String(r.b?.reenviadoDe));

const { execSync: ejecutar } = await import('node:child_process');
// Se mira la BASE y no solo la respuesta: lo que hay que demostrar es que el
// servidor no guarda nada, y eso no se ve desde fuera.
const psql = (sql) => ejecutar(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`,
  { stdio: 'pipe' },
).toString().trim();
console.log('\n=== la clase declarada se valida ===');
r = await registrar(ana, { clase: 'inventada' });
ck('una clase desconocida es 400', r.s === 400, JSON.stringify(r.b));

for (const clase of ['', 'ubicacion', 'contacto', 'voto']) {
  r = await registrar(ana, { clase });
  ck(`"${clase || '(texto)'}" no pide permiso extra`, r.s === 200, JSON.stringify(r.b).slice(0, 90));
}

console.log('\n=== encuestas y eventos: el permiso se exige ===');
r = await registrar(ana, { clase: 'encuesta' });
ck('la dueña del grupo puede crear encuestas', r.s === 200, JSON.stringify(r.b).slice(0, 90));
r = await registrar(ana, { clase: 'evento' });
ck('y eventos', r.s === 200, JSON.stringify(r.b).slice(0, 90));

r = await registrar(beto, { clase: 'encuesta' });
ck('un miembro normal tambien puede abrir encuestas', r.s === 200, JSON.stringify(r.b).slice(0, 90));

// El rol `miembro` de V4 NO trae `evento.crear`, y es a proposito: un evento le
// pide asistencia a todo el grupo, asi que se reservo de moderador para arriba.
r = await registrar(beto, { clase: 'evento' });
ck('pero un miembro normal NO puede crear eventos', r.s === 403, JSON.stringify(r.b).slice(0, 90));

// Restringido: el rol que existe justamente para dejar leer y poco mas.
r = await post(`/v1/conversaciones/${grupo}/miembros/${cora.id}/rol`, ana.t, { rolClave: 'restringido' });
ck('se restringe a cora', r.s === 204, JSON.stringify(r.b).slice(0, 120));

r = await registrar(cora, { clase: 'encuesta' });
ck('una restringida NO puede abrir encuestas', r.s === 403, JSON.stringify(r.b));
r = await registrar(cora, { clase: 'evento' });
ck('ni eventos', r.s === 403, JSON.stringify(r.b));
r = await registrar(cora, { clase: '' });
ck('una restringida tampoco puede escribir texto', r.s === 403, JSON.stringify(r.b));
// Votar no pide permiso propio, pero si pide poder escribir: un voto es un
// sobre. Se comprueba con beto, que es miembro normal.
r = await registrar(beto, { clase: 'voto' });
ck('un miembro normal puede votar sin permiso extra', r.s === 200, JSON.stringify(r.b).slice(0, 90));

console.log('\n=== quien no puede escribir tampoco puede abrir una encuesta ===');
// El orden importa: `mensaje.enviar` se comprueba ANTES que la clase. Si no
// fuera asi, un silenciado podria colar contenido por la puerta de la encuesta.
r = await post(`/v1/conversaciones/${grupo}/miembros/${beto.id}/silenciar`, ana.t,
                { minutos: 60, motivo: 'prueba' });
ck('se silencia a beto', r.s === 204, JSON.stringify(r.b).slice(0, 120));
r = await registrar(beto, { clase: 'encuesta' });
ck('silenciado, ya no puede abrir encuestas', r.s === 403, JSON.stringify(r.b));
r = await registrar(beto, { clase: 'voto' });
ck('ni votar: `mensaje.enviar` se comprueba ANTES que la clase', r.s === 403, JSON.stringify(r.b));


console.log('\n=== AM: la ubicacion en vivo, vista desde el servidor ===');
//
// Lo que hay que comprobar aqui no es que funcione —eso es del cliente y lo
// fijan las pruebas unitarias— sino que el servidor NO SEPA NADA. La funcion
// entera se apoya en que el buzon es tonto: si el servidor pudiera ver una
// coordenada o una fecha, compartir la ubicacion ocho horas seria entregarle
// ocho horas de recorrido.

for (const clase of ['ubicacion_viva', 'ubicacion_viva_fin']) {
  r = await registrar(ana, { clase });
  ck(`"${clase}" se acepta y no pide permiso extra`, r.s === 200, JSON.stringify(r.b).slice(0, 90));
}

// Ni una columna con posicion en las tablas por donde pasan los mensajes.
//
// Se excluye `perfil_empresa.ubicacion` a proposito y no por comodidad: esa
// es la direccion que un negocio PUBLICA de si mismo en su perfil, texto que
// el duenio escribio para que se vea. No tiene nada que ver con donde esta
// una persona ahora, que es lo que este modulo mueve.
const columnasUbic = psql(
  `SELECT count(*) FROM information_schema.columns WHERE table_schema='public' ` +
  `AND table_name <> 'perfil_empresa' AND (` +
  `column_name LIKE '%latitud%' OR column_name LIKE '%longitud%' OR ` +
  `column_name LIKE '%ubicac%' OR column_name LIKE '%posicion%')`,
);
ck('ninguna columna del servidor guarda una posicion de nadie',
   columnasUbic === '0', columnasUbic);

// Ni una tabla. El modulo no agrego estado al servidor: las posiciones son
// sobres cifrados y el vencimiento viaja dentro de la carga.
const tablasUbic = psql(
  `SELECT count(*) FROM information_schema.tables ` +
  `WHERE table_schema='public' AND (table_name LIKE '%ubicac%' OR table_name LIKE '%posicion%')`,
);
ck('y no hay tabla de ubicaciones: el servidor no lleva ninguna cuenta',
   tablasUbic === '0', tablasUbic);

// Y la clase ni siquiera se guarda: se valida en el camino y se tira. O sea
// que ni el METADATO de "aqui hubo un compartido en vivo" queda en la base.
const dondeClase = psql(
  `SELECT count(*) FROM information_schema.columns ` +
  `WHERE table_name = 'mensaje_meta' AND column_name LIKE '%clase%'`,
);
ck('la clase se valida y se tira: no queda ni en los metadatos del mensaje',
   dondeClase === '0', dondeClase);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
