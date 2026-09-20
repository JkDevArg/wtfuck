// Modulo N · Avisos con la app cerrada.
//
// Lo que se comprueba SIEMPRE, este o no configurado el push:
//
//  1. Que el token se registre contra el dispositivo de la SESION y no contra
//     el que diga el cuerpo. Confiar en el cuerpo dejaria colgarle un token
//     propio al aparato de otro y enterarse de cuando recibe mensajes.
//  2. Que un token repetido en otro aparato limpie el anterior. Pasa de verdad
//     al reinstalar: Android puede devolver el mismo token.
//  3. Que darse de baja funcione, porque la app lo llama al cerrar sesion y en
//     un telefono prestado eso importa.
//  4. Que un token absurdo o un proveedor desconocido sean 400.
//  5. Que la configuracion que se le sirve al cliente NO traiga secretos.
//
// Y si el servidor SI tiene push configurado contra el stub, se comprueba
// ademas lo que de verdad importa: **que el aviso no lleve contenido**.
// Ver `stub-fcm.mjs` para como levantarlo.
const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const STUB = process.env.STUB_BASE ?? 'http://localhost:8399';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

async function reg(u, hw) {
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u + S, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + u), hardwareHash: b64(hw ?? ('HW-' + u + S)),
      hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, dispositivo: j.dispositivoId, user: u + S };
}
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, { method: m, headers: H(t), body: body ? JSON.stringify(body) : undefined });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const put = (r, t, b) => call('PUT', r, t, b);
const del = (r, t) => call('DELETE', r, t);

const ana = await reg('na');
const beto = await reg('nb');

console.log('\n=== la configuracion que se le sirve al cliente ===');
let r = await get('/v1/push/config', ana.t);
ck('responde 200', r.s === 200, String(r.s));
const cfg = r.b ?? {};
const activo = cfg.disponible === true;
console.log(`    (push ${activo ? 'CONFIGURADO' : 'apagado'} en este servidor)`);

// La clave privada de la cuenta de servicio es el unico secreto de todo esto, y
// no tiene por que estar en ninguna respuesta.
const textoCfg = JSON.stringify(cfg);
ck('no filtra la clave privada', !textoCfg.includes('PRIVATE KEY') && !textoCfg.includes('clave'),
   textoCfg.slice(0, 160));
ck('trae solo los cinco campos del contrato',
   Object.keys(cfg).sort().join(',') === 'apiKey,appId,disponible,proyectoId,remitenteId',
   Object.keys(cfg).join(','));

r = await fetch(BASE + '/v1/push/config');
ck('sin sesion es 401', r.status === 401, String(r.status));

console.log('\n=== registrar el token ===');
r = await put('/v1/push', ana.t, { token: 'tok-ana-' + S, proveedor: 'fcm' });
ck('se registra', r.s === 204, JSON.stringify(r.b));

r = await get('/v1/dispositivos', ana.t);
let mio = (r.b.dispositivos ?? []).find((d) => d.esEste);
ck('el aparato aparece recibiendo avisos', mio?.recibeAvisos === true, JSON.stringify(mio));

r = await get('/v1/dispositivos', beto.t);
ck('el de otra persona no', (r.b.dispositivos ?? []).every((d) => !d.recibeAvisos),
   JSON.stringify(r.b.dispositivos));

console.log('\n=== validacion ===');
r = await put('/v1/push', ana.t, { token: 'corto', proveedor: 'fcm' });
ck('un token de 5 caracteres es 400', r.s === 400, JSON.stringify(r.b));

r = await put('/v1/push', ana.t, { token: 'x'.repeat(5000), proveedor: 'fcm' });
ck('uno de 5000 tambien', r.s === 400, JSON.stringify(r.b));

r = await put('/v1/push', ana.t, { token: 'tok-valido-largo', proveedor: 'palomas' });
ck('un proveedor inventado es 400', r.s === 400, JSON.stringify(r.b));

r = await get('/v1/dispositivos', ana.t);
mio = (r.b.dispositivos ?? []).find((d) => d.esEste);
ck('y ninguno de los rechazos piso el token bueno', mio?.recibeAvisos === true, JSON.stringify(mio));

console.log('\n=== el mismo token en otro aparato limpia el anterior ===');
// Pasa al reinstalar la app: Android puede devolver el token que ya tenia.
// Dos filas con el mismo token despertarian al aparato equivocado.
const compartido = 'tok-compartido-' + S;
r = await put('/v1/push', ana.t, { token: compartido, proveedor: 'fcm' });
ck('ana registra el token', r.s === 204, String(r.s));
r = await put('/v1/push', beto.t, { token: compartido, proveedor: 'fcm' });
ck('beto registra el MISMO token', r.s === 204, String(r.s));

r = await get('/v1/dispositivos', ana.t);
mio = (r.b.dispositivos ?? []).find((d) => d.esEste);
ck('el de ana quedo sin avisos: el token se lo llevo el ultimo',
   mio?.recibeAvisos === false, JSON.stringify(mio));
r = await get('/v1/dispositivos', beto.t);
ck('y el de beto si los recibe',
   (r.b.dispositivos ?? []).find((d) => d.esEste)?.recibeAvisos === true,
   JSON.stringify(r.b.dispositivos));

