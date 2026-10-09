// Las herramientas que un bot puede correr. Hoy: un catálogo de recon/escaneo.
//
// Dos reglas de seguridad, además del alcance (scope.ts / operadores.ts):
//   1. La IA/el operador eligen un PERFIL (un nombre), no flags sueltos. Cada
//      perfil es un conjunto de flags FIJOS, escritos acá. Nunca se arma la línea
//      con texto libre.
//   2. Se ejecuta con argv (spawn, sin shell): el objetivo va como UN argumento,
//      así que no hay forma de inyectar comandos aunque el objetivo sea raro.
//      Además clasificar() (scope.ts) garantiza que el objetivo no empiece con
//      "-", así que nunca se confunde con una flag.
//
// El ejecutor es inyectable: en producción corre el binario real; en las pruebas
// (y sin la herramienta instalada) se le pasa uno de mentira. Si el binario no
// está, el ejecutor real devuelve un error claro y el bot lo reporta.

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
  descripcion: string;
  perfiles: string[];
  correr(objetivo: string, perfil: string): Promise<{ ok: true; salida: string } | { ok: false; error: string }>;
}

export interface ConfigHerramienta {
  timeoutMs: number;
  maxBytes: number;
}

/** Cómo recibe el objetivo una herramienta: posicional al final, o tras una flag. */
type ModoObjetivo = { modo: 'final' } | { modo: 'flag'; flag: string };

export interface SpecHerramienta {
  /** Nombre en el menú y el comando (/nombre). */
  nombre: string;
  /** Binario a ejecutar, si difiere del nombre (ej: theharvester → theHarvester). */
  binario?: string;
  /** Para el menú de la IA: qué hace y qué objetivo espera. */
  descripcion: string;
  /** Subcomando fijo antes de los flags (ej: amass → ['enum']). */
  subcomando?: string[];
  objetivo: ModoObjetivo;
  /** nombre de perfil → flags FIJOS. El primero es el default. */
  perfiles: Record<string, string[]>;
}

/** Perfiles de nmap, exportados aparte por compatibilidad. */
export const PERFILES_NMAP: Record<string, string[]> = {
  rapido: ['-T4', '-Pn', '-F'],
  normal: ['-T4', '-Pn', '--top-ports', '1000'],
  servicios: ['-T4', '-Pn', '-sV', '--top-ports', '200'],
  completo: ['-T4', '-Pn', '-p-'],
};

/**
 * El catálogo. Cada entrada declara binario, cómo pasa el objetivo y sus
 * perfiles. Agregar una herramienta es agregar una entrada acá: no hay código
 * nuevo por herramienta, así que no hay forma de colar una ejecución arbitraria.
 */
