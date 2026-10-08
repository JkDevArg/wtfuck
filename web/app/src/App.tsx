import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent, type KeyboardEvent } from 'react';
import {
  abrirConversacion, avisosConQuien, cerrarAviso, cuandoSeToqueUnAviso, descargar, editar, enviarArchivo, enviarTexto,
  huellasDe, identidadRevisada, motor, pedirPermisoDeAvisos, reaccionar, retirar, salir, titulo, vincular,
  type Conexion, type Conversacion, type Mensaje,
} from './datos/motor';
import { tamanoLegible } from './datos/archivos';
import { ErrorApi } from './datos/api';

const usarMotor = () => useSyncExternalStore(motor.suscribir, motor.instantanea);

export function App() {
  const e = usarMotor();
  return (
    <>
      {e.aviso && (
        <div className="aviso" role="status">
          <span>{e.aviso}</span>
          <button onClick={cerrarAviso} aria-label="Cerrar aviso">Entendido</button>
        </div>
      )}
      {e.sesion ? <Principal /> : <Vincular />}
    </>
  );
}

// ---------------------------------------------------------------------------
//  Vincular
// ---------------------------------------------------------------------------

function nombreDelNavegador(): string {
  const ua = navigator.userAgent;
  const nav = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Navegador';
  const so = /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : '';
  return so ? `${nav} en ${so}` : nav;
}

function Vincular() {
  const [usuario, setUsuario] = useState('');
  const [codigo, setCodigo] = useState('');
  const [etiqueta, setEtiqueta] = useState(nombreDelNavegador);
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function enviar(ev: FormEvent) {
    ev.preventDefault();
    setOcupado(true);
    setError(null);
    try {
      await vincular(usuario, codigo, etiqueta.trim() || 'Navegador');
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo vincular. Revisa la conexión.');
    } finally {
      setOcupado(false);
    }
  }

  return (
    <main className="vincular">
      <h1 className="marca">wtfuck</h1>
      <p className="sub">Usa tu cuenta en este navegador, vinculándolo desde tu teléfono.</p>
      <ol className="pasos">
        <li>En el teléfono: <b>Perfil → Cuenta y seguridad → Ver mis dispositivos → Generar código</b>.</li>
        <li>Escribe aquí tu usuario y ese código. Vive cinco minutos.</li>
      </ol>
      <form onSubmit={enviar}>
        <label>Usuario
          <input value={usuario} onChange={(e) => setUsuario(e.target.value)} placeholder="@usuario" autoComplete="username" required />
        </label>
        <label>Código de vinculación
          <input
            value={codigo}
            onChange={(e) => setCodigo(e.target.value.toUpperCase())}
            placeholder="XXXX-XXXX"
            className="codigo"
            maxLength={9}
            autoComplete="one-time-code"
            required
          />
        </label>
        <label>Nombre de este navegador
          <input value={etiqueta} onChange={(e) => setEtiqueta(e.target.value)} maxLength={64} />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={ocupado}>{ocupado ? 'Vinculando…' : 'Vincular este navegador'}</button>
      </form>
      <Confianza />
    </main>
  );
}

/** El precio de la web, dicho donde se decide usarla. Ver docs/12-VERSION-WEB.md. */
function Confianza() {
  return (
    <details className="confianza">
      <summary>Qué cambia al usar la versión web</summary>
      <p>
        Los mensajes van cifrados de punta a punta igual que en el teléfono, y las claves de este navegador
        se guardan cifradas en él. La diferencia: en el navegador, el código de la página lo entrega el
        servidor cada vez que la abres. Si alguien tomara el servidor, podría entregar otro. En el teléfono
        eso no pasa, porque la app va firmada.
      </p>
      <p>
        La sesión de este navegador dura 30 días, nunca puede ser tu aparato principal y la puedes revocar
        desde el teléfono cuando quieras.
      </p>
    </details>
  );
}

// ---------------------------------------------------------------------------
//  Lista y chat
// ---------------------------------------------------------------------------

const TEXTO_CONEXION: Record<Conexion, string> = {
  'sin-vincular': '',
  conectando: 'Conectando…',
  'en-linea': 'En línea',
  'sin-red': 'Sin conexión: lo que escribas sale al volver',
  'otra-pestana': 'En línea, a través de otra pestaña',
  desvinculado: 'Desvinculado',
};

