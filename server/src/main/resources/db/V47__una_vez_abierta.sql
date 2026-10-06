-- ============================================================
--  V47: avisar que un "ver una vez" se abrio
-- ============================================================
--
-- Dos cosas que faltaban desde la tanda 8-18:
--  - quien lo mando no se enteraba de que se abrio;
--  - con varios aparatos, cada uno dejaba verlo una vez: abrirlo en el
--    telefono no lo cerraba en la tablet.
--
-- El aviso va como evento, igual que "leido": a los aparatos de quien lo
-- escribio -si los dos comparten confirmaciones de lectura, como con "leido"-
-- y a los OTROS aparatos de quien lo abrio, siempre. Solo el id del mensaje:
-- el servidor sigue sin saber que habia en el.
--
-- Se reconstruye la lista entera de tipos: Postgres no deja agregarle un
-- valor a un CHECK existente. Ver V42.
ALTER TABLE evento_pendiente DROP CONSTRAINT IF EXISTS tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (tipo = ANY (ARRAY[
    'agregado_grupo', 'sacado_grupo', 'grupo_renombrado', 'expulsado', 'silenciado',
    'rol_cambiado', 'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
    'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado', 'mensaje_reaccion',
    'canal_publicacion', 'canal_aprobado', 'canal_rechazado', 'canal_comentario',
    'advertencia', 'sancion', 'sesion_revocada', 'dispositivo_vinculado',
    'dispositivo_revocado', 'historial_pedido', 'llamada_entrante',
    'llamada_contestada', 'llamada_terminada', 'llamada_participante',
    'conversacion_cerrada', 'conversacion_reabierta', 'mensaje_leido',
    'conversacion_vencida', 'conversacion_temporales',
    'una_vez_abierta'
]));
