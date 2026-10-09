// El alcance: contra QUE puede correr una herramienta. Es la pieza que separa
// una herramienta de trabajo de una botonera de escaneo masivo contra terceros.
//
// Regla: se niega por defecto. Un objetivo solo pasa si esta en la lista de
// permitidos Y no en la de excluidos. Sin resolver DNS: un nombre se compara
// como nombre (y sus subdominios), una IP como IP o dentro de un CIDR. Resolver
// un nombre a IP para cruzarlo con un CIDR abriria agujeros (DNS lo controla
// quien pide), asi que no se hace.
//
// Se carga de un archivo JSON (WTFUCK_BOT_SCOPE):
//   { "permitidos": ["example.com", "10.0.0.0/24", "192.0.2.5"],
//     "excluidos":  ["admin.example.com", "10.0.0.1"] }

import { readFileSync } from 'node:fs';

export interface ReglasScope {
  permitidos: string[];
  excluidos: string[];
}

type Entrada =
  | { clase: 'host'; valor: string }
  | { clase: 'ip'; valor: number }
  | { clase: 'cidr'; red: number; bits: number };

const RE_HOST = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

function ipANumero(ip: string): number | null {
  const partes = ip.split('.');
  if (partes.length !== 4) return null;
  let n = 0;
  for (const p of partes) {
    if (!/^\d{1,3}$/.test(p)) return null;
    const o = Number(p);
    if (o > 255) return null;
    n = n * 256 + o;
  }
  return n >>> 0;
}

/** Clasifica un texto en host, ip o cidr. null si no es ninguno valido. */
export function clasificar(crudo: string): Entrada | null {
  const t = crudo.trim().toLowerCase();
  if (!t || t.length > 255 || /\s/.test(t)) return null;

  if (t.includes('/')) {
    const [dir, bitsTxt] = t.split('/');
    if (!dir || !bitsTxt || !/^\d{1,2}$/.test(bitsTxt)) return null;
    const bits = Number(bitsTxt);
    const red = ipANumero(dir);
    if (red === null || bits > 32) return null;
    // La red tiene que estar alineada a los bits (sin bits de host encendidos).
    const mascara = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
    if ((red & mascara) >>> 0 !== red) return null;
    return { clase: 'cidr', red, bits };
  }

  const ip = ipANumero(t);
  if (ip !== null) return { clase: 'ip', valor: ip };

  // Un host real termina en una etiqueta con alguna letra (el TLD). Asi una IP
  // mal escrita (`999.1.1.1`) cae como invalida, no como "host" que no matchea.
  const ultima = t.split('.').at(-1) ?? '';
  if (RE_HOST.test(t) && /[a-z]/.test(ultima)) return { clase: 'host', valor: t };
  return null;
}

function dentroDeCidr(ip: number, red: number, bits: number): boolean {
  const mascara = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
  return ((ip & mascara) >>> 0) === red;
}

/** ¿`objetivo` cae dentro de `entrada`? (objetivo es lo que pide el operador). */
function cubre(entrada: Entrada, objetivo: Entrada): boolean {
  if (entrada.clase === 'host') {
    // Un host permitido cubre ese host y sus subdominios.
    return objetivo.clase === 'host' && (objetivo.valor === entrada.valor || objetivo.valor.endsWith('.' + entrada.valor));
  }
  if (entrada.clase === 'ip') {
    return objetivo.clase === 'ip' && objetivo.valor === entrada.valor;
  }
  // entrada CIDR: cubre una IP dentro, o un CIDR igual o mas chico dentro.
  if (objetivo.clase === 'ip') return dentroDeCidr(objetivo.valor, entrada.red, entrada.bits);
  if (objetivo.clase === 'cidr') return objetivo.bits >= entrada.bits && dentroDeCidr(objetivo.red, entrada.red, entrada.bits);
  return false;
}

export class Scope {
  private readonly permitidos: Entrada[];
  private readonly excluidos: Entrada[];

  constructor(reglas: ReglasScope) {
    this.permitidos = reglas.permitidos.map(clasificar).filter((e): e is Entrada => e !== null);
    this.excluidos = reglas.excluidos.map(clasificar).filter((e): e is Entrada => e !== null);
  }

  static desdeArchivo(ruta: string): Scope {
    const r = JSON.parse(readFileSync(ruta, 'utf8')) as Partial<ReglasScope>;
    return new Scope({ permitidos: r.permitidos ?? [], excluidos: r.excluidos ?? [] });
  }

  /** `{ok:true}` si el objetivo esta en alcance; si no, el motivo. */
  evaluar(objetivoCrudo: string): { ok: true; objetivo: string } | { ok: false; motivo: string } {
    const objetivo = clasificar(objetivoCrudo);
    if (!objetivo) return { ok: false, motivo: 'no parece un host, IP o CIDR valido' };
    if (this.excluidos.some((e) => cubre(e, objetivo))) return { ok: false, motivo: 'esta en la lista de exclusion' };
    if (!this.permitidos.some((e) => cubre(e, objetivo))) return { ok: false, motivo: 'fuera del alcance autorizado' };
    return { ok: true, objetivo: objetivoCrudo.trim().toLowerCase() };
  }

  resumen(): string {
    const p = this.permitidos.length;
    const x = this.excluidos.length;
    return `${p} destino(s) permitido(s)${x ? `, ${x} excluido(s)` : ''}`;
  }

  get vacio(): boolean {
    return this.permitidos.length === 0;
  }
}
