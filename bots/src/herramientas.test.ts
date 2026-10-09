import { describe, expect, test, vi } from 'vitest';
import { CATALOGO, crearHerramienta, crearHerramientas, crearNmap, PERFILES_NMAP, type Ejecutor } from './herramientas.ts';

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

describe('catálogo genérico', () => {
  test('todas las herramientas: flags sin espacios ni metacaracteres de shell', () => {
    for (const spec of Object.values(CATALOGO)) {
      for (const flags of Object.values(spec.perfiles)) {
        for (const f of flags) {
          expect(f).not.toMatch(/\s/);
          expect(f).not.toMatch(/[;|&$`()<>\\]/);
        }
      }
      for (const s of spec.subcomando ?? []) expect(s).toMatch(/^[-A-Za-z0-9]+$/);
    }
  });

  test('objetivo "final": va como último argumento', async () => {
    const ej = fakeEjecutor();
    const whois = crearHerramienta(CATALOGO['whois']!, ej, cfg);
    await whois.correr('example.com', 'normal');
    const [cmd, args] = ej.mock.calls[0]!;
    expect(cmd).toBe('whois');
    expect(args.at(-1)).toBe('example.com');
  });

  test('objetivo por flag: va tras su flag, como argumento aparte', async () => {
    const ej = fakeEjecutor();
    const subfinder = crearHerramienta(CATALOGO['subfinder']!, ej, cfg);
    await subfinder.correr('example.com', 'normal');
    const [cmd, args] = ej.mock.calls[0]!;
    expect(cmd).toBe('subfinder');
    expect(args).toEqual(['-silent', '-d', 'example.com']);
  });

  test('subcomando va primero (amass enum)', async () => {
    const ej = fakeEjecutor();
    const amass = crearHerramienta(CATALOGO['amass']!, ej, cfg);
    await amass.correr('example.com', 'pasivo');
    const [cmd, args] = ej.mock.calls[0]!;
    expect(cmd).toBe('amass');
    expect(args).toEqual(['enum', '-passive', '-d', 'example.com']);
  });

  test('binario distinto del nombre (theharvester → theHarvester)', async () => {
    const ej = fakeEjecutor();
    const h = crearHerramienta(CATALOGO['theharvester']!, ej, cfg);
    expect(h.nombre).toBe('theharvester');
    await h.correr('example.com', 'rapido');
    expect(ej.mock.calls[0]![0]).toBe('theHarvester');
  });

  test('crearHerramientas filtra por nombre y descarta desconocidos', () => {
    const hs = crearHerramientas(['nmap', 'subfinder', 'noexiste'], fakeEjecutor(), cfg);
    expect(hs.map((h) => h.nombre)).toEqual(['nmap', 'subfinder']);
  });

  test('crearHerramientas sin nombres construye todo el catálogo', () => {
    const hs = crearHerramientas([], fakeEjecutor(), cfg);
    expect(hs.length).toBe(Object.keys(CATALOGO).length);
  });

  test('cada herramienta declara descripción y al menos un perfil', () => {
    for (const h of crearHerramientas([], fakeEjecutor(), cfg)) {
      expect(h.descripcion.length).toBeGreaterThan(0);
      expect(h.perfiles.length).toBeGreaterThan(0);
    }
  });
});
