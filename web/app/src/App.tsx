import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent, type KeyboardEvent } from 'react';
import {
  abrirConversacion, cerrarAviso, enviarTexto, motor, salir, titulo, vincular,
  type Conexion, type Conversacion, type Mensaje,
} from './datos/motor';
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
  'otra-pestana': 'Abierta en otra pestaña: úsala allí',
  desvinculado: 'Desvinculado',
};

function Principal() {
  const e = usarMotor();
  const [abierta, setAbierta] = useState<string | null>(null);

  useEffect(() => {
    void abrirConversacion(abierta);
  }, [abierta]);

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

function Chat({ c, mensajes, onAtras }: { c: Conversacion; mensajes: Mensaje[]; onAtras: () => void }) {
  const [borrador, setBorrador] = useState('');
  const fin = useRef<HTMLDivElement>(null);
  const esGrupo = c.tipo === 'grupo';

  useEffect(() => {
    fin.current?.scrollIntoView({ block: 'end' });
  }, [mensajes.length, c.id]);

  function enviar() {
    const t = borrador.trim();
    if (!t) return;
    setBorrador('');
    void enviarTexto(c.id, t);
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
      </header>
      <div className="mensajes" role="log" aria-live="polite">
        {mensajes.map((m) => (
          <div key={m.id} className={`burbuja ${m.esMio ? 'mia' : 'suya'}`}>
            {esGrupo && !m.esMio && <span className="autor">@{m.autor}</span>}
            <span className="texto">{m.texto}</span>
            <span className="pie">
              {hora(m.creadoEn)} <Estado m={m} />
            </span>
            {m.estado === 'fallido' && m.motivo && <span className="motivo">{m.motivo}</span>}
          </div>
        ))}
        <div ref={fin} />
      </div>
      <footer className="escribir">
        <textarea
          value={borrador}
          onChange={(e) => setBorrador(e.target.value)}
          onKeyDown={tecla}
          placeholder="Mensaje"
          rows={1}
          aria-label="Mensaje"
        />
        <button onClick={enviar} disabled={!borrador.trim()}>Enviar</button>
      </footer>
    </>
  );
}
