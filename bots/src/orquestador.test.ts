import { describe, expect, test } from 'vitest';
import { Orquestador, type Plan } from './orquestador.ts';
import type { IA, Turno } from './ia.ts';
import type { Herramienta } from './herramientas.ts';

// Una herramienta de mentira con los mismos perfiles que nmap.
const nmapFalso: Herramienta = {
  nombre: 'nmap',
  perfiles: ['rapido', 'normal', 'servicios', 'completo'],
  async correr() {
    return { ok: true, salida: 'ok' };
  },
};

// Una IA que devuelve lo que le digamos (y recuerda lo que le mandaron).
function iaQueDice(respuesta: string): { ia: IA; ultimo: () => Turno[] } {
  let ultimo: Turno[] = [];
  return {
    ia: {
      async responder(turnos: Turno[]) {
        ultimo = turnos;
        return respuesta;
      },
    },
    ultimo: () => ultimo,
  };
}

function orq(respuesta: string, extra?: string) {
  const { ia, ultimo } = iaQueDice(respuesta);
  return { o: new Orquestador(ia, [nmapFalso], extra), ultimo };
}

describe('decisión de ejecutar', () => {
  test('JSON limpio de ejecución se interpreta', async () => {
    const { o } = orq('{"accion":"ejecutar","herramienta":"nmap","objetivo":"scanme.nmap.org","perfil":"servicios"}');
    const p = await o.decidir('qué servicios corre scanme', { alcance: ['scanme.nmap.org'] });
    expect(p.accion).toBe('ejecutar');
    if (p.accion === 'ejecutar') {
      expect(p.herramienta).toBe('nmap');
      expect(p.objetivo).toBe('scanme.nmap.org');
      expect(p.perfil).toBe('servicios');
    }
  });

  test('tolera markdown y texto alrededor del JSON', async () => {
    const { o } = orq('Claro, voy con esto:\n```json\n{"accion":"ejecutar","herramienta":"nmap","objetivo":"ejemplo.com","perfil":"rapido"}\n```');
    const p = await o.decidir('mirá ejemplo.com', { alcance: [] });
    expect(p.accion).toBe('ejecutar');
    if (p.accion === 'ejecutar') expect(p.objetivo).toBe('ejemplo.com');
  });

  test('un perfil inventado cae a uno real del registro', async () => {
    const { o } = orq('{"accion":"ejecutar","herramienta":"nmap","objetivo":"ejemplo.com","perfil":"ultra-mega"}');
    const p = await o.decidir('x', { alcance: [] });
    expect(p.accion).toBe('ejecutar');
    if (p.accion === 'ejecutar') expect(nmapFalso.perfiles).toContain(p.perfil);
  });

  test('una herramienta que no existe NO se ejecuta, se responde', async () => {
    const { o } = orq('{"accion":"ejecutar","herramienta":"metasploit","objetivo":"ejemplo.com","perfil":"normal"}');
    const p = await o.decidir('explotá ejemplo.com', { alcance: ['ejemplo.com'] });
    expect(p.accion).toBe('responder');
  });

  test('un objetivo con forma inválida no dispara ejecución', async () => {
    const { o } = orq('{"accion":"ejecutar","herramienta":"nmap","objetivo":"esto no es un host","perfil":"normal"}');
    const p = await o.decidir('x', { alcance: [] });
    expect(p.accion).toBe('responder');
  });
});

describe('robustez: nunca rompe, siempre da un Plan', () => {
  test('respuesta sin JSON se trata como texto', async () => {
    const { o } = orq('No me queda claro contra qué querés correr el escaneo.');
    const p = await o.decidir('hola', { alcance: [] });
    expect(p).toEqual<Plan>({ accion: 'responder', texto: 'No me queda claro contra qué querés correr el escaneo.' });
  });

  test('JSON con forma rara NO inventa una ejecución', async () => {
    const { o } = orq('{"foo":"bar"}');
    const p = await o.decidir('hola', { alcance: [] });
    expect(p.accion).toBe('responder');
  });

  test('si la IA falla, responde el error sin tirar', async () => {
    const ia: IA = {
      async responder() {
        throw new Error('sin conexion');
      },
    };
    const o = new Orquestador(ia, [nmapFalso]);
    const p = await o.decidir('hola', { alcance: [] });
    expect(p.accion).toBe('responder');
    if (p.accion === 'responder') expect(p.texto).toMatch(/sin conexion/);
  });
});

test('el prompt le pasa el menú y el alcance declarado', async () => {
  const { o, ultimo } = orq('{"accion":"responder","texto":"ok"}');
  await o.decidir('probá scanme', { alcance: ['scanme.nmap.org'] });
  const system = ultimo()[0]!.texto;
  const user = ultimo()[1]!.texto;
  expect(system).toContain('nmap — perfiles: rapido, normal, servicios, completo');
  expect(user).toContain('scanme.nmap.org'); // el alcance declarado entra en contexto
});
