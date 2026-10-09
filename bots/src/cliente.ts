// Cliente headless de wtfuck: lo que la version web hace en el navegador, pero
// sin pantalla ni IndexedDB. Registra/reconecta la cuenta del bot, publica sus
// claves, abre el WebSocket, descifra lo que llega y cifra lo que responde.
//
// Reutiliza EL MISMO contrato que la app y la web (`web/app/src/datos/protocolo`):
// si el protocolo cambia, el bot se rompe junto con ellas, que es lo que se
// quiere. La criptografia es el mismo WASM (`./cripto`).
//
// Phase 1: solo chats directos y texto. Responde por pares (cifra una copia por
// aparato destino), que sirve igual en directas y en grupos.
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Api, ErrorApi } from './api.ts';
import { Cripto } from './cripto.ts';
import {
  aBytes,
  b64,
  deBytes,
  esAdjunto,
  esConClave,
  esTexto,
  mencionesEn,
  nuevoId,
  texto as cargaTexto,
  TIPO_CIFRADO,
  type Bajada,
  type Carga,
  type CargaAdjunto,
  type CopiaCifrada,
  type Destino,
  type PaqueteClaves,
  type Subida,
} from '../../web/app/src/datos/protocolo.ts';
import { descifrarArchivo } from './adjuntos.ts';

const PREKEYS_OBJETIVO = 100;
const PREKEYS_MINIMO = 20;

interface Sesion {
  token: string;
  usuarioId: string;
  dispositivoId: string;
  username: string;
  hardwareHash: string;
  sig: { firmada: number; kyber: number; unica: number };
}

export interface AdjuntoEntrante {
  adjuntoId: string;
  mime: string;
  nombre: string;
  clase: string;
  clave: string;
  nonce: string;
  bytes: number;
}

export interface MensajeEntrante {
  conversacionId: string;
  autorUsuario: string;
  autorUsuarioId: string;
  texto: string;
  adjunto?: AdjuntoEntrante;
}

export interface ConfigCliente {
  base: string;
  /** Carpeta del bot: almacen Signal + sesion. */
  datos: string;
  username: string;
  password: string;
  etiqueta: string;
  /** STRONGBOX | TEE | SOFTWARE_DEV (local). El servidor decide si lo acepta. */
  nivel: string;
  log: (m: string) => void;
}

export class Cliente {
  private readonly api: Api;
  private readonly cripto: Cripto;
  private readonly rutaSesion: string;
  private sesion: Sesion | null = null;
  private ws: WebSocket | null = null;
  private latido: ReturnType<typeof setInterval> | null = null;
  private intentos = 0;
  private cerrado = false;
  private colaBajadas: Promise<void> = Promise.resolve();
  private readonly pendientes = new Map<string, { ok: () => void; falla: (e: Error) => void }>();
  private readonly cacheDestinos = new Map<string, { hasta: number; lista: Destino[] }>();

  /** Se llama con cada texto directo que recibe el bot. */
  onMensaje: (m: MensajeEntrante) => void = () => {};

  constructor(private readonly cfg: ConfigCliente) {
    this.api = new Api(cfg.base);
    mkdirSync(cfg.datos, { recursive: true });
    this.cripto = new Cripto(join(cfg.datos, 'almacen.json'));
    this.rutaSesion = join(cfg.datos, 'sesion.json');
  }

  get usuario(): string {
    return this.sesion?.username ?? this.cfg.username;
  }

  // ------------------------------------------------------------------
  //  Arranque
  // ------------------------------------------------------------------

  async arrancar(): Promise<void> {
    if (existsSync(this.rutaSesion) && this.cripto.tieneIdentidad()) {
      this.sesion = JSON.parse(readFileSync(this.rutaSesion, 'utf8')) as Sesion;
      this.cfg.log(`Sesion cargada: @${this.sesion.username}. Refrescando token...`);
      await this.login();
    } else {
      this.cfg.log(`Registrando la cuenta del bot @${this.cfg.username}...`);
      await this.registrar();
    }
    await this.reponerClaves();
    this.conectar();
  }

