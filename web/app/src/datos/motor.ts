// El motor de la web: hace lo mismo que el Repositorio de la app para TEXTO.
//
// Vincular, publicar claves, el WebSocket, recibir (pares, grupos, historial),
// enviar (pares o clave de emisor), acuses de entrega y lectura. Lo que no es
// texto se acusa y no se muestra todavía (W3).
//
// La especificación de cada paso, con las rutas de la app que imita, está en
// docs/12-VERSION-WEB.md. Las diferencias a propósito con la app se marcan
// con "DIFERENCIA".
import { ErrorApi, cuandoSePierdaLaSesion, pedir, usarToken } from './api';
import { borrar, guardar, leer, todas, vaciarTodo } from './boveda';
import * as cripto from './cripto';
import {
  aBytes, adjunto, b64, deBytes, edicion, esAdjunto, esConClave, esEdicion, esHistorial, esTexto, mencionesEn,
  nuevoId, respuesta, texto, TIPO_CIFRADO,
  type Bajada, type Carga, type ConversacionResumen, type CopiaCifrada, type Destino, type PaqueteClaves,
  type Subida, type VincularHecho,
} from './protocolo';
import { mimeParaReproducir, type NotaGrabada } from './grabadora';
import {
  cifrarArchivo, claseDe, descifrarArchivo, LIMITES, miniaturaSegura, nombreSeguro, prepararImagen, prepararVideo, SOBRECOSTO,
  tamanoLegible, type Clase,
} from './archivos';

// ---------------------------------------------------------------------------
//  Estado
// ---------------------------------------------------------------------------

export interface Sesion {
  token: string;
  usuarioId: string;
  dispositivoId: string;
  username: string;
  hardwareHash: string;
  etiqueta: string;
  sig: { firmada: number; kyber: number; unica: number };
}

export type EstadoMensaje = 'pendiente' | 'enviado' | 'entregado' | 'leido' | 'fallido';

/** Un archivo del mensaje: lo que dice la carga, más si ya se subió. */
export interface AdjuntoLocal {
  adjuntoId?: string;
  clase: Clase;
  clave?: string;
  nonce?: string;
  mime: string;
  nombre: string;
  bytes: number;
  ancho: number;
  alto: number;
  duracionMs: number;
  miniatura: string;
  /** Notas de voz: 40 barras, ver `Onda` en el contrato. */
  onda?: string;
  unaVez: boolean;
  spoiler: boolean;
  /** Mío: ya está subido y confirmado, se puede mandar el mensaje. */
  listo: boolean;
}

export interface Reaccion { emoji: string; total: number; mia: boolean; quienes: string[] }

export interface Mensaje {
  id: string;
  conversacionId: string;
  autor: string;
  esMio: boolean;
  /** El texto, o el pie de un archivo. */
  texto: string;
  adjunto?: AdjuntoLocal;
  /** A qué mensaje responde. La cita viaja escrita: no hace falta tenerlo. */
  cita?: { id: string; texto: string; autor: string };
  editado?: boolean;
  retirado?: boolean;
  reacciones?: Reaccion[];
  creadoEn: number;
  estado: EstadoMensaje;
  motivo?: string;
  /** Un mensaje ajeno que ya se acusó como leído. */
  leidoPorMi?: boolean;
  deHistorial?: boolean;
}

export interface Conversacion extends ConversacionResumen {
  noLeidos: number;
  ultimo?: { texto: string; creadoEn: number; esMio: boolean };
  /** Aparatos de esta conversación cuya identidad cambió y nadie miró. */
  identidadCambio?: { dispositivo: string; username: string }[];
}

export type Conexion = 'sin-vincular' | 'conectando' | 'en-linea' | 'sin-red' | 'otra-pestana' | 'desvinculado';

/** Un archivo descargado y descifrado: vive en memoria mientras dure la pestaña. */
export interface Archivo { estado: 'bajando' | 'listo' | 'error'; url?: string; error?: string }

export interface Instantanea {
  archivos: Record<string, Archivo>;
  /** Sube cuando llega una publicación a ese canal: la vista abierta recarga. */
  canalesNuevos: Record<string, number>;
  sesion: Sesion | null;
  conexion: Conexion;
  conversaciones: Conversacion[];
  mensajes: Record<string, Mensaje[]>;
  aviso: string | null;
  /** Avisos del navegador: el permiso y si dicen quién escribió. */
  avisos: { permiso: NotificationPermission | 'sin-soporte'; mostrarQuien: boolean };
}

const PREF_QUIEN = 'wtfuck-avisos-sin-quien';

/** Como la app: por defecto el aviso dice QUIÉN escribió, nunca QUÉ. */
function prefQuien(): boolean {
  try {
    return localStorage.getItem(PREF_QUIEN) !== '1';
  } catch {
    return true;
  }
}

let estado: Instantanea = {
  archivos: {},
  canalesNuevos: {},
  sesion: null,
  conexion: 'sin-vincular',
  conversaciones: [],
  mensajes: {},
  aviso: null,
  avisos: { permiso: 'Notification' in window ? Notification.permission : 'sin-soporte', mostrarQuien: prefQuien() },
};
const oyentes = new Set<() => void>();

function cambiar(p: Partial<Instantanea>): void {
  estado = { ...estado, ...p };
  oyentes.forEach((f) => f());
}

export const motor = {
  suscribir(f: () => void) {
    oyentes.add(f);
    return () => oyentes.delete(f);
  },
  instantanea: () => estado,
};

// ---------------------------------------------------------------------------
//  Entre pestañas
// ---------------------------------------------------------------------------
//
// Solo UNA pestaña tiene el socket y el almacén de Signal (ver `conectar`).
// Las demás leen la bóveda: la titular les avisa por este canal cada vez que
// guarda algo, y ellas le piden lo que necesita el socket (mandar lo que
// escribieron, marcar un chat como leído). Así ninguna toca el ratchet a la
// vez que otra, que lo corrompería.

type MensajeCanal = { tipo: 'cambio' } | { tipo: 'despachar' } | { tipo: 'leer'; conversacionId: string };

const canal: BroadcastChannel | null = 'BroadcastChannel' in window ? new BroadcastChannel('wtfuck-web') : null;
let soyTitular = false;
let avisoPendiente: ReturnType<typeof setTimeout> | null = null;

