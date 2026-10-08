// Lo que la web guarda en el navegador, cifrado.
//
// ## Qué protege y qué no
//
// Todo lo que se guarda (el almacén de Signal con las claves privadas, los
// chats, los mensajes, el token) va cifrado con AES-256-GCM, con una clave de
// WebCrypto creada como NO EXPORTABLE y guardada en IndexedDB como objeto.
//
// - Protege contra quien copie el perfil del navegador o lea IndexedDB desde
//   fuera: ve ruido, y la clave no se puede sacar como bytes.
// - NO protege contra código que corra dentro de esta página: ese puede usar
//   la clave igual que nosotros. Es el precio declarado de la versión web
//   (docs/12-VERSION-WEB.md), y por eso la CSP del servidor es tan estricta.
//
// ## Por qué una clave y no una contraseña
//
// Pedir una contraseña cada vez que se abre la pestaña haría la web
// inutilizable. La sesión del navegador ya caduca a los 30 días y el teléfono
// la revoca cuando quiera.
import { openDB, type IDBPDatabase } from 'idb';

const BASE = 'wtfuck-web';
const VERSION = 1;

export type Tabla = 'cuenta' | 'conversaciones' | 'mensajes';

interface Cifrado {
  iv: Uint8Array;
  datos: ArrayBuffer;
}

let db: Promise<IDBPDatabase> | null = null;
let clave: Promise<CryptoKey> | null = null;

function abrir(): Promise<IDBPDatabase> {
  db ??= openDB(BASE, VERSION, {
    upgrade(d) {
      d.createObjectStore('llave');
      d.createObjectStore('cuenta');
      d.createObjectStore('conversaciones');
      // Clave "conversacion|creadoEn|id": se recorren en orden sin descifrar.
      d.createObjectStore('mensajes');
    },
  });
  return db;
}

async function llave(): Promise<CryptoKey> {
  clave ??= (async () => {
    const d = await abrir();
    const vieja = (await d.get('llave', 'aes')) as CryptoKey | undefined;
    if (vieja) return vieja;
    const nueva = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    await d.put('llave', nueva, 'aes');
    return nueva;
  })();
  return clave;
}

const enc = new TextEncoder();
const dec = new TextDecoder();

async function cerrar(valor: unknown): Promise<Cifrado> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const datos = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await llave(), enc.encode(JSON.stringify(valor)));
  return { iv, datos };
}

async function abrirCifrado<T>(c: Cifrado): Promise<T> {
  const plano = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: c.iv as BufferSource }, await llave(), c.datos);
  return JSON.parse(dec.decode(plano)) as T;
}

export async function guardar(tabla: Tabla, clave: string, valor: unknown): Promise<void> {
  await (await abrir()).put(tabla, await cerrar(valor), clave);
}

export async function leer<T>(tabla: Tabla, clave: string): Promise<T | undefined> {
  const c = (await (await abrir()).get(tabla, clave)) as Cifrado | undefined;
  return c ? abrirCifrado<T>(c) : undefined;
}

export async function borrar(tabla: Tabla, clave: string): Promise<void> {
  await (await abrir()).delete(tabla, clave);
}

/** Todas las filas cuya clave empieza con `prefijo`, en orden de clave. */
export async function leerDesde<T>(tabla: Tabla, prefijo: string): Promise<T[]> {
  const d = await abrir();
  const rango = IDBKeyRange.bound(prefijo, prefijo + '￿');
  const filas = (await d.getAll(tabla, rango)) as Cifrado[];
  return Promise.all(filas.map((f) => abrirCifrado<T>(f)));
}

export async function todas<T>(tabla: Tabla): Promise<T[]> {
  const filas = (await (await abrir()).getAll(tabla)) as Cifrado[];
  return Promise.all(filas.map((f) => abrirCifrado<T>(f)));
}

/** Cerrar sesión o perder la vinculación: no queda nada, ni la llave. */
export async function vaciarTodo(): Promise<void> {
  const d = await abrir();
  for (const t of ['llave', 'cuenta', 'conversaciones', 'mensajes']) await d.clear(t);
  clave = null;
}
