-- wtfuck V6 - mensajes ricos
--
-- ============================================================
--  DECISION DE ARQUITECTURA: el servidor pasa a guardar METADATOS
-- ============================================================
--
-- Hasta aqui el servidor era un buzon que olvidaba: entregado el sobre, lo
-- borraba y no quedaba rastro. Eso ya no alcanza.
--
-- Para moderar hace falta poder responder "quien dijo esto y cuando". Sin eso:
--   - un administrador no puede borrar el mensaje de otro, porque el servidor
--     no sabe de quien era;
--   - no se puede fijar un mensaje, porque no hay a que apuntar;
--   - una reaccion no tiene donde colgarse;
--   - un reporte no se puede investigar.
--
-- Asi que se guarda el METADATO y NUNCA el contenido:
--   SI: id, conversacion, autor, cuando, si fue editado, retirado o fijado.
--   NO: el texto, ni el archivo, ni nada legible.
--
-- El precio es real y hay que decirlo: el servidor ahora sabe quien habla con
-- quien y con que frecuencia, aunque no sepa que dice. Es exactamente el mismo
-- trato que hace Signal para poder moderar grupos, y es el minimo que permite
-- cumplir los requisitos de moderacion sin romper el cifrado.

CREATE TABLE mensaje_meta (
    -- El mismo id que genero el cliente: asi ambos lados hablan del mismo
    -- mensaje sin traducir identificadores.
    id              uuid PRIMARY KEY,
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    autor_id        uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    creado_en       timestamptz NOT NULL DEFAULT now(),

    -- Hilos de respuesta. Sin ON DELETE CASCADE: borrar el original no debe
    -- borrar las respuestas, solo dejarlas sin referencia.
    responde_a      uuid REFERENCES mensaje_meta(id) ON DELETE SET NULL,

    -- Reenvio: de quien venia originalmente.
    reenviado_de    uuid REFERENCES usuario(id) ON DELETE SET NULL,

    editado_en      timestamptz,
    retirado_en     timestamptz,
    retirado_por    uuid REFERENCES usuario(id) ON DELETE SET NULL,
    fijado_en       timestamptz,
    fijado_por      uuid REFERENCES usuario(id) ON DELETE SET NULL,

    -- Mensajes temporales: el cliente los borra al vencer y un barrido los
    -- limpia aqui. NULL = permanente.
    expira_en       timestamptz
);

CREATE INDEX mensaje_por_conversacion ON mensaje_meta (conversacion_id, id DESC);
CREATE INDEX mensaje_por_autor ON mensaje_meta (autor_id);
CREATE INDEX mensaje_fijado ON mensaje_meta (conversacion_id) WHERE fijado_en IS NOT NULL;
CREATE INDEX mensaje_expira ON mensaje_meta (expira_en) WHERE expira_en IS NOT NULL;


-- ============================================================
--  Reacciones
-- ============================================================

-- Una reaccion por persona y emoji. Cambiar de emoji es borrar y poner otra.
CREATE TABLE reaccion (
    mensaje_id uuid NOT NULL REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    usuario_id uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    emoji      text NOT NULL,
    creada_en  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (mensaje_id, usuario_id, emoji),
    -- Un emoji real ocupa pocos bytes; el limite evita que alguien meta texto.
    CONSTRAINT emoji_acotado CHECK (char_length(emoji) BETWEEN 1 AND 16)
);

CREATE INDEX reaccion_por_mensaje ON reaccion (mensaje_id);


-- ============================================================
--  Menciones
-- ============================================================

-- Se guardan aparte para poder notificar y para el filtro "mencionaron".
CREATE TABLE mencion (
    mensaje_id uuid NOT NULL REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    usuario_id uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    PRIMARY KEY (mensaje_id, usuario_id)
);

CREATE INDEX mencion_por_usuario ON mencion (usuario_id);


-- ============================================================
--  Mensajes temporales por conversacion
-- ============================================================

ALTER TABLE conversacion
    -- Segundos de vida de los mensajes nuevos. NULL = permanentes.
    ADD COLUMN temporales_segundos integer,
    ADD CONSTRAINT temporales_valido CHECK (
        temporales_segundos IS NULL OR temporales_segundos BETWEEN 60 AND 7776000
    );


-- ============================================================
--  Eventos nuevos del modulo C
-- ============================================================

ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        -- Modulo C: acciones sobre mensajes que TODOS deben aplicar.
        -- Van como evento del servidor y no como mensaje cifrado porque el
        -- servidor es quien autoriza: si viajaran cifradas, no podria validar
        -- que quien borra tenga permiso.
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion'
    )
);

-- El detalle de estos eventos necesita mas espacio que un texto corto.
ALTER TABLE evento_pendiente ALTER COLUMN detalle TYPE text;


-- ============================================================
--  NOTAS
-- ============================================================
-- 1. `mensaje_meta` NO tiene columna de contenido, y no debe tenerla nunca.
--    Si alguna vez hace falta buscar texto, la busqueda va en el cliente.
-- 2. El id lo genera el cliente (UUIDv7). El servidor confia en el id pero NO
--    en el autor: el autor_id sale de la sesion, no del cuerpo de la peticion.
-- 3. Retirar no borra la fila: marca `retirado_en`. Asi el hueco sigue
--    existiendo para las respuestas que apuntaban al mensaje.
