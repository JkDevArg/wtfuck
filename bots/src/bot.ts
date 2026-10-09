// Un bot: junta el cliente headless (E2EE) con la cola de turnos, el router de
// comandos y, segun el modo, una IA o herramientas.
//
// Modos:
//   - eco:         devuelve lo que le escribas (fase 1).
//   - charla:      responde con una IA (fase 2). El texto sale de la burbuja
//                  E2EE hacia el proveedor; se avisa una vez por conversacion.
//   - herramienta: corre herramientas (nmap...) de a una, con bitacora, y bajo
//                  un modelo de TERMINOS + ALCANCE DECLARADO por el operador
//                  (ver operadores.ts): acepta, declara lo que esta autorizado a
//                  auditar, y recien ahi escanea eso.

import { Cliente, type MensajeEntrante } from './cliente.ts';
import { Cola } from './cola.ts';
import type { IA, Turno } from './ia.ts';
import type { Herramienta } from './herramientas.ts';
import type { Operadores } from './operadores.ts';
import type { Bitacora } from './bitacora.ts';
import type { Orquestador } from './orquestador.ts';

export type Modo = 'eco' | 'charla' | 'herramienta';

export interface OpcionesBot {
  base: string;
  datos: string;
  username: string;
  password: string;
  etiqueta: string;
  nivel: string;
  nombre: string;
  modo: Modo;
  conCola: boolean;
  inactividadMs: number;
  log: (m: string) => void;
  // charla:
  ia?: IA;
  systemPrompt?: string;
  historialMax?: number;
  // herramienta:
  herramienta?: Herramienta;
  operadores?: Operadores;
  bitacora?: Bitacora;
  terminos?: string;
  // herramienta + IA (opcional): entiende lenguaje natural y propone acciones.
  orquestador?: Orquestador;
}

const AVISO_IA =
  'Soy un bot con IA. Para responderte, tu mensaje sale del cifrado de punta a ' +
  'punta hacia el proveedor de la IA (que no lo usa para entrenar). El servidor ' +
  'de wtfuck sigue sin poder leerlo. No me cuentes nada que no le dirias a ese ' +
  'proveedor. Escribí /olvida para que borre lo que llevamos hablado.';

const TOPE_CHAT = 3500;

export class Bot {
  private readonly cliente: Cliente;
  private readonly cola: Cola;
  private readonly conversacionDe = new Map<string, string>();
  private readonly historial = new Map<string, Turno[]>();
  private readonly avisados = new Set<string>();
  private ocupado = false; // una sola herramienta corriendo a la vez

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

  private responder(conversacionId: string, texto: string): Promise<void> {
    return this.cliente.responder(conversacionId, texto.slice(0, TOPE_CHAT)).catch((e) => this.o.log(`no se pudo responder: ${(e as Error).message}`));
  }

  private async alRecibir(m: MensajeEntrante): Promise<void> {
    this.conversacionDe.set(m.autorUsuario, m.conversacionId);
    const texto = m.texto.trim();
    const [cmd = '', ...resto] = texto.split(/\s+/);

    if (cmd === '/ayuda' || cmd === '/start' || cmd === '/help') {
      return this.responder(m.conversacionId, this.ayuda());
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

    if (this.o.modo === 'herramienta') {
      return this.herramienta(m, cmd, resto);
    }

    if (cmd === '/olvida' || cmd === '/reinicia') {
      this.historial.delete(m.conversacionId);
      return this.responder(m.conversacionId, 'Listo, olvidé lo que llevabamos hablando.');
    }

    // eco y charla: trabajo que, con cola, necesita el turno.
    if (this.o.conCola) {
      const pos = this.cola.pedir(m.autorUsuario);
      if (pos !== 'activo') {
        return this.responder(m.conversacionId, `El bot esta ocupado. Estas en la cola, posicion ${pos}. Te aviso cuando te toque.`);
      }
    }

    if (this.o.modo === 'charla' && this.o.ia) {
      return this.charlar(m.conversacionId, texto);
    }
    this.cola.tocar(m.autorUsuario);
    return this.responder(m.conversacionId, `Recibi: ${texto}`);
  }

  // ------------------------------------------------------------------
  //  Modo herramienta: terminos + alcance declarado por el operador
  // ------------------------------------------------------------------

  private async herramienta(m: MensajeEntrante, cmd: string, resto: string[]): Promise<void> {
    const h = this.o.herramienta;
    const ops = this.o.operadores;
    const conv = m.conversacionId;
    const u = m.autorUsuario;
    if (!h || !ops) return this.responder(conv, 'Este bot no tiene herramientas configuradas.');

    if (!ops.puedeEntrar(u)) {
      return this.responder(conv, 'Este bot es por invitación. Pedile acceso a quien lo administra.');
    }

    if (cmd === '/terminos') return this.responder(conv, this.o.terminos ?? '(sin términos configurados)');

    if (cmd === '/acepto') {
      ops.aceptar(u);
      this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: '-', resultado: 'ejecutado', detalle: 'acepto los terminos' });
      return this.responder(conv, 'Aceptaste los términos. Ahora declará tu alcance: /alcance <objetivo> (declarás que estás autorizado a auditarlo). /ayuda para el resto.');
    }

