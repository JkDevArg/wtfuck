// Canales: lo mismo que CanalPantalla y el Repositorio de la app.
//
// ## Público y privado
//
// Un canal PÚBLICO guarda su contenido en el servidor, en claro: es una de
// las dos excepciones declaradas al "buzón tonto" (cualquiera puede leerlo,
// así que el cifrado de punta a punta no protegería nada). Por eso aquí no hay
// sobres ni Signal: se lee y se publica por HTTP, y la pantalla lo dice con
// una barra ámbar.
//
// Un canal PRIVADO iría cifrado como un grupo. La app Android todavía no lo
// termina (el servidor rechaza publicar en él y la pantalla no muestra los
// mensajes), así que la web tampoco lo ofrece: muestra el canal y lo dice.
import { pedir } from './api';
import { mencionesEn, nuevoId } from './protocolo';
import type { Reaccion } from './motor';

export interface ConfigCanal {
  conversacionId: string;
  nombre: string;
  alias: string | null;
  publico: boolean;
  descripcion: string;
  comentarios: boolean;
  reacciones: boolean;
  suscriptores: number;
  publicaciones: number;
  suscrito: boolean;
  puedoPublicar: boolean;
  puedoGestionar: boolean;
  miRol: string;
  cifrado: boolean;
  estado: string;
  motivoRechazo: string | null;
}

export interface Publicacion {
  mensajeId: string;
  autor: string;
  cuerpo: string;
  creadoEn: number;
  adjuntoId: string | null;
  adjuntoAncho: number;
  adjuntoAlto: number;
  editado: boolean;
  fijado: boolean;
  reacciones: Reaccion[];
  comentarios: number;
}

export interface Comentario {
  mensajeId: string;
  publicacionId: string;
  autor: string;
  cuerpo: string;
  creadoEn: number;
  editado: boolean;
  reacciones: Reaccion[];
  mio: boolean;
}

export interface CanalEnBusqueda {
  conversacionId: string;
  alias: string;
  nombre: string;
  descripcion: string;
  suscriptores: number;
  suscrito: boolean;
}

const R = '/v1/canales';

export const ficha = (id: string) => pedir<ConfigCanal>('GET', `${R}/${id}`);

/** El muro, del más nuevo al más viejo. `antes` = la última que se tiene. */
export const muro = (id: string, antes?: string) =>
  pedir<Publicacion[]>('GET', `${R}/${id}/publicaciones?limite=30${antes ? `&antes=${encodeURIComponent(antes)}` : ''}`);

export const directorio = () => pedir<{ canales: CanalEnBusqueda[] }>('GET', `${R}/directorio?limite=60`).then((r) => r.canales);

export const buscar = (q: string) =>
  pedir<{ canales: CanalEnBusqueda[] }>('GET', `${R}/buscar?q=${encodeURIComponent(q)}`).then((r) => r.canales);

export const suscribir = (id: string) => pedir<ConfigCanal>('POST', `${R}/${id}/suscribir`);

export const desuscribir = (id: string) => pedir<void>('POST', `${R}/${id}/desuscribir`);

/**
 * Publicar en un canal público: primero el metadato (`/v1/mensajes`, que es
 * donde el servidor autoriza), después el cuerpo en claro. Igual que la app.
 */
export async function publicar(id: string, cuerpo: string): Promise<void> {
  const mensajeId = nuevoId();
  await pedir('POST', '/v1/mensajes', {
    mensajeId, conversacionId: id, respondeA: null, menciones: mencionesEn(cuerpo), reenviadoDe: null, adjuntoId: null, clase: '',
  });
  await pedir('POST', `${R}/${id}/publicaciones`, { mensajeId, cuerpo, adjuntoId: null });
}

export const comentarios = (id: string, publicacionId: string) =>
  pedir<{ comentarios: Comentario[] }>('GET', `${R}/${id}/publicaciones/${publicacionId}/comentarios?limite=100`).then((r) => r.comentarios);

/** Un comentario es un mensaje que responde a la publicación. */
export async function comentar(id: string, publicacionId: string, cuerpo: string): Promise<void> {
  const mensajeId = nuevoId();
  await pedir('POST', '/v1/mensajes', {
    mensajeId, conversacionId: id, respondeA: publicacionId, menciones: mencionesEn(cuerpo), reenviadoDe: null, adjuntoId: null, clase: '',
  });
  await pedir('POST', `${R}/${id}/comentarios`, { mensajeId, publicacionId, cuerpo });
}

export const reaccionarPublicacion = (mensajeId: string, emoji: string, poner: boolean) =>
  pedir<{ reacciones: Reaccion[] }>('POST', '/v1/mensajes/reaccion', { mensajeId, emoji, poner });

const imagenes = new Map<string, Promise<string>>();

/**
 * La imagen de una publicación. En un canal público no va cifrada; se baja con
 * `fetch` (la CSP deja hablar con el almacén) y se muestra como blob: la CSP
 * no deja cargar imágenes de otro dominio directo, y así no hace falta abrirla.
 */
export function imagenDe(adjuntoId: string): Promise<string> {
  let p = imagenes.get(adjuntoId);
  if (!p) {
    p = pedir<{ urlDescarga: string }>('GET', `/v1/adjuntos/${adjuntoId}`)
      .then((i) => fetch(i.urlDescarga))
      .then((r) => {
        if (!r.ok) throw new Error(`No se pudo bajar la imagen (${r.status}).`);
        return r.blob();
      })
      .then((b) => URL.createObjectURL(b));
    imagenes.set(adjuntoId, p);
    p.catch(() => imagenes.delete(adjuntoId));
  }
  return p;
}
