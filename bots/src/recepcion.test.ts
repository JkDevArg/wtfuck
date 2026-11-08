import { describe, expect, test } from 'vitest';
import { abrir, bienvenida, menu, responder, RUTAS_ENLAZADAS } from './recepcion.ts';

describe('Recepción (@rootbot) — guionado, sin IA', () => {
  test('la bienvenida se presenta e incluye el menú', () => {
    const b = bienvenida({ nombre: 'RootBot' });
    expect(b).toContain('RootBot');
    expect(b).toContain('Puedo ayudarte con:');
    // El menú va dentro de la bienvenida (para no repetirlo después).
    expect(b).toContain(menu());
  });

  test('"hola", vacío y /ayuda devuelven el menú', () => {
    for (const t of ['hola', '', '   ', '/ayuda', 'ayuda', 'menu', 'Buenas!']) {
      expect(responder(t)).toBe(menu());
    }
  });

  test('intención por palabras clave: crear un bot → pantalla Mis bots', () => {
    const r = responder('cómo creo un bot?');
    expect(r.toLowerCase()).toContain('bot');
    expect(r).toContain(abrir('bots'));
  });

  test('recuperar contraseña: cubre código, frase y enlace de admin, y avisa del historial', () => {
    const r = responder('perdí mi contraseña, no puedo entrar');
    expect(r).toContain('Código de recuperación');
    expect(r).toContain('Frase de recuperación');
    expect(r).toContain('administrador');
    // El aviso crítico de no borrar el historial al recuperar.
    expect(r.toLowerCase()).toContain('no desinstales');
  });

  test('las tildes y mayúsculas no importan (normalización)', () => {
    expect(responder('RECUPERAR CLAVE')).toBe(responder('recuperar clave'));
    expect(responder('contraseña')).toBe(responder('contrasena'));
  });

  test('elegir por número del menú funciona', () => {
    // El número 1 del menú es "Crear un bot".
    expect(responder('1')).toBe(responder('crear un bot'));
  });

  test('un número fuera de rango no se trata como opción', () => {
    const r = responder('999');
    expect(r).toContain('No estoy seguro');
    expect(r).toContain(menu());
  });

  test('texto sin intención conocida: lo dice y ofrece el menú (nunca deja sin salida)', () => {
    const r = responder('asdkjfh qwerty zzz');
    expect(r).toContain('No estoy seguro');
    expect(r).toContain(menu());
  });

  test('el dominio web aparece en los textos solo si se configura', () => {
    const sin = responder('llamadas');
    expect(sin).not.toContain('https://');
    const con = responder('llamadas', { web: 'webfck.ejemplo.com' });
    expect(con).toContain('https://webfck.ejemplo.com');
  });

  test('privacidad: explica E2EE y el buzón tonto, sin mandar nada afuera', () => {
    const r = responder('esto es seguro? me pueden leer?');
    expect(r.toLowerCase()).toContain('punta a punta');
    expect(r.toLowerCase()).toContain('buzón tonto');
  });

  test('pedir una persona: aclara que es un bot automático', () => {
    const r = responder('quiero hablar con un humano');
    expect(r.toLowerCase()).toContain('bot automático');
  });

  test('los deep-links que enlaza usan el esquema wtfuck://abrir?p=', () => {
    for (const ruta of RUTAS_ENLAZADAS) {
      expect(abrir(ruta)).toBe(`wtfuck://abrir?p=${ruta}`);
    }
  });
});
