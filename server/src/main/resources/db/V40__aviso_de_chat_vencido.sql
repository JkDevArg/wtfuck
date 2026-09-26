-- Modulo AY · El aviso de que una conversacion temporal vencio.
--
-- Va en su propia migracion y no dentro de V39 porque V39 YA SE APLICO. Editar
-- una migracion que ya corrio en algun sitio es como se arma una base que no
-- coincide con otra: el registro dice que la 39 esta hecha y nadie vuelve a
-- mirarla, asi que lo agregado no se ejecuta nunca ahi y si en las instalaciones
-- nuevas. Cuesta nada hacerlo bien y mucho descubrirlo despues.
--
--
-- `evento_pendiente` valida el tipo contra una lista cerrada, y es una defensa
-- que funciona: sin agregarlo aqui, el barrido fallaba cada diez segundos con
-- un CHECK violado. Un tipo de evento mal escrito no se entrega en silencio.
--
-- Se reconstruye la restriccion entera porque Postgres no deja agregarle un
-- valor a un CHECK existente.
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
    'conversacion_vencida'
]));
