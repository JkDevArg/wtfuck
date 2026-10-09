// El alcance, ahora contra el SERVIDOR (ver docs/14-BOTS.md).
//
// Antes el scope vivía en un archivo local del bot. Ahora el bot REGISTRA los
// pedidos en el server y CONSULTA ahí qué está aprobado, para que el admin
// apruebe desde el panel. La aprobación es el gate de dos personas: el operador
// pide (por chat), el admin aprueba (en el panel).
//
// Las EXCLUSIONES del admin (metadatos de nube, loopback) se siguen aplicando
// localmente, antes de pedir y antes de escanear: son no-go absolutos que no
// dependen de ninguna aprobación.

import { clasificar, coincide } from './scope.ts';

/** Lo que el bot necesita del cliente (el cliente headless lo implementa). */
export interface ClienteAlcance {
  pedirAlcance(operador: string, objetivo: string): Promise<string>;
  alcanceAprobados(operador: string): Promise<string[]>;
}

export class Alcance {
  private readonly cache = new Map<string, { hasta: number; lista: string[] }>();

  /**
   * @param excluidos destinos que NADIE puede pedir ni escanear (ganan siempre).
   * @param ttlMs     cuánto se cachea la lista de aprobados por operador.
   */
  constructor(
    private readonly cli: ClienteAlcance,
    private readonly excluidos: string[],
    private readonly ttlMs = 10_000,
  ) {}

  /** El operador pide un objetivo. Valida forma y exclusiones; lo registra como pendiente. */
  async pedir(operador: string, objetivoCrudo: string): Promise<{ ok: true; objetivo: string; estado: string } | { ok: false; motivo: string }> {
    if (!clasificar(objetivoCrudo)) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino esta excluido por el administrador y no se puede pedir' };
    let estado: string;
    try {
      estado = await this.cli.pedirAlcance(operador, objetivo);
    } catch (e) {
      return { ok: false, motivo: `no pude registrar el pedido: ${(e as Error).message}` };
    }
    this.cache.delete(operador.toLowerCase()); // por si ya estaba aprobado
    return { ok: true, objetivo, estado };
  }

  /** ¿Puede escanear este objetivo? (aprobado en el server + no excluido). */
  async permitido(operador: string, objetivoCrudo: string): Promise<{ ok: true; objetivo: string } | { ok: false; motivo: string }> {
    if (!clasificar(objetivoCrudo)) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino esta excluido por el administrador' };
    let aprobados: string[];
    try {
      aprobados = await this.aprobados(operador);
    } catch (e) {
      return { ok: false, motivo: `no pude verificar el alcance con el servidor: ${(e as Error).message}` };
    }
    if (coincide(objetivo, aprobados)) return { ok: true, objetivo };
    return { ok: false, motivo: 'no está en tu alcance aprobado. Pedilo con /alcance ' + objetivo + ' y que un administrador lo apruebe desde el panel' };
  }

  /** Lista de aprobados de un operador, con cache corto. */
  async aprobados(operador: string): Promise<string[]> {
    const k = operador.toLowerCase();
    const c = this.cache.get(k);
    const ahora = Date.now();
    if (c && c.hasta > ahora) return c.lista;
    const lista = await this.cli.alcanceAprobados(operador);
    this.cache.set(k, { hasta: ahora + this.ttlMs, lista });
    return lista;
  }
}
