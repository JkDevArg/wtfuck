// El "cerebro" de un bot de charla. Una interfaz chica, para que el proveedor
// sea intercambiable: hoy Groq (gratis, rapido, y por contrato no entrena con
// lo que recibe), manana otro compatible con la API de OpenAI, o uno local.
//
// OJO, privacidad: lo que se le manda a la IA SALE de la burbuja E2EE hacia el
// proveedor. El bot lo puede leer porque es un participante; el servidor de
// wtfuck sigue sin poder. Un chat con un bot de IA tiene que avisarlo.

export interface Turno {
  rol: 'system' | 'user' | 'assistant';
  texto: string;
}

export interface IA {
  responder(turnos: Turno[]): Promise<string>;
}

export interface ConfigGroq {
  apiKey: string;
  modelo: string;
  /** Endpoint, por si cambia. Compatible con OpenAI. */
  base?: string;
  maxTokens?: number;
  temperatura?: number;
}

/** Cliente de Groq (https://console.groq.com), API compatible con OpenAI. */
export class Groq implements IA {
  private readonly base: string;
  private readonly maxTokens: number;
  private readonly temperatura: number;

  constructor(private readonly cfg: ConfigGroq) {
    if (!cfg.apiKey) throw new Error('Falta GROQ_API_KEY.');
    this.base = cfg.base ?? 'https://api.groq.com/openai/v1';
    this.maxTokens = cfg.maxTokens ?? 800;
    this.temperatura = cfg.temperatura ?? 0.6;
  }

  async responder(turnos: Turno[]): Promise<string> {
    let r: Response;
    try {
      r = await fetch(`${this.base}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.cfg.apiKey}`,
        },
        body: JSON.stringify({
          model: this.cfg.modelo,
          messages: turnos.map((t) => ({ role: t.rol, content: t.texto })),
          max_tokens: this.maxTokens,
          temperature: this.temperatura,
        }),
      });
    } catch {
      throw new Error('No pude hablar con la IA (sin conexion).');
    }

    if (r.status === 429) throw new Error('La IA esta saturada ahora mismo. Probá en un momento.');
    if (!r.ok) {
      const cuerpo = await r.text().catch(() => '');
      throw new Error(`La IA respondio ${r.status}: ${cuerpo.slice(0, 160)}`);
    }

    const j = (await r.json()) as { choices?: { message?: { content?: string } }[] };
    const texto = j.choices?.[0]?.message?.content?.trim();
    if (!texto) throw new Error('La IA no devolvio texto.');
    return texto;
  }
}
