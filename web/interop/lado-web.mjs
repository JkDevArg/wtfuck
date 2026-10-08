// El lado "web" de la prueba de interoperabilidad W0.
//
// Carga el MISMO WebAssembly que usará el navegador (web/cripto/pkg, armado
// con `--target web`) y atiende pedidos de a uno por línea en stdin, en JSON.
// Contesta una línea JSON por pedido en stdout. Lo maneja la prueba de la JVM
// (web/interop-jvm), que hace de teléfono con la libsignal oficial.
//
// Formato: {"id":1,"op":"cifrar","nombre":"web",...} -> {"id":1,"ok":{...}}
// o {"id":1,"error":"motivo"}. Los bytes viajan en base64.
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const PKG = join(AQUI, '..', 'cripto', 'pkg');

const modulo = await import(new URL(`file:///${join(PKG, 'wtfuck_cripto.js').replace(/\\/g, '/')}`));
modulo.initSync({ module: readFileSync(join(PKG, 'wtfuck_cripto_bg.wasm')) });
const { Cliente, versionLibsignal } = modulo;

const b64 = (u8) => Buffer.from(u8).toString('base64');
const deB64 = (s) => new Uint8Array(Buffer.from(s, 'base64'));

const clientes = new Map();
const cliente = (nombre) => {
  const c = clientes.get(nombre);
  if (!c) throw new Error(`no hay cliente "${nombre}"`);
  return c;
};

const ops = {
  version: () => ({ libsignal: versionLibsignal() }),
  nuevo: ({ nombre }) => {
    const c = new Cliente();
    clientes.set(nombre, c);
    return { identidad: c.identidad(), registrationId: c.registrationId() };
  },
  claves: ({ nombre, firmadaId, kyberId, desde, cuantas }) =>
    JSON.parse(cliente(nombre).generarClaves(firmadaId, kyberId, desde, cuantas, Date.now())),
  abrirSesion: ({ nombre, dispositivo, paquete }) => {
    cliente(nombre).abrirSesion(dispositivo, JSON.stringify(paquete), Date.now());
    return {};
  },
  tieneSesion: ({ nombre, dispositivo }) => ({ si: cliente(nombre).tieneSesion(dispositivo) }),
  cifrar: ({ nombre, dispositivo, claro }) =>
    JSON.parse(cliente(nombre).cifrar(dispositivo, deB64(claro), Date.now())),
  descifrar: ({ nombre, dispositivo, tipo, cuerpo }) =>
    ({ claro: b64(cliente(nombre).descifrar(dispositivo, tipo, cuerpo)) }),
  crearDistribucion: ({ nombre, miDispositivo, distId }) =>
    ({ skdm: cliente(nombre).crearDistribucion(miDispositivo, distId) }),
  procesarDistribucion: ({ nombre, remitente, skdm }) => {
    cliente(nombre).procesarDistribucion(remitente, skdm);
    return {};
  },
  cifrarGrupo: ({ nombre, miDispositivo, distId, claro }) =>
    ({ cuerpo: cliente(nombre).cifrarGrupo(miDispositivo, distId, deB64(claro)) }),
  descifrarGrupo: ({ nombre, remitente, cuerpo }) =>
    ({ claro: b64(cliente(nombre).descifrarGrupo(remitente, cuerpo)) }),
  huella: ({ nombre, miUsuario, otroUsuario, otroDispositivo }) =>
    JSON.parse(cliente(nombre).huella(miUsuario, otroUsuario, otroDispositivo)),
};

const lineas = createInterface({ input: process.stdin });
for await (const linea of lineas) {
  if (!linea.trim()) continue;
  let id = null;
  try {
    const p = JSON.parse(linea);
    id = p.id;
    const f = ops[p.op];
    if (!f) throw new Error(`operación desconocida: ${p.op}`);
    process.stdout.write(JSON.stringify({ id, ok: f(p) }) + '\n');
  } catch (e) {
    process.stdout.write(JSON.stringify({ id, error: String(e?.message ?? e) }) + '\n');
  }
}
