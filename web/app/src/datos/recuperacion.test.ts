// El código de recuperación contra lo que escribe Kotlin (OroTest).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';
import { claveDeIdentidad, generar, normalizar, verificadorServidor } from './recuperacion';

const oro = JSON.parse(readFileSync(join(__dirname, 'oro', 'recuperacion.json'), 'utf8'));
const b64 = (b: Uint8Array) => Buffer.from(b).toString('base64');

test('normaliza y deriva igual que Kotlin', async () => {
  for (const c of oro.codigos) {
    const n = await normalizar(c.codigo);
    expect(n).toBe(c.normalizado);
    expect(b64(await verificadorServidor(n!))).toBe(c.verificador);
    expect(b64(await claveDeIdentidad(n!))).toBe(c.identidad);
  }
});

test('las mismas variantes valen o no valen que en Kotlin', async () => {
  for (const [escrito, esperado] of oro.variantes) expect(await normalizar(escrito)).toBe(esperado);
});

test('un código nuevo de la web es válido, y es válido según la regla de Kotlin', async () => {
  for (let i = 0; i < 50; i++) {
    const c = await generar();
    expect(c).toMatch(/^([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{4}$/);
    expect(await normalizar(c)).toBe(c.replaceAll('-', ''));
  }
});
