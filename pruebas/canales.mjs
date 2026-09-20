// Modulo F: canales.
//
// Lo que se comprueba: que publicar y leer sean papeles DISTINTOS, que los
// interruptores del canal manden sobre los permisos del rol, y que un canal
// privado no filtre nada por las rutas publicas.
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
const uuid = () => crypto.randomUUID();

// F.7: el dueno de la PLATAFORMA. No es el dueno del canal: son dos cosas
// distintas a proposito -crear un canal no da ningun poder sobre la
// plataforma- y el nombre parecido es justamente lo que conviene no confundir.
const sembrar = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);

const dueno = await reg('fa');
const lector = await reg('fb');
const otro = await reg('fc');
const jefe = await reg('fz');
sembrar(jefe.user, 100);
// Un administrador, para comprobar que 80 NO alcanza.
const admin = await reg('fy');
sembrar(admin.user, 80);

const ALIAS = 'auditoria_' + S;

console.log('\n=== crear ===');
let r = await post('/v1/canales', dueno.t, {
  nombre: 'Auditoria EducaD', alias: ALIAS, publico: true,
  descripcion: 'Avisos del equipo de auditoria', comentarios: false,
});
ck('se crea un canal publico', r.s === 200, JSON.stringify(r.b).slice(0, 160));
const CANAL = r.b.conversacionId;
ck('quien lo crea puede publicar', r.b.puedoPublicar === true);
ck('y puede gestionarlo', r.b.puedoGestionar === true);
ck('un canal publico se declara NO cifrado de extremo a extremo', r.b.cifrado === false,
   'cifrado=' + r.b.cifrado);
ck('arranca con un suscriptor: su dueno', r.b.suscriptores === 1, String(r.b.suscriptores));

r = await post('/v1/canales', dueno.t, { nombre: 'Sin alias', publico: true });
ck('un canal publico SIN alias se rechaza', r.s === 400, String(r.s));

r = await post('/v1/canales', dueno.t, { nombre: 'Mal alias', alias: 'ab', publico: true });
ck('un alias de menos de 4 caracteres se rechaza', r.s === 400, String(r.s));

r = await post('/v1/canales', otro.t, { nombre: 'Repetido', alias: ALIAS, publico: true });
ck('un alias ya usado se rechaza', r.s === 409, String(r.s));

r = await post('/v1/canales', dueno.t, { nombre: 'Interno', publico: false });
ck('un canal privado no necesita alias', r.s === 200, String(r.s));
const PRIVADO = r.b.conversacionId;
ck('y SI se declara cifrado', r.b.cifrado === true);


console.log('\n=== F.7: un canal no existe hasta que el dueno lo aprueba ===');
r = await get(`/v1/canales/${CANAL}`, dueno.t);
ck('nace pendiente', r.b.estado === 'pendiente', r.b.estado);

r = await get('/v1/canales/directorio', lector.t);
ck('no esta en el directorio', (r.b.canales || []).every((k) => k.conversacionId !== CANAL));

r = await get(`/v1/canales/buscar?q=${ALIAS}`, lector.t);
ck('no aparece en la busqueda', (r.b.canales || []).length === 0, JSON.stringify(r.b).slice(0, 120));

r = await get(`/v1/canales/alias/${ALIAS}`, lector.t);
ck('ni por su alias exacto: responde como si no existiera', r.s === 404, String(r.s));

r = await post(`/v1/canales/${CANAL}/suscribir`, lector.t);
ck('y nadie se puede suscribir', r.s === 403, String(r.s));

r = await get(`/v1/canales/${CANAL}`, dueno.t);
ck('pero quien lo creo si entra y lo prepara', r.s === 200 && r.b.puedoPublicar === true);

// Quien decide es el propietario de la plataforma, y SOLO el.
r = await get('/v1/panel/canales', lector.t);
ck('un usuario cualquiera no ve la cola de canales', r.s === 404, String(r.s));

r = await get('/v1/panel/canales', admin.t);
ck('un administrador (80) tampoco: la decision es del dueno', r.s === 404, String(r.s));

r = await post(`/v1/panel/canales/${CANAL}`, admin.t, { aprobado: true });
ck('y un administrador no puede aprobar', r.s === 404, String(r.s));

r = await get('/v1/panel/canales', jefe.t);
ck('el dueno si ve la cola', r.s === 200, String(r.s));
const enCola = (r.b.canales || []).find((k) => k.conversacionId === CANAL);
ck('con el canal esperando', !!enCola, JSON.stringify(r.b).slice(0, 160));
ck('y con quien lo creo, para poder decidir', enCola?.creador === dueno.user, enCola?.creador);
ck('y si es publico o privado, que es lo que cambia la decision', enCola?.publico === true);

