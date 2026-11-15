// Social / Estados en la web. Espeja SocialPantalla + VisorHistorias +
// EditorDeEstado de la app. La lógica (cifrado, API, persistencia) vive en
// datos/historias.ts; acá solo está la interfaz.
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import {
  agruparHistorias, instantaneaHistorias, marcarVistaHistoria, mediaDeHistoria, publicarFoto, publicarTexto,
  retirarHistoria, sincronizarHistorias, suscribirHistorias, vistasDeHistoria, FONDOS_HISTORIA, TOPE_TEXTO_HISTORIA,
  type GrupoHistorias,
} from './datos/historias';
import { sesionActual } from './datos/motor';

const usarHistorias = () => useSyncExternalStore(suscribirHistorias, instantaneaHistorias);

function iniciales(u: string): string {
  return (u || '?').slice(0, 2).toUpperCase();
}

function hace(ms: number): string {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return 'hace un momento';
  const m = Math.floor(s / 60);
  if (m < 60) return `hace ${m} min`;
  const h = Math.floor(m / 60);
  return `hace ${h} h`;
}

/** La pantalla Social: lista de estados (anillos) + crear + visor. */
export function Social({ onCerrar }: { onCerrar: () => void }) {
  const todas = usarHistorias();
  const [editor, setEditor] = useState(false);
  const [viendo, setViendo] = useState<{ grupos: GrupoHistorias[]; indice: number } | null>(null);
  const yo = sesionActual()?.username ?? '';

  useEffect(() => { void sincronizarHistorias(); }, []);

  const { mias, ajenas } = useMemo(() => agruparHistorias(todas), [todas]);
  const recientes = ajenas.filter((g) => g.algunaSinVer);
  const vistos = ajenas.filter((g) => !g.algunaSinVer);

  const abrir = (grupos: GrupoHistorias[], indice: number) => setViendo({ grupos, indice });
  const todosLosGrupos = [...(mias ? [mias] : []), ...ajenas];

  return (
    <div className="social-pantalla">
      <header className="cabecera">
        <button className="atras" onClick={onCerrar} aria-label="Cerrar">←</button>
        <span className="nombre">Social · Estados</span>
      </header>
      <div className="social-cuerpo">
        <h2 className="seccion">Tu estado</h2>
        <button className="fila" onClick={() => (mias ? abrir(todosLosGrupos, 0) : setEditor(true))}>
          <span className={`avatar anillo ${mias ? 'propio' : 'vacio'}`}>{iniciales(yo)}</span>
          <span className="cuerpo">
            <span className="nombre">{mias ? 'Mi estado' : 'Añadir a mi estado'}</span>
            <span className="previa">{mias ? `${mias.historias.length} ${mias.historias.length === 1 ? 'estado' : 'estados'} · toca para ver` : 'Foto o texto, se borra en 24 h'}</span>
          </span>
          <span className="social-mas" onClick={(e) => { e.stopPropagation(); setEditor(true); }} role="button" aria-label="Nuevo estado">＋</span>
        </button>

        {!!recientes.length && <h2 className="seccion">Recientes</h2>}
        {recientes.map((g) => (
          <FilaGrupo key={g.autorUsername} g={g} onAbrir={() => abrir(todosLosGrupos, todosLosGrupos.indexOf(g))} />
        ))}

        {!!vistos.length && <h2 className="seccion">Vistos</h2>}
        {vistos.map((g) => (
          <FilaGrupo key={g.autorUsername} g={g} onAbrir={() => abrir(todosLosGrupos, todosLosGrupos.indexOf(g))} />
        ))}

        {!mias && !ajenas.length && (
          <p className="tenue centrado">Todavía no hay estados. Toca “＋” para publicar el tuyo.</p>
        )}
      </div>

      <button className="social-nuevo" onClick={() => setEditor(true)}>Nuevo estado</button>

      {editor && <EditorEstado onCerrar={() => setEditor(false)} />}
      {viendo && <Visor grupos={viendo.grupos} indiceInicial={viendo.indice} onCerrar={() => setViendo(null)} />}
    </div>
  );
}

function FilaGrupo({ g, onAbrir }: { g: GrupoHistorias; onAbrir: () => void }) {
  return (
    <button className="fila" onClick={onAbrir}>
      <span className={`avatar anillo ${g.algunaSinVer ? 'sinver' : 'visto'}`}>{iniciales(g.autorUsername)}</span>
      <span className="cuerpo">
        <span className="nombre">@{g.autorUsername}</span>
        <span className="previa">{hace(g.ultima)}</span>
      </span>
    </button>
  );
}

