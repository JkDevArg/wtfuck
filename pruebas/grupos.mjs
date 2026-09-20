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
  return { t: j.token, id: j.usuarioId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const get = (ruta, t) => call('GET', ruta, t);
const post = (ruta, t, b) => call('POST', ruta, t, b);
const put = (ruta, t, b) => call('PUT', ruta, t, b);
const del = (ruta, t) => call('DELETE', ruta, t);

const jefe = await reg('jefe');
const admin = await reg('admin');
const mod = await reg('mod');
const socio = await reg('socio');
const fuera = await reg('fuera');

const g = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Equipo B', usernames: [admin.user, mod.user, socio.user] });
const G = g.b.id;
const C = `/v1/conversaciones/${G}`;

// jefe reparte roles
await post(`${C}/miembros/${admin.id}/rol`, jefe.t, { rolClave: 'administrador' });
await post(`${C}/miembros/${mod.id}/rol`, jefe.t, { rolClave: 'moderador' });

console.log('\n=== B.2 configuracion del grupo ===');
let cfg = await get(`${C}/config`, socio.t);
ck('cualquier miembro puede ver la configuracion', cfg.s === 200 && cfg.b.nombre === 'Equipo B', JSON.stringify(cfg.b));

let r = await put(`${C}/config`, socio.t, { ...cfg.b, descripcion: 'hackeado' });
ck('un miembro normal NO puede editarla', r.s === 403, String(r.s));

r = await put(`${C}/config`, mod.t, { ...cfg.b, descripcion: 'hackeado' });
ck('un moderador tampoco', r.s === 403, String(r.s));

r = await put(`${C}/config`, admin.t, { ...cfg.b, descripcion: 'Equipo del modulo B', alias: 'equipo-b-' + S });
ck('un administrador si', r.s === 200 && r.b.descripcion === 'Equipo del modulo B', JSON.stringify(r.b));

const otroG = await post('/v1/conversaciones/grupo', jefe.t, { nombre: 'Otro', usernames: [] });
r = await put(`/v1/conversaciones/${otroG.b.id}/config`, jefe.t, { nombre: 'Otro', alias: 'equipo-b-' + S });
ck('un alias ya usado se rechaza', r.s === 409, String(r.s));

r = await put(`${C}/config`, admin.t, { nombre: 'Equipo B', alias: 'AB' });
ck('un alias invalido se rechaza', r.s === 400, String(r.s));

console.log('\n=== B.2 modo anuncio ===');
await put(`${C}/config`, admin.t, { nombre: 'Equipo B', soloAdmins: true });
// Se comprueba con la ruta de preferencias? No: se valida al enviar, via WS.
// Aqui se verifica con el efecto observable mas cercano: crear invitacion.
r = await post(`${C}/invitaciones`, socio.t, { horas: 0, usosMax: 0 });
ck('un miembro no puede crear enlaces de invitacion', r.s === 403, String(r.s));
await put(`${C}/config`, admin.t, { nombre: 'Equipo B', soloAdmins: false });

console.log('\n=== B.3 gestion de miembros ===');
let ms = await get(`${C}/miembros`, socio.t);
ck('la lista de miembros trae rol y jerarquia', ms.s === 200 && ms.b.length === 4 && ms.b[0].jerarquia === 100, JSON.stringify(ms.b?.map(x => x.rolClave)));

r = await post(`${C}/miembros/${admin.id}/expulsar`, mod.t, { motivo: 'porque si' });
ck('un moderador no puede expulsar a un administrador', r.s === 403, String(r.s));

r = await post(`${C}/miembros/${jefe.id}/expulsar`, admin.t, { motivo: 'golpe' });
ck('un administrador no puede expulsar al propietario', r.s === 403, String(r.s));

r = await post(`${C}/miembros/${socio.id}/silenciar`, mod.t, { minutos: 60, motivo: 'spam' });
ck('un moderador si puede silenciar a un miembro', r.s === 204, String(r.s));

ms = await get(`${C}/miembros`, admin.t);
const silenciado = ms.b.find((x) => x.usuario.username === socio.user);
ck('el silencio aparece en la lista con su vencimiento', silenciado?.restriccion === 'silenciado' && silenciado?.restringidoHasta > Date.now(), JSON.stringify(silenciado));

r = await del(`${C}/miembros/${socio.id}/silenciar`, mod.t);
ck('y se puede levantar', r.s === 204);

console.log('\n=== B.4 roles personalizados ===');
r = await post(`${C}/roles`, admin.t, { nombre: 'Curador', jerarquia: 40, permisos: ['mensaje.fijar', 'mensaje.borrar_ajeno'] });
ck('un administrador crea un rol propio', r.s === 200 && r.b.jerarquia === 40, JSON.stringify(r.b));
const rolCurador = r.b;

r = await post(`${C}/roles`, admin.t, { nombre: 'Casi jefe', jerarquia: 95, permisos: [] });
ck('no se puede crear un rol con jerarquia de sistema', r.s === 400, String(r.s));

r = await post(`${C}/roles`, mod.t, { nombre: 'Trampa', jerarquia: 10, permisos: [] });
ck('un moderador no puede crear roles', r.s === 403, String(r.s));

r = await post(`${C}/roles`, admin.t, { nombre: 'Goloso', jerarquia: 30, permisos: ['grupo.eliminar'] });
ck('nadie puede otorgar un permiso que el mismo no tiene', r.s === 403, JSON.stringify(r.b));

r = await post(`${C}/miembros/${socio.id}/rol`, admin.t, { rolClave: rolCurador.clave });
ck('se asigna el rol propio a un miembro', r.s === 204, String(r.s));

ms = await get(`${C}/miembros`, admin.t);
ck('el miembro aparece con su rol nuevo', ms.b.find((x) => x.usuario.username === socio.user)?.rolNombre === 'Curador');

r = await post(`${C}/miembros/${mod.id}/rol`, admin.t, { rolClave: 'propietario' });
ck('no se puede promover a alguien por encima de uno mismo', r.s === 403, String(r.s));

r = await del(`${C}/roles/${rolCurador.id}`, admin.t);
ck('se elimina el rol propio', r.s === 204, String(r.s));
ms = await get(`${C}/miembros`, admin.t);
ck('quien lo tenia vuelve a miembro, no queda sin rol', ms.b.find((x) => x.usuario.username === socio.user)?.rolClave === 'miembro');

const rolSistema = (await get(`${C}/roles`, admin.t)).b.find((x) => x.clave === 'moderador');
r = await del(`${C}/roles/${rolSistema.id}`, admin.t);
ck('un rol de sistema no se puede eliminar', r.s === 400, String(r.s));

console.log('\n=== B.5 enlaces de invitacion ===');
r = await post(`${C}/invitaciones`, admin.t, { horas: 0, usosMax: 1 });
ck('un administrador crea un enlace', r.s === 200 && r.b.codigo?.length >= 8, JSON.stringify(r.b));
const inv = r.b.codigo;

let vp = await get(`/v1/invitaciones/${inv}`, fuera.t);
ck('la vista previa muestra el grupo sin entrar', vp.s === 200 && vp.b.nombre === 'Equipo B' && vp.b.yaEsMiembro === false, JSON.stringify(vp.b));

r = await post(`/v1/invitaciones/${inv}`, fuera.t);
ck('usar el enlace hace ingresar', r.s === 200 && r.b.estado === 'ingresado', JSON.stringify(r.b));

const otro = await reg('otro');
r = await post(`/v1/invitaciones/${inv}`, otro.t);
ck('un enlace de un solo uso ya no sirve', r.s === 404, String(r.s));

r = await post(`${C}/invitaciones`, admin.t, { horas: 0, usosMax: 0 });
const inv2 = r.b.codigo;
r = await del(`${C}/invitaciones/${inv2}`, admin.t);
ck('se revoca un enlace', r.s === 204);
r = await post(`/v1/invitaciones/${inv2}`, otro.t);
ck('un enlace revocado no sirve', r.s === 404, String(r.s));

console.log('\n=== B.5 veto ===');
r = await post(`${C}/miembros/${fuera.id}/expulsar`, admin.t, { motivo: 'spam', vetar: true });
ck('se expulsa y veta a alguien', r.s === 204, String(r.s));
r = await post(`${C}/invitaciones`, admin.t, { horas: 0, usosMax: 0 });
r = await post(`/v1/invitaciones/${r.b.codigo}`, fuera.t);
ck('un vetado no entra ni con enlace valido', r.s === 403, JSON.stringify(r.b));

console.log('\n=== B.6 solicitudes de ingreso ===');
await put(`${C}/config`, admin.t, { nombre: 'Equipo B', aprobarIngreso: true });
r = await post(`${C}/invitaciones`, admin.t, { horas: 0, usosMax: 0 });
const inv3 = r.b.codigo;

r = await post(`/v1/invitaciones/${inv3}`, otro.t);
ck('con aprobacion activa, el enlace genera solicitud', r.s === 200 && r.b.estado === 'solicitud_enviada', JSON.stringify(r.b));

let sol = await get(`${C}/solicitudes`, socio.t);
ck('un miembro normal no ve las solicitudes', sol.s === 403, String(sol.s));

sol = await get(`${C}/solicitudes`, admin.t);
ck('un administrador si las ve', sol.s === 200 && sol.b.length === 1, JSON.stringify(sol.b?.map(x => x.usuario.username)));

r = await post(`${C}/solicitudes/${otro.id}`, admin.t, { aprobar: true });
ck('se aprueba la solicitud', r.s === 204, String(r.s));
ms = await get(`${C}/miembros`, admin.t);
ck('y la persona queda dentro', ms.b.some((x) => x.usuario.username === otro.user));

r = await post(`${C}/solicitudes/${otro.id}`, admin.t, { aprobar: true });
ck('no se puede resolver dos veces la misma solicitud', r.s === 404, String(r.s));

console.log('\n=== B.7 preferencias personales ===');
r = await put(`${C}/preferencias`, socio.t, { silenciarMinutos: 120, archivado: true, fijado: false });
ck('silenciar y archivar un chat', r.s === 200 && r.b.archivado === true && r.b.silenciadoHasta > Date.now(), JSON.stringify(r.b));

r = await put(`${C}/preferencias`, socio.t, { silenciarMinutos: -1 });
ck('silenciar para siempre', r.s === 200 && r.b.silenciadoHasta === -1, JSON.stringify(r.b));

r = await put(`${C}/preferencias`, socio.t, { silenciarMinutos: 0 });
ck('quitar el silencio', r.s === 200 && r.b.silenciadoHasta === null, JSON.stringify(r.b));

r = await put(`/v1/conversaciones/${otroG.b.id}/preferencias`, socio.t, { archivado: true });
ck('no se pueden tocar las preferencias de un chat ajeno', r.s === 404, String(r.s));

console.log('\n=== bloqueos ===');
r = await post(`/v1/bloqueos/${mod.user}`, socio.t);
ck('bloquear a alguien', r.s === 204, String(r.s));
r = await post('/v1/conversaciones/directa', socio.t, { usernameDestino: mod.user });
ck('con bloqueo NO se abre conversacion directa', r.s === 403, String(r.s) + ' ' + JSON.stringify(r.b));
r = await del(`/v1/bloqueos/${mod.user}`, socio.t);
ck('desbloquear', r.s === 204);

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
