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

// El directorio esta ORDENADO POR SUSCRIPTORES y viene paginado. Un canal
// recien creado tiene cero, asi que buscarlo en la primera pagina solo
// funciona mientras la base este casi vacia: con 786 canales acumulados de
// otras corridas, este canal cae fuera y la prueba se pone roja sin que nada
// se haya roto.
//
// Es el mismo defecto que ya se corrigio una vez en la BUSQUEDA de canales
// (32 acumulados contra un LIMIT 30). Se afirma lo que de verdad importa
// —que un canal aprobado es LISTABLE— y no en que puesto sale.
r = await get('/v1/canales/directorio?limite=200', lector.t);
const enDirectorio = (r.b.canales || []).some((k) => k.conversacionId === CANAL);
const porBusqueda = ((await get(`/v1/canales/buscar?q=auditoria_${S}`, lector.t)).b.canales || [])
  .some((k) => k.conversacionId === CANAL);
ck('ahora SI se puede encontrar: esta listado', enDirectorio || porBusqueda,
   `directorio=${enDirectorio} busqueda=${porBusqueda}`);

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

// El contador cuenta los comentarios QUE SE PUEDEN LEER, no las filas de
// metadatos, y aqui solo se registro el metadato: el cuerpo se guarda en la
// seccion siguiente. Asi que todavia va en 0, y eso es lo correcto.
//
// Antes contaba metadatos y esta misma linea afirmaba `=== 1`. Sonaba mejor y
// era el origen del defecto que reporto el usuario: la tarjeta decia
// "1 comentario" y la hoja de comentarios estaba vacia. Las dos tenian razon.
r = await get(`/v1/canales/${CANAL}/publicaciones`, lector.t);
ck('el metadato solo NO cuenta como comentario: no hay nada que leer',
   r.b[0]?.comentarios === 0, String(r.b[0]?.comentarios));

// ============================================================
//  Modulo AA: el CUERPO del comentario
// ============================================================
//
// Esta seccion existe por un defecto que la suite no veia, y conviene entender
// por que no lo veia antes de leerla.
//
// Arriba se comprueba que el suscriptor puede comentar y que **la publicacion
// cuenta su comentario**. Las dos cosas pasaban, y las dos seguian pasando con
// el defecto puesto: el contador cuenta filas de `mensaje_meta`, o sea
// metadatos, y el metadato se registraba bien.
//
// Lo que no se comprobaba era lo unico que le importa a quien lee: **que el
// texto del comentario se pueda recuperar**. No se podia. Un canal publico no
// reparte sobres -es lo que le permite escalar-, asi que el comentario, que se
// mandaba como mensaje cifrado, no tenia a quien entregarse: quedaba el
// metadato, subia el contador y el cuerpo se iba al vacio.
//
// Medido en la base antes de arreglarlo: cada comentario de canal tenia cero
// sobres y cero cuerpo guardado. En la app se veia como "dice 1 comentario y
// no hay ningun comentario".
//
// La leccion es la de siempre: **una prueba que afirma el contador no afirma
// el contenido.** Ahora se comprueba el viaje completo.

console.log('\n=== comentarios: el cuerpo, ida y vuelta ===');

r = await post(`/v1/canales/${CANAL}/comentarios`, lector.t,
               { mensajeId: c2, publicacionId: p1, cuerpo: 'Primer comentario' });
ck('se guarda el cuerpo del comentario', r.s === 204, String(r.s) + ' ' + JSON.stringify(r.b));

// En una variable propia: mas abajo se consulta el muro, y reusar `r` para
// las dos cosas se lleva puesta la respuesta que todavia hace falta leer.
const leidos = await get(`/v1/canales/${CANAL}/publicaciones/${p1}/comentarios`, lector.t);
ck('y se puede volver a leer', leidos.s === 200 && leidos.b.comentarios?.length === 1,
   String(leidos.s) + ' ' + JSON.stringify(leidos.b).slice(0, 160));
ck('con su texto', leidos.b.comentarios?.[0]?.cuerpo === 'Primer comentario',
   leidos.b.comentarios?.[0]?.cuerpo);
ck('y quien lo escribio', leidos.b.comentarios?.[0]?.autor === lector.user,
   leidos.b.comentarios?.[0]?.autor);
ck('marcado como mio para quien lo escribio', leidos.b.comentarios?.[0]?.mio === true,
   String(leidos.b.comentarios?.[0]?.mio));

r = await get(`/v1/canales/${CANAL}/publicaciones`, lector.t);
ck('y AHORA la publicacion lo cuenta', r.b[0]?.comentarios === 1,
   String(r.b[0]?.comentarios));

