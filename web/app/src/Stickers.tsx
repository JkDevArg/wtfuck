import { useEffect, useRef, useState } from 'react';
import {
  listarStickers, guardarSticker, borrarSticker, enviarSticker, urlSticker, type StickerGuardado,
} from './datos/stickers';

/** Mis stickers (en este navegador): agregar, enviar y quitar. */
export function PanelStickers({ conversacionId, onCerrar }: { conversacionId: string; onCerrar: () => void }) {
  const [lista, setLista] = useState<StickerGuardado[]>([]);
  const [aviso, setAviso] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const selector = useRef<HTMLInputElement>(null);

  const recargar = () => void listarStickers().then(setLista).catch(() => setAviso('No se pudieron cargar.'));
  useEffect(() => { recargar(); }, []);

  async function subir(files: FileList | null) {
    const f = files?.[0];
    if (f) {
      try { await guardarSticker(f); setAviso(''); recargar(); }
      catch (x) { setAviso((x as Error).message); }
    }
    if (selector.current) selector.current.value = '';
  }
  async function elegir(s: StickerGuardado) {
    if (ocupado) return;
    setOcupado(true);
    try { await enviarSticker(conversacionId, s); onCerrar(); }
    catch { setAviso('No se pudo enviar.'); setOcupado(false); }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Stickers" onClick={onCerrar}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h2>Stickers</h2>
        <input ref={selector} type="file" accept="image/*" hidden onChange={(e) => void subir(e.target.files)} />
        <button onClick={() => selector.current?.click()}>Agregar un sticker</button>
        {aviso && <p className="previa">{aviso}</p>}
        {lista.length === 0 && !aviso && (
          <p className="previa">Todavía no tenés stickers acá. Agregá una imagen para empezar.</p>
        )}
        <div className="gifs-grid">
          {lista.map((s) => (
            <ItemSticker key={s.id} s={s} onElegir={() => void elegir(s)} onQuitar={() => void borrarSticker(s.id).then(recargar)} />
          ))}
        </div>
      </div>
    </div>
  );
}

function ItemSticker({ s, onElegir, onQuitar }: { s: StickerGuardado; onElegir: () => void; onQuitar: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const u = urlSticker(s);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [s]);
  return (
    <div className="gif-item sticker-item">
      <button className="sticker-enviar" onClick={onElegir} title={s.nombre} aria-label="Enviar sticker">
        {url && <img src={url} alt="" />}
      </button>
      <button className="sticker-x" onClick={onQuitar} aria-label="Quitar sticker">×</button>
    </div>
  );
}
