-- ============================================================
--  V10: canales (modulo F)
-- ============================================================
--
-- Un canal es una conversacion con `tipo = 'canal'`. Reusa participante,
-- roles, permisos, mensajes, reacciones y adjuntos sin cambiar nada de eso:
-- lo unico propio de un canal es QUIEN puede hablar y como se lo encuentra.

ALTER TABLE conversacion DROP CONSTRAINT IF EXISTS tipo_valido;
ALTER TABLE conversacion ADD CONSTRAINT tipo_valido
    CHECK (tipo IN ('directa', 'grupo', 'canal'));

-- El CHECK de V1 exigia clave_directa solo para 'directa'. Se rehace para que
-- un canal quede en la misma rama que un grupo: sin clave de par.
ALTER TABLE conversacion DROP CONSTRAINT IF EXISTS directa_tiene_clave;
ALTER TABLE conversacion ADD CONSTRAINT directa_tiene_clave CHECK (
    (tipo = 'directa' AND clave_directa IS NOT NULL) OR
    (tipo IN ('grupo', 'canal') AND clave_directa IS NULL)
);

CREATE TABLE IF NOT EXISTS canal (
    conversacion_id uuid PRIMARY KEY REFERENCES conversacion(id) ON DELETE CASCADE,

    -- Alias publico, el "@algo" con el que se encuentra el canal.
    alias           text UNIQUE,
    publico         boolean NOT NULL DEFAULT false,
    descripcion     text    NOT NULL DEFAULT '',

    -- Si los suscriptores pueden comentar las publicaciones. Un canal sin
    -- comentarios es unidireccional; con comentarios es semidireccional.
    comentarios     boolean NOT NULL DEFAULT false,
    reacciones      boolean NOT NULL DEFAULT true,

    creado_en       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT alias_valido CHECK (alias IS NULL OR alias ~ '^[a-z0-9_]{4,32}$'),

    -- Un canal publico SIN alias no se puede encontrar, y entonces no es
    -- publico: es privado con otro nombre. La base lo impide.
    CONSTRAINT publico_necesita_alias CHECK (NOT publico OR alias IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS canal_publico ON canal (alias) WHERE publico;


-- ============================================================
--  LA EXCEPCION: aqui el servidor SI guarda contenido
-- ============================================================
--
-- Hasta ahora la regla era absoluta: el servidor guarda metadatos de mensaje y
-- jamas el contenido. Los canales PUBLICOS rompen esa regla, a proposito, y
-- conviene entender por que antes de juzgarlo.
--
-- 1. Un canal publico no tiene secreto que guardar. Cualquiera se suscribe y
--    lee; el "extremo a extremo" no protege nada cuando uno de los extremos es
--    todo el mundo.
--
-- 2. Un canal sin historial no es un canal. Quien se suscribe hoy espera ver
--    lo de ayer, y para eso alguien tiene que haberlo guardado. Con E2EE puro
--    no hay nadie que pueda: el remitente no puede cifrar para un suscriptor
--    que todavia no existe.
--
-- 3. El costo de cifrar por dispositivo no escala a un canal. Diez mil
--    suscriptores serian diez mil copias por publicacion.
--
-- Es lo mismo que hacen Telegram y WhatsApp con sus canales, y por los mismos
-- motivos. Lo que NO se hace es esconderlo: la app dice en la ficha del canal
-- que el contenido no va cifrado de extremo a extremo.
--
-- Los canales PRIVADOS no tienen fila aqui: siguen cifrados como un grupo. El
-- precio, declarado, es que un canal privado no puede mostrar historial a
-- quien se suscribe despues.

CREATE TABLE IF NOT EXISTS publicacion_contenido (
    mensaje_id      uuid PRIMARY KEY REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,

    -- EN CLARO. Ver la nota de arriba.
    cuerpo          text NOT NULL,

    creado_en       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT cuerpo_acotado CHECK (length(cuerpo) <= 8192)
);

-- La consulta del historial: "dame las ultimas N publicaciones de este canal".
CREATE INDEX IF NOT EXISTS publicacion_por_canal
    ON publicacion_contenido (conversacion_id, mensaje_id DESC);


-- ============================================================
--  Rol de suscriptor
-- ============================================================
--
-- Un suscriptor NO es un miembro con menos permisos: es otra cosa. Puede leer
-- y (si el canal lo permite) reaccionar y comentar, pero no publicar. Se crea
-- como rol de sistema para que un canal nuevo no tenga que inventarlo.

-- Jerarquia 8: por debajo de miembro (10) y por encima de restringido (5).
-- Un suscriptor no manda sobre nadie, y eso es correcto.
INSERT INTO rol (clave, nombre, jerarquia, es_sistema)
VALUES ('suscriptor', 'Suscriptor', 8, true)
ON CONFLICT (clave) WHERE es_sistema DO NOTHING;

-- Lo minimo para leer un canal. Reaccionar y comentar se conceden aparte,
-- segun la configuracion del canal, para que apagarlas surta efecto de verdad.
INSERT INTO rol_permiso (rol_id, permiso)
SELECT r.id, p.clave
FROM rol r, permiso p
WHERE r.clave = 'suscriptor' AND r.es_sistema
  AND p.clave IN (
      'grupo.ver_info', 'miembro.ver',
      -- Comentar y reaccionar los tiene el ROL, pero el interruptor del canal
      -- es lo que decide si de verdad puede: apagar los comentarios tiene que
      -- surtir efecto sin tocar permisos de nadie.
      'canal.comentar', 'mensaje.reaccionar', 'mensaje.responder',
      'mensaje.borrar_propio'
  )
ON CONFLICT DO NOTHING;