r = await get(`/v1/canales/${CANAL}/publicaciones/${p1}/comentarios`, dueno.t);
ck('y NO como mio para otra persona', r.b.comentarios?.[0]?.mio === false,
   String(r.b.comentarios?.[0]?.mio));

// Un canal publico se lee sin estar suscrito, y sus comentarios tambien: si
// se pudieran ver las publicaciones pero no las respuestas, la mitad de la
// conversacion quedaria detras de una suscripcion.
r = await get(`/v1/canales/${CANAL}/publicaciones/${p1}/comentarios`, otro.t);
ck('quien NO esta suscrito tambien lee los comentarios de un canal publico',
   r.s === 200 && r.b.comentarios?.length === 1, String(r.s));

console.log('\n=== comentarios: lo que NO se puede colgar ===');

// El cuerpo se cuelga del metadato PROPIO. Sin esto, cualquiera reescribe el
// comentario de otro.
r = await post(`/v1/canales/${CANAL}/comentarios`, dueno.t,
               { mensajeId: c2, publicacionId: p1, cuerpo: 'te edito el comentario' });
ck('no se puede colgar texto del comentario de otra persona', r.s === 404,
   String(r.s) + ' ' + JSON.stringify(r.b));

r = await get(`/v1/canales/${CANAL}/publicaciones/${p1}/comentarios`, lector.t);
ck('y el original queda intacto', r.b.comentarios?.[0]?.cuerpo === 'Primer comentario',
   r.b.comentarios?.[0]?.cuerpo);

const cHuerfano = uuid();
await post('/v1/mensajes', lector.t, { mensajeId: cHuerfano, conversacionId: CANAL, respondeA: p1 });
r = await post(`/v1/canales/${CANAL}/comentarios`, lector.t,
               { mensajeId: cHuerfano, publicacionId: uuid(), cuerpo: 'cuelgo de la nada' });
ck('ni de una publicacion que no existe', r.s === 404, String(r.s));

r = await post(`/v1/canales/${CANAL}/comentarios`, lector.t,
               { mensajeId: cHuerfano, publicacionId: p1, cuerpo: '' });
ck('un comentario vacio se rechaza', r.s === 400, String(r.s));

r = await post(`/v1/canales/${CANAL}/comentarios`, lector.t,
               { mensajeId: cHuerfano, publicacionId: p1, cuerpo: 'x'.repeat(2049) });
ck('y uno de mas de 2048 caracteres tambien', r.s === 400, String(r.s));

r = await post(`/v1/canales/${CANAL}/comentarios`, lector.t,
               { mensajeId: cHuerfano, publicacionId: p1, cuerpo: 'x'.repeat(2048) });
ck('2048 justos entran', r.s === 204, String(r.s));

// Un canal privado no guarda contenido, ni publicaciones ni comentarios. Ahi
// el comentario es un mensaje cifrado y solo lo ve quien reciba el sobre.
const cPriv = uuid();
await post('/v1/mensajes', dueno.t, { mensajeId: cPriv, conversacionId: PRIVADO, respondeA: pPriv });
r = await post(`/v1/canales/${PRIVADO}/comentarios`, dueno.t,
               { mensajeId: cPriv, publicacionId: pPriv, cuerpo: 'secreto' });