function Principal() {
  const e = usarMotor();
  const [abierta, setAbierta] = useState<string | null>(null);

  useEffect(() => {
    void abrirConversacion(abierta);
  }, [abierta]);

  // Tocar un aviso del navegador abre ese chat.
  useEffect(() => cuandoSeToqueUnAviso(setAbierta), []);

  const conv = e.conversaciones.find((c) => c.id === abierta) ?? null;

  return (
    <div className={`principal ${abierta ? 'con-chat' : ''}`}>
      <aside className="lista">
        <header>
          <span className="marca">wtfuck</span>
          <span className={`conexion ${e.conexion}`}>{TEXTO_CONEXION[e.conexion]}</span>
        </header>
        <div className="yo">
          @{e.sesion!.username} · {e.sesion!.etiqueta}
          <button className="enlace" onClick={() => { if (confirm('¿Desvincular este navegador? Se borra todo lo guardado aquí.')) void salir(); }}>
            Desvincular
          </button>
        </div>
        <Avisos />
        <ul>
          {e.conversaciones.filter((c) => !c.esSolicitud).map((c) => (
            <FilaChat key={c.id} c={c} activa={c.id === abierta} onAbrir={() => setAbierta(c.id)} />
          ))}
          {!e.conversaciones.length && <li className="vacio">Todavía no hay chats.</li>}
        </ul>
      </aside>
      <section className="chat">
        {conv ? <Chat c={conv} mensajes={e.mensajes[conv.id] ?? []} onAtras={() => setAbierta(null)} /> : (
          <div className="sin-chat">
            <p>Elige un chat.</p>
            <Confianza />
          </div>
        )}
      </section>
    </div>
  );
}

/** El permiso de avisos del navegador, y si dicen quién escribió. Nunca el texto. */
function Avisos() {
  const { avisos } = usarMotor();
  if (avisos.permiso === 'sin-soporte' || avisos.permiso === 'denied') return null;
  if (avisos.permiso === 'default') {
    return (
      <div className="avisos">
        <button className="enlace cian" onClick={() => void pedirPermisoDeAvisos()}>Activar avisos de mensajes nuevos</button>
      </div>
    );
  }
  return (
    <label className="avisos interruptor">
      <input type="checkbox" checked={avisos.mostrarQuien} onChange={(e) => avisosConQuien(e.target.checked)} />
      Los avisos dicen quién escribió (nunca el texto)
    </label>
  );
}

function hora(ms: number): string {
  const d = new Date(ms);
  const hoy = new Date();
  return d.toDateString() === hoy.toDateString()
    ? d.toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('es-PE', { day: '2-digit', month: '2-digit' });
}

function FilaChat({ c, activa, onAbrir }: { c: Conversacion; activa: boolean; onAbrir: () => void }) {
  const t = titulo(c);
  return (
    <li>
      <button className={`fila ${activa ? 'activa' : ''}`} onClick={onAbrir}>
        <span className="avatar" aria-hidden="true">{t.slice(0, 2).toUpperCase()}</span>
        <span className="cuerpo">
          <span className="arriba">
            <span className="nombre">{t}</span>
            {c.ultimo && <span className="cuando">{hora(c.ultimo.creadoEn)}</span>}
          </span>
          <span className="abajo">
            <span className="previa">{c.ultimo ? (c.ultimo.esMio ? 'Tú: ' : '') + c.ultimo.texto : c.tipo === 'grupo' ? 'Grupo' : ''}</span>
            {c.noLeidos > 0 && <span className="globo">{c.noLeidos}</span>}
          </span>
        </span>
      </button>
    </li>
  );
}

function Estado({ m }: { m: Mensaje }) {
  if (!m.esMio) return null;
  if (m.estado === 'fallido') return <span className="estado fallido" title={m.motivo}>No salió</span>;
  if (m.estado === 'pendiente') return <span className="estado" title="En cola">◷</span>;
  const dos = m.estado === 'entregado' || m.estado === 'leido';
  return (
    <span className={`estado ${m.estado === 'leido' ? 'leido' : ''}`} title={m.estado}>
      <svg width="16" height="10" viewBox="0 0 16 10" aria-hidden="true">
        <path d="M1 5l3 3 6-7" fill="none" stroke="currentColor" strokeWidth="1.6" />
        {dos && <path d="M6 8l1 1 7-8" fill="none" stroke="currentColor" strokeWidth="1.6" />}
      </svg>
    </span>
  );
}

