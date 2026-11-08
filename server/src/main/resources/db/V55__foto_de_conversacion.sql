-- wtfuck V55 - foto de grupo y de canal
--
-- Un grupo o un canal podia tener nombre y descripcion, pero no cara. La foto
-- se guarda igual que el avatar de usuario (V2): como bytes en la propia fila,
-- no como adjunto, porque es un dato del perfil de la conversacion y no un
-- mensaje. `avatar_actualizado` sirve de cache-buster: la URL lleva ?v=<esto>,
-- asi que al cambiar la foto cambia la URL y ningun cache sirve una vieja.
--
-- Solo aplica a 'grupo' y 'canal'. En un chat directo la foto es el avatar del
-- otro, que ya existe en `usuario`; no se toca nada ahi.

ALTER TABLE conversacion
    ADD COLUMN IF NOT EXISTS avatar             bytea,
    ADD COLUMN IF NOT EXISTS avatar_actualizado timestamptz;
