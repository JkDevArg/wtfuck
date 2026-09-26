-- Modulo BC · Invitaciones para registrarse.
--
-- Hasta aqui el registro era abierto: quien tuviera el APK se creaba una
-- cuenta. Para un despliegue publico eso esta bien; para uno de un equipo o un
-- laboratorio, no, y la unica alternativa era no repartir el APK.
--
-- Se controla con `WTFUCK_REGISTRO`: `abierto` por defecto —para no cambiar el
-- comportamiento de nadie al actualizar— e `invitacion` para cerrarlo.
--
-- El nombre lleva `_registro` porque `invitacion` a secas YA EXISTE y es otra
-- cosa: la de entrar a una conversacion. Dos tablas con el mismo nombre para
-- dos permisos distintos es como se acaba autorizando lo que no era.

CREATE TABLE invitacion_registro (
    -- El codigo ES la clave. No hay un id aparte: el codigo ya es unico, y un
    -- id extra solo daria dos formas de nombrar la misma fila.
    codigo       text PRIMARY KEY,

    creada_por   uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    creada_en    timestamptz NOT NULL DEFAULT now(),

    -- NULL = no caduca. Es lo que quiere quien reparte un enlace y no sabe
    -- cuando lo van a abrir; el resto del tiempo conviene poner fecha.
    expira_en    timestamptz,

    -- Cuantas cuentas puede crear. 1 para un codigo personal, N para uno que
    -- se pega en un canal.
    usos_max     integer NOT NULL DEFAULT 1,
    usos         integer NOT NULL DEFAULT 0,

    -- Revocar no borra: un codigo borrado se lleva por delante el rastro de
    -- quien entro con el, que es justo lo que hace falta conservar cuando se
    -- revoca algo.
    revocada_en  timestamptz,

    -- Para quien era. Lo escribe quien la crea y solo lo ve el staff.
    nota         text NOT NULL DEFAULT '',

    CONSTRAINT invitacion_usos_max_positivo CHECK (usos_max >= 1),
    -- Que `usos` no pueda pasar de `usos_max` lo garantiza la base y no el
    -- codigo: dos personas canjeando el ultimo uso a la vez es una carrera
    -- real, y una comprobacion en Kotlin no la cubre.
    CONSTRAINT invitacion_usos_coherentes CHECK (usos >= 0 AND usos <= usos_max)
);

CREATE INDEX idx_invitacion_registro_creador ON invitacion_registro (creada_por, creada_en DESC);

-- Quien entro con cada codigo.
--
-- Separado y no un contador porque la pregunta util no es "cuantos la usaron"
-- sino "quien". Cuando hay que revisar una cuenta, saber quien la invito es la
-- mitad del trabajo.
CREATE TABLE invitacion_uso (
    codigo     text NOT NULL REFERENCES invitacion_registro(codigo) ON DELETE CASCADE,
    usuario_id uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    usada_en   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (codigo, usuario_id)
);

CREATE INDEX idx_invitacion_uso_usuario ON invitacion_uso (usuario_id);
