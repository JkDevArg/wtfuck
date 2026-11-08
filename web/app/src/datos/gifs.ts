// Buscador de GIFs en la web. Es un servicio del servidor (modulo Gifs.kt): se
// busca por texto, se muestran las previas y, al elegir uno, se baja completo y
// se manda como un adjunto de imagen normal (cifrado como todo lo demas).
//
// Las previas y el GIF completo se bajan con `fetch` + token (un <img> no manda
// el Authorization) y se muestran como object URL, igual que los avatares.

import { pedir, tokenActual } from './api';
import { enviarArchivo } from './motor';
import type { BusquedaGifs } from './protocolo';

export function buscarGifs(q: string): Promise<BusquedaGifs> {
  return pedir<BusquedaGifs>('GET', `/v1/gifs/buscar?q=${encodeURIComponent(q)}`);
}

async function bajarGif(id: string, previa: boolean): Promise<Blob> {
  const t = tokenActual();
  const r = await fetch(
    `/v1/gifs/${encodeURIComponent(id)}/bytes?previa=${previa}`,
    t ? { headers: { Authorization: `Bearer ${t}` } } : undefined,
  );
  if (!r.ok) throw new Error(`No se pudo traer el GIF (${r.status}).`);
  return r.blob();
}

const cachePrevia = new Map<string, Promise<string>>();

/** URL (object URL) de la previa de un GIF, para el grid. Cacheada por id. */
export function previaGif(id: string): Promise<string> {
  let p = cachePrevia.get(id);
  if (!p) {
    p = bajarGif(id, true).then((b) => URL.createObjectURL(b));
    cachePrevia.set(id, p);
    p.catch(() => cachePrevia.delete(id));
  }
  return p;
}

/** Baja el GIF completo y lo manda como adjunto de imagen a la conversacion. */
export async function enviarGif(conversacionId: string, id: string): Promise<void> {
  const blob = await bajarGif(id, false);
  const file = new File([blob], `gif-${id}.gif`, { type: 'image/gif' });
  await enviarArchivo(conversacionId, file, '');
}
