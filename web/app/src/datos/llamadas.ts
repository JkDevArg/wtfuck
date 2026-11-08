// Llamadas de audio 1-a-1 en la web (modulo K, Fase 1).
//
// WebRTC nativo del navegador. La señalizacion (SDP + candidatos ICE) NO pasa en
// claro: va cifrada de punta a punta como cualquier carga, a traves de
// `enviarSenalLlamada`/`registrarSenalLlamada` de motor.ts. El servidor solo ve
// metadatos (quien llama a quien) por HTTP: iniciar, contestar, terminar.
//
// Por que importa que la señalizacion vaya cifrada: el SDP lleva la huella del
// certificado DTLS de cada lado. Si pasara en claro, el servidor podria cambiarla
// y montar un hombre-en-el-medio que escuche la llamada. Cifrada, mueve bytes que
// no puede abrir. Ver protocol/Llamadas.kt.

import { pedir } from './api';
import { enviarSenalLlamada, registrarSenalLlamada } from './motor';
import {
  llamadaOferta, llamadaRespuesta, llamadaCandidato, llamadaFin,
  esLlamadaOferta, esLlamadaRespuesta, esLlamadaCandidato, esLlamadaFin,
  type Carga, type ConfigTurn, type IniciarLlamadaReq, type LlamadaCreada,
  type LlamadaEnCurso, type TerminarLlamadaReq,
} from './protocolo';

export type FaseLlamada = 'inactiva' | 'llamando' | 'sonando' | 'en-curso';

export interface EstadoLlamada {
  fase: FaseLlamada;
  llamadaId: string;
  conversacionId: string;
  /** Username del otro, para mostrar. */
  conQuien: string;
  desdeMs: number;
  muteado: boolean;
  /** Si la llamada es con video. */
  conVideo: boolean;
  /** La camara propia esta encendida. */
  camaraEncendida: boolean;
  /** Ya llega video del otro (para decidir si dibujar su recuadro). */
  hayVideoRemoto: boolean;
  /** Ya hay stream del otro (audio o video): la UI engancha el reproductor. */
  remotoListo: boolean;
}

const inicial = (): EstadoLlamada => ({
  fase: 'inactiva', llamadaId: '', conversacionId: '', conQuien: '', desdeMs: 0, muteado: false,
  conVideo: false, camaraEncendida: false, hayVideoRemoto: false, remotoListo: false,
});

/** Los streams para la UI (no van en el estado: son objetos, no datos). */
let streamRemoto: MediaStream | null = null;
export const streamLocalActual = (): MediaStream | null => streamLocal;
export const streamRemotoActual = (): MediaStream | null => streamRemoto;

// --- store observable (mismo patron que motor.ts, para useSyncExternalStore) ---
let estado: EstadoLlamada = inicial();
const subs = new Set<() => void>();
function emitir(parcial: Partial<EstadoLlamada>) {
  estado = { ...estado, ...parcial };
  subs.forEach((f) => f());
}
export const llamadas = {
  suscribir(f: () => void): () => void {
    subs.add(f);
    return () => { subs.delete(f); };
  },
  instantanea: (): EstadoLlamada => estado,
};

// --- WebRTC ---
let pc: RTCPeerConnection | null = null;
let streamLocal: MediaStream | null = null;
let turnActual: ConfigTurn | null = null;
let ofertaPendiente: string | null = null;
let conVideoPendiente = false;
let candidatosPendientes: RTCIceCandidateInit[] = [];

function iceServers(turn: ConfigTurn | null): RTCIceServer[] {
  // Solo el TURN/STUN del propio servidor: no se usa un STUN publico de
  // terceros para no filtrarle a nadie con quien se esta hablando. Sin TURN, la
  // llamada funciona cuando ICE logra conexion directa (misma red).
  if (turn?.hay && turn.urls.length) {
    return [{ urls: turn.urls, username: turn.usuario, credential: turn.clave }];
  }
  return [];
}

function medios(conVideo: boolean): Promise<MediaStream> {
  return navigator.mediaDevices.getUserMedia({ audio: true, video: conVideo });
}

function crearPc(conversacionId: string, llamadaId: string): RTCPeerConnection {
  const p = new RTCPeerConnection({ iceServers: iceServers(turnActual) });
  p.onicecandidate = (ev) => {
    if (ev.candidate) {
      void enviarSenalLlamada(
        conversacionId,
        llamadaCandidato(llamadaId, ev.candidate.candidate, ev.candidate.sdpMid ?? null, ev.candidate.sdpMLineIndex ?? 0),
      );
    }
  };
  p.ontrack = (ev) => {
    // El stream remoto (audio y, si hay, video) lo reproduce la UI en un
    // elemento <audio>/<video>; aca solo se guarda y se avisa.
    streamRemoto = ev.streams[0] ?? null;
    emitir({ hayVideoRemoto: !!streamRemoto?.getVideoTracks().length, remotoListo: true });
  };
  p.onconnectionstatechange = () => {
    if (p.connectionState === 'failed') void colgar();
  };
  return p;
}

async function aplicarCandidatosPendientes(): Promise<void> {
  if (!pc) return;
  const cola = candidatosPendientes;
  candidatosPendientes = [];
  for (const c of cola) await pc.addIceCandidate(c).catch(() => undefined);
}

function cerrar(): void {
  streamLocal?.getTracks().forEach((t) => t.stop());
  streamLocal = null;
  streamRemoto = null;
  pc?.close();
  pc = null;
  ofertaPendiente = null;
  conVideoPendiente = false;
  candidatosPendientes = [];
  turnActual = null;
  estado = inicial();
  subs.forEach((f) => f());
}

// --- acciones (las usa la UI) ---

