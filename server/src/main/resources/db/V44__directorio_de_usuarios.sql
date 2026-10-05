-- El directorio de Usuarios: la lista de gente que QUISO aparecer.
--
-- ## Por que nace en false
--
-- Estar en una lista que cualquiera con cuenta puede recorrer es exponerse, y
-- en una mensajeria privada el defecto tiene que ser el seguro. Quien no toca
-- nunca los ajustes -que es casi todo el mundo- no aparece. Aparece quien
-- marca la casilla, y solo desde ese momento. Es un consentimiento, y un
-- consentimiento no se presume (Ley 29733, art. 13: el titular decide).
--
-- ## Por que una columna y no un nivel mas de priv_busqueda
--
-- Son dos preguntas distintas. `priv_busqueda` dice quien puede ENCONTRARME
-- si escribe mi usuario exacto; esto dice si aparezco en una lista sin que
-- nadie sepa mi usuario de antes. La segunda expone mucho mas que la primera,
-- y mezclarlas obligaria a elegir entre las dos.
--
-- El directorio respeta ademas priv_busqueda: quien aparece en la lista tiene
-- que poder abrirse al tocarlo, y el perfil ya exige `buscable`.

ALTER TABLE usuario ADD COLUMN priv_directorio boolean NOT NULL DEFAULT false;

-- Parcial: la lista recorre solo a quienes se apuntaron, que van a ser pocos.
-- Ordenado por username porque la paginacion es por clave ("desde").
CREATE INDEX usuario_en_directorio ON usuario (username) WHERE priv_directorio;
