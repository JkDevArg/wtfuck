import { useEffect, useState, type FormEvent } from 'react';
import { ErrorApi } from './datos/api';
import {
  crearInvitacion, misInvitaciones, modoRegistro, pedirCodigoCorreo, recuperarCuenta, registrarse, revocarInvitacion,
  type Invitacion, type ModoRegistro,
} from './datos/motor';

const mensaje = (x: unknown, otro: string) => (x instanceof ErrorApi || x instanceof Error ? x.message : otro);

/** iPhone o iPad en Safari, sin instalar en la pantalla de inicio. */
export function esIosSinInstalar(): boolean {
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const instalada = window.matchMedia?.('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true;
  return ios && !instalada;
}

/** Antes de crear la cuenta en un iPhone: instalarla, o Safari puede borrar las claves. */
export function AvisoIos() {
  if (!esIosSinInstalar()) return null;
  return (
    <div className="aviso-ios" role="note">
      <b>En iPhone, instálala primero.</b> Toca <b>Compartir</b> y luego <b>Agregar a pantalla de inicio</b>, y
      ábrela desde ese ícono. Así recibes avisos con la app cerrada, y Safari no borra tus claves si pasas una
      semana sin abrirla.
    </div>
  );
}

// ---------------------------------------------------------------------------
//  Crear la cuenta
// ---------------------------------------------------------------------------

export function Registro({ etiquetaInicial, onCodigo }: { etiquetaInicial: string; onCodigo: (c: string) => void }) {
  const [invitacion, setInvitacion] = useState(() => new URLSearchParams(location.search).get('invitacion') ?? '');
  const [correo, setCorreo] = useState('');
  const [paso, setPaso] = useState<'correo' | 'cuenta'>('correo');
  const [codigo, setCodigo] = useState('');
  const [usuario, setUsuario] = useState('');
  const [clave, setClave] = useState('');
  const [clave2, setClave2] = useState('');
  const [etiqueta, setEtiqueta] = useState(etiquetaInicial);
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  async function enviarCodigo(ev: FormEvent) {
    ev.preventDefault();
    setOcupado(true);
    setError(null);
    try {
      const r = await pedirCodigoCorreo(correo, 'registro', { codigoInvitacion: invitacion });
      // Solo en el servidor de desarrollo, sin servicio de correo.
      if (r.codigoDePrueba) setCodigo(r.codigoDePrueba);
      setAviso(`Si el correo es válido, te llegó un código de 6 dígitos. Vence en ${Math.round(r.expiraEnSegundos / 60)} minutos.`);
      setPaso('cuenta');
    } catch (x) {
      setError(mensaje(x, 'No se pudo enviar el código.'));
    } finally {
      setOcupado(false);
    }
  }

  async function crear(ev: FormEvent) {
    ev.preventDefault();
    if (clave !== clave2) {
      setError('Las contraseñas no coinciden.');
      return;
    }
    setOcupado(true);
    setError(null);
    try {
      const codigoRecuperacion = await registrarse({
        username: usuario, password: clave, correo, codigoCorreo: codigo, invitacion, etiqueta: etiqueta.trim() || 'Navegador',
      });
      onCodigo(codigoRecuperacion);
    } catch (x) {
      setError(mensaje(x, 'No se pudo crear la cuenta.'));
      setOcupado(false);
    }
  }

  if (paso === 'correo') {
    return (
      <form onSubmit={enviarCodigo}>
        <p className="sub">Para crear una cuenta desde la web hace falta una <b>invitación</b> de alguien que ya usa wtfuck, y un correo.</p>
        <label>Código de invitación
          <input value={invitacion} onChange={(e) => setInvitacion(e.target.value.toUpperCase())} placeholder="XXXXXXXXXXXX" className="codigo" maxLength={16} required />
        </label>
        <label>Correo
          <input type="email" value={correo} onChange={(e) => setCorreo(e.target.value)} placeholder="tu@correo.com" autoComplete="email" required />
        </label>
        <p className="tenue">El correo no se guarda: el servidor guarda solo una huella cifrada, para que no se repita y para recuperar la cuenta.</p>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={ocupado}>{ocupado ? 'Enviando…' : 'Enviarme el código'}</button>
      </form>
    );
  }

  return (
    <form onSubmit={crear}>
      {aviso && <p className="sub">{aviso}</p>}
      <label>Código del correo
        <input value={codigo} onChange={(e) => setCodigo(e.target.value.replace(/\D/g, ''))} inputMode="numeric" className="codigo" maxLength={6} autoComplete="one-time-code" required />
      </label>
      <label>Usuario
        <input value={usuario} onChange={(e) => setUsuario(e.target.value)} placeholder="@usuario" autoComplete="username" minLength={3} maxLength={24} required />
      </label>
      <label>Contraseña
        <input type="password" value={clave} onChange={(e) => setClave(e.target.value)} autoComplete="new-password" minLength={10} required />
      </label>
      <label>Repite la contraseña
        <input type="password" value={clave2} onChange={(e) => setClave2(e.target.value)} autoComplete="new-password" minLength={10} required />
      </label>
      <label>Nombre de este navegador
        <input value={etiqueta} onChange={(e) => setEtiqueta(e.target.value)} maxLength={64} />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit" disabled={ocupado}>{ocupado ? 'Creando la cuenta…' : 'Crear la cuenta'}</button>
      <button type="button" className="enlace" onClick={() => setPaso('correo')}>Cambiar el correo o la invitación</button>
    </form>
  );
}

/** El código de recuperación, una sola vez. No se puede cerrar sin confirmar. */
export function CodigoNuevo({ codigo, onListo }: { codigo: string; onListo: () => void }) {
  const [guardado, setGuardado] = useState(false);
  const [copiado, setCopiado] = useState(false);
  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Tu código de recuperación">
      <div className="panel">
        <h2>Tu código de recuperación</h2>
        <p>
          Es la <b>única</b> forma de recuperar la cuenta si se borran los datos de este navegador o lo pierdes.
          Guárdalo fuera de aquí: en papel o en tu gestor de contraseñas. Nadie más lo tiene, tampoco el servidor.
        </p>
        <p className="codigo-grande">{codigo}</p>
        <button
          className="enlace cian"
          onClick={() => void navigator.clipboard?.writeText(codigo).then(() => setCopiado(true))}
        >
          {copiado ? 'Copiado' : 'Copiar'}
        </button>
        <label className="interruptor">
          <input type="checkbox" checked={guardado} onChange={(e) => setGuardado(e.target.checked)} />
          Ya lo guardé en un lugar seguro
        </label>
        <button onClick={onListo} disabled={!guardado}>Continuar</button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
//  Recuperar una cuenta creada en la web
// ---------------------------------------------------------------------------

export function Recuperar({ etiquetaInicial }: { etiquetaInicial: string }) {
  const [usuario, setUsuario] = useState('');
  const [correo, setCorreo] = useState('');
  const [paso, setPaso] = useState<'correo' | 'codigos'>('correo');
  const [codigoCorreo, setCodigoCorreo] = useState('');
  const [codigoRec, setCodigoRec] = useState('');
  const [clave, setClave] = useState('');
  const [totp, setTotp] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function enviarCodigo(ev: FormEvent) {
    ev.preventDefault();
    setOcupado(true);
    setError(null);
    try {
      const r = await pedirCodigoCorreo(correo, 'recuperar', { username: usuario.trim().replace(/^@/, '') });
      if (r.codigoDePrueba) setCodigoCorreo(r.codigoDePrueba);
      setPaso('codigos');
    } catch (x) {
      setError(mensaje(x, 'No se pudo enviar el código.'));
    } finally {
      setOcupado(false);
    }
  }

  async function recuperar(ev: FormEvent) {
    ev.preventDefault();
    setOcupado(true);
    setError(null);
    try {
      await recuperarCuenta({
        username: usuario, correo, codigoCorreo, codigoRecuperacion: codigoRec, passwordNueva: clave, totp, etiqueta: etiquetaInicial,
      });
    } catch (x) {
      setError(mensaje(x, 'No se pudo recuperar la cuenta.'));
      setOcupado(false);
    }
  }

  if (paso === 'correo') {
    return (
      <form onSubmit={enviarCodigo}>
        <p className="sub">Para una cuenta que creaste en la web. Si la creaste en el teléfono, recupérala desde la app.</p>
        <label>Usuario
          <input value={usuario} onChange={(e) => setUsuario(e.target.value)} placeholder="@usuario" autoComplete="username" required />
        </label>
        <label>Correo de la cuenta
          <input type="email" value={correo} onChange={(e) => setCorreo(e.target.value)} autoComplete="email" required />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={ocupado}>{ocupado ? 'Enviando…' : 'Enviarme el código'}</button>
      </form>
    );
  }
  return (
    <form onSubmit={recuperar}>
      <p className="sub">Si el usuario y el correo coinciden, te llegó un código. Vas a entrar con una clave de seguridad nueva: tus contactos verán el aviso de que cambió, y los mensajes viejos no vuelven.</p>
      <label>Código del correo
        <input value={codigoCorreo} onChange={(e) => setCodigoCorreo(e.target.value.replace(/\D/g, ''))} inputMode="numeric" className="codigo" maxLength={6} autoComplete="one-time-code" required />
      </label>
      <label>Código de recuperación
        <input value={codigoRec} onChange={(e) => setCodigoRec(e.target.value.toUpperCase())} placeholder="XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX" className="codigo" required />
      </label>
      <label>Contraseña nueva
        <input type="password" value={clave} onChange={(e) => setClave(e.target.value)} autoComplete="new-password" minLength={10} required />
      </label>
      <label>Código de dos pasos (si lo tienes activado)
        <input value={totp} onChange={(e) => setTotp(e.target.value)} inputMode="numeric" autoComplete="one-time-code" />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit" disabled={ocupado}>{ocupado ? 'Recuperando…' : 'Recuperar la cuenta en este navegador'}</button>
    </form>
  );
}

/** Si el servidor deja crear cuentas desde la web. */
export function usarModoRegistro(): ModoRegistro | null {
  const [m, setM] = useState<ModoRegistro | null>(null);
  useEffect(() => {
    modoRegistro().then(setM).catch(() => setM({ requiereInvitacion: false, registroWeb: false }));
  }, []);
  return m;
}

// ---------------------------------------------------------------------------
//  Invitar a alguien (sobre todo, a quien tiene iPhone)
// ---------------------------------------------------------------------------

export function Invitaciones({ onCerrar }: { onCerrar: () => void }) {
  const [lista, setLista] = useState<Invitacion[] | null>(null);
  const [disponibles, setDisponibles] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [copiada, setCopiada] = useState<string | null>(null);

  const cargar = () => misInvitaciones().then((r) => { setLista(r.invitaciones); setDisponibles(r.disponibles); }).catch((x) => setError(mensaje(x, 'No se pudieron leer.')));
  useEffect(() => { void cargar(); }, []);

  const enlace = (c: string) => `${location.origin}${import.meta.env.BASE_URL}?invitacion=${c}`;

  async function nueva() {
    setError(null);
    try {
      await crearInvitacion();
      await cargar();
    } catch (x) {
      setError(mensaje(x, 'No se pudo crear.'));
    }
  }

  function estado(i: Invitacion): string {
    if (i.revocada) return 'revocada';
    if (i.usos >= i.usosMax) return 'usada';
    if (i.expiraEn && i.expiraEn < Date.now()) return 'vencida';
    return `vence el ${new Date(i.expiraEn).toLocaleDateString('es-PE', { day: '2-digit', month: '2-digit' })}`;
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label="Invitar a alguien" onClick={onCerrar}>
      <div className="panel" onClick={(e) => e.stopPropagation()}>
        <h2>Invitar a alguien</h2>
        <p className="tenue">
          Para quien no tiene Android (por ejemplo, un iPhone): con tu invitación crea su cuenta desde la web. Cada
          una sirve una sola vez y vence en 7 días. Queda registrado que la invitaste tú.
        </p>
        <button onClick={() => void nueva()} disabled={!disponibles}>
          {disponibles ? `Crear una invitación (te quedan ${disponibles})` : 'No te quedan invitaciones por ahora'}
        </button>
        <ul className="invitaciones">
          {lista?.map((i) => {
            const viva = !i.revocada && i.usos < i.usosMax && (!i.expiraEn || i.expiraEn > Date.now());
            return (
              <li key={i.codigo}>
                <span className="codigo">{i.codigo}</span>
                <span className="tenue">{estado(i)}</span>
                {viva && (
                  <>
                    <button className="enlace cian" onClick={() => void navigator.clipboard?.writeText(enlace(i.codigo)).then(() => setCopiada(i.codigo))}>
                      {copiada === i.codigo ? 'Enlace copiado' : 'Copiar enlace'}
                    </button>
                    <button className="enlace" onClick={() => void revocarInvitacion(i.codigo).then(cargar).catch((x) => setError(mensaje(x, 'No se pudo revocar.')))}>Revocar</button>
                  </>
                )}
              </li>
            );
          })}
          {lista?.length === 0 && <li className="tenue">Todavía no creaste ninguna.</li>}
        </ul>
        {error && <p className="error">{error}</p>}
        <button className="enlace" onClick={onCerrar}>Cerrar</button>
      </div>
    </div>
  );
}
