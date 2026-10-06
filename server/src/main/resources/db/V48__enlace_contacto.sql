-- ============================================================
--  V48: el enlace de contacto ("agregame", con QR)
-- ============================================================
--
-- Un codigo al azar por cuenta, que deja que quien lo TENGA encuentre a esa
-- cuenta y le escriba. No es el @usuario: el usuario es publico y fijo, y un
-- QR con el usuario dentro valdria para siempre aunque circulara por donde no
-- debe. El codigo se cambia cuando se quiera, y el viejo deja de servir en el
-- acto -ese es el punto de que exista-.
--
-- Uno por cuenta (PRIMARY KEY usuario_id): rotarlo es pisar la fila, no
-- acumular codigos viejos que alguien podria seguir usando.
CREATE TABLE IF NOT EXISTS enlace_contacto (
    usuario_id uuid PRIMARY KEY REFERENCES usuario(id) ON DELETE CASCADE,
    token      text NOT NULL UNIQUE,
    creado_en  timestamptz NOT NULL DEFAULT now()
);
