// Términos + alcance declarado por cada operador, CON aprobación del admin.
//
// El modelo (decidido con el usuario): cada operador acepta los términos y PIDE
// los objetivos que atesta estar autorizado a auditar. Pero pedir no alcanza:
// cada objetivo queda PENDIENTE hasta que un ADMIN lo aprueba. Recién un objetivo
// aprobado se puede escanear. Es un control de dos personas: el operador declara,
// el admin confirma. Ambas cosas quedan registradas (quién pidió qué y cuándo;
// quién aprobó y cuándo).
//
// Guardarrailes que se mantienen igual:
//   - Exclusiones del ADMIN que nadie puede declarar (metadatos de nube,
//     loopback): ni siquiera se pueden pedir.
//   - Invitacion: por defecto solo cuentas en la lista pueden entrar.
//   - La cola (uno a la vez) y la bitacora siguen.

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { clasificar, coincide } from './scope.ts';

export type EstadoObjetivo = 'pendiente' | 'aprobado';

export interface ObjetivoDeclarado {
  objetivo: string;
  estado: EstadoObjetivo;
  pedidoEn: string;
  aprobadoEn?: string;
  aprobadoPor?: string;
}

interface EstadoOperador {
  aceptoEn: string;
  alcance: ObjetivoDeclarado[];
}

export interface Pendiente {
  operador: string;
  objetivo: string;
  pedidoEn: string;
}

export class Operadores {
  private estado: Record<string, EstadoOperador> = {};

  /**
   * @param excluidos destinos que NADIE puede declarar (ganan siempre).
   * @param abierto   true = cualquiera que acepte; false = solo `invitados`.
   * @param invitados @usuarios autorizados cuando no es abierto.
   * @param admins    @usuarios que pueden aprobar pedidos de alcance.
   */
  constructor(
    private readonly archivo: string,
    private readonly excluidos: string[],
    private readonly abierto: boolean,
    private readonly invitados: string[],
    private readonly admins: string[] = [],
  ) {
    if (existsSync(archivo)) {
      const crudo = JSON.parse(readFileSync(archivo, 'utf8')) as Record<string, unknown>;
      this.estado = this.migrar(crudo);
    } else {
      mkdirSync(dirname(archivo), { recursive: true });
    }
  }

  /** Lee el formato viejo (alcance: string[]) y lo trae al nuevo. */
  private migrar(crudo: Record<string, unknown>): Record<string, EstadoOperador> {
    const out: Record<string, EstadoOperador> = {};
    for (const [u, v] of Object.entries(crudo)) {
      const e = v as { aceptoEn?: string; alcance?: unknown };
      const alcance: ObjetivoDeclarado[] = Array.isArray(e.alcance)
        ? e.alcance.map((x) =>
            typeof x === 'string'
              ? { objetivo: x, estado: 'aprobado' as const, pedidoEn: e.aceptoEn ?? '', aprobadoEn: e.aceptoEn ?? '', aprobadoPor: '(migrado)' }
              : (x as ObjetivoDeclarado),
          )
        : [];
      out[u] = { aceptoEn: e.aceptoEn ?? '', alcance };
    }
    return out;
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
    const e = this.estado[u] ?? { aceptoEn: '', alcance: [] };
    e.aceptoEn = new Date().toISOString();
    this.estado[u] = e;
    this.guardar();
  }

  /** Todo el alcance del operador (pendientes + aprobados), para mostrar. */
  alcance(usuario: string): ObjetivoDeclarado[] {
    return this.estado[usuario.toLowerCase()]?.alcance ?? [];
  }

  /** Solo los objetivos aprobados (lo que de verdad se puede escanear). */
  aprobados(usuario: string): string[] {
    return this.alcance(usuario).filter((o) => o.estado === 'aprobado').map((o) => o.objetivo);
  }

  /**
   * El operador PIDE un objetivo. Valida forma y exclusiones; queda PENDIENTE.
   * Devuelve `yaEstaba` si ya lo tenía (con su estado actual).
   */
  pedirAlcance(usuario: string, objetivoCrudo: string): { ok: true; objetivo: string; estado: EstadoObjetivo } | { ok: false; motivo: string } {
    const clasificado = clasificar(objetivoCrudo);
    if (!clasificado) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino esta excluido por el administrador y no se puede declarar' };
    const u = usuario.toLowerCase();
    const e = this.estado[u] ?? { aceptoEn: '', alcance: [] };
    const ya = e.alcance.find((o) => o.objetivo === objetivo);
    if (ya) {
      this.estado[u] = e;
      return { ok: true, objetivo, estado: ya.estado };
    }
    e.alcance.push({ objetivo, estado: 'pendiente', pedidoEn: new Date().toISOString() });
    this.estado[u] = e;
    this.guardar();
    return { ok: true, objetivo, estado: 'pendiente' };
  }