/** Los 60 dígitos con cada aparato del otro, para leerlos en voz alta. */
function Huellas({ c, onCerrar }: { c: Conversacion; onCerrar: () => void }) {
  const [lista, setLista] = useState<{ username: string; etiqueta: string; digitos: string }[] | null>(null);
  useEffect(() => {
    void huellasDe(c.id).then(setLista).catch(() => setLista([]));
  }, [c.id]);
  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Verificar el cifrado" onClick={onCerrar}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h2>Verificar el cifrado</h2>
        <p className="tenue">
          Compárenla en persona o por teléfono. Si coincide con la que ve el otro, nadie está en el medio. Hay
          una por cada aparato.
        </p>
        {lista === null && <p className="tenue">Calculando…</p>}
        {lista?.length === 0 && <p className="tenue">Todavía no hay sesión con ningún aparato de este chat.</p>}
        {lista?.map((h) => (
          <div key={h.username + h.etiqueta} className="huella">
            <span className="quien">@{h.username}{h.etiqueta ? ` · ${h.etiqueta}` : ''}</span>
            <code>{h.digitos.match(/.{5}/g)?.join(' ')}</code>
          </div>
        ))}
        <button onClick={onCerrar}>Cerrar</button>
      </div>
    </div>
  );
}

/**
 * Una imagen que termina de cargar agranda el chat: si quien lee estaba al
 * final, que siga al final. Si había subido a leer algo, no se lo mueve.
 */
function seguirAbajo(e: { currentTarget: HTMLElement }) {
  const lista = e.currentTarget.closest('.mensajes');
  if (lista && lista.scrollHeight - lista.scrollTop - lista.clientHeight < 600) lista.scrollTop = lista.scrollHeight;
}

/** Las mismas reacciones rápidas que la app (AccionesMensaje.kt). */
const REACCIONES = ['👍', '❤️', '😂', '😮', '😢', '🙏'];

function Adjunto({ m }: { m: Mensaje }) {
  const { archivos } = usarMotor();
  const a = m.adjunto!;
  const f = archivos[m.id];
  const [destapado, setDestapado] = useState(!a.spoiler);
  const esImagen = a.clase === 'imagen' || a.clase === 'sticker';

  // Las imágenes se bajan solas al verse; lo demás, al tocar. Como la app con
  // WiFi: las fotos sí, los videos y documentos no.
  useEffect(() => {
    if (esImagen && !a.unaVez && !f && a.bytes <= 16 * 1024 * 1024) void descargar(m);
  }, [esImagen, a.unaVez, f, a.bytes, m]);

  if (a.unaVez) {
    return <div className="adjunto archivo"><span>Ver una vez: ábrelo en el teléfono.</span></div>;
  }
  const proporcion = a.ancho && a.alto ? Math.min(1.8, Math.max(0.6, a.ancho / a.alto)) : 1.3;
  if (esImagen) {
    const src = f?.url ?? (a.miniatura ? `data:image/jpeg;base64,${a.miniatura}` : undefined);
    return (
      <button className={`adjunto imagen ${destapado ? '' : 'spoiler'}`} style={{ aspectRatio: String(proporcion) }}
        onClick={() => (destapado ? f?.url && window.open(f.url, '_blank', 'noopener') : setDestapado(true))}
        aria-label={destapado ? 'Abrir la foto' : 'Spoiler: toca para ver'}>
        {src && <img src={src} alt="" onLoad={seguirAbajo} />}
        {!destapado && <span className="velo-texto">Spoiler · toca para ver</span>}
        {f?.estado === 'bajando' && <span className="velo-texto">Bajando…</span>}
        {f?.estado === 'error' && <span className="velo-texto error">{f.error}</span>}
      </button>
    );
  }
  if ((a.clase === 'video' || a.clase === 'audio' || a.clase === 'nota_voz') && f?.estado === 'listo') {
    return a.clase === 'video'
      ? <video className="adjunto video" src={f.url} controls style={{ aspectRatio: String(proporcion) }} />
      : <audio className="adjunto" src={f.url} controls />;
  }
  return (
    <div className="adjunto archivo">
      <span className="nombre-archivo">{a.nombre}</span>
      <span className="tenue">{tamanoLegible(a.bytes)}</span>
      {f?.estado === 'listo' ? (
        <a className="enlace cian" href={f.url} download={a.nombre}>Guardar</a>
      ) : f?.estado === 'bajando' ? (
        <span className="tenue">Bajando…</span>
      ) : (
        <button className="enlace cian" onClick={() => void descargar(m)}>
          {a.clase === 'video' || a.clase === 'audio' || a.clase === 'nota_voz' ? 'Reproducir' : 'Descargar'}
        </button>
      )}
      {f?.estado === 'error' && <span className="error">{f.error}</span>}
    </div>
  );
}

