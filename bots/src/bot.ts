// Un bot: junta el cliente headless (E2EE) con la cola de turnos, el router de
// comandos y, en modo charla, una IA.
//
// Modos:
//   - eco:    devuelve lo que le escribas (fase 1, para probar el cifrado).
//   - charla: responde con una IA (fase 2). El texto SALE de la burbuja E2EE
//             hacia el proveedor de la IA; se avisa una vez por conversacion.

import { Cliente, type MensajeEntrante } from './cliente.ts';
import { Cola } from './cola.ts';
import type { IA, Turno } from './ia.ts';

export type Modo = 'eco' | 'charla';

export interface OpcionesBot {
  base: string;
  datos: string;
  username: string;
  password: string;
  etiqueta: string;
  nivel: string;
  nombre: string;
  modo: Modo;
  /** Si usa cola de turnos (bots de herramienta). Un bot de charla no la usa. */
  conCola: boolean;
  inactividadMs: number;
  log: (m: string) => void;
  // Solo en modo charla:
  ia?: IA;
  systemPrompt?: string;
  /** Cuantos pares usuario/bot recordar por conversacion. */
  historialMax?: number;
}

const AVISO_IA =
  'Soy un bot con IA. Para responderte, tu mensaje sale del cifrado de punta a ' +
  'punta hacia el proveedor de la IA (que no lo usa para entrenar). El servidor ' +
  'de wtfuck sigue sin poder leerlo. No me cuentes nada que no le dirias a ese ' +
  'proveedor. Escribí /olvida para que borre lo que llevamos hablado.';

function ayuda(modo: Modo): string {
  const comun = ['  /ayuda   esto', '  /turno   como esta la cola'];
  if (modo === 'charla') {
    return [
      'Soy un bot de wtfuck con IA. Escribime y te respondo.',
      '',
      'Comandos:',
      ...comun,
      '  /olvida  borro lo que llevamos hablado',
    ].join('\n');
  }
  return [
    'Soy un bot de wtfuck (modo eco: te devuelvo lo que escribas).',
    '',
    'Comandos:',
    ...comun,
    '  /tarea N  simula un trabajo de N segundos (para probar la cola)',
    '  /fin      suelta tu turno',
  ].join('\n');
}

export class Bot {
  private readonly cliente: Cliente;
  private readonly cola: Cola;
  private readonly conversacionDe = new Map<string, string>();
  private readonly historial = new Map<string, Turno[]>();
  private readonly avisados = new Set<string>();

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
    this.o.log(`Bot "${this.o.nombre}" (${this.o.modo}) listo como @${this.cliente.usuario}. Escribile desde otra cuenta.`);
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
      return this.responder(m.conversacionId, ayuda(this.o.modo));
    }
    if (cmd === '/turno') {
      const e = this.cola.estado();
      const quien = e.activo ? `@${e.activo}` : 'nadie';
      const cola = e.espera.length ? ` En espera: ${e.espera.map((u) => '@' + u).join(', ')}.` : '';
      return this.responder(m.conversacionId, `Turno de ${quien}.${cola}`);
    }
    if (cmd === '/olvida' || cmd === '/reinicia') {
      this.historial.delete(m.conversacionId);
      return this.responder(m.conversacionId, 'Listo, olvidé lo que llevabamos hablando.');
    }
    if (cmd === '/fin') {
      if (this.o.conCola) this.cola.liberar(m.autorUsuario);
      return this.responder(m.conversacionId, 'Listo, soltaste tu turno.');
    }

    // Trabajo: en un bot con cola, necesita el turno.
    if (this.o.conCola) {
      const pos = this.cola.pedir(m.autorUsuario);
      if (pos !== 'activo') {
        return this.responder(
          m.conversacionId,
          `El bot esta ocupado. Estas en la cola, posicion ${pos}. Te aviso cuando te toque.`,
        );
      }
    }

    if (this.o.modo === 'charla' && this.o.ia) {
      return this.charlar(m.conversacionId, texto);
    }

    if (cmd === '/tarea') {
      const seg = Math.min(120, Math.max(1, Number(resto[0]) || 3));
      await this.responder(m.conversacionId, `Trabajando ${seg}s... (mientras tanto nadie mas me usa)`);
      await new Promise((r) => setTimeout(r, seg * 1000));
      this.cola.tocar(m.autorUsuario);
      return this.responder(m.conversacionId, `Termine la tarea de ${seg}s. Tu turno sigue abierto; /fin para soltarlo.`);
    }

    // Modo eco.
    this.cola.tocar(m.autorUsuario);
    return this.responder(m.conversacionId, `Recibi: ${texto}`);
  }

  private async charlar(conversacionId: string, texto: string): Promise<void> {
    if (!this.avisados.has(conversacionId)) {
      this.avisados.add(conversacionId);
      await this.responder(conversacionId, AVISO_IA);
    }
    const max = (this.o.historialMax ?? 8) * 2;
    const hist = this.historial.get(conversacionId) ?? [];
    hist.push({ rol: 'user', texto });

    const turnos: Turno[] = [{ rol: 'system', texto: this.o.systemPrompt ?? '' }, ...hist];
    let respuesta: string;
    try {
      respuesta = await this.o.ia!.responder(turnos);
    } catch (e) {
      hist.pop(); // no se encadena un turno que fallo
      return this.responder(conversacionId, (e as Error).message);
    }
    hist.push({ rol: 'assistant', texto: respuesta });
    while (hist.length > max) hist.shift();
    this.historial.set(conversacionId, hist);
    await this.responder(conversacionId, respuesta);
  }

  private alPasarTurno(siguiente: string | null): void {
    if (!siguiente) return;
    const conv = this.conversacionDe.get(siguiente);
    if (conv) void this.responder(conv, 'Te toca: el bot quedo libre para ti. Escribi tu comando.');
  }
}
