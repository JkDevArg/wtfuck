import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { Operadores } from './operadores.ts';

let dir: string;
let archivo: string;
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'ops-'));
  archivo = join(dir, 'operadores.json');
});
afterEach(() => rmSync(dir, { recursive: true, force: true }));

const EXCL = ['169.254.0.0/16', '127.0.0.0/8'];

describe('invitacion', () => {
  test('por defecto solo entran los invitados', () => {
    const ops = new Operadores(archivo, EXCL, false, ['ana']);
    expect(ops.puedeEntrar('ana')).toBe(true);
    expect(ops.puedeEntrar('ANA')).toBe(true);
    expect(ops.puedeEntrar('beto')).toBe(false);
  });
  test('en modo abierto entra cualquiera', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    expect(ops.puedeEntrar('quiensea')).toBe(true);
  });
});

describe('terminos y alcance', () => {
  test('hay que aceptar antes de escanear', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    const r = ops.permitido('ana', 'example.com');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/aceptar/);
  });

  test('aceptar + declarar alcance habilita ese objetivo y sus subdominios', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    ops.aceptar('ana');
    expect(ops.agregarAlcance('ana', 'example.com').ok).toBe(true);
    expect(ops.permitido('ana', 'example.com').ok).toBe(true);
    expect(ops.permitido('ana', 'sub.example.com').ok).toBe(true);
    // algo que no declaro, no:
    expect(ops.permitido('ana', 'otro.com').ok).toBe(false);
  });

  test('el alcance es por operador, no se comparte', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    ops.aceptar('ana');
    ops.agregarAlcance('ana', 'example.com');
    ops.aceptar('beto');
    expect(ops.permitido('beto', 'example.com').ok).toBe(false); // beto no lo declaro
  });

  test('no se puede declarar un destino excluido por el admin', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    ops.aceptar('ana');
    const r = ops.agregarAlcance('ana', '169.254.169.254'); // metadatos de nube
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/exclu/);
    expect(ops.permitido('ana', '169.254.169.254').ok).toBe(false);
  });

  test('un objetivo con forma invalida no se declara', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    ops.aceptar('ana');
    expect(ops.agregarAlcance('ana', 'esto no es un host').ok).toBe(false);
  });

  test('quitar saca el objetivo', () => {
    const ops = new Operadores(archivo, EXCL, true, []);
    ops.aceptar('ana');
    ops.agregarAlcance('ana', '10.0.0.0/24');
    expect(ops.permitido('ana', '10.0.0.5').ok).toBe(true);
    expect(ops.quitarAlcance('ana', '10.0.0.0/24')).toBe(true);
    expect(ops.permitido('ana', '10.0.0.5').ok).toBe(false);
  });
});

test('la aceptacion y el alcance sobreviven a reiniciar', () => {
  const a = new Operadores(archivo, EXCL, true, []);
  a.aceptar('ana');
  a.agregarAlcance('ana', 'example.com');
  // otra instancia leyendo el mismo archivo
  const b = new Operadores(archivo, EXCL, true, []);
  expect(b.aceptado('ana')).toBe(true);
  expect(b.permitido('ana', 'example.com').ok).toBe(true);
});
