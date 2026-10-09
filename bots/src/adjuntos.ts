// Adjuntos de texto: descifrado y parseo de listas de objetivos.
//
// El bot solo acepta archivos de TEXTO (listas de hosts/IPs). Nunca ejecuta el
// contenido ni lo interpreta como otra cosa: lo trata como candidatos de alcance
// que después hay que pedir y que el admin tiene que aprobar, igual que si los
// escribieras a mano. Un adjunto no saltea ningún control.
//
// El descifrado espeja el de la web (web/app/src/datos/archivos.ts): AES-256-GCM,
// `cifrado || tag(16B)`, clave y nonce que viajaron dentro del mensaje E2EE.

import { clasificar } from './scope.ts';

const SOBRECOSTO = 16; // la etiqueta GCM
/** Tope del archivo CIFRADO que nos molestamos en bajar (una lista de texto es chica). */
export const MAX_BYTES_LISTA = 256 * 1024;
/** Tope de objetivos que tomamos de una sola lista (evita pedidos masivos). */
export const MAX_OBJETIVOS_LISTA = 50;

export class ArchivoAlterado extends Error {
  constructor() {
    super('El archivo no coincide con su firma: pudo ser alterado.');
  }
}

/** AES-256-GCM, mismo formato que la web. Devuelve el texto claro como Buffer. */
export async function descifrarArchivo(cifrado: ArrayBuffer, claveB64: string, nonceB64: string): Promise<ArrayBuffer> {
  const raw = Buffer.from(claveB64, 'base64');
  const iv = Buffer.from(nonceB64, 'base64');
  if (raw.length !== 32 || iv.length !== 12 || cifrado.byteLength < SOBRECOSTO) throw new ArchivoAlterado();
  const k = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']);
  try {
    return await crypto.subtle.decrypt({ name: 'AES-GCM', iv, tagLength: 128 }, k, cifrado);
  } catch {
    throw new ArchivoAlterado();
  }
}

const EXT_TEXTO = /\.(txt|csv|list|lst|text|targets?|hosts?|scope)$/i;

/** ¿Es un archivo de texto "seguro" para tratarlo como lista? (mime o extensión). */
export function esListaDeTexto(mime: string, nombre: string): boolean {
  const m = (mime || '').toLowerCase();
  if (m.startsWith('text/')) return true;
  if (m === 'application/csv' || m === 'application/json') return true; // json raro, pero es texto
  // Sin mime útil: nos fiamos de una extensión de texto conocida.
  if (!m || m === 'application/octet-stream') return EXT_TEXTO.test(nombre || '');
  return false;
}

export interface ListaParseada {
  validos: string[]; // objetivos bien formados (host/IP/CIDR), sin duplicados
  invalidas: number; // líneas con contenido que no son un objetivo válido
  truncado: boolean; // había más de MAX_OBJETIVOS_LISTA
}

/**
 * Saca objetivos de un texto: una entrada por línea (o separada por comas),
 * ignora vacías y comentarios (#, //), deduplica, y clasifica cada una.
 */
export function parsearObjetivos(texto: string, max = MAX_OBJETIVOS_LISTA): ListaParseada {
  const vistos = new Set<string>();
  const validos: string[] = [];
  let invalidas = 0;
  let truncado = false;

  const crudos = texto
    .split(/[\r\n,;]+/)
    .map((s) => s.trim())
    .filter((s) => s && !s.startsWith('#') && !s.startsWith('//'));

  for (const c of crudos) {
    const clasif = clasificar(c);
    if (!clasif) {
      invalidas++;
      continue;
    }
    const norm = c.toLowerCase();
    if (vistos.has(norm)) continue;
    if (validos.length >= max) {
      truncado = true;
      break;
    }
    vistos.add(norm);
    validos.push(norm);
  }
  return { validos, invalidas, truncado };
}
