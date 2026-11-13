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
export const TIPO_EDICION = 'com.wtfuck.protocol.Carga.Edicion';
/** El único con @SerialName propio en el contrato (Adjuntos.kt). */
export const TIPO_ADJUNTO = 'adjunto';

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

export interface CargaEdicion {
  type: typeof TIPO_EDICION;
  mensajeId: string;
  textoNuevo: string;
}

/** `CargaAdjunto` (protocol/Adjuntos.kt), en el mismo orden de campos. */
export interface CargaAdjunto {
  type: typeof TIPO_ADJUNTO;
  adjuntoId: string;
  clase: string;
  clave: string;
  nonce: string;
  mime: string;
  nombre: string;
  /** Tamaño EN CLARO. */
  bytes: number;
  ancho: number;
  alto: number;
  duracionMs: number;
  onda: string;
  pie: string;
  /** JPEG en base64, 240 px como mucho. Viaja cifrado con el mensaje. */
  miniatura: string;
  silencioso: boolean;
  reenviadoDe: string | null;
  unaVez: boolean;
  forma: string;
  spoiler: boolean;
  baliza: string | null;
}

export interface CargaConClave {
  type: typeof TIPO_CON_CLAVE;
  distribucion: string;
  interior: Carga;
}

// --- señalización de llamadas (modulo K). Viaja CIFRADA como cualquier carga:
//     el SDP y los candidatos ICE no pueden pasar en claro por el servidor, o
//     podria cambiar las huellas DTLS y escuchar la llamada. Ver Sobre.kt. ---
export const TIPO_LLAMADA_OFERTA = 'com.wtfuck.protocol.Carga.LlamadaOferta';
export const TIPO_LLAMADA_RESPUESTA = 'com.wtfuck.protocol.Carga.LlamadaRespuesta';
export const TIPO_LLAMADA_CANDIDATO = 'com.wtfuck.protocol.Carga.LlamadaCandidato';
export const TIPO_LLAMADA_FIN = 'com.wtfuck.protocol.Carga.LlamadaFin';

export interface CargaLlamadaOferta {
  type: typeof TIPO_LLAMADA_OFERTA;
  llamadaId: string;
  sdp: string;
  conVideo: boolean;
}
export interface CargaLlamadaRespuesta {
  type: typeof TIPO_LLAMADA_RESPUESTA;
  llamadaId: string;
  sdp: string;
}
export interface CargaLlamadaCandidato {
  type: typeof TIPO_LLAMADA_CANDIDATO;
  llamadaId: string;
  candidato: string;
  sdpMid: string | null;
  sdpMLineIndex: number;
}
export interface CargaLlamadaFin {
  type: typeof TIPO_LLAMADA_FIN;
  llamadaId: string;
  motivo: string;
}

/** Cualquier otra carga: la web todavía no la muestra, pero la acusa. */
export interface CargaOtra {
  type: string;
  [campo: string]: unknown;
}

export type Carga =
  | CargaTexto
  | CargaHistorial
  | CargaConClave
  | CargaEdicion
  | CargaAdjunto
  | CargaLlamadaOferta
  | CargaLlamadaRespuesta
  | CargaLlamadaCandidato
  | CargaLlamadaFin
  | CargaOtra;

// Constructores de señalización. Los campos van EN EL ORDEN de Kotlin (Sobre.kt):
// el cifrado es sobre el JSON y el otro lado deserializa posicionalmente-tolerante,
// pero se respeta el orden por las dudas y para que se lea igual que en la app.
export function llamadaOferta(llamadaId: string, sdp: string, conVideo: boolean): CargaLlamadaOferta {
  return { type: TIPO_LLAMADA_OFERTA, llamadaId, sdp, conVideo };
}
export function llamadaRespuesta(llamadaId: string, sdp: string): CargaLlamadaRespuesta {
  return { type: TIPO_LLAMADA_RESPUESTA, llamadaId, sdp };
}
export function llamadaCandidato(
  llamadaId: string, candidato: string, sdpMid: string | null, sdpMLineIndex: number,
): CargaLlamadaCandidato {
  return { type: TIPO_LLAMADA_CANDIDATO, llamadaId, candidato, sdpMid, sdpMLineIndex };
}
export function llamadaFin(llamadaId: string, motivo: string): CargaLlamadaFin {
  return { type: TIPO_LLAMADA_FIN, llamadaId, motivo };
}

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

/** Un texto que cita otro mensaje: la cita viaja escrita, igual que en la app. */
export function respuesta(cuerpo: string, a: { id: string; texto: string; autor: string }): CargaTexto {
  return { ...texto(cuerpo), respondeA: a.id, respondeTexto: a.texto.slice(0, 140), respondeAutor: a.autor };
}

export function edicion(mensajeId: string, textoNuevo: string): CargaEdicion {
  return { type: TIPO_EDICION, mensajeId, textoNuevo };
}

