// El orquestador: convierte lo que pide el operador en lenguaje natural en UNA
// decisión estructurada, usando la IA SOLO como traductor de intención.
//
// Idea de seguridad (OWASP LLM Top 10 - "Excessive Agency"): la IA puede saber
// mucho de metodología, pero su AGENCIA está acotada. Nunca arma una línea de
// comando ni elige flags: solo puede proponer una herramienta y un perfil del
// MENÚ cerrado que le pasamos, contra un objetivo que el operador mencionó.
// Esa propuesta después la valida el bot contra el alcance declarado (scope.ts /
// operadores.ts) y la cola, por la MISMA ruta que un comando manual. Si la IA
// alucina un objetivo fuera de alcance, se rechaza igual.
//
// La autoridad no vive en la IA: vive en el alcance que el humano declaró. La IA
// jamás declara alcance ni asume permiso; si falta, lo pide en texto.

import type { IA, Turno } from './ia.ts';
import type { Herramienta } from './herramientas.ts';
import { clasificar } from './scope.ts';

export interface PasoPlan {
  herramienta: string;
  perfil: string;
  descripcion: string;
}

export type Plan =
  | { accion: 'ejecutar'; herramienta: string; objetivo: string; perfil: string; nota?: string }
  | { accion: 'recon'; objetivo: string; pasos: PasoPlan[] }
  | { accion: 'responder'; texto: string };

/** Tope de pasos de un plan de reconocimiento (evita planes enormes). */
const MAX_PASOS = 6;

const SYSTEM_AUDITOR = [
  'Sos un asistente de auditoría de seguridad que trabaja DENTRO de wtfuck, una',
  'app de mensajería privada. Te usa un profesional que ya aceptó los términos. Su',
  'alcance se compone de objetivos que un administrador APROBÓ. Respondé en',
  'español, claro y al grano.',
  '',
  'A partir de lo que pide el operador, decidís UNA de tres cosas:',
  '  (a) EJECUTAR una sola herramienta del MENÚ, o',
  '  (b) proponer un RECONOCIMIENTO: un plan ordenado de pasos del MENÚ sobre UN',
  '      objetivo (cuando pide "reconocé X", "auditá X", o algo de varias etapas), o',
  '  (c) RESPONDER con texto (explicar, sugerir, pedir precisión).',
  '',
  'METODOLOGÍA (marco mental, estándar PTES): pre-engagement → intelligence',
  'gathering (reconocimiento) → threat modeling → análisis de vulnerabilidades →',
  'explotación → post-explotación → reporte. Para web, te guiás por OWASP WSTG',
  '(Information Gathering: fingerprinting, superficie de ataque, puntos de',
  'entrada). Con el menú actual estás sobre todo en reconocimiento, enumeración y',
  'análisis. Podés EXPLICAR cualquier fase y sugerir próximos pasos en texto,',
  'aunque no tengas la herramienta para ejecutarlos.',
  '',
  'CÓMO ELEGIR EL PERFIL de un escaneo según la intención del operador:',
  '  - un vistazo rápido / "está vivo" / pocos puertos → el perfil más liviano',
  '  - un barrido estándar → el perfil normal',
  '  - identificar servicios y versiones → el perfil de servicios',
  '  - exhaustivo / todos los puertos → el perfil completo (avisá que es lento)',
  '',
  'REGLAS DURAS (no las rompas nunca):',
  '1. Solo podés ejecutar herramientas y perfiles que estén en el MENÚ de abajo.',
  '   Nunca inventes flags, comandos ni herramientas que no estén listadas.',
  '2. El objetivo tiene que ser algo que el operador pidió EXPLÍCITAMENTE. Nunca',
  '   lo cambies, lo amplíes, ni agregues objetivos por tu cuenta.',
  '3. Vos NO autorizás nada. La autorización es responsabilidad del operador y la',
  '   gobierna su alcance declarado, que el sistema valida por separado. Si algo',
  '   parece faltar en el alcance, decílo en texto; nunca asumas permiso.',
  '4. No des instrucciones para evadir defensas ni para atacar algo fuera de una',
  '   auditoría autorizada.',
  '5. Ante la duda, respondé con texto y pedí precisión. No ejecutes a lo loco.',
  '',
  'Para un RECONOCIMIENTO: ordená los pasos de menos a más intrusivo (primero',
  'descubrir, después detallar). Máximo 6 pasos. Cada paso es una herramienta y un',
  'perfil del MENÚ, con una descripción corta de qué busca. Todos los pasos van',
  'contra el MISMO objetivo que pidió el operador.',
  '',
  'FORMATO: respondé SIEMPRE con UN solo objeto JSON y nada más (sin markdown, sin',
  'texto antes ni después):',
  '  ejecutar:  {"accion":"ejecutar","herramienta":"<nombre>","objetivo":"<lo que pidió>","perfil":"<del menú>","nota":"<por qué, breve>"}',
  '  recon:     {"accion":"recon","objetivo":"<lo que pidió>","pasos":[{"herramienta":"<nombre>","perfil":"<del menú>","descripcion":"<qué busca>"}]}',
  '  responder: {"accion":"responder","texto":"<tu respuesta>"}',
].join('\n');

export class Orquestador {
  constructor(
    private readonly ia: IA,
    private readonly herramientas: Herramienta[],
    private readonly systemExtra?: string,
  ) {}

