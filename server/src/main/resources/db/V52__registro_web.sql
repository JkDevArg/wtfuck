-- W5 · Registro desde la web (y por ahi, iPhone, que no tiene app).
--
-- Una cuenta creada desde un navegador tiene que pasar dos puertas que la app
-- Android no pasa: un correo verificado y una invitacion WEB. Ver
-- `Correo.kt`, `Invitaciones.kt` y docs/12-VERSION-WEB.md, "W5".

-- ------------------------------------------------------------
--  El correo, sin guardarlo
-- ------------------------------------------------------------
--
-- Igual que el telefono (V14): se guarda HMAC(pepper, "correo:" || correo),
-- nunca el correo. Alcanza para comprobar que no esta repetido y para
-- encontrar la cuenta al recuperarla -la persona lo vuelve a escribir-. Una
-- fuga de la base no entrega una lista de correos.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS correo_hash bytea;
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS correo_verificado_en timestamptz;

CREATE UNIQUE INDEX IF NOT EXISTS usuario_correo_unico
    ON usuario (correo_hash) WHERE correo_hash IS NOT NULL;

COMMENT ON COLUMN usuario.correo_hash IS
    'HMAC del correo con WTFUCK_PEPPER_TELEFONO y el prefijo "correo:". Nunca el correo.';

-- Los codigos de 6 digitos al correo viven en la misma tabla que los del SMS:
-- el destino ya es un hash, y lo que cambia es el proposito.
ALTER TABLE codigo_verificacion DROP CONSTRAINT IF EXISTS proposito_valido;
ALTER TABLE codigo_verificacion ADD CONSTRAINT proposito_valido CHECK (
    proposito IN ('verificar_telefono', 'recuperar_cuenta', 'eliminar_cuenta',
                  'registro_correo', 'recuperar_correo')
);

-- ------------------------------------------------------------
--  Invitaciones con alcance
-- ------------------------------------------------------------
--
-- `general` es la de siempre (la app, con el servidor en modo invitacion).
-- `web` es la que pide el registro desde el navegador. Dos puertas distintas:
-- abrir una no abre la otra.
ALTER TABLE invitacion_registro
    ADD COLUMN IF NOT EXISTS alcance text NOT NULL DEFAULT 'general';
ALTER TABLE invitacion_registro DROP CONSTRAINT IF EXISTS alcance_valido;
ALTER TABLE invitacion_registro ADD CONSTRAINT alcance_valido
    CHECK (alcance IN ('general', 'web'));

-- El cupo de cada usuario se cuenta por quien la creo, con el indice que ya
-- existe desde V41 (`idx_invitacion_registro_creador`).

-- ------------------------------------------------------------
--  El navegador ya puede ser el aparato principal
-- ------------------------------------------------------------
--
-- V49 lo prohibia, cuando la web solo se vinculaba a una cuenta de telefono.
-- Ahora una cuenta puede nacer en la web, y su unico aparato es el navegador.
-- El servidor sigue sin dejar PROMOVER un navegador por encima de un telefono
-- (`Dispositivos.promover`), y sigue sin dejar registrar uno sin correo ni
-- invitacion (`Repo.registrar`).
ALTER TABLE dispositivo DROP CONSTRAINT IF EXISTS navegador_nunca_principal;
