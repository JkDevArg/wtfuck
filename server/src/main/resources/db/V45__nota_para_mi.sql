-- ============================================================
--  V45: "Nota para mi"
-- ============================================================
--
-- Una conversacion con UNA sola persona, como "Mensajes guardados" de Telegram
-- o "Nota personal" de Signal. Sirve para apuntar, guardar enlaces y reenviarse
-- cosas.
--
-- Por que en el servidor y no solo en el telefono: con varios aparatos, lo que
-- se apunta en uno tiene que aparecer en los otros. Y sale gratis del modelo
-- de siempre: los destinos de un envio son los aparatos de los participantes
-- menos el que envia, asi que en una conversacion donde solo estoy yo, los
-- destinos son MIS OTROS aparatos. El servidor sigue sin ver nada: solo sobres
-- cifrados, igual que en cualquier chat.
--
-- Usa `clave_directa` como una directa: `notas:<usuario>`. La columna es
-- UNIQUE, asi que no puede haber dos por persona aunque dos aparatos la pidan
-- a la vez.

ALTER TABLE conversacion DROP CONSTRAINT IF EXISTS tipo_valido;
ALTER TABLE conversacion ADD CONSTRAINT tipo_valido
    CHECK (tipo IN ('directa', 'grupo', 'canal', 'notas'));

ALTER TABLE conversacion DROP CONSTRAINT IF EXISTS directa_tiene_clave;
ALTER TABLE conversacion ADD CONSTRAINT directa_tiene_clave CHECK (
    (tipo IN ('directa', 'notas') AND clave_directa IS NOT NULL) OR
    (tipo IN ('grupo', 'canal') AND clave_directa IS NULL)
);
