// HTTP contra la API, en el MISMO origen (`/v1/...`): en producción la sirve
// el mismo servidor que la página, y en desarrollo Vite la pasa al servidor
// local. Ver vite.config.ts y server/Web.kt.

/** Un rechazo del servidor, con el motivo que manda en `{"motivo": ...}`. */
export class ErrorApi extends Error {
  constructor(public estado: number, motivo: string) {
    super(motivo);
  }
}

let token: string | null = null;

export function usarToken(t: string | null): void {
  token = t;
}

export const tokenActual = () => token;

/** Lo que se avisa cuando el servidor deja de reconocer la sesión. */
let alPerderSesion: () => void = () => {};
export function cuandoSePierdaLaSesion(f: () => void): void {
  alPerderSesion = f;
}

export async function pedir<T>(metodo: string, ruta: string, cuerpo?: unknown, conSesion = true): Promise<T> {
  const cabeceras: Record<string, string> = { 'Content-Type': 'application/json' };
  if (conSesion && token) cabeceras.Authorization = `Bearer ${token}`;
  let r: Response;
  try {
    r = await fetch(ruta, { method: metodo, headers: cabeceras, body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo) });
  } catch {
    throw new ErrorApi(0, 'Sin conexión con el servidor.');
  }
  const texto = await r.text();
  let json: unknown = null;
  try {
    json = texto ? JSON.parse(texto) : null;
  } catch {
    json = null;
  }
  if (!r.ok) {
    if (r.status === 401 && conSesion) alPerderSesion();
    const motivo = (json as { motivo?: string } | null)?.motivo ?? `Error ${r.status}`;
    throw new ErrorApi(r.status, motivo);
  }
  return json as T;
}
