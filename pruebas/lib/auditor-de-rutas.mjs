// Enumera las rutas del servidor leyendo el codigo, no una lista escrita a mano.
//
// ## Por que existe
//
// `ajeno.mjs` abria diciendo: «Un auditor de rutas sobre Main.kt da 127 rutas,
// 78 de ellas mutantes y 45 con un `{id}` en el camino». Ese numero estaba
// escrito en un COMENTARIO. No habia auditor: era una foto de un dia, y sus
// filas son una lista fija.
//
// O sea que toda ruta agregada despues quedaba fuera del barrido de acceso
// ajeno **y nadie se enteraba**. La garantia del §16 —«ninguna ruta resuelve un
// id sin comprobar quien lo pide»— valia para el codigo de aquel dia, no para
// el de hoy, y la suite seguia dando verde igual.
//
// Es la misma forma de defecto que este proyecto ya encontro tres veces: una
// capacidad que la API tiene y la interfaz no ofrece, una prueba que afirma
// sobre un contador y no sobre el contenido, un barrido que cierra la llamada y
// no avisa. Aqui: una garantia que no alcanza al codigo nuevo.
//
// Ahora el auditor LEE `Main.kt` y `ajeno.mjs` le pregunta. Si alguien agrega
// una ruta mutante con id y no la cubre ni la exime, la suite falla y dice
// cual.
//
// Vive en `lib/` y no junto a las suites porque es una BIBLIOTECA. El runner
// descubre suites listando `pruebas/` y no entra en subcarpetas; dejado
// arriba lo intentaba correr y lo marcaba ROTA, que es ruido sobre un archivo
// que nunca tuvo un resumen que imprimir.
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RAIZ = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const MAIN = join(RAIZ, 'server/src/main/kotlin/com/wtfuck/server/Main.kt');
const PROTOCOLO = join(RAIZ, 'protocol/src/main/kotlin/com/wtfuck/protocol');

/**
 * Las constantes `RUTA_*` del protocolo.
 *
 * Hacen falta porque la mitad de las rutas se declaran como `post(RUTA_PERFIL)`
 * o `post("$RUTA_LLAMADAS/{id}/contestar")`. Una expresion regular que solo
 * mire cadenas literales se pierde esas y da un numero que parece bien.
 */
function constantes() {
  const mapa = new Map();
  for (const archivo of readdirSync(PROTOCOLO)) {
    if (!archivo.endsWith('.kt')) continue;
    const texto = readFileSync(join(PROTOCOLO, archivo), 'utf8');
    for (const m of texto.matchAll(/const val (RUTA_\w+)\s*=\s*"([^"]*)"/g)) {
      mapa.set(m[1], m[2]);
    }
  }
  return mapa;
}

/** Resuelve la expresion de una ruta a un patron, o null si no se puede. */
function resolver(expr, consts) {
  const t = expr.trim();

  // "texto literal"
  const literal = t.match(/^"([^"$]*)"$/);
  if (literal) return literal[1];

  // CONSTANTE
  if (consts.has(t)) return consts.get(t);

  // "$CONSTANTE/resto" o "${CONSTANTE}/resto"
  const interp = t.match(/^"(.*)"$/);
  if (interp) {
    let dentro = interp[1];
    let pudo = true;
    dentro = dentro.replace(/\$\{?(\w+)\}?/g, (todo, nombre) => {
      // `{id}` de Ktor no es interpolacion: va entre llaves sin dolar.
      if (consts.has(nombre)) return consts.get(nombre);
      pudo = false;
      return todo;
    });
    if (pudo) return dentro;
  }
  return null;
}

/**
 * Todas las rutas declaradas en Main.kt.
 *
 * Devuelve `{ metodo, patron, linea, muta, conId }`. `muta` excluye GET porque
 * la lectura la miran `privacidad.mjs` y `ajeno-lectura.mjs`; `conId` marca las
 * que resuelven un objeto por el camino, que son la superficie del §16.
 */
export function rutas() {
  const consts = constantes();
  const texto = readFileSync(MAIN, 'utf8');
  const lineas = texto.split('\n');
  const salida = [];
  const sinResolver = [];

  lineas.forEach((linea, i) => {
    const m = linea.match(/^\s*(get|post|put|patch|delete)\((.+?)\)\s*\{/);
    if (!m) return;
    const metodo = m[1].toUpperCase();
    const patron = resolver(m[2], consts);
    if (patron === null) {
      sinResolver.push({ metodo, expr: m[2].trim(), linea: i + 1 });
      return;
    }
    salida.push({
      metodo,
      patron,
      linea: i + 1,
      muta: metodo !== 'GET',
      conId: patron.includes('{'),
    });
  });

  return { rutas: salida, sinResolver };
}

/**
 * Normaliza un camino concreto a un patron comparable.
 *
 * Una fila del barrido produce `/v1/conversaciones/018f.../config`; la ruta
 * declarada es `/v1/conversaciones/{id}/config`. Se reemplaza por `{}` todo
 * segmento que sea un identificador —un UUID, o algo que no parezca una
 * palabra fija del camino— y se compara la forma.
 */
export function normalizar(camino) {
  return camino
    .split('?')[0]
    .split('/')
    .map((seg) => {
      if (seg === '') return seg;
      if (/^\{.*\}$/.test(seg)) return '{}';
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(seg)) return '{}';
      return seg;
    })
    .join('/');
}

// Como programa: imprime el inventario. Util para mirar a mano que hay.
if (process.argv[1] && process.argv[1].endsWith('auditor-de-rutas.mjs')) {
  const { rutas: r, sinResolver } = rutas();
  const mutantes = r.filter((x) => x.muta);
  const conId = mutantes.filter((x) => x.conId);
  console.log(`${r.length} rutas · ${mutantes.length} mutantes · ${conId.length} mutantes con id`);
  if (sinResolver.length) {
    console.log(`\n${sinResolver.length} sin resolver (revisar a mano):`);
    for (const s of sinResolver) console.log(`  Main.kt:${s.linea}  ${s.metodo} ${s.expr}`);
  }
  console.log('\nMutantes con id:');
  for (const x of conId) console.log(`  ${x.metodo.padEnd(6)} ${normalizar(x.patron)}`);
}
