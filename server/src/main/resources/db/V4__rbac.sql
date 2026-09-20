-- wtfuck V4 - RBAC, bloqueos, restricciones y auditoria
--
-- Regla de oro del modulo: el cliente NUNCA decide. Los permisos se resuelven
-- en el servidor en cada peticion y JAMAS viajan dentro del token: si viajaran,
-- un administrador degradado seguiria mandando hasta que su token expire.

-- ============================================================
--  Catalogo de permisos
-- ============================================================

CREATE TABLE permiso (
    clave       text PRIMARY KEY,
    descripcion text NOT NULL,
    -- conversacion | canal | global
    ambito      text NOT NULL,
    CONSTRAINT ambito_permiso_valido CHECK (ambito IN ('conversacion','canal','global'))
);

INSERT INTO permiso (clave, descripcion, ambito) VALUES
    ('mensaje.enviar',          'Enviar mensajes',                    'conversacion'),
    ('mensaje.responder',       'Responder mensajes',                 'conversacion'),
    ('mensaje.reaccionar',      'Reaccionar a mensajes',              'conversacion'),
    ('mensaje.editar',          'Editar sus propios mensajes',        'conversacion'),
    ('mensaje.borrar_propio',   'Eliminar sus propios mensajes',      'conversacion'),
    ('mensaje.borrar_ajeno',    'Eliminar mensajes de otros',         'conversacion'),
    ('mensaje.fijar',           'Fijar mensajes',                     'conversacion'),
    ('mensaje.reenviar',        'Reenviar mensajes',                  'conversacion'),
    ('mensaje.enviar_enlace',   'Enviar enlaces',                     'conversacion'),
    ('media.imagen',            'Enviar imagenes',                    'conversacion'),
    ('media.video',             'Enviar videos',                      'conversacion'),
    ('media.audio',             'Enviar audios',                      'conversacion'),
    ('media.documento',         'Enviar documentos',                  'conversacion'),
    ('media.nota_voz',          'Enviar notas de voz',                'conversacion'),
    ('media.sticker_gif',       'Enviar stickers y GIFs',             'conversacion'),
    ('encuesta.crear',          'Crear encuestas',                    'conversacion'),
    ('evento.crear',            'Crear eventos',                      'conversacion'),
    ('miembro.ver',             'Ver la lista de miembros',           'conversacion'),
    ('miembro.invitar',         'Invitar miembros',                   'conversacion'),
    ('miembro.aprobar',         'Aprobar solicitudes de ingreso',     'conversacion'),
    ('miembro.expulsar',        'Expulsar miembros',                  'conversacion'),
    ('miembro.bloquear',        'Vetar miembros',                     'conversacion'),
    ('miembro.silenciar',       'Silenciar miembros',                 'conversacion'),
    ('grupo.ver_info',          'Ver la informacion del grupo',       'conversacion'),
    ('grupo.editar_info',       'Editar la informacion del grupo',    'conversacion'),
    ('grupo.cambiar_foto',      'Cambiar la foto del grupo',          'conversacion'),
    ('grupo.cambiar_nombre',    'Cambiar el nombre del grupo',        'conversacion'),
    ('grupo.administrar_roles', 'Administrar roles y administradores','conversacion'),
    ('grupo.eliminar',          'Eliminar el grupo',                  'conversacion'),
    ('invitacion.crear',        'Crear enlaces de invitacion',        'conversacion'),
    ('invitacion.revocar',      'Revocar enlaces de invitacion',      'conversacion'),
    ('canal.publicar',          'Publicar en el canal',               'canal'),
    ('canal.comentar',          'Comentar publicaciones',             'canal'),
    ('canal.estadisticas',      'Ver estadisticas del canal',         'canal');


-- ============================================================
--  Roles
-- ============================================================

