-- wtfuck V3 - privacidad y eventos del sistema
--
-- Los tres niveles de cada ajuste:
--   todos      cualquier usuario del servidor
--   conocidos  solo quien ya tiene una conversacion DIRECTA abierta contigo
--   nadie      ni siquiera los conocidos
--
-- "conocidos" reemplaza a "mis contactos" de WhatsApp: aqui no hay agenda del
-- telefono, asi que el equivalente honesto es "gente con la que ya hablas".

ALTER TABLE usuario
    ADD COLUMN priv_foto    text NOT NULL DEFAULT 'todos',
    ADD COLUMN priv_estado  text NOT NULL DEFAULT 'todos',
    ADD COLUMN priv_escribe text NOT NULL DEFAULT 'todos',
    ADD COLUMN priv_grupos  text NOT NULL DEFAULT 'todos';

ALTER TABLE usuario
    ADD CONSTRAINT priv_foto_valido    CHECK (priv_foto    IN ('todos','conocidos','nadie')),
    ADD CONSTRAINT priv_estado_valido  CHECK (priv_estado  IN ('todos','conocidos','nadie')),
    -- Escribir no admite 'nadie': una cuenta a la que nadie puede escribir no es
    -- una cuenta de mensajeria. Quien quiera eso, desactiva la cuenta.
    ADD CONSTRAINT priv_escribe_valido CHECK (priv_escribe IN ('todos','conocidos')),
    ADD CONSTRAINT priv_grupos_valido  CHECK (priv_grupos  IN ('todos','conocidos','nadie'));


-- ============================================================
--  Eventos del sistema
-- ============================================================

-- Avisos que genera el SERVIDOR, no un usuario: "te agregaron a un grupo",
-- "te sacaron", "cambio el nombre".
--
-- Van en tabla aparte, no en sobre_pendiente, por una razon de fondo: el cuerpo
-- de un sobre es opaco y desde la fase 4 va cifrado entre clientes. El servidor
-- no puede fabricar uno porque no tiene las claves. Estos eventos, en cambio,
-- son metadatos que el servidor ya conoce, y viajan en claro a proposito.
CREATE TABLE evento_pendiente (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    destino_dispositivo uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    tipo                text NOT NULL,
    conversacion_id     uuid REFERENCES conversacion(id) ON DELETE CASCADE,
    actor_username      text NOT NULL,
    detalle             text,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    expira_en           timestamptz NOT NULL DEFAULT now() + interval '30 days',

    CONSTRAINT tipo_evento_valido CHECK (
        tipo IN ('agregado_grupo','sacado_grupo','grupo_renombrado')
    )
);

-- Misma consulta caliente que el buzon: "dame lo pendiente de este dispositivo".
CREATE INDEX evento_por_destino ON evento_pendiente (destino_dispositivo, id);
CREATE INDEX evento_expirado ON evento_pendiente (expira_en);