    // Antes de cualquier otra cosa, hay que aceptar.
    if (!ops.aceptado(u)) {
      return this.responder(conv, `${this.o.terminos ?? ''}\n\nPara usarme, escribí /acepto.`);
    }

    if (cmd === '/alcance') {
      const sub = resto[0];
      if (!sub) {
        const lista = ops.alcance(u);
        return this.responder(conv, lista.length ? `Tu alcance declarado:\n- ${lista.join('\n- ')}` : 'No declaraste ningún objetivo todavía. /alcance <objetivo> para agregar.');
      }
      if (sub === 'quitar') {
        const obj = resto[1];
        if (!obj) return this.responder(conv, 'Uso: /alcance quitar <objetivo>');
        const quito = ops.quitarAlcance(u, obj);
        return this.responder(conv, quito ? `Saqué ${obj.toLowerCase()} de tu alcance.` : 'Eso no estaba en tu alcance.');
      }
      const r = ops.agregarAlcance(u, sub);
      if (!r.ok) {
        // Un intento de declarar algo excluido (p.ej. metadatos de nube) es
        // justo lo que conviene auditar: queda en la bitacora.
        this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: sub.toLowerCase(), resultado: 'rechazado-scope', detalle: `declaracion rechazada: ${r.motivo}` });
        return this.responder(conv, `No pude agregarlo: ${r.motivo}.`);
      }
      this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: r.objetivo, resultado: 'ejecutado', detalle: 'declaro alcance (autorizado)' });
      return this.responder(conv, `Agregado a tu alcance: ${r.objetivo}. Declarás que estás autorizado a auditarlo. Ya podés /${h.nombre} ${r.objetivo}.`);
    }

    if (cmd === '/perfiles') return this.responder(conv, `Perfiles de ${h.nombre}: ${h.perfiles.join(', ')}.`);
    if (cmd === `/${h.nombre}`) return this.correrHerramienta(m, resto);

    // Si hay orquestador y esto NO es un comando, lo entiende la IA.
    if (this.o.orquestador && !cmd.startsWith('/')) {
      return this.auditarConIA(m, m.texto.trim());
    }
    return this.responder(conv, 'No entendí. /ayuda para ver qué puedo hacer.');
  }

  /**
   * Lenguaje natural → la IA PROPONE un plan; la ejecución sigue pasando por la
   * misma puerta (permitido + cola + bitacora). La IA no se saltea nada.
   */
  private async auditarConIA(m: MensajeEntrante, texto: string): Promise<void> {
    const conv = m.conversacionId;
    const u = m.autorUsuario;
    const ops = this.o.operadores!;

    if (!this.avisados.has(conv)) {
      this.avisados.add(conv);
      await this.responder(conv, AVISO_IA);
    }

    const plan = await this.o.orquestador!.decidir(texto, { alcance: ops.alcance(u) });
    if (plan.accion === 'responder') {
      return this.responder(conv, plan.texto);
    }
    // plan.accion === 'ejecutar': la IA propuso herramienta+objetivo+perfil.
    if (plan.nota) await this.responder(conv, `(${plan.nota})`);
    return this.correrObjetivo(m, plan.objetivo, plan.perfil);
  }

  private async correrHerramienta(m: MensajeEntrante, resto: string[]): Promise<void> {
    const h = this.o.herramienta!;
    const objetivo = resto[0] ?? '';
    if (!objetivo) {
      return this.responder(m.conversacionId, `Uso: /${h.nombre} <objetivo> [perfil]. Perfiles: ${h.perfiles.join(', ')}. /alcance para ver lo tuyo.`);
    }
    return this.correrObjetivo(m, objetivo, resto[1] ?? 'normal');
  }

  /** Única puerta de ejecución: valida alcance, toma el turno y corre con bitacora. */
  private async correrObjetivo(m: MensajeEntrante, objetivo: string, perfil: string): Promise<void> {
    const h = this.o.herramienta!;
    const ops = this.o.operadores!;
    const bitacora = this.o.bitacora;
    const u = m.autorUsuario;

    const ev = ops.permitido(u, objetivo);
    if (!ev.ok) {
      bitacora?.registrar({ operador: u, herramienta: h.nombre, objetivo, resultado: 'rechazado-scope', detalle: ev.motivo });
      return this.responder(m.conversacionId, `No puedo escanear "${objetivo}": ${ev.motivo}.`);
    }

    const pos = this.cola.pedir(u);
    if (pos !== 'activo') {
      return this.responder(m.conversacionId, `El bot esta ocupado. Estas en la cola, posicion ${pos}. Te aviso cuando te toque.`);
    }
    if (this.ocupado) {
      bitacora?.registrar({ operador: u, herramienta: h.nombre, objetivo: ev.objetivo, perfil, resultado: 'rechazado-ocupado' });
      return this.responder(m.conversacionId, 'Ya hay un escaneo en curso. Esperá a que termine.');
    }

    this.ocupado = true;
    await this.responder(m.conversacionId, `Escaneando ${ev.objetivo} (${h.nombre}, perfil ${perfil})... puede tardar.`);
    try {
      const r = await h.correr(ev.objetivo, perfil);
      bitacora?.registrar({ operador: u, herramienta: h.nombre, objetivo: ev.objetivo, perfil, resultado: r.ok ? 'ejecutado' : 'error', detalle: r.ok ? undefined : r.error });
      await this.responder(m.conversacionId, r.ok ? r.salida : `No salió: ${r.error}`);
    } catch (e) {
      bitacora?.registrar({ operador: u, herramienta: h.nombre, objetivo: ev.objetivo, perfil, resultado: 'error', detalle: (e as Error).message });
      await this.responder(m.conversacionId, `Error corriendo ${h.nombre}: ${(e as Error).message}`);
    } finally {
      this.ocupado = false;
      this.cola.tocar(u);
    }
  }

  // ------------------------------------------------------------------
  //  Modo charla
  // ------------------------------------------------------------------

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
      hist.pop();
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

  private ayuda(): string {
    if (this.o.modo === 'charla') {
      return ['Soy un bot de wtfuck con IA. Escribime y te respondo.', '', 'Comandos:', '  /ayuda   esto', '  /olvida  borro lo que llevamos hablado'].join('\n');
    }
    if (this.o.modo === 'herramienta' && this.o.herramienta) {
      const h = this.o.herramienta;
      return [
        'Soy un bot de herramientas de wtfuck. Vos declarás qué estás autorizado a auditar, y escaneo eso (de a uno).',
        '',
        'Comandos:',
        '  /terminos               los términos de uso',
        '  /acepto                 acepto los términos',
        '  /alcance                lo que declaraste',
        '  /alcance <objetivo>     declaro que estoy autorizado a auditar ese objetivo',
        '  /alcance quitar <obj>   lo saco',
        `  /${h.nombre} <objetivo> [perfil]   escanea algo de tu alcance`,
        '  /perfiles               perfiles disponibles',
        '  /turno    /fin          la cola',
        ...(this.o.orquestador
          ? ['', 'También podés hablarme normal (ej: "mirá qué servicios corre scanme.nmap.org") y yo decido la herramienta. Igual solo corro lo que esté en tu alcance.']
          : []),
      ].join('\n');
    }
    return ['Soy un bot de wtfuck (modo eco: te devuelvo lo que escribas).', '', 'Comandos:', '  /ayuda   esto', '  /turno   como esta la cola', '  /fin     suelta tu turno'].join('\n');
  }
}