r = await post(`/v1/panel/canales/${CANAL}`, jefe.t, { aprobado: false });
ck('rechazar sin motivo se rechaza: seria un muro sin salida', r.s === 400, String(r.s));

r = await post(`/v1/panel/canales/${CANAL}`, jefe.t, { aprobado: true });
ck('el dueno aprueba', r.s === 204, String(r.s));

r = await get(`/v1/canales/${CANAL}`, dueno.t);
ck('y el canal queda aprobado', r.b.estado === 'aprobado', r.b.estado);

r = await get('/v1/canales/directorio', lector.t);
ck('ahora SI esta en el directorio, sin buscar nada',
   (r.b.canales || []).some((k) => k.conversacionId === CANAL));

r = await post(`/v1/panel/canales/${CANAL}`, jefe.t, { aprobado: true });
ck('revisar dos veces el mismo canal se rechaza', r.s === 409, String(r.s));

// Uno rechazado, para ver el otro lado.
r = await post('/v1/canales', otro.t, {
  nombre: 'Canal dudoso', alias: 'dudoso_' + S, publico: true, descripcion: 'x',
});
const RECHAZADO = r.b.conversacionId;
r = await post(`/v1/panel/canales/${RECHAZADO}`, jefe.t, {
  aprobado: false, motivo: 'No cumple las normas de la plataforma.',
});
ck('rechazar con motivo funciona', r.s === 204, String(r.s));

r = await get(`/v1/canales/${RECHAZADO}`, otro.t);
ck('quien lo creo ve el rechazo', r.b.estado === 'rechazado', r.b.estado);
ck('y ve por que, que es lo unico que le permite corregir',
   (r.b.motivoRechazo || '').includes('normas'), r.b.motivoRechazo);

r = await get('/v1/canales/directorio', lector.t);
ck('un canal rechazado no se lista',
   (r.b.canales || []).every((k) => k.conversacionId !== RECHAZADO));

r = await post(`/v1/canales/${RECHAZADO}/suscribir`, lector.t);
ck('ni admite suscriptores', r.s === 403, String(r.s));

// H.3: aprobar no es una puerta de un solo sentido.
r = await get('/v1/panel/canales?estado=aprobado', jefe.t);
ck('el dueno ve tambien lo que ya esta publicado',
   (r.b.canales || []).some((k) => k.conversacionId === CANAL));

r = await post(`/v1/panel/canales/${CANAL}`, jefe.t, {
  aprobado: false, motivo: 'Se descarrilo despues de aprobarse.',
});
ck('y puede retirar un canal aprobado', r.s === 204, String(r.s));

r = await get('/v1/canales/directorio', lector.t);
ck('retirado, sale del directorio',
   (r.b.canales || []).every((k) => k.conversacionId !== CANAL));

r = await post(`/v1/canales/${CANAL}/suscribir`, otro.t);
ck('y no admite suscriptores nuevos', r.s === 403, String(r.s));

// Y se puede volver a aprobar: quien corrigio lo que se le pidio no queda
// condenado para siempre.
r = await post(`/v1/panel/canales/${CANAL}`, jefe.t, { aprobado: true });
ck('un canal retirado se puede volver a aprobar', r.s === 204, String(r.s));

// El privado tambien pasa por la cola: la regla es "los canales los aprueba
// el dueno", sin excepciones que dependan de un booleano que ademas se puede
// cambiar despues.
r = await post(`/v1/panel/canales/${PRIVADO}`, jefe.t, { aprobado: true });
ck('el canal privado tambien se aprueba', r.s === 204, String(r.s));

// Un canal tiene nombre propio: en la lista de conversaciones NO puede
// aparecer como si fuera una directa sin nadie al otro lado.
r = await get('/v1/conversaciones', dueno.t);
const mio = (r.b || []).find((x) => x.id === CANAL);
ck('un canal se nombra con su nombre en la lista de conversaciones',
   mio?.nombre === 'Auditoria EducaD', JSON.stringify(mio?.nombre));
ck('y se declara como canal', mio?.tipo === 'canal', mio?.tipo);

console.log('\n=== descubrir ===');
r = await get(`/v1/canales/alias/${ALIAS}`, lector.t);
ck('se encuentra por alias sin estar suscrito', r.s === 200 && r.b.conversacionId === CANAL);
ck('y se ve que todavia no esta suscrito', r.b.suscrito === false);
ck('un no suscrito NO puede publicar', r.b.puedoPublicar === false);

