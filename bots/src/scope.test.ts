import { describe, expect, test } from 'vitest';
import { clasificar, Scope } from './scope.ts';

describe('clasificar', () => {
  test('hosts, ips y cidr validos', () => {
    expect(clasificar('example.com')?.clase).toBe('host');
    expect(clasificar('SUB.Example.COM')).toEqual({ clase: 'host', valor: 'sub.example.com' });
    expect(clasificar('192.0.2.5')?.clase).toBe('ip');
    expect(clasificar('10.0.0.0/24')?.clase).toBe('cidr');
  });

  test('rechaza basura y cosas peligrosas', () => {
    for (const malo of ['', 'a b', '10.0.0.0/33', '999.1.1.1', 'example.com; rm -rf /', 'host con espacio', '10.0.0.5/24']) {
      expect(clasificar(malo), malo).toBeNull();
    }
  });
});

describe('Scope', () => {
  const scope = new Scope({
    permitidos: ['example.com', '10.0.0.0/24', '192.0.2.5'],
    excluidos: ['admin.example.com', '10.0.0.1'],
  });

  test('deja pasar lo permitido', () => {
    expect(scope.evaluar('example.com').ok).toBe(true);
    expect(scope.evaluar('sub.example.com').ok).toBe(true); // subdominio
    expect(scope.evaluar('10.0.0.50').ok).toBe(true); // dentro del CIDR
    expect(scope.evaluar('10.0.0.0/28').ok).toBe(true); // CIDR mas chico, dentro
    expect(scope.evaluar('192.0.2.5').ok).toBe(true);
  });

  test('niega por defecto lo que no esta', () => {
    for (const fuera of ['otro.com', 'example.com.malo.net', '10.0.1.5', '8.8.8.8', '10.0.0.0/16']) {
      const r = scope.evaluar(fuera);
      expect(r.ok, fuera).toBe(false);
    }
  });

  test('la exclusion gana sobre el permiso', () => {
    const r = scope.evaluar('admin.example.com');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.motivo).toMatch(/exclusion/);
    expect(scope.evaluar('10.0.0.1').ok).toBe(false); // IP excluida dentro del CIDR permitido
  });

  test('un objetivo invalido no entra', () => {
    expect(scope.evaluar('no es un host').ok).toBe(false);
  });

  test('un scope vacio no deja pasar nada', () => {
    const vacio = new Scope({ permitidos: [], excluidos: [] });
    expect(vacio.vacio).toBe(true);
    expect(vacio.evaluar('example.com').ok).toBe(false);
  });
});
