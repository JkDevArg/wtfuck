import { afterEach, expect, test, vi } from 'vitest';
import { Groq, type Turno } from './ia.ts';

afterEach(() => vi.restoreAllMocks());

const turnos: Turno[] = [
  { rol: 'system', texto: 'sos un asistente' },
  { rol: 'user', texto: 'hola' },
];

function responderCon(status: number, cuerpo: unknown) {
  return vi.fn(async (_url: string | URL, _opts?: RequestInit) =>
    new Response(typeof cuerpo === 'string' ? cuerpo : JSON.stringify(cuerpo), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

test('arma la peticion como la espera Groq y lee la respuesta', async () => {
  const fetchMock = responderCon(200, { choices: [{ message: { content: '  hola, en que ayudo  ' } }] });
  vi.stubGlobal('fetch', fetchMock);

  const groq = new Groq({ apiKey: 'k-secreta', modelo: 'openai/gpt-oss-20b' });
  const r = await groq.responder(turnos);
  expect(r).toBe('hola, en que ayudo'); // recortado

  const [url, opts] = fetchMock.mock.calls[0]!;
  expect(url).toBe('https://api.groq.com/openai/v1/chat/completions');
  expect(opts!.method).toBe('POST');
  const headers = opts!.headers as Record<string, string>;
  expect(headers['Authorization']).toBe('Bearer k-secreta');
  const body = JSON.parse(opts!.body as string);
  expect(body.model).toBe('openai/gpt-oss-20b');
  expect(body.messages).toEqual([
    { role: 'system', content: 'sos un asistente' },
    { role: 'user', content: 'hola' },
  ]);
  expect(typeof body.max_tokens).toBe('number');
});

test('sin API key no se construye', () => {
  expect(() => new Groq({ apiKey: '', modelo: 'x' })).toThrow(/GROQ_API_KEY/);
});

test('429 da un mensaje de saturacion', async () => {
  vi.stubGlobal('fetch', responderCon(429, { error: 'rate limit' }));
  const groq = new Groq({ apiKey: 'k', modelo: 'x' });
  await expect(groq.responder(turnos)).rejects.toThrow(/saturada/i);
});

test('otro error trae el codigo', async () => {
  vi.stubGlobal('fetch', responderCon(401, 'no autorizado'));
  const groq = new Groq({ apiKey: 'k', modelo: 'x' });
  await expect(groq.responder(turnos)).rejects.toThrow(/401/);
});

test('una respuesta sin texto es un error', async () => {
  vi.stubGlobal('fetch', responderCon(200, { choices: [{ message: {} }] }));
  const groq = new Groq({ apiKey: 'k', modelo: 'x' });
  await expect(groq.responder(turnos)).rejects.toThrow(/no devolvio/i);
});

test('base configurable (otro proveedor compatible)', async () => {
  const fetchMock = responderCon(200, { choices: [{ message: { content: 'ok' } }] });
  vi.stubGlobal('fetch', fetchMock);
  const groq = new Groq({ apiKey: 'k', modelo: 'm', base: 'http://localhost:9999/v1' });
  await groq.responder(turnos);
  expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:9999/v1/chat/completions');
});
