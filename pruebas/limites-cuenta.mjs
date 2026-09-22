// Modulo P.3 · Limites de ritmo en las escrituras de autoservicio.
//
// ## El hueco que esta suite cierra
//
// La auditoria del modulo P lo dejo anotado como "resolver antes de abrir la
// beta": `PUT /v1/cuenta/tipo` y `PUT /v1/cuenta/empresa` no tenian ningun
// limite de ritmo, y la maquinaria ya existia y se usaba en cinco archivos
// (`Dispositivos`, `Identidad`, `Llamadas`, `Main`, `Moderacion`). Aqui
// simplemente no se habia enchufado.
//
// ## Por que importa en ESTA ruta y no es burocracia
//
// La ficha es **texto publico que otros leen**, y cada guardado escribe una
// fila de auditoria y retira la verificacion. Sin tope pasan tres cosas:
//
//   1. Un script llena `auditoria` a la velocidad de la red.
//   2. Un nombre comercial se puede rotar mas rapido de lo que alguien
//      modera: se denuncia "Banco Nacional", y cuando el moderador abre el
//      caso la ficha dice otra cosa. **Eso es evadir la moderacion cambiando
//      el dato denunciado**, y es el motivo principal del cupo diario.
//   3. Alternar normal <-> empresa borra y recrea la ficha en cada vuelta.
//
// ## Dos limites, y la division no es por importancia
//
//   - **Rafaga** (`Limitador`, en memoria): para el script. Un reinicio
//     perdona la rafaga en curso y eso esta bien; dura segundos.
//   - **Cupo diario** (`Cupos`, en la base): para la rotacion sostenida. Aqui
//     un reinicio NO puede perdonar, porque entonces bastaria esperar un
//     despliegue. Es la misma razon que el cupo de denuncias.
//
// Uso:  node pruebas/limites-cuenta.mjs

const BASE = process.env.WTFUCK_BASE ?? 'http://localhost:8300';
const S = Math.random().toString(36).slice(2, 7);
let ok = 0, fail = 0;
const ck = (n, c, x = '') => { c ? (ok++, console.log('  PASA  ' + n)) : (fail++, console.log('  FALLA ' + n + '  ' + x)); };
const b64 = (s) => Buffer.from(s).toString('base64');

let n = 0;
async function reg(u) {
  const nom = u + S + (n++);
  const r = await fetch(BASE + '/v1/registro', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: nom, password: 'clave-larga-123', etiquetaDispositivo: 't',
      identidadPub: b64('k' + nom), hardwareHash: b64('HW-' + nom), hardwareNivel: 'SOFTWARE_DEV',
    }),
  });
  const j = await r.json();
  return { t: j.token, id: j.usuarioId, user: nom };
}

const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
const call = async (m, ruta, t, body) => {
  const r = await fetch(BASE + ruta, {
    method: m, headers: H(t), body: body === undefined ? undefined : JSON.stringify(body),
  });
  const txt = await r.text();
  let b = null;
  try { b = JSON.parse(txt); } catch { b = txt; }
  return { s: r.status, b };
};
const get = (r, t) => call('GET', r, t);
const put = (r, t, b) => call('PUT', r, t, b);