function avisarCambio(): void {
  if (!soyTitular || !canal || avisoPendiente) return;
  avisoPendiente = setTimeout(() => {
    avisoPendiente = null;
    canal.postMessage({ tipo: 'cambio' } satisfies MensajeCanal);
  }, 120);
}

let releyendo: ReturnType<typeof setTimeout> | null = null;

if (canal) {
  canal.onmessage = (ev: MessageEvent<MensajeCanal>) => {
    const m = ev.data;
    if (m.tipo === 'cambio' && !soyTitular) {
      if (releyendo) clearTimeout(releyendo);
      releyendo = setTimeout(() => void releerBoveda(), 80);
    } else if (m.tipo === 'despachar' && soyTitular) {
      void releerBoveda().then(() => despachar());
    } else if (m.tipo === 'leer' && soyTitular) {
      void releerBoveda().then(() => marcarLeida(m.conversacionId));
    }
  };
}

/** Rehace el estado desde la bóveda: lo que guardó la otra pestaña. */
async function releerBoveda(): Promise<void> {
  const convs = await todas<Conversacion>('conversaciones');
  const msjs = await todas<Mensaje>('mensajes');
  const mensajes: Record<string, Mensaje[]> = {};
  porId.clear();
  for (const m of msjs) {
    porId.set(m.id, m);
    (mensajes[m.conversacionId] ??= []).push(m);
  }
  for (const l of Object.values(mensajes)) l.sort((a, b) => a.creadoEn - b.creadoEn);
  const orden = [...convs].sort((a, b) => (b.ultimo?.creadoEn ?? 0) - (a.ultimo?.creadoEn ?? 0));
  cambiar({ conversaciones: orden, mensajes });
}

const PREKEYS_OBJETIVO = 100;
const PREKEYS_MINIMO = 20;

// ---------------------------------------------------------------------------
//  Mensajes en memoria y en la bóveda
// ---------------------------------------------------------------------------

/** "conversación|creadoEn de 15 cifras|id": IndexedDB los ordena solo. */
const claveMsj = (m: Mensaje) => `${m.conversacionId}|${String(m.creadoEn).padStart(15, '0')}|${m.id}`;
const porId = new Map<string, Mensaje>();

async function guardarMensaje(m: Mensaje): Promise<boolean> {
  const ya = porId.get(m.id);
  if (ya && ya !== m) return false; // repetido: el buzón reentrega por diseño
  porId.set(m.id, m);
  await guardar('mensajes', claveMsj(m), m);
  avisarCambio();
  const lista = [...(estado.mensajes[m.conversacionId] ?? []).filter((x) => x.id !== m.id), m].sort(
    (a, b) => a.creadoEn - b.creadoEn,
  );
  cambiar({ mensajes: { ...estado.mensajes, [m.conversacionId]: lista } });
  await tocarConversacion(m);
  return true;
}

async function actualizarMensaje(id: string, cambio: Partial<Mensaje>): Promise<void> {
  const m = porId.get(id);
  if (!m) return;
  // Un estado no retrocede: un "entregado" tardío no pisa un "leído".
  const orden: EstadoMensaje[] = ['fallido', 'pendiente', 'enviado', 'entregado', 'leido'];
  if (cambio.estado && m.estado !== 'fallido' && orden.indexOf(cambio.estado) < orden.indexOf(m.estado)) {
    delete cambio.estado;
  }
  const nuevo = { ...m, ...cambio };
  porId.set(id, nuevo);
  await guardar('mensajes', claveMsj(nuevo), nuevo);
  avisarCambio();
  const lista = (estado.mensajes[m.conversacionId] ?? []).map((x) => (x.id === id ? nuevo : x));
  cambiar({ mensajes: { ...estado.mensajes, [m.conversacionId]: lista } });
  // Si es el último del chat, la vista previa de la lista tiene que decir lo
  // mismo: el texto editado, o que se eliminó.
  const c = estado.conversaciones.find((x) => x.id === m.conversacionId);
  if (c?.ultimo && c.ultimo.creadoEn === nuevo.creadoEn && (cambio.texto !== undefined || cambio.retirado)) {
    await publicarConversaciones(estado.conversaciones.map((x) => (x.id === c.id ? { ...x, ultimo: { ...c.ultimo!, texto: resumenDe(nuevo) } } : x)));
  }
}

/** Lo que dice la lista de chats de un mensaje. */
function resumenDe(m: Mensaje): string {
  if (m.retirado) return 'Mensaje eliminado';
  if (m.texto) return m.texto;
  const a = m.adjunto;
  if (!a) return '';
  return { imagen: 'Foto', sticker: 'Sticker', video: 'Video', audio: 'Audio', nota_voz: 'Nota de voz' }[a.clase as string] ?? a.nombre;
}

async function tocarConversacion(m: Mensaje): Promise<void> {
  const convs = estado.conversaciones.map((c) => {
    if (c.id !== m.conversacionId) return c;
    const masNuevo = !c.ultimo || m.creadoEn >= c.ultimo.creadoEn;
    const sumar = !m.esMio && !m.leidoPorMi && !m.deHistorial && abierta !== c.id ? 1 : 0;
    return {
      ...c,
      noLeidos: c.noLeidos + sumar,
      ultimo: masNuevo
        ? { texto: resumenDe(m), creadoEn: m.creadoEn, esMio: m.esMio }
        : c.ultimo,
    };
  });
  await publicarConversaciones(convs);
}

async function publicarConversaciones(convs: Conversacion[]): Promise<void> {
  const orden = [...convs].sort((a, b) => (b.ultimo?.creadoEn ?? 0) - (a.ultimo?.creadoEn ?? 0));
  cambiar({ conversaciones: orden });
  await Promise.all(orden.map((c) => guardar('conversaciones', c.id, c)));
  avisarCambio();
}

// ---------------------------------------------------------------------------
//  Arranque, vinculación y claves
// ---------------------------------------------------------------------------

cuandoSePierdaLaSesion(() => void perderVinculo());

const AVISO_GUARDADO = 'wtfuck-aviso';

