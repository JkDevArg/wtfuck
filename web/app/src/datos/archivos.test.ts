import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';
import { ArchivoAlterado, cifrarArchivo, claseDe, descifrarArchivo, nombreSeguro } from './archivos';

const oro = JSON.parse(readFileSync(join(__dirname, 'oro', 'archivo.json'), 'utf8'));

test('abre un archivo que cifró la JVM igual que CifradorArchivo', async () => {
  const cifrado = Uint8Array.from(Buffer.from(oro.cifrado, 'base64'));
  const claro = await descifrarArchivo(cifrado.buffer, oro.clave, oro.nonce);
  expect(new TextDecoder().decode(claro)).toBe(oro.claro);
});

test('ida y vuelta, con 16 bytes de etiqueta', async () => {
  const claro = new TextEncoder().encode('una foto de mentira').buffer;
  const c = await cifrarArchivo(claro);
  expect(c.cifrado.byteLength).toBe(claro.byteLength + 16);
  expect(new TextDecoder().decode(await descifrarArchivo(c.cifrado, c.clave, c.nonce))).toBe('una foto de mentira');
});

test('un byte cambiado no se abre: pudo ser alterado', async () => {
  const c = await cifrarArchivo(new TextEncoder().encode('intacto').buffer);
  const b = new Uint8Array(c.cifrado);
  b[0] ^= 1;
  await expect(descifrarArchivo(b.buffer, c.clave, c.nonce)).rejects.toBeInstanceOf(ArchivoAlterado);
});

test('el nombre de otro, sin trucos de dirección ni saltos', () => {
  expect(nombreSeguro('foto‮gpj.exe')).toBe('fotogpj.exe');
  expect(nombreSeguro('a\nb')).toBe('ab');
  expect(nombreSeguro('')).toBe('archivo');
  expect(nombreSeguro('x'.repeat(300)).length).toBe(120);
});

test('la clase sale del tipo', () => {
  expect(claseDe('image/png')).toBe('imagen');
  expect(claseDe('video/mp4')).toBe('video');
  expect(claseDe('audio/ogg')).toBe('audio');
  expect(claseDe('application/pdf')).toBe('documento');
});
