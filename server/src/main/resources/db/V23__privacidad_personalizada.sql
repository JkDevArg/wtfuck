-- ============================================================
--  V23: el nivel "personalizado" de privacidad (L.1, brief §3)
-- ============================================================
--
-- ## Por que es una tabla y no un cuarto nivel
--
-- `todos`, `conocidos` y `nadie` son valores: caben en una columna. "Todos
-- MENOS Fulano" y "solo Mengano" no son valores, son **listas**, y una lista
-- por persona y por ajuste no cabe en un `text`.
--
-- Por eso el nivel se llama `personalizado` y significa "mira la tabla de
-- excepciones para este ajuste". Sin excepciones cargadas se comporta como
-- `nadie` en una lista blanca vacia, que es el unico defecto seguro: si un
-- fallo dejara la lista sin leer, el resultado es no mostrar el dato, no
-- mostrarselo a todos.
--
-- ## Por que dos modos y no uno
--
-- Las dos formas en que la gente piensa la privacidad son opuestas y las dos
-- son legitimas:
--
--   * `salvo`  - "que lo vean todos MENOS estas personas". Lista negra.
--   * `solo`   - "que lo vean SOLO estas personas". Lista blanca.
--
-- Con un solo modo, el otro caso se vuelve imposible o absurdo: una lista
-- negra no puede expresar "solo mi familia" sin enumerar a la plataforma
-- entera.

CREATE TABLE IF NOT EXISTS privacidad_excepcion (
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    -- Que ajuste. Los mismos nombres que viajan en el contrato.
    ajuste      text NOT NULL,

    -- A quien aplica la excepcion.
    otro_id     uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    creado_en   timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (usuario_id, ajuste, otro_id),

    CONSTRAINT ajuste_valido CHECK (ajuste IN (
        'foto', 'estado', 'nombre', 'grupos', 'llamadas', 'busqueda', 'ultima_vez'
    )),

    -- Una excepcion sobre uno mismo no significa nada y solo confundiria al
    -- resolver: el propio perfil siempre se ve completo.
    CONSTRAINT no_sobre_mi CHECK (usuario_id <> otro_id)
);

CREATE INDEX IF NOT EXISTS privacidad_excepcion_por_ajuste
    ON privacidad_excepcion (usuario_id, ajuste);


-- ============================================================
--  El modo de cada ajuste personalizado
-- ============================================================
--
-- Va en `usuario` y no en la tabla de excepciones porque es UNO por ajuste, no
-- uno por excepcion: guardarlo en cada fila permitiria que dos excepciones del
-- mismo ajuste dijeran cosas contrarias.
--
-- `escribe` NO esta en la lista de ajustes personalizables, y es a proposito:
-- una lista blanca de "quien me puede escribir" convierte la cuenta en un club
-- cerrado, y para eso ya estan los bloqueos -que son por persona, explicitos y
-- reciprocos-. `lectura` y `escribiendo` tampoco: son booleanos reciprocos y
-- una lista de "a quien si le mando confirmaciones" es exactamente el espejo de
-- una sola direccion que la reciprocidad evita.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_modo jsonb NOT NULL DEFAULT '{}'::jsonb;
