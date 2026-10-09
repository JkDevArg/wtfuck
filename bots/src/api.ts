// HTTP contra la API de wtfuck, con el token del bot. Node 22 trae `fetch`
// global, asi que no hace falta nada mas.

export class ErrorApi extends Error {
  constructor(
    public estado: number,
    motivo: string,
  ) {
    super(motivo);
  }
}

export class Api {
  token: string | null = null;

  constructor(private readonly base: string) {}

  async pedir<T>(metodo: string, ruta: string, cuerpo?: unknown, conSesion = true): Promise<T> {
    const cabeceras: Record<string, string> = { 'Content-Type': 'application/json' };
    if (conSesion && this.token) cabeceras['Authorization'] = `Bearer ${this.token}`;
    let r: Response;
    try {
      r = await fetch(this.base + ruta, {
        method: metodo,
        headers: cabeceras,
        body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
      });
    } catch {
      throw new ErrorApi(0, 'Sin conexion con el servidor.');
    }
    const texto = await r.text();
    let json: unknown = null;
    try {
      json = texto ? JSON.parse(texto) : null;
    } catch {
      json = null;
    }
    if (!r.ok) {
      const motivo = (json as { motivo?: string } | null)?.motivo ?? `Error ${r.status}`;
      throw new ErrorApi(r.status, motivo);
    }
    return json as T;
  }
}
