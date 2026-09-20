-- Modulo P · Tres tipos de cuenta: normal, desarrollador y empresa.
--
-- ## Que problema resuelve
--
-- Hasta aqui todas las cuentas eran iguales y la unica distincion era
-- `staff_nivel`, que es OTRA cosa: staff es poder sobre la plataforma
-- —moderar, suspender—, y esto es **que clase de cuenta soy**. Mezclarlos
-- habria significado que declararse empresa diera poder de moderacion, o que un
-- moderador no pudiera tener ficha de empresa. Son dos ejes y van en dos
-- columnas.
--
-- ## Por que UNA columna y no dos banderas
--
-- Los tres tipos son excluyentes: una cuenta es una cosa. Con banderas
-- separadas (`es_desarrollador`, `es_empresa`) el estado "las dos a la vez"
-- seria representable y habria que decidir que significa en cada pantalla.
-- Un CHECK con tres valores hace que ese estado no exista.
--
-- ## Quien puede cambiarlo, y por que no es igual para los tres
--
-- Esto es lo importante y esta implementado en `Cuentas.kt`:
--
--   - `desarrollador` lo otorga **staff**, nunca uno mismo. Es un modo con
--     capacidades tecnicas; auto-otorgarselo seria una escalada de privilegio
--     con otro nombre.
--   - `empresa` lo activa **la propia persona**, porque es una declaracion
--     sobre si misma, como el estado o la biografia. Pero nace **sin
--     verificar**: cualquiera puede escribir "Banco Nacional" en su ficha, y
--     por eso el distintivo de verificada lo pone staff y solo staff. Sin esa
--     separacion, la ficha de empresa seria una herramienta de suplantacion.
--   - `normal` es el valor por defecto y se puede volver a el siempre.

ALTER TABLE usuario ADD COLUMN IF NOT EXISTS tipo_cuenta text NOT NULL DEFAULT 'normal';

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS tipo_cuenta_valido;
ALTER TABLE usuario ADD CONSTRAINT tipo_cuenta_valido
    CHECK (tipo_cuenta IN ('normal', 'desarrollador', 'empresa'));

-- La ficha de empresa va en su propia tabla y no en columnas de `usuario`.
--
-- Son siete campos que solo tienen sentido para un tipo de cuenta: en `usuario`
-- serian siete columnas nulas para el 99% de las filas, y cada consulta de
-- perfil las arrastraria. Aparte, la ficha se borra al dejar de ser empresa sin
-- tocar la cuenta.
CREATE TABLE IF NOT EXISTS perfil_empresa (
    usuario_id      uuid PRIMARY KEY REFERENCES usuario(id) ON DELETE CASCADE,
    nombre_comercial text NOT NULL,
    -- Lista cerrada, validada en el servidor. Texto libre no se puede filtrar
    -- ni moderar, y una categoria que nadie puede buscar no es una categoria.
    categoria       text NOT NULL,
    descripcion     text,
    sitio_web       text,
    -- Rango, no numero exacto: nadie mantiene actualizado un numero exacto, y
    -- el rango es lo que de verdad se usa para decidir con quien se habla.
    tamano          text,
    ubicacion       text,
    fundada_en      smallint,
    -- El distintivo. Lo pone staff y solo staff: ver la nota de arriba.
    verificada_en   timestamptz,
    verificada_por  uuid REFERENCES usuario(id) ON DELETE SET NULL,
    creada_en       timestamptz NOT NULL DEFAULT now(),
    actualizada_en  timestamptz NOT NULL DEFAULT now()
);

-- Para el directorio: buscar empresas por categoria es la consulta que da
-- sentido a tener una categoria.
CREATE INDEX IF NOT EXISTS empresa_por_categoria ON perfil_empresa (categoria);
