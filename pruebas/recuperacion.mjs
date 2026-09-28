// El codigo de recuperacion: la unica salida cuando se pierde el telefono.
//
// ## Por que esta suite es la mas importante de las nuevas
//
// Esta ruta hace EXACTAMENTE lo que el atado al hardware existe para impedir:
// dar de alta un aparato que nadie autorizo desde dentro. Si alguna de sus
// cuatro puertas no cierra, la defensa entera deja de valer — y no se notaria,
// porque el camino feliz seguiria funcionando igual.
//
// Asi que aqui se prueba sobre todo lo que tiene que FALLAR:
//
//   - sin el codigo del SMS
//   - sin el codigo de recuperacion, o con uno ajeno
//   - sin el segundo factor
//   - contra una cuenta que nunca configuro codigo
//   - desde un hardware que ya es de otra cuenta
//
// Y dos propiedades que no se ven desde el camino feliz: que la ruta no sirva
// de ORACULO (sin el SMS no se puede averiguar si una cuenta tiene codigo ni
// si tiene dos pasos) y que un rechazo NO queme el codigo del SMS.
import { createHmac, createHash, randomBytes } from 'node:crypto';

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (b) => Buffer.from(b).toString('base64');

const call = async (m, ruta, t, body) => {
  const h = { 'Content-Type': 'application/json' };
  if (t) h.Authorization = 'Bearer ' + t;
  const r = await fetch(BASE + ruta, {
    method: m, headers: h, body: body ? JSON.stringify(body) : undefined,
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};

// ---------------------------------------------------------------------------
//  El codigo, replicado aqui a proposito
// ---------------------------------------------------------------------------
//
// Se reimplementa en vez de importar del cliente Kotlin, y es deliberado: si
// la prueba usara la misma funcion que el codigo bajo prueba, un error en la
// derivacion pasaria desapercibido en las dos. Asi el HKDF se escribe dos
// veces de forma independiente y tienen que coincidir.
const ALFABETO = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function generarCodigo() {
  const entropia = randomBytes(16);
  const control = createHash('sha256').update(entropia).digest()[0];
  const bytes = Buffer.concat([entropia, Buffer.from([control])]);
  let out = '', acumulado = 0n, bits = 0;
  for (const b of bytes) {
    acumulado = (acumulado << 8n) | BigInt(b);
    bits += 8;
    while (bits >= 5) { bits -= 5; out += ALFABETO[Number((acumulado >> BigInt(bits)) & 31n)]; }
  }
  if (bits > 0) out += ALFABETO[Number((acumulado << BigInt(5 - bits)) & 31n)];
  return out;   // 28 simbolos, sin guiones
}

/** HKDF-SHA256: extract con sal de ceros + una vuelta de expand. */
function derivar(codigo, etiqueta) {
  const prk = createHmac('sha256', Buffer.alloc(32)).update(Buffer.from(codigo, 'ascii')).digest();
  return createHmac('sha256', prk)
    .update(Buffer.concat([Buffer.from(etiqueta, 'ascii'), Buffer.from([1])]))
    .digest();
}
const verificadorDe = (codigo) => b64(derivar(codigo, 'wtfuck/servidor/verificador/v1'));

// ---------------------------------------------------------------------------
//  Montaje
// ---------------------------------------------------------------------------
const CLAVE = 'clave-larga-de-prueba-123';

async function registrar(nombre, hw) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: nombre + S, password: CLAVE, etiquetaDispositivo: 'tel',
      identidadPub: b64('idk-' + nombre), hardwareHash: b64(hw + S),
      hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, user: nombre + S, hw: b64(hw + S) };
}

async function verificarTelefono(cuenta, tel) {
  let r = await call('POST', '/v1/cuenta/codigo', cuenta.t, {
    telefono: tel, proposito: 'verificar_telefono',
  });
  if (!r.b?.codigoDePrueba) return false;
  r = await call('POST', '/v1/cuenta/telefono', cuenta.t, {
    telefono: tel, codigo: r.b.codigoDePrueba,
  });
  return r.s === 204 || r.s === 200;
}

async function pedirSms(user, tel) {
  const r = await call('POST', '/v1/cuenta/codigo', null, {
    telefono: tel, proposito: 'recuperar_cuenta', username: user,
  });
  return r.b?.codigoDePrueba ?? null;
}