console.log('\n=== darse de baja ===');
r = await del('/v1/push', beto.t);
ck('la baja responde 204', r.s === 204, String(r.s));
r = await get('/v1/dispositivos', beto.t);
ck('y el aparato deja de recibir avisos',
   (r.b.dispositivos ?? []).find((d) => d.esEste)?.recibeAvisos === false,
   JSON.stringify(r.b.dispositivos));

r = await del('/v1/push', beto.t);
ck('darse de baja dos veces no falla', r.s === 204, String(r.s));

// ------------------------------------------------------------------
//  Solo si el servidor apunta al stub
// ------------------------------------------------------------------
if (!activo) {
  console.log('\n=== el aviso en si: OMITIDO ===');
  console.log('    El servidor no tiene push configurado. Para probarlo:');
  console.log('    1. node pruebas/stub-fcm.mjs');
  console.log('    2. arrancar el servidor con WTFUCK_FCM_* apuntando al stub');
  console.log('    3. node pruebas/push.mjs');
} else {
  console.log('\n=== que viaja en el aviso ===');
  let stubVivo = true;
  await fetch(STUB + '/limpiar', { method: 'POST' }).catch(() => { stubVivo = false; });
  ck('el stub de FCM responde', stubVivo, `no hay nada en ${STUB}`);

  if (stubVivo) {
    // Ana registra un token y se queda SIN socket abierto. Beto le escribe:
    // ahi es donde el servidor tiene que ir a buscarla.
    const tokenAna = 'tok-aviso-' + S;
    await put('/v1/push', ana.t, { token: tokenAna, proveedor: 'fcm' });

    // Se dispara con un EVENTO del servidor -beto la agrega a un grupo- y no
    // registrando un metadato de mensaje. La diferencia importa: registrar un
    // metadato no le empuja nada a nadie. El push se engancha en
    // `Hub.empujar`, que es el unico sitio del servidor que sabe la diferencia
    // entre "esta escuchando" e "ir a buscarlo".
    r = await call('POST', '/v1/conversaciones/grupo', beto.t, {
      nombre: 'Grupo push', usernames: [ana.user],
    });
    const conv = r.b?.conversacionId ?? r.b?.id;
    ck('se crea el grupo que la agrega', !!conv, JSON.stringify(r.b).slice(0, 140));

    // El envio va en un hilo aparte a proposito -no puede meterse en el tiempo
    // de respuesta de quien manda-, asi que se espera.
    let avisos = [];
    for (let i = 0; i < 25 && avisos.length === 0; i++) {
      await new Promise((res) => setTimeout(res, 200));
      const rr = await fetch(STUB + '/recibidos').then((x) => x.json()).catch(() => []);
      avisos = rr.filter((x) => x.ruta === 'send');
    }
    ck('llego un aviso al proveedor', avisos.length > 0, JSON.stringify(avisos).slice(0, 200));

    const hay = avisos.length > 0;
    const m = avisos[0]?.cuerpo?.message ?? {};
    ck('va dirigido al token de ana', m.token === tokenAna, String(m.token));
    ck('con prioridad alta: sin eso no despierta un proceso muerto',
       m.android?.priority === 'high', JSON.stringify(m.android));

    // LO IMPORTANTE DE TODO EL MODULO.
    ck('NO lleva bloque `notification`: el sistema no dibuja nada solo',
       hay && m.notification === undefined, JSON.stringify(m.notification));
    ck('el `data` tiene UNA clave que no significa nada',
       JSON.stringify(m.data) === '{"w":"1"}', JSON.stringify(m.data));

    // Ojo: estos tres solo valen si LLEGO un aviso. Sin aviso, "no viaja el
    // nombre" es trivialmente cierto y no prueba nada.
    const crudo = JSON.stringify(avisos[0]?.cuerpo ?? {});
    ck('no viaja el nombre de quien escribe', hay && !crudo.includes(beto.user), crudo.slice(0, 200));
    ck('no viaja el id de la conversacion', hay && !crudo.includes(conv), crudo.slice(0, 200));
    ck('no viaja el id del usuario', hay && !crudo.includes(ana.id), crudo.slice(0, 200));

    ck('el token de acceso se pidio con la cuenta de servicio',
       (avisos[0]?.autorizacion ?? '').startsWith('Bearer '), avisos[0]?.autorizacion);

    console.log('\n=== veinte mensajes no son veinte avisos ===');
    await fetch(STUB + '/limpiar', { method: 'POST' });
    // Seis eventos seguidos sobre el mismo aparato: renombrar el grupo avisa a
    // todos sus participantes, y ana sigue sin socket.
    for (let i = 0; i < 6; i++) {
      await call('PUT', `/v1/conversaciones/${conv}/config`, beto.t, {
        nombre: `Grupo push ${i}`,
      });
    }
    await new Promise((res) => setTimeout(res, 1500));
    const segunda = (await fetch(STUB + '/recibidos').then((x) => x.json()).catch(() => []))
      .filter((x) => x.ruta === 'send');
    // El aviso no lleva contenido, asi que el segundo no consigue nada que el
    // primero no haya conseguido ya: que el telefono se conecte y baje TODO.
    ck('se coalescen en cero o uno', hay && segunda.length <= 1, `fueron ${segunda.length}`);
  }
}

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
