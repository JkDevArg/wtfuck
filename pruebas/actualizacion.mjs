// La version publicada (modulo BD).
//
// Corre contra CUALQUIER servidor y hace lo que puede:
//
//   - Sin version configurada: comprueba que contesta apagado y que no filtra
//     una URL a medio poner.
//   - Con `WTFUCK_APK_VERSION` puesta: ademas el contenido y los recortes.
//
// Cuando la parte de "con version" no se prueba, lo DICE.
//
// Para el completo:
//   WTFUCK_PUERTO=8303 WTFUCK_APK_VERSION=7 WTFUCK_APK_NOMBRE=0.7.0 \
//     WTFUCK_APK_URL=https://ejemplo/wtfuck.apk WTFUCK_APK_SHA256=$(printf 'a%.0s' {1..64}) \
//     WTFUCK_APK_MINIMA=99 WTFUCK_APK_NOTAS='probando' \
//     server/build/install/server/bin/server
//   WTFUCK_BASE=http://127.0.0.1:8303 node pruebas/actualizacion.mjs

const BASE = process.env.WTFUCK_BASE ?? process.env.WTFUCK_URL ?? 'http://localhost:8300';
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };

async function get(ruta, token) {
  const h = {};
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + ruta, { headers: h });
  let j = null;
  try { j = await r.json(); } catch { /* sin cuerpo */ }
  return { estado: r.status, cuerpo: j };
}

// ==================================================================
//  Sin autenticar, y es el punto
// ==================================================================

const v = await get('/v1/version');

// La razon de ser de esta ruta: tiene que contestarle a una app tan vieja que
// ya no puede entrar. Si un cambio de protocolo la dejo fuera, "actualizate"
// es justo la respuesta que necesita, y no puede depender de un login que ya
// no le funciona. Detras de `autenticar()` esta ruta no serviria para nada.
ck('se consulta SIN sesion', v.estado === 200, `vino ${v.estado}`);
ck('trae un versionCode numerico', typeof v.cuerpo?.versionCode === 'number',
   JSON.stringify(v.cuerpo));

// Con una cuenta tambien, claro: la app normal la consulta ya autenticada y
// una ruta que solo funcione sin token seria una trampa distinta.
const conToken = await get('/v1/version', 'basura-que-no-es-un-token');
ck('un token invalido no la rompe', conToken.estado === 200, `vino ${conToken.estado}`);

const publicando = (v.cuerpo?.versionCode ?? 0) > 0;
console.log(`  (servidor ${publicando ? 'PUBLICANDO version ' + v.cuerpo.versionCode : 'SIN version configurada'})`);

// ==================================================================
//  Apagado por defecto
// ==================================================================

if (!publicando) {
  // Apagado tiene que significar apagado del todo. Si el servidor devolviera
  // la URL y la huella de un APK que no anuncia, estaria publicando por
  // accidente lo que alguien dejo a medio configurar.
  ck('apagado no filtra la URL', !v.cuerpo?.url, v.cuerpo?.url);
  ck('apagado no filtra la huella', !v.cuerpo?.sha256, v.cuerpo?.sha256);
  ck('apagado no declara ninguna minima', (v.cuerpo?.minima ?? 0) === 0,
     String(v.cuerpo?.minima));

  console.log('');
  console.log('  ---- el contenido de una publicacion NO se probo ----');
  console.log('  Este servidor no tiene WTFUCK_APK_VERSION. Ver la cabecera.');
  console.log('');
} else {
  // ================================================================
  //  Con version publicada
  // ================================================================
  const c = v.cuerpo;

  ck('trae un nombre de version', typeof c.versionName === 'string' && c.versionName.length > 0,
     c.versionName);

  // https y no http: el APK se baja de ahi y se instala. Por http, cualquiera
  // en el camino elige que se descarga. Android rechazaria una firma distinta,
  // pero eso convierte un ataque en un fallo de instalacion — y deja igual de
  // rota la actualizacion.
  ck('la URL va por https', c.url.startsWith('https://'), c.url);

  // 64 hex. No es la defensa principal -esa es la firma, que comprueba
  // Android- pero una huella mal puesta hace que NINGUNA descarga cuadre y la
  // actualizacion no funcione para nadie, en silencio.
  ck('la huella es un SHA-256 completo', /^[0-9a-f]{64}$/.test(c.sha256 ?? ''),
     `largo ${c.sha256?.length}`);

  // El recorte que importa: una minima MAYOR que la publicada deja a todo el
  // mundo fuera, incluida la version que se acaba de subir. Es un error de
  // dedo facil de cometer -poner el numero de build en la casilla de al lado-
  // e imposible de diagnosticar desde el telefono, donde solo se ve
  // "tienes que actualizar" contra una version que ya es la ultima.
  ck('la minima nunca pasa a la publicada', c.minima <= c.versionCode,
     `minima ${c.minima} > publicada ${c.versionCode}`);
}

console.log(`\n${'='.repeat(46)}\n  PASAN: ${ok}   FALLAN: ${fail}\n${'='.repeat(46)}\n`);
process.exit(fail === 0 ? 0 : 1);