-- conversacion_id NULL = rol de sistema (plantilla, vale para todas).
-- conversacion_id NOT NULL = rol propio de ese grupo.
CREATE TABLE rol (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    conversacion_id uuid REFERENCES conversacion(id) ON DELETE CASCADE,
    clave           text NOT NULL,
    nombre          text NOT NULL,
    -- Quien manda sobre quien. Nadie puede actuar sobre jerarquia >= la suya.
    jerarquia       integer NOT NULL,
    es_sistema      boolean NOT NULL DEFAULT false,
    creado_en       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT jerarquia_valida CHECK (jerarquia BETWEEN 1 AND 100),
    -- Un rol de sistema no puede pertenecer a una conversacion, y al reves.
    CONSTRAINT sistema_sin_conversacion CHECK (
        (es_sistema AND conversacion_id IS NULL) OR
        (NOT es_sistema AND conversacion_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX rol_sistema_unico ON rol (clave) WHERE es_sistema;
CREATE UNIQUE INDEX rol_propio_unico ON rol (conversacion_id, clave) WHERE NOT es_sistema;

CREATE TABLE rol_permiso (
    rol_id  uuid NOT NULL REFERENCES rol(id) ON DELETE CASCADE,
    permiso text NOT NULL REFERENCES permiso(clave) ON DELETE CASCADE,
    PRIMARY KEY (rol_id, permiso)
);

-- Roles de sistema. Ver docs/05-PLAN-COMPLETO.md
INSERT INTO rol (clave, nombre, jerarquia, es_sistema) VALUES
    ('propietario',   'Propietario',    100, true),
    ('administrador', 'Administrador',   80, true),
    ('moderador',     'Moderador',       50, true),
    ('miembro',       'Miembro',         10, true),
    ('restringido',   'Restringido',      5, true);

-- propietario: absolutamente todo lo de conversacion y canal
INSERT INTO rol_permiso (rol_id, permiso)
SELECT r.id, p.clave FROM rol r, permiso p
WHERE r.clave = 'propietario' AND r.es_sistema AND p.ambito IN ('conversacion','canal');

-- administrador: todo menos eliminar el grupo
INSERT INTO rol_permiso (rol_id, permiso)
SELECT r.id, p.clave FROM rol r, permiso p
WHERE r.clave = 'administrador' AND r.es_sistema
  AND p.ambito IN ('conversacion','canal')
  AND p.clave <> 'grupo.eliminar';

-- moderador: modera contenido y gente, no toca la configuracion del grupo
INSERT INTO rol_permiso (rol_id, permiso)
SELECT r.id, p.clave FROM rol r, permiso p
WHERE r.clave = 'moderador' AND r.es_sistema AND p.clave IN (
    'mensaje.enviar','mensaje.responder','mensaje.reaccionar','mensaje.editar',
    'mensaje.borrar_propio','mensaje.borrar_ajeno','mensaje.fijar','mensaje.reenviar',
    'mensaje.enviar_enlace','media.imagen','media.video','media.audio','media.documento',
    'media.nota_voz','media.sticker_gif','encuesta.crear','evento.crear',
    'miembro.ver','miembro.invitar','miembro.aprobar','miembro.expulsar','miembro.silenciar',
    'grupo.ver_info','canal.comentar'
);

-- miembro: participa, no administra
INSERT INTO rol_permiso (rol_id, permiso)
SELECT r.id, p.clave FROM rol r, permiso p
WHERE r.clave = 'miembro' AND r.es_sistema AND p.clave IN (
    'mensaje.enviar','mensaje.responder','mensaje.reaccionar','mensaje.editar',
    'mensaje.borrar_propio','mensaje.reenviar','mensaje.enviar_enlace',
    'media.imagen','media.video','media.audio','media.documento','media.nota_voz',
    'media.sticker_gif','encuesta.crear','miembro.ver','grupo.ver_info','canal.comentar'
);

-- restringido: solo mira
INSERT INTO rol_permiso (rol_id, permiso)
SELECT r.id, p.clave FROM rol r, permiso p
WHERE r.clave = 'restringido' AND r.es_sistema AND p.clave IN ('miembro.ver','grupo.ver_info');


-- ============================================================
--  Asignacion y excepciones
-- ============================================================

-- `participante.rol` era un texto libre; pasa a apuntar al catalogo.
ALTER TABLE participante ADD COLUMN rol_id uuid REFERENCES rol(id) ON DELETE SET NULL;

UPDATE participante p SET rol_id = (
    SELECT r.id FROM rol r
    WHERE r.es_sistema AND r.clave = CASE p.rol WHEN 'admin' THEN 'administrador' ELSE 'miembro' END
);

CREATE INDEX participante_rol_idx ON participante (rol_id);

-- Excepcion por persona y permiso. Un DENEGAR gana siempre sobre el rol.
CREATE TABLE permiso_override (
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    usuario_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    permiso         text NOT NULL REFERENCES permiso(clave) ON DELETE CASCADE,
    permitido       boolean NOT NULL,
    creado_en       timestamptz NOT NULL DEFAULT now(),
    creado_por      uuid REFERENCES usuario(id) ON DELETE SET NULL,
    PRIMARY KEY (conversacion_id, usuario_id, permiso)
);


-- ============================================================
--  Restricciones y bloqueos
-- ============================================================

-- silenciado | expulsado | vetado. `hasta` NULL = indefinido.
CREATE TABLE restriccion (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    usuario_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    tipo            text NOT NULL,
    motivo          text,
    hasta           timestamptz,
    aplicada_por    uuid REFERENCES usuario(id) ON DELETE SET NULL,
    creada_en       timestamptz NOT NULL DEFAULT now(),
    levantada_en    timestamptz,

    CONSTRAINT tipo_restriccion_valido CHECK (tipo IN ('silenciado','expulsado','vetado'))
);

-- La consulta caliente: "¿este usuario tiene una restriccion viva aqui?"
CREATE INDEX restriccion_activa
    ON restriccion (conversacion_id, usuario_id) WHERE levantada_en IS NULL;

-- Bloqueo entre personas. Se comprueba ANTES que cualquier rol: ningun permiso
-- de grupo debe permitir saltarse un bloqueo personal.
CREATE TABLE bloqueo (
    bloqueador_id uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    bloqueado_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    creado_en     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bloqueador_id, bloqueado_id),
    CONSTRAINT no_autobloqueo CHECK (bloqueador_id <> bloqueado_id)
);

CREATE INDEX bloqueo_inverso ON bloqueo (bloqueado_id);


-- ============================================================
--  Rol global y estado de cuenta
-- ============================================================

ALTER TABLE usuario
    ADD COLUMN rol_global        text NOT NULL DEFAULT 'usuario',
    ADD COLUMN suspendido_en     timestamptz,
    ADD COLUMN suspendido_motivo text,
    ADD CONSTRAINT rol_global_valido CHECK (
        rol_global IN ('usuario','moderador','administrador','superadministrador')
    );


-- ============================================================
--  Auditoria
-- ============================================================

-- Toda accion administrativa deja rastro. Sin esto no hay forma de responder
-- "quien expulso a esta persona y cuando".
CREATE TABLE auditoria (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    actor_id     uuid REFERENCES usuario(id) ON DELETE SET NULL,
    accion       text NOT NULL,
    recurso_tipo text NOT NULL,
    recurso_id   uuid,
    objetivo_id  uuid REFERENCES usuario(id) ON DELETE SET NULL,
    detalle      jsonb,
    creado_en    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX auditoria_por_recurso ON auditoria (recurso_tipo, recurso_id, id DESC);
CREATE INDEX auditoria_por_actor ON auditoria (actor_id, id DESC);


-- ============================================================
--  NOTAS
-- ============================================================
-- 1. `participante.rol` (texto) se conserva por ahora para no romper el codigo
--    existente; la verdad pasa a ser `rol_id`. Se elimina en V5, cuando ningun
--    query lo lea.
-- 2. Los permisos NO se cachean en el token a proposito: se resuelven por
--    peticion. Es lo que hace que degradar a alguien surta efecto de inmediato.
-- 3. `restriccion.hasta` se compara contra now() al resolver: un silencio
--    vencido deja de aplicar solo, sin job que lo levante.
