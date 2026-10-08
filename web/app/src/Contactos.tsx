import { useEffect, useState } from 'react';
import { ErrorApi } from './datos/api';
import { buscarPersonas, crearGrupo, empezarChat, personaExacta } from './datos/motor';
import type { UsuarioPublico } from './datos/protocolo';

const mensaje = (x: unknown, otro: string) => (x instanceof ErrorApi || x instanceof Error ? x.message : otro);

/**
 * Buscar a alguien y empezar un chat, o armar un grupo. Lo que se puede y lo
 * que no lo decide el servidor con las mismas reglas que la app: quién sale en
 * el directorio, quién acepta mensajes de desconocidos (si no, el chat nace
 * como solicitud), los bloqueos y quién te puede agregar a un grupo.
 */
export function NuevoChat({ onCerrar, onAbrir }: { onCerrar: () => void; onAbrir: (id: string) => void }) {
  const [modo, setModo] = useState<'chat' | 'grupo'>('chat');
  const [q, setQ] = useState('');
  const [lista, setLista] = useState<UsuarioPublico[] | null>(null);
  const [elegidos, setElegidos] = useState<UsuarioPublico[]>([]);
  const [nombre, setNombre] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(async () => {
      try {
        const texto = q.trim().replace(/^@/, '');
        if (texto.length < 2) {
          setLista(texto ? [] : await buscarPersonas(''));
          return;
        }
        const [delDirectorio, exacta] = await Promise.all([buscarPersonas(texto), personaExacta(texto)]);
        // El usuario exacto primero: puede no salir en el directorio (ajuste de
        // privacidad), pero si sabes su usuario lo puedes encontrar.
        const todos = exacta ? [exacta, ...delDirectorio.filter((u) => u.usuarioId !== exacta.usuarioId)] : delDirectorio;
        setLista(todos);
      } catch (x) {
        setError(mensaje(x, 'No se pudo buscar.'));
      }
    }, 250);
    return () => clearTimeout(t);
  }, [q]);

  async function chatCon(u: UsuarioPublico) {
    setOcupado(true);
    setError(null);
    try {
      onAbrir(await empezarChat(u.username));
    } catch (x) {
      setError(mensaje(x, 'No se pudo empezar el chat.'));
      setOcupado(false);
    }
  }

  function alternar(u: UsuarioPublico) {
    setElegidos((l) => (l.some((x) => x.usuarioId === u.usuarioId) ? l.filter((x) => x.usuarioId !== u.usuarioId) : [...l, u]));
  }

  async function grupo() {
    setOcupado(true);
    setError(null);
    try {
      onAbrir(await crearGrupo(nombre, elegidos.map((u) => u.username)));
    } catch (x) {
      setError(mensaje(x, 'No se pudo crear el grupo.'));
      setOcupado(false);
    }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Nuevo chat" onClick={onCerrar}>
      <div className="panel nuevo-chat" onClick={(e) => e.stopPropagation()}>
        <div className="pestanas" role="tablist">
          <button role="tab" aria-selected={modo === 'chat'} className={modo === 'chat' ? 'activa' : ''} onClick={() => setModo('chat')}>Nuevo chat</button>
          <button role="tab" aria-selected={modo === 'grupo'} className={modo === 'grupo' ? 'activa' : ''} onClick={() => setModo('grupo')}>Nuevo grupo</button>
        </div>
        {modo === 'grupo' && (
          <>
            <input value={nombre} onChange={(e) => setNombre(e.target.value)} placeholder="Nombre del grupo" maxLength={64} aria-label="Nombre del grupo" />
            {!!elegidos.length && (
              <div className="elegidos">
                {elegidos.map((u) => <button key={u.usuarioId} className="chip" onClick={() => alternar(u)} aria-label={`Quitar a @${u.username}`}>@{u.username} ×</button>)}
              </div>
            )}
          </>
        )}
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar por @usuario o nombre" aria-label="Buscar personas" autoFocus />
        <div className="canales">
          {lista?.map((u) => {
            const elegido = elegidos.some((x) => x.usuarioId === u.usuarioId);
            return (
              <button
                key={u.usuarioId}
                className={`fila ${elegido ? 'activa' : ''}`}
                onClick={() => (modo === 'chat' ? void chatCon(u) : alternar(u))}
                disabled={ocupado}
              >
                <span className="avatar" aria-hidden="true">{(u.nombreMostrado || u.username).slice(0, 2).toUpperCase()}</span>
                <span className="cuerpo">
                  <span className="arriba"><span className="nombre">{u.nombreMostrado || '@' + u.username}</span>{u.enLinea && <span className="cuando">en línea</span>}</span>
                  <span className="abajo"><span className="previa">@{u.username}</span>{elegido && <span className="globo">✓</span>}</span>
                </span>
              </button>
            );
          })}
          {lista?.length === 0 && <p className="tenue">{q.trim().length >= 2 ? 'Nadie con ese usuario o nombre.' : 'Escribe al menos dos letras.'}</p>}
        </div>
        {error && <p className="error">{error}</p>}
        <div className="acciones-panel">
          <button className="enlace" onClick={onCerrar}>Cerrar</button>
          {modo === 'grupo' && (
            <button onClick={() => void grupo()} disabled={ocupado || !nombre.trim() || !elegidos.length}>
              {ocupado ? 'Creando…' : `Crear grupo (${elegidos.length})`}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
