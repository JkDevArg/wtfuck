-- ============================================================
--  V36: una imagen en la publicacion de un canal
-- ============================================================
--
-- Un canal solo podia publicar TEXTO. Para una pagina de anuncios ese es el
-- hueco grande: un aviso con una imagen es lo normal, no la excepcion.
--
-- ## Por que va sin cifrar, y por que eso aqui es una ventaja
--
-- El adjunto normal se cifra en el telefono con una clave aleatoria, y **esa
-- clave viaja dentro del sobre**. Un canal publico no reparte sobres -es lo
-- que le permite escalar-, asi que no hay donde meterla: quien se suscriba
-- manana tendria el archivo y no la llave.
--
-- Asi que va en claro, bajo la misma excepcion que el cuerpo de la
-- publicacion, con los mismos tres motivos de `V10__canales.sql`.
--
-- Y eso habilita algo que el brief pedia y el cifrado hacia imposible:
-- **validar el tipo de archivo en el servidor**. El documento de cobertura
-- dice que las dos cosas eran incompatibles y que se resolvio partiendo por
-- clase -fotos de perfil validadas, adjuntos cifrados no-. Esta es la tercera
-- clase, y cae del lado validable: `Adjuntos.confirmar` lee los primeros bytes
-- del almacen y comprueba la firma real del archivo.
--
-- Un canal PRIVADO sigue siendo solo texto en el servidor, con el mismo precio
-- declarado que el resto del canal privado.
--
-- ## Por que una columna y no una tabla
--
-- Una imagen por publicacion. Un carrusel es otra cosa -otra pantalla, otro
-- gesto- y no se va a inventar aqui por si acaso. Si algun dia hacen falta
-- varias, esta columna se convierte en tabla con una migracion, que es mas
-- barato que mantener hoy una tabla de uno.

ALTER TABLE publicacion_contenido
    ADD COLUMN IF NOT EXISTS adjunto_id uuid REFERENCES adjunto(id) ON DELETE SET NULL;

-- `SET NULL` y no `CASCADE`: si el adjunto se va -por el barrido de huerfanos,
-- por ejemplo- la publicacion **sigue existiendo** con su texto. Perder el
-- aviso entero porque se perdio su imagen seria tirar lo que si esta.

-- Para el barrido: encontrar los adjuntos de canal que ya no cuelgan de
-- ninguna publicacion.
CREATE INDEX IF NOT EXISTS publicacion_por_adjunto
    ON publicacion_contenido (adjunto_id) WHERE adjunto_id IS NOT NULL;
