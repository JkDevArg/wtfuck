import { describe, expect, test } from 'vitest';
import { Agente, type EventoAgente } from './agente.ts';
import type { IA, Turno } from './ia.ts';
import type { ResultadoComando } from './sandbox.ts';

const LIM = { maxComandos: 5, sesionTimeoutMs: 60_000 };

// IA que responde una secuencia de textos (uno por llamada).
function iaSecuencia(respuestas: string[]): { ia: IA; vistos: () => Turno[][] } {
  const vistos: Turno[][] = [];
  let i = 0;
  return {
    ia: {
      async responder(turnos) {
        vistos.push(turnos);
        return respuestas[Math.min(i++, respuestas.length - 1)]!;
      },
    },
    vistos: () => vistos,
  };
}

function ejecutorFalso(salida = 'ok'): (c: string) => Promise<ResultadoComando> {
  return async () => ({ salida, codigo: 0, recortado: false, vencio: false });
}

test('corre los comandos que decide la IA y termina con el resumen', async () => {
  const { ia } = iaSecuencia([
    '{"accion":"comando","comando":"nmap -F objetivo"}',
    '{"accion":"comando","comando":"whatweb objetivo"}',
    '{"accion":"terminado","resumen":"Dos puertos abiertos."}',
  ]);
  const eventos: EventoAgente[] = [];
  const r = await new Agente(ia).correr('example.com', ejecutorFalso(), LIM, (e) => eventos.push(e));
  expect(r.motivo).toBe('terminado');
  expect(r.resumen).toMatch(/Dos puertos/);
  expect(r.pasos.map((p) => p.comando)).toEqual(['nmap -F objetivo', 'whatweb objetivo']);
  // Emite comando+salida por cada paso y un fin.
  expect(eventos.filter((e) => e.tipo === 'comando')).toHaveLength(2);
  expect(eventos.at(-1)).toMatchObject({ tipo: 'fin', motivo: 'terminado' });
});

test('respeta el tope de comandos si la IA nunca termina', async () => {
  const { ia } = iaSecuencia(['{"accion":"comando","comando":"echo loop"}']); // siempre lo mismo
  const r = await new Agente(ia).correr('example.com', ejecutorFalso(), { maxComandos: 3, sesionTimeoutMs: 60_000 });
  expect(r.motivo).toBe('tope-comandos');
  expect(r.pasos).toHaveLength(3);
});

test('la salida de un comando se le reinyecta a la IA como datos', async () => {
  const { ia, vistos } = iaSecuencia([
    '{"accion":"comando","comando":"id"}',
    '{"accion":"terminado","resumen":"ok"}',
  ]);
  await new Agente(ia).correr('example.com', ejecutorFalso('uid=0(root)'), LIM);
  // En la 2da llamada, el historial debe incluir la salida rotulada como datos.
  const segunda = vistos()[1]!;
  const hayDatos = segunda.some((t) => t.rol === 'user' && t.texto.includes('uid=0(root)') && /no confiables/.test(t.texto));
  expect(hayDatos).toBe(true);
});

test('una respuesta sin JSON cierra la sesión, no inventa comando', async () => {
  const { ia } = iaSecuencia(['no sé qué hacer']);
  const r = await new Agente(ia).correr('example.com', ejecutorFalso(), LIM);
  expect(r.motivo).toBe('terminado');
  expect(r.pasos).toHaveLength(0);
});

test('si la IA falla, termina con error sin tirar', async () => {
  const ia: IA = {
    async responder() {
      throw new Error('sin conexion');
    },
  };
  const r = await new Agente(ia).correr('example.com', ejecutorFalso(), LIM);
  expect(r.motivo).toBe('error');
  expect(r.resumen).toMatch(/sin conexion/);
});

test('si el comando falla al ejecutar, termina con error', async () => {
  const { ia } = iaSecuencia(['{"accion":"comando","comando":"x"}']);
  const ejecutar = async () => {
    throw new Error('contenedor caído');
  };
  const r = await new Agente(ia).correr('example.com', ejecutar, LIM);
  expect(r.motivo).toBe('error');
});