export async function iniciar(): Promise<void> {
  const s = await leer<Sesion>('cuenta', 'sesion');
  if (!s || !(await cripto.tieneIdentidad())) {
    // Si se perdio el vinculo justo antes de recargar, que se sepa por que se
    // volvio a esta pantalla. Una sola vez.
    const aviso = sessionStorage.getItem(AVISO_GUARDADO);
    sessionStorage.removeItem(AVISO_GUARDADO);
    cambiar({ sesion: null, conexion: 'sin-vincular', aviso });
    return;
  }
  usarToken(s.token);
  const convs = await todas<Conversacion>('conversaciones');
  const msjs = await todas<Mensaje>('mensajes');
  const mensajes: Record<string, Mensaje[]> = {};
  for (const m of msjs) {
    porId.set(m.id, m);
    (mensajes[m.conversacionId] ??= []).push(m);
  }
  for (const l of Object.values(mensajes)) l.sort((a, b) => a.creadoEn - b.creadoEn);
  cambiar({ sesion: s, mensajes, conexion: 'conectando' });
  await publicarConversaciones(convs);
  void arrancarEnLinea();
}

async function arrancarEnLinea(): Promise<void> {
  try {
    await reponerClaves();
    await sincronizarConversaciones();
  } catch (e) {
    if (e instanceof ErrorApi && e.estado === 401) return;
  }
  conectar();
}

/** Vincula este navegador a una cuenta con el código del teléfono. */
export async function vincular(username: string, codigo: string, etiqueta: string): Promise<void> {
  const { identidad } = await cripto.crearIdentidad();
  const hardwareHash = b64.a(crypto.getRandomValues(new Uint8Array(32)));
  const r = await pedir<VincularHecho>(
    'POST',
    '/v1/dispositivos/vincular',
    {
      username: username.trim().replace(/^@/, '').toLowerCase(),
      codigo: codigo.trim().toUpperCase(),
      etiquetaDispositivo: etiqueta,
      identidadPub: identidad,
      hardwareHash,
      hardwareNivel: 'NAVEGADOR',
    },
    false,
  );
  const s: Sesion = {
    token: r.token,
    usuarioId: r.usuarioId,
    dispositivoId: r.dispositivoId,
    username: r.username,
    hardwareHash,
    etiqueta,
    sig: { firmada: 1, kyber: 1, unica: 1 },
  };
  await guardar('cuenta', 'sesion', s);
  usarToken(s.token);
  cambiar({ sesion: s, conexion: 'conectando' });
  // Primero las claves: un aparato sin claves publicadas no puede recibir
  // nada, y el pedido de historial le llegaría a quien no puede cifrarle.
  await publicarClaves(PREKEYS_OBJETIVO);
  await sincronizarConversaciones();
  conectar();
  try {
    const h = await pedir<{ hayQuienResponda: boolean }>('POST', '/v1/dispositivos/historial', {});
    if (!h.hayQuienResponda) {
      cambiar({ aviso: 'Ningún otro aparato tuyo está en línea para mandarte el historial. Empiezas vacío.' });
    }
  } catch {
    // El historial es una ayuda: sin él, se empieza vacío.
  }
}

async function publicarClaves(cuantas: number): Promise<void> {
  const s = estado.sesion!;
  const claves = await cripto.generarClaves(s.sig.firmada, s.sig.kyber, s.sig.unica, cuantas);
  await pedir('PUT', '/v1/claves', claves);
  const sig = { firmada: s.sig.firmada + 1, kyber: s.sig.kyber + 1, unica: s.sig.unica + cuantas };
  const nueva = { ...s, sig };
  await guardar('cuenta', 'sesion', nueva);
  cambiar({ sesion: nueva });
}

async function reponerClaves(): Promise<void> {
  const e = await pedir<{ unicasDisponibles: number; objetivo: number; minimo: number }>('GET', '/v1/claves/estado');
  if (e.unicasDisponibles < (e.minimo ?? PREKEYS_MINIMO)) {
    await publicarClaves((e.objetivo ?? PREKEYS_OBJETIVO) - e.unicasDisponibles);
  }
}

export async function sincronizarConversaciones(): Promise<void> {
  const lista = await pedir<ConversacionResumen[]>('GET', '/v1/conversaciones');
  const viejas = new Map(estado.conversaciones.map((c) => [c.id, c]));
  const convs: Conversacion[] = lista.map((c) => ({
    ...c,
    noLeidos: viejas.get(c.id)?.noLeidos ?? 0,
    ultimo: viejas.get(c.id)?.ultimo,
  }));
  for (const v of viejas.keys()) if (!convs.some((c) => c.id === v)) await borrar('conversaciones', v);
  await publicarConversaciones(convs);
}

/** El título, como la app: en una directa, el @usuario y nunca el nombre que el otro se puso. */
export function titulo(c: ConversacionResumen): string {
  if (c.tipo === 'notas') return 'Nota para mí';
  if (c.tipo === 'directa') return c.nombre;
  return c.participantes[0]?.nombreMostrado && c.tipo !== 'grupo' ? c.participantes[0].nombreMostrado : c.nombre;
}

/** Revocado desde el teléfono, o la sesión venció: no queda nada en el navegador. */
async function perderVinculo(): Promise<void> {
  cerrarSocket();
  await vaciarTodo();
  cripto.olvidar();
  porId.clear();
  usarToken(null);
  const aviso = 'Este navegador ya no está vinculado a tu cuenta. Se borró todo lo que había guardado aquí.';
  try {
    sessionStorage.setItem(AVISO_GUARDADO, aviso);
  } catch {
    // Sin sessionStorage, el aviso se ve igual hasta recargar.
  }
  cambiar({ sesion: null, conexion: 'desvinculado', conversaciones: [], mensajes: {}, aviso });
}

export async function salir(): Promise<void> {
  // DIFERENCIA con la app: la web no tiene "cerrar sesión" a medias. Salir es
  // desvincular este navegador, y lo hace el servidor revocando el aparato.
  const s = estado.sesion;
  if (s) {
    try {
      await pedir('DELETE', `/v1/dispositivos/${s.dispositivoId}`);
    } catch {
      // Si no hay red, igual se borra todo aquí; el teléfono lo puede revocar.
    }
  }
  await perderVinculo();
  sessionStorage.removeItem(AVISO_GUARDADO);
  cambiar({ conexion: 'sin-vincular', aviso: null });
}

