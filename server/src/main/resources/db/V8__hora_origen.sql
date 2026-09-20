-- ============================================================
--  V8: la hora del AUTOR viaja con el sobre
-- ============================================================
--
-- Hasta aqui `sobre_pendiente` guardaba solo `recibido_en`, la hora en que el
-- servidor recibio el sobre, y la entrega la usaba como hora del mensaje.
--
-- Eso rompia justamente lo que este proyecto promete. Con `msg off` un mensaje
-- se escribe sin red y sale horas despues: quien lo recibia lo veia con la hora
-- de la entrega y no con la de su autor, y ademas quedaba mal ordenado respecto
-- de los mensajes que si habian salido en el momento.
--
-- Se detecto probando con dos emuladores: el emisor mostraba 03:16 y el
-- receptor 03:21 para el mismo mensaje.
--
-- Las dos horas se conservan porque responden preguntas distintas:
--   creado_en_origen -> cuando lo escribio la persona. Es lo que se muestra.
--   recibido_en      -> cuando entro al servidor. Es para retencion y barrido.

ALTER TABLE sobre_pendiente
    ADD COLUMN IF NOT EXISTS creado_en_origen bigint;

-- Las filas que ya estaban no tienen el dato original. Se las deja con la hora
-- de recepcion, que es la mejor aproximacion disponible: poner 0 las mostraria
-- como de 1970 y eso seria peor que una hora aproximada.
UPDATE sobre_pendiente
   SET creado_en_origen = (extract(epoch FROM recibido_en) * 1000)::bigint
 WHERE creado_en_origen IS NULL;

ALTER TABLE sobre_pendiente
    ALTER COLUMN creado_en_origen SET NOT NULL;
