// El código de recuperación, igual que `CodigoRecuperacion` del protocolo
// (Kotlin). Lo prueba recuperacion.test.ts contra lo que escribe Kotlin: si la
// web lo normalizara o lo derivara distinto, una cuenta creada aquí no se
// podría recuperar desde la app, ni al revés.
//
// 28 símbolos en Base32 de Crockford (sin I, L, O, U): 16 bytes al azar más uno
// de control (el primer byte de su SHA-256). Se muestra en grupos de 4.
//
// El código NUNCA viaja. De él salen dos claves independientes:
//   codigo --"wtfuck/servidor/verificador/v1"--> lo que guarda el servidor (hasheado)
//   codigo --"wtfuck/copia/identidad/v1"------> cifra la copia de la identidad

const ALFABETO = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const BYTES_AZAR = 16;
export const SIMBOLOS = 28;

async function control(entropia: Uint8Array<ArrayBuffer>): Promise<number> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', entropia))[0];
}

function aBase32(bytes: Uint8Array): string {
  let s = '';
  let acumulado = 0;
  let bits = 0;
  for (const b of bytes) {
    acumulado = ((acumulado << 8) | b) & 0xffff;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      s += ALFABETO[(acumulado >> bits) & 0x1f];
    }
  }
  if (bits > 0) s += ALFABETO[(acumulado << (5 - bits)) & 0x1f];
  return s;
}

function deBase32(simbolos: string): Uint8Array<ArrayBuffer> | null {
  const out = new Uint8Array(BYTES_AZAR + 1);
  let acumulado = 0;
  let bits = 0;
  let i = 0;
  for (const c of simbolos) {
    const v = ALFABETO.indexOf(c);
    if (v < 0) return null;
    acumulado = ((acumulado << 5) | v) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      if (i < out.length) out[i++] = (acumulado >> bits) & 0xff;
    }
  }
  if (i !== out.length) return null;
  // Los 4 bits de relleno del último símbolo TIENEN que ser cero, como en
  // Kotlin: si no, casi la mitad de las erratas en el último carácter darían
  // los mismos bytes, pasarían el control y el código se aceptaría en silencio.
  if (bits > 0 && (acumulado & ((1 << bits) - 1)) !== 0) return null;
  return out;
}

export const conGuiones = (simbolos: string) => simbolos.match(/.{1,4}/g)!.join('-');

/** Un código nuevo, con guiones, listo para mostrar. */
export async function generar(): Promise<string> {
  const entropia = crypto.getRandomValues(new Uint8Array(BYTES_AZAR));
  const todo = new Uint8Array(BYTES_AZAR + 1);
  todo.set(entropia);
  todo[BYTES_AZAR] = await control(entropia);
  return conGuiones(aBase32(todo));
}

/**
 * Como lo escribió la persona -con espacios, guiones, minúsculas, una O en vez
 * de un cero- a la forma canónica, o null si no es un código (largo o control).
 */
export async function normalizar(escrito: string): Promise<string | null> {
  let limpio = '';
  for (const c of escrito.toUpperCase()) {
    if ('- \t\n\r'.includes(c)) continue;
    if (c === 'I' || c === 'L') limpio += '1';
    else if (c === 'O') limpio += '0';
    else if (ALFABETO.includes(c)) limpio += c;
    else return null;
  }
  if (limpio.length !== SIMBOLOS) return null;
  const bytes = deBase32(limpio);
  if (!bytes) return null;
  const entropia = bytes.slice(0, BYTES_AZAR);
  return bytes[BYTES_AZAR] === (await control(entropia)) ? limpio : null;
}

async function hmac(clave: Uint8Array<ArrayBuffer>, datos: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const k = await crypto.subtle.importKey('raw', clave, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, datos));
}

/** HKDF de un solo bloque, como `derivar` en Kotlin: extract con sal cero y expand con la etiqueta. */
async function derivar(codigo: string, etiqueta: string): Promise<Uint8Array<ArrayBuffer>> {
  const prk = await hmac(new Uint8Array(32), new TextEncoder().encode(codigo));
  const info = new TextEncoder().encode(etiqueta);
  const datos = new Uint8Array(info.length + 1);
  datos.set(info);
  datos[info.length] = 1;
  return hmac(prk, datos);
}

/** Lo que se le da al servidor. `codigo` ya normalizado. */
export const verificadorServidor = (codigo: string) => derivar(codigo, 'wtfuck/servidor/verificador/v1');

/** La clave de la copia de la identidad. `codigo` ya normalizado. */
export const claveDeIdentidad = (codigo: string) => derivar(codigo, 'wtfuck/copia/identidad/v1');