const TEL_ANA = '+51955' + String(Math.floor(100000 + Math.random() * 899999));
const TEL_BETO = '+51944' + String(Math.floor(100000 + Math.random() * 899999));

const ana = await registrar('ra', 'HW-RA');
const beto = await registrar('rb', 'HW-RB');

console.log('\n=== montaje ===');
ck('ana se registra', !!ana.t);
ck('beto se registra', !!beto.t);
ck('ana verifica su telefono', await verificarTelefono(ana, TEL_ANA));
ck('beto verifica el suyo', await verificarTelefono(beto, TEL_BETO));

// ---------------------------------------------------------------------------
//  Fijar el codigo
// ---------------------------------------------------------------------------
console.log('\n=== fijar el codigo de recuperacion ===');

let r = await call('GET', '/v1/cuenta/recuperacion', ana.t);
ck('al principio no hay codigo', r.s === 200 && r.b?.configurado === false, JSON.stringify(r.b));

const COD_ANA = generarCodigo();

r = await call('PUT', '/v1/cuenta/recuperacion', ana.t, {
  verificadorB64: verificadorDe(COD_ANA),
});
ck('sin la contrasena no se puede fijar', r.s === 400 || r.s === 401, String(r.s));

r = await call('PUT', '/v1/cuenta/recuperacion', ana.t, {
  verificadorB64: verificadorDe(COD_ANA), password: 'la-que-no-es',
});
ck('con la contrasena equivocada tampoco', r.s === 401, String(r.s));

r = await call('PUT', '/v1/cuenta/recuperacion', ana.t, {
  verificadorB64: b64(randomBytes(8)), password: CLAVE,
});
ck('un verificador que no mide 32 bytes se rechaza', r.s === 400, String(r.s));

r = await call('PUT', '/v1/cuenta/recuperacion', ana.t, {
  verificadorB64: verificadorDe(COD_ANA), password: CLAVE,
});
ck('con la contrasena si', r.s === 204, String(r.s) + JSON.stringify(r.b ?? ''));

r = await call('GET', '/v1/cuenta/recuperacion', ana.t);
ck('y ahora consta que hay uno', r.s === 200 && r.b?.configurado === true, JSON.stringify(r.b));
ck('con su fecha', r.b?.fijadoEn > 0, String(r.b?.fijadoEn));

r = await call('GET', '/v1/cuenta/recuperacion', null);
ck('sin sesion no se puede consultar', r.s === 401, String(r.s));

// El verificador NO debe poder leerse de vuelta por ninguna ruta: si se
// pudiera, una sesion robada bastaria para llevarse la salida de emergencia.
const cuerpoEstado = JSON.stringify(r.b ?? '') + JSON.stringify(
  (await call('GET', '/v1/cuenta', ana.t)).b ?? '',
);
ck('el verificador no vuelve por ninguna parte',
   !cuerpoEstado.includes(verificadorDe(COD_ANA).slice(0, 20)), cuerpoEstado.slice(0, 120));

// ---------------------------------------------------------------------------
//  Las cuatro puertas
// ---------------------------------------------------------------------------
console.log('\n=== recuperar en un telefono nuevo: lo que debe FALLAR ===');

const HW_NUEVO = b64('HW-NUEVO' + S);
const CLAVE_NUEVA = 'clave-nueva-larga-9876';
const aparato = {
  etiquetaDispositivo: 'telefono nuevo',
  identidadPub: b64('idk-ra'),
  hardwareHash: HW_NUEVO,
  hardwareNivel: 'SOFTWARE_DEV',
};

// Puerta 1: el SMS.
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: '000000',
  verificadorB64: verificadorDe(COD_ANA), passwordNueva: CLAVE_NUEVA, ...aparato,
});
ck('sin el codigo del SMS no entra', r.s === 400 || r.s === 403, String(r.s));

// El oraculo: sin pasar el SMS, la respuesta tiene que ser la misma se tenga o
// no el codigo de recuperacion. Si difiriera, cualquiera podria averiguar que
// cuentas tienen configurada la salida de emergencia.
const sinSmsConCodigo = r.s;
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: '000000',
  verificadorB64: b64(randomBytes(32)), passwordNueva: CLAVE_NUEVA, ...aparato,
});
ck('y la respuesta no delata si el codigo de recuperacion era bueno',
   r.s === sinSmsConCodigo, `${sinSmsConCodigo} vs ${r.s}`);

