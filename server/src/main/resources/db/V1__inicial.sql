-- wtfuck - esquema inicial
-- PostgreSQL 16+
--
-- REGLA DE ORO: este esquema no contiene ni una sola columna con contenido
-- legible de un mensaje. El cuerpo es siempre bytea opaco.

CREATE EXTENSION IF NOT EXISTS citext;      -- username case-insensitive
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- UUIDv7: ordenado por tiempo. Indices B-tree sin fragmentacion, y no revela
-- el conteo de filas. Postgres 18 lo trae nativo; mientras tanto, esta funcion.
CREATE OR REPLACE FUNCTION uuidv7() RETURNS uuid AS $func$
  SELECT encode(
    set_bit(set_bit(overlay(
      uuid_send(gen_random_uuid()) PLACING
      substring(int8send((extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
      FROM 1 FOR 6), 52, 1), 53, 1), 'hex')::uuid;
$func$ LANGUAGE sql VOLATILE;


-- ============================================================
--  IDENTIDAD
-- ============================================================

CREATE TABLE usuario (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    username        citext NOT NULL UNIQUE,
    password_hash   text   NOT NULL,          -- Argon2id. Nunca la contrasena.
    creado_en       timestamptz NOT NULL DEFAULT now(),
    desactivado_en  timestamptz,

    CONSTRAINT username_formato CHECK (username ~ '^[a-z0-9_]{3,24}$')
);
COMMENT ON TABLE usuario IS 'Identidad publica. Sin telefono, sin correo, sin PII.';


-- Un usuario puede tener N dispositivos EN EL ESQUEMA.
-- La regla de negocio "uno solo" se aplica con el indice parcial de abajo.
-- Separarlos ahora evita reescribir todo el dia que se permita multi-dispositivo.
CREATE TABLE dispositivo (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id         uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    etiqueta           text NOT NULL,             -- "Pixel 9 de Joaquin"

    -- Identidad criptografica del dispositivo (X3DH, fase 4).
    -- La privada correspondiente NUNCA sale del Keystore del telefono.
    identidad_pub      bytea NOT NULL,

    -- Vinculo con el hardware. Ver docs/04-DEVICE-BINDING.md
    -- Hash del certificado de atestacion del Keystore. Un hardware = una cuenta.
    hardware_hash      bytea NOT NULL,
    hardware_nivel     text  NOT NULL,            -- STRONGBOX | TEE | SOFTWARE_DEV

    registrado_en      timestamptz NOT NULL DEFAULT now(),
    ultimo_visto_en    timestamptz,
    revocado_en        timestamptz,

    CONSTRAINT nivel_valido CHECK (hardware_nivel IN ('STRONGBOX','TEE','SOFTWARE_DEV'))
);

-- REGLA 1: un solo dispositivo activo por usuario.
CREATE UNIQUE INDEX dispositivo_unico_por_usuario
    ON dispositivo (usuario_id) WHERE revocado_en IS NULL;

-- REGLA 2: un solo hardware activo -> no se pueden duplicar cuentas.
CREATE UNIQUE INDEX dispositivo_unico_por_hardware
    ON dispositivo (hardware_hash) WHERE revocado_en IS NULL;


CREATE TABLE sesion (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    dispositivo_id uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    token_hash     bytea NOT NULL UNIQUE,        -- SHA-256 del token. Nunca el token.
    emitido_en     timestamptz NOT NULL DEFAULT now(),
    expira_en      timestamptz NOT NULL,
    revocado_en    timestamptz
);
CREATE INDEX sesion_activa ON sesion (dispositivo_id) WHERE revocado_en IS NULL;


-- ============================================================
--  MATERIAL DE CLAVES  (fase 4 - declarado desde ya)
-- ============================================================

-- X3DH: el remitente necesita claves del destinatario aunque este offline.
CREATE TABLE prekey_firmada (
    dispositivo_id uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    key_id         integer NOT NULL,
    publica        bytea NOT NULL,
    firma          bytea NOT NULL,              -- firmada con la clave de identidad
    creada_en      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (dispositivo_id, key_id)
);

-- De un solo uso: se consumen al abrir sesion criptografica. El cliente repone.
CREATE TABLE prekey_unica (
    dispositivo_id uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    key_id         integer NOT NULL,
    publica        bytea NOT NULL,
    consumida_en   timestamptz,
    PRIMARY KEY (dispositivo_id, key_id)
);
-- El servidor sirve la primera disponible y la marca. Indice parcial = barato.
CREATE INDEX prekey_disponible
    ON prekey_unica (dispositivo_id, key_id) WHERE consumida_en IS NULL;


-- ============================================================
--  CONVERSACIONES  (1:1 y grupos comparten forma)
-- ============================================================

CREATE TABLE conversacion (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tipo        text NOT NULL,
    creada_en   timestamptz NOT NULL DEFAULT now(),
    creador_id  uuid REFERENCES usuario(id) ON DELETE SET NULL,

    -- Solo grupos. METADATO VISIBLE PARA EL SERVIDOR: el nombre del grupo no va
    -- cifrado en el MVP. Cifrarlo obliga a distribuirlo como mensaje de control,
    -- lo que se difiere. Documentado a proposito, no es un descuido.
    nombre      text,

    -- Solo para 1:1: "uuid_menor:uuid_mayor". Impide conversaciones duplicadas
    -- entre el mismo par. NULL en grupos.
    clave_directa text UNIQUE,

    CONSTRAINT tipo_valido CHECK (tipo IN ('directa','grupo')),
    CONSTRAINT directa_tiene_clave CHECK (
        (tipo = 'directa' AND clave_directa IS NOT NULL) OR
        (tipo = 'grupo'   AND clave_directa IS NULL)
    )
);

CREATE TABLE participante (
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    usuario_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    rol             text NOT NULL DEFAULT 'miembro',
    unido_en        timestamptz NOT NULL DEFAULT now(),
    salido_en       timestamptz,
    PRIMARY KEY (conversacion_id, usuario_id),
    CONSTRAINT rol_valido CHECK (rol IN ('miembro','admin'))
);
-- "dame las conversaciones de este usuario" -> index-only scan
CREATE INDEX participante_por_usuario
    ON participante (usuario_id) WHERE salido_en IS NULL;


-- ============================================================
--  EL BUZON  (la unica tabla caliente)
-- ============================================================

-- Fan-out en escritura: al enviar, se inserta UNA fila por dispositivo destino.
-- Se BORRA al confirmarse la entrega. Esta tabla nunca crece sin limite.
CREATE TABLE sobre_pendiente (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    destino_dispositivo uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    origen_dispositivo  uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    conversacion_id     uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,

    -- Opaco. El servidor NO parsea esto. Desde la fase 4 va cifrado E2EE.
    cuerpo              bytea NOT NULL,

    recibido_en         timestamptz NOT NULL DEFAULT now(),
    expira_en           timestamptz NOT NULL DEFAULT now() + interval '30 days',

    CONSTRAINT cuerpo_acotado CHECK (octet_length(cuerpo) <= 65536)
);

-- La consulta mas caliente del sistema: "dame lo pendiente de este dispositivo,
-- en orden". El PK es UUIDv7 (ordenado por tiempo), asi que el orden sale gratis.
CREATE INDEX sobre_por_destino ON sobre_pendiente (destino_dispositivo, id);

-- Barrido de expirados (job periodico).
CREATE INDEX sobre_expirado ON sobre_pendiente (expira_en);


-- ============================================================
--  NOTAS DE ESCALAMIENTO
-- ============================================================
-- 1. Hasta ~50k usuarios: una sola instancia de Postgres. No optimices antes.
-- 2. Primer cuello de botella esperado: sobre_pendiente. Solucion cuando llegue:
--    particionar por HASH(destino_dispositivo). El indice ya lo tiene de prefijo.
-- 3. Las sesiones WebSocket vivas van en Redis (dispositivo_id -> instancia),
--    NO en memoria del proceso. Es lo que permite correr N servidores.
-- 4. Media (fase 6) NUNCA va en Postgres. Va a S3/MinIO cifrada; en el mensaje
--    viaja solo la URL y la clave de descifrado.