export function adjunto(c: Pick<CargaAdjunto, 'adjuntoId' | 'clase' | 'clave' | 'nonce'> & Partial<CargaAdjunto>): CargaAdjunto {
  return {
    type: TIPO_ADJUNTO,
    adjuntoId: c.adjuntoId,
    clase: c.clase,
    clave: c.clave,
    nonce: c.nonce,
    mime: c.mime ?? '',
    nombre: c.nombre ?? '',
    bytes: c.bytes ?? 0,
    ancho: c.ancho ?? 0,
    alto: c.alto ?? 0,
    duracionMs: c.duracionMs ?? 0,
    onda: c.onda ?? '',
    pie: c.pie ?? '',
    miniatura: c.miniatura ?? '',
    silencioso: c.silencioso ?? false,
    reenviadoDe: c.reenviadoDe ?? null,
    unaVez: c.unaVez ?? false,
    forma: c.forma ?? '',
    spoiler: c.spoiler ?? false,
    baliza: c.baliza ?? null,
  };
}

export const esTexto = (c: Carga): c is CargaTexto => c.type === TIPO_TEXTO;
export const esHistorial = (c: Carga): c is CargaHistorial => c.type === TIPO_HISTORIAL;
export const esConClave = (c: Carga): c is CargaConClave => c.type === TIPO_CON_CLAVE;
export const esEdicion = (c: Carga): c is CargaEdicion => c.type === TIPO_EDICION;
export const esAdjunto = (c: Carga): c is CargaAdjunto => c.type === TIPO_ADJUNTO;
export const esLlamadaOferta = (c: Carga): c is CargaLlamadaOferta => c.type === TIPO_LLAMADA_OFERTA;
export const esLlamadaRespuesta = (c: Carga): c is CargaLlamadaRespuesta => c.type === TIPO_LLAMADA_RESPUESTA;
export const esLlamadaCandidato = (c: Carga): c is CargaLlamadaCandidato => c.type === TIPO_LLAMADA_CANDIDATO;
export const esLlamadaFin = (c: Carga): c is CargaLlamadaFin => c.type === TIPO_LLAMADA_FIN;

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

// --- GIFs (modulo del buscador): servicio del servidor, no van cifrados en el
//     catalogo; al enviarse se bajan y van como un adjunto de imagen normal ---
export interface GifResumen {
  id: string;
  titulo: string;
  ancho: number;
  alto: number;
  bytes: number;
}
export interface BusquedaGifs {
  resultados: GifResumen[];
  aviso: string;
}

// --- llamadas (modulo K): metadatos por HTTP; la señalizacion va cifrada arriba ---
export interface ConfigTurn {
  urls: string[];
  usuario: string;
  clave: string;
  expiraEnSegundos: number;
  hay: boolean;
}
export interface IniciarLlamadaReq {
  conversacionId: string;
  conVideo: boolean;
  invitados: string[];
}
export interface LlamadaCreada {
  llamadaId: string;
  destinos: Destino[];
  turn: ConfigTurn;
}
export interface TerminarLlamadaReq {
  motivo: string;
}
export interface LlamadaEnCurso {
  llamadaId: string;
  conversacionId: string;
  conVideo: boolean;
  origen: string;
  estado: string;
  turn: ConfigTurn;
  destinos: Destino[];
  miEstado: string;
  estados: Record<string, string>;
}

export interface UsuarioPublico {
  usuarioId: string;
  username: string;
  nombreMostrado?: string;
  enLinea?: boolean;
  ultimaVez?: number;
  /** Marca de tiempo de la foto, o 0/ausente si no tiene. Cache-buster. */
  avatarVersion?: number;
}

export interface ConversacionResumen {
  id: string;
  tipo: 'directa' | 'grupo' | 'canal' | 'notas' | string;
  nombre: string;
  participantes: UsuarioPublico[];
  archivado: boolean;
  fijado: boolean;
  esSolicitud: boolean;
  /** Foto del grupo o canal, o 0/ausente si no tiene. En directas es 0. */
  avatarVersion?: number;
  /** Mensajes temporales: segundos tras los que un mensaje nuevo se borra solo. 0/ausente = permanentes. */
  temporalesSegundos?: number;
}

/**
 * Mensajes temporales: las duraciones que ofrece el selector y cómo se nombran.
 * Espejo de `DuracionMensaje` del protocolo Kotlin (el servidor acepta 60..7776000).
 */
export const DuracionMensaje = {
  OPCIONES: [60, 5 * 60, 3600, 8 * 3600, 24 * 3600, 7 * 24 * 3600, 28 * 24 * 3600] as number[],
  texto(seg: number): string {
    if (seg <= 0) return 'Desactivado';
    const d = [
      [28 * 24 * 3600, '4 semanas'], [7 * 24 * 3600, '1 semana'], [24 * 3600, '1 día'],
      [8 * 3600, '8 horas'], [3600, '1 hora'], [5 * 60, '5 minutos'], [60, '1 minuto'],
    ] as const;
    for (const [s, t] of d) if (seg >= s) return t;
    return `${seg} segundos`;
  },
};

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
