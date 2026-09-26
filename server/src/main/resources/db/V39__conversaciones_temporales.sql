-- Modulo AY · Chats y grupos temporales.
--
-- Una conversacion que se borra sola cuando se cumple su plazo: la
-- conversacion entera, no sus mensajes uno a uno.
--
-- ## Por que una columna y no una tabla aparte
--
-- Porque no es un estado que vaya y venga: una conversacion nace temporal o
-- no, y eso no cambia. Una tabla `conversacion_temporal` obligaria a un JOIN
-- en la consulta mas caliente del servidor —la lista de chats— para un dato
-- que casi siempre es NULL.
--
-- ## Por que `timestamptz` y no un intervalo
--
-- Se guarda CUANDO vence, no cuanto dura. La diferencia importa cuando el
-- servidor se reinicia o la fila se restaura de una copia: una fecha absoluta
-- sigue queriendo decir lo mismo, y un "dura 24 horas" empezaria a contar otra
-- vez desde el principio.
ALTER TABLE conversacion ADD COLUMN IF NOT EXISTS expira_en timestamptz;

-- El barrido busca por fecha entre MUY pocas filas temporales dentro de todas
-- las conversaciones. Un indice parcial indexa solo las que tienen fecha: no
-- paga por las normales, que son casi todas.
CREATE INDEX IF NOT EXISTS conversacion_temporal
    ON conversacion (expira_en) WHERE expira_en IS NOT NULL;

-- Una fecha de vencimiento en el pasado al crear no tiene sentido: seria una
-- conversacion que nace muerta. El CHECK no lo puede comprobar contra `now()`
-- —no es inmutable— pero si puede exigir que, si hay fecha, haya tambien fecha
-- de creacion con la que compararla, que es lo que hace el barrido.
ALTER TABLE conversacion
    ADD CONSTRAINT temporal_coherente
    CHECK (expira_en IS NULL OR expira_en > creada_en);
