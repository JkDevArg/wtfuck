import { describe, expect, test } from 'vitest';
import { Alcance, type ClienteAlcance } from './alcance.ts';

const EXCL = ['169.254.0.0/16', '127.0.0.0/8'];

// Cliente falso: guarda pedidos y una lista de aprobados controlable.
function clienteFalso(aprobados: string[] = []): ClienteAlcance & { pedidos: Array<[string, string]>; llamadas: number } {
  return {
    pedidos: [],
    llamadas: 0,
    async pedirAlcance(operador, objetivo) {
      this.pedidos.push([operador, objetivo]);
      return 'pendiente';
    },
    async alcanceAprobados() {
      this.llamadas++;
      return aprobados;
    },
  };
}

describe('pedir', () => {
  test('registra un objetivo válido como pendiente', async () => {
    const cli = clienteFalso();
    const a = new Alcance(cli, EXCL);
    const r = await a.pedir('ana', 'example.com');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.estado).toBe('pendiente');
    expect(cli.pedidos).toEqual([['ana', 'example.com']]);
  });

  test('no deja pedir un excluido (ni llega al server)', async () => {
    const cli = clienteFalso();
    const r = await new Alcance(cli, EXCL).pedir('ana', '169.254.169.254');
    expect(r.ok).toBe(false);
    expect(cli.pedidos).toHaveLength(0);
  });

  test('rechaza una forma inválida', async () => {
    const cli = clienteFalso();
    const r = await new Alcance(cli, EXCL).pedir('ana', 'esto no es host');
    expect(r.ok).toBe(false);
    expect(cli.pedidos).toHaveLength(0);
  });
});

describe('permitido', () => {
  test('aprobado en el server pasa (y cubre subdominios)', async () => {
    const a = new Alcance(clienteFalso(['example.com']), EXCL);
    expect((await a.permitido('ana', 'example.com')).ok).toBe(true);
    expect((await a.permitido('ana', 'sub.example.com')).ok).toBe(true);
  });

  test('lo no aprobado se rechaza', async () => {
    const a = new Alcance(clienteFalso(['example.com']), EXCL);
    const r = await a.permitido('ana', 'otro.com');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/alcance aprobado/);
  });

  test('un excluido se rechaza aunque figure como aprobado', async () => {
    const a = new Alcance(clienteFalso(['169.254.169.254']), EXCL);
    const r = await a.permitido('ana', '169.254.169.254');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/exclu/);
  });
});

describe('cache', () => {
  test('cachea la lista de aprobados dentro del TTL', async () => {
    const cli = clienteFalso(['example.com']);
    const a = new Alcance(cli, EXCL, 10_000);
    await a.permitido('ana', 'example.com');
    await a.permitido('ana', 'example.com');
    expect(cli.llamadas).toBe(1); // la segunda salió de cache
  });

  test('pedir invalida la cache de ese operador', async () => {
    const cli = clienteFalso(['example.com']);
    const a = new Alcance(cli, EXCL, 10_000);
    await a.aprobados('ana'); // llena cache (1)
    await a.pedir('ana', 'nuevo.com'); // invalida
    await a.aprobados('ana'); // vuelve a consultar (2)
    expect(cli.llamadas).toBe(2);
  });
});
