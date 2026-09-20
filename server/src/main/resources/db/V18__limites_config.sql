-- ============================================================
--  V18: los limites de abuso se pueden ajustar sin recompilar (H.6)
-- ============================================================
--
-- ## Por que esto es una tabla y no una constante mas
--
-- Los limites son la unica defensa contra el abuso automatizado, y el numero
-- correcto no se sabe de antemano: se descubre mirando la plataforma real. Con
-- los numeros compilados, ajustar uno es un despliegue, y un despliegue en
-- medio de un ataque es lo ultimo que se quiere estar haciendo.
--
-- ## Lo que NO es
--
-- No es una tabla de configuracion general. Aqui solo viven los limites que ya
-- existen en el codigo, con su nombre; la tabla **sobreescribe** un valor por
-- defecto que sigue estando en el codigo y sigue siendo el que manda si esta
-- fila no existe. Asi una base vacia se comporta igual que antes, y borrar una
-- fila es volver al valor probado en vez de quedarse sin limite.
--
-- ## Por que no hay "sin limite"
--
-- `tope` y `ventana_s` son mayores que cero por CHECK. Un limite configurable
-- que admite cero o infinito no es un limite: es un interruptor para apagar la
-- defensa, y tarde o temprano alguien lo usa "un momento" y se olvida.

CREATE TABLE IF NOT EXISTS limite_config (
    clave          text PRIMARY KEY,
    tope           int  NOT NULL,
    ventana_s      int  NOT NULL,

    -- Quien lo cambio y cuando. Un limite relajado es una decision de
    -- seguridad y tiene que tener nombre y fecha.
    actualizado_por uuid REFERENCES usuario(id) ON DELETE SET NULL,
    actualizado_en  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT tope_positivo CHECK (tope > 0),
    CONSTRAINT ventana_positiva CHECK (ventana_s > 0),
    -- Un techo, para que un error de tipeo no deje una ventana de un mes.
    CONSTRAINT ventana_razonable CHECK (ventana_s <= 86400),
    CONSTRAINT tope_razonable CHECK (tope <= 1000000)
);
