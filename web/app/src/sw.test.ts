// El service worker (public/sw.js) corrido en una caja con un `self` de mentira.
// Lo que importa: que el aviso diga SIEMPRE lo mismo -no hay nada que decir,
// el push llega vacío- y que no moleste si la web ya está a la vista.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { runInNewContext } from 'node:vm';
import { expect, test } from 'vitest';

const codigo = readFileSync(join(__dirname, '..', 'public', 'sw.js'), 'utf8');

function cargar(ventanas: { url: string; visibilityState: string; focused: boolean; focus?: () => void }[]) {
  const oyentes: Record<string, (e: unknown) => void> = {};
  const avisos: { titulo: string; opciones: Record<string, unknown> }[] = [];
  const abiertas: string[] = [];
  const self = {
    addEventListener: (t: string, f: (e: unknown) => void) => { oyentes[t] = f; },
    skipWaiting: () => {},
    clients: {
      claim: async () => {},
      matchAll: async () => ventanas,
      openWindow: async (u: string) => { abiertas.push(u); },
    },
    registration: {
      scope: 'https://ejemplo.test/web/',
      showNotification: async (titulo: string, opciones: Record<string, unknown>) => { avisos.push({ titulo, opciones }); },
    },
  };
  runInNewContext(codigo, { self });
  async function disparar(tipo: string, extra: Record<string, unknown> = {}) {
    let espera: Promise<unknown> = Promise.resolve();
    oyentes[tipo]({ ...extra, waitUntil: (p: Promise<unknown>) => { espera = p; } });
    await espera;
  }
  return { disparar, avisos, abiertas, oyentes };
}

test('no intercepta peticiones: la página siempre viene del servidor', () => {
  const { oyentes } = cargar([]);
  expect(Object.keys(oyentes).sort()).toEqual(['activate', 'install', 'notificationclick', 'push']);
});

test('con el navegador cerrado, un aviso fijo que no dice nada', async () => {
  const { disparar, avisos } = cargar([]);
  await disparar('push', { data: null });
  expect(avisos).toEqual([{ titulo: 'wtfuck', opciones: { body: 'Tienes algo nuevo', tag: 'wtfuck', renotify: false } }]);
});

test('con la web a la vista, no avisa: avisa la página', async () => {
  const { disparar, avisos } = cargar([{ url: 'https://ejemplo.test/web/', visibilityState: 'visible', focused: true }]);
  await disparar('push');
  expect(avisos).toEqual([]);
});

test('abierta pero en otra pestaña, avisa igual', async () => {
  const { disparar, avisos } = cargar([{ url: 'https://ejemplo.test/web/', visibilityState: 'hidden', focused: false }]);
  await disparar('push');
  expect(avisos.length).toBe(1);
});

test('al tocarlo enfoca la web si está abierta, o la abre', async () => {
  let enfocada = false;
  const a = cargar([{ url: 'https://ejemplo.test/web/', visibilityState: 'hidden', focused: false, focus: () => { enfocada = true; } }]);
  await a.disparar('notificationclick', { notification: { close: () => {} } });
  expect(enfocada).toBe(true);
  expect(a.abiertas).toEqual([]);

  const b = cargar([{ url: 'https://otro.test/', visibilityState: 'visible', focused: true }]);
  await b.disparar('notificationclick', { notification: { close: () => {} } });
  expect(b.abiertas).toEqual(['https://ejemplo.test/web/']);
});