ck('un canal privado rechaza guardar el cuerpo de un comentario', r.s === 409,
   String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== las reacciones VIAJAN en el muro ===');
//
// `Publicacion.reacciones` existia en el contrato desde el modulo F y la
// consulta del muro **nunca las leia**: el campo tenia `= emptyList()` por
// defecto, asi que viajaba siempre vacio y la tarjeta del canal no dibujaba
// ninguna. No fallaba nada, y por eso ninguna prueba lo noto: el valor por
// defecto hacia que "no las lei" fuera indistinguible de "no tiene".
//
// Es el mismo error del modulo X con otra cara, esta vez en el servidor.

r = await post('/v1/mensajes/reaccion', dueno.t, { mensajeId: p1, emoji: '🎉', poner: true });
ck('el dueno reacciona a su propia publicacion', r.s === 200, String(r.s));

r = await get(`/v1/canales/${CANAL}/publicaciones`, dueno.t);
const reacs = r.b[0]?.reacciones ?? [];
ck('el muro trae las reacciones de la publicacion', reacs.length >= 1,
   JSON.stringify(reacs));
ck('con su emoji y su recuento', reacs.some(x => x.emoji === '🎉' && x.total === 1),
   JSON.stringify(reacs));
ck('y marcadas como mias para quien reacciono', reacs.find(x => x.emoji === '🎉')?.mia === true,
   JSON.stringify(reacs.find(x => x.emoji === '🎉')));

r = await get(`/v1/canales/${CANAL}/publicaciones`, otro.t);
const reacsOtro = r.b[0]?.reacciones ?? [];
ck('y NO como mias para otra persona',
   reacsOtro.find(x => x.emoji === '🎉')?.mia === false, JSON.stringify(reacsOtro));

// Quitarla la saca del muro, no la deja en cero: una reaccion con total 0
// seria una fila de interfaz sin nadie detras.
r = await post('/v1/mensajes/reaccion', dueno.t, { mensajeId: p1, emoji: '🎉', poner: false });
ck('se quita la reaccion', r.s === 200, String(r.s));
r = await get(`/v1/canales/${CANAL}/publicaciones`, dueno.t);
ck('y desaparece del muro en vez de quedar en cero',
   !(r.b[0]?.reacciones ?? []).some(x => x.emoji === '🎉'),
   JSON.stringify(r.b[0]?.reacciones));

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

// ============================================================
//  Modulo AC: una imagen en la publicacion
// ============================================================
//
// Un canal solo podia publicar TEXTO. Una pagina de anuncios sin imagenes no
// es una pagina de anuncios.
//
// ## Por que la imagen va SIN cifrar, y por que aqui eso es una ventaja
//
// El adjunto normal se cifra en el telefono y **la clave viaja en el sobre**.
// Un canal publico no reparte sobres, asi que no hay donde meterla: quien se
// suscriba manana tendria el archivo y no la llave.
//
// Va en claro, bajo la misma excepcion que el cuerpo. Y eso habilita lo que el
// brief pedia y el cifrado hacia imposible: **el servidor comprueba la firma
// real del archivo**. Es la tercera clase de archivo del sistema -perfil
// validado, adjunto cifrado no, imagen de canal publico validada-.

console.log('\n=== publicacion con imagen ===');

// PNG minimo valido: firma de 8 bytes y un IHDR. Alcanza para la validacion,
// que mira los primeros doce.
const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  Buffer.from([0x00, 0x00, 0x00, 0x0D]),
  Buffer.from('IHDR'),
  Buffer.alloc(40),
]);

async function subirAlAlmacen(url, cuerpo) {
  const r = await fetch(url, { method: 'PUT', body: cuerpo });
  return r.status;
}

r = await post('/v1/adjuntos', dueno.t, {
  conversacionId: CANAL, clase: 'imagen', bytes: PNG.length,
  mime: 'image/png', nombre: 'aviso.png', ancho: 4, alto: 4,
});
ck('el dueno reserva un adjunto de imagen en el canal', r.s === 200, JSON.stringify(r.b).slice(0, 120));
const reserva = r.b;

ck('la subida al almacen va bien', await subirAlAlmacen(reserva.urlSubida, PNG) < 300);

r = await post(`/v1/adjuntos/${reserva.adjuntoId}/confirmar`, dueno.t, {});
ck('y se confirma', r.s === 200, String(r.s) + ' ' + JSON.stringify(r.b).slice(0, 120));

const pImg = uuid();
await post('/v1/mensajes', dueno.t, { mensajeId: pImg, conversacionId: CANAL });
r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t, {
  mensajeId: pImg, cuerpo: 'Con foto', adjuntoId: reserva.adjuntoId,
});
ck('la publicacion acepta la imagen', r.s === 204, String(r.s) + ' ' + JSON.stringify(r.b));

r = await get(`/v1/canales/${CANAL}/publicaciones`, lector.t);
const conFoto = (r.b ?? []).find(x => x.cuerpo === 'Con foto');
ck('el muro devuelve el id del adjunto', conFoto?.adjuntoId === reserva.adjuntoId,
   JSON.stringify(conFoto).slice(0, 160));
ck('y sus medidas, para que la tarjeta no salte de alto',
   conFoto?.adjuntoAncho === 4 && conFoto?.adjuntoAlto === 4,
   `${conFoto?.adjuntoAncho}x${conFoto?.adjuntoAlto}`);

console.log('\n=== la imagen de un canal publico la ve cualquiera ===');
//
// MISMA regla que el muro. Si la imagen exigiera pertenencia, quien todavia no
// sigue el canal veria las publicaciones con un hueco donde va la foto — o sea
// justo quien esta decidiendo si seguirlo.

