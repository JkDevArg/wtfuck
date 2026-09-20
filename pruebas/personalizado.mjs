// L.1 · El nivel `personalizado` de privacidad.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que `solo` con la lista VACIA no muestre el dato a nadie. Es el defecto
//     seguro: ante la falta de datos, ocultar.
//  2. Que las dos direcciones funcionen: lista negra y lista blanca.
//  3. Que no se pueda personalizar `escribe`, `lectura` ni `escribiendo`, que
//     son los ajustes donde una lista rompe el modelo.
//  4. Que guardar reemplace la lista entera y no acumule.
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
const get = (r, t) => call('GET', r, t);
const put = (r, t, b) => call('PUT', r, t, b);

const ana = await reg('pa');
const amiga = await reg('pb');
const molesta = await reg('pc');
const extrana = await reg('pd');

const perfil = (quien, de) => get(`/v1/usuarios/${de.user}`, quien.t);
const priv = async (quien) => (await get('/v1/perfil/privacidad', quien.t)).b;

await put('/v1/perfil', ana.t, { nombreMostrado: 'Ana Real', estadoTexto: 'mi bio' });

console.log('\n=== la pantalla recibe todos los ajustes, incluso vacios ===');
// Se comprueba CUALES y no CUANTOS. La version anterior fijaba el numero 7
// y, al agregarse `historias`, fallaba diciendo "8" -un numero que no dice
// cual sobra ni cual falta-. Con la lista, un ajuste que se olvide de
// aparecer aqui se nombra solo.
const ESPERADOS = [
  'foto', 'estado', 'nombre', 'grupos', 'llamadas', 'busqueda', 'ultima_vez',
  'historias',
].sort();
let r = await get('/v1/perfil/privacidad/excepciones', ana.t);
const llegaron = (r.b.ajustes || []).map((a) => a.ajuste).sort();
ck('vienen todos los personalizables y ninguno de mas',
   JSON.stringify(llegaron) === JSON.stringify(ESPERADOS),
   `llegaron ${JSON.stringify(llegaron)}`);
ck('con modo "salvo" por defecto',
   (r.b.ajustes || []).every((a) => a.modo === 'salvo'), JSON.stringify(r.b.ajustes?.[0]));
ck('y con la lista vacia', (r.b.ajustes || []).every((a) => a.usernames.length === 0));

console.log('\n=== lista negra: todos MENOS una persona ===');
const P = await priv(ana);
r = await put('/v1/perfil/privacidad', ana.t, { ...P, nombre: 'personalizado' });
ck('el nivel personalizado se acepta', r.s === 200, String(r.s));

r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'nombre', modo: 'salvo', usernames: [molesta.user],
});
ck('se guarda la excepcion', r.s === 200, String(r.s));

r = await perfil(extrana, ana);
ck('una extrana SI ve el nombre: esta fuera de la lista negra',
   r.b.nombreMostrado === 'Ana Real', r.b.nombreMostrado);

r = await perfil(molesta, ana);
ck('quien esta en la lista NO lo ve', r.b.nombreMostrado === '', JSON.stringify(r.b.nombreMostrado));
ck('pero sigue viendo el username: no es un bloqueo', r.b.username === ana.user);

console.log('\n=== lista blanca: SOLO una persona ===');
r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'nombre', modo: 'solo', usernames: [amiga.user],
});
ck('se cambia a lista blanca', r.s === 200, String(r.s));

r = await perfil(amiga, ana);
ck('quien esta en la lista lo ve', r.b.nombreMostrado === 'Ana Real', r.b.nombreMostrado);

r = await perfil(extrana, ana);
ck('y quien no esta, no', r.b.nombreMostrado === '', JSON.stringify(r.b.nombreMostrado));

r = await perfil(molesta, ana);
ck('la que estaba en la negra tampoco: la lista se REEMPLAZO, no se acumulo',
   r.b.nombreMostrado === '', JSON.stringify(r.b.nombreMostrado));

r = await get('/v1/perfil/privacidad/excepciones', ana.t);
const nombre = (r.b.ajustes || []).find((a) => a.ajuste === 'nombre');
ck('y la lista guardada tiene una sola persona',
   nombre?.usernames.length === 1 && nombre.usernames[0] === amiga.user,
   JSON.stringify(nombre));

console.log('\n=== el defecto seguro: lista blanca VACIA no muestra a nadie ===');
r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'nombre', modo: 'solo', usernames: [],
});
ck('se guarda vacia', r.s === 200, String(r.s));

r = await perfil(amiga, ana);
ck('ni quien estaba antes lo ve', r.b.nombreMostrado === '', JSON.stringify(r.b.nombreMostrado));
r = await perfil(extrana, ana);
ck('ni nadie mas', r.b.nombreMostrado === '', JSON.stringify(r.b.nombreMostrado));
r = await perfil(ana, ana);
ck('pero yo sigo viendo mi propio nombre', r.b.nombreMostrado === 'Ana Real');

console.log('\n=== funciona en los otros ajustes ===');
r = await put('/v1/perfil/privacidad', ana.t, { ...P, nombre: 'todos', busqueda: 'personalizado' });
await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'busqueda', modo: 'solo', usernames: [amiga.user],
});
r = await perfil(amiga, ana);
ck('con busqueda en lista blanca, la amiga la encuentra', r.s === 200, String(r.s));
r = await perfil(extrana, ana);
ck('y una extrana recibe 404, como si no existiera', r.s === 404, String(r.s));

console.log('\n=== lo que NO se puede personalizar ===');
for (const ajuste of ['escribe', 'lectura', 'escribiendo', 'inventado']) {
  r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
    ajuste: ajuste, modo: 'solo', usernames: [amiga.user],
  });
  ck(`"${ajuste}" no admite excepciones`, r.s === 400, String(r.s));
}

r = await put('/v1/perfil/privacidad', ana.t, { ...P, escribe: 'personalizado' });
ck('y `escribe` no acepta el nivel personalizado', r.s === 400, String(r.s));

console.log('\n=== validacion ===');
r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'foto', modo: 'a veces', usernames: [],
});
ck('un modo inventado se rechaza', r.s === 400, String(r.s));

r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'foto', modo: 'salvo', usernames: Array(250).fill(amiga.user),
});
ck('una lista de 250 nombres se rechaza', r.s === 400, String(r.s));

r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'foto', modo: 'salvo', usernames: [amiga.user, 'fantasma_que_no_existe'],
});
ck('un nombre inexistente se ignora en silencio y no pierde los buenos',
   r.s === 200 &&
   (r.b.ajustes || []).find((a) => a.ajuste === 'foto')?.usernames.length === 1,
   JSON.stringify((r.b.ajustes || []).find((a) => a.ajuste === 'foto')));

r = await put('/v1/perfil/privacidad/excepciones', ana.t, {
  ajuste: 'foto', modo: 'salvo', usernames: [ana.user],
});
ck('y ponerse a uno mismo en la lista no hace nada',
   (r.b.ajustes || []).find((a) => a.ajuste === 'foto')?.usernames.length === 0,
   JSON.stringify((r.b.ajustes || []).find((a) => a.ajuste === 'foto')));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
