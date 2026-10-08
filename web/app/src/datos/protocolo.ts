// El contrato con el servidor y con los demás aparatos, en TypeScript.
//
// La fuente de verdad es `protocol/` (Kotlin). Lo que está aquí tiene que
// producir y leer EXACTAMENTE los mismos JSON: las pruebas de oro de
// protocolo.test.ts los comparan con lo que genera Kotlin.

// ---------------------------------------------------------------------------
//  Cargas: lo que viaja cifrado de punta a punta
// ---------------------------------------------------------------------------

/**
 * kotlinx.serialization pone en `type` el nombre COMPLETO de la clase (las
 * subclases de `Carga` no tienen @SerialName), y escribe los null porque la
 * app usa `encodeDefaults = true`.
 */
export const TIPO_TEXTO = 'com.wtfuck.protocol.Carga.Texto';
export const TIPO_CON_CLAVE = 'com.wtfuck.protocol.Carga.ConClaveGrupo';
export const TIPO_HISTORIAL = 'com.wtfuck.protocol.Carga.Historial';

export interface CargaTexto {
  type: typeof TIPO_TEXTO;
  cuerpo: string;
  respondeA: string | null;
  respondeTexto: string | null;
  respondeAutor: string | null;
  reenviadoDe: string | null;
  historia: unknown;
  silencioso: boolean;
  previa: unknown;
  baliza: string | null;
}

export interface MensajeHistorico {
  id: string;
  autor: string;
  esMio: boolean;
  texto: string;
  creadoEn: number;
}

export interface CargaHistorial {
  type: typeof TIPO_HISTORIAL;
  conversacionId: string;
  mensajes: MensajeHistorico[];
  hayMas: boolean;
}

export interface CargaConClave {
  type: typeof TIPO_CON_CLAVE;
  distribucion: string;
  interior: Carga;
}

/** Cualquier otra carga: la web todavía no la muestra, pero la acusa. */
export interface CargaOtra {
  type: string;
  [campo: string]: unknown;
}

export type Carga = CargaTexto | CargaHistorial | CargaConClave | CargaOtra;

/** Un texto con los campos en el orden y con los null que pone Kotlin. */
export function texto(cuerpo: string): CargaTexto {
  return {
    type: TIPO_TEXTO,
    cuerpo,
    respondeA: null,
    respondeTexto: null,
    respondeAutor: null,
    reenviadoDe: null,
    historia: null,
    silencioso: false,
    previa: null,
    baliza: null,
  };
}

export const esTexto = (c: Carga): c is CargaTexto => c.type === TIPO_TEXTO;
export const esHistorial = (c: Carga): c is CargaHistorial => c.type === TIPO_HISTORIAL;
export const esConClave = (c: Carga): c is CargaConClave => c.type === TIPO_CON_CLAVE;

// ---------------------------------------------------------------------------
//  Relleno (protocol/Relleno.kt): esconder el largo del mensaje
// ---------------------------------------------------------------------------

export const RELLENO_MAXIMO = 60 * 1024;

/** 256, 512 … 8192, y después de a 8 KiB hasta 60 KiB. Igual que Kotlin. */
export const CUBOS: number[] = (() => {
  const c: number[] = [];
  for (let v = 256; v <= 8 * 1024; v *= 2) c.push(v);
  for (let v = 16 * 1024; v <= RELLENO_MAXIMO; v += 8 * 1024) c.push(v);
  if (c[c.length - 1] !== RELLENO_MAXIMO) c.push(RELLENO_MAXIMO);
  return c;
})();

/** Ceros al final hasta el cubo siguiente. Lo que no entra en 60 KiB va tal cual. */
export function rellenar(claro: Uint8Array): Uint8Array {
  const destino = CUBOS.find((c) => c >= claro.length);
  if (destino === undefined) return claro;
  const out = new Uint8Array(destino);
  out.set(claro);
  return out;
}

/** Quita TODOS los ceros finales: un JSON nunca termina en un byte 0. */
export function quitarRelleno(claro: Uint8Array): Uint8Array {
  let fin = claro.length;
  while (fin > 0 && claro[fin - 1] === 0) fin--;
  return claro.subarray(0, fin);
}

