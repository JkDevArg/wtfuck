import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { motor, sincronizarConversaciones, titulo, type Conversacion, type Reaccion } from './datos/motor';
import {
  buscar, comentar, comentarios, desuscribir, directorio, ficha, imagenDe, muro, publicar, reaccionarPublicacion,
  suscribir, type CanalEnBusqueda, type Comentario, type ConfigCanal, type Publicacion,
} from './datos/canales';

const usarMotor = () => useSyncExternalStore(motor.suscribir, motor.instantanea);
const REACCIONES = ['👍', '❤️', '😂', '😮', '😢', '🙏'];

function hora(ms: number): string {
  const d = new Date(ms);
  return d.toDateString() === new Date().toDateString()
    ? d.toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('es-PE', { day: '2-digit', month: '2-digit' });
}

function Imagen({ adjuntoId, ancho, alto }: { adjuntoId: string; ancho: number; alto: number }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    imagenDe(adjuntoId).then(setUrl).catch((e: Error) => setError(e.message));
  }, [adjuntoId]);
  const p = ancho && alto ? Math.min(1.8, Math.max(0.6, ancho / alto)) : 1.3;
  return (
    <div className="adjunto imagen" style={{ aspectRatio: String(p) }}>
      {url && <img src={url} alt="" />}
      {error && <span className="velo-texto error">{error}</span>}
    </div>
  );
}

function Reacciones({ lista, onTocar }: { lista: Reaccion[]; onTocar: (emoji: string, ya: boolean) => void }) {
  const [eligiendo, setEligiendo] = useState(false);
  return (
    <span className="reacciones">
      {lista.map((r) => (
        <button key={r.emoji} className={r.mia ? 'mia' : ''} title={r.quienes.map((q) => '@' + q).join(', ')} onClick={() => onTocar(r.emoji, r.mia)}>
          {r.emoji} {r.total}
        </button>
      ))}
      <button onClick={() => setEligiendo((x) => !x)} aria-label="Reaccionar">+</button>
      {eligiendo && REACCIONES.map((e) => (
        <button key={e} onClick={() => { setEligiendo(false); onTocar(e, lista.some((r) => r.emoji === e && r.mia)); }}>{e}</button>
      ))}
    </span>
  );
}

