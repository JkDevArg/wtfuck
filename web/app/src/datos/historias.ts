// Historias (estados) en la web. Espeja el módulo de la app:
//
//  - Una historia va CIFRADA E2EE, un sobre por dispositivo de la audiencia
//    (como un mensaje), por HTTP a POST /v1/historias/{id}/sobres.
//  - El servidor NO guarda el contenido: solo metadatos (quién, cuándo, a quién,
//    quién la vio) y caduca a 24 h. El contenido llega por el buzón y se
//    descifra en motor.ts (recibir), que llama a `guardarContenidoHistoria`.
//  - Por eso el contenido se PERSISTE en el navegador (boveda, store 'cuenta',
//    prefijo 'historia:'): si no, recargar la pestaña lo perdería (el sobre ya
//    se entregó y no vuelve).
//
// Responder/reaccionar a una historia es un mensaje directo normal; eso vive en
// motor.ts, no acá.
import { pedir } from './api';
import { guardar, leer, borrar } from './boveda';
import { cifrarHistoriaPara, destinosHistoria, sesionActual } from './motor';
import {
  adjunto, historia as construirHistoria, nuevoId, ClaseHistoria, RUTA_HISTORIAS,
  type CargaAdjunto, type CargaHistoria, type HistoriaInfo, type HistoriaMia, type VistasDeHistoria,
} from './protocolo';
import {
  cifrarArchivo, descifrarArchivo, LIMITES, miniaturaSegura, prepararImagen, SOBRECOSTO,
} from './archivos';

/** Colores de fondo de un estado de texto (espejo de FONDOS_HISTORIA de la app). */
export const FONDOS_HISTORIA = ['#1D9E75', '#534AB7', '#D4537E', '#BA7517', '#185FA5', '#0F6E56', '#993C1D'];

export const TOPE_TEXTO_HISTORIA = 280;

/** Una historia tal como la maneja la web: metadato + contenido (si llegó). */
export interface HistoriaLocal {
  historiaId: string;
  autorUsername: string;
  esMia: boolean;
  clase: string;
  creadaEn: number;
  expiraEn: number;
  /** La vi yo (solo para ajenas). */
  vista: boolean;
  /** Contenido descifrado (si el sobre ya llegó). */
  texto?: string;
  fondo?: string;
  adjunto?: CargaAdjunto | null;
  tieneContenido: boolean;
  /** Solo las mías: a cuántos fue y cuántos la vieron (null = confirmaciones off). */
  destinatarios?: number;
  vistas?: number | null;
}

/** Lo que se persiste del contenido de un sobre de historia. */
interface ContenidoGuardado {
  autor: string;
  esMia: boolean;
  creadaEn: number;
  texto: string;
  fondo: string;
  adjunto: CargaAdjunto | null;
  clase: string;
}

// --- Store observable (para useSyncExternalStore) --------------------------
const porId = new Map<string, HistoriaLocal>();
const contenidos = new Map<string, ContenidoGuardado>();
let snap: HistoriaLocal[] = [];
const oyentes = new Set<() => void>();

function recomputar(): void {
  const ahora = Date.now();
  snap = [...porId.values()]
    .filter((h) => h.expiraEn > ahora)
    .sort((a, b) => b.creadaEn - a.creadaEn);
  oyentes.forEach((f) => f());
}

export function suscribirHistorias(f: () => void): () => void {
  oyentes.add(f);
  return () => { oyentes.delete(f); };
}
export function instantaneaHistorias(): HistoriaLocal[] {
  return snap;
}

function fusionar(id: string, parcial: Partial<HistoriaLocal> & { historiaId: string }): void {
  const prev = porId.get(id);
  const cont = contenidos.get(id);
  const h: HistoriaLocal = {
    historiaId: id,
    autorUsername: parcial.autorUsername ?? prev?.autorUsername ?? cont?.autor ?? '',
    esMia: parcial.esMia ?? prev?.esMia ?? cont?.esMia ?? false,
    clase: parcial.clase ?? prev?.clase ?? cont?.clase ?? 'texto',
    creadaEn: parcial.creadaEn ?? prev?.creadaEn ?? cont?.creadaEn ?? Date.now(),
    expiraEn: parcial.expiraEn ?? prev?.expiraEn ?? Date.now() + 24 * 3600_000,
    vista: parcial.vista ?? prev?.vista ?? false,
    texto: cont?.texto,
    fondo: cont?.fondo,
    adjunto: cont?.adjunto,
    tieneContenido: !!cont,
    destinatarios: parcial.destinatarios ?? prev?.destinatarios,
    vistas: parcial.vistas ?? prev?.vistas,
  };
  porId.set(id, h);
}