// Puerta 2: el codigo de recuperacion.
let sms = await pedirSms(ana.user, TEL_ANA);
ck('llega el SMS de recuperacion', !!sms, String(sms));

r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms,
  verificadorB64: verificadorDe(generarCodigo()),
  passwordNueva: CLAVE_NUEVA, ...aparato,
});
ck('con el SMS pero un codigo de recuperacion AJENO, no entra', r.s === 403, String(r.s));

// Y el SMS no se quemo: el rechazo deshace la transaccion. Sin esto haria
// falta un SMS nuevo por cada intento y la pantalla seria inusable.
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms,
  verificadorB64: 'no-es-base64-valido!!',
  passwordNueva: CLAVE_NUEVA, ...aparato,
});
ck('un verificador con basura tampoco', r.s === 403 || r.s === 400, String(r.s));

// Una cuenta SIN codigo configurado no se puede recuperar asi: es beto.
const smsBeto = await pedirSms(beto.user, TEL_BETO);
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: beto.user, telefono: TEL_BETO, codigo: smsBeto,
  verificadorB64: verificadorDe(generarCodigo()),
  passwordNueva: CLAVE_NUEVA,
  ...aparato, hardwareHash: b64('HW-OTRO-MAS' + S),
});
ck('una cuenta que nunca configuro codigo no se recupera', r.s === 403, String(r.s));

// Contrasena nueva demasiado corta: error de forma, no una puerta.
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms,
  verificadorB64: verificadorDe(COD_ANA), passwordNueva: 'corta', ...aparato,
});
ck('una contrasena nueva corta se rechaza', r.s === 400, String(r.s));

// Un hardware que YA es de otra cuenta no se puede reclamar.
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms,
  verificadorB64: verificadorDe(COD_ANA), passwordNueva: CLAVE_NUEVA,
  ...aparato, hardwareHash: beto.hw,
});
ck('no se puede recuperar sobre el hardware de otra cuenta', r.s === 409, String(r.s));

// ---------------------------------------------------------------------------
//  El camino feliz
// ---------------------------------------------------------------------------
console.log('\n=== y ahora si: el telefono nuevo entra ===');

// Antes: comprobar que ese hardware NO podia entrar. Es la linea base de todo
// el modulo — si esto no diera 403, no habria nada que arreglar.
r = await call('POST', '/v1/sesion', null, {
  username: ana.user, password: CLAVE, hardwareHash: HW_NUEVO,
});
ck('el telefono nuevo NO puede entrar por las buenas', r.s === 403, String(r.s));

r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms,
  verificadorB64: verificadorDe(COD_ANA), passwordNueva: CLAVE_NUEVA, ...aparato,
});
ck('con las cuatro puertas, se recupera', r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 120));
ck('y devuelve una sesion usable', !!r.b?.token, JSON.stringify(r.b).slice(0, 90));
const TOKEN_NUEVO = r.b?.token;

r = await call('GET', '/v1/cuenta', TOKEN_NUEVO);
ck('la sesion nueva sirve', r.s === 200, String(r.s));

// El aparato viejo queda fuera: el escenario es "me robaron el telefono".
r = await call('GET', '/v1/cuenta', ana.t);
ck('la sesion del telefono viejo quedo revocada', r.s === 401, String(r.s));

r = await call('POST', '/v1/sesion', null, {
  username: ana.user, password: CLAVE_NUEVA, hardwareHash: ana.hw,
});
ck('y el hardware viejo ya no puede volver a entrar', r.s === 403, String(r.s));

r = await call('POST', '/v1/sesion', null, {
  username: ana.user, password: CLAVE, hardwareHash: HW_NUEVO,
});
ck('la contrasena vieja ya no vale', r.s === 401, String(r.s));

r = await call('POST', '/v1/sesion', null, {
  username: ana.user, password: CLAVE_NUEVA, hardwareHash: HW_NUEVO,
});
ck('la nueva si, desde el telefono nuevo', r.s === 200, String(r.s));

// El codigo sigue valiendo: NO es de un solo uso a proposito. Si se invalidara
// y la persona se quedara a medias, volveria a estar bloqueada y sin salida.
const sms2 = await pedirSms(ana.user, TEL_ANA);
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms2,
  verificadorB64: verificadorDe(COD_ANA), passwordNueva: CLAVE_NUEVA, ...aparato,
});
ck('el mismo codigo sirve otra vez: no es de un solo uso', r.s === 200, String(r.s));