export const cerrarAviso = () => {
  sessionStorage.removeItem(AVISO_GUARDADO);
  cambiar({ aviso: null });
};

// ---------------------------------------------------------------------------
//  WebSocket, uno solo entre todas las pestañas
// ---------------------------------------------------------------------------

let ws: WebSocket | null = null;
let intentos = 0;
let latido: ReturnType<typeof setInterval> | null = null;
let soltarCandado: (() => void) | null = null;
let desfase = 0;
const ahora = () => Date.now() + desfase;

function mandar(s: Subida): boolean {
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  ws.send(JSON.stringify(s));
  return true;
}

/**
 * El servidor admite UN socket por aparato: uno nuevo cierra el anterior. Con
 * dos pestañas se echarían entre sí para siempre. Un candado de Web Locks
 * deja el socket a una sola; las demás lo dicen y esperan su turno.
 */
function conectar(): void {
  if (soltarCandado || !estado.sesion) return;
  if (!('locks' in navigator)) {
    soyTitular = true;
    abrirSocket();
    return;
  }
  void navigator.locks.request('wtfuck-socket', { ifAvailable: true }, async (candado) => {
    if (!candado) {
      cambiar({ conexion: 'otra-pestana' });
      // Esperar a que la otra se cierre, sin pelearle el socket.
      void navigator.locks.request('wtfuck-socket', async () => {
        // Le toca: lo que guardó la otra pestaña es la verdad, también el
        // almacén de Signal.
        cripto.recargar();
        await releerBoveda();
        await new Promise<void>((r) => {
          soltarCandado = r;
          soyTitular = true;
          abrirSocket();
        });
      });
      return;
    }
    await new Promise<void>((r) => {
      soltarCandado = r;
      soyTitular = true;
      abrirSocket();
    });
  });
}

function abrirSocket(): void {
  const s = estado.sesion;
  if (!s) return;
  cambiar({ conexion: 'conectando' });
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const w = new WebSocket(`${proto}://${location.host}/v1/ws?token=${encodeURIComponent(s.token)}`);
  ws = w;
  w.onopen = () => {
    intentos = 0;
    cambiar({ conexion: 'en-linea' });
    // El navegador no deja mandar pings de WebSocket: se usa el del protocolo.
    latido = setInterval(() => mandar({ type: 'ping' }), 25_000);
    void despachar();
  };
  w.onmessage = (ev) => {
    let b: Bajada;
    try {
      b = JSON.parse(String(ev.data)) as Bajada;
    } catch {
      return;
    }
    void encolarBajada(b);
  };
  w.onclose = (ev) => {
    if (latido) clearInterval(latido);
    latido = null;
    if (ws !== w) return;
    ws = null;
    // 1008 = "token invalido": revocado o vencido. No es un corte de red.
    if (ev.code === 1008) {
      void perderVinculo();
      return;
    }
    cambiar({ conexion: 'sin-red' });
    const espera = Math.min(30_000, 1000 * 2 ** Math.min(intentos++, 6));
    setTimeout(() => {
      if (estado.sesion && !ws) abrirSocket();
    }, espera);
  };
}

function cerrarSocket(): void {
  const w = ws;
  ws = null;
  w?.close();
  soltarCandado?.();
  soltarCandado = null;
}

// Las bajadas se procesan de a una: descifrar cambia el ratchet.
let colaBajadas: Promise<void> = Promise.resolve();
function encolarBajada(b: Bajada): Promise<void> {
  colaBajadas = colaBajadas.then(() => manejar(b)).catch((e) => console.warn('bajada', e));
  return colaBajadas;
}

async function manejar(b: Bajada): Promise<void> {
  switch (b.type) {
    case 'entrega':
      return recibir(b);
    case 'evento':
      if (['agregado_grupo', 'sacado_grupo', 'expulsado', 'grupo_renombrado'].includes(b.tipo)) {
        await sincronizarConversaciones().catch(() => undefined);
      } else if (b.tipo === 'mensaje_retirado' && b.detalle) {
        await actualizarMensaje(b.detalle, { retirado: true, texto: '', adjunto: undefined, cita: undefined });
      } else if (b.tipo === 'canal_publicacion' && b.conversacionId) {
        await publicacionNueva(b.conversacionId, b.actor, b.detalle ?? '', b.creadoEn);
      } else if (b.tipo === 'mensaje_reaccion' && b.detalle) {
        // "msgId:emoji:true|false": se relee el mensaje para tener el total.
        await refrescarReacciones(b.detalle.split(':')[0]).catch(() => undefined);
      }
      mandar({ type: 'acuse_evento', eventoIds: [b.eventoId] });
      return;
    case 'aceptado':
      if (b.servidorEn && Math.abs(b.servidorEn - Date.now()) > 2000) desfase = b.servidorEn - Date.now();
      await confirmarEnvio(b.sobreId, b.sinCopia ?? []);
      return;
    case 'error':
      if (b.sobreId) await actualizarMensaje(b.sobreId, { estado: 'fallido', motivo: b.motivo });
      return;
    case 'entregado':
      return actualizarMensaje(b.sobreId, { estado: 'entregado' });
    case 'leido':
      for (const id of b.mensajeIds) await actualizarMensaje(id, { estado: 'leido' });
      return;
    default:
      return;
  }
}

// ---------------------------------------------------------------------------
//  Recibir
// ---------------------------------------------------------------------------

