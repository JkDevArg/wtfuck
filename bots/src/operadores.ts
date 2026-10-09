// Quién puede usar el bot y si aceptó los términos.
//
// El ALCANCE (qué objetivos se pueden auditar) ya NO vive acá: pasó al servidor,
// para que un admin lo apruebe desde el panel (ver alcance.ts y docs/14-BOTS.md).
// Este módulo guarda solo lo local del bot: la invitación (quién entra) y la
// aceptación de los términos.

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

interface EstadoOperador {
  aceptoEn: string;
}

export class Operadores {
  private estado: Record<string, EstadoOperador> = {};

  /**
   * @param abierto   true = cualquiera que acepte; false = solo `invitados`.
   * @param invitados @usuarios autorizados cuando no es abierto.
   * @param admins    @usuarios con rol admin del bot (informativo; aprobar es por panel).
   */
  constructor(
    private readonly archivo: string,
    private readonly abierto: boolean,
    private readonly invitados: string[],
    private readonly admins: string[] = [],
  ) {
    if (existsSync(archivo)) {
      const crudo = JSON.parse(readFileSync(archivo, 'utf8')) as Record<string, { aceptoEn?: string }>;
      for (const [u, v] of Object.entries(crudo)) this.estado[u] = { aceptoEn: v.aceptoEn ?? '' };
    } else {
      mkdirSync(dirname(archivo), { recursive: true });
    }
  }

  esAdmin(usuario: string): boolean {
    return this.admins.includes(usuario.toLowerCase());
  }

  /** ¿Puede siquiera usar el bot? (antes de aceptar). */
  puedeEntrar(usuario: string): boolean {
    return this.abierto || this.invitados.includes(usuario.toLowerCase()) || this.esAdmin(usuario);
  }

  aceptado(usuario: string): boolean {
    return !!this.estado[usuario.toLowerCase()]?.aceptoEn;
  }

  aceptar(usuario: string): void {
    const u = usuario.toLowerCase();
    this.estado[u] = { aceptoEn: new Date().toISOString() };
    this.guardar();
  }

  private guardar(): void {
    const tmp = `${this.archivo}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.estado, null, 1), 'utf8');
    renameSync(tmp, this.archivo);
  }
}
