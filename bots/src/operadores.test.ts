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

const EXCL = ['169.254.0.0/16', '127.0.0.0/8'];
const ADMINS = ['jefe'];

function ops(invitados = ['ana'], abierto = false) {
  return new Operadores(archivo, EXCL, abierto, invitados, ADMINS);
}

describe('invitacion', () => {
  test('por defecto solo entran invitados y admins', () => {
    const o = ops(['ana']);
    expect(o.puedeEntrar('ana')).toBe(true);
    expect(o.puedeEntrar('jefe')).toBe(true); // admin siempre entra
    expect(o.puedeEntrar('beto')).toBe(false);
  });
  test('en modo abierto entra cualquiera', () => {
    expect(ops([], true).puedeEntrar('quiensea')).toBe(true);
  });
});

describe('pedir alcance queda pendiente hasta que el admin aprueba', () => {
  test('pedir no habilita: queda pendiente', () => {
    const o = ops();
    o.aceptar('ana');
    const r = o.pedirAlcance('ana', 'example.com');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.estado).toBe('pendiente');
    // todavía NO puede escanear
    const p = o.permitido('ana', 'example.com');
    expect(p.ok).toBe(false);
    if (!p.ok) expect(p.motivo).toMatch(/pendiente/);
  });

  test('tras aprobar, ya puede escanear ese objetivo y subdominios', () => {
    const o = ops();
    o.aceptar('ana');
    o.pedirAlcance('ana', 'example.com');
    const a = o.aprobar('jefe', 'ana', 'example.com');
    expect(a.ok).toBe(true);
    expect(o.permitido('ana', 'example.com').ok).toBe(true);
    expect(o.permitido('ana', 'sub.example.com').ok).toBe(true);
    expect(o.permitido('ana', 'otro.com').ok).toBe(false);
  });

  test('un no-admin no puede aprobar', () => {
    const o = ops(['ana', 'beto']);
    o.aceptar('ana');
    o.pedirAlcance('ana', 'example.com');
    const r = o.aprobar('beto', 'ana', 'example.com');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/no sos admin/);
  });

  test('el admin no puede auto-aprobarse', () => {
    const o = ops(['jefe']);
    o.aceptar('jefe');
    o.pedirAlcance('jefe', 'example.com');
    const r = o.aprobar('jefe', 'jefe', 'example.com');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/propios/);
  });

  test('aprobar algo que no se pidió falla', () => {
    const o = ops();
    o.aceptar('ana');
    const r = o.aprobar('jefe', 'ana', 'nada.com');
    expect(r.ok).toBe(false);
  });
});

describe('cola de pendientes para el admin', () => {
  test('lista los pendientes de todos', () => {
    const o = ops(['ana', 'beto']);
    o.aceptar('ana');
    o.aceptar('beto');
    o.pedirAlcance('ana', 'a.com');
    o.pedirAlcance('beto', 'b.com');
    o.aprobar('jefe', 'ana', 'a.com');
    const pend = o.pendientes();
    expect(pend.map((p) => p.objetivo)).toEqual(['b.com']); // a.com ya aprobado
  });

  test('rechazar saca el pedido', () => {
    const o = ops();
    o.aceptar('ana');
    o.pedirAlcance('ana', 'a.com');
    expect(o.rechazar('jefe', 'ana', 'a.com').ok).toBe(true);
    expect(o.pendientes()).toHaveLength(0);
  });
});

describe('exclusiones del admin', () => {
  test('no se puede ni pedir un destino excluido', () => {
    const o = ops();
    o.aceptar('ana');
    const r = o.pedirAlcance('ana', '169.254.169.254');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/exclu/);
  });

  test('un objetivo con forma invalida no se pide', () => {
    const o = ops();
    o.aceptar('ana');
    expect(o.pedirAlcance('ana', 'esto no es un host').ok).toBe(false);
  });
});

describe('terminos', () => {
  test('hay que aceptar antes de escanear', () => {
    const o = ops();
    const r = o.permitido('ana', 'example.com');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/aceptar/);
  });

  test('el alcance es por operador, no se comparte', () => {
    const o = ops(['ana', 'beto']);
    o.aceptar('ana');
    o.pedirAlcance('ana', 'example.com');
    o.aprobar('jefe', 'ana', 'example.com');
    o.aceptar('beto');
    expect(o.permitido('beto', 'example.com').ok).toBe(false);
  });
});

test('aceptacion, pedido y aprobacion sobreviven a reiniciar', () => {
  const a = ops();
  a.aceptar('ana');
  a.pedirAlcance('ana', 'example.com');
  a.aprobar('jefe', 'ana', 'example.com');
  const b = ops();
  expect(b.aceptado('ana')).toBe(true);
  expect(b.permitido('ana', 'example.com').ok).toBe(true);
  expect(b.aprobados('ana')).toContain('example.com');
});

test('migra el formato viejo (alcance: string[]) como aprobado', () => {
  // Escribe a mano el formato anterior.
  writeFileSync(archivo, JSON.stringify({ ana: { aceptoEn: '2026-01-01T00:00:00Z', alcance: ['viejo.com'] } }));
  const o = ops();
  expect(o.permitido('ana', 'viejo.com').ok).toBe(true);
  expect(o.aprobados('ana')).toContain('viejo.com');
});