// --- Recepción del contenido (lo llama motor.ts al descifrar un sobre) ------
export async function guardarContenidoHistoria(
  autorUsername: string, carga: CargaHistoria, creadaEn: number, esMia = false,
): Promise<void> {
  const cont: ContenidoGuardado = {
    autor: autorUsername, esMia, creadaEn,
    texto: carga.texto ?? '', fondo: carga.fondo ?? '', adjunto: carga.adjunto ?? null, clase: carga.clase,
  };
  contenidos.set(carga.historiaId, cont);
  await guardar('cuenta', `historia:${carga.historiaId}`, cont).catch(() => {});
  fusionar(carga.historiaId, { historiaId: carga.historiaId, autorUsername, esMia, clase: carga.clase, creadaEn });
  recomputar();
}

// --- Sincronización con el servidor ----------------------------------------
let sincronizando: Promise<void> | null = null;

export function sincronizarHistorias(): Promise<void> {
  sincronizando ??= hacerSync().finally(() => { sincronizando = null; });
  return sincronizando;
}

async function hacerSync(): Promise<void> {
  const [paraMi, mias] = await Promise.all([
    pedir<{ historias: HistoriaInfo[] }>('GET', RUTA_HISTORIAS).then((r) => r.historias).catch(() => [] as HistoriaInfo[]),
    pedir<{ historias: HistoriaMia[] }>('GET', `${RUTA_HISTORIAS}/mias`).then((r) => r.historias).catch(() => [] as HistoriaMia[]),
  ]);

  const vivos = new Set<string>();
  const s = sesionActual();

  for (const info of paraMi) {
    vivos.add(info.historiaId);
    await asegurarContenido(info.historiaId);
    fusionar(info.historiaId, {
      historiaId: info.historiaId, autorUsername: info.autorUsername, esMia: false,
      clase: info.clase, creadaEn: info.creadaEn, expiraEn: info.expiraEn, vista: info.vista,
    });
  }
  for (const mia of mias) {
    vivos.add(mia.historiaId);
    await asegurarContenido(mia.historiaId);
    fusionar(mia.historiaId, {
      historiaId: mia.historiaId, autorUsername: s?.username ?? '', esMia: true,
      clase: mia.clase, creadaEn: mia.creadaEn, expiraEn: mia.expiraEn,
      destinatarios: mia.destinatarios, vistas: mia.vistas,
    });
  }

  // Limpiar las que ya no están vivas en el server (caducaron o se retiraron).
  for (const id of [...porId.keys()]) {
    if (!vivos.has(id)) { porId.delete(id); contenidos.delete(id); await borrar('cuenta', `historia:${id}`).catch(() => {}); }
  }
  recomputar();
}

/** Trae a memoria el contenido persistido de una historia, si existe. */
async function asegurarContenido(id: string): Promise<void> {
  if (contenidos.has(id)) return;
  const cont = await leer<ContenidoGuardado>('cuenta', `historia:${id}`).catch(() => undefined);
  if (cont) contenidos.set(id, cont);
}

// --- Publicar --------------------------------------------------------------
export async function publicarTexto(texto: string, fondo: string): Promise<void> {
  const s = sesionActual();
  if (!s) throw new Error('No hay sesión.');
  const t = texto.trim();
  if (!t) throw new Error('Escribí algo para tu estado.');
  if (t.length > TOPE_TEXTO_HISTORIA) throw new Error(`El estado de texto es de hasta ${TOPE_TEXTO_HISTORIA} caracteres.`);
  const historiaId = nuevoId();
  const ds = await destinosHistoria();
  const carga = construirHistoria({ historiaId, clase: ClaseHistoria.TEXTO, texto: t, fondo });
  const copias = await cifrarHistoriaPara(ds, carga);
  await pedir('POST', RUTA_HISTORIAS, { historiaId, clase: ClaseHistoria.TEXTO });
  if (copias.length) await pedir('POST', `${RUTA_HISTORIAS}/${historiaId}/sobres`, { copias, ultimoLote: true });
  await guardarContenidoHistoria(s.username, carga, Date.now(), true);
  void sincronizarHistorias();
}

