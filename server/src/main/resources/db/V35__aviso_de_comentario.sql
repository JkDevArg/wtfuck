-- ============================================================
--  V35: el aviso de comentario en un canal
-- ============================================================
--
-- Mismo caso que `V11__evento_canal.sql`, y el mismo error: `V34` agrego los
-- comentarios y **se olvido del tipo de aviso**. El INSERT revento con
-- "violates check constraint tipo_evento_valido" y la ruta respondio 500.
--
-- Lo cazo la base, que es donde tenia que cazarse. Esa lista cerrada es la
-- razon por la que el defecto salio en la primera ejecucion de la suite y no
-- seis meses despues en forma de clientes recibiendo un tipo de evento que no
-- saben interpretar.
--
-- Va en su propia migracion y no dentro de `V34` porque `V34` ya esta
-- aplicada: **una migracion aplicada no se reescribe, se corrige con la
-- siguiente.** Reescribirla funcionaria en una base nueva y dejaria roto
-- cualquier entorno donde ya corrio. Es la misma nota que dejo `V11`, y esta
-- vez me la salte yo.
--
-- ## A quien se le avisa, y por que no a todos
--
-- Solo a **quien escribio la publicacion**. Un canal con mil suscriptores y
-- cien comentarios por publicacion daria cien mil avisos de algo que nadie
-- pidio seguir. Quien publico si quiere saber que le comentaron; el resto lo
-- ve al abrir los comentarios.

ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion',
        'canal_publicacion', 'canal_aprobado', 'canal_rechazado',
        -- Modulo AA.
        'canal_comentario',
        'advertencia', 'sancion',
        'sesion_revocada', 'dispositivo_vinculado', 'dispositivo_revocado',
        'historial_pedido',
        'llamada_entrante', 'llamada_contestada', 'llamada_terminada',
        'conversacion_cerrada', 'conversacion_reabierta',
        'mensaje_leido'
    )
);
