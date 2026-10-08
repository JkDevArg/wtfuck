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
  aBytes, b64, deBytes, esConClave, esHistorial, esTexto, mencionesEn, nuevoId, texto, TIPO_CIFRADO,
  type Bajada, type Carga, type ConversacionResumen, type CopiaCifrada, type Destino, type PaqueteClaves,
  type Subida, type VincularHecho,
} from './protocolo';

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

export interface Mensaje {
  id: string;
  conversacionId: string;
  autor: string;
  esMio: boolean;
  texto: string;
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
}

export type Conexion = 'sin-vincular' | 'conectando' | 'en-linea' | 'sin-red' | 'otra-pestana' | 'desvinculado';

export interface Instantanea {
  sesion: Sesion | null;
  conexion: Conexion;
  conversaciones: Conversacion[];
  mensajes: Record<string, Mensaje[]>;
  aviso: string | null;
}

let estado: Instantanea = { sesion: null, conexion: 'sin-vincular', conversaciones: [], mensajes: {}, aviso: null };
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
  const lista = (estado.mensajes[m.conversacionId] ?? []).map((x) => (x.id === id ? nuevo : x));
  cambiar({ mensajes: { ...estado.mensajes, [m.conversacionId]: lista } });
}

async function tocarConversacion(m: Mensaje): Promise<void> {
  const convs = estado.conversaciones.map((c) => {
    if (c.id !== m.conversacionId) return c;
    const masNuevo = !c.ultimo || m.creadoEn >= c.ultimo.creadoEn;
    const sumar = !m.esMio && !m.leidoPorMi && !m.deHistorial && abierta !== c.id ? 1 : 0;
    return {
      ...c,
      noLeidos: c.noLeidos + sumar,
      ultimo: masNuevo ? { texto: m.texto, creadoEn: m.creadoEn, esMio: m.esMio } : c.ultimo,
    };
  });
  await publicarConversaciones(convs);
}

async function publicarConversaciones(convs: Conversacion[]): Promise<void> {
  const orden = [...convs].sort((a, b) => (b.ultimo?.creadoEn ?? 0) - (a.ultimo?.creadoEn ?? 0));
  cambiar({ conversaciones: orden });
  await Promise.all(orden.map((c) => guardar('conversaciones', c.id, c)));
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
    abrirSocket();
    return;
  }
  void navigator.locks.request('wtfuck-socket', { ifAvailable: true }, async (candado) => {
    if (!candado) {
      cambiar({ conexion: 'otra-pestana' });
      // Esperar a que la otra se cierre, sin pelearle el socket.
      void navigator.locks.request('wtfuck-socket', () => new Promise<void>((r) => { soltarCandado = r; abrirSocket(); }));
      return;
    }
    await new Promise<void>((r) => {
      soltarCandado = r;
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

  const esMio = e.origenUsername.toLowerCase() === s.username.toLowerCase();
  if (carga === null) {
    // Igual que la app: se deja constancia y se acusa, para no reentregarlo
    // para siempre.
    await guardarMensaje({
      id: e.mensajeId || e.sobreId, conversacionId: e.conversacionId, autor: e.origenUsername, esMio,
      texto: '(no se pudo descifrar)', creadoEn: e.creadoEn, estado: esMio ? 'enviado' : 'entregado',
    });
  } else if (esTexto(carga)) {
    await guardarMensaje({
      id: e.mensajeId || e.sobreId, conversacionId: e.conversacionId, autor: e.origenUsername, esMio,
      texto: carga.cuerpo, creadoEn: e.creadoEn, estado: esMio ? 'enviado' : 'entregado',
      leidoPorMi: esMio ? true : undefined,
    });
    if (!esMio && abierta === e.conversacionId) void marcarLeida(e.conversacionId);
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
//  Leer
// ---------------------------------------------------------------------------

let abierta: string | null = null;

export async function abrirConversacion(id: string | null): Promise<void> {
  abierta = id;
  if (id) await marcarLeida(id);
}

async function marcarLeida(conversacionId: string): Promise<void> {
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

export async function enviarTexto(conversacionId: string, cuerpo: string): Promise<void> {
  const s = estado.sesion;
  if (!s || !cuerpo.trim()) return;
  const ultimos = estado.mensajes[conversacionId] ?? [];
  const ultimo = ultimos.length ? ultimos[ultimos.length - 1].creadoEn : 0;
  await guardarMensaje({
    id: nuevoId(), conversacionId, autor: s.username, esMio: true, texto: cuerpo,
    creadoEn: Math.max(ahora(), ultimo + 1), estado: 'pendiente', leidoPorMi: true,
  });
  void despachar();
}

let despachando = false;

/** Manda lo pendiente, en orden. Sin red, espera a la próxima conexión. */
async function despachar(): Promise<void> {
  if (despachando) return;
  despachando = true;
  try {
    const pendientes = [...porId.values()].filter((m) => m.esMio && m.estado === 'pendiente').sort((a, b) => a.creadoEn - b.creadoEn);
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

async function asegurarSesion(d: Destino): Promise<boolean> {
  if (await cripto.tieneSesion(d.dispositivoId)) return true;
  try {
    const p = await pedir<PaqueteClaves>('GET', `/v1/claves/dispositivo/${d.dispositivoId}`);
    await cripto.abrirSesion(d.dispositivoId, p);
    return true;
  } catch (x) {
    console.warn(`sin sesión con ${d.username} (${d.etiqueta})`, x);
    return false;
  }
}

async function despacharUno(m: Mensaje): Promise<void> {
  const s = estado.sesion!;
  await pedir('POST', '/v1/mensajes', {
    mensajeId: m.id, conversacionId: m.conversacionId, respondeA: null, menciones: mencionesEn(m.texto),
    reenviadoDe: null, adjuntoId: null, clase: '',
  });
  const conv = estado.conversaciones.find((c) => c.id === m.conversacionId);
  const ds = await destinos(m.conversacionId);
  const conSesion: Destino[] = [];
  for (const d of ds) if (await asegurarSesion(d)) conSesion.push(d);

  const carga = texto(m.texto);
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

  if (!copias.length && ds.length) {
    await actualizarMensaje(m.id, { estado: 'fallido', motivo: 'Nadie en esa conversación publicó sus claves todavía.' });
    return;
  }
  // DIFERENCIA con la app: la clave de emisor se da por repartida cuando el
  // servidor ACEPTA el envío, no cuando el socket lo escribe.
  enVuelo.set(m.id, { conversacionId: m.conversacionId, repartirA });
  if (!mandar({ type: 'enviar', sobreId: m.id, conversacionId: m.conversacionId, creadoEn: m.creadoEn, copias })) {
    enVuelo.delete(m.id);
    throw new Error('sin socket');
  }
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
