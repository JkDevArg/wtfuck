-- Modulo O · Un adjunto puede pertenecer a una historia y no a un chat.
--
-- ## El bloqueo que levanta
--
-- Hasta aqui todo adjunto colgaba de una conversacion, y de ahi salia su
-- autorizacion: quien pertenece a la conversacion puede bajarlo. Una historia
-- con imagen no tiene conversacion —se publica a una audiencia, que es otra
-- cosa— asi que sin esto no hay forma de subir una foto a una historia salvo
-- inventarle un chat, que es exactamente lo que se evito con los sobres en V29.
--
-- ## Por que una columna nueva y no reutilizar `conversacion_id`
--
-- Porque de esa columna cuelga **quien puede bajar el archivo**, y son dos
-- preguntas distintas: en un chat se mira `participante`, en una historia se
-- mira `historia_destino`. Meter los dos ids en el mismo campo obligaria a
-- adivinar cual es en cada consulta, y adivinar en una comprobacion de acceso
-- es como se filtran archivos.
--
-- ## El CHECK no es decoracion
--
-- Exactamente uno de los dos. Un adjunto sin ninguno seria un archivo sin
-- dueno al que nadie puede autorizar —y al que, por tanto, cualquiera podria—;
-- con los dos, dos caminos de autorizacion distintos sobre el mismo objeto, y
-- el mas permisivo gana. Los dos casos son agujeros, y los dos los para esta
-- linea.

ALTER TABLE adjunto ADD COLUMN IF NOT EXISTS historia_id uuid
    REFERENCES historia(id) ON DELETE CASCADE;

ALTER TABLE adjunto ALTER COLUMN conversacion_id DROP NOT NULL;

ALTER TABLE adjunto DROP CONSTRAINT IF EXISTS adjunto_tiene_dueno;
ALTER TABLE adjunto ADD CONSTRAINT adjunto_tiene_dueno CHECK (
    (conversacion_id IS NOT NULL AND historia_id IS NULL)
    OR
    (conversacion_id IS NULL AND historia_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS adjunto_por_historia ON adjunto (historia_id);
