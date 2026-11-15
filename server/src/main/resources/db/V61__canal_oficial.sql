-- ============================================================
--  V61: canal oficial de anuncios (WTFuck Announces)
-- ============================================================
--
-- Un canal oficial de la instancia: anuncios y changelog. Es un canal publico
-- normal (reusa toda la infra del modulo F), con tres diferencias de uso que no
-- necesitan codigo nuevo salvo esta marca:
--
--   - Auto-suscrito: a cada usuario se le inserta su fila de participante al
--     registrarse, y a los existentes en el arranque. Ver Repo/registro y
--     Canales.sembrarCanalOficial.
--   - Solo lectura: se crea con comentarios=false y reacciones=false, que el
--     servidor ya enforcea (Mensajes.registrar rechaza comentar). Un suscriptor
--     nunca pudo publicar. No hace falta un "modo" aparte.
--   - Se publica desde el panel (staff), no desde un cliente. Ver el endpoint
--     POST /v1/panel/anuncios y Canales.publicarOficial.
--
-- La marca `oficial` sirve para encontrarlo (auto-suscripcion, publicar) y para
-- que la interfaz le ponga una insignia.

ALTER TABLE canal ADD COLUMN IF NOT EXISTS oficial boolean NOT NULL DEFAULT false;

-- Como mucho UN canal oficial por instancia: el indice parcial lo garantiza.
CREATE UNIQUE INDEX IF NOT EXISTS canal_unico_oficial ON canal ((oficial)) WHERE oficial;