  private menu(): string {
    const lineas = this.herramientas.map((h) => `  - ${h.nombre}: ${h.descripcion} — perfiles: ${h.perfiles.join(', ')}`);
    return `MENÚ DE HERRAMIENTAS (es lo ÚNICO que podés ejecutar):\n${lineas.join('\n')}`;
  }

  /**
   * Decide un Plan a partir del mensaje del operador. SIEMPRE devuelve un Plan
   * válido: si la IA devuelve algo raro, cae a `responder` en vez de romper.
   */
  async decidir(mensaje: string, contexto: { alcance: string[] }): Promise<Plan> {
    const alcance = contexto.alcance.length ? contexto.alcance.join(', ') : '(todavía no tiene objetivos aprobados)';
    const system = `${SYSTEM_AUDITOR}\n\n${this.menu()}${this.systemExtra ? `\n\n${this.systemExtra}` : ''}`;
    const turnos: Turno[] = [
      { rol: 'system', texto: system },
      { rol: 'user', texto: `Alcance aprobado del operador: ${alcance}\nMensaje del operador: ${JSON.stringify(mensaje)}` },
    ];

    let crudo: string;
    try {
      crudo = await this.ia.responder(turnos);
    } catch (e) {
      return { accion: 'responder', texto: `No pude pensar la respuesta: ${(e as Error).message}` };
    }
    return this.interpretar(crudo);
  }

  /** Parsea y VALIDA la decisión de la IA contra el registro de herramientas. */
  private interpretar(crudo: string): Plan {
    const obj = extraerJson(crudo);
    // Sin JSON parseable: tratamos todo como una respuesta de texto.
    if (!obj) return { accion: 'responder', texto: crudo.trim() || '(sin respuesta)' };

    if (obj['accion'] === 'ejecutar') {
      const nombre = typeof obj['herramienta'] === 'string' ? obj['herramienta'].trim().toLowerCase() : '';
      const objetivo = typeof obj['objetivo'] === 'string' ? obj['objetivo'].trim() : '';
      const perfilPedido = typeof obj['perfil'] === 'string' ? obj['perfil'].trim().toLowerCase() : '';
      const nota = typeof obj['nota'] === 'string' ? obj['nota'].trim() : undefined;

      const h = this.herramientas.find((x) => x.nombre === nombre);
      if (!h) return { accion: 'responder', texto: `No tengo esa herramienta. Puedo: ${this.herramientas.map((x) => x.nombre).join(', ')}.` };
      if (!objetivo || !clasificar(objetivo)) return { accion: 'responder', texto: 'Decime contra qué objetivo (un host, IP o rango) querés que corra.' };
      // Perfil fuera del menú (o ausente) → caemos a uno del propio registro.
      const perfil = h.perfiles.includes(perfilPedido) ? perfilPedido : (h.perfiles.includes('normal') ? 'normal' : h.perfiles[0]!);
      return { accion: 'ejecutar', herramienta: h.nombre, objetivo, perfil, nota };
    }

    if (obj['accion'] === 'recon') {
      const objetivo = typeof obj['objetivo'] === 'string' ? obj['objetivo'].trim() : '';
      if (!objetivo || !clasificar(objetivo)) return { accion: 'responder', texto: 'Decime contra qué objetivo (un host, IP o rango) querés el reconocimiento.' };
      const crudos = Array.isArray(obj['pasos']) ? obj['pasos'] : [];
      const pasos: PasoPlan[] = [];
      for (const p of crudos.slice(0, MAX_PASOS)) {
        if (!p || typeof p !== 'object') continue;
        const pp = p as Record<string, unknown>;
        const nombre = typeof pp['herramienta'] === 'string' ? pp['herramienta'].trim().toLowerCase() : '';
        const h = this.herramientas.find((x) => x.nombre === nombre);
        if (!h) continue; // paso con herramienta fuera del menú: se descarta
        const perfilPedido = typeof pp['perfil'] === 'string' ? pp['perfil'].trim().toLowerCase() : '';
        const perfil = h.perfiles.includes(perfilPedido) ? perfilPedido : (h.perfiles.includes('normal') ? 'normal' : h.perfiles[0]!);
        const descripcion = typeof pp['descripcion'] === 'string' && pp['descripcion'].trim() ? pp['descripcion'].trim() : `${h.nombre} (${perfil})`;
        pasos.push({ herramienta: h.nombre, perfil, descripcion });
      }
      if (!pasos.length) return { accion: 'responder', texto: 'No pude armar un plan con las herramientas que tengo. ¿Querés que corra algo puntual?' };
      return { accion: 'recon', objetivo, pasos };
    }

    if (obj['accion'] === 'responder' && typeof obj['texto'] === 'string') {
      return { accion: 'responder', texto: obj['texto'].trim() || '(sin respuesta)' };
    }

    // JSON con forma inesperada: no inventamos una ejecución, respondemos.
    return { accion: 'responder', texto: 'No te entendí bien. ¿Qué querés auditar y contra qué objetivo?' };
  }
}

/** Saca el primer objeto JSON de un texto (tolerante a ```json o texto alrededor). */
function extraerJson(crudo: string): Record<string, unknown> | null {
  const i = crudo.indexOf('{');
  const j = crudo.lastIndexOf('}');
  if (i < 0 || j <= i) return null;
  try {
    const v = JSON.parse(crudo.slice(i, j + 1)) as unknown;
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}
