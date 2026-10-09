// El sandbox: un contenedor efímero donde el agente corre comandos de pentesting,
// con la RED confinada al alcance aprobado (ver docs/15-AGENTE-SHELL.md).
//
// La baranda dura NO es "qué comando corre" sino "a dónde llega la red": aunque
// un prompt injection haga que la IA intente algo fuera de lugar, el contenedor
// solo puede conectar a las IPs del scope. Metadatos de nube, loopback y la LAN
// privada quedan bloqueados salvo que el scope los incluya explícitamente.
//
// El motor es inyectable: en producción es Docker; en las pruebas, uno falso. Así
// la lógica de egress se prueba sin Docker.

import { spawn } from 'node:child_process';
import { lookup } from 'node:dns/promises';
import { clasificar } from './scope.ts';

export interface OpcionesSandbox {
  imagen: string;
  /** Alcance aprobado (hosts/IPs/CIDR). Define a dónde puede conectar la red. */
  scope: string[];
  /** Destinos que SIEMPRE se bloquean (metadatos, loopback, privados). */
  excluidos: string[];
  memoria: string; // ej "1g"
  cpus: string; // ej "1.0"
  pids: number; // ej 256
}

export interface ResultadoComando {
  salida: string;
  codigo: number | null;
  recortado: boolean;
  vencio: boolean;
}

/** Ciclo de vida de un contenedor. Inyectable (Docker real o falso). */
export interface MotorContenedor {
  /** Crea el contenedor (ya con egress aplicado) y devuelve su id. */
  crear(opts: { imagen: string; nombre: string; egress: PlanEgress; limites: { memoria: string; cpus: string; pids: number } }): Promise<string>;
  /** Ejecuta un comando dentro del contenedor (shell), con tope de tiempo y bytes. */
  ejecutar(id: string, comando: string, timeoutMs: number, maxBytes: number): Promise<ResultadoComando>;
  /** Destruye el contenedor. */
  destruir(id: string): Promise<void>;
}

/** El plan de red: a qué IPs/CIDR se permite salir, y qué se niega siempre. */
export interface PlanEgress {
  permitidas: string[]; // IPs o CIDR (ya resueltos) que el contenedor puede alcanzar
  negadas: string[]; // IPs/CIDR que se bloquean aunque aparezcan (metadatos, etc.)
  sinResolver: string[]; // hosts del scope que no se pudieron resolver (quedan fuera)
}

/** Resolver DNS inyectable (para test). Devuelve las IPs de un host. */
export type Resolver = (host: string) => Promise<string[]>;

const resolverReal: Resolver = async (host) => {
  const rs = await lookup(host, { all: true });
  return rs.map((r) => r.address);
};

// Siempre negados, estén o no en el scope del admin: nube + loopback + link-local.
const NEGADOS_BASE = ['169.254.0.0/16', '127.0.0.0/8', '::1/128', 'fe80::/10'];
// Privados: negados salvo que el scope los incluya explícitamente.
const PRIVADOS = ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'];

/**
 * Arma el plan de egress desde el scope aprobado. Los hosts se resuelven a IP;
 * las IPs/CIDR pasan directo. Nada que caiga en los negados entra. Un scope vacío
 * da `permitidas: []` → el contenedor no puede conectar a nada (falla seguro).
 */
export async function planearEgress(scope: string[], excluidos: string[], resolver: Resolver = resolverReal): Promise<PlanEgress> {
  const negadas = [...NEGADOS_BASE, ...excluidos.filter((e) => clasificar(e))];
  const permitidas = new Set<string>();
  const sinResolver: string[] = [];

  // ¿El scope incluye explícitamente algún rango privado? Si no, se niegan todos.
  const permiteItem = (x: string) => scope.some((s) => s.trim().toLowerCase() === x);
  for (const p of PRIVADOS) if (!permiteItem(p)) negadas.push(p);

  for (const crudo of scope) {
    const item = crudo.trim().toLowerCase();
    const clase = clasificar(item);
    if (!clase) continue;
    if (clase.clase === 'ip' || clase.clase === 'cidr') {
      permitidas.add(item);
    } else {
      // host: resolver a IP(s).
      try {
        const ips = await resolver(item);
        if (ips.length) for (const ip of ips) permitidas.add(ip);
        else sinResolver.push(item);
      } catch {
        sinResolver.push(item);
      }
    }
  }
  // Quitar de permitidas cualquier cosa que esté negada por base (defensa).
  return { permitidas: [...permitidas], negadas: [...new Set(negadas)], sinResolver };
}

