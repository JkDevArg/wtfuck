-- wtfuck V5 - grupos avanzados
--
-- Modulo B: configuracion de grupo, enlaces de invitacion, solicitudes de
-- ingreso, roles personalizados y preferencias por persona.
--
-- Todo lo que aqui se configura lo hace cumplir el motor de V4. Este archivo
-- solo guarda la decision; quien la aplica es Autz.

-- ============================================================
--  Configuracion del grupo
-- ============================================================

ALTER TABLE conversacion
    ADD COLUMN descripcion       text,
    -- Publico: cualquiera con el enlace entra. Privado: solo por invitacion.
    ADD COLUMN publico           boolean NOT NULL DEFAULT false,
    -- Modo anuncio: solo quien tiene mensaje.enviar por rol puede escribir.
    ADD COLUMN solo_admins       boolean NOT NULL DEFAULT false,
    -- Si los nuevos miembros pueden leer lo anterior a su ingreso.
    ADD COLUMN historial_visible boolean NOT NULL DEFAULT true,
    ADD COLUMN aprobar_ingreso   boolean NOT NULL DEFAULT false,
    ADD COLUMN permitir_media    boolean NOT NULL DEFAULT true,
    ADD COLUMN permitir_enlaces  boolean NOT NULL DEFAULT true,
    -- Alias publico (@equipo-educad) para grupos y canales abiertos.
    ADD COLUMN alias             citext,
    ADD CONSTRAINT descripcion_acotada CHECK (descripcion IS NULL OR char_length(descripcion) <= 500),
    ADD CONSTRAINT alias_formato CHECK (alias IS NULL OR alias ~ '^[a-z0-9_-]{4,32}$');

CREATE UNIQUE INDEX conversacion_alias_unico ON conversacion (alias) WHERE alias IS NOT NULL;


-- ============================================================
--  Preferencias por persona
-- ============================================================

-- Silenciar, archivar y fijar son decisiones de CADA participante, no del
-- grupo: por eso viven aqui y no en conversacion. Es lo que hace que silenciar
-- un grupo no lo silencie para los demas.
ALTER TABLE participante
    ADD COLUMN silenciado_hasta timestamptz,
    ADD COLUMN archivado        boolean NOT NULL DEFAULT false,
    ADD COLUMN fijado           boolean NOT NULL DEFAULT false;

CREATE INDEX participante_archivado
    ON participante (usuario_id) WHERE archivado AND salido_en IS NULL;


-- ============================================================
--  Enlaces de invitacion
-- ============================================================

CREATE TABLE invitacion (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    -- Aleatorio y opaco. No se deriva del id del grupo: si se derivara,
    -- conocer un enlace permitiria adivinar los demas.
    codigo          text NOT NULL UNIQUE,
    creada_por      uuid REFERENCES usuario(id) ON DELETE SET NULL,
    creada_en       timestamptz NOT NULL DEFAULT now(),
    expira_en       timestamptz,
    usos_max        integer,
    usos            integer NOT NULL DEFAULT 0,
    revocada_en     timestamptz,

    CONSTRAINT usos_max_valido CHECK (usos_max IS NULL OR usos_max > 0),
    CONSTRAINT codigo_formato CHECK (char_length(codigo) BETWEEN 8 AND 64)
);

CREATE INDEX invitacion_por_grupo
    ON invitacion (conversacion_id) WHERE revocada_en IS NULL;


-- ============================================================
--  Solicitudes de ingreso
-- ============================================================

CREATE TABLE solicitud_ingreso (
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    usuario_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    estado          text NOT NULL DEFAULT 'pendiente',
    mensaje         text,
    creada_en       timestamptz NOT NULL DEFAULT now(),
    resuelta_en     timestamptz,
    resuelta_por    uuid REFERENCES usuario(id) ON DELETE SET NULL,

    PRIMARY KEY (conversacion_id, usuario_id),
    CONSTRAINT estado_solicitud_valido CHECK (estado IN ('pendiente','aprobada','rechazada')),
    CONSTRAINT mensaje_acotado CHECK (mensaje IS NULL OR char_length(mensaje) <= 200)
);

CREATE INDEX solicitud_pendiente
    ON solicitud_ingreso (conversacion_id) WHERE estado = 'pendiente';


-- ============================================================
--  Eventos nuevos
-- ============================================================

-- El check de V3 era cerrado; se amplia para los avisos del modulo B.
ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva'
    )
);


-- ============================================================
--  NOTAS
-- ============================================================
-- 1. `solo_admins` NO se traduce a quitar el permiso mensaje.enviar de cada
--    miembro: eso destruiria la configuracion al desactivar el modo. Se evalua
--    en el momento, dentro de Autz.
-- 2. `historial_visible` aun no filtra nada: el servidor no guarda historial
--    (vive en los clientes). Queda declarado para cuando exista sincronizacion
--    multi-dispositivo, que es la unica forma de que el historial viaje.
-- 3. `invitacion.usos` se incrementa dentro de la misma transaccion que el alta
--    del participante; si el alta falla, el uso no se consume.
