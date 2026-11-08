import { useEffect, useState } from 'react';
import { buscarGifs, previaGif, enviarGif } from './datos/gifs';
import type { GifResumen } from './datos/protocolo';

/** Buscar y enviar un GIF. Al elegir uno, se baja completo y se manda. */
export function PanelGifs({ conversacionId, onCerrar }: { conversacionId: string; onCerrar: () => void }) {
  const [q, setQ] = useState('');
  const [res, setRes] = useState<GifResumen[]>([]);
  const [aviso, setAviso] = useState('');
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    let vivo = true;
    // Espera a que deje de teclear: una busqueda por cada letra es ruido.
    const t = setTimeout(() => {
      buscarGifs(q)
        .then((b) => { if (vivo) { setRes(b.resultados); setAviso(b.aviso); } })
        .catch(() => { if (vivo) setAviso('No se pudo buscar.'); });
    }, 300);
    return () => { vivo = false; clearTimeout(t); };
  }, [q]);

  async function elegir(id: string) {
    if (enviando) return;
    setEnviando(true);
    try {
      await enviarGif(conversacionId, id);
      onCerrar();
    } catch {
      setAviso('No se pudo enviar el GIF.');
      setEnviando(false);
    }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="GIFs" onClick={onCerrar}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h2>GIFs</h2>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar GIFs" aria-label="Buscar GIFs" autoFocus />
        {aviso && <p className="previa">{aviso}</p>}
        <div className="gifs-grid">
          {res.map((g) => <PreviaGif key={g.id} g={g} onElegir={() => void elegir(g.id)} />)}
        </div>
      </div>
    </div>
  );
}

function PreviaGif({ g, onElegir }: { g: GifResumen; onElegir: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let vivo = true;
    previaGif(g.id).then((u) => { if (vivo) setUrl(u); }).catch(() => {});
    return () => { vivo = false; };
  }, [g.id]);
  return (
    <button className="gif-item" onClick={onElegir} title={g.titulo} aria-label={g.titulo || 'GIF'}>
      {url ? <img src={url} alt="" loading="lazy" /> : <span className="gif-cargando" />}
    </button>
  );
}