function Burbuja({ m, esGrupo, onResponder, onEditar }: {
  m: Mensaje; esGrupo: boolean; onResponder: () => void; onEditar: () => void;
}) {
  const [eligiendo, setEligiendo] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const intentar = (f: () => Promise<void>) => void f().catch((x: Error) => setAviso(x.message));
  if (m.retirado) {
    return <div className={`burbuja ${m.esMio ? 'mia' : 'suya'} retirada`}><span className="texto">Mensaje eliminado</span></div>;
  }
  return (
    <div className={`burbuja ${m.esMio ? 'mia' : 'suya'}`}>
      {esGrupo && !m.esMio && <span className="autor">@{m.autor}</span>}
      {m.cita && (
        <span className="cita"><b>@{m.cita.autor}</b><span>{m.cita.texto}</span></span>
      )}
      {m.adjunto && <Adjunto m={m} />}
      {m.texto && <span className="texto">{m.texto}</span>}
      <span className="pie">
        {m.editado && <span>editado</span>}
        {hora(m.creadoEn)} <Estado m={m} />
      </span>
      {m.estado === 'fallido' && m.motivo && <span className="motivo">{m.motivo}</span>}
      {!!m.reacciones?.length && (
        <span className="reacciones">
          {m.reacciones.map((r) => (
            <button key={r.emoji} className={r.mia ? 'mia' : ''} title={r.quienes.map((q) => '@' + q).join(', ')}
              onClick={() => intentar(() => reaccionar(m, r.emoji))}>
              {r.emoji} {r.total}
            </button>
          ))}
        </span>
      )}
      {m.estado !== 'pendiente' && m.estado !== 'fallido' && (
        <span className="acciones-msj">
          <button onClick={onResponder}>Responder</button>
          <button onClick={() => setEligiendo((x) => !x)}>Reaccionar</button>
          {m.esMio && !m.adjunto && <button onClick={onEditar}>Editar</button>}
          {m.esMio && (
            <button className="peligro" onClick={() => {
              if (confirm('¿Eliminar este mensaje para todos? No se puede deshacer.')) intentar(() => retirar(m));
            }}>Eliminar</button>
          )}
        </span>
      )}
      {eligiendo && (
        <span className="reacciones elegir">
          {REACCIONES.map((e) => (
            <button key={e} onClick={() => { setEligiendo(false); intentar(() => reaccionar(m, e)); }}>{e}</button>
          ))}
        </span>
      )}
      {aviso && <span className="motivo">{aviso}</span>}
    </div>
  );
}