// Se busca por el sufijo de ESTA corrida y no por `auditoria` a secas.
//
// La busqueda devuelve los 30 que mas suscriptores tienen, y cada corrida de
// esta suite deja un `auditoria_<sufijo>` mas en la base de desarrollo. Al
// pasar de 30, el canal recien creado -con un suscriptor- quedaba fuera del
// tope y la prueba fallaba por la CANTIDAD DE CANALES ACUMULADOS y no por
// nada del servidor. Llego a 32 y se rompio entre dos corridas del mismo dia.
//
// El sufijo es unico por corrida, asi que esto prueba el mismo camino -LIKE
// sobre alias, nombre y descripcion- sin depender de lo que haya en la base.
//
// El tope sin cursor se queda como esta, y es distinto del caso de la cola de
// moderacion: un directorio que devuelve los 30 canales mas seguidos y espera
// que afines la busqueda es una decision razonable, mientras que una cola de
// denuncias donde no se puede llegar a la numero 31 deja trabajo sin hacer.
r = await get(`/v1/canales/buscar?q=${S}`, lector.t);
ck('la busqueda encuentra el canal', (r.b?.canales || []).some((k) => k.alias === ALIAS),
   JSON.stringify(r.b).slice(0, 140));

// Y sigue encontrandolo por una palabra del nombre, que es el otro camino.
r = await get(`/v1/canales/buscar?q=auditoria`, lector.t);
ck('y la busqueda por palabra devuelve canales publicos aprobados',
   (r.b?.canales || []).length > 0 && (r.b.canales).every((k) => !!k.alias),
   JSON.stringify(r.b).slice(0, 140));

r = await get(`/v1/canales/alias/noexiste${S}`, lector.t);
ck('un alias inexistente da 404', r.s === 404, String(r.s));

// El canal privado no tiene alias, asi que no hay por donde encontrarlo.
r = await get('/v1/canales/buscar?q=interno', lector.t);
ck('un canal PRIVADO no aparece en la busqueda',
   !(r.b?.canales || []).some((k) => k.conversacionId === PRIVADO), JSON.stringify(r.b));

console.log('\n=== suscribirse ===');
r = await post(`/v1/canales/${CANAL}/suscribir`, lector.t);
ck('cualquiera se suscribe a un canal publico', r.s === 200, String(r.s));
ck('ahora hay dos suscriptores', r.b.suscriptores === 2, String(r.b.suscriptores));
ck('un suscriptor NO puede publicar', r.b.puedoPublicar === false);
ck('ni gestionar el canal', r.b.puedoGestionar === false);
ck('y su rol es suscriptor', r.b.miRol === 'suscriptor', r.b.miRol);

r = await post(`/v1/canales/${PRIVADO}/suscribir`, lector.t);
ck('a un canal privado NO se puede entrar solo', r.s === 403, String(r.s));

console.log('\n=== publicar ===');
const p1 = uuid();
r = await post('/v1/mensajes', dueno.t, { mensajeId: p1, conversacionId: CANAL });
ck('el dueno registra una publicacion', r.s === 200, JSON.stringify(r.b).slice(0, 120));
r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t, { mensajeId: p1, cuerpo: 'Primera publicacion' });
ck('y se guarda su contenido', r.s === 204, String(r.s));

const p2 = uuid();
r = await post('/v1/mensajes', lector.t, { mensajeId: p2, conversacionId: CANAL });
ck('un suscriptor NO puede publicar (403)', r.s === 403, String(r.s) + ' ' + JSON.stringify(r.b));

r = await post(`/v1/canales/${CANAL}/publicaciones`, lector.t, { mensajeId: p1, cuerpo: 'me cuelgo' });
ck('ni colgar contenido de una publicacion ajena', r.s === 403 || r.s === 404, String(r.s));

console.log('\n=== historial ===');
r = await get(`/v1/canales/${CANAL}/publicaciones`, lector.t);
ck('un suscriptor lee el historial', r.s === 200 && r.b.length === 1, JSON.stringify(r.b).slice(0, 140));
ck('con el cuerpo de la publicacion', r.b[0]?.cuerpo === 'Primera publicacion', r.b[0]?.cuerpo);
ck('y quien la escribio', r.b[0]?.autor === dueno.user, r.b[0]?.autor);

// Un canal PUBLICO se lee sin estar suscrito: eso es lo que significa
// publico. Si hubiera que seguirlo para saber de que trata, descubrirlo no
// serviria de nada.
r = await get(`/v1/canales/${CANAL}/publicaciones`, otro.t);
ck('quien NO esta suscrito tambien lee un canal publico', r.s === 200 && r.b.length === 1,
   String(r.s) + ' ' + JSON.stringify(r.b).slice(0, 100));

