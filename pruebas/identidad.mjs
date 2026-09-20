// Modulo I: identidad y cuenta.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que el TELEFONO se guarde SOLO como hash y aun asi sirva para recuperar.
//     Se verifica mirando la base: no debe haber ni un numero en claro.
//  2. Que la normalizacion a E.164 funcione: el mismo numero escrito de cinco
//     maneras tiene que dar el MISMO hash, o el descubrimiento falla en
//     silencio. Es el riesgo real de este modulo.
//  3. Que la ruta de recuperar no sea un oraculo: pedir un codigo con un
//     usuario o un numero que no coinciden tiene que responder igual que si
//     coincidieran.
//  4. Que recuperar cierre todas las sesiones.
//  5. Que el 2FA no se pueda apagar sin la contrasena.
//  6. Que "conocidos" sea contacto O conversacion, y en la direccion correcta.
//  7. Que un codigo tenga tope de intentos, vida corta y un solo uso.
//  8. Que el telefono NO sirva para ingresar: el ingreso es por username.
import { execSync } from 'node:child_process';
import { createHmac } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

const CLAVE = 'clave-larga-123';

async function reg(u) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: CLAVE, etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64('HW-' + u + S), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dev: j.dispositivoId, user: u + S, hw: b64('HW-' + u + S) };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m,
    headers: t ? H(t) : { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const post = (r, t, b) => call('POST', r, t, b);
const put = (r, t, b) => call('PUT', r, t, b);
const del = (r, t, b) => call('DELETE', r, t, b);

const psql = (sql) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c "${sql}"`,
  { stdio: 'pipe' },
).toString().trim();

const ana = await reg('ia');
const beto = await reg('ib');
const ceci = await reg('ic');

// Numeros distintos por corrida: el indice de telefono es unico.
const n9 = () => '9' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
const TEL_ANA = '+51' + n9();
const TEL_BETO = '+51' + n9();

console.log('\n=== normalizacion a E.164 ===');
// Es el riesgo real del modulo: si el mismo numero da hashes distintos segun
// como se escriba, el descubrimiento falla y nadie entiende por que.
const local = TEL_ANA.slice(3);
let r = await post('/v1/cuenta/codigo', ana.t, { telefono: 'no-es-un-numero' });
ck('algo que no es un numero se rechaza', r.s === 400, String(r.s) + JSON.stringify(r.b).slice(0, 90));
r = await post('/v1/cuenta/codigo', ana.t, { telefono: '123' });
ck('un numero demasiado corto se rechaza', r.s === 400, String(r.s));

console.log('\n=== I.2 pedir y canjear el codigo ===');
r = await post('/v1/cuenta/codigo', ana.t, { telefono: TEL_ANA });
ck('se pide el codigo por SMS', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('dice cuanto hay que esperar para reenviar', r.b.reintentarEnSegundos > 0, String(r.b.reintentarEnSegundos));
ck('y cuanto vive el codigo', r.b.expiraEnSegundos >= 300, String(r.b.expiraEnSegundos));
ck('sin pasarela de SMS el codigo vuelve marcado como de prueba',
   typeof r.b.codigoDePrueba === 'string' && r.b.codigoDePrueba.length === 6,
   JSON.stringify(r.b));
const COD_ANA = r.b.codigoDePrueba;

// El mismo numero escrito de otra forma tiene que caer en el MISMO destino, y
// por eso choca con la espera de reenvio. Es la prueba de que normaliza.
// Se afirma sobre el MENSAJE y no solo sobre el 429: "ya te enviamos un
// codigo" prueba que cayo en el mismo destino, mientras "vas muy rapido" seria
// el limite de ritmo y haria pasar la prueba por el motivo equivocado.
const mismoDestino = (rr) => rr.s === 429 && (rr.b?.motivo || '').includes('Ya te enviamos');
r = await post('/v1/cuenta/codigo', ana.t, { telefono: local });
ck('el mismo numero sin prefijo cae en el mismo destino: normaliza', mismoDestino(r),
   String(r.s) + JSON.stringify(r.b).slice(0, 90));
r = await post('/v1/cuenta/codigo', ana.t, { telefono: '00' + TEL_ANA.slice(1) });
ck('y escrito con 00 en vez de +, tambien', mismoDestino(r), JSON.stringify(r.b).slice(0, 90));
r = await post('/v1/cuenta/codigo', ana.t, { telefono: TEL_ANA.slice(0, 3) + ' ' + local.slice(0, 3) + '-' + local.slice(3) });
ck('y con espacios y guiones, tambien', mismoDestino(r), JSON.stringify(r.b).slice(0, 90));

r = await post('/v1/cuenta/telefono', ana.t, { telefono: TEL_ANA, codigo: '000000' });
ck('un codigo que no coincide se rechaza', r.s === 400, String(r.s));
ck('y dice cuantos intentos quedan', (r.b?.motivo || '').includes('intentos'), JSON.stringify(r.b));

// Se canjea con el numero escrito DE OTRA FORMA: si la normalizacion no fuera
// consistente entre pedir y canjear, esto fallaria.
r = await post('/v1/cuenta/telefono', ana.t, { telefono: local, codigo: COD_ANA });
ck('el codigo correcto verifica el telefono, aunque se escriba distinto',
   r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('el prefijo de pais SI se guarda en claro', r.b.pais === '51', r.b.pais);
ck('y devuelve el numero ofuscado, no el numero', (r.b.ofuscado || '').includes('***'), r.b.ofuscado);

r = await post('/v1/cuenta/telefono', ana.t, { telefono: TEL_ANA, codigo: COD_ANA });
ck('el mismo codigo no sirve dos veces', r.s === 400, String(r.s));

console.log('\n=== un evento de seguridad que falla NO tumba la operacion ===');
// Reproduce el defecto que encontro el emulador. `remoteHost` devolvia
// "localhost" detras de adb reverse, y 'localhost'::inet es un ERROR de
// Postgres: el INSERT del evento fallaba, la transaccion quedaba abortada, y
// el commit se volvia un rollback silencioso. La ruta respondia 200 con un
// codigo que no existia en la base.
const TEL_NAT = '+51' + n9();
{
  const rr = await fetch(BASE + '/v1/cuenta/codigo', {
    method: 'POST',
    headers: { ...H(ceci.t), 'X-Forwarded-For': 'localhost' },
    body: JSON.stringify({ telefono: TEL_NAT }),
  });
  const pedidoNat = await rr.json();
  ck('pedir codigo con una IP que no es IP responde 200', rr.status === 200,
     String(rr.status) + JSON.stringify(pedidoNat).slice(0, 90));
  ck('y devuelve un codigo', typeof pedidoNat.codigoDePrueba === 'string',
     JSON.stringify(pedidoNat));

  r = await post('/v1/cuenta/telefono', ceci.t,
                 { telefono: TEL_NAT, codigo: pedidoNat.codigoDePrueba });
  ck('y ese codigo SI existe en la base: el evento fallido solo se deshizo a si mismo',
     r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
  await del('/v1/cuenta/telefono', ceci.t);
}

console.log('\n=== el numero NO esta en la base ===');
const sinPrefijo = TEL_ANA.slice(1);
const fuga = psql(
  `SELECT count(*) FROM usuario u WHERE ` +
  `coalesce(u.username::text,'') || coalesce(u.nombre_mostrado,'') || ` +
  `coalesce(u.estado_texto,'') || coalesce(u.biografia,'') || ` +
  `coalesce(u.telefono_pais,'') LIKE '%${sinPrefijo}%'`,
);
ck('el numero no aparece en ninguna columna de texto de usuario', fuga === '0', fuga);
const fugaCodigo = psql(
  `SELECT count(*) FROM codigo_verificacion WHERE destino_hash::text LIKE '%${sinPrefijo}%'`,
);
ck('ni en la tabla de codigos', fugaCodigo === '0', fugaCodigo);
ck('lo unico en claro es el prefijo de pais, que comparten millones',
   psql(`SELECT telefono_pais FROM usuario WHERE username='${ana.user}'`) === '51');

// Y que el hash sea el que dice ser: HMAC con el pepper del entorno, sobre el
// numero YA normalizado.
const esperado = createHmac('sha256', 'pepper-de-pruebas-local-no-produccion')
  .update(TEL_ANA).digest('hex');
const guardado = psql(`SELECT encode(telefono_hash,'hex') FROM usuario WHERE username='${ana.user}'`);
ck('el hash guardado es HMAC-SHA256(pepper, numero en E.164)', guardado === esperado,
   guardado.slice(0, 20) + ' vs ' + esperado.slice(0, 20));

console.log('\n=== un numero no se comparte entre cuentas ===');
r = await post('/v1/cuenta/codigo', beto.t, { telefono: TEL_ANA });
ck('pedir un codigo para un numero ya tomado se rechaza ANTES de mandarlo',
   r.s === 409, String(r.s) + JSON.stringify(r.b).slice(0, 90));

console.log('\n=== I.1 estado de la cuenta ===');
r = await get('/v1/cuenta', ana.t);
ck('el estado dice que el telefono esta verificado', r.b.telefonoVerificado === true);
ck('y que la cuenta ya se puede recuperar', r.b.puedeRecuperarse === true);
ck('arranca descubrible', r.b.descubrible === true);
ck('sin 2FA', r.b.totpActivado === false);

r = await get('/v1/cuenta', beto.t);
ck('una cuenta sin telefono NO se puede recuperar: eso es lo que hay que avisar',
   r.b.puedeRecuperarse === false);

r = await put('/v1/cuenta', ana.t, { biografia: 'Auditoria y seguridad', descubrible: false });
ck('se guarda la biografia', r.b.biografia === 'Auditoria y seguridad', r.b.biografia);
ck('y se puede apagar el descubrimiento', r.b.descubrible === false);
await put('/v1/cuenta', ana.t, { descubrible: true });

console.log('\n=== I.4 descubrir por telefono, como la agenda ===');
r = await post('/v1/cuenta/codigo', beto.t, { telefono: TEL_BETO });
await post('/v1/cuenta/telefono', beto.t, { telefono: TEL_BETO, codigo: r.b.codigoDePrueba });

const TEL_NADIE = '+51' + n9();
r = await post('/v1/contactos/descubrir', ceci.t, { telefonos: [TEL_ANA, TEL_BETO, TEL_NADIE] });
ck('se encuentran los dos que existen', (r.b.encontrados || []).length === 2,
   JSON.stringify(r.b).slice(0, 200));
ck('y devuelve el numero consultado para poder emparejarlo',
   (r.b.encontrados || []).some((e) => e.telefono === TEL_ANA));
ck('el que no existe simplemente no aparece',
   !(r.b.encontrados || []).some((e) => e.telefono === TEL_NADIE));

// La agenda de un telefono trae los numeros escritos de cualquier manera.
r = await post('/v1/contactos/descubrir', ceci.t, {
  telefonos: [TEL_ANA.slice(3), '00' + TEL_ANA.slice(1), TEL_ANA],
});
ck('el mismo numero en tres formatos encuentra UNA persona, no tres',
   (r.b.encontrados || []).length === 1, JSON.stringify(r.b).slice(0, 160));

r = await post('/v1/contactos/descubrir', ceci.t, { telefonos: ['no-es-un-numero', '12'] });
ck('lo que no es un numero se descarta sin error', r.s === 200 && (r.b.encontrados || []).length === 0);

await put('/v1/cuenta', ana.t, { descubrible: false });
r = await post('/v1/contactos/descubrir', ceci.t, { telefonos: [TEL_ANA] });
ck('quien apago el descubrimiento NO aparece', (r.b.encontrados || []).length === 0,
   JSON.stringify(r.b));
await put('/v1/cuenta', ana.t, { descubrible: true });

// Un numero sin verificar no debe servir para aparecer.
r = await post('/v1/contactos/descubrir', ana.t, { telefonos: ['+51' + n9()] });
ck('un numero que nadie verifico no encuentra a nadie', (r.b.encontrados || []).length === 0);

console.log('\n=== contactos ===');
r = await post('/v1/contactos', ceci.t, { username: ana.user, alias: 'Ana de auditoria' });
ck('se guarda un contacto con alias propio', r.s === 200 && r.b.contactos.length === 1,
   JSON.stringify(r.b).slice(0, 140));
ck('el alias es el que YO le puse', r.b.contactos[0].alias === 'Ana de auditoria');

r = await post('/v1/contactos', ceci.t, { username: ana.user, favorito: true });
ck('marcar favorito no borra el alias', r.b.contactos[0].favorito === true && r.b.contactos[0].alias === 'Ana de auditoria',
   JSON.stringify(r.b.contactos[0]));

r = await post('/v1/contactos', ceci.t, { username: ceci.user });
ck('no se puede agregar a uno mismo', r.s === 400, String(r.s));

r = await post('/v1/contactos/descubrir', ceci.t, { telefonos: [TEL_ANA] });
ck('descubrir avisa si ya es contacto', r.b.encontrados[0]?.yaEsContacto === true);

console.log('\n=== "conocidos" ahora es contacto O conversacion ===');
// Ana solo acepta mensajes de conocidos.
await put('/v1/perfil/privacidad', ana.t, { escribe: 'conocidos' });
r = await post('/v1/conversaciones/directa', beto.t, { usernameDestino: ana.user });
ck('un desconocido no puede abrir conversacion con ella', r.s === 403, String(r.s));
// Ceci la tiene en su libreta, pero eso es de CECI: lo que importa para que
// Beto pueda escribirle es que ANA lo tenga a el.
r = await post('/v1/contactos', ana.t, { username: beto.user });
ck('ana guarda a beto en su libreta', r.s === 200);
r = await post('/v1/conversaciones/directa', beto.t, { usernameDestino: ana.user });
ck('y ahora beto SI puede escribirle, sin haber hablado nunca antes', r.s === 200, String(r.s));

// EL SALTO DE PRIVACIDAD. Un ajuste es del DUENO: "solo conocidos" significa
// los que YO considero conocidos. Si bastara con agregar a alguien a la propia
// libreta para pasar su filtro, el ajuste no prometeria nada.
await put('/v1/perfil/privacidad', ceci.t, { escribe: 'conocidos' });
r = await post('/v1/contactos', beto.t, { username: ceci.user });
ck('beto agrega a ceci a SU libreta', r.s === 200, String(r.s));
r = await post('/v1/conversaciones/directa', beto.t, { usernameDestino: ceci.user });
ck('agregar a alguien a mi libreta NO me deja saltar SU filtro de conocidos',
   r.s === 403, String(r.s) + JSON.stringify(r.b).slice(0, 90));
r = await post('/v1/contactos', ceci.t, { username: beto.user });
r = await post('/v1/conversaciones/directa', beto.t, { usernameDestino: ceci.user });
ck('hace falta que sea ELLA la que lo guarde', r.s === 200, String(r.s));
await put('/v1/perfil/privacidad', ceci.t, { escribe: 'todos' });
await del(`/v1/contactos/${ceci.user}`, beto.t);
await del(`/v1/contactos/${beto.user}`, ceci.t);

await put('/v1/perfil/privacidad', ana.t, { escribe: 'todos' });

console.log('\n=== I.5 sesiones ===');
r = await get('/v1/sesiones', ana.t);
ck('se listan las sesiones', r.s === 200 && r.b.sesiones.length >= 1, JSON.stringify(r.b).slice(0, 160));
const actual = (r.b.sesiones || []).find((x) => x.esLaActual);
ck('una esta marcada como la actual', !!actual, JSON.stringify(r.b.sesiones));
ck('y dice desde donde se abrio', actual?.ip !== undefined);

// Un segundo login abre otra sesion sobre el mismo dispositivo.
const login2 = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw });
ck('un segundo ingreso abre otra sesion', login2.s === 200, String(login2.s));
const T2 = login2.b.token;

r = await get('/v1/sesiones', ana.t);
ck('ahora hay dos', r.b.sesiones.length === 2, String(r.b.sesiones.length));

r = await del('/v1/sesiones/otras', ana.t);
ck('se cierran las otras y se dice cuantas', r.s === 200 && r.b.cerradas === 1, JSON.stringify(r.b));

r = await get('/v1/cuenta', T2);
ck('la sesion cerrada deja de servir de inmediato', r.s === 401, String(r.s));
r = await get('/v1/cuenta', ana.t);
ck('y la propia sigue viva', r.s === 200, String(r.s));

console.log('\n=== salir cierra la sesion EN EL SERVIDOR ===');
const login3 = await post('/v1/sesion', null, { username: beto.user, password: CLAVE, hardwareHash: beto.hw });
const T3 = login3.b.token;
r = await del('/v1/sesiones', T3);
ck('salir responde 204', r.s === 204, String(r.s));
r = await get('/v1/cuenta', T3);
ck('y el token queda muerto: ya no es solo cosa del cliente', r.s === 401, String(r.s));

console.log('\n=== I.6 2FA ===');
r = await post('/v1/cuenta/totp', ana.t);
ck('se genera el secreto', r.s === 200 && r.b.secretoBase32.length >= 30, JSON.stringify(r.b).slice(0, 140));
ck('y una URI otpauth para el QR', (r.b.uri || '').startsWith('otpauth://totp/wtfuck:'), r.b.uri);
const SECRETO = r.b.secretoBase32;

r = await get('/v1/cuenta', ana.t);
ck('un secreto sin confirmar NO activa el 2FA', r.b.totpActivado === false);

r = await post('/v1/cuenta/totp/confirmar', ana.t, { codigo: '000000' });
ck('un codigo que no coincide no lo activa', r.s === 400, String(r.s));

// TOTP del lado del cliente, para probar el del servidor.
const base32 = (s) => {
  const ABC = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, val = 0;
  const out = [];
  for (const ch of s.toUpperCase().replace(/=/g, '')) {
    val = (val << 5) | ABC.indexOf(ch);
    bits += 5;
    if (bits >= 8) { out.push((val >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
};
const totp = (secreto, t = Date.now()) => {
  const ctr = Math.floor(t / 1000 / 30);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(ctr));
  const h = createHmac('sha1', base32(secreto)).update(msg).digest();
  const off = h[h.length - 1] & 0x0f;
  const bin = ((h[off] & 0x7f) << 24) | (h[off + 1] << 16) | (h[off + 2] << 8) | h[off + 3];
  return String(bin % 1000000).padStart(6, '0');
};

r = await post('/v1/cuenta/totp/confirmar', ana.t, { codigo: totp(SECRETO) });
ck('el codigo correcto lo activa', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('y entrega codigos de respaldo, una sola vez', (r.b.codigosRespaldo || []).length === 8,
   String((r.b.codigosRespaldo || []).length));
const RESPALDOS = r.b.codigosRespaldo;
ck('los de respaldo no usan caracteres que se confunden',
   RESPALDOS.every((x) => !/[01OIL]/.test(x.replace('-', ''))), RESPALDOS[0]);

r = await get('/v1/cuenta', ana.t);
ck('el estado ya reporta el 2FA', r.b.totpActivado === true);
ck('y cuantos respaldos quedan', r.b.codigosRespaldoSinUsar === 8, String(r.b.codigosRespaldoSinUsar));

console.log('\n=== el 2FA se aplica al ingresar ===');
r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw });
ck('sin el codigo no se entra', r.s === 401, String(r.s));
ck('y se dice que la cuenta pide dos pasos', (r.b?.motivo || '').includes('dos pasos'),
   JSON.stringify(r.b));

r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw, totp: '123456' });
ck('con un codigo cualquiera tampoco', r.s === 401, String(r.s));

r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw, totp: totp(SECRETO) });
ck('con el codigo correcto si', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 90));

r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw, totp: RESPALDOS[0] });
ck('un codigo de respaldo tambien entra', r.s === 200, String(r.s));
r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw, totp: RESPALDOS[0] });
ck('y se consume: no vale dos veces', r.s === 401, String(r.s));
r = await get('/v1/cuenta', ana.t);
ck('quedan siete', r.b.codigosRespaldoSinUsar === 7, String(r.b.codigosRespaldoSinUsar));

console.log('\n=== apagar el 2FA exige la contrasena ===');
r = await del('/v1/cuenta/totp', ana.t, { password: 'equivocada' });
ck('con la contrasena mal, no se apaga', r.s === 403, String(r.s));
r = await get('/v1/cuenta', ana.t);
ck('sigue activo', r.b.totpActivado === true);

console.log('\n=== I.3 recuperar la cuenta ===');
// El oraculo: pedir un codigo con datos que no coinciden tiene que verse igual.
r = await post('/v1/cuenta/codigo', null, {
  telefono: TEL_ANA, proposito: 'recuperar_cuenta', username: 'noexiste' + S,
});
ck('pedir codigo para un usuario que no existe responde 200 igual', r.s === 200, String(r.s));
ck('y NO trae codigo: no se le manda nada a nadie', r.b.codigoDePrueba == null, JSON.stringify(r.b));

r = await post('/v1/cuenta/codigo', null, {
  telefono: TEL_BETO, proposito: 'recuperar_cuenta', username: ana.user,
});
ck('con un numero que no es de esa cuenta, lo mismo', r.s === 200 && r.b.codigoDePrueba == null,
   JSON.stringify(r.b));

r = await post('/v1/cuenta/codigo', null, {
  telefono: TEL_ANA, proposito: 'recuperar_cuenta', username: ana.user,
});
ck('con los datos correctos si se manda el codigo', r.s === 200 && !!r.b.codigoDePrueba,
   JSON.stringify(r.b));
const COD_REC = r.b.codigoDePrueba;

r = await post('/v1/cuenta/recuperar', null, {
  username: ana.user, telefono: TEL_ANA, codigo: COD_REC, passwordNueva: 'corta',
});
ck('una contrasena nueva demasiado corta se rechaza', r.s === 400, String(r.s));

const CLAVE_NUEVA = 'clave-nueva-larga-456';
r = await post('/v1/cuenta/recuperar', null, {
  username: ana.user, telefono: TEL_ANA, codigo: COD_REC, passwordNueva: CLAVE_NUEVA,
});
ck('se recupera la cuenta', r.s === 204, String(r.s) + JSON.stringify(r.b).slice(0, 90));

r = await get('/v1/cuenta', ana.t);
ck('recuperar CIERRA todas las sesiones: si te la robaron, el cambio tiene que servir',
   r.s === 401, String(r.s));

r = await post('/v1/sesion', null, { username: ana.user, password: CLAVE, hardwareHash: ana.hw, totp: totp(SECRETO) });
ck('la contrasena vieja ya no entra', r.s === 401, String(r.s));

r = await post('/v1/sesion', null, {
  username: ana.user, password: CLAVE_NUEVA, hardwareHash: ana.hw, totp: totp(SECRETO),
});
ck('la nueva si', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 90));
const ANA2 = r.b.token;
r = await get('/v1/cuenta', ANA2);
ck('y el 2FA sigue puesto: recuperar la clave no lo quita', r.b.totpActivado === true,
   JSON.stringify(r.b).slice(0, 120));

r = await post('/v1/sesion', null, {
  username: ana.user, password: CLAVE_NUEVA, hardwareHash: b64('HW-OTRO' + S), totp: totp(SECRETO),
});
ck('recuperar NO devuelve el acceso desde otro telefono: el vinculo sigue', r.s === 403,
   String(r.s) + JSON.stringify(r.b).slice(0, 90));

console.log('\n=== el telefono NO sirve para entrar ===');
// Es la regla que sostiene todo el diseno: si el numero sirviera para ingresar,
// volveria a ser el identificador de la cuenta y se perderia lo que el registro
// por username resolvio.
r = await post('/v1/sesion', null, {
  username: TEL_ANA, password: CLAVE_NUEVA, hardwareHash: ana.hw, totp: totp(SECRETO),
});
ck('el numero no es un usuario valido para ingresar', r.s === 401, String(r.s));

console.log('\n=== I.7 eliminar la cuenta ===');
r = await post('/v1/cuenta/eliminar', ceci.t, { password: 'equivocada' });
ck('sin la contrasena no se elimina', r.s === 403, String(r.s));

r = await post('/v1/cuenta/eliminar', ANA2, { password: CLAVE_NUEVA });
ck('con 2FA activo, tambien hace falta el codigo', r.s === 401, String(r.s));

r = await post('/v1/cuenta/eliminar', ceci.t, { password: CLAVE });
ck('se pide la eliminacion', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('con periodo de gracia de 30 dias', r.b.diasDeGracia === 30, String(r.b.diasDeGracia));
ck('y fecha de ejecucion', r.b.seEjecutaEn > r.b.pedidaEn, JSON.stringify(r.b).slice(0, 120));
ck('dice lo que NO se puede borrar, en vez de prometer un borrado total',
   (r.b.advertencias || []).length >= 3, JSON.stringify(r.b.advertencias).slice(0, 200));
ck('y avisa que entrar cancela', (r.b.advertencias || []).some((a) => a.includes('cancela')));

r = await get('/v1/cuenta', ceci.t);
ck('pedir la eliminacion cierra las sesiones', r.s === 401, String(r.s));

r = await post('/v1/sesion', null, { username: ceci.user, password: CLAVE, hardwareHash: ceci.hw });
ck('se puede volver a entrar', r.s === 200, String(r.s));
const CECI2 = r.b.token;
r = await get('/v1/cuenta', CECI2);
ck('y entrar CANCELA la eliminacion', r.b.eliminacionPedidaEn == null, JSON.stringify(r.b).slice(0, 140));

console.log('\n=== la cuenta sigue existiendo de verdad ===');
const existe = psql(`SELECT count(*) FROM usuario WHERE username='${ceci.user}'`);
ck('no se borro nada todavia', existe === '1', existe);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
