// Un bot: junta el cliente headless (E2EE) con la cola de turnos y el router de
// comandos. En la fase 1 no hay IA ni herramientas: el "trabajo" se simula con
// `/tarea N`, que ocupa el turno N segundos, para poder probar la cola desde
// dos cuentas.

import { Cliente, type MensajeEntrante } from './cliente.ts';
import { Cola } from './cola.ts';

export interface OpcionesBot {
  base: string;
  datos: string;
  username: string;
  password: string;
  etiqueta: string;
  nivel: string;
  nombre: string;
  /** Si usa cola de turnos (bots de herramienta). Un bot de charla no la usa. */
  conCola: boolean;
  inactividadMs: number;
  log: (m: string) => void;
}

const AYUDA = [
  'Soy un bot de wtfuck (fase 1: marco de prueba, sin IA ni herramientas todavia).',
  '',
  'Comandos:',
  '  /ayuda         esto',
  '  /turno         como esta la cola',
  '  /tarea N       simula un trabajo de N segundos (para probar la cola)',
  '  /fin           suelta tu turno',
  '',
  'Cualquier otro texto te lo devuelvo, para comprobar el cifrado de punta a punta.',
].join('\n');

export class Bot {
  private readonly cliente: Cliente;
  private readonly cola: Cola;
  private readonly conversacionDe = new Map<string, string>();

  constructor(private readonly o: OpcionesBot) {
    this.cliente = new Cliente({
      base: o.base,
      datos: o.datos,
      username: o.username,
      password: o.password,
      etiqueta: o.etiqueta,
      nivel: o.nivel,
      log: o.log,
    });
    this.cola = new Cola(o.inactividadMs, (siguiente) => this.alPasarTurno(siguiente));
    this.cliente.onMensaje = (m) => void this.alRecibir(m).catch((e) => o.log(`error: ${(e as Error).message}`));
  }

  async arrancar(): Promise<void> {
    await this.cliente.arrancar();
    this.o.log(`Bot "${this.o.nombre}" listo como @${this.cliente.usuario}. Escribile desde otra cuenta.`);
  }

  cerrar(): void {
    this.cliente.cerrar();
  }

  private async responder(conversacionId: string, texto: string): Promise<void> {
    await this.cliente.responder(conversacionId, texto).catch((e) => this.o.log(`no se pudo responder: ${(e as Error).message}`));
  }

  private async alRecibir(m: MensajeEntrante): Promise<void> {
    this.conversacionDe.set(m.autorUsuario, m.conversacionId);
    const texto = m.texto.trim();
    const [cmd, ...resto] = texto.split(/\s+/);

    if (cmd === '/ayuda' || cmd === '/start' || cmd === '/help') {
      return this.responder(m.conversacionId, AYUDA);
    }
    if (cmd === '/turno') {
      const e = this.cola.estado();
      const quien = e.activo ? `@${e.activo}` : 'nadie';
      const cola = e.espera.length ? ` En espera: ${e.espera.map((u) => '@' + u).join(', ')}.` : '';
      return this.responder(m.conversacionId, `Turno de ${quien}.${cola}`);
    }
    if (cmd === '/fin') {
      if (this.o.conCola) this.cola.liberar(m.autorUsuario);
      return this.responder(m.conversacionId, 'Listo, soltaste tu turno.');
    }

    // De aqui en adelante es "trabajo": en un bot con cola, necesita el turno.
    if (this.o.conCola) {
      const pos = this.cola.pedir(m.autorUsuario);
      if (pos !== 'activo') {
        return this.responder(
          m.conversacionId,
          `El bot esta ocupado. Estas en la cola, posicion ${pos}. Te aviso cuando te toque.`,
        );
      }
    }

    if (cmd === '/tarea') {
      const seg = Math.min(120, Math.max(1, Number(resto[0]) || 3));
      await this.responder(m.conversacionId, `Trabajando ${seg}s... (mientras tanto nadie mas me usa)`);
      await new Promise((r) => setTimeout(r, seg * 1000));
      this.cola.tocar(m.autorUsuario);
      return this.responder(m.conversacionId, `Termine la tarea de ${seg}s. Tu turno sigue abierto; /fin para soltarlo.`);
    }

    // Eco, para comprobar el cifrado de ida y vuelta.
    this.cola.tocar(m.autorUsuario);
    return this.responder(m.conversacionId, `Recibi: ${texto}`);
  }

  private alPasarTurno(siguiente: string | null): void {
    if (!siguiente) return;
    const conv = this.conversacionDe.get(siguiente);
    if (conv) void this.responder(conv, 'Te toca: el bot quedo libre para ti. Escribi tu comando.');
  }
}