  private async registrar(): Promise<void> {
    const { identidad } = await this.cripto.crearIdentidad();
    const hardwareHash = b64.a(crypto.getRandomValues(new Uint8Array(32)));
    const r = await this.api.pedir<{ token: string; usuarioId: string; dispositivoId: string; username: string }>(
      'POST',
      '/v1/registro',
      {
        username: this.cfg.username,
        password: this.cfg.password,
        etiquetaDispositivo: this.cfg.etiqueta,
        identidadPub: identidad,
        hardwareHash,
        hardwareNivel: this.cfg.nivel,
      },
      false,
    );
    this.sesion = { ...r, hardwareHash, sig: { firmada: 1, kyber: 1, unica: 1 } };
    this.api.token = r.token;
    this.guardarSesion();
    await this.publicarClaves(PREKEYS_OBJETIVO);
    this.cfg.log(`Cuenta creada: @${r.username}`);
  }

  private async login(): Promise<void> {
    const s = this.sesion!;
    const r = await this.api.pedir<{ token: string; usuarioId: string; dispositivoId: string; username: string }>(
      'POST',
      '/v1/sesion',
      { username: s.username, password: this.cfg.password, hardwareHash: s.hardwareHash },
      false,
    );
    this.sesion = { ...s, token: r.token, usuarioId: r.usuarioId, dispositivoId: r.dispositivoId };
    this.api.token = r.token;
    this.guardarSesion();
  }

  private async publicarClaves(cuantas: number): Promise<void> {
    const s = this.sesion!;
    const claves = await this.cripto.generarClaves(s.sig.firmada, s.sig.kyber, s.sig.unica, cuantas);
    await this.api.pedir('PUT', '/v1/claves', claves);
    s.sig = { firmada: s.sig.firmada + 1, kyber: s.sig.kyber + 1, unica: s.sig.unica + cuantas };
    this.guardarSesion();
  }

  private async reponerClaves(): Promise<void> {
    const e = await this.api.pedir<{ unicasDisponibles: number; objetivo: number; minimo: number }>('GET', '/v1/claves/estado');
    if (e.unicasDisponibles < (e.minimo ?? PREKEYS_MINIMO)) {
      await this.publicarClaves((e.objetivo ?? PREKEYS_OBJETIVO) - e.unicasDisponibles);
    }
  }

  private guardarSesion(): void {
    const tmp = `${this.rutaSesion}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.sesion), 'utf8');
    renameSync(tmp, this.rutaSesion);
  }

  // ------------------------------------------------------------------
  //  WebSocket
  // ------------------------------------------------------------------

  private conectar(): void {
    if (this.cerrado || !this.sesion) return;
    const base = new URL(this.cfg.base);
    const proto = base.protocol === 'https:' ? 'wss' : 'ws';
    const w = new WebSocket(`${proto}://${base.host}/v1/ws?token=${encodeURIComponent(this.sesion.token)}`);
    this.ws = w;

    w.onopen = () => {
      this.intentos = 0;
      this.cfg.log('En linea.');
      this.latido = setInterval(() => this.mandar({ type: 'ping' }), 25_000);
      this.latido.unref?.();
    };
    w.onmessage = (ev) => {
      let b: Bajada;
      try {
        b = JSON.parse(String(ev.data)) as Bajada;
      } catch {
        return;
      }
      this.encolar(b);
    };
    w.onclose = (ev) => {
      if (this.latido) clearInterval(this.latido);
      this.latido = null;
      if (this.ws !== w) return;
      this.ws = null;
      if (this.cerrado) return;
      if (ev.code === 1008) {
        // Token invalido: revocado o vencido. Reintentar login y reconectar.
        this.cfg.log('El servidor cerro la sesion (1008). Reautenticando...');
        void this.login()
          .then(() => this.conectar())
          .catch((e) => this.cfg.log(`No se pudo reautenticar: ${(e as Error).message}`));
        return;
      }
      const espera = Math.min(30_000, 1000 * 2 ** Math.min(this.intentos++, 6));
      this.cfg.log(`Sin red, reintento en ${Math.round(espera / 1000)}s.`);
      setTimeout(() => {
        if (!this.cerrado && !this.ws) this.conectar();
      }, espera).unref?.();
    };
    w.onerror = () => {
      /* onclose se encarga del reintento */
    };
  }

