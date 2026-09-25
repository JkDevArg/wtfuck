// Comunidades (modulo AD).
//
// ## Que es una comunidad aqui
//
// Un conjunto de grupos bajo un nombre, MAS un canal de anuncios. Lo segundo
// es lo unico que la hace una comunidad: sin el, agrupar chats es una carpeta,
// y una carpeta se resuelve en el telefono sin que el servidor se entere.
//
// ## Lo que esta suite fija, y por que
//
// La pertenencia se **deriva**: sos de la comunidad si sos de alguno de sus
// grupos. No hay lista de miembros aparte. Eso significa que la fila de
// `participante` del canal de anuncios hay que MANTENERLA en cuatro momentos,
// y cada uno tiene su seccion aqui:
//
//   1. cuando un grupo entra a la comunidad
//   2. cuando alguien entra a un grupo que ya estaba en la comunidad
//   3. cuando alguien sale de un grupo
//   4. cuando un grupo sale de la comunidad
//
// Los cuatro son el mismo invariante mirado desde cuatro lados. Probar solo el
// primero -que es el facil- dejaria pasar exactamente los defectos que hacen
// que el modelo derivado se rompa con el uso.
//
// Y el ajuste de privacidad `comunidades`, que es lo que el §3 del brief pedia
// y faltaba: alguien agrega a la comunidad el grupo en el que YA estabas, y de
// golpe estas en un canal con quinientos desconocidos sin que nadie te haya
// agregado a nada.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const uuid = () => crypto.randomUUID();

async function pedir(ruta, metodo, token, cuerpo) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + ruta, {
    method: metodo, headers: h,
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  });
  const t = await r.text();
  let b = null;
  try { b = t ? JSON.parse(t) : null; } catch { b = t; }
  return { s: r.status, b };
}
const get = (r, t) => pedir(r, 'GET', t);
const post = (r, t, c) => pedir(r, 'POST', t, c);
const put = (r, t, c) => pedir(r, 'PUT', t, c);
const del = (r, t) => pedir(r, 'DELETE', t);

async function reg(u) {
  const nom = u + S;
  const r = await post('/v1/registro', null, {
    username: nom, password: 'clave-larga-123', etiquetaDispositivo: 't',
    identidadPub: b64('k' + nom), hardwareHash: b64('HW-' + nom), hardwareNivel: 'SOFTWARE_DEV',
  });
  if (r.s !== 200) throw new Error('registro fallo: ' + r.s + ' ' + JSON.stringify(r.b));
  return { user: nom, t: r.b.token, id: r.b.usuarioId };
}

let r;
const duenoC = await reg('ka');
const miembro = await reg('kb');
const otro = await reg('kc');
const ajeno = await reg('kd');

// Un grupo con el dueno y un miembro.
async function grupoCon(dueno, gente, nombre) {
  const r = await post('/v1/conversaciones/grupo', dueno.t, {
    nombre, usernames: gente.map(g => g.user),
  });
  if (r.s !== 200) throw new Error('grupo fallo: ' + r.s + ' ' + JSON.stringify(r.b));
  return r.b.conversacion?.id ?? r.b.id;
}

console.log('\n=== crear una comunidad ===');
const g1 = await grupoCon(duenoC, [miembro], 'Equipo A');
const g2 = await grupoCon(duenoC, [otro], 'Equipo B');

r = await post('/v1/comunidades', duenoC.t, {
  nombre: 'Laboratorio', descripcion: 'Todo el lab', grupos: [g1],
});
ck('se crea la comunidad con un grupo', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b).slice(0, 160));
const com = r.b?.comunidad;
ck('trae su canal de anuncios', typeof com?.anunciosId === 'string' && com.anunciosId.length > 10,
   JSON.stringify(com));
ck('y cuenta su grupo', com?.grupos === 1, String(com?.grupos));
ck('quien la crea la administra', com?.soyAdmin === true, String(com?.soyAdmin));
const anuncios = com?.anunciosId;

console.log('\n=== 1. al entrar el grupo, su gente entra a los anuncios ===');
r = await get('/v1/conversaciones', miembro.t);
const veAnuncios = (r.b?.conversaciones ?? r.b ?? []).some(x => (x.id ?? x.conversacionId) === anuncios);
ck('el miembro del grupo ve el canal de anuncios en su lista', veAnuncios,
   JSON.stringify((r.b?.conversaciones ?? r.b ?? []).map(x => x.id ?? x.conversacionId)).slice(0, 200));

r = await get('/v1/comunidades', miembro.t);
ck('y la comunidad aparece entre las suyas',
   (r.b?.comunidades ?? []).some(x => x.id === com.id), JSON.stringify(r.b).slice(0, 160));
ck('pero NO como administrador',
   (r.b?.comunidades ?? []).find(x => x.id === com.id)?.soyAdmin === false,
   JSON.stringify((r.b?.comunidades ?? []).find(x => x.id === com.id)));

