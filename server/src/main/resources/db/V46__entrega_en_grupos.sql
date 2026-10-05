-- ============================================================
--  V46: a quien le llego cada mensaje de un grupo
-- ============================================================
--
-- Para "Info del mensaje": en un grupo, el doble check dice que le llego a
-- alguien, no a quien. La lectura ya se guardaba (`lectura`, V20); la entrega
-- no, porque acusar un sobre lo BORRA del buzon y con el se iba el dato.
--
-- Solo en grupos, a proposito. En una directa el check ya lo dice todo, y no
-- guardar es la forma mas segura de no tener un dato. Y solo el hecho: quien,
-- que mensaje y cuando. Vive lo que vive `mensaje_meta`: se borra con el.
CREATE TABLE IF NOT EXISTS entrega (
    mensaje_id   uuid NOT NULL REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    usuario_id   uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    entregado_en timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (mensaje_id, usuario_id)
);