/** Genera el script de iptables que aplica el egress dentro del contenedor. */
export function scriptEgress(egress: PlanEgress): string {
  const l: string[] = [
    '#!/bin/sh',
    'set -e',
    // Política por defecto: nada sale.
    'iptables -P OUTPUT DROP',
    'iptables -P INPUT DROP',
    'iptables -P FORWARD DROP',
    'iptables -A OUTPUT -o lo -j ACCEPT',
    'iptables -A INPUT -i lo -j ACCEPT',
    'iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT',
    'iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT',
    // DNS para poder resolver nombres (solo salida a 53).
    'iptables -A OUTPUT -p udp --dport 53 -j ACCEPT',
    'iptables -A OUTPUT -p tcp --dport 53 -j ACCEPT',
  ];
  // Negados primero (ganan), luego permitidos.
  for (const d of egress.negadas) l.push(`iptables -A OUTPUT -d ${d} -j DROP`);
  for (const p of egress.permitidas) l.push(`iptables -A OUTPUT -d ${p} -j ACCEPT`);
  return l.join('\n') + '\n';
}

/** El motor Docker real. */
export const motorDocker: MotorContenedor = {
  async crear({ imagen, nombre, egress, limites }) {
    // Contenedor efímero, sin privilegios, solo las caps necesarias; duerme para
    // mantenerse vivo mientras ejecutamos comandos con `docker exec`.
    const args = [
      'run', '-d', '--rm', '--name', nombre,
      '--cap-drop', 'ALL', '--cap-add', 'NET_RAW', '--cap-add', 'NET_ADMIN',
      '--security-opt', 'no-new-privileges',
      '--memory', limites.memoria, '--cpus', limites.cpus, '--pids-limit', String(limites.pids),
      '--tmpfs', '/tmp:rw,noexec,nosuid,size=256m',
      imagen, 'sleep', 'infinity',
    ];
    const id = (await correrDocker(args, 30_000)).salida.trim();
    if (!id) throw new Error('no se pudo crear el contenedor');
    // Aplicar el egress dentro del contenedor.
    const script = scriptEgress(egress);
    await correrDockerConEntrada(['exec', '-i', nombre, 'sh', '-c', 'cat > /egress.sh && sh /egress.sh'], script, 30_000);
    return nombre;
  },
  async ejecutar(id, comando, timeoutMs, maxBytes) {
    const r = await correrDocker(['exec', id, 'sh', '-c', comando], timeoutMs, maxBytes);
    return r;
  },
  async destruir(id) {
    await correrDocker(['rm', '-f', id], 15_000).catch(() => undefined);
  },
};

function correrDocker(args: string[], timeoutMs: number, maxBytes = 100_000): Promise<ResultadoComando> {
  return new Promise((resolve) => {
    const hijo = spawn('docker', args, { shell: false });
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
      resolve({ salida: `no se pudo ejecutar docker: ${e.message}`, codigo: null, recortado: false, vencio: false });
    });
    hijo.on('close', (codigo) => {
      clearTimeout(reloj);
      resolve({ salida, codigo, recortado, vencio });
    });
  });
}

function correrDockerConEntrada(args: string[], entrada: string, timeoutMs: number): Promise<ResultadoComando> {
  return new Promise((resolve) => {
    const hijo = spawn('docker', args, { shell: false });
    let salida = '';
    const reloj = setTimeout(() => hijo.kill('SIGKILL'), timeoutMs);
    hijo.stdout?.on('data', (d: Buffer) => (salida += d.toString()));
    hijo.stderr?.on('data', (d: Buffer) => (salida += d.toString()));
    hijo.on('error', (e) => {
      clearTimeout(reloj);
      resolve({ salida: `no se pudo ejecutar docker: ${e.message}`, codigo: null, recortado: false, vencio: false });
    });
    hijo.on('close', (codigo) => {
      clearTimeout(reloj);
      resolve({ salida, codigo, recortado: false, vencio: false });
    });
    hijo.stdin?.end(entrada);
  });
}