  private mandar(s: Subida): boolean {
    if (this.ws?.readyState !== WebSocket.OPEN) return false;
    this.ws.send(JSON.stringify(s));
    return true;
  }

  cerrar(): void {
    this.cerrado = true;
    if (this.latido) clearInterval(this.latido);
    this.ws?.close();
    this.ws = null;
  }

  // Las bajadas se procesan de a una: descifrar cambia el ratchet.
  private encolar(b: Bajada): void {
    this.colaBajadas = this.colaBajadas.then(() => this.manejar(b)).catch((e) => this.cfg.log(`bajada: ${(e as Error).message}`));
  }

  private async manejar(b: Bajada): Promise<void> {
    switch (b.type) {
      case 'entrega':
        return this.recibir(b);
      case 'evento':
        // Phase 1 no reacciona a eventos (agregado a grupo, etc.), pero los
        // acusa para que el servidor no los reentregue.
        this.mandar({ type: 'acuse_evento', eventoIds: [b.eventoId] });
        return;
      case 'aceptado': {
        this.pendientes.get(b.sobreId)?.ok();
        this.pendientes.delete(b.sobreId);
        return;
      }
      case 'error': {
        if (b.sobreId) {
          this.pendientes.get(b.sobreId)?.falla(new Error(b.motivo));
          this.pendientes.delete(b.sobreId);
        }
        return;
      }
      default:
        return; // entregado, leido, escribiendo, pong
    }
  }

  private async recibir(e: Extract<Bajada, { type: 'entrega' }>): Promise<void> {
    let carga: Carga | null = null;
    try {
      const claro =
        e.tipo === TIPO_CIFRADO.GRUPO
          ? await this.cripto.descifrarGrupo(e.origenDispositivo, e.cuerpo)
          : await this.cripto.descifrar(e.origenDispositivo, e.tipo, e.cuerpo);
      carga = deBytes(claro);
      if (esConClave(carga)) {
        await this.cripto.procesarDistribucion(e.origenDispositivo, carga.distribucion).catch(() => undefined);
        carga = carga.interior;
      }
    } catch (x) {
      this.cfg.log(`no se pudo descifrar ${e.sobreId}: ${(x as Error).message}`);
    }

    // Se acusa SIEMPRE: un sobre que no se pudo abrir no debe reentregarse
    // para siempre. (Igual que la app y la web.)
    this.mandar({ type: 'acuse', sobreIds: [e.sobreId] });

    const esMio = e.origenUsername.toLowerCase() === this.usuario.toLowerCase();
    if (esMio || !carga) return;
    // Diagnóstico: cada sobre recibido, con su id. Dos lineas con el MISMO id =
    // reentrega; con ids distintos = el emisor mando dos copias (dos aparatos).
    this.cfg.log(`<- sobre ${e.sobreId.slice(0, 8)} de @${e.origenUsername}`);
    if (esTexto(carga) && carga.cuerpo.trim()) {
      this.onMensaje({ conversacionId: e.conversacionId, autorUsuario: e.origenUsername, autorUsuarioId: e.origenUsuarioId, texto: carga.cuerpo });
    } else if (esAdjunto(carga)) {
      const a = carga as CargaAdjunto;
      this.onMensaje({
        conversacionId: e.conversacionId,
        autorUsuario: e.origenUsername,
        autorUsuarioId: e.origenUsuarioId,
        texto: (a.pie ?? '').trim(),
        adjunto: { adjuntoId: a.adjuntoId, mime: a.mime, nombre: a.nombre, clase: a.clase, clave: a.clave, nonce: a.nonce, bytes: a.bytes ?? 0 },
      });
    }
  }

