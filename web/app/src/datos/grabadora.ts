// Notas de voz: lo mismo que el Reproductor de la app.
//
// ## El formato
//
// La app graba MP4 con AAC a 64 kbps. Chrome y Safari también lo saben hacer
// con MediaRecorder, y es lo que se pide primero: Android lo reproduce con
// MediaPlayer y con duración. El WebM que graba Chrome NO trae duración, y
// en Android la barra no avanzaría ni se podría saltar: solo se usa si el
// navegador no sabe hacer MP4.
//
// ## La onda
//
// Cada 80 ms, el pico de la señal, en la misma escala que la app (que lee
// `maxAmplitude` de 0 a 32767 y divide por 12000). Al final, 40 barras con el
// mismo algoritmo que `Onda.codificar`.

const ALFABETO = '0123456789abcdefghijklmnopqrstuv';
export const BARRAS = 40;
export const TOPE_MS = 10 * 60 * 1000;
export const TOPE_BYTES = 12 * 1024 * 1024;
export const MINIMO_MS = 1000;

/** Igual que `Onda.codificar` (protocol/Adjuntos.kt). */
export function codificarOnda(m: number[]): string {
  if (!m.length) return '';
  const tope = Math.max(...m);
  if (tope <= 0) return '';
  const n = m.length;
  let out = '';
  for (let i = 0; i < BARRAS; i++) {
    const desde = Math.floor((i * n) / BARRAS);
    const hasta = Math.max(Math.floor(((i + 1) * n) / BARRAS), desde + 1);
    let pico = 0;
    for (let j = desde; j < Math.min(hasta, n); j++) pico = Math.max(pico, m[j]);
    // Kotlin hace (pico / tope * 31).toInt() en Float: se trunca igual.
    const nivel = Math.min(31, Math.max(0, Math.trunc(Math.fround(Math.fround(pico / tope) * 31))));
    out += ALFABETO[nivel];
  }
  return out;
}

/** 40 alturas de 0 a 1 para dibujar, o null si la onda no vale. */
export function decodificarOnda(s: string): number[] | null {
  if (s.length !== BARRAS) return null;
  const v = [...s].map((c) => ALFABETO.indexOf(c));
  return v.some((x) => x < 0) ? null : v.map((x) => x / 31);
}

export const mimeDeGrabacion = (): string | null =>
  ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4', 'audio/webm;codecs=opus'].find((t) =>
    typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(t),
  ) ?? null;

export interface NotaGrabada {
  archivo: File;
  duracionMs: number;
  onda: string;
}

/**
 * Una fuente de prueba (un tono que sube y baja) en lugar del micrófono. Solo
 * existe en el servidor de desarrollo: ahí no siempre hay micrófono, y el
 * panel del navegador lo bloquea.
 */
function fuenteDePrueba(ctx: AudioContext): MediaStream {
  const osc = ctx.createOscillator();
  const vol = ctx.createGain();
  osc.frequency.value = 440;
  vol.gain.setValueAtTime(0.1, ctx.currentTime);
  for (let t = 0; t < 600; t += 0.6) vol.gain.linearRampToValueAtTime(t % 1.2 < 0.6 ? 0.9 : 0.1, ctx.currentTime + t);
  const destino = ctx.createMediaStreamDestination();
  osc.connect(vol).connect(destino);
  osc.start();
  return destino.stream;
}

export class Grabacion {
  private rec: MediaRecorder;
  private trozos: Blob[] = [];
  private muestras: number[] = [];
  private inicio = performance.now();
  private reloj: ReturnType<typeof setInterval>;
  private bytes = 0;
  private terminar: ((n: NotaGrabada | null) => void) | null = null;
  readonly mime: string;

  private constructor(private stream: MediaStream, private ctx: AudioContext, mime: string, alTope: () => void) {
    this.mime = mime;
    const analizador = ctx.createAnalyser();
    analizador.fftSize = 2048;
    ctx.createMediaStreamSource(stream).connect(analizador);
    const buf = new Float32Array(analizador.fftSize);
    this.reloj = setInterval(() => {
      analizador.getFloatTimeDomainData(buf);
      let pico = 0;
      for (const x of buf) pico = Math.max(pico, Math.abs(x));
      this.muestras.push(Math.min(1, (pico * 32767) / 12000));
      if (performance.now() - this.inicio >= TOPE_MS) alTope();
    }, 80);
    this.rec = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 64_000 });
    this.rec.ondataavailable = (e) => {
      if (!e.data.size) return;
      this.trozos.push(e.data);
      this.bytes += e.data.size;
      if (this.bytes >= TOPE_BYTES) alTope();
    };
    this.rec.onstop = () => this.cerrar();
    this.rec.start(1000);
  }

  static async iniciar(alTope: () => void): Promise<Grabacion> {
    const mime = mimeDeGrabacion();
    if (!mime) throw new Error('Este navegador no sabe grabar audio.');
    const ctx = new AudioContext();
    const prueba = import.meta.env.DEV && new URLSearchParams(location.search).has('microfono-de-prueba');
    const stream = prueba ? fuenteDePrueba(ctx) : await navigator.mediaDevices.getUserMedia({ audio: true });
    return new Grabacion(stream, ctx, mime, alTope);
  }

  get transcurridoMs(): number {
    return performance.now() - this.inicio;
  }

  /** Termina y devuelve la nota, o null si duró menos de un segundo. */
  detener(): Promise<NotaGrabada | null> {
    return new Promise((r) => {
      this.terminar = r;
      if (this.rec.state !== 'inactive') this.rec.stop();
      else this.cerrar();
    });
  }

  cancelar(): void {
    this.terminar = null;
    if (this.rec.state !== 'inactive') this.rec.stop();
  }

  private cerrar(): void {
    clearInterval(this.reloj);
    this.stream.getTracks().forEach((t) => t.stop());
    void this.ctx.close();
    const duracionMs = Math.round(performance.now() - this.inicio);
    const f = this.terminar;
    this.terminar = null;
    if (!f) return;
    if (duracionMs < MINIMO_MS) {
      f(null);
      return;
    }
    const tipo = this.mime.split(';')[0];
    const ext = tipo === 'audio/mp4' ? 'm4a' : 'webm';
    const archivo = new File(this.trozos, `voz-${Date.now()}.${ext}`, { type: tipo });
    f({ archivo, duracionMs, onda: codificarOnda(this.muestras) });
  }
}

/**
 * El tipo para reproducir lo que llega: la app manda sus notas como
 * `application/octet-stream` (graba a un archivo sin tipo). Se deduce de la
 * extensión del nombre.
 */
export function mimeParaReproducir(mime: string, nombre: string): string {
  if (mime && mime !== 'application/octet-stream') return mime;
  const ext = nombre.toLowerCase().split('.').pop() ?? '';
  return { m4a: 'audio/mp4', mp4: 'video/mp4', aac: 'audio/aac', ogg: 'audio/ogg', opus: 'audio/ogg', webm: 'audio/webm', mp3: 'audio/mpeg' }[ext] ?? 'application/octet-stream';
}
