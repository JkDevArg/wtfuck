import { expect, test, vi } from 'vitest';
import { crearNmap, PERFILES_NMAP, type Ejecutor } from './herramientas.ts';

const cfg = { timeoutMs: 1000, maxBytes: 100 };
function fakeEjecutor(resp: Partial<Awaited<ReturnType<Ejecutor>>> = {}) {
  return vi.fn<Ejecutor>(async () => ({ codigo: 0, salida: '22/tcp open ssh', recortado: false, vencio: false, ...resp }));
}

test('el objetivo va como argumento aparte, con los flags del perfil', async () => {
  const ej = fakeEjecutor();
  const nmap = crearNmap(ej, cfg);
  await nmap.correr('example.com', 'rapido');
  const [cmd, args] = ej.mock.calls[0]!;
  expect(cmd).toBe('nmap');
  expect(args).toEqual([...PERFILES_NMAP['rapido']!, 'example.com']);
  // El objetivo es UN elemento del argv: no hay shell donde inyectar.
  expect(args.at(-1)).toBe('example.com');
});

test('un perfil desconocido no ejecuta nada', async () => {
  const ej = fakeEjecutor();
  const nmap = crearNmap(ej, cfg);
  const r = await nmap.correr('example.com', 'inventado');
  expect(r.ok).toBe(false);
  expect(ej).not.toHaveBeenCalled();
});

test('marca el corte por tiempo y por tamano', async () => {
  const nmap = crearNmap(fakeEjecutor({ vencio: true }), cfg);
  const r = await nmap.correr('example.com', 'normal');
  expect(r.ok).toBe(true);
  if (r.ok) expect(r.salida).toMatch(/tiempo limite/);

  const nmap2 = crearNmap(fakeEjecutor({ recortado: true }), cfg);
  const r2 = await nmap2.correr('example.com', 'normal');
  if (r2.ok) expect(r2.salida).toMatch(/recortada/);
});

test('si el binario no esta, es un error claro', async () => {
  const nmap = crearNmap(fakeEjecutor({ codigo: null, salida: 'no se pudo ejecutar nmap: ENOENT' }), cfg);
  const r = await nmap.correr('example.com', 'normal');
  expect(r.ok).toBe(false);
  if (!r.ok) expect(r.error).toMatch(/no se pudo ejecutar/);
});

test('los perfiles son flags fijos, nunca texto del usuario', () => {
  for (const flags of Object.values(PERFILES_NMAP)) {
    for (const f of flags) expect(f).toMatch(/^[-A-Za-z0-9]+$/); // sin espacios ni metacaracteres
  }
});