export async function publicarFoto(file: File, pie: string): Promise<void> {
  const s = sesionActual();
  if (!s) throw new Error('No hay sesión.');
  const prep = await prepararImagen(file);
  if (prep.datos.byteLength + SOBRECOSTO > LIMITES['imagen']) {
    throw new Error('La imagen es muy grande para un estado.');
  }
  const historiaId = nuevoId();
  // El metadato PRIMERO: el servidor solo deja colgarle un adjunto a una
  // historia que ya existe y es mía.
  await pedir('POST', RUTA_HISTORIAS, { historiaId, clase: ClaseHistoria.IMAGEN });

  const cif = await cifrarArchivo(prep.datos);
  const r = await pedir<{ adjuntoId: string; urlSubida: string }>('POST', '/v1/adjuntos', {
    conversacionId: '', historiaId, clase: 'imagen', bytes: cif.cifrado.byteLength,
    mime: prep.mime, nombre: 'estado.jpg', ancho: prep.ancho, alto: prep.alto, duracionMs: 0,
  });
  const subida = await fetch(r.urlSubida, { method: 'PUT', body: cif.cifrado, headers: { 'Content-Type': 'application/octet-stream' } });
  if (!subida.ok) throw new Error(`El almacén rechazó la imagen (${subida.status}).`);
  await pedir('POST', `/v1/adjuntos/${r.adjuntoId}/confirmar`);

  const adj = adjunto({
    adjuntoId: r.adjuntoId, clase: 'imagen', clave: cif.clave, nonce: cif.nonce, mime: prep.mime,
    nombre: 'estado.jpg', bytes: prep.datos.byteLength, ancho: prep.ancho, alto: prep.alto,
    miniatura: miniaturaSegura(prep.miniatura), pie,
  });
  const ds = await destinosHistoria();
  const carga = construirHistoria({ historiaId, clase: ClaseHistoria.IMAGEN, texto: pie, adjunto: adj });
  const copias = await cifrarHistoriaPara(ds, carga);
  if (copias.length) await pedir('POST', `${RUTA_HISTORIAS}/${historiaId}/sobres`, { copias, ultimoLote: true });
  await guardarContenidoHistoria(s.username, carga, Date.now(), true);
  void sincronizarHistorias();
}

// --- Ver, vistas, retirar --------------------------------------------------
export async function marcarVistaHistoria(id: string): Promise<void> {
  const h = porId.get(id);
  if (!h || h.esMia || h.vista) return;
  await pedir('POST', `${RUTA_HISTORIAS}/${id}/vista`).catch(() => {});
  fusionar(id, { historiaId: id, vista: true });
  recomputar();
}

export function vistasDeHistoria(id: string): Promise<VistasDeHistoria> {
  return pedir<VistasDeHistoria>('GET', `${RUTA_HISTORIAS}/${id}/vistas`);
}

export async function retirarHistoria(id: string): Promise<void> {
  await pedir('DELETE', `${RUTA_HISTORIAS}/${id}`);
  porId.delete(id); contenidos.delete(id);
  await borrar('cuenta', `historia:${id}`).catch(() => {});
  recomputar();
}

// --- Media (foto/video) de una historia ------------------------------------
const mediaCache = new Map<string, string>();
export async function mediaDeHistoria(h: HistoriaLocal): Promise<string | null> {
  const a = h.adjunto;
  if (!a?.adjuntoId || !a.clave || !a.nonce) return null;
  const cached = mediaCache.get(h.historiaId);
  if (cached) return cached;
  const info = await pedir<{ urlDescarga: string }>('GET', `/v1/adjuntos/${a.adjuntoId}`);
  const resp = await fetch(info.urlDescarga);
  if (!resp.ok) throw new Error(`No se pudo bajar (${resp.status}).`);
  const claro = await descifrarArchivo(await resp.arrayBuffer(), a.clave, a.nonce);
  const url = URL.createObjectURL(new Blob([claro], { type: a.mime || 'application/octet-stream' }));
  mediaCache.set(h.historiaId, url);
  return url;
}

// --- Agrupar por autor (para la lista estilo "anillos") --------------------
export interface GrupoHistorias {
  autorUsername: string;
  esMia: boolean;
  historias: HistoriaLocal[];
  algunaSinVer: boolean;
  ultima: number;
}

/** Autores con al menos una historia CON contenido (lo que no se puede abrir, no se anuncia). */
export function agruparHistorias(todas: HistoriaLocal[]): { mias: GrupoHistorias | null; ajenas: GrupoHistorias[] } {
  const porAutor = new Map<string, HistoriaLocal[]>();
  const mias: HistoriaLocal[] = [];
  for (const h of todas) {
    if (!h.tieneContenido && !h.esMia) continue;
    if (h.esMia) mias.push(h);
    else (porAutor.get(h.autorUsername) ?? porAutor.set(h.autorUsername, []).get(h.autorUsername)!).push(h);
  }
  const grupo = (autor: string, hs: HistoriaLocal[], esMia: boolean): GrupoHistorias => {
    const orden = [...hs].sort((a, b) => a.creadaEn - b.creadaEn);
    return {
      autorUsername: autor, esMia, historias: orden,
      algunaSinVer: orden.some((h) => !h.vista && !h.esMia),
      ultima: Math.max(...orden.map((h) => h.creadaEn)),
    };
  };
  const ajenas = [...porAutor.entries()]
    .map(([autor, hs]) => grupo(autor, hs, false))
    .sort((a, b) => Number(b.algunaSinVer) - Number(a.algunaSinVer) || b.ultima - a.ultima);
  return { mias: mias.length ? grupo('', mias, true) : null, ajenas };
}
