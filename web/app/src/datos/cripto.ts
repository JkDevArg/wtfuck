// libsignal en el navegador: el WebAssembly de web/cripto, con el almacén
// guardado cifrado en la bóveda después de cada operación que lo cambia.
//
// Una sola instancia y en serie: el Double Ratchet es estado que cambia, y dos
// operaciones a la vez sobre la misma sesión la corromperían. Es el mismo
// motivo del candado de `CifradorSignal` en la app.
import init, { Cliente } from '../../../cripto/pkg/wtfuck_cripto.js';
import wasmUrl from '../../../cripto/pkg/wtfuck_cripto_bg.wasm?url';
import { guardar, leer } from './boveda';

let listo: Promise<unknown> | null = null;
let cliente: Cliente | null = null;
let cola: Promise<unknown> = Promise.resolve();

async function iniciarWasm(): Promise<void> {
  listo ??= init({ module_or_path: wasmUrl });
  await listo;
}

/** Corre `f` después de la anterior, y guarda el almacén si `cambia`. */
function enSerie<T>(f: (c: Cliente) => T, cambia: boolean): Promise<T> {
  const p = cola.then(async () => {
    const c = await obtener();
    const r = f(c);
    if (cambia) await guardar('cuenta', 'almacen', c.exportar());
    return r;
  });
  cola = p.catch(() => undefined);
  return p;
}

async function obtener(): Promise<Cliente> {
  if (cliente) return cliente;
  await iniciarWasm();
  const guardado = await leer<string>('cuenta', 'almacen');
  if (!guardado) throw new Error('Este navegador todavía no tiene claves.');
  cliente = Cliente.importar(guardado);
  return cliente;
}

/** Un aparato nuevo: identidad al azar. Pisa lo que hubiera. */
export async function crearIdentidad(): Promise<{ identidad: string; registrationId: number }> {
  await iniciarWasm();
  cliente = new Cliente();
  await guardar('cuenta', 'almacen', cliente.exportar());
  return { identidad: cliente.identidad(), registrationId: cliente.registrationId() };
}

export const tieneIdentidad = async () => (await leer<string>('cuenta', 'almacen')) !== undefined;

export interface ClavesPublicadas {
  registrationId: number;
  identidad: string;
  firmada: { keyId: number; publica: string; firma: string };
  kyber: { keyId: number; publica: string; firma: string };
  unicas: { keyId: number; publica: string }[];
}

export const generarClaves = (firmadaId: number, kyberId: number, desde: number, cuantas: number) =>
  enSerie((c) => JSON.parse(c.generarClaves(firmadaId, kyberId, desde, cuantas, Date.now())) as ClavesPublicadas, true);

export const unicasRestantes = () => enSerie((c) => c.unicasRestantes(), false);

export const tieneSesion = (dispositivo: string) => enSerie((c) => c.tieneSesion(dispositivo), false);

export const abrirSesion = (dispositivo: string, paquete: unknown) =>
  enSerie((c) => c.abrirSesion(dispositivo, JSON.stringify(paquete), Date.now()), true);

export const cifrar = (dispositivo: string, claro: Uint8Array) =>
  enSerie((c) => JSON.parse(c.cifrar(dispositivo, claro, Date.now())) as { tipo: number; cuerpo: string }, true);

export const descifrar = (dispositivo: string, tipo: number, cuerpo: string) =>
  enSerie((c) => c.descifrar(dispositivo, tipo, cuerpo), true);

export const crearDistribucion = (miDispositivo: string, distId: string) =>
  enSerie((c) => c.crearDistribucion(miDispositivo, distId), true);

export const procesarDistribucion = (remitente: string, skdm: string) =>
  enSerie((c) => c.procesarDistribucion(remitente, skdm), true);

export const cifrarGrupo = (miDispositivo: string, distId: string, claro: Uint8Array) =>
  enSerie((c) => c.cifrarGrupo(miDispositivo, distId, claro), true);

export const descifrarGrupo = (remitente: string, cuerpo: string) =>
  enSerie((c) => c.descifrarGrupo(remitente, cuerpo), true);

export const huella = (miUsuario: string, otroUsuario: string, otroDispositivo: string) =>
  enSerie((c) => JSON.parse(c.huella(miUsuario, otroUsuario, otroDispositivo)) as { digitos: string; escaneable: string }, false);

export const identidadCambio = (dispositivo: string) => enSerie((c) => c.identidadCambio(dispositivo), false);

export const identidadVista = (dispositivo: string) => enSerie((c) => c.identidadVista(dispositivo), true);

/** Vuelve a leer el almacén de la bóveda: otra pestaña pudo cambiarlo. */
export function recargar(): void {
  cliente = null;
}

/** Para olvidar todo al cerrar sesión. */
export function olvidar(): void {
  cliente = null;
}