r = await get('/v1/comunidades', ajeno.t);
ck('quien no esta en ningun grupo no la ve',
   !(r.b?.comunidades ?? []).some(x => x.id === com.id), JSON.stringify(r.b).slice(0, 120));

r = await get(`/v1/comunidades/${com.id}`, ajeno.t);
ck('y pedirla por id da 404, no 403', r.s === 404, String(r.s));

console.log('\n=== 2. quien entra al grupo DESPUES, entra a los anuncios ===');
//
// Este es el que rompe un modelo derivado mal implementado: el grupo ya estaba
// en la comunidad, asi que sumar a su gente "al agregar el grupo" no alcanza.
r = await post(`/v1/conversaciones/${g1}/miembros`, duenoC.t, { usernames: [ajeno.user] });
ck('se agrega a alguien al grupo que ya estaba en la comunidad', r.s === 200,
   String(r.s) + ' ' + JSON.stringify(r.b).slice(0, 120));

r = await get('/v1/comunidades', ajeno.t);
ck('y ahora SI ve la comunidad', (r.b?.comunidades ?? []).some(x => x.id === com.id),
   JSON.stringify(r.b).slice(0, 160));

r = await get('/v1/conversaciones', ajeno.t);
ck('y el canal de anuncios',
   (r.b?.conversaciones ?? r.b ?? []).some(x => (x.id ?? x.conversacionId) === anuncios),
   'no aparece');

console.log('\n=== 3. quien sale del grupo sale de los anuncios ===');
r = await post(`/v1/conversaciones/${g1}/salir`, ajeno.t);
ck('sale del grupo', r.s === 200 || r.s === 204, String(r.s));

r = await get('/v1/comunidades', ajeno.t);
ck('y deja de ver la comunidad', !(r.b?.comunidades ?? []).some(x => x.id === com.id),
   JSON.stringify(r.b).slice(0, 160));

// Y sobre todo: deja de estar en el CANAL DE ANUNCIOS.
//
// Esta es la que importa, y la primera version de esta suite no la tenia. Solo
// comprobaba la lista de comunidades, que se **deriva** de estar en un grupo:
// al salir del grupo deja de aparecer ahi aunque la fila del canal se quede
// para siempre. O sea que pasaba con el enganche puesto y sin el.
//
// Lo confirme quitando los dos enganches a proposito: de las cuatro secciones
// del invariante, solo una fallaba. Una prueba que no falla contra el codigo
// roto no prueba nada.
r = await get('/v1/conversaciones', ajeno.t);
ck('y sale del canal de anuncios',
   !(r.b?.conversaciones ?? r.b ?? []).some(x => (x.id ?? x.conversacionId) === anuncios),
   'sigue en los anuncios');

console.log('\n=== agregar mas grupos ===');
r = await post(`/v1/comunidades/${com.id}/grupos`, duenoC.t, { grupos: [g2] });
ck('el admin agrega otro grupo', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b).slice(0, 120));
ck('y ahora son dos', r.b?.comunidad?.grupos === 2, String(r.b?.comunidad?.grupos));

r = await get('/v1/comunidades', otro.t);
ck('la gente del grupo nuevo tambien la ve',
   (r.b?.comunidades ?? []).some(x => x.id === com.id), JSON.stringify(r.b).slice(0, 160));

console.log('\n=== lo que NO se puede agregar ===');
const ajenoGrupo = await grupoCon(ajeno, [], 'Grupo de otro');
r = await post(`/v1/comunidades/${com.id}/grupos`, duenoC.t, { grupos: [ajenoGrupo] });
ck('no se puede agregar un grupo que no administras', r.s === 200, String(r.s));
ck('y se dice cual y por que',
   (r.b?.rechazados ?? []).some(x => x.conversacionId === ajenoGrupo && /administr/i.test(x.motivo)),
   JSON.stringify(r.b?.rechazados));
ck('sin haberlo agregado', r.b?.comunidad?.grupos === 2, String(r.b?.comunidad?.grupos));

r = await post(`/v1/comunidades/${com.id}/grupos`, duenoC.t, { grupos: [g2] });
ck('un grupo que ya esta se rechaza y se explica',
   (r.b?.rechazados ?? []).some(x => x.conversacionId === g2 && /ya esta/i.test(x.motivo)),
   JSON.stringify(r.b?.rechazados));

r = await post(`/v1/comunidades/${com.id}/grupos`, duenoC.t, { grupos: [anuncios] });
ck('un canal no es un grupo y se rechaza',
   (r.b?.rechazados ?? []).some(x => /solo se pueden agregar grupos/i.test(x.motivo)),
   JSON.stringify(r.b?.rechazados));

// NO falla entero: quien agrega cinco y tiene permiso en cuatro espera que
// entren los cuatro y que le digan cual no.
const g3 = await grupoCon(duenoC, [], 'Equipo C');
r = await post(`/v1/comunidades/${com.id}/grupos`, duenoC.t, { grupos: [g3, ajenoGrupo] });
ck('con uno bueno y uno malo, el bueno entra igual', r.b?.comunidad?.grupos === 3,
   String(r.b?.comunidad?.grupos));
