// El directorio de Usuarios (pestaña Social): solo quien QUISO aparecer.
//
// Lo que se comprueba:
//  - nace apagado: nadie aparece sin haber marcado la casilla (V44);
//  - al marcarla aparece, y al desmarcarla desaparece;
//  - no aparecen: uno mismo, quien me bloqueo o bloquee, quien no me deja
//    encontrarlo (priv_busqueda), ni quien pidio eliminar su cuenta;
//  - se busca por usuario y por nombre, y un `%` no es un comodin;
//  - la paginacion por clave no repite ni salta a nadie;
//  - y la foto sale con las reglas del perfil: estar en la lista no es
//    mostrar lo que la persona reservo para sus conocidos.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
// Prefijo propio de esta corrida: la base de desarrollo acumula usuarios de
// corridas anteriores, y buscar por algo comun terminaria pasando de una
// pagina y volviendo la suite inestable.
const P = 'd' + S;
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
async function call(m, ruta, t, body) {
  const r = await fetch(BASE + ruta, {
    method: m,
    headers: t ? H(t) : { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}
async function reg(u) {
  const r = await call('POST', '/v1/registro', null, {
    username: P + u, password: 'clave-larga-123', etiquetaDispositivo: 't',
    identidadPub: b64('k' + u), hardwareHash: b64('HW-dir-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
  });
  return { t: r.b.token, id: r.b.usuarioId, user: P + u };
}
async function privacidad(p, cambios) {
  const actual = (await call('GET', '/v1/perfil/privacidad', p.t)).b;
  return call('PUT', '/v1/perfil/privacidad', p.t, { ...actual, ...cambios });
}
async function lista(p, q, desde = '') {
  const r = await call('GET', `/v1/directorio?q=${encodeURIComponent(q)}&desde=${encodeURIComponent(desde)}`, p.t);
  return { s: r.s, usuarios: (r.b?.usuarios ?? []).map((u) => u.username), crudo: r.b };
}

const todosDir = (p, desde = '') => lista(p, P, desde);

const ana = await reg('ana');
const beto = await reg('beto');
const carla = await reg('carla');
const dani = await reg('dani');
const eva = await reg('eva');

console.log('\n=== nace apagado ===');
let p = await call('GET', '/v1/perfil/privacidad', beto.t);
ck('la privacidad trae el campo, en false', p.b?.directorio === false, JSON.stringify(p.b));
let l = await todosDir(ana);
ck('nadie aparece sin haberlo pedido', l.s === 200 && l.usuarios.length === 0, JSON.stringify(l.usuarios));

console.log('\n=== al marcarlo aparece ===');
let r = await privacidad(beto, { directorio: true });
ck('se guarda', r.s === 200 && r.b?.directorio === true, JSON.stringify(r.b));
p = await call('GET', '/v1/perfil/privacidad', beto.t);
ck('y se lee de vuelta', p.b?.directorio === true);
l = await todosDir(ana);
ck('ana ve a beto', l.usuarios.includes(beto.user), JSON.stringify(l.usuarios));
ck('cambiar otro ajuste no lo apaga', (await privacidad(beto, { foto: 'todos' })).b?.directorio === true);

console.log('\n=== uno mismo no ===');
await privacidad(ana, { directorio: true });
l = await todosDir(ana);
ck('ana no se ve a si misma', !l.usuarios.includes(ana.user), JSON.stringify(l.usuarios));
l = await todosDir(beto);
ck('pero beto si la ve', l.usuarios.includes(ana.user), JSON.stringify(l.usuarios));

console.log('\n=== bloqueos, en los dos sentidos ===');
await privacidad(carla, { directorio: true });
ck('carla aparece antes del bloqueo', (await todosDir(ana)).usuarios.includes(carla.user));
r = await call('POST', `/v1/bloqueos/${ana.user}`, carla.t);
ck('carla bloquea a ana', r.s < 300, String(r.s));
ck('ana ya no ve a quien la bloqueo', !(await todosDir(ana)).usuarios.includes(carla.user));
ck('y carla tampoco ve a quien bloqueo', !(await todosDir(carla)).usuarios.includes(ana.user));
ck('beto sigue viendo a carla', (await todosDir(beto)).usuarios.includes(carla.user));

console.log('\n=== quien no deja que lo encuentren ===');
await privacidad(dani, { directorio: true, busqueda: 'nadie' });
ck('dani en la lista pero sin dejarse buscar: no aparece', !(await todosDir(ana)).usuarios.includes(dani.user));
ck('coherente con el perfil, que tambien lo niega',
   (await call('GET', `/v1/usuarios/${dani.user}`, ana.t)).s === 404);
await privacidad(dani, { busqueda: 'todos' });
ck('al abrir la busqueda aparece', (await todosDir(ana)).usuarios.includes(dani.user));

console.log('\n=== buscar ===');
ck('por prefijo del usuario', (await lista(ana, P + 'beto')).usuarios.includes(beto.user));
ck('con arroba delante tambien', (await lista(ana, '@' + P + 'beto')).usuarios.includes(beto.user));
r = await call('PUT', '/v1/perfil', beto.t, { nombreMostrado: 'Bartolome ' + S, estadoTexto: '' });
ck('beto se pone un nombre', r.s === 200, String(r.s));
ck('por el nombre que eligio', (await lista(ana, 'bartolome')).usuarios.includes(beto.user));
ck('un % no es un comodin', (await lista(ana, '%')).usuarios.length === 0);
ck('ni un _', (await lista(ana, '_')).usuarios.length === 0);

console.log('\n=== la foto con las reglas del perfil ===');
await privacidad(beto, { foto: 'nadie' });
l = await lista(ana, P + 'beto');
const fila = l.crudo?.usuarios?.find((u) => u.username === beto.user);
const perfil = (await call('GET', `/v1/usuarios/${beto.user}`, ana.t)).b;
ck('el directorio dice lo mismo que el perfil sobre la foto',
   JSON.stringify(fila?.avatarVersion ?? null) === JSON.stringify(perfil?.avatarVersion ?? null),
   `${fila?.avatarVersion} vs ${perfil?.avatarVersion}`);

console.log('\n=== quien pidio irse ===');
await privacidad(eva, { directorio: true });
ck('eva aparece', (await todosDir(ana)).usuarios.includes(eva.user));
r = await call('POST', '/v1/cuenta/eliminar', eva.t, { password: 'clave-larga-123' });
ck('eva pide eliminar su cuenta', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b));
ck('y deja de aparecer en seguida', !(await todosDir(ana)).usuarios.includes(eva.user));

console.log('\n=== paginacion por clave ===');
const todos = (await todosDir(beto)).usuarios;
ck('hay al menos dos para paginar', todos.length >= 2, JSON.stringify(todos));
const desde = todos[0];
const resto = await todosDir(beto, desde);
ck('desde un usuario, sigue despues de el', resto.usuarios.every((u) => u > desde), JSON.stringify(resto.usuarios));
ck('sin repetir ni saltar', JSON.stringify([desde, ...resto.usuarios]) === JSON.stringify(todos),
   JSON.stringify([desde, ...resto.usuarios]) + ' vs ' + JSON.stringify(todos));

console.log('\n=== al desmarcarlo desaparece ===');
await privacidad(beto, { directorio: false });
ck('beto ya no esta', !(await todosDir(ana)).usuarios.includes(beto.user));

console.log('\n=== sin sesion ===');
r = await call('GET', '/v1/directorio', null);
ck('sin token es 401', r.s === 401, String(r.s));

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