export const CATALOGO: Record<string, SpecHerramienta> = {
  nmap: {
    nombre: 'nmap',
    descripcion: 'puertos, servicios y scripts de un host o IP',
    objetivo: { modo: 'final' },
    perfiles: PERFILES_NMAP,
  },
  masscan: {
    nombre: 'masscan',
    descripcion: 'escaneo de puertos muy rápido a gran escala (IP o rango); puede requerir privilegios',
    objetivo: { modo: 'final' },
    perfiles: {
      comunes: ['-p', '21,22,25,80,443,3389,8080,8443', '--rate', '1000'],
      top: ['--top-ports', '100', '--rate', '1000'],
      completo: ['-p', '1-65535', '--rate', '1000'],
    },
  },
  rustscan: {
    nombre: 'rustscan',
    descripcion: 'descubrimiento rápido de puertos de un host o IP',
    objetivo: { modo: 'flag', flag: '-a' },
    perfiles: {
      rapido: ['--ulimit', '5000', '-g'],
      completo: ['--ulimit', '5000', '--range', '1-65535', '-g'],
    },
  },
  naabu: {
    nombre: 'naabu',
    descripcion: 'descubrimiento de puertos de un host o IP (ProjectDiscovery)',
    objetivo: { modo: 'flag', flag: '-host' },
    perfiles: {
      rapido: ['-top-ports', '100', '-silent'],
      normal: ['-top-ports', '1000', '-silent'],
      completo: ['-p', '-', '-silent'],
    },
  },
  amass: {
    nombre: 'amass',
    descripcion: 'mapeo de activos y subdominios de un dominio',
    subcomando: ['enum'],
    objetivo: { modo: 'flag', flag: '-d' },
    perfiles: {
      pasivo: ['-passive'],
      activo: ['-active'],
    },
  },
  subfinder: {
    nombre: 'subfinder',
    descripcion: 'enumeración pasiva de subdominios de un dominio',
    objetivo: { modo: 'flag', flag: '-d' },
    perfiles: {
      normal: ['-silent'],
      todas: ['-all', '-silent'],
    },
  },
  findomain: {
    nombre: 'findomain',
    descripcion: 'descubrimiento de subdominios de un dominio',
    objetivo: { modo: 'flag', flag: '-t' },
    perfiles: {
      normal: ['-q'],
    },
  },
  theharvester: {
    nombre: 'theharvester',
    binario: 'theHarvester',
    descripcion: 'OSINT de correos, hosts y nombres públicos de un dominio',
    objetivo: { modo: 'flag', flag: '-d' },
    perfiles: {
      rapido: ['-b', 'crtsh'],
      varias: ['-b', 'crtsh,bing,duckduckgo,otx'],
    },
  },
  bbot: {
    nombre: 'bbot',
    descripcion: 'reconocimiento y descubrimiento de activos de un dominio',
    objetivo: { modo: 'flag', flag: '-t' },
    perfiles: {
      subdominios: ['-p', 'subdomain-enum', '-y', '--silent'],
    },
  },
  dnsx: {
    nombre: 'dnsx',
    descripcion: 'consultas y validación DNS de un dominio (rinde más con listas)',
    objetivo: { modo: 'flag', flag: '-d' },
    perfiles: {
      resolver: ['-silent', '-a', '-aaaa', '-cname', '-resp'],
    },
  },
  dnsenum: {
    nombre: 'dnsenum',
    descripcion: 'enumeración DNS de un dominio',
    objetivo: { modo: 'final' },
    perfiles: {
      rapido: ['--noreverse', '--nocolor'],
    },
  },
  fierce: {
    nombre: 'fierce',
    descripcion: 'reconocimiento de infraestructura DNS de un dominio',
    objetivo: { modo: 'flag', flag: '--domain' },
    perfiles: {
      normal: [],
    },
  },
  whois: {
    nombre: 'whois',
    descripcion: 'información de registro de un dominio',
    objetivo: { modo: 'final' },
    perfiles: {
      normal: [],
    },
  },
};

/** Construye una Herramienta a partir de su spec. */
export function crearHerramienta(spec: SpecHerramienta, ejecutor: Ejecutor, cfg: ConfigHerramienta): Herramienta {
  const binario = spec.binario ?? spec.nombre;
  return {
    nombre: spec.nombre,
    descripcion: spec.descripcion,
    perfiles: Object.keys(spec.perfiles),
    async correr(objetivo, perfil) {
      const flags = spec.perfiles[perfil];
      if (!flags) return { ok: false, error: `Perfil desconocido. Elegí uno: ${Object.keys(spec.perfiles).join(', ')}.` };
      const base = [...(spec.subcomando ?? []), ...flags];
      const argv = spec.objetivo.modo === 'final' ? [...base, objetivo] : [...base, spec.objetivo.flag, objetivo];
      const r = await ejecutor(binario, argv, cfg.timeoutMs, cfg.maxBytes);
      if (r.codigo === null && !r.vencio) return { ok: false, error: r.salida };
      let salida = r.salida.trim() || '(sin salida)';
      if (r.vencio) salida += '\n[cortado: superó el tiempo limite]';
      if (r.recortado) salida += '\n[salida recortada]';
      return { ok: true, salida };
    },
  };
}

/** Compat: nmap suelto. */
export function crearNmap(ejecutor: Ejecutor, cfg: ConfigHerramienta): Herramienta {
  return crearHerramienta(CATALOGO['nmap']!, ejecutor, cfg);
}

/**
 * Construye las herramientas pedidas (por nombre) desde el catálogo. Un nombre
 * desconocido se ignora. Sin nombres, construye todo el catálogo.
 */
export function crearHerramientas(nombres: string[], ejecutor: Ejecutor, cfg: ConfigHerramienta): Herramienta[] {
  const claves = nombres.length ? nombres : Object.keys(CATALOGO);
  const out: Herramienta[] = [];
  for (const n of claves) {
    const spec = CATALOGO[n.trim().toLowerCase()];
    if (spec) out.push(crearHerramienta(spec, ejecutor, cfg));
  }
  return out;
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