r = await get(`/v1/adjuntos/${reserva.adjuntoId}`, otro.t);
ck('quien NO esta suscrito obtiene la URL de la imagen', r.s === 200,
   String(r.s) + ' ' + JSON.stringify(r.b).slice(0, 100));
ck('y es una URL de descarga', typeof r.b?.urlDescarga === 'string' && r.b.urlDescarga.length > 10,
   String(r.b?.urlDescarga).slice(0, 60));

console.log('\n=== lo que el servidor SI puede validar aqui ===');

r = await post('/v1/adjuntos', dueno.t, {
  conversacionId: CANAL, clase: 'imagen', bytes: 40,
  mime: 'image/png', nombre: 'mentira.png', ancho: 1, alto: 1,
});
const falsa = r.b;
ck('se reserva otro adjunto', r.s === 200);
// Un ejecutable disfrazado de PNG: el Content-Type dice imagen y los bytes no.
await subirAlAlmacen(falsa.urlSubida, Buffer.concat([Buffer.from('MZ'), Buffer.alloc(40, 0x41)]));
r = await post(`/v1/adjuntos/${falsa.adjuntoId}/confirmar`, dueno.t, {});
ck('confirmar RECHAZA un archivo que no es una imagen', r.s === 400,
   String(r.s) + ' ' + JSON.stringify(r.b));

r = await get(`/v1/adjuntos/${falsa.adjuntoId}`, dueno.t);
ck('y el adjunto rechazado deja de existir', r.s === 404, String(r.s));

console.log('\n=== lo que NO se puede colgar de una publicacion ===');

// El adjunto de OTRA conversacion. Sin esta comprobacion, publicar seria una
// forma de leer el archivo de un chat ajeno.
r = await post('/v1/adjuntos', lector.t, {
  conversacionId: CANAL, clase: 'imagen', bytes: PNG.length,
  mime: 'image/png', nombre: 'x.png', ancho: 1, alto: 1,
});
const ajena = r.b;
if (ajena?.urlSubida) {
  await subirAlAlmacen(ajena.urlSubida, PNG);
  await post(`/v1/adjuntos/${ajena.adjuntoId}/confirmar`, lector.t, {});
  const pAjeno = uuid();
  await post('/v1/mensajes', dueno.t, { mensajeId: pAjeno, conversacionId: CANAL });
  r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t, {
    mensajeId: pAjeno, cuerpo: 'robo la imagen', adjuntoId: ajena.adjuntoId,
  });
  ck('no se puede publicar la imagen que subio OTRA persona', r.s === 404,
     String(r.s) + ' ' + JSON.stringify(r.b));
}

const pNada = uuid();
await post('/v1/mensajes', dueno.t, { mensajeId: pNada, conversacionId: CANAL });
r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t, { mensajeId: pNada, cuerpo: '' });
ck('una publicacion sin texto y sin imagen se rechaza', r.s === 400,
   String(r.s) + ' ' + JSON.stringify(r.b));

// Con imagen y sin texto SI: una foto sola es una publicacion.
r = await post('/v1/adjuntos', dueno.t, {
  conversacionId: CANAL, clase: 'imagen', bytes: PNG.length,
  mime: 'image/png', nombre: 'sola.png', ancho: 2, alto: 2,
});
const sola = r.b;
await subirAlAlmacen(sola.urlSubida, PNG);
await post(`/v1/adjuntos/${sola.adjuntoId}/confirmar`, dueno.t, {});
const pSola = uuid();
await post('/v1/mensajes', dueno.t, { mensajeId: pSola, conversacionId: CANAL });
r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t, {
  mensajeId: pSola, cuerpo: '', adjuntoId: sola.adjuntoId,
});
ck('pero con imagen y sin texto si se publica', r.s === 204, String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== un canal PRIVADO sigue siendo solo texto ===');
r = await post(`/v1/canales/${PRIVADO}/publicaciones`, dueno.t, {
  mensajeId: uuid(), cuerpo: 'x', adjuntoId: sola.adjuntoId,
});
ck('un canal privado rechaza guardar contenido, con imagen o sin ella', r.s === 409,
   String(r.s) + ' ' + JSON.stringify(r.b));

console.log('\n=== el muro se ordena por FECHA, no por id ===');
//
// El defecto que esto fija: el muro ordenaba por `mensaje_id DESC`, y el id de
// un mensaje lo genera el cliente con `randomUUID()`, o sea un v4: **un numero
// al azar**. El muro salia desordenado y no se veia porque hace falta mas de
// una publicacion para notarlo. Medido en la base antes de arreglarlo,
// ordenando por id salian las fechas 19, 19, 20, 19, 18 y 23 de septiembre.
//
// La prueba **elige los ids a proposito** para que el orden por id sea el
// CONTRARIO al orden por fecha. Sin eso, con dos ids al azar la prueba pasaria
// la mitad de las veces contra el codigo roto, que es peor que no tenerla.

const dos = [uuid(), uuid()].sort();
const idViejoPeroMayor = dos[1];   // se publica PRIMERO y tiene el id mas ALTO
const idNuevoPeroMenor = dos[0];   // se publica DESPUES y tiene el id mas BAJO

await post('/v1/mensajes', dueno.t, { mensajeId: idViejoPeroMayor, conversacionId: CANAL });
r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t,
               { mensajeId: idViejoPeroMayor, cuerpo: 'la primera' });
