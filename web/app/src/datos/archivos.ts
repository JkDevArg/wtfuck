// Fotos y archivos: lo mismo que CifradorArchivo y Media de la app.
//
// ## El cifrado
//
// AES-256-GCM en UN solo mensaje, con clave y nonce al azar por archivo. Lo
// que se sube es `cifrado || etiqueta (16 B)`, sin cabecera ni AAD. La clave
// y el nonce viajan aparte, dentro del mensaje cifrado de punta a punta. La
// integridad la da la etiqueta: si no cuadra, el archivo fue alterado.
//
// ## Lo que la web todavía no hace
//
// "Ver una vez" no se abre en el navegador: es justo lo que una captura de
// pantalla o una extensión copiaría sin que nadie lo note. Se ve en el teléfono.
import { b64 } from './protocolo';

export type Clase = 'imagen' | 'video' | 'audio' | 'nota_voz' | 'documento' | 'sticker';

const MIB = 1024 * 1024;

/** Límite del archivo CIFRADO por clase (protocol/Adjuntos.kt). */
export const LIMITES: Record<Clase, number> = {
  imagen: 16 * MIB,
  video: 64 * MIB,
  audio: 32 * MIB,
  nota_voz: 16 * MIB,
  documento: 64 * MIB,
  sticker: 2 * MIB,
};

export const SOBRECOSTO = 16;

export function claseDe(mime: string): Clase {
  if (mime.startsWith('image/')) return 'imagen';
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  return 'documento';
}

export async function cifrarArchivo(claro: ArrayBuffer): Promise<{ cifrado: ArrayBuffer; clave: string; nonce: string }> {
  const clave = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const k = await crypto.subtle.importKey('raw', clave, 'AES-GCM', false, ['encrypt']);
  const cifrado = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: 128 }, k, claro);
  return { cifrado, clave: b64.a(clave), nonce: b64.a(iv) };
}

/** Error que se muestra tal cual: la etiqueta no cuadró. */
export class ArchivoAlterado extends Error {
  constructor() {
    super('El archivo no coincide con su firma: pudo ser alterado.');
  }
}

export async function descifrarArchivo(cifrado: ArrayBuffer, claveB64: string, nonceB64: string): Promise<ArrayBuffer> {
  const raw = b64.de(claveB64);
  const iv = b64.de(nonceB64);
  if (raw.length !== 32 || iv.length !== 12 || cifrado.byteLength < SOBRECOSTO) throw new ArchivoAlterado();
  const k = await crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, ['decrypt']);
  try {
    return await crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv as BufferSource, tagLength: 128 }, k, cifrado);
  } catch {
    throw new ArchivoAlterado();
  }
}

/**
 * El nombre que manda otro, saneado como `ContenidoSeguro` de la app: sin
 * caracteres de control ni de dirección (con un U+202E, "fotocod.exe" se lee
 * "fotoexe.doc") y sin saltos de línea, y como mucho 120 caracteres.
 */
export function nombreSeguro(n: string): string {
  // eslint-disable-next-line no-control-regex
  const limpio = n.replace(/[\u0000-\u001f\u007f‎‏‪-‮⁦-⁩]/g, '').trim();
  return (limpio || 'archivo').slice(0, 120);
}

/** Una miniatura ajena: como `MiniaturaSegura`, a lo sumo 48 KiB. */
export function miniaturaSegura(m: string): string {
  if (!m) return '';
  try {
    return b64.de(m).length <= 48 * 1024 ? m : '';
  } catch {
    return '';
  }
}

export function tamanoLegible(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < MIB) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / MIB).toFixed(1)} MB`;
}

// ---------------------------------------------------------------------------
//  Imágenes: reducir y miniatura, como Media.kt
// ---------------------------------------------------------------------------

async function aJpeg(img: CanvasImageSource & { width: number; height: number }, lado: number, calidad: number): Promise<{ blob: Blob; ancho: number; alto: number }> {
  const escala = Math.min(1, lado / Math.max(img.width, img.height));
  const ancho = Math.max(1, Math.round(img.width * escala));
  const alto = Math.max(1, Math.round(img.height * escala));
  const lienzo = document.createElement('canvas');
  lienzo.width = ancho;
  lienzo.height = alto;
  lienzo.getContext('2d')!.drawImage(img, 0, 0, ancho, alto);
  const blob = await new Promise<Blob>((r, x) => lienzo.toBlob((b) => (b ? r(b) : x(new Error('No se pudo leer la imagen.'))), 'image/jpeg', calidad));
  return { blob, ancho, alto };
}

export interface ImagenPreparada {
  datos: ArrayBuffer;
  mime: string;
  ancho: number;
  alto: number;
  miniatura: string;
}

/**
 * Lado máximo 2560 px en JPEG al 88, igual que la calidad "alta" de la app.
 * Un GIF va tal cual: recomprimirlo lo dejaría quieto. La miniatura es de
 * 240 px y baja de calidad hasta pesar 20 KB o menos.
 */
export async function prepararImagen(f: File): Promise<ImagenPreparada> {
  const img = await createImageBitmap(f);
  try {
    let miniatura = '';
    for (const q of [0.7, 0.5, 0.3]) {
      const m = await aJpeg(img, 240, q);
      if (m.blob.size <= 20 * 1024 || q === 0.3) {
        miniatura = b64.a(new Uint8Array(await m.blob.arrayBuffer()));
        break;
      }
    }
    if (f.type === 'image/gif') {
      return { datos: await f.arrayBuffer(), mime: 'image/gif', ancho: img.width, alto: img.height, miniatura };
    }
    const r = await aJpeg(img, 2560, 0.88);
    return { datos: await r.blob.arrayBuffer(), mime: 'image/jpeg', ancho: r.ancho, alto: r.alto, miniatura };
  } finally {
    img.close();
  }
}

export interface VideoPreparado {
  ancho: number;
  alto: number;
  duracionMs: number;
  miniatura: string;
}

/**
 * Miniatura y medidas de un video, como la app con el primer fotograma. Si el
 * navegador no sabe decodificarlo, el video sale igual, sin miniatura.
 */
export async function prepararVideo(f: File): Promise<VideoPreparado> {
  const url = URL.createObjectURL(f);
  const v = document.createElement('video');
  v.muted = true;
  v.preload = 'auto';
  v.src = url;
  try {
    await new Promise<void>((r, x) => {
      v.onloadeddata = () => r();
      v.onerror = () => x(new Error('no se pudo leer el video'));
      setTimeout(() => x(new Error('tiempo')), 8000);
    });
    const t = Math.min(0.1, (v.duration || 1) / 2);
    await new Promise<void>((r) => {
      v.onseeked = () => r();
      v.currentTime = t;
      setTimeout(r, 3000);
    });
    const marco = { width: v.videoWidth, height: v.videoHeight };
    const lienzo = Object.assign(v, marco);
    let miniatura = '';
    for (const q of [0.7, 0.5, 0.3]) {
      const m = await aJpeg(lienzo, 240, q);
      if (m.blob.size <= 20 * 1024 || q === 0.3) {
        miniatura = b64.a(new Uint8Array(await m.blob.arrayBuffer()));
        break;
      }
    }
    return { ancho: v.videoWidth, alto: v.videoHeight, duracionMs: Math.round((v.duration || 0) * 1000), miniatura };
  } catch {
    return { ancho: 0, alto: 0, duracionMs: 0, miniatura: '' };
  } finally {
    URL.revokeObjectURL(url);
  }
}
