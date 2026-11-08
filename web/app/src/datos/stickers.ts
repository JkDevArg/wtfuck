// Stickers en la web: imagenes propias que se guardan en ESTE navegador y se
// reusan para enviar (van como un adjunto de imagen, igual que en la app).
//
// Son LOCALES de cada dispositivo: no se sincronizan con el telefono ni entre
// navegadores (el telefono tiene su propia coleccion). Se guardan como base64 en
// la boveda cifrada (que serializa a JSON, asi que no admite Blobs directos).

import { guardar, leerDesde, borrar } from './boveda';
import { enviarArchivo } from './motor';
import { b64, nuevoId } from './protocolo';

/** Tope por sticker, como en la app (2 MB). */
export const TOPE_STICKER = 2 * 1024 * 1024;

export interface StickerGuardado {
  id: string;
  nombre: string;
  mime: string;
  /** La imagen en base64 (la boveda no guarda Blobs). */
  datos: string;
  creado: number;
}

export async function listarStickers(): Promise<StickerGuardado[]> {
  const todos = await leerDesde<StickerGuardado>('cuenta', 'sticker:');
  return todos.sort((a, b) => b.creado - a.creado);
}

export async function guardarSticker(file: File): Promise<void> {
  if (!file.type.startsWith('image/')) throw new Error('Tiene que ser una imagen.');
  if (file.size > TOPE_STICKER) throw new Error('La imagen supera los 2 MB.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  const id = nuevoId();
  await guardar('cuenta', `sticker:${id}`, {
    id, nombre: file.name || 'sticker', mime: file.type, datos: b64.a(bytes), creado: Date.now(),
  } satisfies StickerGuardado);
}

export async function borrarSticker(id: string): Promise<void> {
  await borrar('cuenta', `sticker:${id}`);
}

export async function enviarSticker(conversacionId: string, s: StickerGuardado): Promise<void> {
  const file = new File([b64.de(s.datos)], s.nombre, { type: s.mime });
  await enviarArchivo(conversacionId, file, '');
}

/** Object URL para mostrar el sticker en el panel. */
export function urlSticker(s: StickerGuardado): string {
  return URL.createObjectURL(new Blob([b64.de(s.datos)], { type: s.mime }));
}
