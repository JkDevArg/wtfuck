// Nivel de hardware · lo que el servidor acepta cuando nadie le dice nada.
//
// ## Que se comprueba, y por que hace falta una SEGUNDA instancia
//
// `WTFUCK_PERMITIR_SOFTWARE_DEV` decide si se aceptan aparatos `SOFTWARE_DEV`
// (sin enclave seguro: emuladores). Hasta el 2026-10-08 valia `true` cuando la
// variable no estaba definida: un despliegue que se la olvidara quedaba abierto
// a emuladores sin que nada lo dijera. Fail-open. Ahora el defecto es `false` y
// solo `arrancar-servidor.ps1` la pone en `true`, a la vista.
//
// Eso no se puede ver contra la instancia de siempre (8300), que es la de
// desarrollo y la tiene en `true` A PROPOSITO -sin eso ninguna otra suite podria
// registrarse-. Hace falta una instancia levantada SIN la variable, que es
// exactamente el caso del despliegue olvidadizo:
//
//   .\pruebas\arrancar-servidor.ps1 -Puerto 8310 -SinEmuladores
//   node pruebas/nivel-hardware.mjs
//
// ## La seccion 3 fija un LIMITE, no una virtud
//
// El servidor no verifica ninguna atestacion: el nivel es el que el cliente
// DECLARA. Un cliente modificado escribe "TEE" y entra aunque el interruptor
// este en `false`. La seccion 3 lo comprueba a proposito para que nadie pueda
// leer docs/04-DEVICE-BINDING.md y creer otra cosa. Si algun dia se implementa
// la verificacion de la cadena, esa seccion TIENE que fallar y reescribirse:
// es la alarma que impide que el codigo y la documentacion vuelvan a separarse.
//
// Si la segunda instancia no responde, la suite se omite y lo dice.
const DEV = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const PROD = process.env.WTFUCK_BASE_PROD ?? 'http://localhost:8310';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

async function registrar(base, u, nivel) {
  const r = await fetch(base + '/v1/registro', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: u, password: 'clave-larga-123', etiquetaDispositivo: 'nivel ' + u,
      // Material inventado de punta a punta: ni la clave ni el hash vienen de
      // ningun Keystore. Es lo que mandaria un cliente modificado.
      identidadPub: b64('k-inventada-' + u), hardwareHash: b64('HW-nivel-' + u + S),
      hardwareNivel: nivel,
    }),
  });
  const txt = await r.text();
  let b = null;
  try { b = txt ? JSON.parse(txt) : null; } catch { b = txt; }
  return { s: r.status, b };
}

let hayProd = true;
try {
  const r = await fetch(PROD + '/salud');
  if (!r.ok) throw new Error('respondio ' + r.status);
} catch {
  hayProd = false;
}

if (!hayProd) {
  console.log('\n=== OMITIDA: no hay una instancia sin la variable ===');
  console.log(`    ${PROD} no responde. Para correr esta suite:`);
  console.log('    .\\pruebas\\arrancar-servidor.ps1 -Puerto 8310 -SinEmuladores');
  console.log('\n=== 0 pasan, 0 fallan ===');
  process.exit(0);
}

console.log('\n=== 1. desarrollo (8300): el script la pone en true, a la vista ===');
const dev = await registrar(DEV, `nd${S}`, 'SOFTWARE_DEV');
ck('un emulador se registra en la instancia de desarrollo', dev.s === 200 && dev.b?.token, `status=${dev.s} ${JSON.stringify(dev.b)}`);

console.log('\n=== 2. sin la variable: el defecto CIERRA ===');
const sw = await registrar(PROD, `np${S}`, 'SOFTWARE_DEV');
ck('SOFTWARE_DEV sin la variable -> 403', sw.s === 403, `status=${sw.s} ${JSON.stringify(sw.b)}`);
ck('el motivo lo dice', typeof sw.b?.motivo === 'string' && /enclave/i.test(sw.b.motivo), JSON.stringify(sw.b));
// Que el rechazo no deje nada a medias: el username sigue libre.
const sw2 = await registrar(PROD, `np${S}`, 'SOFTWARE_DEV');
ck('el rechazo no reserva el username (mismo 403, no 409)', sw2.s === 403, `status=${sw2.s}`);
const nav = await registrar(PROD, `nn${S}`, 'NAVEGADOR');
ck('un navegador sigue sin poder crear cuentas -> 403', nav.s === 403, `status=${nav.s}`);
const raro = await registrar(PROD, `nr${S}`, 'CUALQUIERA');
ck('un nivel desconocido -> 400', raro.s === 400, `status=${raro.s}`);

console.log('\n=== 3. LIMITE CONOCIDO: el nivel es declarado, no verificado ===');
// Ver docs/04-DEVICE-BINDING.md, "Lo que el servidor verifica hoy". Si esto
// empieza a fallar porque se implemento la atestacion, es la buena noticia:
// reescribir esta seccion y el documento en el mismo commit.
const tee = await registrar(PROD, `nt${S}`, 'TEE');
ck('un "TEE" declarado sin cadena de atestacion se ACEPTA (limite, no virtud)', tee.s === 200 && tee.b?.token, `status=${tee.s} ${JSON.stringify(tee.b)}`);
const sb = await registrar(PROD, `ns${S}`, 'STRONGBOX');
ck('un "STRONGBOX" declarado tambien', sb.s === 200 && sb.b?.token, `status=${sb.s}`);

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
