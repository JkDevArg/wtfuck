-- wtfuck V60 - delegacion acotada para gestionar bots
--
-- Deja que el bot oficial de recepcion (@rootbot) cree y liste los bots de un
-- usuario EN SU NOMBRE, sin tener su sesion. El usuario genera un token desde la
-- app, se lo pasa al bot por el chat E2EE, y el bot lo presenta al servidor.
--
-- Alcance fijo por diseno: SOLO crear y listar bots (nunca borrar), con los
-- mismos limites del dueno. Por eso no hay columna de alcance: la ruta delegada
-- ya es la unica que lo acepta. El token se guarda HASHEADO (como el codigo de
-- un bot); el claro se le muestra al usuario una sola vez.

CREATE TABLE delegacion_bot (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dueno_id    uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    token_hash  bytea NOT NULL,
    creado_en   timestamptz NOT NULL DEFAULT now(),
    vence_en    timestamptz NOT NULL,
    revocado_en timestamptz
);

CREATE INDEX delegacion_bot_dueno ON delegacion_bot (dueno_id);
-- La resolucion del token busca por hash entre las vivas.
CREATE INDEX delegacion_bot_hash ON delegacion_bot (token_hash);
