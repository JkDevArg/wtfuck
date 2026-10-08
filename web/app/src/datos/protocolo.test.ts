// Pruebas de oro: TypeScript tiene que producir y leer EXACTAMENTE lo que
// produce Kotlin. Los archivos de oro/ los escribe web/interop-jvm (OroTest).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import {
  aBytes, b64, deBytes, esConClave, esHistorial, esTexto, mencionesEn, quitarRelleno, rellenar, texto, TIPO_CON_CLAVE,
} from './protocolo';

const oro = (n: string) => readFileSync(join(__dirname, 'oro', n), 'utf8').trim();

describe('Carga en JSON, igual que Kotlin', () => {
  const casos = ['hola', 'con ñ, tildes y 🙂', '', 'comillas " y \\ barra\nsalto'];
  const deKotlin: string[] = JSON.parse(oro('textos.json'));

  test.each(casos.map((c, i) => [c, i]))('texto %j sale byte a byte igual', (c, i) => {
    expect(JSON.stringify(texto(c as string))).toBe(deKotlin[i as number]);
  });

  test('ConClaveGrupo con su interior', () => {
    const c = { type: TIPO_CON_CLAVE, distribucion: 'U0tETQ==', interior: texto('a todos') };
    expect(JSON.stringify(c)).toBe(oro('con-clave.json'));
  });

  test('se leen las tres que manda la app', () => {
    const k = JSON.parse(oro('con-clave.json'));
    expect(esConClave(k)).toBe(true);
    expect(esTexto(k.interior)).toBe(true);
    const h = JSON.parse(oro('historial.json'));
    expect(esHistorial(h)).toBe(true);
    expect(h.mensajes.map((m: { id: string }) => m.id)).toEqual(['m1', 'm2']);
  });
});

describe('Relleno, igual que Relleno.kt', () => {
  const pares: [number, number][] = JSON.parse(oro('relleno.json'));
  test.each(pares)('%i bytes quedan en %i', (entra, sale) => {
    expect(rellenar(new Uint8Array(entra).fill(1)).length).toBe(sale);
  });

  test('un texto rellenado es lo mismo que en Kotlin, y se lee', () => {
    const deKotlin = oro('texto-rellenado.b64');
    expect(b64.a(aBytes(texto('hola')))).toBe(deKotlin);
    const leida = deBytes(b64.de(deKotlin));
    expect(esTexto(leida) && leida.cuerpo).toBe('hola');
  });

  test('quitar el relleno deja el JSON', () => {
    const b = new TextEncoder().encode('{"a":1}');
    expect(quitarRelleno(rellenar(b))).toEqual(b);
  });
});

test('menciones, igual que mencionesEn de Kotlin', () => {
  const t = 'Hola @Ana y @beto_2, y @ana otra vez; @no y @x_muy_largo_mas_de_veinticuatro_caracteres';
  expect(mencionesEn(t)).toEqual(JSON.parse(oro('menciones.json')));
});
