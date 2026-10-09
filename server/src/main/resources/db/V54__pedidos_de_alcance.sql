-- Pedidos de alcance de los bots de pentesting (ver docs/14-BOTS.md).
--
-- El modelo de dos personas: un operador le pide a un bot un objetivo (por chat),
-- el bot lo registra aca como PENDIENTE, y un admin lo aprueba o rechaza desde el
-- panel. Recien un objetivo APROBADO se puede escanear. El bot consulta esta
-- tabla para saber que esta aprobado.
--
-- Por que en el servidor y no en un archivo del bot: para que la aprobacion pase
-- por el panel de administracion (que habla con el servidor), con el mismo rol de
-- staff y la misma auditoria que el resto de la moderacion.

CREATE TABLE pedido_alcance (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    -- El bot (cuenta) que registro el pedido. Cada bot ve y usa lo suyo.
    bot_id       uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    -- El operador que pidio (su username, tal como lo vio el bot en el chat).
    operador     text NOT NULL,
    -- host / ip / cidr. El bot ya lo valido antes de registrarlo; el admin lo ve.
    objetivo     text NOT NULL CHECK (char_length(objetivo) BETWEEN 1 AND 255),
    estado       text NOT NULL DEFAULT 'pendiente'
                     CHECK (estado IN ('pendiente', 'aprobado', 'rechazado')),
    creado_en    timestamptz NOT NULL DEFAULT now(),
    resuelto_por uuid REFERENCES usuario (id),
    resuelto_en  timestamptz,
    motivo       text
);

-- La cola del admin: los pendientes, mas viejo primero.
CREATE INDEX pedido_alcance_pendientes ON pedido_alcance (creado_en) WHERE estado = 'pendiente';
-- Lo que consulta el bot: sus objetivos por operador.
CREATE INDEX pedido_alcance_bot_op ON pedido_alcance (bot_id, operador);
-- Un mismo (bot, operador, objetivo) no se repite: el pedido se reusa.
CREATE UNIQUE INDEX pedido_alcance_unico ON pedido_alcance (bot_id, operador, lower(objetivo));

COMMENT ON TABLE pedido_alcance IS
    'Objetivos que un operador pide auditar con un bot; un admin los aprueba desde el panel.';
