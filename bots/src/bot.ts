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

import { Cliente, type AdjuntoEntrante, type MensajeEntrante } from './cliente.ts';
import { esListaDeTexto, parsearObjetivos, MAX_BYTES_LISTA } from './adjuntos.ts';
import { Cola } from './cola.ts';
import type { IA, Turno } from './ia.ts';
import type { Herramienta } from './herramientas.ts';
import type { Operadores } from './operadores.ts';
import type { Bitacora } from './bitacora.ts';
import type { Orquestador, PasoPlan } from './orquestador.ts';
import type { Agente, LimitesAgente } from './agente.ts';
import { planearEgress, type MotorContenedor } from './sandbox.ts';

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
  herramientas?: Herramienta[];
  operadores?: Operadores;
  bitacora?: Bitacora;
  terminos?: string;
  // herramienta + IA (opcional): entiende lenguaje natural y propone acciones.
  orquestador?: Orquestador;
  // rate limit de peticiones a la IA, por operador:
  rateVentanaMs?: number;
  rateMax?: number;
  // agente-shell (opcional): la IA corre comandos en un sandbox con red al scope.
  agente?: Agente;
  motorContenedor?: MotorContenedor;
  sandboxCfg?: { imagen: string; memoria: string; cpus: string; pids: number };
  limitesAgente?: LimitesAgente;
  comandoTimeoutMs?: number;
  excluidos?: string[];
}

/** Un reconocimiento en curso: plan de pasos que avanza con confirmación. */
interface SesionRecon {
  operador: string;
  objetivo: string;
  pasos: PasoPlan[];
  indice: number;
  estado: 'esperando' | 'corriendo';
}

/** Lista corta para un mensaje: los primeros y "y N más". */
function resumirLista(xs: string[], tope = 10): string {
  if (xs.length <= tope) return xs.join(', ');
  return `${xs.slice(0, tope).join(', ')} y ${xs.length - tope} más`;
}

