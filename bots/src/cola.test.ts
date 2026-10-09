import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { Cola } from './cola.ts';

describe('Cola de turnos', () => {
  test('el primero toma el turno; los demas hacen fila', () => {
    const cola = new Cola(0, () => {});
    expect(cola.pedir('ana')).toBe('activo');
    expect(cola.pedir('beto')).toBe(1);
    expect(cola.pedir('caro')).toBe(2);
    // El activo puede seguir pidiendo sin perder su lugar.
    expect(cola.pedir('ana')).toBe('activo');
    // Pedir dos veces no duplica en la fila.
    expect(cola.pedir('beto')).toBe(1);
    expect(cola.estado()).toEqual({ activo: 'ana', espera: ['beto', 'caro'] });
  });

  test('al soltar, el turno pasa al siguiente y se avisa', () => {
    const avisos: (string | null)[] = [];
    const cola = new Cola(0, (s) => avisos.push(s));
    cola.pedir('ana');
    cola.pedir('beto');
    cola.pedir('caro');
    cola.liberar('ana');
    expect(cola.estado()).toEqual({ activo: 'beto', espera: ['caro'] });
    expect(avisos).toEqual(['beto']);
    cola.liberar('beto');
    cola.liberar('caro');
    expect(cola.estado()).toEqual({ activo: null, espera: [] });
    expect(avisos).toEqual(['beto', 'caro', null]);
  });

  test('soltar a quien solo espera lo saca de la fila sin tocar el turno', () => {
    const cola = new Cola(0, () => {});
    cola.pedir('ana');
    cola.pedir('beto');
    cola.pedir('caro');
    cola.liberar('caro');
    expect(cola.estado()).toEqual({ activo: 'ana', espera: ['beto'] });
  });

  describe('inactividad', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    test('un turno ocioso se suelta solo y pasa al siguiente', () => {
      const avisos: (string | null)[] = [];
      const cola = new Cola(1000, (s) => avisos.push(s));
      cola.pedir('ana');
      cola.pedir('beto');
      vi.advanceTimersByTime(999);
      expect(cola.estado().activo).toBe('ana');
      vi.advanceTimersByTime(2);
      expect(cola.estado().activo).toBe('beto');
      expect(avisos).toEqual(['beto']);
    });

    test('tocar reinicia el reloj de inactividad', () => {
      const cola = new Cola(1000, () => {});
      cola.pedir('ana');
      cola.pedir('beto');
      vi.advanceTimersByTime(800);
      cola.tocar('ana');
      vi.advanceTimersByTime(800);
      expect(cola.estado().activo).toBe('ana'); // no se vencio: se reinicio a los 800
      vi.advanceTimersByTime(300);
      expect(cola.estado().activo).toBe('beto');
    });
  });
});
