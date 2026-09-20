-- wtfuck V2 - perfil de usuario
--
-- Nota honesta sobre privacidad: nombre, estado, foto y portada son METADATOS
-- que el servidor SI ve. No van cifrados, porque para mostrarlos a quien te
-- busca sin revelar tu lista de contactos haria falta un esquema mucho mayor.
-- Los mensajes siguen siendo opacos; el perfil no.

ALTER TABLE usuario
    ADD COLUMN nombre_mostrado text,
    ADD COLUMN estado_texto    text,
    ADD COLUMN avatar          bytea,
    ADD COLUMN avatar_actualizado  timestamptz,
    ADD COLUMN portada         bytea,
    ADD COLUMN portada_actualizada timestamptz;

-- Se acotan aqui, no solo en la app: un cliente modificado no debe poder
-- empujar una imagen de 40 MB a la base.
ALTER TABLE usuario
    ADD CONSTRAINT avatar_acotado  CHECK (avatar  IS NULL OR octet_length(avatar)  <=  524288),
    ADD CONSTRAINT portada_acotada CHECK (portada IS NULL OR octet_length(portada) <= 1048576),
    ADD CONSTRAINT nombre_acotado  CHECK (nombre_mostrado IS NULL OR char_length(nombre_mostrado) <= 48),
    ADD CONSTRAINT estado_acotado  CHECK (estado_texto    IS NULL OR char_length(estado_texto)    <= 140);

-- Los grupos tambien tienen foto.
ALTER TABLE conversacion
    ADD COLUMN avatar bytea,
    ADD COLUMN avatar_actualizado timestamptz,
    ADD CONSTRAINT conv_avatar_acotado CHECK (avatar IS NULL OR octet_length(avatar) <= 524288);