function Chat({ c, mensajes, onAtras }: { c: Conversacion; mensajes: Mensaje[]; onAtras: () => void }) {
  const [borrador, setBorrador] = useState('');
  const [verHuellas, setVerHuellas] = useState(false);
  const [citado, setCitado] = useState<Mensaje | null>(null);
  const [editando, setEditando] = useState<Mensaje | null>(null);
  const [errorArchivo, setErrorArchivo] = useState<string | null>(null);
  const fin = useRef<HTMLDivElement>(null);
  const caja = useRef<HTMLTextAreaElement>(null);
  const selector = useRef<HTMLInputElement>(null);
  const esGrupo = c.tipo === 'grupo';

  useEffect(() => {
    fin.current?.scrollIntoView({ block: 'end' });
  }, [mensajes.length, c.id]);

  useEffect(() => {
    setCitado(null);
    setEditando(null);
  }, [c.id]);

  function enviar() {
    const t = borrador.trim();
    if (!t) return;
    setBorrador('');
    if (editando) {
      const m = editando;
      setEditando(null);
      void editar(m, t).catch((x: Error) => setErrorArchivo(x.message));
      return;
    }
    void enviarTexto(c.id, t, citado ?? undefined);
    setCitado(null);
  }

  function elegirArchivo(fs: FileList | null) {
    const f = fs?.[0];
    if (!f) return;
    setErrorArchivo(null);
    const pie = borrador.trim();
    setBorrador('');
    void enviarArchivo(c.id, f, pie).catch((x: Error) => setErrorArchivo(x.message));
    if (selector.current) selector.current.value = '';
  }

  function tecla(ev: KeyboardEvent<HTMLTextAreaElement>) {
    if (ev.key === 'Enter' && !ev.shiftKey) {
      ev.preventDefault();
      enviar();
    }
  }

  return (
    <>
      <header className="cabecera">
        <button className="atras" onClick={onAtras} aria-label="Volver a la lista">←</button>
        <span className="nombre">{titulo(c)}</span>
        {esGrupo && <span className="miembros">{c.participantes.length + 1} miembros</span>}
        <button className="enlace cian derecha" onClick={() => setVerHuellas(true)}>Verificar cifrado</button>
      </header>
      {!!c.identidadCambio?.length && (
        <div className="cambio" role="alert">
          <span>
            La clave de seguridad de {c.identidadCambio.map((d) => `@${d.username}`).join(', ')} cambió. Puede
            ser que reinstaló la app o vinculó otro aparato; si no lo esperaban, comparen la huella.
          </span>
          <span className="acciones">
            <button className="enlace cian" onClick={() => setVerHuellas(true)}>Ver huella</button>
            <button className="enlace" onClick={() => void identidadRevisada(c.id)}>Entendido</button>
          </span>
        </div>
      )}
      {verHuellas && <Huellas c={c} onCerrar={() => setVerHuellas(false)} />}
      <div className="mensajes" role="log" aria-live="polite">
        {mensajes.map((m) => (
          <Burbuja key={m.id} m={m} esGrupo={esGrupo}
            onResponder={() => { setEditando(null); setCitado(m); caja.current?.focus(); }}
            onEditar={() => { setCitado(null); setEditando(m); setBorrador(m.texto); caja.current?.focus(); }} />
        ))}
        <div ref={fin} />
      </div>
      {(citado || editando || errorArchivo) && (
        <div className="contexto">
          {citado && <span>Respondiendo a <b>@{citado.autor}</b>: {(citado.texto || citado.adjunto?.nombre || '').slice(0, 80)}</span>}
          {editando && <span>Editando tu mensaje</span>}
          {errorArchivo && <span className="error">{errorArchivo}</span>}
          <button className="enlace" onClick={() => { setCitado(null); setEditando(null); setErrorArchivo(null); if (editando) setBorrador(''); }}>Cancelar</button>
        </div>
      )}
      <footer className="escribir">
        <input ref={selector} type="file" hidden onChange={(e) => elegirArchivo(e.target.files)} />
        <button className="adjuntar" onClick={() => selector.current?.click()} aria-label="Adjuntar un archivo" title="Adjuntar (el texto escrito va como pie)" disabled={!!editando}>
          <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M21 11.5l-8.6 8.6a5.5 5.5 0 0 1-7.8-7.8l8.6-8.6a3.7 3.7 0 0 1 5.2 5.2l-8.6 8.6a1.8 1.8 0 0 1-2.6-2.6L15 7" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
        </button>
        <textarea
          ref={caja}
          value={borrador}
          onChange={(e) => setBorrador(e.target.value)}
          onKeyDown={tecla}
          placeholder="Mensaje"
          rows={1}
          aria-label="Mensaje"
        />
        <button onClick={enviar} disabled={!borrador.trim()}>{editando ? 'Guardar' : 'Enviar'}</button>
      </footer>
    </>
  );
}