async function recibir(e: Extract<Bajada, { type: 'entrega' }>): Promise<void> {
  const s = estado.sesion!;
  let carga: Carga | null = null;
  try {
    const claro =
      e.tipo === TIPO_CIFRADO.GRUPO
        ? await cripto.descifrarGrupo(e.origenDispositivo, e.cuerpo)
        : await cripto.descifrar(e.origenDispositivo, e.tipo, e.cuerpo);
    carga = deBytes(claro);
    if (esConClave(carga)) {
      try {
        await cripto.procesarDistribucion(e.origenDispositivo, carga.distribucion);
      } catch (x) {
        console.warn('clave de emisor', x);
      }
      carga = carga.interior;
    }
  } catch (x) {
    console.warn('no se pudo descifrar', x);
  }

  if (e.conversacionId && !estado.conversaciones.some((c) => c.id === e.conversacionId)) {
    await sincronizarConversaciones().catch(() => undefined);
  }
  if (carga !== null && e.conversacionId) await revisarIdentidad(e.conversacionId, e.origenDispositivo, e.origenUsername);

  const esMio = e.origenUsername.toLowerCase() === s.username.toLowerCase();
  if (carga === null) {
    // Igual que la app: se deja constancia y se acusa, para no reentregarlo
    // para siempre.
    await guardarMensaje({
      id: e.mensajeId || e.sobreId, conversacionId: e.conversacionId, autor: e.origenUsername, esMio,
      texto: '(no se pudo descifrar)', creadoEn: e.creadoEn, estado: esMio ? 'enviado' : 'entregado',
    });
  } else if (esTexto(carga) || esAdjunto(carga)) {
    const m: Mensaje = {
      id: e.mensajeId || e.sobreId, conversacionId: e.conversacionId, autor: e.origenUsername, esMio,
      texto: '', creadoEn: e.creadoEn, estado: esMio ? 'enviado' : 'entregado',
      leidoPorMi: esMio ? true : undefined,
    };
    if (esTexto(carga)) {
      m.texto = carga.cuerpo;
      if (carga.respondeA && carga.respondeTexto != null) {
        m.cita = { id: carga.respondeA, texto: carga.respondeTexto, autor: carga.respondeAutor ?? '' };
      }
    } else {
      const clase = (Object.keys(LIMITES).includes(carga.clase) ? carga.clase : 'documento') as Clase;
      // Igual que la app: "ver una vez" no trae ni pie ni miniatura que mostrar,
      // y el spoiler y la forma solo valen donde tienen sentido.
      m.texto = carga.unaVez ? '' : carga.pie;
      m.adjunto = {
        adjuntoId: carga.adjuntoId, clase, clave: carga.clave, nonce: carga.nonce,
        mime: carga.mime, nombre: nombreSeguro(carga.nombre), bytes: carga.bytes, ancho: carga.ancho, alto: carga.alto,
        duracionMs: carga.duracionMs, miniatura: carga.unaVez ? '' : miniaturaSegura(carga.miniatura), onda: carga.onda,
        unaVez: carga.unaVez, spoiler: carga.spoiler && (clase === 'imagen' || clase === 'video'), listo: true,
      };
    }
    const nuevo = await guardarMensaje(m);
    if (!esMio && nuevo) avisar(m);
    if (!esMio && abierta === e.conversacionId) void marcarLeida(e.conversacionId);
  } else if (esEdicion(carga)) {
    // DIFERENCIA con la app: solo el AUTOR puede editar su mensaje. La app no
    // lo comprueba, y cualquiera en el grupo podría reescribir lo de otro.
    const original = porId.get(carga.mensajeId);
    if (original && original.autor.toLowerCase() === e.origenUsername.toLowerCase() && !original.retirado) {
      await actualizarMensaje(original.id, { texto: carga.textoNuevo, editado: true });
    }
  } else if (esHistorial(carga)) {
    for (const h of carga.mensajes) {
      await guardarMensaje({
        id: h.id, conversacionId: carga.conversacionId, autor: h.autor, esMio: h.esMio, texto: h.texto,
        creadoEn: h.creadoEn, estado: h.esMio ? 'entregado' : 'leido', leidoPorMi: true, deHistorial: true,
      });
    }
  }
  mandar({ type: 'acuse', sobreIds: [e.sobreId] });
}

// ---------------------------------------------------------------------------
//  Avisos del navegador
// ---------------------------------------------------------------------------
//
// Solo con la página abierta (en otra pestaña o minimizada): con el navegador
// cerrado haría falta Web Push. Igual que la app (Notificaciones.kt): el
// texto NUNCA va en el aviso, porque un aviso se ve en la pantalla aunque
// nadie haya abierto nada. Lo único que se elige es si dice quién escribió.

let alTocarAviso: (conversacionId: string) => void = () => {};
export function cuandoSeToqueUnAviso(f: (conversacionId: string) => void): void {
  alTocarAviso = f;
}

export async function pedirPermisoDeAvisos(): Promise<void> {
  if (!('Notification' in window)) return;
  const p = await Notification.requestPermission();
  cambiar({ avisos: { ...estado.avisos, permiso: p } });
}

export function avisosConQuien(si: boolean): void {
  try {
    localStorage.setItem(PREF_QUIEN, si ? '0' : '1');
  } catch {
    // Sin localStorage, vale hasta recargar.
  }
  cambiar({ avisos: { ...estado.avisos, mostrarQuien: si } });
}

function avisar(m: Mensaje): void {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  if (!document.hidden && abierta === m.conversacionId) return;
  const c = estado.conversaciones.find((x) => x.id === m.conversacionId);
  const esGrupo = c?.tipo === 'grupo';
  // Los mismos textos que la app.
  const [titulo_, cuerpo] = !estado.avisos.mostrarQuien
    ? ['wtfuck', 'Tienes un mensaje nuevo']
    : c?.tipo === 'canal'
      ? [titulo(c), 'Publicación nueva']
      : esGrupo
      ? [c ? titulo(c) : 'Grupo', `@${m.autor} escribió en el grupo`]
      : [`@${m.autor}`, 'Te escribió'];
  try {
    const n = new Notification(titulo_, { body: cuerpo, tag: m.conversacionId });
    n.onclick = () => {
      window.focus();
      alTocarAviso(m.conversacionId);
      n.close();
    };
  } catch {
    // Algunos navegadores solo avisan desde un service worker.
  }
}

// ---------------------------------------------------------------------------
//  Cambio de identidad
// ---------------------------------------------------------------------------
//
// Igual que la app: se confía al primer uso y, si la identidad de un aparato
// cambia, se avisa en el chat. Puede ser una reinstalación o un aparato nuevo;
// si no, alguien en el medio. Comparar la huella es lo único que lo descarta.

async function revisarIdentidad(conversacionId: string, dispositivo: string, username: string): Promise<void> {
  if (!(await cripto.identidadCambio(dispositivo))) return;
  const c = estado.conversaciones.find((x) => x.id === conversacionId);
  if (!c || c.identidadCambio?.some((x) => x.dispositivo === dispositivo)) return;
  const nuevo = { ...c, identidadCambio: [...(c.identidadCambio ?? []), { dispositivo, username }] };
  await publicarConversaciones(estado.conversaciones.map((x) => (x.id === c.id ? nuevo : x)));
}

