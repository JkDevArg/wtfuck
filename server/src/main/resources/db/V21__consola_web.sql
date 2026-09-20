-- ============================================================
--  V21: la consola web de administracion (L.8)
-- ============================================================
--
-- ## Por que la consola web NO es un cliente de mensajeria
--
-- Un cliente web de mensajeria tendria que descifrar, y para descifrar hace
-- falta ser un dispositivo con sus propias claves de identidad -eso es lo que
-- el modulo J hizo posible-. Llevar libsignal al navegador es un proyecto
-- aparte: WASM, el almacen de claves en IndexedDB, y la pregunta seria de si
-- un navegador es un sitio donde poner claves de largo plazo.
--
-- Lo que SI se puede llevar a la web sin mentir es el **panel**: todo lo que
-- toca son metadatos que el servidor ya conoce porque los necesita para
-- autorizar. Denuncias, cuentas, canales por aprobar, limites, bitacora.
-- Ningun mensaje. Y moderar desde una pantalla grande es de verdad mas util
-- que moderar desde un telefono.
--
-- ## Por que un token aparte y no la sesion normal
--
-- Entrar con usuario y contrasena desde el navegador crearia un DISPOSITIVO, y
-- ahi se rompen dos reglas de golpe: una cuenta por hardware -un navegador no
-- tiene hardware que atestiguar- y la promesa de que cada dispositivo es una
-- copia mas de los mensajes. Un navegador no puede ser eso.
--
-- Asi que la raiz de confianza sigue siendo el telefono: quien ya esta dentro,
-- con su contrasena y su segundo factor, **emite** un token de consola y lo
-- pega en el navegador. El token:
--
--   * sirve SOLO para las rutas del panel. No puede leer ni mandar mensajes;
--   * vive horas, no meses;
--   * se ve y se revoca desde el telefono, como una sesion;
--   * no da mas nivel del que ya tenia quien lo emitio.

CREATE TABLE IF NOT EXISTS token_consola (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id     uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    -- Igual que las sesiones: se guarda el HASH. Una copia de la base no
    -- entrega tokens usables.
    token_hash     bytea NOT NULL UNIQUE,

    -- Desde que aparato se emitio. Sirve para reconocerlo en la lista.
    emitido_desde  uuid REFERENCES dispositivo(id) ON DELETE SET NULL,
    etiqueta       text,

    creado_en      timestamptz NOT NULL DEFAULT now(),
    expira_en      timestamptz NOT NULL,
    ultimo_uso_en  timestamptz,
    revocado_en    timestamptz,

    -- Una consola que no caduca es una sesion eterna con otro nombre.
    CONSTRAINT expira_despues CHECK (expira_en > creado_en)
);

CREATE INDEX IF NOT EXISTS token_consola_por_usuario
    ON token_consola (usuario_id, creado_en DESC);

CREATE INDEX IF NOT EXISTS token_consola_vivos
    ON token_consola (expira_en) WHERE revocado_en IS NULL;
