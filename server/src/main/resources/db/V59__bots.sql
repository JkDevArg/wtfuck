-- wtfuck V59 - bots gestionados (estilo BotFather, pero E2EE)
--
-- Un bot ES una cuenta de cifrado normal, headless. La diferencia: la crea un
-- DUENO (hasta 3 por persona), se registra con un CODIGO de un solo uso (en vez
-- de con atestacion de hardware, que un bot no tiene), y puede ser PUBLICO para
-- que otros lo encuentren y le escriban.
--
-- El cifrado no se toca: el dueno corre el cliente del bot donde quiera (Lambda,
-- EC2, una VM) y ahi viven las claves del bot. El servidor nunca descifra; mueve
-- sobres opacos como con cualquier cuenta.
--
-- `usuario_id` es NULL hasta que el dueno registra el bot con el codigo: recien
-- ahi existe la cuenta. `codigo_hash` se guarda hasheado; el codigo en claro se
-- le muestra al dueno una sola vez, al crearlo.

CREATE TABLE bot (
    username      text PRIMARY KEY,
    dueno_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    codigo_hash   bytea NOT NULL,
    publico       boolean NOT NULL DEFAULT false,
    descripcion   text NOT NULL DEFAULT '',
    usuario_id    uuid REFERENCES usuario(id) ON DELETE SET NULL,
    creado_en     timestamptz NOT NULL DEFAULT now(),
    registrado_en timestamptz
);

CREATE INDEX bot_dueno ON bot (dueno_id);
-- El directorio de bots publicos ya registrados.
CREATE INDEX bot_publicos ON bot (publico) WHERE publico AND usuario_id IS NOT NULL;
