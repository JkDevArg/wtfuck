import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react';
import {
  llamadas, aceptar, rechazar, colgar, alternarMute, alternarCamara,
  streamLocalActual, streamRemotoActual,
} from './datos/llamadas';

/**
 * La capa de llamada: aparece ENCIMA de todo cuando hay una llamada (entrante,
 * saliente o en curso), de audio o de video.
 */
export function CapaLlamada() {
  const e = useSyncExternalStore(llamadas.suscribir, llamadas.instantanea);
  const [seg, setSeg] = useState(0);
  const refRemoto = useRef<HTMLVideoElement | null>(null);
  const refLocal = useRef<HTMLVideoElement | null>(null);
  const refAudio = useRef<HTMLAudioElement | null>(null);

  useEffect(() => {
    if (e.fase !== 'en-curso') { setSeg(0); return; }
    const t = setInterval(() => setSeg(Math.floor((Date.now() - e.desdeMs) / 1000)), 1000);
    return () => clearInterval(t);
  }, [e.fase, e.desdeMs]);

  // Engancha los streams a los elementos cuando llegan o cambia el modo.
  useEffect(() => {
    const sr = streamRemotoActual();
    const sl = streamLocalActual();
    if (refRemoto.current) refRemoto.current.srcObject = sr;
    if (refLocal.current) refLocal.current.srcObject = sl;
    if (refAudio.current) refAudio.current.srcObject = sr;
  }, [e.fase, e.conVideo, e.remotoListo, e.camaraEncendida]);

  if (e.fase === 'inactiva') return null;

  const mmss = `${String(Math.floor(seg / 60)).padStart(2, '0')}:${String(seg % 60).padStart(2, '0')}`;
  const titulo =
    e.fase === 'sonando' ? `@${e.conQuien} te ${e.conVideo ? 'videollama' : 'llama'}`
    : e.fase === 'llamando' ? `Llamando a @${e.conQuien}…`
    : `@${e.conQuien}`;
  const sub = e.fase === 'en-curso' ? mmss : (e.conVideo ? 'Videollamada' : 'Llamada de audio');

  const videoEnCurso = e.conVideo && e.fase === 'en-curso';

  return (
    <div style={velo}>
      {/* Audio del otro: en audio-only lo reproduce este elemento; en video lo
          reproduce el <video> remoto (y este queda sin stream). */}
      {!videoEnCurso && <audio ref={refAudio} autoPlay />}

      {videoEnCurso ? (
        <div style={videoWrap}>
          <video ref={refRemoto} autoPlay playsInline style={videoRemoto} />
          <video ref={refLocal} autoPlay playsInline muted style={videoLocal} />
          <div style={barraVideo}>
            <button style={btn('#2a3642')} onClick={alternarMute}>{e.muteado ? 'Activar micro' : 'Silenciar'}</button>
            <button style={btn('#2a3642')} onClick={alternarCamara}>{e.camaraEncendida ? 'Apagar cámara' : 'Prender cámara'}</button>
            <button style={btn('#ff6b6b')} onClick={() => void colgar()}>Colgar</button>
          </div>
        </div>
      ) : (
        <div style={tarjeta}>
          <div style={{ fontSize: 22, fontWeight: 700 }}>{titulo}</div>
          <div style={{ color: 'var(--secundario)', marginTop: 8 }}>{sub}</div>
          <div style={{ display: 'flex', gap: 12, marginTop: 28, justifyContent: 'center', flexWrap: 'wrap' }}>
            {e.fase === 'sonando' ? (
              <>
                <button style={btn('#ff6b6b')} onClick={() => void rechazar()}>Rechazar</button>
                <button style={btn('#2bd4c4', '#04302b')} onClick={() => void aceptar()}>Aceptar</button>
              </>
            ) : e.fase === 'en-curso' ? (
              <>
                <button style={btn('#2a3642')} onClick={alternarMute}>{e.muteado ? 'Activar micro' : 'Silenciar'}</button>
                <button style={btn('#ff6b6b')} onClick={() => void colgar()}>Colgar</button>
              </>
            ) : (
              <button style={btn('#ff6b6b')} onClick={() => void colgar()}>Cancelar</button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

const velo: CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1000,
  background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
};
const tarjeta: CSSProperties = {
  background: 'var(--superficie)', border: '1px solid var(--borde)', borderRadius: 18,
  padding: '28px 24px', width: '100%', maxWidth: 360, textAlign: 'center', color: 'var(--texto)',
};
const videoWrap: CSSProperties = {
  position: 'relative', width: '100%', height: '100%', maxWidth: 900, maxHeight: '90vh',
  background: '#000', borderRadius: 14, overflow: 'hidden',
};
const videoRemoto: CSSProperties = { width: '100%', height: '100%', objectFit: 'contain', background: '#000' };
const videoLocal: CSSProperties = {
  position: 'absolute', right: 12, top: 12, width: 'clamp(90px, 22vw, 160px)',
  borderRadius: 10, border: '2px solid rgba(255,255,255,0.3)', background: '#111', transform: 'scaleX(-1)',
};
const barraVideo: CSSProperties = {
  position: 'absolute', left: 0, right: 0, bottom: 0, display: 'flex', gap: 10,
  justifyContent: 'center', flexWrap: 'wrap', padding: 14, background: 'linear-gradient(transparent, rgba(0,0,0,0.6))',
};
function btn(bg: string, fg = '#e6edf3'): CSSProperties {
  return { background: bg, color: fg, border: 0, borderRadius: 12, padding: '12px 18px', fontWeight: 700, fontSize: 15, cursor: 'pointer' };
}
