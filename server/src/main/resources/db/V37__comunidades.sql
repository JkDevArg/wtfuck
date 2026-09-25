-- ============================================================
--  V37: comunidades (modulo AD)
-- ============================================================
--
-- Era el ultimo hueco declarado del brief. El §3 pide un ajuste de
-- "invitaciones a comunidades" y la cobertura decia, con razon, que faltaba el
-- **contenedor entero** y no el ajuste.
--
-- ## Que es una comunidad aqui, y que la distingue de una carpeta
--
-- Un conjunto de grupos bajo un nombre, **mas un canal de anuncios**. Lo
-- segundo es lo unico que la hace una comunidad: sin el, agrupar chats es una
-- carpeta, y una carpeta se resuelve en el telefono sin que el servidor se
-- entere. El canal de anuncios es un sitio donde quien administra alcanza a
-- todos de una vez, y eso si necesita existir en el servidor.
--
-- ## La pertenencia se DERIVA, no se declara
--
-- Sos de la comunidad si sos de alguno de sus grupos. No hay una lista de
-- miembros aparte, y eso es deliberado: dos listas que dicen lo mismo se
-- separan, y el dia que se separan nadie sabe cual manda. Lo que si existe es
-- la fila de `participante` del canal de anuncios, que se **mantiene** cuando
-- un grupo entra, cuando alguien entra a un grupo y cuando alguien se va.
--
-- La consecuencia, dicha: agregar un grupo a una comunidad mete a toda su
-- gente en el canal de anuncios. Por eso hay un ajuste de privacidad -abajo- y
-- por eso ese ajuste existe.
--
-- ## Un grupo pertenece a UNA comunidad como mucho
--
-- Con dos, "salir de la comunidad" deja de tener un significado unico y
-- aparecen preguntas sin respuesta buena: ¿de que anuncios te vas? Es la clase
-- de generalidad que se agrega barata y se paga cara.

CREATE TABLE IF NOT EXISTS comunidad (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    nombre       text NOT NULL,
    descripcion  text NOT NULL DEFAULT '',
    creador_id   uuid REFERENCES usuario(id) ON DELETE SET NULL,

    -- El canal de anuncios. Es una `conversacion` de tipo 'canal', privada:
    -- reusa participante, roles, permisos, mensajes y adjuntos sin duplicar
    -- nada. Lo unico propio de la comunidad es que agrupa grupos.
    anuncios_id  uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,

    creada_en    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT comunidad_nombre_acotado CHECK (length(nombre) BETWEEN 1 AND 64),
    CONSTRAINT comunidad_descripcion_acotada CHECK (length(descripcion) <= 512)
);

CREATE TABLE IF NOT EXISTS comunidad_grupo (
    comunidad_id    uuid NOT NULL REFERENCES comunidad(id) ON DELETE CASCADE,
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    agregado_en     timestamptz NOT NULL DEFAULT now(),
    agregado_por    uuid REFERENCES usuario(id) ON DELETE SET NULL,
    PRIMARY KEY (comunidad_id, conversacion_id)
);

-- Un grupo en una sola comunidad. Ver la nota de arriba.
CREATE UNIQUE INDEX IF NOT EXISTS comunidad_grupo_unico
    ON comunidad_grupo (conversacion_id);

-- "¿de que comunidad es este grupo?" y "¿que grupos tiene esta comunidad?".
CREATE INDEX IF NOT EXISTS comunidad_grupo_por_comunidad
    ON comunidad_grupo (comunidad_id);


-- ============================================================
--  El ajuste del §3, y por que es este y no otro
-- ============================================================
--
-- El brief pide "invitaciones a comunidades". En este modelo a nadie se lo
-- invita a una comunidad: se lo agrega a un **grupo**, y eso ya lo gobierna
-- `priv_grupos`.
--
-- Lo que `priv_grupos` NO cubre es el caso propio de las comunidades: alguien
-- agrega a una comunidad **el grupo en el que ya estabas**, y de golpe estas
-- en un canal de anuncios con quinientos desconocidos, sin que nadie te haya
-- agregado a nada. Ese es el hecho nuevo, y es el que este ajuste gobierna.
--
--   todos      -> me meten en el canal de anuncios
--   conocidos  -> solo si quien agrega el grupo es alguien con quien ya hablo
--   nadie      -> mi grupo puede estar en la comunidad; yo no en sus anuncios
--
-- `nadie` NO te saca del grupo ni te echa de la comunidad: seguis donde
-- estabas. Lo unico que deja de pasar es que te sumen a un canal que no
-- pediste. Un ajuste de privacidad que te expulsa de algo no es un ajuste de
-- privacidad, es una sancion.
ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS priv_comunidades text NOT NULL DEFAULT 'todos';

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS priv_comunidades_valido;
ALTER TABLE usuario ADD CONSTRAINT priv_comunidades_valido
    CHECK (priv_comunidades IN ('todos', 'conocidos', 'nadie', 'personalizado'));
