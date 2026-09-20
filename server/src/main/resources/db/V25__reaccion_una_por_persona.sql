-- ============================================================
--  V25: una reaccion por persona y por mensaje
-- ============================================================
--
-- ## El defecto
--
-- La llave primaria de `reaccion` era (mensaje_id, usuario_id, emoji), asi que
-- una misma persona podia sostener varias reacciones sobre el mismo mensaje. La
-- cabecera de V6 decia "cambiar de emoji es borrar y poner otra", dando por
-- sentado que el cliente borraria la anterior antes de poner la nueva. El
-- cliente nunca lo hizo -manda `poner=true` y nada mas-, asi que reaccionar dos
-- veces dejaba las dos marcas una al lado de la otra.
--
-- Eso no es lo que hace ningun mensajero conocido y no es lo que la gente
-- espera: mi reaccion es UNA, y lo que se acumula es la MISMA reaccion de
-- varias personas.
--
-- ## Por que se arregla en la base y no solo en el servidor
--
-- Porque una regla que solo vive en el codigo de la ruta se vuelve a romper la
-- proxima vez que alguien inserte desde otro sitio. Con la llave primaria
-- puesta en (mensaje_id, usuario_id), el segundo emoji de la misma persona no
-- es un caso que haya que recordar tratar: es un conflicto que Postgres
-- resuelve, y `ON CONFLICT ... DO UPDATE` lo convierte en un reemplazo.

-- Primero se limpia lo que ya quedo mal. Se conserva la reaccion MAS RECIENTE
-- de cada persona, que es la que esa persona eligio ultima: al momento de
-- tocarla, su intencion fue que esa reemplazara a la anterior.
DELETE FROM reaccion r
WHERE EXISTS (
    SELECT 1 FROM reaccion otra
    WHERE otra.mensaje_id = r.mensaje_id
      AND otra.usuario_id = r.usuario_id
      AND (otra.creada_en > r.creada_en
           OR (otra.creada_en = r.creada_en AND otra.emoji > r.emoji))
);

ALTER TABLE reaccion DROP CONSTRAINT reaccion_pkey;
ALTER TABLE reaccion ADD PRIMARY KEY (mensaje_id, usuario_id);
