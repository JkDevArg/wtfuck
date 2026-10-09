// Las herramientas que un bot puede correr. Hoy: nmap.
//
// Dos reglas de seguridad, ademas del alcance (scope.ts):
//   1. La IA/el operador eligen un PERFIL (un nombre), no flags sueltos. Nunca
//      se arma la linea con texto libre.
//   2. Se ejecuta con argv (spawn, sin shell): el objetivo va como UN argumento,
//      asi que no hay forma de inyectar comandos aunque el objetivo sea raro.
//
// El ejecutor es inyectable: en produccion corre el binario real; en las
// pruebas (y sin nmap instalado) se le pasa uno de mentira.

import { spawn } from 'node:child_process';

export interface Ejecucion {
  codigo: number | null;
  salida: string;
  recortado: boolean;
  vencio: boolean;
}

export type Ejecutor = (cmd: string, args: string[], timeoutMs: number, maxBytes: number) => Promise<Ejecucion>;

export interface Herramienta {
  nombre: string;
  perfiles: string[];
  correr(objetivo: string, perfil: string): Promise<{ ok: true; salida: string } | { ok: false; error: string }>;
}

/** Perfiles de nmap: conjuntos de flags FIJOS. El operador elige el nombre. */
export const PERFILES_NMAP: Record<string, string[]> = {
  // -Pn: no hace ping primero (muchos hosts lo bloquean y saldria "host down").
  rapido: ['-T4', '-Pn', '-F'], // los 100 puertos mas comunes
  normal: ['-T4', '-Pn', '--top-ports', '1000'],
  servicios: ['-T4', '-Pn', '-sV', '--top-ports', '200'], // detecta version
  completo: ['-T4', '-Pn', '-p-'], // los 65535 (lento)
};

export interface ConfigHerramienta {
  timeoutMs: number;
  maxBytes: number;
}

export function crearNmap(ejecutor: Ejecutor, cfg: ConfigHerramienta): Herramienta {
  return {
    nombre: 'nmap',
    perfiles: Object.keys(PERFILES_NMAP),
    async correr(objetivo, perfil) {
      const flags = PERFILES_NMAP[perfil];
      if (!flags) return { ok: false, error: `Perfil desconocido. Elegí uno: ${Object.keys(PERFILES_NMAP).join(', ')}.` };
      const r = await ejecutor('nmap', [...flags, objetivo], cfg.timeoutMs, cfg.maxBytes);
      if (r.codigo === null && !r.vencio) return { ok: false, error: r.salida };
      let salida = r.salida.trim() || '(sin salida)';
      if (r.vencio) salida += '\n[cortado: superó el tiempo limite]';
      if (r.recortado) salida += '\n[salida recortada]';
      return { ok: true, salida };
    },
  };
}

/** El ejecutor real: spawn sin shell, con tope de tiempo y de salida. */
export const ejecutorReal: Ejecutor = (cmd, args, timeoutMs, maxBytes) =>
  new Promise((resolve) => {
    let hijo;
    try {
      hijo = spawn(cmd, args, { shell: false });
    } catch (e) {
      resolve({ codigo: null, salida: `no se pudo ejecutar ${cmd}: ${(e as Error).message}`, recortado: false, vencio: false });
      return;
    }
    let salida = '';
    let recortado = false;
    let vencio = false;
    const reloj = setTimeout(() => {
      vencio = true;
      hijo.kill('SIGKILL');
    }, timeoutMs);
    const onData = (d: Buffer) => {
      if (recortado) return;
      salida += d.toString();
      if (salida.length >= maxBytes) {
        salida = salida.slice(0, maxBytes);
        recortado = true;
      }
    };
    hijo.stdout?.on('data', onData);
    hijo.stderr?.on('data', onData);
    hijo.on('error', (e) => {
      clearTimeout(reloj);
      resolve({ codigo: null, salida: `no se pudo ejecutar ${cmd}: ${e.message}`, recortado: false, vencio: false });
    });
    hijo.on('close', (codigo) => {
      clearTimeout(reloj);
      resolve({ codigo, salida, recortado, vencio });
    });
  });
