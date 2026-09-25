-- ============================================================
--  V38: avisar de lo que hace cada participante de una llamada
-- ============================================================
--
-- ## El hueco que abrio el modulo AF
--
-- Hasta AF, que alguien rechazara una llamada la terminaba entera, y el fin de
-- la llamada SI se avisaba (`llamada_terminada`). AF arreglo eso —un "no" de
-- uno no puede cortarle el timbre a los otros dos— y con ello aparecio un
-- silencio nuevo: **ahora la llamada sigue y quien llamo no se entera de
-- nada**.
--
-- En una llamada de tres, A llama a B y C, B declina, y la pantalla de A
-- sigue diciendo "llamando" por B durante los cuarenta y cinco segundos del
-- timbre. Un telefono normal dice "rechazada" en el acto, y esa diferencia no
-- es cosmetica: A se queda esperando a alguien que ya dijo que no.
--
-- El evento no cierra nada. Solo cuenta lo que le paso a UN participante, y
-- por eso es un tipo aparte y no un `llamada_terminada` con otro motivo: un
-- cliente que reciba "terminada" cuelga, y aqui la llamada sigue viva.
--
-- ## Por que una migracion para una palabra
--
-- Porque `tipo` es una lista cerrada. Es la misma nota de `V11` y `V35`: sin
-- esto el INSERT revienta contra `tipo_evento_valido` y la ruta responde 500.
-- Esa lista cerrada es exactamente lo que hace que el error salga en la
-- primera ejecucion de la suite y no seis meses despues, en forma de clientes
-- recibiendo un tipo de evento que no saben interpretar.
--
-- Y va en su propia migracion, no dentro de `V37`: **una migracion aplicada no
-- se reescribe, se corrige con la siguiente.** Reescribirla funcionaria en una
-- base nueva y dejaria roto cualquier entorno donde ya corrio.

ALTER TABLE evento_pendiente DROP CONSTRAINT IF EXISTS tipo_evento_valido;

ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion',
        'canal_publicacion', 'canal_aprobado', 'canal_rechazado',
        'canal_comentario',
        'advertencia', 'sancion',
        'sesion_revocada', 'dispositivo_vinculado', 'dispositivo_revocado',
        'historial_pedido',
        'llamada_entrante', 'llamada_contestada', 'llamada_terminada',
        -- Modulo AF. Lo que le paso a UNA persona dentro de una llamada que
        -- sigue viva: rechazo, entro, se fue.
        'llamada_participante',
        'conversacion_cerrada', 'conversacion_reabierta',
        'mensaje_leido'
    )
);