// ---------------------------------------------------------------------------
//  Visor: pantalla completa, barras de progreso, autoavance
// ---------------------------------------------------------------------------

const DURACION_MS = 5000;

function Visor({ grupos, indiceInicial, onCerrar }: { grupos: GrupoHistorias[]; indiceInicial: number; onCerrar: () => void }) {
  const [ig, setIg] = useState(indiceInicial);
  const [ih, setIh] = useState(0);
  const [avance, setAvance] = useState(0);
  const [pausa, setPausa] = useState(false);
  const [media, setMedia] = useState<string | null>(null);
  const [verVistas, setVerVistas] = useState(false);

  const grupo = grupos[ig];
  const h = grupo?.historias[ih];

  const siguiente = useCallback(() => {
    setAvance(0); setMedia(null);
    if (!grupo) return onCerrar();
    if (ih + 1 < grupo.historias.length) { setIh(ih + 1); return; }
    if (ig + 1 < grupos.length) { setIg(ig + 1); setIh(0); return; }
    onCerrar();
  }, [grupo, ih, ig, grupos, onCerrar]);

  const anterior = () => {
    setAvance(0); setMedia(null);
    if (ih > 0) { setIh(ih - 1); return; }
    if (ig > 0) { const g = grupos[ig - 1]; setIg(ig - 1); setIh(Math.max(0, g.historias.length - 1)); return; }
  };

  // Marcar vista + cargar media al mostrar cada historia.
  useEffect(() => {
    if (!h) return;
    void marcarVistaHistoria(h.historiaId);
    if (h.clase === 'imagen' && h.adjunto) {
      let vivo = true;
      void mediaDeHistoria(h).then((u) => { if (vivo) setMedia(u); }).catch(() => {});
      return () => { vivo = false; };
    }
    return;
  }, [h]);

  // Autoavance (texto e imagen: 5 s).
  useEffect(() => {
    if (!h || pausa || verVistas) return;
    const t = setInterval(() => {
      setAvance((a) => {
        const n = a + 50 / DURACION_MS;
        if (n >= 1) { clearInterval(t); siguiente(); return 1; }
        return n;
      });
    }, 50);
    return () => clearInterval(t);
  }, [h, pausa, verVistas, siguiente]);

  if (!h) return null;
  const fondo = h.clase === 'imagen' ? '#000' : (/^#[0-9a-fA-F]{6}$/.test(h.fondo ?? '') ? h.fondo : '#1D9E75');

  return (
    <div className="visor" style={{ background: fondo }}>
      <div className="visor-barras">
        {grupo.historias.map((_, i) => (
          <span key={i} className="barra"><i style={{ width: `${i < ih ? 100 : i === ih ? avance * 100 : 0}%` }} /></span>
        ))}
      </div>
      <header className="visor-cab">
        <span className="avatar chico">{iniciales(grupo.esMia ? (sesionActual()?.username ?? '') : grupo.autorUsername)}</span>
        <span className="visor-quien">{grupo.esMia ? 'Mi estado' : '@' + grupo.autorUsername}</span>
        <span className="visor-hora">{hace(h.creadaEn)}</span>
        <button className="visor-x" onClick={onCerrar} aria-label="Cerrar">×</button>
      </header>

      <div
        className="visor-lienzo"
        onPointerDown={() => setPausa(true)}
        onPointerUp={() => setPausa(false)}
        onPointerLeave={() => setPausa(false)}
      >
        <span className="visor-zona izq" onClick={anterior} aria-label="Anterior" />
        <span className="visor-zona der" onClick={siguiente} aria-label="Siguiente" />
        {h.clase === 'imagen' ? (
          media ? <img src={media} alt="" className="visor-img" /> : <span className="visor-cargando">Cargando…</span>
        ) : (
          <p className="visor-texto">{h.texto}</p>
        )}
        {h.clase === 'imagen' && h.texto && <p className="visor-pie">{h.texto}</p>}
      </div>

      {grupo.esMia ? (
        <footer className="visor-pie-acc">
          <button className="enlace" onClick={() => { setPausa(true); setVerVistas(true); }}>
            {h.vistas == null ? 'Vistas ocultas' : `👁 ${h.vistas}`}
          </button>
          <button className="enlace peligro" onClick={() => { if (confirm('¿Eliminar este estado?')) void retirarHistoria(h.historiaId).then(onCerrar); }}>
            Eliminar
          </button>
        </footer>
      ) : null}

      {verVistas && <HojaVistas historiaId={h.historiaId} onCerrar={() => { setVerVistas(false); setPausa(false); }} />}
    </div>
  );
}

function HojaVistas({ historiaId, onCerrar }: { historiaId: string; onCerrar: () => void }) {
  const [lista, setLista] = useState<{ username: string; nombreMostrado: string; vistaEn: number }[] | null>(null);
  const [motivo, setMotivo] = useState<string | null>(null);
  useEffect(() => {
    void vistasDeHistoria(historiaId).then((r) => { setLista(r.vistas); setMotivo(r.motivo); }).catch(() => setLista([]));
  }, [historiaId]);
  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Quién lo vio" onClick={onCerrar}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h2>Quién lo vio</h2>
        {motivo === 'sin_lectura' && (
          <p className="tenue">Tenés las confirmaciones de lectura apagadas, así que tampoco ves quién vio lo tuyo.</p>
        )}
        {lista === null && <p className="tenue">Cargando…</p>}
        {lista?.length === 0 && motivo !== 'sin_lectura' && <p className="tenue">Todavía no lo vio nadie.</p>}
        {lista?.map((v) => (
          <div key={v.username} className="fila-vista">
            <span className="avatar chico">{iniciales(v.username)}</span>
            <span className="cuerpo"><span className="nombre">@{v.username}</span><span className="previa">{hace(v.vistaEn)}</span></span>
          </div>
        ))}
        <button onClick={onCerrar}>Cerrar</button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
//  Editor: texto o foto
// ---------------------------------------------------------------------------

function EditorEstado({ onCerrar }: { onCerrar: () => void }) {
  const [modo, setModo] = useState<'texto' | 'foto'>('texto');
  const [texto, setTexto] = useState('');
  const [fondo, setFondo] = useState(FONDOS_HISTORIA[0]!);
  const [foto, setFoto] = useState<File | null>(null);
  const [vistaPrevia, setVistaPrevia] = useState<string | null>(null);
  const [pie, setPie] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const selector = useRef<HTMLInputElement>(null);

  function elegirFoto(f: File | null) {
    if (!f) return;
    setFoto(f);
    setVistaPrevia(URL.createObjectURL(f));
    setModo('foto');
  }

  async function publicar() {
    setOcupado(true); setError(null);
    try {
      if (modo === 'foto' && foto) await publicarFoto(foto, pie.trim());
      else await publicarTexto(texto, fondo);
      onCerrar();
    } catch (x) {
      setError(x instanceof Error ? x.message : 'No se pudo publicar.');
      setOcupado(false);
    }
  }

  const puede = modo === 'foto' ? !!foto : !!texto.trim();

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Nuevo estado" onClick={onCerrar}>
      <div className="panel editor-estado" onClick={(e) => e.stopPropagation()}>
        <h2>Nuevo estado</h2>
        <div className="pestanas">
          <button className={modo === 'texto' ? 'activa' : ''} onClick={() => setModo('texto')}>Texto</button>
          <button className={modo === 'foto' ? 'activa' : ''} onClick={() => selector.current?.click()}>Foto</button>
        </div>
        <input ref={selector} type="file" accept="image/*" hidden onChange={(e) => elegirFoto(e.target.files?.[0] ?? null)} />

        {modo === 'texto' ? (
          <>
            <div className="lienzo-texto" style={{ background: fondo }}>
              <textarea
                value={texto}
                onChange={(e) => setTexto(e.target.value.slice(0, TOPE_TEXTO_HISTORIA))}
                placeholder="Escribí tu estado…"
                maxLength={TOPE_TEXTO_HISTORIA}
                autoFocus
              />
            </div>
            <div className="colores">
              {FONDOS_HISTORIA.map((c) => (
                <button key={c} className={`color ${c === fondo ? 'activo' : ''}`} style={{ background: c }}
                  onClick={() => setFondo(c)} aria-label={`Fondo ${c}`} />
              ))}
            </div>
          </>
        ) : (
          <>
            {vistaPrevia && <img src={vistaPrevia} alt="" className="foto-previa" />}
            <input value={pie} onChange={(e) => setPie(e.target.value)} placeholder="Pie (opcional)" maxLength={280} />
          </>
        )}

        {error && <p className="error">{error}</p>}
        <div className="acciones-panel">
          <button className="enlace" onClick={onCerrar}>Cancelar</button>
          <button onClick={() => void publicar()} disabled={!puede || ocupado}>{ocupado ? 'Publicando…' : 'Publicar'}</button>
        </div>
      </div>
    </div>
  );
}
