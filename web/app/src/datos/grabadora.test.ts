import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';
import { codificarOnda, decodificarOnda, mimeParaReproducir } from './grabadora';

const oro = JSON.parse(readFileSync(join(__dirname, 'oro', 'onda.json'), 'utf8'));

test('la onda sale igual que Onda.codificar de Kotlin', () => {
  expect(codificarOnda(oro.muestras)).toBe(oro.onda);
  expect(codificarOnda([0.2, 0.9, 0.5])).toBe(oro.pocas);
  expect(codificarOnda([])).toBe(oro.vacia);
});

test('se lee de vuelta, y una onda rota no', () => {
  expect(decodificarOnda(oro.onda)?.length).toBe(40);
  expect(decodificarOnda('xyz')).toBeNull();
  expect(decodificarOnda('!'.repeat(40))).toBeNull();
});

test('una nota de la app (sin tipo) se reproduce como audio/mp4', () => {
  expect(mimeParaReproducir('application/octet-stream', 'voz-1730000000000.m4a')).toBe('audio/mp4');
  expect(mimeParaReproducir('audio/mp4', 'x')).toBe('audio/mp4');
});