ck('se publica la primera', r.s === 204, String(r.s));

// Un instante despues, para que `creado_en` sea estrictamente mayor.
await new Promise(res => setTimeout(res, 25));

await post('/v1/mensajes', dueno.t, { mensajeId: idNuevoPeroMenor, conversacionId: CANAL });
r = await post(`/v1/canales/${CANAL}/publicaciones`, dueno.t,
               { mensajeId: idNuevoPeroMenor, cuerpo: 'la segunda' });
ck('y despues la segunda, con un id MENOR', r.s === 204, String(r.s));

r = await get(`/v1/canales/${CANAL}/publicaciones`, dueno.t);
const cuerpos = (r.b ?? []).map(x => x.cuerpo);
ck('el muro pone la mas nueva primero, aunque su id sea menor',
   cuerpos[0] === 'la segunda' && cuerpos[1] === 'la primera',
   JSON.stringify(cuerpos.slice(0, 3)));

// Y la paginacion tiene que seguir el MISMO orden. Un cursor que ordena por
// una clave y corta por otra saltea filas o las repite.
r = await get(`/v1/canales/${CANAL}/publicaciones?limite=1`, dueno.t);
ck('con limite 1 trae solo la mas nueva', r.b?.length === 1 && r.b[0].cuerpo === 'la segunda',
   JSON.stringify(r.b?.map(x => x.cuerpo)));

r = await get(`/v1/canales/${CANAL}/publicaciones?limite=1&antes=${idNuevoPeroMenor}`, dueno.t);
ck('y pidiendo lo anterior a ella trae la primera, no un salteo',
   r.b?.length === 1 && r.b[0].cuerpo === 'la primera',
   JSON.stringify(r.b?.map(x => x.cuerpo)));

console.log('\n=== configuracion: solo quien puede ===');
r = await put(`/v1/canales/${CANAL}`, lector.t, {
  nombre: 'Secuestrado', alias: ALIAS, publico: true, descripcion: '', comentarios: true, reacciones: true,
});
ck('un suscriptor NO puede configurar el canal', r.s === 403, String(r.s));

console.log('\n=== estadisticas ===');
r = await get(`/v1/canales/${CANAL}/estadisticas`, dueno.t);
ck('el dueno ve las estadisticas', r.s === 200, JSON.stringify(r.b));
ck('cuenta suscriptores', r.b?.suscriptores === 2, String(r.b?.suscriptores));
// Calculado, por lo mismo que el de comentarios: estaba en `=== 1` y se
// rompio al agregar la seccion del orden, que publica dos mas.
const statsPre = r.b;
r = await get(`/v1/canales/${CANAL}/publicaciones?limite=100`, dueno.t);
ck('cuenta publicaciones', statsPre?.publicaciones === r.b.length,
   `estadistica=${statsPre?.publicaciones} muro=${r.b.length}`);
r = { b: statsPre };
// Calculado y NO fijo. Estaba en `=== 1` y se rompio al agregar la seccion
// del cuerpo, que crea un comentario mas: una prueba con un numero a mano se
// rompe cuando alguien agrega datos arriba, y entonces lo que se corrige es el
// numero -sin mirar- en vez del codigo. Ya paso en `moderacion.mjs`.
//
// `p1` es la unica publicacion con comentarios, asi que la estadistica del
// canal tiene que coincidir con los que devuelve su lista.
const stats = r.b;
r = await get(`/v1/canales/${CANAL}/publicaciones/${p1}/comentarios`, dueno.t);
ck('cuenta comentarios aparte de las publicaciones',
   stats?.comentarios === r.b.comentarios?.length,
   `estadistica=${stats?.comentarios} lista=${r.b.comentarios?.length}`);
r = { b: stats };
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
