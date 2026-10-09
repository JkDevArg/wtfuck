// libsignal para el bot: el MISMO WebAssembly que la version web (web/cripto),
// pero con el almacen guardado en un archivo en vez de IndexedDB.
//
// Un bot es un cliente mas: tiene su identidad Signal, descifra lo que le
// mandan y cifra lo que responde. El servidor sigue sin poder leer nada; el
// bot, que es un participante, si (como cualquier destinatario).
//
// Una sola instancia y en serie: el Double Ratchet es estado que cambia, y dos
// operaciones a la vez sobre la misma sesion la corromperian. Es el mismo
// motivo del candado en la app y en la web.
import { existsSync, readFileSync } from 'node:fs';
import { readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
// El paquete WASM se genera con `web/cripto/construir.sh` (esta en .gitignore);
// sus tipos viven en el .d.ts junto al .js generado.
// Se usa `initSync` y no el `init` por defecto: el import por defecto
// (`import init, {...}`) se envuelve raro bajo tsx/esbuild (queda como objeto,
// no funcion). `initSync` es un export nombrado, compila el .wasm de forma
// sincrona desde los BYTES, y asi no depende de un `fetch` de URL `file:` que
// Node no resuelve.
import { Cliente, initSync } from '../../web/cripto/pkg/wtfuck_cripto.js';

const aqui = dirname(fileURLToPath(import.meta.url));
const RUTA_WASM = join(aqui, '..', '..', 'web', 'cripto', 'pkg', 'wtfuck_cripto_bg.wasm');

let wasmIniciado = false;
function iniciarWasm(): void {
  if (wasmIniciado) return;
  initSync({ module: readFileSync(RUTA_WASM) });
  wasmIniciado = true;
}

export interface ClavesPublicadas {
  registrationId: number;
  identidad: string;
  firmada: { keyId: number; publica: string; firma: string };
  kyber: { keyId: number; publica: string; firma: string };
  unicas: { keyId: number; publica: string }[];
}

/** El almacen Signal de UN bot, en un archivo JSON. */
export class Cripto {
  private cliente: InstanceType<typeof Cliente> | null = null;
  private cola: Promise<unknown> = Promise.resolve();

  constructor(private readonly archivo: string) {}

  tieneIdentidad(): boolean {
    return existsSync(this.archivo);
  }

  /** Identidad al azar. Pisa lo que hubiera: solo para crear el bot. */
  async crearIdentidad(): Promise<{ identidad: string; registrationId: number }> {
    await iniciarWasm();
    this.cliente = new Cliente();
    await this.guardar(this.cliente);
    return { identidad: this.cliente.identidad(), registrationId: this.cliente.registrationId() };
  }

  generarClaves(firmadaId: number, kyberId: number, desde: number, cuantas: number): Promise<ClavesPublicadas> {
    return this.enSerie((c) => JSON.parse(c.generarClaves(firmadaId, kyberId, desde, cuantas, Date.now())) as ClavesPublicadas, true);
  }

  tieneSesion(dispositivo: string): Promise<boolean> {
    return this.enSerie((c) => c.tieneSesion(dispositivo), false);
  }

  abrirSesion(dispositivo: string, paquete: unknown): Promise<void> {
    return this.enSerie((c) => c.abrirSesion(dispositivo, JSON.stringify(paquete), Date.now()), true);
  }

  cifrar(dispositivo: string, claro: Uint8Array): Promise<{ tipo: number; cuerpo: string }> {
    return this.enSerie((c) => JSON.parse(c.cifrar(dispositivo, claro, Date.now())) as { tipo: number; cuerpo: string }, true);
  }

  descifrar(dispositivo: string, tipo: number, cuerpo: string): Promise<Uint8Array> {
    return this.enSerie((c) => c.descifrar(dispositivo, tipo, cuerpo), true);
  }

  descifrarGrupo(remitente: string, cuerpo: string): Promise<Uint8Array> {
    return this.enSerie((c) => c.descifrarGrupo(remitente, cuerpo), true);
  }

  procesarDistribucion(remitente: string, skdm: string): Promise<void> {
    return this.enSerie((c) => c.procesarDistribucion(remitente, skdm), true);
  }

  identidadCambio(dispositivo: string): Promise<boolean> {
    return this.enSerie((c) => c.identidadCambio(dispositivo), false);
  }

  identidadVista(dispositivo: string): Promise<void> {
    return this.enSerie((c) => c.identidadVista(dispositivo), true);
  }

  // ------------------------------------------------------------------

  private enSerie<T>(f: (c: InstanceType<typeof Cliente>) => T, cambia: boolean): Promise<T> {
    const p = this.cola.then(async () => {
      const c = await this.obtener();
      const r = f(c);
      if (cambia) await this.guardar(c);
      return r;
    });
    this.cola = p.catch(() => undefined);
    return p;
  }

  private async obtener(): Promise<InstanceType<typeof Cliente>> {
    if (this.cliente) return this.cliente;
    await iniciarWasm();
    const json = await readFile(this.archivo, 'utf8');
    this.cliente = Cliente.importar(json);
    return this.cliente;
  }

  /** Escritura atomica: a un .tmp y rename, para no dejar el almacen a medias. */
  private async guardar(c: InstanceType<typeof Cliente>): Promise<void> {
    const tmp = `${this.archivo}.tmp`;
    await writeFile(tmp, c.exportar(), 'utf8');
    await rename(tmp, this.archivo);
  }
}