function Comentarios({ canal, p, puede }: { canal: string; p: Publicacion; puede: boolean }) {
  const [lista, setLista] = useState<Comentario[] | null>(null);
  const [texto, setTexto] = useState('');
  const [error, setError] = useState<string | null>(null);
  const cargar = useCallback(() => comentarios(canal, p.mensajeId).then(setLista).catch((e: Error) => setError(e.message)), [canal, p.mensajeId]);
  useEffect(() => { void cargar(); }, [cargar]);
  async function enviar() {
    const t = texto.trim();
    if (!t) return;
    setTexto('');
    try {
      await comentar(canal, p.mensajeId, t);
      await cargar();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <div className="comentarios">
      {lista?.map((c) => (
        <div key={c.mensajeId} className="comentario"><b>@{c.autor}</b> {c.cuerpo} <span className="tenue">{hora(c.creadoEn)}</span></div>
      ))}
      {lista?.length === 0 && <p className="tenue">Sin comentarios todavía.</p>}
      {puede && (
        <div className="comentar">
          <input value={texto} onChange={(e) => setTexto(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && void enviar()} placeholder="Comentar" maxLength={2048} aria-label="Comentar" />
          <button onClick={() => void enviar()} disabled={!texto.trim()}>Comentar</button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  );
}

export function Canal({ c, onAtras }: { c: Conversacion; onAtras: () => void }) {
  const { canalesNuevos } = usarMotor();
  const [cfg, setCfg] = useState<ConfigCanal | null>(null);
  const [pubs, setPubs] = useState<Publicacion[]>([]);
  const [hayMas, setHayMas] = useState(false);
  const [abiertos, setAbiertos] = useState<Record<string, boolean>>({});
  const [texto, setTexto] = useState('');
  const [error, setError] = useState<string | null>(null);
  const nuevas = canalesNuevos[c.id] ?? 0;

  const recargar = useCallback(async () => {
    try {
      const [f, m] = await Promise.all([ficha(c.id), muro(c.id)]);
      setCfg(f);
      setPubs(m);
      setHayMas(m.length >= 30);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [c.id]);

  useEffect(() => { void recargar(); }, [recargar, nuevas]);

  async function anteriores() {
    const mas = await muro(c.id, pubs[pubs.length - 1]?.mensajeId);
    setPubs((p) => [...p, ...mas]);
    setHayMas(mas.length >= 30);
  }

  async function enviar() {
    const t = texto.trim();
    if (!t) return;
    setTexto('');
    try {
      await publicar(c.id, t);
      await recargar();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function reaccionar(p: Publicacion, emoji: string, ya: boolean) {
    try {
      const meta = await reaccionarPublicacion(p.mensajeId, emoji, !ya);
      setPubs((l) => l.map((x) => (x.mensajeId === p.mensajeId ? { ...x, reacciones: meta.reacciones } : x)));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function salir() {
    if (!confirm('¿Dejar de seguir este canal?')) return;
    await desuscribir(c.id).catch((e: Error) => setError(e.message));
    await sincronizarConversaciones();
    onAtras();
  }

  return (
    <>
      <header className="cabecera">
        <button className="atras" onClick={onAtras} aria-label="Volver a la lista">←</button>
        <span className="nombre">{titulo(c)}</span>
        {cfg && <span className="miembros">{cfg.suscriptores} suscriptores</span>}
        {cfg?.suscrito && !cfg.puedoGestionar && <button className="enlace derecha" onClick={() => void salir()}>Dejar de seguir</button>}
      </header>
      {cfg && !cfg.cifrado && (
        <div className="cambio">Canal público: el contenido no va cifrado de extremo a extremo. Lo puede leer cualquiera.</div>
      )}
      {cfg && cfg.estado !== 'aprobado' && (
        <div className="cambio">{cfg.estado === 'pendiente' ? 'Pendiente de aprobación: todavía no aparece en el directorio.' : `Rechazado${cfg.motivoRechazo ? `: ${cfg.motivoRechazo}` : '.'}`}</div>
      )}
      <div className="mensajes muro">
        {cfg && !cfg.publico && (
          <p className="tenue centro">Los canales privados van cifrados como un grupo, y todavía no se pueden leer desde la web.</p>
        )}
        {[...pubs].reverse().map((p) => (
          <article key={p.mensajeId} className="publicacion">
            <span className="autor">@{p.autor}{p.fijado ? ' · fijada' : ''}</span>
            {p.adjuntoId && <Imagen adjuntoId={p.adjuntoId} ancho={p.adjuntoAncho} alto={p.adjuntoAlto} />}
            {p.cuerpo && <span className="texto">{p.cuerpo}</span>}
            <span className="pie">{p.editado && <span>editado</span>}{hora(p.creadoEn)}</span>
            {cfg?.reacciones && <Reacciones lista={p.reacciones} onTocar={(e, ya) => void reaccionar(p, e, ya)} />}
            {cfg?.comentarios && (
              <button className="enlace cian" onClick={() => setAbiertos((a) => ({ ...a, [p.mensajeId]: !a[p.mensajeId] }))}>
                {p.comentarios} comentario{p.comentarios === 1 ? '' : 's'}
              </button>
            )}
            {abiertos[p.mensajeId] && <Comentarios canal={c.id} p={p} puede={!!cfg?.suscrito} />}
          </article>
        ))}
        {hayMas && <button className="enlace cian centro" onClick={() => void anteriores()}>Ver anteriores</button>}
        {cfg?.publico && !pubs.length && <p className="tenue centro">Este canal todavía no publicó nada.</p>}
      </div>
      {error && <div className="contexto"><span className="error">{error}</span><button className="enlace" onClick={() => setError(null)}>Cerrar</button></div>}
      {cfg?.puedoPublicar && cfg.publico && (
        <footer className="escribir">
          <textarea value={texto} onChange={(e) => setTexto(e.target.value)} placeholder="Publicar en el canal (sin cifrar)" rows={1} maxLength={8192} aria-label="Publicar" />
          <button onClick={() => void enviar()} disabled={!texto.trim()}>Publicar</button>
        </footer>
      )}
    </>
  );
}

/** El directorio de canales públicos y el buscador, como Descubrir de la app. */
export function DescubrirCanales({ onCerrar, onAbrir }: { onCerrar: () => void; onAbrir: (id: string) => void }) {
  const [lista, setLista] = useState<CanalEnBusqueda[] | null>(null);
  const [q, setQ] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      (q.trim().length >= 2 ? buscar(q.trim()) : directorio()).then(setLista).catch((e: Error) => setError(e.message));
    }, 250);
    return () => clearTimeout(t);
  }, [q]);

  async function seguir(c: CanalEnBusqueda) {
    try {
      if (!c.suscrito) await suscribir(c.conversacionId);
      await sincronizarConversaciones();
      onAbrir(c.conversacionId);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Descubrir canales" onClick={onCerrar}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h2>Canales</h2>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar por nombre o alias" aria-label="Buscar canales" />
        <div className="canales">
          {lista?.map((c) => (
            <button key={c.conversacionId} className="fila" onClick={() => void seguir(c)}>
              <span className="avatar" aria-hidden="true">{c.nombre.slice(0, 2).toUpperCase()}</span>
              <span className="cuerpo">
                <span className="arriba"><span className="nombre">{c.nombre}</span><span className="cuando">{c.suscriptores} subs.</span></span>
                <span className="abajo"><span className="previa">@{c.alias} · {c.descripcion || 'Sin descripción'}</span>{c.suscrito && <span className="globo">✓</span>}</span>
              </span>
            </button>
          ))}
          {lista?.length === 0 && <p className="tenue">No hay canales{q.trim() ? ' con ese nombre' : ' en el directorio todavía'}.</p>}
        </div>
        {error && <p className="error">{error}</p>}
        <button onClick={onCerrar}>Cerrar</button>
      </div>
    </div>
  );
}