ck('y el malo se informa aparte', (r.b?.rechazados ?? []).length === 1,
   JSON.stringify(r.b?.rechazados));

console.log('\n=== solo el admin manda ===');
r = await post(`/v1/comunidades/${com.id}/grupos`, miembro.t, { grupos: [g3] });
ck('un miembro NO agrega grupos', r.s === 403, String(r.s) + ' ' + JSON.stringify(r.b));

r = await put(`/v1/comunidades/${com.id}`, miembro.t, { nombre: 'Secuestrada', descripcion: '' });
ck('ni la edita', r.s === 403, String(r.s));

r = await put(`/v1/comunidades/${com.id}`, duenoC.t, { nombre: 'Lab CENTUSEC', descripcion: 'Avisos' });
ck('el admin si la edita', r.s === 200 && r.b?.comunidad?.nombre === 'Lab CENTUSEC',
   JSON.stringify(r.b?.comunidad).slice(0, 120));

console.log('\n=== 4. al salir el grupo, su gente sale de los anuncios ===');
r = await del(`/v1/comunidades/${com.id}/grupos/${g2}`, duenoC.t);
ck('el admin quita un grupo', r.s === 204, String(r.s));

r = await get('/v1/comunidades', otro.t);
ck('y su gente deja de ver la comunidad',
   !(r.b?.comunidades ?? []).some(x => x.id === com.id), JSON.stringify(r.b).slice(0, 160));

r = await get('/v1/conversaciones', otro.t);
ck('y sale del canal de anuncios',
   !(r.b?.conversaciones ?? r.b ?? []).some(x => (x.id ?? x.conversacionId) === anuncios),
   'sigue en los anuncios');

// Pero el admin sigue, aunque no este en ninguno de los grupos que quedan.
r = await get('/v1/comunidades', duenoC.t);
ck('el admin sigue viendo la suya', (r.b?.comunidades ?? []).some(x => x.id === com.id),
   JSON.stringify(r.b).slice(0, 160));

r = await del(`/v1/comunidades/${com.id}/grupos/${g2}`, duenoC.t);
ck('quitar un grupo que ya no esta da 404', r.s === 404, String(r.s));

console.log('\n=== el ajuste de privacidad del §3 ===');
//
// Lo que `priv_grupos` NO cubre: alguien agrega a la comunidad el grupo en el
// que YA estabas, y de golpe estas en un canal de anuncios con gente que no
// elegiste. Ese es el hecho nuevo.

const reservado = await reg('ke');
r = await get('/v1/perfil/privacidad', reservado.t);
ck('el ajuste existe y viene en todos por defecto', r.b?.comunidades === 'todos',
   JSON.stringify(r.b?.comunidades));

const priv = { ...r.b, comunidades: 'nadie' };
r = await put('/v1/perfil/privacidad', reservado.t, priv);
ck('se puede poner en nadie', r.s === 200 && r.b?.comunidades === 'nadie',
   String(r.s) + ' ' + JSON.stringify(r.b?.comunidades));

const gPriv = await grupoCon(duenoC, [reservado], 'Equipo con reservado');
r = await post(`/v1/comunidades/${com.id}/grupos`, duenoC.t, { grupos: [gPriv] });
ck('el grupo entra a la comunidad igual', r.s === 200, String(r.s));

r = await get('/v1/conversaciones', reservado.t);
const lista = (r.b?.conversaciones ?? r.b ?? []).map(x => x.id ?? x.conversacionId);
ck('pero a quien dijo "nadie" NO se lo mete en los anuncios',
   !lista.includes(anuncios), JSON.stringify(lista).slice(0, 200));
ck('y SIGUE en su grupo: el ajuste no expulsa de nada',
   lista.includes(gPriv), JSON.stringify(lista).slice(0, 200));

// Y el dueno del grupo, que si acepta, entra normalmente.
r = await get('/v1/conversaciones', duenoC.t);
ck('quien no lo cambio entra a los anuncios como siempre',
   (r.b?.conversaciones ?? r.b ?? []).some(x => (x.id ?? x.conversacionId) === anuncios),
   'no aparece');

console.log('\n=== un grupo pertenece a UNA comunidad ===');
r = await post('/v1/comunidades', duenoC.t, { nombre: 'Otra', grupos: [g3] });
ck('se crea otra comunidad', r.s === 200, String(r.s));
ck('y el grupo que ya era de la primera se rechaza',
   (r.b?.rechazados ?? []).some(x => x.conversacionId === g3 && /otra comunidad/i.test(x.motivo)),
   JSON.stringify(r.b?.rechazados));

console.log('\n=== sin sesion no hay nada ===');
r = await get('/v1/comunidades', null);
ck('listar sin token da 401', r.s === 401, String(r.s));
r = await post('/v1/comunidades', null, { nombre: 'x' });
ck('crear sin token da 401', r.s === 401, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