r = await get(`/v1/canales/${PRIVADO}/publicaciones`, dueno.t);
ck('un canal privado devuelve historial VACIO: el servidor no lo tiene',
   r.s === 200 && r.b.length === 0, JSON.stringify(r.b));

const pPriv = uuid();
await post('/v1/mensajes', dueno.t, { mensajeId: pPriv, conversacionId: PRIVADO });
r = await post(`/v1/canales/${PRIVADO}/publicaciones`, dueno.t, { mensajeId: pPriv, cuerpo: 'secreto' });
ck('y rechaza que se le guarde contenido en claro', r.s === 409, String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== comentarios: el interruptor manda ===');
const c1 = uuid();
r = await post('/v1/mensajes', lector.t, { mensajeId: c1, conversacionId: CANAL, respondeA: p1 });
ck('con los comentarios apagados, comentar se rechaza', r.s === 403, String(r.s) + ' ' + JSON.stringify(r.b));

r = await put(`/v1/canales/${CANAL}`, dueno.t, {
  nombre: 'Auditoria EducaD', alias: ALIAS, publico: true,
  descripcion: 'Avisos del equipo de auditoria', comentarios: true, reacciones: true,
});
ck('el dueno enciende los comentarios', r.s === 200 && r.b.comentarios === true, JSON.stringify(r.b).slice(0, 120));

const c2 = uuid();
r = await post('/v1/mensajes', lector.t, { mensajeId: c2, conversacionId: CANAL, respondeA: p1 });
ck('ahora el suscriptor SI puede comentar', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b));

r = await get(`/v1/canales/${CANAL}/publicaciones`, lector.t);
ck('la publicacion cuenta su comentario', r.b[0]?.comentarios === 1, String(r.b[0]?.comentarios));

console.log('\n=== reacciones: tambien manda el interruptor ===');
r = await post('/v1/mensajes/reaccion', lector.t, { mensajeId: p1, emoji: '👍', poner: true });
ck('con las reacciones encendidas se puede reaccionar', r.s === 200, String(r.s));

r = await put(`/v1/canales/${CANAL}`, dueno.t, {
  nombre: 'Auditoria EducaD', alias: ALIAS, publico: true,
  descripcion: 'x', comentarios: true, reacciones: false,
});
ck('se apagan las reacciones', r.s === 200 && r.b.reacciones === false);
r = await post('/v1/mensajes/reaccion', lector.t, { mensajeId: p1, emoji: '🎉', poner: true });
ck('y entonces reaccionar se rechaza', r.s === 403, String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== configuracion: solo quien puede ===');
r = await put(`/v1/canales/${CANAL}`, lector.t, {
  nombre: 'Secuestrado', alias: ALIAS, publico: true, descripcion: '', comentarios: true, reacciones: true,
});
ck('un suscriptor NO puede configurar el canal', r.s === 403, String(r.s));

console.log('\n=== estadisticas ===');
r = await get(`/v1/canales/${CANAL}/estadisticas`, dueno.t);
ck('el dueno ve las estadisticas', r.s === 200, JSON.stringify(r.b));
ck('cuenta suscriptores', r.b?.suscriptores === 2, String(r.b?.suscriptores));
ck('cuenta publicaciones', r.b?.publicaciones === 1, String(r.b?.publicaciones));
ck('cuenta comentarios aparte de las publicaciones', r.b?.comentarios === 1, String(r.b?.comentarios));
ck('y cuenta reacciones', r.b?.reacciones === 1, String(r.b?.reacciones));

r = await get(`/v1/canales/${CANAL}/estadisticas`, lector.t);
ck('un suscriptor NO ve las estadisticas', r.s === 403, String(r.s));

console.log('\n=== darse de baja ===');
r = await post(`/v1/canales/${CANAL}/desuscribir`, lector.t);
ck('se puede dar de baja', r.s === 204, String(r.s));
r = await get(`/v1/canales/${CANAL}/publicaciones`, lector.t);
ck('pero sigue pudiendo leerlo: es publico', r.s === 200, String(r.s));
r = await get(`/v1/canales/alias/${ALIAS}`, lector.t);
ck('pero sigue encontrando el canal por su alias', r.s === 200 && r.b.suscrito === false);

const anon = await fetch(BASE + `/v1/canales/buscar?q=auditoria`);
ck('sin sesion no se busca nada', anon.status === 401, String(anon.status));

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
