-- ============================================================
--  V34: el cuerpo de los comentarios de un canal publico
-- ============================================================
--
-- ## El defecto que arregla, porque no era una pantalla que faltaba
--
-- Comentar una publicacion de un canal publico **perdia el texto**. No se veia
-- como una perdida: el contador de la tarjeta decia "1 comentario" y no habia
-- ningun comentario que mostrar en ninguna parte.
--
-- La causa esta en dos decisiones correctas que juntas dejaban un agujero:
--
--   1. Un canal publico **no reparte sobres** (ver `Canales.guardarContenido`).
--      Es lo que le permite escalar: con diez mil suscriptores, un sobre por
--      dispositivo serian diez mil filas por publicacion. En vez de eso se
--      emite un aviso y cada cliente se trae el historial.
--   2. Un comentario se mandaba como **mensaje normal cifrado**, por el buzon.
--
-- En un grupo eso funciona. En un canal publico no hay a quien entregarle el
-- sobre, asi que quedaba la fila de `mensaje_meta` -de ahi el contador, que
-- cuenta metadatos y era honesto- y el cuerpo se iba al vacio. Medido en la
-- base antes de escribir esto: cada comentario tenia 0 sobres y 0 cuerpo
-- guardado.
--
-- ## Por que el cuerpo va aqui, en claro
--
-- Por los mismos tres motivos que ya estan argumentados en `V10__canales.sql`
-- para el cuerpo de la publicacion, y **no hay que estirar ninguno**: un
-- comentario en una publicacion publica es tan publico como la publicacion.
--
--   1. No hay secreto que guardar. Cualquiera se suscribe y lee.
--   2. Un canal sin historial no es un canal, y un comentario que no se puede
--      leer no es un comentario.
--   3. Cifrar por dispositivo no escala, y de comentarios hay mas volumen que
--      de publicaciones.
--
-- Lo que NO se hace es esconderlo: la ficha del canal ya dice que el contenido
-- de un canal publico no va cifrado de extremo a extremo, y eso ahora tambien
-- cubre los comentarios.
--
-- Los canales PRIVADOS no tienen fila aqui. Ahi el comentario sigue siendo un
-- mensaje cifrado y solo lo ve quien recibio el sobre, con el mismo precio
-- declarado que el resto del canal privado: quien se suscribe despues no ve lo
-- de antes.

CREATE TABLE IF NOT EXISTS comentario_contenido (
    mensaje_id      uuid PRIMARY KEY REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,

    -- A que publicacion responde.
    --
    -- Se guarda aqui **ademas** de en `mensaje_meta.responde_a`, y no es
    -- duplicado por descuido: `responde_a` puede apuntar a cualquier mensaje
    -- de la conversacion, y esta columna lleva una FK a la publicacion, o sea
    -- la base garantiza que un comentario cuelga de una publicacion y no de
    -- otro comentario. Al borrarse la publicacion se van sus comentarios.
    publicacion_id  uuid NOT NULL REFERENCES publicacion_contenido(mensaje_id) ON DELETE CASCADE,

    -- EN CLARO. Ver la nota de arriba.
    cuerpo          text NOT NULL,

    creado_en       timestamptz NOT NULL DEFAULT now(),

    -- Mas corto que una publicacion (8192) a proposito: un comentario que
    -- ocupa lo mismo que el articulo que comenta no es un comentario, y el
    -- limite es lo unico que evita que el muro de un canal se use de almacen.
    CONSTRAINT comentario_acotado CHECK (length(cuerpo) > 0 AND length(cuerpo) <= 2048)
);

-- La consulta real: "dame los comentarios de esta publicacion, los primeros
-- arriba". ASC y no DESC: una conversacion se lee en el orden en que paso, al
-- contrario que el muro, donde lo ultimo va primero.
CREATE INDEX IF NOT EXISTS comentario_por_publicacion
    ON comentario_contenido (publicacion_id, mensaje_id);

-- Para contar los de un canal entero sin recorrer sus publicaciones.
CREATE INDEX IF NOT EXISTS comentario_por_canal
    ON comentario_contenido (conversacion_id);