// ---------------------------------------------------------------------------
//  Rotar
// ---------------------------------------------------------------------------
console.log('\n=== rotar el codigo invalida el anterior ===');

r = await call('POST', '/v1/sesion', null, {
  username: ana.user, password: CLAVE_NUEVA, hardwareHash: HW_NUEVO,
});
const SESION = r.b?.token;

const COD_NUEVO = generarCodigo();
r = await call('PUT', '/v1/cuenta/recuperacion', SESION, {
  verificadorB64: verificadorDe(COD_NUEVO), password: CLAVE_NUEVA,
});
ck('se puede rotar', r.s === 204, String(r.s));

const sms3 = await pedirSms(ana.user, TEL_ANA);
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms3,
  verificadorB64: verificadorDe(COD_ANA), passwordNueva: CLAVE_NUEVA,
  ...aparato, hardwareHash: b64('HW-TERCERO' + S),
});
ck('el codigo VIEJO ya no sirve', r.s === 403, String(r.s));

r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms3,
  verificadorB64: verificadorDe(COD_NUEVO), passwordNueva: CLAVE_NUEVA,
  ...aparato, hardwareHash: b64('HW-TERCERO' + S),
});
ck('y el nuevo si', r.s === 200, String(r.s));

// ---------------------------------------------------------------------------
//  Puerta 3: el segundo factor
// ---------------------------------------------------------------------------
//
// Esta faltaba en la primera version de esta suite, y era el hueco mas grave:
// el camino feliz pasaba porque ninguna cuenta de prueba tenia 2FA, asi que la
// puerta nunca se tocaba. Una puerta que no se prueba es una puerta que no se
// sabe si cierra.
console.log('\n=== con dos pasos, tambien hace falta el TOTP ===');

// TOTP del lado del cliente, para probar el del servidor.
const base32 = (t) => {
  const ABC = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, val = 0;
  const out = [];
  for (const ch of t.toUpperCase().replace(/=/g, '')) {
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

// Se entra desde el ultimo aparato bueno para poder activar el 2FA.
r = await call('POST', '/v1/sesion', null, {
  username: ana.user, password: CLAVE_NUEVA, hardwareHash: b64('HW-TERCERO' + S),
});
const SESION_3 = r.b?.token;
ck('se entra desde el ultimo aparato', r.s === 200, String(r.s));

r = await call('POST', '/v1/cuenta/totp', SESION_3);
const SECRETO = r.b?.secretoBase32;
r = await call('POST', '/v1/cuenta/totp/confirmar', SESION_3, { codigo: totp(SECRETO) });
ck('se activa el 2FA', r.s === 200, String(r.s));
const RESPALDOS = r.b?.codigosRespaldo ?? [];
ck('y entrega codigos de respaldo', RESPALDOS.length > 0, String(RESPALDOS.length));

const HW_CUARTO = b64('HW-CUARTO' + S);
let sms4 = await pedirSms(ana.user, TEL_ANA);
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms4,
  verificadorB64: verificadorDe(COD_NUEVO), passwordNueva: CLAVE_NUEVA,
  ...aparato, hardwareHash: HW_CUARTO,
});
ck('con SMS y codigo pero SIN el segundo factor, no entra', r.s === 401, String(r.s));

r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms4,
  verificadorB64: verificadorDe(COD_NUEVO), passwordNueva: CLAVE_NUEVA, totp: '000000',
  ...aparato, hardwareHash: HW_CUARTO,
});
ck('con un segundo factor equivocado tampoco', r.s === 401, String(r.s));

// Un codigo de RESPALDO tiene que servir: quien perdio el telefono perdio
// tambien la app de autenticacion, y para eso se emiten.
r = await call('POST', '/v1/cuenta/recuperar-dispositivo', null, {
  username: ana.user, telefono: TEL_ANA, codigo: sms4,
  verificadorB64: verificadorDe(COD_NUEVO), passwordNueva: CLAVE_NUEVA,
  totp: RESPALDOS[0],
  ...aparato, hardwareHash: HW_CUARTO,
});
ck('un codigo de RESPALDO sirve: quien perdio el telefono perdio el autenticador',
   r.s === 200, String(r.s) + JSON.stringify(r.b).slice(0, 100));

// Y el SMS habia sobrevivido a los dos rechazos anteriores.
ck('el SMS aguanto los dos intentos fallidos sin quemarse', r.s === 200, String(r.s));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