  /** Registra en el server que un operador pidió un objetivo. Devuelve el estado. */
  async pedirAlcance(operador: string, objetivo: string): Promise<string> {
    const r = await this.api.pedir<{ estado: string }>('POST', '/v1/bot/alcance', { operador, objetivo });
    return r.estado;
  }

  /** Los objetivos aprobados de un operador (según el server). */
  async alcanceAprobados(operador: string): Promise<string[]> {
    const r = await this.api.pedir<{ aprobados: string[] }>('GET', `/v1/bot/alcance?operador=${encodeURIComponent(operador)}`);
    return r.aprobados ?? [];
  }

  /** Baja el blob cifrado de un adjunto y lo descifra. Devuelve el claro. */
  async descargarAdjunto(a: AdjuntoEntrante): Promise<Buffer> {
    const info = await this.api.pedir<{ urlDescarga: string }>('GET', `/v1/adjuntos/${a.adjuntoId}`);
    const r = await fetch(info.urlDescarga);
    if (!r.ok) throw new Error(`no se pudo bajar el adjunto (${r.status})`);
    const claro = await descifrarArchivo(await r.arrayBuffer(), a.clave, a.nonce);
    return Buffer.from(claro);
  }

  // ------------------------------------------------------------------
  //  Responder
  // ------------------------------------------------------------------

  /** Manda un texto a una conversacion. Cifra una copia por aparato destino. */
  async responder(conversacionId: string, texto: string): Promise<void> {
    const sobreId = nuevoId();
    // Diagnóstico: cada respuesta que sale, con su destino y un extracto.
    this.cfg.log(`-> ${conversacionId.slice(0, 8)}: ${texto.slice(0, 40).replace(/\n/g, ' ')}`);
    await this.api.pedir('POST', '/v1/mensajes', {
      mensajeId: sobreId,
      conversacionId,
      respondeA: null,
      menciones: mencionesEn(texto),
      reenviadoDe: null,
      adjuntoId: null,
      clase: '',
    });

    const carga: Carga = cargaTexto(texto);
    const ds = await this.destinos(conversacionId);
    const copias: CopiaCifrada[] = [];
    const claro = aBytes(carga);
    for (const d of ds) {
      if (!(await this.asegurarSesion(d))) continue;
      const c = await this.cripto.cifrar(d.dispositivoId, claro);
      copias.push({ destinos: [d.dispositivoId], cuerpo: c.cuerpo, tipo: c.tipo });
    }
    if (!copias.length) {
      if (ds.length) throw new Error('Nadie en esa conversacion tiene claves publicadas.');
      return; // conversacion sin otros destinos
    }

    await new Promise<void>((ok, falla) => {
      this.pendientes.set(sobreId, { ok, falla });
      if (!this.mandar({ type: 'enviar', sobreId, conversacionId, creadoEn: Date.now(), copias })) {
        this.pendientes.delete(sobreId);
        falla(new Error('sin socket'));
      }
      setTimeout(() => {
        if (this.pendientes.delete(sobreId)) falla(new Error('el servidor no confirmo el envio'));
      }, 20_000).unref?.();
    });
  }

  private async destinos(conversacionId: string, forzar = false): Promise<Destino[]> {
    const c = this.cacheDestinos.get(conversacionId);
    if (!forzar && c && c.hasta > Date.now()) return c.lista;
    const r = await this.api.pedir<{ destinos: Destino[] }>('GET', `/v1/conversaciones/${conversacionId}/destinos`);
    this.cacheDestinos.set(conversacionId, { hasta: Date.now() + 30_000, lista: r.destinos });
    return r.destinos;
  }

  private async asegurarSesion(d: Destino): Promise<boolean> {
    if (await this.cripto.tieneSesion(d.dispositivoId)) return true;
    try {
      const p = await this.api.pedir<PaqueteClaves>('GET', `/v1/claves/dispositivo/${d.dispositivoId}`);
      await this.cripto.abrirSesion(d.dispositivoId, p);
      return true;
    } catch (x) {
      this.cfg.log(`sin sesion con @${d.username}: ${x instanceof ErrorApi ? x.message : String(x)}`);
      return false;
    }
  }
}
