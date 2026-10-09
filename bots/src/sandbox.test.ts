import { describe, expect, test } from 'vitest';
import { planearEgress, scriptEgress, type Resolver } from './sandbox.ts';

const EXCL = ['169.254.0.0/16', '127.0.0.0/8'];

// Resolver falso: mapea nombres a IPs sin tocar la red.
const dns: Record<string, string[]> = {
  'example.com': ['93.184.216.34'],
  'scanme.nmap.org': ['45.33.32.156'],
  'multi.com': ['1.1.1.1', '2.2.2.2'],
};
const resolver: Resolver = async (h) => dns[h] ?? [];

describe('planearEgress', () => {
  test('resuelve hosts a IP y deja pasar IPs/CIDR tal cual', async () => {
    const e = await planearEgress(['example.com', '10.5.0.0/24', '198.51.100.7'], EXCL, resolver);
    expect(e.permitidas).toContain('93.184.216.34');
    expect(e.permitidas).toContain('10.5.0.0/24');
    expect(e.permitidas).toContain('198.51.100.7');
  });

  test('un host con varias IPs las incluye todas', async () => {
    const e = await planearEgress(['multi.com'], EXCL, resolver);
    expect(e.permitidas).toEqual(expect.arrayContaining(['1.1.1.1', '2.2.2.2']));
  });

  test('scope vacío ⇒ no se permite ninguna salida (falla seguro)', async () => {
    const e = await planearEgress([], EXCL, resolver);
    expect(e.permitidas).toHaveLength(0);
  });

  test('un host que no resuelve queda fuera y se reporta', async () => {
    const e = await planearEgress(['noexiste.tld'], EXCL, resolver);
    expect(e.permitidas).toHaveLength(0);
    expect(e.sinResolver).toContain('noexiste.tld');
  });

  test('metadatos de nube y loopback SIEMPRE negados', async () => {
    const e = await planearEgress(['example.com'], EXCL, resolver);
    expect(e.negadas).toContain('169.254.0.0/16');
    expect(e.negadas).toContain('127.0.0.0/8');
  });

  test('rangos privados negados si el scope no los incluye', async () => {
    const e = await planearEgress(['example.com'], EXCL, resolver);
    expect(e.negadas).toEqual(expect.arrayContaining(['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']));
  });

  test('un rango privado en el scope SÍ se permite (y no se niega)', async () => {
    const e = await planearEgress(['10.0.0.0/8'], EXCL, resolver);
    expect(e.permitidas).toContain('10.0.0.0/8');
    expect(e.negadas).not.toContain('10.0.0.0/8');
  });
});

describe('scriptEgress', () => {
  test('política por defecto DROP y DNS permitido', async () => {
    const e = await planearEgress(['example.com'], EXCL, resolver);
    const s = scriptEgress(e);
    expect(s).toContain('iptables -P OUTPUT DROP');
    expect(s).toContain('--dport 53 -j ACCEPT');
  });

  test('los negados van como DROP y los permitidos como ACCEPT', async () => {
    const e = await planearEgress(['example.com'], EXCL, resolver);
    const s = scriptEgress(e);
    expect(s).toContain('iptables -A OUTPUT -d 169.254.0.0/16 -j DROP');
    expect(s).toContain('iptables -A OUTPUT -d 93.184.216.34 -j ACCEPT');
  });

  test('el DROP de un negado aparece antes que cualquier ACCEPT de destino', async () => {
    const e = await planearEgress(['example.com'], EXCL, resolver);
    const s = scriptEgress(e);
    const primerDrop = s.indexOf('-d 169.254.0.0/16 -j DROP');
    const primerAccept = s.indexOf('-d 93.184.216.34 -j ACCEPT');
    expect(primerDrop).toBeGreaterThanOrEqual(0);
    expect(primerDrop).toBeLessThan(primerAccept);
  });
});
