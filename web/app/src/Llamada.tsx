import { useEffect, useState, useSyncExternalStore, type CSSProperties } from 'react';
import { llamadas, aceptar, rechazar, colgar, alternarMute } from './datos/llamadas';

/**
 * La capa de llamada: aparece ENCIMA de todo cuando hay una llamada (entrante,
 * saliente o en curso). No es una ruta: una llamada tiene que verse sobre lo que
 * sea que haya en pantalla y desaparecer sin dejar nada atras.
 */
export function CapaLlamada() {
  const e = useSyncExternalStore(llamadas.suscribir, llamadas.instantanea);
  const [seg, setSeg] = useState(0);

  useEffect(() => {
    if (e.fase !== 'en-curso') {
      setSeg(0);
      return;
    }
    const t = setInterval(() => setSeg(Math.floor((Date.now() - e.desdeMs) / 1000)), 1000);
    return () => clearInterval(t);
  }, [e.fase, e.desdeMs]);

  if (e.fase === 'inactiva') return null;

  const mmss = `${String(Math.floor(seg / 60)).padStart(2, '0')}:${String(seg % 60).padStart(2, '0')}`;
  const titulo =
    e.fase === 'sonando' ? `@${e.conQuien} te llama`
    : e.fase === 'llamando' ? `Llamando a @${e.conQuien}…`
    : `@${e.conQuien}`;
  const sub = e.fase === 'en-curso' ? mmss : 'Llamada de audio';

  return (
    <div style={velo}>
      <div style={tarjeta}>
        <div style={{ fontSize: 22, fontWeight: 700 }}>{titulo}</div>
        <div style={{ color: 'var(--secundario)', marginTop: 8 }}>{sub}</div>
        <div style={{ display: 'flex', gap: 12, marginTop: 28, justifyContent: 'center' }}>
          {e.fase === 'sonando' ? (
            <>
              <button style={btn('#ff6b6b')} onClick={() => void rechazar()}>Rechazar</button>
              <button style={btn('#2bd4c4', '#04302b')} onClick={() => void aceptar()}>Aceptar</button>
            </>
          ) : e.fase === 'en-curso' ? (
            <>
              <button style={btn('#2a3642')} onClick={alternarMute}>
                {e.muteado ? 'Activar micro' : 'Silenciar'}
              </button>
              <button style={btn('#ff6b6b')} onClick={() => void colgar()}>Colgar</button>
            </>
          ) : (
            <button style={btn('#ff6b6b')} onClick={() => void colgar()}>Cancelar</button>
          )}
        </div>
      </div>
    </div>
  );
}

const velo: CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1000,
  background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
};
const tarjeta: CSSProperties = {
  background: 'var(--superficie)', border: '1px solid var(--borde)', borderRadius: 18,
  padding: '28px 24px', width: '100%', maxWidth: 360, textAlign: 'center', color: 'var(--texto)',
};
function btn(bg: string, fg = '#e6edf3'): CSSProperties {
  return { background: bg, color: fg, border: 0, borderRadius: 12, padding: '12px 18px', fontWeight: 700, fontSize: 15, cursor: 'pointer' };
}