/** Llamar a la persona de una conversacion directa, con o sin video. */
export async function iniciarLlamada(conversacionId: string, conQuien: string, conVideo = false): Promise<void> {
  if (estado.fase !== 'inactiva') return;
  try {
    const creada = await pedir<LlamadaCreada>('POST', '/v1/llamadas', {
      conversacionId, conVideo, invitados: [],
    } satisfies IniciarLlamadaReq);
    turnActual = creada.turn;
    streamLocal = await medios(conVideo);
    pc = crearPc(conversacionId, creada.llamadaId);
    streamLocal.getTracks().forEach((t) => pc!.addTrack(t, streamLocal!));
    const oferta = await pc.createOffer();
    await pc.setLocalDescription(oferta);
    await enviarSenalLlamada(conversacionId, llamadaOferta(creada.llamadaId, oferta.sdp ?? '', conVideo));
    emitir({ fase: 'llamando', llamadaId: creada.llamadaId, conversacionId, conQuien, conVideo, camaraEncendida: conVideo });
  } catch (x) {
    console.warn('no se pudo iniciar la llamada', x);
    cerrar();
    throw x;
  }
}

/** Aceptar una llamada que esta sonando. */
export async function aceptar(): Promise<void> {
  if (estado.fase !== 'sonando' || ofertaPendiente === null) return;
  const sdpOferta = ofertaPendiente;
  try {
    const curso = await pedir<LlamadaEnCurso>('POST', `/v1/llamadas/${estado.llamadaId}/contestar`);
    turnActual = curso.turn;
    streamLocal = await medios(conVideoPendiente);
    pc = crearPc(estado.conversacionId, estado.llamadaId);
    streamLocal.getTracks().forEach((t) => pc!.addTrack(t, streamLocal!));
    await pc.setRemoteDescription({ type: 'offer', sdp: sdpOferta });
    await aplicarCandidatosPendientes();
    const resp = await pc.createAnswer();
    await pc.setLocalDescription(resp);
    await enviarSenalLlamada(estado.conversacionId, llamadaRespuesta(estado.llamadaId, resp.sdp ?? ''));
    ofertaPendiente = null;
    emitir({ fase: 'en-curso', desdeMs: Date.now(), conVideo: conVideoPendiente, camaraEncendida: conVideoPendiente });
  } catch (x) {
    console.warn('no se pudo aceptar la llamada', x);
    await colgar();
  }
}

/** Rechazar una llamada entrante. */
export async function rechazar(): Promise<void> {
  await terminar('rechazada');
}

/** Colgar (o cancelar) la llamada en curso o saliente. */
export async function colgar(): Promise<void> {
  await terminar('colgada');
}

async function terminar(motivo: string): Promise<void> {
  const id = estado.llamadaId;
  const conv = estado.conversacionId;
  if (id) {
    void enviarSenalLlamada(conv, llamadaFin(id, motivo)).catch(() => undefined);
    void pedir('POST', `/v1/llamadas/${id}/terminar`, { motivo } satisfies TerminarLlamadaReq).catch(() => undefined);
  }
  cerrar();
}

/** Silenciar/activar el microfono. */
export function alternarMute(): void {
  if (!streamLocal) return;
  const nuevo = !estado.muteado;
  streamLocal.getAudioTracks().forEach((t) => { t.enabled = !nuevo; });
  emitir({ muteado: nuevo });
}

/** Encender/apagar la camara (solo en videollamada). */
export function alternarCamara(): void {
  if (!streamLocal || !estado.conVideo) return;
  const encender = !estado.camaraEncendida;
  streamLocal.getVideoTracks().forEach((t) => { t.enabled = encender; });
  emitir({ camaraEncendida: encender });
}

// --- señalizacion entrante (registrada en motor.ts) ---

function alRecibirSenal(conversacionId: string, _origenDispositivo: string, origenUsername: string, carga: Carga): void {
  if (esLlamadaOferta(carga)) {
    // Ya en otra llamada: se rechaza como "ocupado" sin tocar la actual.
    if (estado.fase !== 'inactiva') {
      void enviarSenalLlamada(conversacionId, llamadaFin(carga.llamadaId, 'ocupado')).catch(() => undefined);
      void pedir('POST', `/v1/llamadas/${carga.llamadaId}/terminar`, { motivo: 'ocupado' } satisfies TerminarLlamadaReq)
        .catch(() => undefined);
      return;
    }
    ofertaPendiente = carga.sdp;
    conVideoPendiente = carga.conVideo;
    candidatosPendientes = [];
    emitir({ fase: 'sonando', llamadaId: carga.llamadaId, conversacionId, conQuien: origenUsername, conVideo: carga.conVideo });
  } else if (esLlamadaRespuesta(carga)) {
    if (pc && estado.llamadaId === carga.llamadaId) {
      void pc.setRemoteDescription({ type: 'answer', sdp: carga.sdp })
        .then(aplicarCandidatosPendientes)
        .catch((x) => console.warn('respuesta', x));
      emitir({ fase: 'en-curso', desdeMs: Date.now() });
    }
  } else if (esLlamadaCandidato(carga)) {
    if (estado.llamadaId !== carga.llamadaId) return;
    const cand: RTCIceCandidateInit = {
      candidate: carga.candidato,
      sdpMid: carga.sdpMid ?? undefined,
      sdpMLineIndex: carga.sdpMLineIndex,
    };
    if (pc && pc.remoteDescription) void pc.addIceCandidate(cand).catch(() => undefined);
    else candidatosPendientes.push(cand);
  } else if (esLlamadaFin(carga)) {
    if (estado.llamadaId === carga.llamadaId) cerrar();
  }
}

registrarSenalLlamada(alRecibirSenal);