const enc = new TextEncoder();
const dec = new TextDecoder();

/** Lo que se cifra: el JSON en UTF-8, con relleno salvo que se pida lo contrario. */
export function aBytes(c: Carga, conRelleno = true): Uint8Array {
  const b = enc.encode(JSON.stringify(c));
  return conRelleno ? rellenar(b) : b;
}

export function deBytes(b: Uint8Array): Carga {
  return JSON.parse(dec.decode(quitarRelleno(b))) as Carga;
}

// ---------------------------------------------------------------------------
//  Menciones (protocol/Mensajes.kt: mencionesEn)
// ---------------------------------------------------------------------------

export function mencionesEn(t: string): string[] {
  const vistas = new Set<string>();
  for (const m of t.toLowerCase().matchAll(/@([a-z0-9_]{3,24})/g)) vistas.add(m[1]);
  return [...vistas];
}

// ---------------------------------------------------------------------------
//  REST y WebSocket
// ---------------------------------------------------------------------------

export interface ClaveFirmada { keyId: number; publica: string; firma: string }
export interface ClavePublica { keyId: number; publica: string }

export interface PaqueteClaves {
  usuarioId: string;
  username: string;
  dispositivoId: string;
  registrationId: number;
  identidad: string;
  firmada: ClaveFirmada;
  kyber: ClaveFirmada;
  unica: ClavePublica | null;
}

export interface Destino {
  usuarioId: string;
  username: string;
  dispositivoId: string;
  registrationId: number;
  identidad: string;
  etiqueta: string;
}

export interface UsuarioPublico {
  usuarioId: string;
  username: string;
  nombreMostrado?: string;
  enLinea?: boolean;
  ultimaVez?: number;
}

export interface ConversacionResumen {
  id: string;
  tipo: 'directa' | 'grupo' | 'canal' | 'notas' | string;
  nombre: string;
  participantes: UsuarioPublico[];
  archivado: boolean;
  fijado: boolean;
  esSolicitud: boolean;
}

export interface VincularHecho {
  token: string;
  usuarioId: string;
  dispositivoId: string;
  username: string;
  dispositivos: number;
}

export interface CopiaCifrada {
  destinos: string[];
  cuerpo: string;
  tipo: number;
}

export const TIPO_CIFRADO = { SESION: 2, PREPARADO: 3, GRUPO: 7 } as const;

export type Subida =
  | { type: 'enviar'; sobreId: string; conversacionId: string; creadoEn: number; copias: CopiaCifrada[] }
  | { type: 'acuse'; sobreIds: string[] }
  | { type: 'acuse_evento'; eventoIds: string[] }
  | { type: 'acuse_lectura'; conversacionId: string; mensajeIds: string[] }
  | { type: 'ping' };

export type Bajada =
  | {
      type: 'entrega';
      sobreId: string;
      mensajeId: string;
      conversacionId: string;
      origenUsuarioId: string;
      origenUsername: string;
      origenDispositivo: string;
      creadoEn: number;
      cuerpo: string;
      tipo: number;
      mencionado?: boolean;
    }
  | { type: 'aceptado'; sobreId: string; sinCopia?: string[]; servidorEn?: number }
  | { type: 'error'; sobreId: string | null; motivo: string }
  | { type: 'entregado'; sobreId: string }
  | { type: 'leido'; conversacionId: string; mensajeIds: string[]; porQuien: string }
  | { type: 'escribiendo'; conversacionId: string; username: string; grabando?: boolean }
  | {
      type: 'evento';
      eventoId: string;
      tipo: string;
      conversacionId: string;
      nombreConversacion: string;
      actor: string;
      creadoEn: number;
      detalle: string | null;
    }
  | { type: 'pong' };

/** UUID v4, como `UUID.randomUUID()` de la app. */
export const nuevoId = () => crypto.randomUUID();

const b64 = {
  de: (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0)),
  a: (b: Uint8Array) => {
    let s = '';
    for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
    return btoa(s);
  },
};
export { b64 };
