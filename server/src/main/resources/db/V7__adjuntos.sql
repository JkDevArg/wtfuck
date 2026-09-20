-- wtfuck V7 - adjuntos (modulo D)
--
-- ============================================================
--  LA TENSION CENTRAL DE ESTE MODULO
-- ============================================================
--
-- El brief pide dos cosas que no se pueden cumplir a la vez sobre el mismo
-- archivo:
--
--   1. "Validacion de archivos" (que el servidor compruebe el tipo real).
--   2. Cifrado de extremo a extremo.
--
-- Si el archivo se cifra en el cliente, el servidor ve ruido: no hay firma que
-- inspeccionar. Y si el servidor puede inspeccionarlo, no esta cifrado.
--
-- Reparto elegido, por clase de archivo:
--
--   Fotos de perfil y portadas -> SIN cifrar. El servidor SI valida la firma
--     real del archivo (ya lo hace desde V2). Son publicas por definicion.
--
--   Adjuntos de mensajes -> CIFRADOS en el cliente con una clave por archivo.
--     El servidor valida lo que puede sin leer: permiso, tamano y cuota. La
--     firma del archivo la valida el cliente que lo recibe, DESPUES de
--     descifrarlo y ANTES de pasarlo a un decodificador.
--
-- Los campos `*_declarado` de abajo son justamente eso: lo que el cliente dice
-- que es. Se guardan para poder dibujar la burbuja (tamano, duracion) sin
-- descargar el archivo, pero NO son una verdad verificada por el servidor.

CREATE TABLE adjunto (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),

    -- Se ata al mensaje al confirmarse la subida. Nace suelto porque primero
    -- se sube el archivo y despues se manda el mensaje que lo referencia.
    mensaje_id      uuid REFERENCES mensaje_meta(id) ON DELETE CASCADE,
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,
    subido_por      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    -- imagen | video | audio | nota_voz | documento | sticker
    clase           text NOT NULL,

    -- Bytes del archivo CIFRADO, que es lo que ocupa en el almacen.
    bytes           bigint NOT NULL,

    -- Declarados por el cliente. Ver la cabecera: no son verificables.
    mime_declarado    text,
    nombre_declarado  text,
    ancho             integer,
    alto              integer,
    duracion_ms       integer,

    -- Clave del objeto en el almacen. Aleatoria: no se deriva del nombre del
    -- archivo ni del id del mensaje, para que conocer una no permita adivinar
    -- las demas.
    objeto          text NOT NULL UNIQUE,

    -- Un adjunto reservado y nunca confirmado es basura: el barrido lo limpia.
    confirmado_en   timestamptz,
    creado_en       timestamptz NOT NULL DEFAULT now(),
    expira_en       timestamptz,

    CONSTRAINT clase_valida CHECK (
        clase IN ('imagen','video','audio','nota_voz','documento','sticker')
    ),
    CONSTRAINT bytes_positivo CHECK (bytes > 0),
    -- 64 MB por archivo. El limite real por clase lo aplica el servidor; este
    -- es el techo absoluto, para que ningun camino lo pase por alto.
    CONSTRAINT bytes_acotado CHECK (bytes <= 67108864),
    CONSTRAINT nombre_acotado CHECK (nombre_declarado IS NULL OR char_length(nombre_declarado) <= 200)
);

CREATE INDEX adjunto_por_mensaje ON adjunto (mensaje_id);
CREATE INDEX adjunto_por_conversacion ON adjunto (conversacion_id, id DESC);
-- Los reservados sin confirmar, para el barrido.
CREATE INDEX adjunto_huerfano ON adjunto (creado_en) WHERE confirmado_en IS NULL;


-- ============================================================
--  Cuota por usuario
-- ============================================================

-- Sin cuota, cualquiera llena el disco del servidor. Se mide sobre lo
-- confirmado: un adjunto reservado y abandonado no cuenta.
CREATE OR REPLACE VIEW uso_almacenamiento AS
SELECT subido_por AS usuario_id,
       count(*)          AS archivos,
       coalesce(sum(bytes), 0) AS bytes
FROM adjunto
WHERE confirmado_en IS NOT NULL
GROUP BY subido_por;


-- ============================================================
--  Preferencias de descarga (modulo D.7)
-- ============================================================

ALTER TABLE usuario
    -- siempre | wifi | nunca
    ADD COLUMN descarga_auto text NOT NULL DEFAULT 'wifi',
    ADD CONSTRAINT descarga_auto_valida CHECK (descarga_auto IN ('siempre','wifi','nunca'));


-- ============================================================
--  NOTAS
-- ============================================================
-- 1. El contenido NO vive aqui: vive en el almacen de objetos. Esta tabla solo
--    guarda a que apunta y quien lo subio.
-- 2. `objeto` es unico para que dos adjuntos no puedan pisarse.
-- 3. Un adjunto sin `mensaje_id` y sin `confirmado_en` pasadas 24 h es basura
--    de una subida interrumpida y se borra junto con su objeto.
