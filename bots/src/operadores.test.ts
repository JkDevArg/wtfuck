import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
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

const ADMINS = ['jefe'];

function ops(invitados = ['ana'], abierto = false) {
  return new Operadores(archivo, abierto, invitados, ADMINS);
}

describe('invitacion', () => {
  test('por defecto solo entran invitados y admins', () => {
    const o = ops(['ana']);
    expect(o.puedeEntrar('ana')).toBe(true);
    expect(o.puedeEntrar('ANA')).toBe(true);
    expect(o.puedeEntrar('jefe')).toBe(true);
    expect(o.puedeEntrar('beto')).toBe(false);
  });
  test('en modo abierto entra cualquiera', () => {
    expect(ops([], true).puedeEntrar('quiensea')).toBe(true);
  });
  test('esAdmin', () => {
    expect(ops().esAdmin('jefe')).toBe(true);
    expect(ops().esAdmin('ana')).toBe(false);
  });
});

describe('terminos', () => {
  test('aceptar marca aceptado', () => {
    const o = ops();
    expect(o.aceptado('ana')).toBe(false);
    o.aceptar('ana');
    expect(o.aceptado('ana')).toBe(true);
  });

  test('la aceptacion sobrevive a reiniciar', () => {
    ops().aceptar('ana');
    expect(ops().aceptado('ana')).toBe(true);
  });

  test('lee el formato viejo (con alcance) ignorando el alcance', () => {
    // El JSON viejo tenia {aceptoEn, alcance:[...]}; ahora solo importa aceptoEn.
    writeFileSync(archivo, JSON.stringify({ ana: { aceptoEn: '2026-01-01T00:00:00Z', alcance: ['x.com'] } }));
    expect(ops().aceptado('ana')).toBe(true);
  });
});