const { execSync } = await import('node:child_process');
const sql = (q) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -t -c "${q}"`,
  { encoding: 'utf8' },
).trim();

const BETA = 'beta_pruebas';

/** Guarda la ficha `veces` veces y devuelve cuantas respondieron cada cosa. */
async function martillar(quien, veces, ruta, cuerpo) {
  const cuenta = {};
  for (let i = 0; i < veces; i++) {
    const r = await put(ruta, quien.t, cuerpo(i));
    cuenta[r.s] = (cuenta[r.s] ?? 0) + 1;
    // Se corta en el primer 429: lo que se mide es SI aparece, no cuantos.
    if (r.s === 429) { cuenta.mensaje = r.b?.motivo ?? ''; cuenta.enElIntento = i + 1; break; }
  }
  return cuenta;
}

// ---------------------------------------------------------------------------
//  Siembra
// ---------------------------------------------------------------------------
console.log('\n=== siembra ===');

// Cuenta nueva con el username fijo de la beta. Nueva a proposito: el limitador
// de rafaga vive en memoria y se indexa por usuario_id, asi que una cuenta
// recien creada empieza con el presupuesto entero y esta suite no depende del
// orden en que corran las demas.
sql(`DELETE FROM usuario WHERE username = '${BETA}'`);
const emp = await reg('lc');
sql(`UPDATE usuario SET username = '${BETA}' WHERE username = '${emp.user}'`);
emp.user = BETA;

const puerta = await get('/v1/cuenta/tipo', emp.t);
if (puerta.b?.puedeElegirTipo !== true) {
  console.log('\n=== OMITIDA: el servidor no tiene beta_pruebas en WTFUCK_CUENTAS_BETA ===');
  console.log('    Levantalo con pruebas/arrancar-servidor.ps1, que ya la incluye.');
  process.exit(0);
}
ck('hay una cuenta dentro de la beta', !!emp.t);

// El administrador se crea aqui y no al final porque la seccion 2 lo necesita:
// el limite de rafaga vive en memoria y no hay ruta para vaciarlo, asi que para
// poder llegar al cupo DIARIO hay que subir el de rafaga desde el panel.
const staff = await reg('ls');
sql(`UPDATE usuario SET staff_nivel = 80 WHERE username = '${staff.user}'`);
ck('y un administrador para el panel de limites', !!staff.t);

// No se comprueba que el limite de una cuenta no afecte a otra, y es por una
// razon y no por olvido: las unicas dos cuentas de la beta son el propietario
// y esta, y cualquier otra recibe 404 en estas rutas, asi que un 404 no
// probaria nada sobre el limitador. El aislamiento es estructural -la clave es
// `usuarioId.toString()`, igual que en las otras cinco rutas limitadas- y lo
// cubre `AutorizacionTest` por el lado del limitador.

// ---------------------------------------------------------------------------
//  1 · La rafaga de fichas se corta
// ---------------------------------------------------------------------------
console.log('\n=== 1 · guardar la ficha en bucle ===');

let r = await put('/v1/cuenta/tipo', emp.t, { tipo: 'empresa' });
ck('la cuenta se declara empresa', r.s === 200, JSON.stringify(r.b));

// 45 guardados seguidos. Ninguna persona hace esto; un script lo hace en un
// segundo. El nombre cambia en cada vuelta para que sea el caso peor: rotar el
// dato que otros leen.
const fichas = await martillar(emp, 45, '/v1/cuenta/empresa', (i) => ({
  nombreComercial: `Rotando ${i}`, categoria: 'otra',
}));
ck('en algun momento responde 429 y no 200 indefinidamente',
   fichas[429] > 0, JSON.stringify(fichas));
ck('y el 429 dice cuanto esperar, no solo que no',
   /segundos|limite/i.test(fichas.mensaje ?? ''), String(fichas.mensaje));
// Que corte DESPUES de unos cuantos es la otra mitad: un limite que salta al
// tercer guardado convierte corregir una errata en un error.
ck('pero no antes de que se pueda usar de verdad (>= 10 guardados)',
   (fichas.enElIntento ?? 0) >= 10, String(fichas.enElIntento));

// ---------------------------------------------------------------------------
//  2 · El cupo diario vive en la base
// ---------------------------------------------------------------------------
console.log('\n=== 2 · el cupo que un reinicio no perdona ===');

// **Primero se aparta el limite de rafaga.** La seccion anterior agoto su
// ventana de diez minutos, y mientras este agotada responde el 429 de rafaga y
// el cupo diario no llega a evaluarse nunca. Se sube desde el panel, que es la
// via legitima, y se restaura al final de la seccion.
//
// Que esto haga falta dice algo del diseno y esta bien: los dos limites estan
// en serie, y el mas corto contesta primero.
r = await put(`/v1/panel/limites/ficha_empresa`, staff.t,
              { tope: 100000, ventanaSegundos: 600 });
ck('el panel sube el limite de rafaga de la ficha', r.s === 200, JSON.stringify(r.b));

// El limitador de rafaga es en memoria; el cupo diario es una fila. Se
// comprueba que la fila exista y cuente, porque es lo que hace que reiniciar el
// servidor no regale el cupo.
const contados = sql(
  `SELECT coalesce(sum(n),0) FROM contador_uso cu JOIN usuario u ON u.id = cu.usuario_id ` +
  `WHERE u.username = '${BETA}' AND cu.accion = 'ficha_empresa'`,
);
ck('los guardados quedaron contados en la base', Number(contados) > 0, contados);

// Se empuja el contador por encima del tope a mano en vez de mandar mil
// peticiones: lo que hay que probar es que el TOPE corta, no que la red aguanta.
sql(
  `UPDATE contador_uso SET n = 100000 WHERE accion = 'ficha_empresa' AND usuario_id = ` +
  `(SELECT id FROM usuario WHERE username = '${BETA}')`,
);
r = await put('/v1/cuenta/empresa', emp.t, { nombreComercial: 'Pasado el cupo', categoria: 'otra' });
ck('con el contador diario pasado, se rechaza', r.s === 429, `${r.s} ${JSON.stringify(r.b)}`);
ck('y el mensaje habla del dia, no de segundos',
   /dia/i.test(r.b?.motivo ?? ''), String(r.b?.motivo));

// Y la ficha NO se guardo: un 429 que igual escribe es peor que ninguno.
const nombreAhora = sql(
  `SELECT e.nombre_comercial FROM perfil_empresa e JOIN usuario u ON u.id = e.usuario_id ` +
  `WHERE u.username = '${BETA}'`,
);
ck('el rechazo no dejo escrito el nombre nuevo',
   nombreAhora !== 'Pasado el cupo', nombreAhora);

// El limite excedido se registra: una rafaga aislada es un dedo pesado, la
// misma rafaga cien veces al dia es otra cosa, y sin registro se ven igual.
const eventos = sql(
  `SELECT count(*) FROM evento_seguridad ev JOIN usuario u ON u.id = ev.usuario_id ` +
  `WHERE u.username = '${BETA}' AND ev.tipo = 'limite_excedido'`,
);
ck('y queda constancia en el registro de seguridad', Number(eventos) > 0, eventos);

// Se deja el limite como estaba: un ajuste de prueba que se queda puesto es un
// limite desactivado en el proximo arranque, y nadie se acordaria de por que.
r = await call('DELETE', '/v1/panel/limites/ficha_empresa', staff.t);
ck('y el limite de fabrica se restaura', r.s === 204, String(r.s));

// ---------------------------------------------------------------------------
//  3 · Cambiar de tipo tiene su propio presupuesto
// ---------------------------------------------------------------------------
console.log('\n=== 3 · alternar el tipo de cuenta ===');

// Presupuesto aparte del de la ficha: si compartieran cuenta, agotar uno
// bloquearia el otro y "guarde mi ficha muchas veces" impediria volver a
// cuenta personal, que es justamente como uno se quita la ficha de encima.
sql(
  `DELETE FROM contador_uso WHERE usuario_id = ` +
  `(SELECT id FROM usuario WHERE username = '${BETA}')`,
);
r = await put('/v1/cuenta/tipo', emp.t, { tipo: 'normal' });
ck('volver a personal sigue funcionando con la ficha agotada', r.s === 200, JSON.stringify(r.b));

const tipos = await martillar(emp, 30, '/v1/cuenta/tipo',
  (i) => ({ tipo: i % 2 === 0 ? 'empresa' : 'normal' }));
ck('alternar el tipo en bucle tambien se corta', tipos[429] > 0, JSON.stringify(tipos));
ck('y no antes de un par de cambios legitimos (>= 5)',
   (tipos.enElIntento ?? 0) >= 5, String(tipos.enElIntento));

// ---------------------------------------------------------------------------
//  4 · Los dos limites se pueden ver y ajustar desde el panel
// ---------------------------------------------------------------------------
console.log('\n=== 4 · visibles en el panel de limites ===');

// Un limite que no aparece en el panel es un numero que solo se puede cambiar
// desplegando. Los otros catorce estan ahi; estos dos tambien.
r = await get('/v1/panel/limites', staff.t);
const claves = (r.b?.limites ?? r.b ?? []).map?.((x) => x.clave) ?? [];
ck('el panel de limites se lee', r.s === 200, `${r.s} ${JSON.stringify(r.b).slice(0, 160)}`);
ck('y lista el limite de la ficha', claves.includes('ficha_empresa'), JSON.stringify(claves));
ck('y el de cambiar el tipo', claves.includes('tipo_cuenta'), JSON.stringify(claves));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
