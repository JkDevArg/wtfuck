// La bitacora de un bot de herramientas: quien pidio que, contra que, cuando y
// como salio. Append-only, una linea JSON por evento (JSONL). Es parte de los
// guardarrailes: sin registro, un escaneo fuera de lugar no deja rastro.

import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export interface Evento {
  operador: string;
  herramienta: string;
  objetivo: string;
  perfil?: string;
  resultado: 'ejecutado' | 'rechazado-scope' | 'rechazado-operador' | 'rechazado-ocupado' | 'error';
  detalle?: string;
}

export class Bitacora {
  constructor(private readonly archivo: string) {
    mkdirSync(dirname(archivo), { recursive: true });
  }

  registrar(e: Evento): void {
    const linea = JSON.stringify({ ts: new Date().toISOString(), ...e });
    try {
      appendFileSync(this.archivo, linea + '\n', 'utf8');
    } catch {
      // Una bitacora que no se puede escribir no debe tumbar el bot, pero si
      // avisarse por consola.
      console.error('No se pudo escribir la bitacora:', linea);
    }
  }
}
