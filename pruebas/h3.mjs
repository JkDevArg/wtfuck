// H.3: gobierno de grupos y canales desde el panel.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que "cerrada" signifique de verdad que NADIE escribe, incluido el
//     administrador del grupo. Si el rol pudiera saltarselo, el cierre no
//     serviria para nada: quien causa el problema suele ser el dueno.
//  2. Que se pueda LEER lo cerrado. El historial ya esta en los telefonos;
//     bloquear la lectura no borraria nada y solo estorbaria.
//  3. Que el nivel importe y que cerrar deje motivo, aviso y bitacora.
//  4. Que una directa NO se cierre desde el panel: para eso esta la suspension.
import { execSync } from 'node:child_process';

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
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  return { s: r.status, b: txt ? JSON.parse(txt) : null };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);

const sembrar = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

const jefe = await reg('ga');
sembrar(jefe.user, 80);
const mod = await reg('gb');
sembrar(mod.user, 50);
const duenio = await reg('gc');
const socio = await reg('gd');

// Un grupo con dos personas y un mensaje, para que haya algo que cerrar.
let r = await post('/v1/conversaciones/grupo', duenio.t, {
  nombre: 'Grupo a cerrar ' + S, usernames: [socio.user],
});
ck('se crea el grupo', r.s === 200, JSON.stringify(r.b).slice(0, 140));
const GRUPO = r.b.id;

const sobre = () => ({ mensajeId: crypto.randomUUID(), conversacionId: GRUPO });

console.log('\n=== quien puede ver y cerrar ===');
r = await get('/v1/panel/conversaciones', duenio.t);
ck('un usuario cualquiera no ve las conversaciones de la plataforma', r.s === 404, String(r.s));

r = await get('/v1/panel/conversaciones', mod.t);
ck('un moderador (50) tampoco', r.s === 404, String(r.s));

r = await get(`/v1/panel/conversaciones?q=cerrar`, jefe.t);
ck('un administrador si', r.s === 200, String(r.s));
const fila = (r.b.conversaciones || []).find((x) => x.id === GRUPO);
ck('con el grupo y sus numeros', fila && fila.miembros === 2, JSON.stringify(fila));
ck('y quien lo creo', fila?.creador === duenio.user, fila?.creador);
ck('sin un solo mensaje dentro: el panel no lee contenido',
   fila !== undefined && !('mensajes_texto' in fila) && !('ultimo' in fila));

console.log('\n=== cerrar ===');
r = await post(`/v1/panel/conversaciones/${GRUPO}/cerrar`, jefe.t, { motivo: '' });
ck('cerrar sin motivo se rechaza', r.s === 400, String(r.s));

r = await post(`/v1/panel/conversaciones/${GRUPO}/cerrar`, mod.t, { motivo: 'x' });
ck('un moderador no puede cerrar', r.s === 404, String(r.s));

r = await post(`/v1/panel/conversaciones/${GRUPO}/cerrar`, jefe.t, {
  motivo: 'Uso para coordinar spam.',
});
ck('el administrador cierra', r.s === 204, String(r.s));

console.log('\n=== cerrada = nadie escribe, incluido el dueno ===');
r = await post('/v1/mensajes', duenio.t, sobre());
ck('el DUENO del grupo tampoco puede escribir', r.s === 403, String(r.s));
ck('y se le dice que fue la plataforma, no que "no tiene permiso"',
   (r.b?.motivo || '').toLowerCase().includes('cerrada'), r.b?.motivo);

r = await post('/v1/mensajes', socio.t, sobre());
ck('un miembro tampoco', r.s === 403, String(r.s));

const cfgPrevia = await get(`/v1/conversaciones/${GRUPO}/config`, duenio.t);
r = await put(`/v1/conversaciones/${GRUPO}/config`, duenio.t, {
  ...cfgPrevia.b, descripcion: 'cambiado con el grupo cerrado',
});
ck('ni renombrarlo: el cierre gana sobre el rol de administrador del grupo',
   r.s === 403, String(r.s));

console.log('\n=== pero se puede LEER ===');
r = await get('/v1/conversaciones', duenio.t);
ck('el grupo sigue en la lista de conversaciones',
   (r.b || []).some((x) => x.id === GRUPO));

r = await get(`/v1/conversaciones/${GRUPO}/miembros`, duenio.t);
ck('y se pueden ver sus miembros: leer no esta bloqueado', r.s === 200, String(r.s));

console.log('\n=== queda anotado y avisado ===');
r = await get('/v1/panel/bitacora?q=conversacion.cerrada', jefe.t);
ck('el cierre esta en la bitacora',
   (r.b.lineas || []).some((l) => l.accion === 'conversacion.cerrada' && l.actor === jefe.user),
   JSON.stringify((r.b.lineas || [])[0]));

r = await get('/v1/panel/conversaciones?cerradas=true', jefe.t);
const cerrada = (r.b.conversaciones || []).find((x) => x.id === GRUPO);
ck('aparece en el filtro de cerradas', !!cerrada);
ck('con el motivo', (cerrada?.cierreMotivo || '').includes('spam'), cerrada?.cierreMotivo);
ck('y con quien la cerro', cerrada?.cerradaPor === jefe.user, cerrada?.cerradaPor);

console.log('\n=== reabrir ===');
r = await post(`/v1/panel/conversaciones/${GRUPO}/reabrir`, jefe.t);
ck('se reabre', r.s === 204, String(r.s));

r = await post('/v1/mensajes', duenio.t, sobre());
ck('y se puede volver a escribir', r.s === 200 || r.s === 202, String(r.s));

r = await post(`/v1/panel/conversaciones/${GRUPO}/reabrir`, jefe.t);
ck('reabrir lo que no estaba cerrado se rechaza', r.s === 409, String(r.s));

console.log('\n=== una directa no se cierra desde el panel ===');
r = await post('/v1/conversaciones/directa', duenio.t, { usernameDestino: socio.user });
const DIRECTA = r.b.id;
r = await post(`/v1/panel/conversaciones/${DIRECTA}/cerrar`, jefe.t, { motivo: 'x y z' });
ck('se rechaza y se dice que la herramienta es la suspension',
   r.s === 400 && (r.b?.motivo || '').toLowerCase().includes('suspende'), JSON.stringify(r.b));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