export async function identidadRevisada(conversacionId: string): Promise<void> {
  const c = estado.conversaciones.find((x) => x.id === conversacionId);
  if (!c?.identidadCambio?.length) return;
  for (const d of c.identidadCambio) await cripto.identidadVista(d.dispositivo);
  await publicarConversaciones(estado.conversaciones.map((x) => (x.id === c.id ? { ...x, identidadCambio: [] } : x)));
}

/** Las huellas con cada aparato de la otra persona, para comparar en voz alta. */
export async function huellasDe(conversacionId: string): Promise<{ username: string; etiqueta: string; digitos: string }[]> {
  const ds = await destinos(conversacionId, true);
  const s = estado.sesion!;
  const out: { username: string; etiqueta: string; digitos: string }[] = [];
  for (const d of ds) {
    if (d.usuarioId === s.usuarioId) continue;
    try {
      const h = await cripto.huella(s.usuarioId, d.usuarioId, d.dispositivoId);
      out.push({ username: d.username, etiqueta: d.etiqueta, digitos: h.digitos });
    } catch {
      // Sin sesión con ese aparato todavía: no hay identidad que comparar.
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
//  Leer
// ---------------------------------------------------------------------------

let abierta: string | null = null;

export async function abrirConversacion(id: string | null): Promise<void> {
  abierta = id;
  if (id) await marcarLeida(id);
}

async function marcarLeida(conversacionId: string): Promise<void> {
  if (!soyTitular) {
    canal?.postMessage({ tipo: 'leer', conversacionId } satisfies MensajeCanal);
    return;
  }
  const sinLeer = (estado.mensajes[conversacionId] ?? []).filter((m) => !m.esMio && !m.leidoPorMi);
  const c = estado.conversaciones.find((x) => x.id === conversacionId);
  if (c && c.noLeidos) await publicarConversaciones(estado.conversaciones.map((x) => (x.id === conversacionId ? { ...x, noLeidos: 0 } : x)));
  if (!sinLeer.length) return;
  if (mandar({ type: 'acuse_lectura', conversacionId, mensajeIds: sinLeer.map((m) => m.id).slice(0, 200) })) {
    for (const m of sinLeer) await actualizarMensaje(m.id, { leidoPorMi: true });
  }
}

// ---------------------------------------------------------------------------
//  Enviar
// ---------------------------------------------------------------------------

function siguienteHora(conversacionId: string): number {
  const ultimos = estado.mensajes[conversacionId] ?? [];
  const ultimo = ultimos.length ? ultimos[ultimos.length - 1].creadoEn : 0;
  return Math.max(ahora(), ultimo + 1);
}

function pedirDespacho(): void {
  if (soyTitular) void despachar();
  else canal?.postMessage({ tipo: 'despachar' } satisfies MensajeCanal);
}

export async function enviarTexto(conversacionId: string, cuerpo: string, citado?: Mensaje): Promise<void> {
  const s = estado.sesion;
  if (!s || !cuerpo.trim()) return;
  await guardarMensaje({
    id: nuevoId(), conversacionId, autor: s.username, esMio: true, texto: cuerpo,
    creadoEn: siguienteHora(conversacionId), estado: 'pendiente', leidoPorMi: true,
    // Como la app: solo se cita dentro de la misma conversación.
    cita: citado && citado.conversacionId === conversacionId
      ? { id: citado.id, texto: (citado.texto || citado.adjunto?.nombre || '').slice(0, 140), autor: citado.autor }
      : undefined,
  });
  pedirDespacho();
}

/**
 * Un archivo: se prepara, se cifra, se sube DIRECTO al almacén con la URL
 * firmada (los bytes no pasan por el servidor, como en la app) y recién
 * entonces sale el mensaje con la clave.
 */
/** Una nota de voz: un archivo de clase `nota_voz`, con su onda y su duración. */
export const enviarNotaDeVoz = (conversacionId: string, n: NotaGrabada) => enviarArchivo(conversacionId, n.archivo, '', n);

export async function enviarArchivo(conversacionId: string, f: File, pie: string, voz?: NotaGrabada): Promise<void> {
  const s = estado.sesion;
  if (!s) return;
  const clase: Clase = voz ? 'nota_voz' : claseDe(f.type);
  let datos: ArrayBuffer;
  let mime = f.type || 'application/octet-stream';
  let ancho = 0;
  let alto = 0;
  let miniatura = '';
  let duracionMs = voz?.duracionMs ?? 0;
  if (clase === 'imagen') {
    const p = await prepararImagen(f);
    ({ datos, mime, ancho, alto, miniatura } = p);
  } else {
    datos = await f.arrayBuffer();
    if (clase === 'video') ({ ancho, alto, duracionMs, miniatura } = await prepararVideo(f));
  }
  if (datos.byteLength + SOBRECOSTO > LIMITES[clase]) {
    throw new Error(`El límite para ${clase} es ${tamanoLegible(LIMITES[clase])}.`);
  }
  const id = nuevoId();
  const adj: AdjuntoLocal = {
    clase, mime, nombre: nombreSeguro(f.name), bytes: datos.byteLength, ancho, alto, duracionMs,
    miniatura, onda: voz?.onda, unaVez: false, spoiler: false, listo: false,
  };
  await guardarMensaje({
    id, conversacionId, autor: s.username, esMio: true, texto: pie, adjunto: adj,
    creadoEn: siguienteHora(conversacionId), estado: 'pendiente', leidoPorMi: true,
  });
  // Lo mío se ve al instante, sin bajarlo de vuelta.
  cambiar({ archivos: { ...estado.archivos, [id]: { estado: 'listo', url: URL.createObjectURL(new Blob([datos], { type: mime })) } } });
  try {
    const c = await cifrarArchivo(datos);
    const r = await pedir<{ adjuntoId: string; urlSubida: string }>('POST', '/v1/adjuntos', {
      conversacionId, historiaId: null, clase, bytes: c.cifrado.byteLength, mime, nombre: adj.nombre, ancho, alto, duracionMs,
    });
    const subida = await fetch(r.urlSubida, { method: 'PUT', body: c.cifrado, headers: { 'Content-Type': 'application/octet-stream' } });
    if (!subida.ok) throw new Error(`El almacén rechazó el archivo (${subida.status}).`);
    await pedir('POST', `/v1/adjuntos/${r.adjuntoId}/confirmar`);
    await actualizarMensaje(id, { adjunto: { ...adj, adjuntoId: r.adjuntoId, clave: c.clave, nonce: c.nonce, listo: true } });
    pedirDespacho();
  } catch (x) {
    await actualizarMensaje(id, { estado: 'fallido', motivo: x instanceof Error ? x.message : 'No se pudo subir el archivo.' });
  }
}

/** Baja, verifica y descifra un archivo. Queda en memoria, nunca en la bóveda. */
export async function descargar(m: Mensaje): Promise<void> {
  const a = m.adjunto;
  if (!a?.adjuntoId || !a.clave || !a.nonce || estado.archivos[m.id]) return;
  const poner = (x: Archivo) => cambiar({ archivos: { ...estado.archivos, [m.id]: x } });
  poner({ estado: 'bajando' });
  try {
    const info = await pedir<{ urlDescarga: string }>('GET', `/v1/adjuntos/${a.adjuntoId}`);
    const r = await fetch(info.urlDescarga);
    if (!r.ok) throw new Error(`No se pudo bajar (${r.status}).`);
    const claro = await descifrarArchivo(await r.arrayBuffer(), a.clave, a.nonce);
    poner({ estado: 'listo', url: URL.createObjectURL(new Blob([claro], { type: mimeParaReproducir(a.mime, a.nombre) })) });
  } catch (x) {
    poner({ estado: 'error', error: x instanceof Error ? x.message : 'No se pudo bajar.' });
  }
}

/** Editar: el servidor lo autoriza y la edición viaja cifrada, como en la app. */
export async function editar(m: Mensaje, nuevo: string): Promise<void> {
  if (!m.esMio || m.retirado || !nuevo.trim() || nuevo === m.texto) return;
  await pedir('POST', `/v1/mensajes/${m.id}/editar`);
  await actualizarMensaje(m.id, { texto: nuevo, editado: true });
  // Sin registrar: no es un mensaje nuevo, es el cambio de uno que existe.
  await cifrarYMandar(m.conversacionId, nuevoId(), edicion(m.id, nuevo), ahora());
}

/** "Eliminar para todos". El servidor decide si se puede. */
export async function retirar(m: Mensaje): Promise<void> {
  await pedir('POST', `/v1/mensajes/${m.id}/retirar`);
  await actualizarMensaje(m.id, { retirado: true, texto: '', adjunto: undefined, cita: undefined });
}

/**
 * Una publicación nueva en un canal: no es un mensaje (vive en el servidor),
 * así que solo se mueve la lista, se avisa y la vista abierta recarga.
 */
async function publicacionNueva(canal: string, autor: string, extracto: string, creadoEn: number): Promise<void> {
  if (!estado.conversaciones.some((c) => c.id === canal)) await sincronizarConversaciones().catch(() => undefined);
  const convs = estado.conversaciones.map((c) => c.id !== canal ? c : {
    ...c,
    noLeidos: c.noLeidos + (abierta === canal ? 0 : 1),
    ultimo: { texto: extracto, creadoEn, esMio: false },
  });
  await publicarConversaciones(convs);
  cambiar({ canalesNuevos: { ...estado.canalesNuevos, [canal]: (estado.canalesNuevos[canal] ?? 0) + 1 } });
  avisar({ id: nuevoId(), conversacionId: canal, autor, esMio: false, texto: '', creadoEn, estado: 'entregado' });
}

/** Una reacción por persona: otra reemplaza la anterior; la misma la quita. */
export async function reaccionar(m: Mensaje, emoji: string): Promise<void> {
  const ya = m.reacciones?.some((r) => r.emoji === emoji && r.mia) ?? false;
  const meta = await pedir<{ reacciones: Reaccion[] }>('POST', '/v1/mensajes/reaccion', { mensajeId: m.id, emoji, poner: !ya });
  await actualizarMensaje(m.id, { reacciones: meta.reacciones });
}

async function refrescarReacciones(mensajeId: string): Promise<void> {
  if (!porId.has(mensajeId)) return;
  const meta = await pedir<{ reacciones: Reaccion[] }>('GET', `/v1/mensajes/${mensajeId}`);
  await actualizarMensaje(mensajeId, { reacciones: meta.reacciones });
}

let despachando = false;

/** Manda lo pendiente, en orden. Sin red, espera a la próxima conexión. */
async function despachar(): Promise<void> {
  if (despachando) return;
  despachando = true;
  try {
    // Un archivo que todavía se está subiendo espera: el mensaje lleva su clave.
    const pendientes = [...porId.values()]
      .filter((m) => m.esMio && m.estado === 'pendiente' && (!m.adjunto || m.adjunto.listo))
      .sort((a, b) => a.creadoEn - b.creadoEn);
    for (const m of pendientes) {
      if (!ws || ws.readyState !== WebSocket.OPEN) return;
      try {
        await despacharUno(m);
      } catch (x) {
        if (x instanceof ErrorApi && x.estado === 429) {
          setTimeout(() => void despachar(), 10_000);
          return;
        }
        if (x instanceof ErrorApi && x.estado >= 400 && x.estado < 500) {
          await actualizarMensaje(m.id, { estado: 'fallido', motivo: x.message });
          continue;
        }
        return; // red: se reintenta al reconectar
      }
    }
  } finally {
    despachando = false;
  }
}

interface Distribucion { distId: string; repartidaA: string[]; pendienteA?: string[] }
const destinosCache = new Map<string, { hasta: number; lista: Destino[] }>();
const enVuelo = new Map<string, { conversacionId: string; repartirA: string[] }>();

async function destinos(conversacionId: string, forzar = false): Promise<Destino[]> {
  const c = destinosCache.get(conversacionId);
  if (!forzar && c && c.hasta > Date.now()) return c.lista;
  const r = await pedir<{ destinos: Destino[] }>('GET', `/v1/conversaciones/${conversacionId}/destinos`);
  destinosCache.set(conversacionId, { hasta: Date.now() + 30_000, lista: r.destinos });
  return r.destinos;
}

async function asegurarSesion(d: Destino, conversacionId: string): Promise<boolean> {
  if (await cripto.tieneSesion(d.dispositivoId)) return true;
  try {
    const p = await pedir<PaqueteClaves>('GET', `/v1/claves/dispositivo/${d.dispositivoId}`);
    await cripto.abrirSesion(d.dispositivoId, p);
    await revisarIdentidad(conversacionId, d.dispositivoId, d.username);
    return true;
  } catch (x) {
    console.warn(`sin sesión con ${d.username} (${d.etiqueta})`, x);
    return false;
  }
}

function cargaDe(m: Mensaje): Carga {
  const a = m.adjunto;
  if (a?.adjuntoId && a.clave && a.nonce) {
    return adjunto({
      adjuntoId: a.adjuntoId, clase: a.clase, clave: a.clave, nonce: a.nonce, mime: a.mime, nombre: a.nombre,
      bytes: a.bytes, ancho: a.ancho, alto: a.alto, duracionMs: a.duracionMs, pie: m.texto, miniatura: a.miniatura,
      onda: a.onda ?? '',
    });
  }
  return m.cita ? respuesta(m.texto, m.cita) : texto(m.texto);
}

async function despacharUno(m: Mensaje): Promise<void> {
  await pedir('POST', '/v1/mensajes', {
    mensajeId: m.id, conversacionId: m.conversacionId, respondeA: m.cita?.id ?? null, menciones: mencionesEn(m.texto),
    reenviadoDe: null, adjuntoId: m.adjunto?.adjuntoId ?? null, clase: '',
  });
  const r = await cifrarYMandar(m.conversacionId, m.id, cargaDe(m), m.creadoEn);
  if (r === 'sin-claves') {
    await actualizarMensaje(m.id, { estado: 'fallido', motivo: 'Nadie en esa conversación publicó sus claves todavía.' });
  }
}

/**
 * Cifra una carga para todos los aparatos de la conversación y la manda. Por
 * pares, o con clave de emisor en un grupo. No registra nada en el servidor:
 * eso lo hace quien llama, si corresponde.
 */
async function cifrarYMandar(conversacionId: string, sobreId: string, carga: Carga, creadoEn: number): Promise<'ok' | 'sin-claves'> {
  const s = estado.sesion!;
  const m = { conversacionId, id: sobreId, creadoEn };
  const conv = estado.conversaciones.find((c) => c.id === m.conversacionId);
  const ds = await destinos(m.conversacionId);
  const conSesion: Destino[] = [];
  for (const d of ds) if (await asegurarSesion(d, m.conversacionId)) conSesion.push(d);

  const copias: CopiaCifrada[] = [];
  let repartirA: string[] = [];

  if (conv?.tipo === 'grupo' && conSesion.length) {
    const guardada = await leer<Distribucion>('cuenta', `dist:${m.conversacionId}`);
    const vigentes = new Set(conSesion.map((d) => d.dispositivoId));
    const salieron = (guardada?.repartidaA ?? []).some((d) => !vigentes.has(d));
    const dist: Distribucion = !guardada || salieron ? { distId: nuevoId(), repartidaA: [] } : guardada;
    const skdm = await cripto.crearDistribucion(s.dispositivoId, dist.distId);
    const cuerpo = await cripto.cifrarGrupo(s.dispositivoId, dist.distId, aBytes(carga));
    await guardar('cuenta', `dist:${m.conversacionId}`, dist);
    const yaLaTienen = conSesion.filter((d) => dist.repartidaA.includes(d.dispositivoId));
    if (yaLaTienen.length) copias.push({ destinos: yaLaTienen.map((d) => d.dispositivoId), cuerpo, tipo: TIPO_CIFRADO.GRUPO });
    // Igual que la app: el que no tiene la clave la recibe junto con el
    // mensaje, por pares y SIN relleno.
    const envuelto = aBytes({ type: 'com.wtfuck.protocol.Carga.ConClaveGrupo', distribucion: skdm, interior: carga }, false);
    for (const d of conSesion.filter((x) => !dist.repartidaA.includes(x.dispositivoId))) {
      const c = await cripto.cifrar(d.dispositivoId, envuelto);
      copias.push({ destinos: [d.dispositivoId], cuerpo: c.cuerpo, tipo: c.tipo });
      repartirA.push(d.dispositivoId);
    }
  } else {
    const claro = aBytes(carga);
    for (const d of conSesion) {
      const c = await cripto.cifrar(d.dispositivoId, claro);
      copias.push({ destinos: [d.dispositivoId], cuerpo: c.cuerpo, tipo: c.tipo });
    }
    repartirA = [];
  }

  if (!copias.length && ds.length) return 'sin-claves';
  // DIFERENCIA con la app: la clave de emisor se da por repartida cuando el
  // servidor ACEPTA el envío, no cuando el socket lo escribe.
  enVuelo.set(m.id, { conversacionId: m.conversacionId, repartirA });
  if (!mandar({ type: 'enviar', sobreId: m.id, conversacionId: m.conversacionId, creadoEn: m.creadoEn, copias })) {
    enVuelo.delete(m.id);
    throw new Error('sin socket');
  }
  return 'ok';
}

async function confirmarEnvio(sobreId: string, sinCopia: string[]): Promise<void> {
  const v = enVuelo.get(sobreId);
  enVuelo.delete(sobreId);
  await actualizarMensaje(sobreId, { estado: 'enviado' });
  if (sinCopia.length) {
    // DIFERENCIA con la app: no se reenvía el mensaje entero (puede ser un
    // aparato sin claves, y eso daría un bucle). Se refrescan los destinos
    // para el próximo.
    if (v) destinosCache.delete(v.conversacionId);
  }
  if (v && v.repartirA.length) {
    const d = await leer<Distribucion>('cuenta', `dist:${v.conversacionId}`);
    if (d) await guardar('cuenta', `dist:${v.conversacionId}`, { ...d, repartidaA: [...new Set([...d.repartidaA, ...v.repartirA])] });
  }
}

/** Los 60 dígitos para comparar con el teléfono del otro. */
export async function huellaCon(otroUsuarioId: string, otroDispositivo: string) {
  return cripto.huella(estado.sesion!.usuarioId, otroUsuarioId, otroDispositivo);
}
