-- ============================================================
--  V20: presencia y el resto de los ajustes de privacidad (L.1)
-- ============================================================
--
-- ## El problema que arregla, y es de honestidad
--
-- La cabecera del chat decia "conectado" **siempre**, para cualquiera, porque
-- no habia ningun dato de presencia detras: era un adorno con forma de
-- informacion. Peor que no decir nada, porque la gente lo lee y decide cosas
-- con eso ("esta en linea y no me contesta").
--
-- Ahora hay presencia de verdad, y por eso mismo hace falta poder apagarla:
-- saber cuando alguien estuvo conectado por ultima vez es justo el dato que
-- mas se usa para vigilar a una pareja o a un hijo.
--
-- ## La reciprocidad no es un detalle
--
-- Quien oculta su ultima conexion **tampoco la ve** de los demas. Sin esa
-- regla, el ajuste se convierte en un espejo de una sola direccion: ver sin ser
-- visto. Se aplica en el servidor, no ocultando el texto en la pantalla.

-- Cuando estuvo activo por ultima vez. Se actualiza al conectar y al
-- desconectar el socket, no en cada mensaje: escribir una fila por mensaje
-- seria un contador de escrituras gratis para nada.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS ultima_vez timestamptz;

-- ¿Quien ve mi ultima conexion y si estoy en linea?
--
-- Un solo ajuste para las dos cosas a proposito: separarlos da la combinacion
-- absurda de "no ves cuando estuve, pero si que estoy ahora", que no protege
-- nada y multiplica las pantallas.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_ultima_vez text NOT NULL DEFAULT 'conocidos';

-- ¿Quien ve mi nombre mostrado? Con `nadie`, los desconocidos ven el username.
--
-- El username NO se puede ocultar: es la direccion con la que existis en la
-- plataforma, como el numero en otras apps. Lo que se puede ocultar es el
-- nombre de verdad, que es el dato personal.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_nombre text NOT NULL DEFAULT 'todos';

-- ¿Quien me encuentra buscando mi username?
--
-- `nadie` no te vuelve inalcanzable: quien ya tiene conversacion contigo sigue
-- escribiendote, y un enlace de invitacion a un grupo sigue funcionando. Lo
-- que deja de funcionar es que un desconocido te encuentre tecleando tu
-- nombre.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_busqueda text NOT NULL DEFAULT 'todos';

-- ¿Mando confirmaciones de lectura?
--
-- Es booleano y no de tres niveles porque es **reciproco**: si lo apagas, no
-- mandas los tuyos y tampoco ves los de los demas. Un nivel "conocidos" aqui
-- solo serviria para ver sin ser visto dentro de un subconjunto.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_lectura boolean NOT NULL DEFAULT true;

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_ultima_vez_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_ultima_vez_valido
    CHECK (priv_ultima_vez IN ('todos', 'conocidos', 'nadie'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_nombre_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_nombre_valido
    CHECK (priv_nombre IN ('todos', 'conocidos', 'nadie'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_busqueda_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_busqueda_valido
    CHECK (priv_busqueda IN ('todos', 'conocidos', 'nadie'));


-- ============================================================
--  Confirmaciones de lectura
-- ============================================================
--
-- El acuse de LECTURA no puede ser el mismo que el de ENTREGA. El de entrega
-- borra el sobre del buzon -es lo que hace que el servidor no acumule
-- historial- y ocurre cuando el mensaje llega al aparato. El de lectura ocurre
-- cuando la persona ABRE el chat, que puede ser horas despues o nunca.
--
-- Por eso se guarda aparte y solo el hecho: quien leyo que mensaje y cuando.
-- Ni una palabra del contenido, que sigue siendo opaco para el servidor.
CREATE TABLE IF NOT EXISTS lectura (
    mensaje_id  uuid NOT NULL REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    leido_en    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (mensaje_id, usuario_id)
);

CREATE INDEX IF NOT EXISTS lectura_por_mensaje ON lectura (mensaje_id);


-- ============================================================
--  El aviso de lectura
-- ============================================================
ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion',
        'canal_publicacion',
        'advertencia', 'sancion',
        'sesion_revocada',
        'dispositivo_vinculado', 'dispositivo_revocado',
        'historial_pedido',
        'llamada_entrante', 'llamada_contestada', 'llamada_terminada',
        'canal_aprobado', 'canal_rechazado',
        'conversacion_cerrada', 'conversacion_reabierta',

        -- L.1: alguien leyo mi mensaje.
        'mensaje_leido'
    )
);
