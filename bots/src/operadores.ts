// Términos + alcance declarado por cada operador.
//
// El modelo (decidido con el usuario): en vez de un alcance global, cada
// operador acepta los términos y DECLARA los objetivos que atesta estar
// autorizado a auditar. Esa aceptacion y esa declaracion quedan registradas:
// son el respaldo real (quien dijo que tenia permiso sobre que, y cuando), no
// un disclaimer generico.
//
// Guardarrailes que se mantienen igual:
//   - Exclusiones del ADMIN que nadie puede declarar (metadatos de nube,
//     loopback): protegen la infra y los no-go obvios.
//   - Invitacion: por defecto solo cuentas en la lista pueden entrar. En modo
//     ABIERTO cualquiera que acepte puede usarlo; ahi la unica proteccion es la
//     atestacion registrada, y hay que saberlo.
//   - La cola (uno a la vez) y la bitacora siguen.

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { clasificar, coincide } from './scope.ts';

interface EstadoOperador {
  aceptoEn: string;
  alcance: string[];
}

export class Operadores {
  private estado: Record<string, EstadoOperador> = {};

  /**
   * @param excluidos destinos que NADIE puede declarar (ganan siempre).
   * @param abierto   true = cualquiera que acepte; false = solo `invitados`.
   * @param invitados @usuarios autorizados cuando no es abierto.
   */
  constructor(
    private readonly archivo: string,
    private readonly excluidos: string[],
    private readonly abierto: boolean,
    private readonly invitados: string[],
  ) {
    if (existsSync(archivo)) {
      this.estado = JSON.parse(readFileSync(archivo, 'utf8')) as Record<string, EstadoOperador>;
    } else {
      mkdirSync(dirname(archivo), { recursive: true });
    }
  }

  /** ¿Puede siquiera usar el bot? (antes de aceptar). */
  puedeEntrar(usuario: string): boolean {
    return this.abierto || this.invitados.includes(usuario.toLowerCase());
  }

  aceptado(usuario: string): boolean {
    return !!this.estado[usuario.toLowerCase()]?.aceptoEn;
  }

  aceptar(usuario: string): void {
    const u = usuario.toLowerCase();
    const e = this.estado[u] ?? { aceptoEn: '', alcance: [] };
    e.aceptoEn = new Date().toISOString();
    this.estado[u] = e;
    this.guardar();
  }

  alcance(usuario: string): string[] {
    return this.estado[usuario.toLowerCase()]?.alcance ?? [];
  }

  /** Declara un objetivo como autorizado. Valida forma y exclusiones del admin. */
  agregarAlcance(usuario: string, objetivoCrudo: string): { ok: true; objetivo: string } | { ok: false; motivo: string } {
    const clasificado = clasificar(objetivoCrudo);
    if (!clasificado) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino esta excluido por el administrador y no se puede declarar' };
    const u = usuario.toLowerCase();
    const e = this.estado[u] ?? { aceptoEn: '', alcance: [] };
    if (!e.alcance.includes(objetivo)) e.alcance.push(objetivo);
    this.estado[u] = e;
    this.guardar();
    return { ok: true, objetivo };
  }

  quitarAlcance(usuario: string, objetivoCrudo: string): boolean {
    const u = usuario.toLowerCase();
    const e = this.estado[u];
    if (!e) return false;
    const objetivo = objetivoCrudo.trim().toLowerCase();
    const antes = e.alcance.length;
    e.alcance = e.alcance.filter((x) => x !== objetivo);
    this.guardar();
    return e.alcance.length < antes;
  }

  /** ¿Puede escanear este objetivo? (aceptó + está en SU alcance + no excluido). */
  permitido(usuario: string, objetivoCrudo: string): { ok: true; objetivo: string } | { ok: false; motivo: string } {
    if (!this.aceptado(usuario)) return { ok: false, motivo: 'primero tenés que aceptar los términos con /acepto' };
    if (!clasificar(objetivoCrudo)) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino esta excluido por el administrador' };
    if (!coincide(objetivo, this.alcance(usuario))) {
      return { ok: false, motivo: 'no está en tu alcance declarado. Agregalo con /alcance ' + objetivo + ' (declarás que estás autorizado a auditarlo)' };
    }
    return { ok: true, objetivo };
  }

  private guardar(): void {
    const tmp = `${this.archivo}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.estado, null, 1), 'utf8');
    renameSync(tmp, this.archivo);
  }
}