  quitarAlcance(usuario: string, objetivoCrudo: string): boolean {
    const u = usuario.toLowerCase();
    const e = this.estado[u];
    if (!e) return false;
    const objetivo = objetivoCrudo.trim().toLowerCase();
    const antes = e.alcance.length;
    e.alcance = e.alcance.filter((o) => o.objetivo !== objetivo);
    this.guardar();
    return e.alcance.length < antes;
  }

  // ---- Admin: aprobar / rechazar ----

  /** Todos los pedidos pendientes, de todos los operadores (para el admin). */
  pendientes(): Pendiente[] {
    const out: Pendiente[] = [];
    for (const [operador, e] of Object.entries(this.estado)) {
      for (const o of e.alcance) if (o.estado === 'pendiente') out.push({ operador, objetivo: o.objetivo, pedidoEn: o.pedidoEn });
    }
    return out.sort((a, b) => a.pedidoEn.localeCompare(b.pedidoEn));
  }

  /** El admin aprueba un pedido. No se puede auto-aprobar. */
  aprobar(admin: string, operador: string, objetivoCrudo: string): { ok: true; objetivo: string } | { ok: false; motivo: string } {
    if (!this.esAdmin(admin)) return { ok: false, motivo: 'no sos admin' };
    const op = operador.toLowerCase().replace(/^@/, '');
    if (op === admin.toLowerCase()) return { ok: false, motivo: 'no podés aprobar tus propios pedidos' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    const o = this.estado[op]?.alcance.find((x) => x.objetivo === objetivo);
    if (!o) return { ok: false, motivo: `@${op} no pidió "${objetivo}"` };
    if (o.estado === 'aprobado') return { ok: false, motivo: 'ya estaba aprobado' };
    // Chequeo defensivo: nunca aprobar algo que entretanto quedó excluido.
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino está excluido por el administrador' };
    o.estado = 'aprobado';
    o.aprobadoEn = new Date().toISOString();
    o.aprobadoPor = admin.toLowerCase();
    this.guardar();
    return { ok: true, objetivo };
  }

  /** El admin rechaza (saca) un pedido pendiente. */
  rechazar(admin: string, operador: string, objetivoCrudo: string): { ok: true } | { ok: false; motivo: string } {
    if (!this.esAdmin(admin)) return { ok: false, motivo: 'no sos admin' };
    const op = operador.toLowerCase().replace(/^@/, '');
    const quito = this.quitarAlcance(op, objetivoCrudo);
    return quito ? { ok: true } : { ok: false, motivo: `@${op} no tenía "${objetivoCrudo.trim().toLowerCase()}"` };
  }

  /** ¿Puede escanear este objetivo? (aceptó + está APROBADO en su alcance + no excluido). */
  permitido(usuario: string, objetivoCrudo: string): { ok: true; objetivo: string } | { ok: false; motivo: string } {
    if (!this.aceptado(usuario)) return { ok: false, motivo: 'primero tenés que aceptar los términos con /acepto' };
    if (!clasificar(objetivoCrudo)) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    const objetivo = objetivoCrudo.trim().toLowerCase();
    if (coincide(objetivo, this.excluidos)) return { ok: false, motivo: 'ese destino esta excluido por el administrador' };
    const aprobados = this.aprobados(usuario);
    if (coincide(objetivo, aprobados)) return { ok: true, objetivo };
    // Distinguimos "pendiente de aprobación" de "ni lo pediste".
    const pendiente = this.alcance(usuario).some((o) => o.estado === 'pendiente' && coincide(objetivo, [o.objetivo]));
    if (pendiente) return { ok: false, motivo: 'está pendiente de aprobación del administrador. Esperá el visto bueno' };
    return { ok: false, motivo: 'no está en tu alcance aprobado. Pedilo con /alcance ' + objetivo + ' (lo aprueba el administrador)' };
  }

  private guardar(): void {
    const tmp = `${this.archivo}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.estado, null, 1), 'utf8');
    renameSync(tmp, this.archivo);
  }
}