/** ¿El texto es un sí, un no, o ninguno? (para confirmar pasos sin gastar IA). */
function confirmacion(texto: string): 'si' | 'no' | null {
  const t = texto.trim().toLowerCase();
  if (/^(s[ií]\b|dale|ok|oka|listo|siguiente|segu[ií]|continu|next|avanz|adelante|👍|🆗)/.test(t)) return 'si';
  if (/^(no\b|nop|basta|salir|stop|cancel|corta|cort[aá]|par[aá]|chau|dejá|deja)/.test(t)) return 'no';
  return null;
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
  private readonly recon = new Map<string, SesionRecon>(); // por conversacionId
  private readonly ultimasPeticiones = new Map<string, number[]>(); // rate limit por operador
  private readonly porNombre = new Map<string, Herramienta>(); // /nombre → herramienta
  private ocupado = false; // una sola herramienta corriendo a la vez

  constructor(private readonly o: OpcionesBot) {
    for (const h of o.herramientas ?? []) this.porNombre.set(h.nombre, h);
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
    if (cmd === '/fin' || cmd === '/cancelar') {
      const teniaRecon = this.recon.delete(m.conversacionId);
      if (this.o.conCola) this.cola.liberar(m.autorUsuario);
      return this.responder(m.conversacionId, teniaRecon ? 'Corté el reconocimiento y soltaste tu turno.' : 'Listo, soltaste tu turno.');
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
    const ops = this.o.operadores;
    const conv = m.conversacionId;
    const u = m.autorUsuario;
    if (!this.porNombre.size || !ops) return this.responder(conv, 'Este bot no tiene herramientas configuradas.');

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

    // ¿Mandó un archivo/lista? Lo tratamos como objetivos candidatos (a pedir).
    if (m.adjunto) return this.cargarLista(m, m.adjunto);

    // ¿Hay un reconocimiento en curso en esta conversación? Lo atendemos primero.
    const sesion = this.recon.get(conv);
    if (sesion && sesion.operador === u) {
      if (sesion.estado === 'corriendo') {
        return this.responder(conv, `Estoy corriendo el paso ${sesion.indice + 1}/${sesion.pasos.length} del recon de ${sesion.objetivo}. Esperá a que termine; después elegís si seguimos.`);
      }
      // Esperando confirmación. Un sí/no decide; un comando (/...) lo dejamos pasar.
      if (!cmd.startsWith('/')) {
        const c = confirmacion(m.texto);
        if (c === 'si') return this.avanzarRecon(m, sesion);
        if (c === 'no') {
          this.recon.delete(conv);
          return this.responder(conv, 'Listo, corté el reconocimiento. Quedó lo que ya corrimos.');
        }
        const prox = sesion.pasos[sesion.indice]!;
        return this.responder(conv, `Tenés un reconocimiento en curso de ${sesion.objetivo}. Paso ${sesion.indice + 1}/${sesion.pasos.length}: ${prox.descripcion}. ¿Sigo? (sí / no, o /cancelar)`);
      }
    }

    if (cmd === '/alcance') {
      const sub = resto[0];
      if (!sub) {
        const lista = ops.alcance(u);
        if (!lista.length) return this.responder(conv, 'No pediste ningún objetivo todavía. /alcance <objetivo> para pedir (lo aprueba el administrador).');
        const txt = lista.map((o) => `- ${o.objetivo} ${o.estado === 'aprobado' ? '✓ aprobado' : '⏳ pendiente de aprobación'}`).join('\n');
        return this.responder(conv, `Tu alcance:\n${txt}`);
      }
      if (sub === 'quitar') {
        const obj = resto[1];
        if (!obj) return this.responder(conv, 'Uso: /alcance quitar <objetivo>');
        const quito = ops.quitarAlcance(u, obj);
        return this.responder(conv, quito ? `Saqué ${obj.toLowerCase()} de tu alcance.` : 'Eso no estaba en tu alcance.');
      }
      const r = ops.pedirAlcance(u, sub);
      if (!r.ok) {
        // Un intento de pedir algo excluido (p.ej. metadatos de nube) es justo
        // lo que conviene auditar: queda en la bitacora.
        this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: sub.toLowerCase(), resultado: 'rechazado-scope', detalle: `pedido rechazado: ${r.motivo}` });
        return this.responder(conv, `No pude agregarlo: ${r.motivo}.`);
      }
      if (r.estado === 'aprobado') return this.responder(conv, `${r.objetivo} ya está aprobado. Podés auditarlo.`);
      this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: r.objetivo, resultado: 'ejecutado', detalle: 'pide alcance (atesta autorizacion), pendiente de aprobacion' });
      this.avisarAdmins(`Pedido de alcance: @${u} quiere auditar "${r.objetivo}". Aprobá con /aprobar ${u} ${r.objetivo} o /pendientes para ver todo.`);
      return this.responder(conv, `Pedí "${r.objetivo}" (declarás que estás autorizado a auditarlo). Queda ⏳ pendiente de aprobación del administrador. Te aviso cuando pase el check.`);
    }

    // ---- Comandos de admin ----
    if (cmd === '/pendientes' || cmd === '/aprobar' || cmd === '/rechazar') {
      if (!ops.esAdmin(u)) return this.responder(conv, 'Ese comando es solo para el administrador.');
      if (cmd === '/pendientes') {
        const pend = ops.pendientes();
        if (!pend.length) return this.responder(conv, 'No hay pedidos pendientes.');
        const txt = pend.map((p) => `- @${p.operador} → ${p.objetivo}  (/aprobar ${p.operador} ${p.objetivo})`).join('\n');
        return this.responder(conv, `Pedidos pendientes:\n${txt}`);
      }
      const operador = resto[0];
      const objetivo = resto[1];
      if (!operador || !objetivo) return this.responder(conv, `Uso: ${cmd} <operador> <objetivo>`);
      if (cmd === '/aprobar') {
        const r = ops.aprobar(u, operador, objetivo);
        if (!r.ok) return this.responder(conv, `No pude aprobar: ${r.motivo}.`);
        this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: r.objetivo, resultado: 'ejecutado', detalle: `aprobo alcance de @${operador.toLowerCase().replace(/^@/, '')}` });
        this.avisarOperador(operador, `✓ El administrador aprobó "${r.objetivo}". Ya podés auditarlo (/nmap ${r.objetivo}, otra herramienta, o pedime un reconocimiento).`);
        return this.responder(conv, `Aprobado: @${operador} ya puede auditar "${r.objetivo}".`);
      }
      // /rechazar
      const r = ops.rechazar(u, operador, objetivo);
      if (!r.ok) return this.responder(conv, `No pude rechazar: ${r.motivo}.`);
      this.o.bitacora?.registrar({ operador: u, herramienta: '-', objetivo: objetivo.toLowerCase(), resultado: 'rechazado-scope', detalle: `admin rechazo pedido de @${operador.toLowerCase().replace(/^@/, '')}` });
      this.avisarOperador(operador, `El administrador rechazó tu pedido de "${objetivo.toLowerCase()}".`);
      return this.responder(conv, `Rechazado el pedido de @${operador} para "${objetivo.toLowerCase()}".`);
    }

    if (cmd === '/perfiles' || cmd === '/herramientas') {
      const txt = [...this.porNombre.values()].map((h) => `  /${h.nombre} — ${h.descripcion}\n     perfiles: ${h.perfiles.join(', ')}`).join('\n');
      return this.responder(conv, `Herramientas:\n${txt}`);
    }

    if (cmd === '/pentest') {
      if (!this.o.agente || !this.o.motorContenedor) return this.responder(conv, 'El modo pentest (shell en sandbox) no está activo en este bot.');
      const objetivo = resto[0];
      if (!objetivo) return this.responder(conv, 'Uso: /pentest <objetivo> (tiene que estar aprobado). La IA corre comandos en un sandbox con la red limitada a ese objetivo.');
      return this.pentest(m, objetivo);
    }
    // ¿El comando es /<herramienta>?
    if (cmd.startsWith('/')) {
      const nombre = cmd.slice(1).toLowerCase();
      if (this.porNombre.has(nombre)) return this.correrHerramienta(m, nombre, resto);
    }

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

    // Rate limit: pensar con la IA cuesta. Si escribís de más, freno acá (no
    // encolo trabajo nuevo: el bot va paso por paso, no se satura).
    if (!this.rateOk(u)) {
      return this.responder(conv, 'Vas muy rápido. Dejá que termine lo anterior y escribime de nuevo en unos segundos.');
    }

    if (!this.avisados.has(conv)) {
      this.avisados.add(conv);
      await this.responder(conv, AVISO_IA);
    }

    await this.responder(conv, 'Pensando...');
    const plan = await this.o.orquestador!.decidir(texto, { alcance: ops.aprobados(u) });

    if (plan.accion === 'responder') {
      return this.responder(conv, plan.texto);
    }
    if (plan.accion === 'ejecutar') {
      if (plan.nota) await this.responder(conv, `(${plan.nota})`);
      return this.correrObjetivo(m, plan.herramienta, plan.objetivo, plan.perfil);
    }
    // plan.accion === 'recon': plan de varios pasos, con confirmación entre cada uno.
    return this.iniciarRecon(m, plan.objetivo, plan.pasos);
  }

  /** Muestra el plan y arranca la máquina de pasos (no corre nada todavía). */
  private async iniciarRecon(m: MensajeEntrante, objetivo: string, pasos: PasoPlan[]): Promise<void> {
    const conv = m.conversacionId;
    const u = m.autorUsuario;
    const ops = this.o.operadores!;

    // El objetivo tiene que estar APROBADO antes de empezar. La IA no habilita nada.
    const ev = ops.permitido(u, objetivo);
    if (!ev.ok) {
      this.o.bitacora?.registrar({ operador: u, herramienta: 'recon', objetivo, resultado: 'rechazado-scope', detalle: ev.motivo });
      return this.responder(conv, `Para reconocer "${objetivo}" primero tiene que estar aprobado: ${ev.motivo}.`);
    }

    this.recon.set(conv, { operador: u, objetivo: ev.objetivo, pasos, indice: 0, estado: 'esperando' });
    this.o.bitacora?.registrar({ operador: u, herramienta: 'recon', objetivo: ev.objetivo, resultado: 'ejecutado', detalle: `plan de ${pasos.length} paso(s)` });
    const lista = pasos.map((p, i) => `  ${i + 1}. ${p.descripcion} (${p.herramienta} ${p.perfil})`).join('\n');
    const primero = pasos[0]!;
    return this.responder(
      conv,
      `Plan de reconocimiento para ${ev.objetivo}:\n${lista}\n\nVoy paso por paso y te muestro cada resultado. Arranco con el paso 1: ${primero.descripcion}. ¿Dale? (sí / no, o /cancelar)`,
    );
  }

  /** Corre el paso actual del recon, streamea el resultado y propone el siguiente. */
  private async avanzarRecon(m: MensajeEntrante, sesion: SesionRecon): Promise<void> {
    const conv = m.conversacionId;
    const paso = sesion.pasos[sesion.indice]!;

    sesion.estado = 'corriendo';
    await this.responder(conv, `🔍 Paso ${sesion.indice + 1}/${sesion.pasos.length}: ${paso.descripcion}`);
    await this.correrObjetivo(m, paso.herramienta, sesion.objetivo, paso.perfil); // misma puerta: permitido + cola + bitacora

    sesion.indice++;
    sesion.estado = 'esperando';
    if (sesion.indice >= sesion.pasos.length) {
      this.recon.delete(conv);
      return this.responder(conv, `✅ Reconocimiento de ${sesion.objetivo} terminado (${sesion.pasos.length} paso/s). Si querés que profundice en algo, pedímelo.`);
    }
    const prox = sesion.pasos[sesion.indice]!;
    return this.responder(conv, `Paso ${sesion.indice}/${sesion.pasos.length} listo. Siguiente: ${prox.descripcion} (${prox.herramienta} ${prox.perfil}). ¿Sigo? (sí / no)`);
  }

  /** Ventana deslizante de peticiones a la IA por operador. */
  private rateOk(u: string): boolean {
    const ahora = Date.now();
    const ventana = this.o.rateVentanaMs ?? 60_000;
    const max = this.o.rateMax ?? 8;
    const arr = (this.ultimasPeticiones.get(u) ?? []).filter((t) => ahora - t < ventana);
    if (arr.length >= max) {
      this.ultimasPeticiones.set(u, arr);
      return false;
    }
    arr.push(ahora);
    this.ultimasPeticiones.set(u, arr);
    return true;
  }

  private async correrHerramienta(m: MensajeEntrante, nombre: string, resto: string[]): Promise<void> {
    const h = this.porNombre.get(nombre)!;
    const objetivo = resto[0] ?? '';
    if (!objetivo) {
      return this.responder(m.conversacionId, `Uso: /${h.nombre} <objetivo> [perfil]. Perfiles: ${h.perfiles.join(', ')}. /alcance para ver lo tuyo.`);
    }
    return this.correrObjetivo(m, nombre, objetivo, resto[1] ?? h.perfiles[0]!);
  }

  /** Única puerta de ejecución: valida alcance, toma el turno y corre con bitacora. */
  private async correrObjetivo(m: MensajeEntrante, nombre: string, objetivo: string, perfil: string): Promise<void> {
    const h = this.porNombre.get(nombre);
    const ops = this.o.operadores!;
    const bitacora = this.o.bitacora;
    const u = m.autorUsuario;
    if (!h) return this.responder(m.conversacionId, `No tengo la herramienta "${nombre}".`);

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

  /**
   * Agente-shell: la IA corre comandos en un sandbox efímero cuya red SOLO
   * alcanza el objetivo aprobado. Streamea cada comando y su salida al chat.
   */
  private async pentest(m: MensajeEntrante, objetivo: string): Promise<void> {
    const conv = m.conversacionId;
    const u = m.autorUsuario;
    const ops = this.o.operadores!;
    const motor = this.o.motorContenedor!;
    const agente = this.o.agente!;
    const bitacora = this.o.bitacora;

    // El objetivo tiene que estar APROBADO (la IA no habilita nada).
    const ev = ops.permitido(u, objetivo);
    if (!ev.ok) {
      bitacora?.registrar({ operador: u, herramienta: 'shell', objetivo, resultado: 'rechazado-scope', detalle: ev.motivo });
      return this.responder(conv, `Para un pentest de "${objetivo}" primero tiene que estar aprobado: ${ev.motivo}.`);
    }
    if (!this.rateOk(u)) return this.responder(conv, 'Vas muy rápido. Esperá unos segundos.');

    const pos = this.cola.pedir(u);
    if (pos !== 'activo') return this.responder(conv, `El bot esta ocupado. Estas en la cola, posicion ${pos}.`);
    if (this.ocupado) return this.responder(conv, 'Ya hay algo corriendo. Esperá a que termine.');

    if (!this.avisados.has(conv)) {
      this.avisados.add(conv);
      await this.responder(conv, AVISO_IA);
    }

    // El sandbox solo ve ESTE objetivo (más restrictivo que todo el alcance).
    const egress = await planearEgress([ev.objetivo], this.o.excluidos ?? []);
    if (!egress.permitidas.length) {
      return this.responder(conv, `No pude resolver "${ev.objetivo}" a una IP, así que no abro el sandbox (quedaría sin red). Probá con una IP o revisá el DNS.`);
    }

    this.ocupado = true;
    const id = `wtfuck-pentest-${u}-${Date.now()}`.replace(/[^a-zA-Z0-9_.-]/g, '');
    bitacora?.registrar({ operador: u, herramienta: 'shell', objetivo: ev.objetivo, resultado: 'ejecutado', detalle: `inicia sandbox (egress: ${egress.permitidas.join(',')})` });
    await this.responder(conv, `🧪 Abriendo sandbox para ${ev.objetivo} (red limitada a: ${egress.permitidas.join(', ')}). La IA va a encadenar comandos; te muestro cada uno.`);

    const cfg = this.o.sandboxCfg!;
    try {
      await motor.crear({ imagen: cfg.imagen, nombre: id, egress, limites: { memoria: cfg.memoria, cpus: cfg.cpus, pids: cfg.pids } });
    } catch (e) {
      this.ocupado = false;
      this.cola.tocar(u);
      bitacora?.registrar({ operador: u, herramienta: 'shell', objetivo: ev.objetivo, resultado: 'error', detalle: `no se pudo crear el sandbox: ${(e as Error).message}` });
      return this.responder(conv, `No pude abrir el sandbox: ${(e as Error).message}`);
    }

    const ejecutar = (comando: string) => motor.ejecutar(id, comando, this.o.comandoTimeoutMs ?? 120_000, 20_000);
    try {
      await agente.correr(ev.objetivo, ejecutar, this.o.limitesAgente ?? { maxComandos: 15, sesionTimeoutMs: 1_800_000 }, (e) => {
        if (e.tipo === 'comando') {
          bitacora?.registrar({ operador: u, herramienta: 'shell', objetivo: ev.objetivo, resultado: 'ejecutado', detalle: e.comando });
          void this.responder(conv, `🖥️ [${e.n}] $ ${e.comando}${e.motivo ? `\n   (${e.motivo})` : ''}`);
        } else if (e.tipo === 'salida') {
          const extra = (e.vencio ? '\n[cortado por tiempo]' : '') + (e.recortado ? '\n[salida recortada]' : '');
          void this.responder(conv, (e.salida.trim() || '(sin salida)') + extra);
        } else if (e.tipo === 'fin') {
          const cab = e.motivo === 'terminado' ? '✅ Pentest terminado' : `⏹️ Pentest cortado (${e.motivo})`;
          void this.responder(conv, `${cab}:\n${e.resumen}`);
        }
      });
    } catch (e) {
      await this.responder(conv, `Error en el pentest: ${(e as Error).message}`);
    } finally {
      await motor.destruir(id).catch(() => undefined);
      bitacora?.registrar({ operador: u, herramienta: 'shell', objetivo: ev.objetivo, resultado: 'ejecutado', detalle: 'cierra sandbox' });
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

  /** Avisa a los admins que YA tienen una conversación abierta con el bot. */
  private avisarAdmins(texto: string): void {
    const ops = this.o.operadores;
    if (!ops) return;
    for (const [usuario, conv] of this.conversacionDe) {
      if (ops.esAdmin(usuario)) void this.responder(conv, texto);
    }
  }

  /** Avisa a un operador si tiene una conversación abierta con el bot. */
  private avisarOperador(operador: string, texto: string): void {
    const u = operador.toLowerCase().replace(/^@/, '');
    const conv = this.conversacionDe.get(u);
    if (conv) void this.responder(conv, texto);
  }

  /**
   * Un archivo de texto con objetivos → los PIDE (pendientes de aprobación). Un
   * adjunto no saltea ningún control: solo es una forma cómoda de cargar la lista.
   */
  private async cargarLista(m: MensajeEntrante, a: AdjuntoEntrante): Promise<void> {
    const conv = m.conversacionId;
    const u = m.autorUsuario;
    const ops = this.o.operadores!;

    if (!esListaDeTexto(a.mime, a.nombre)) {
      return this.responder(conv, 'Solo puedo leer listas de texto (.txt, .csv, .list…). Ese archivo no parece texto seguro, así que no lo abro.');
    }
    if (a.bytes > MAX_BYTES_LISTA) {
      return this.responder(conv, `Ese archivo es muy grande para una lista (${Math.round(a.bytes / 1024)} KiB). Mandá uno de hasta ${Math.round(MAX_BYTES_LISTA / 1024)} KiB.`);
    }

    let texto: string;
    try {
      const buf = await this.cliente.descargarAdjunto(a);
      if (buf.length > MAX_BYTES_LISTA) return this.responder(conv, 'El archivo resultó más grande de lo declarado; no lo proceso.');
      texto = buf.toString('utf8');
    } catch (e) {
      return this.responder(conv, `No pude leer el archivo: ${(e as Error).message}.`);
    }

    const { validos, invalidas, truncado } = parsearObjetivos(texto);
    if (!validos.length) {
      return this.responder(conv, 'No encontré objetivos válidos (hosts, IPs o CIDR) en el archivo.');
    }

    const pedidos: string[] = [];
    const yaEstaban: string[] = [];
    const rechazados: string[] = [];
    for (const obj of validos) {
      const r = ops.pedirAlcance(u, obj);
      if (!r.ok) rechazados.push(obj);
      else if (r.estado === 'aprobado') yaEstaban.push(obj);
      else pedidos.push(obj);
    }

    this.o.bitacora?.registrar({
      operador: u,
      herramienta: '-',
      objetivo: `lista:${a.nombre || 'archivo'}`,
      resultado: 'ejecutado',
      detalle: `pide ${pedidos.length}, ya ${yaEstaban.length}, rechaza ${rechazados.length}, invalidas ${invalidas}${truncado ? ', truncada' : ''}`,
    });
    if (pedidos.length) {
      this.avisarAdmins(`@${u} cargó una lista: ${pedidos.length} objetivo(s) pendiente(s) de aprobación. Mirá /pendientes.`);
    }

    const partes = [`De "${a.nombre || 'tu archivo'}" saqué ${validos.length} objetivo(s) válido(s):`];
    if (pedidos.length) partes.push(`⏳ Pedí ${pedidos.length} (pendientes de aprobación del admin): ${resumirLista(pedidos)}`);
    if (yaEstaban.length) partes.push(`✓ ${yaEstaban.length} ya estaban aprobados.`);
    if (rechazados.length) partes.push(`✗ ${rechazados.length} rechazados (excluidos por el admin): ${resumirLista(rechazados)}`);
    if (invalidas) partes.push(`${invalidas} línea(s) no eran objetivos válidos y las descarté.`);
    if (truncado) partes.push(`Corté en ${validos.length} objetivos (la lista era más larga).`);
    return this.responder(conv, partes.join('\n'));
  }

  private ayuda(): string {
    if (this.o.modo === 'charla') {
      return ['Soy un bot de wtfuck con IA. Escribime y te respondo.', '', 'Comandos:', '  /ayuda   esto', '  /olvida  borro lo que llevamos hablado'].join('\n');
    }
    if (this.o.modo === 'herramienta' && this.porNombre.size) {
      const nombres = [...this.porNombre.keys()];
      return [
        'Soy un bot de herramientas de wtfuck. Vos declarás qué estás autorizado a auditar, y corro eso (de a uno).',
        '',
        'Comandos:',
        '  /terminos               los términos de uso',
        '  /acepto                 acepto los términos',
        '  /alcance                lo que pediste y su estado (✓/⏳)',
        '  /alcance <objetivo>     pido auditar ese objetivo (lo aprueba el admin)',
        '  /alcance quitar <obj>   lo saco',
        `  /<herramienta> <objetivo> [perfil]   corre esa herramienta sobre tu alcance aprobado`,
        `  herramientas: ${nombres.map((n) => '/' + n).join(' ')}`,
        '  /herramientas           qué hace cada una y sus perfiles',
        ...(this.o.agente ? ['  /pentest <objetivo>     la IA audita con shell en un sandbox (red solo a ese objetivo)'] : []),
        '  /turno    /fin          la cola (/fin o /cancelar corta un recon)',
        '',
        'También podés mandarme un archivo de texto (.txt/.csv) con una lista de objetivos: los pido por vos (quedan pendientes de aprobación).',
        '',
        'Admin:',
        '  /pendientes             pedidos esperando aprobación',
        '  /aprobar <op> <obj>     apruebo un pedido',
        '  /rechazar <op> <obj>    lo rechazo',
        ...(this.o.orquestador
          ? ['', 'También podés hablarme normal (ej: "mirá qué servicios corre scanme.nmap.org") y yo decido la herramienta. Igual solo corro lo que esté en tu alcance.']
          : []),
      ].join('\n');
    }
    return ['Soy un bot de wtfuck (modo eco: te devuelvo lo que escribas).', '', 'Comandos:', '  /ayuda   esto', '  /turno   como esta la cola', '  /fin     suelta tu turno'].join('\n');
  }
}
