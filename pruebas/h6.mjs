// H.6: limites ajustables y bitacora.
//
// Lo que se comprueba, en orden de importancia:
//
//  1. Que un limite ajustado SURTA EFECTO de verdad. Una pantalla que guarda
//     un numero que el limitador no lee es peor que no tener la pantalla.
//  2. Que no se pueda apagar la defensa: ni tope 0, ni ventana de un mes.
//  3. Que restaurar vuelva al valor de fabrica y no a "sin limite".
//  4. Que el nivel importe: un moderador no cambia limites de plataforma.
//  5. Que cambiar un limite quede en la bitacora, con nombre.
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
const del = (r, t) => call('DELETE', r, t);
const post = (r, t, b) => call('POST', r, t, b);

const sembrar = (username, nivel) => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c ` +
  `"UPDATE usuario SET staff_nivel=${nivel} WHERE username='${username}'"`,
  { stdio: 'pipe' },
);
const limpiarConfig = () => execSync(
  `docker exec wtfuck_db psql -U wtfuck -d wtfuck -q -c "DELETE FROM limite_config"`,
  { stdio: 'pipe' },
);

limpiarConfig();

const admin = await reg('ha');
sembrar(admin.user, 80);
const mod = await reg('hb');
sembrar(mod.user, 50);
const cualquiera = await reg('hc');

console.log('\n=== quien puede ver y tocar los limites ===');
let r = await get('/v1/panel/limites', cualquiera.t);
ck('un usuario cualquiera no ve los limites', r.s === 404, String(r.s));

r = await get('/v1/panel/limites', mod.t);
ck('un moderador (50) tampoco: son infraestructura, no moderacion', r.s === 404, String(r.s));

r = await get('/v1/panel/limites', admin.t);
ck('un administrador (80) si', r.s === 200, String(r.s));
const lista = r.b.limites || [];
ck('vienen todos los ajustables', lista.length >= 14, String(lista.length));

const msg = lista.find((l) => l.clave === 'enviar_mensaje');
ck('cada uno trae su valor y su valor de fabrica',
   msg && msg.tope === msg.topeDefecto && msg.esDefecto === true, JSON.stringify(msg));
ck('y su etiqueta, para que la pantalla no muestre claves internas',
   (msg?.etiqueta || '').length > 3, msg?.etiqueta);

console.log('\n=== no se puede apagar la defensa ===');
r = await put('/v1/panel/limites/enviar_mensaje', admin.t, { tope: 0, ventanaSegundos: 60 });
ck('tope 0 se rechaza', r.s === 400, String(r.s));

r = await put('/v1/panel/limites/enviar_mensaje', admin.t, { tope: 10, ventanaSegundos: 0 });
ck('ventana 0 se rechaza', r.s === 400, String(r.s));

r = await put('/v1/panel/limites/enviar_mensaje', admin.t, { tope: 10, ventanaSegundos: 999999 });
ck('una ventana de dias se rechaza', r.s === 400, String(r.s));

r = await put('/v1/panel/limites/no_existe', admin.t, { tope: 10, ventanaSegundos: 60 });
ck('una clave inventada se rechaza', r.s === 404, String(r.s));

r = await put('/v1/panel/limites/enviar_mensaje', mod.t, { tope: 10, ventanaSegundos: 60 });
ck('y un moderador no puede ajustar', r.s === 404, String(r.s));

console.log('\n=== lo importante: el limite ajustado SURTE EFECTO ===');
// Se aprieta el limite de busqueda a 2 por minuto y se comprueba en la ruta
// real. Sin esta prueba, la pantalla podria estar guardando numeros que el
// limitador nunca lee, que es el fallo silencioso clasico de una config.
r = await put('/v1/panel/limites/buscar', admin.t, { tope: 2, ventanaSegundos: 60 });
ck('se ajusta el limite de busqueda a 2/min', r.s === 200, String(r.s));
ck('y responde que ya no es el de fabrica', r.b.esDefecto === false);
ck('con quien lo cambio', r.b.actualizadoPor === admin.user, r.b.actualizadoPor);

// La cache del limitador es de 30 s; el ajuste la invalida en el proceso.
const victima = await reg('hd');
r = await get('/v1/canales/buscar?q=zzz', victima.t);
ck('primera busqueda pasa', r.s === 200, String(r.s));
r = await get('/v1/canales/buscar?q=zzz', victima.t);
ck('segunda busqueda pasa', r.s === 200, String(r.s));
r = await get('/v1/canales/buscar?q=zzz', victima.t);
ck('la TERCERA se rechaza con 429: el limite nuevo esta vivo', r.s === 429, String(r.s));
ck('y dice cuanto esperar', (r.b?.motivo || '').includes('segundos'), r.b?.motivo);

console.log('\n=== restaurar vuelve al valor probado ===');
r = await del('/v1/panel/limites/buscar', admin.t);
ck('se restaura', r.s === 204, String(r.s));

r = await get('/v1/panel/limites', admin.t);
const b = (r.b.limites || []).find((l) => l.clave === 'buscar');
ck('vuelve a ser el de fabrica', b?.esDefecto === true && b?.tope === b?.topeDefecto,
   JSON.stringify(b));
ck('y ese valor de fabrica NO es "sin limite"', (b?.tope || 0) > 0 && (b?.ventanaDefectoSegundos || 0) > 0);

// Otro usuario, para no arrastrar las marcas del anterior.
const otra = await reg('he');
r = await get('/v1/canales/buscar?q=zzz', otra.t);
ck('y con el limite restaurado se puede buscar de nuevo', r.s === 200, String(r.s));

console.log('\n=== la bitacora ===');
r = await get('/v1/panel/bitacora', cualquiera.t);
ck('no la ve cualquiera', r.s === 404, String(r.s));

r = await get('/v1/panel/bitacora', mod.t);
ck('ni un moderador: dice lo que hizo cada moderador', r.s === 404, String(r.s));

r = await get('/v1/panel/bitacora?q=limite', admin.t);
ck('el administrador si', r.s === 200, String(r.s));
const lineas = r.b.lineas || [];
ck('el ajuste quedo anotado', lineas.some((l) => l.accion === 'limite.ajustado'),
   JSON.stringify(lineas.slice(0, 2)));
ck('y la restauracion tambien', lineas.some((l) => l.accion === 'limite.restaurado'));
const linea = lineas.find((l) => l.accion === 'limite.ajustado');
ck('con nombre y fecha: relajar un limite no es anonimo',
   linea?.actor === admin.user && linea?.creadoEn > 0, JSON.stringify(linea));
ck('y con el detalle de que se cambio', (linea?.detalle || '').includes('buscar'), linea?.detalle);

r = await get('/v1/panel/bitacora?q=' + admin.user, admin.t);
ck('se puede filtrar por quien lo hizo', (r.b.lineas || []).length > 0, String((r.b.lineas || []).length));

r = await get('/v1/panel/bitacora?q=zzznoexistezzz', admin.t);
ck('y un filtro sin resultados devuelve vacio, no todo',
   (r.b.lineas || []).length === 0, String((r.b.lineas || []).length));

console.log(`\n=== ${ok} pasan, ${fail} fallan ===`);
process.exit(fail === 0 ? 0 : 1);
