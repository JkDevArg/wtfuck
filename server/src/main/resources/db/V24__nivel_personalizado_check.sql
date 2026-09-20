-- ============================================================
--  V24: los CHECK admiten el nivel `personalizado`
-- ============================================================
--
-- Correccion de V23. Ahi se agrego el nivel al contrato y a la resolucion, y
-- se olvido esto: los CHECK de V20 solo permitian tres valores, asi que el
-- servidor aceptaba `personalizado`, Postgres lo rechazaba, y el sintoma era
-- un 500 en un PUT que la pantalla habia dado por bueno.
--
-- `priv_escribe` NO se toca a proposito: sigue admitiendo solo `todos` y
-- `conocidos`. Una lista blanca de "quien me puede escribir" convierte la
-- cuenta en un club cerrado, y para eso estan los bloqueos.
ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_foto_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_foto_valido
    CHECK (priv_foto IN ('todos', 'conocidos', 'nadie', 'personalizado'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_estado_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_estado_valido
    CHECK (priv_estado IN ('todos', 'conocidos', 'nadie', 'personalizado'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_grupos_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_grupos_valido
    CHECK (priv_grupos IN ('todos', 'conocidos', 'nadie', 'personalizado'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_llamadas_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_llamadas_valido
    CHECK (priv_llamadas IN ('todos', 'conocidos', 'nadie', 'personalizado'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_nombre_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_nombre_valido
    CHECK (priv_nombre IN ('todos', 'conocidos', 'nadie', 'personalizado'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_busqueda_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_busqueda_valido
    CHECK (priv_busqueda IN ('todos', 'conocidos', 'nadie', 'personalizado'));

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_ultima_vez_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_ultima_vez_valido
    CHECK (priv_ultima_vez IN ('todos', 'conocidos', 'nadie', 'personalizado'));
