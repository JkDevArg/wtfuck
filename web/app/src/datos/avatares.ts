// Fotos de perfil (personas) y de conversacion (grupos y canales) en la web.
//
// Por que no basta un <img src="/v1/usuario/pepe/avatar">: ese endpoint exige
// el token (Authorization: Bearer ...), y una etiqueta <img> no manda cabeceras,
// asi que daria 401. Se baja con `fetch` + token, se arma un blob y se muestra
// como object URL -el mismo patron que las imagenes de mensajes y canales-.
//
// La version va en la URL (?v=...) y en la clave de cache: al cambiar la foto
// cambia la version, la clave es otra y se vuelve a bajar sola. Sin foto
// (version 0 o ausente) se resuelve a null y la interfaz dibuja las iniciales.

import { tokenActual } from './api';
import type { ConversacionResumen, UsuarioPublico } from './protocolo';

const cache = new Map<string, Promise<string | null>>();

async function bajar(ruta: string): Promise<string | null> {
  const t = tokenActual();
  const r = await fetch(ruta, t ? { headers: { Authorization: `Bearer ${t}` } } : undefined);
  // 404 = esta cuenta/grupo no tiene foto; no es un error, es "sin foto".
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`No se pudo bajar la foto (${r.status}).`);
  return URL.createObjectURL(await r.blob());
}

function cacheado(clave: string, ruta: string): Promise<string | null> {
  let p = cache.get(clave);
  if (!p) {
    p = bajar(ruta);
    cache.set(clave, p);
    // Un fallo no se queda pegado en el cache: el proximo intento reintenta.
    p.catch(() => cache.delete(clave));
  }
  return p;
}

/** Foto de una persona, o null si no tiene. */
export function avatarUsuario(username: string, version?: number): Promise<string | null> {
  if (!version || version <= 0) return Promise.resolve(null);
  return cacheado(`u:${username}:${version}`, `/v1/usuario/${encodeURIComponent(username)}/avatar?v=${version}`);
}

/** Foto de un grupo o canal, o null si no tiene. */
export function avatarConversacion(id: string, version?: number): Promise<string | null> {
  if (!version || version <= 0) return Promise.resolve(null);
  return cacheado(`c:${id}:${version}`, `/v1/conversaciones/${id}/avatar?v=${version}`);
}

/** Clave estable + cargador para el avatar de una persona. Para el componente <Avatar>. */
export function fotoDeUsuario(u: UsuarioPublico): { clave: string; cargar: () => Promise<string | null> } {
  return { clave: `u:${u.username}:${u.avatarVersion ?? 0}`, cargar: () => avatarUsuario(u.username, u.avatarVersion) };
}

/**
 * Clave + cargador para el avatar de una conversacion: en una directa es la foto
 * del otro; en un grupo o canal, la de la conversacion; en notas, nada.
 */
export function fotoDeConversacion(c: ConversacionResumen): { clave: string; cargar: () => Promise<string | null> } {
  if (c.tipo === 'directa') {
    const otro = c.participantes[0];
    if (!otro) return { clave: 'vacio', cargar: () => Promise.resolve(null) };
    return fotoDeUsuario(otro);
  }
  if (c.tipo === 'grupo' || c.tipo === 'canal') {
    return { clave: `c:${c.id}:${c.avatarVersion ?? 0}`, cargar: () => avatarConversacion(c.id, c.avatarVersion) };
  }
  return { clave: 'vacio', cargar: () => Promise.resolve(null) };
}
