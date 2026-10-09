// Saca el primer objeto JSON de un texto, tolerante a ```json o texto alrededor.
// Lo usan el orquestador y el agente para leer las decisiones de la IA sin romper
// si vienen envueltas en markdown o con texto de más.
export function extraerJson(crudo: string): Record<string, unknown> | null {
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
