-- ============================================================
--  V11: el aviso de publicacion en un canal
-- ============================================================
--
-- `evento_pendiente.tipo` tiene un CHECK con la lista cerrada de avisos que el
-- servidor puede fabricar. Faltaba el de canales, y el INSERT reventaba con
-- "violates check constraint tipo_evento_valido".
--
-- Va en su propia migracion y no dentro de V10 porque V10 ya esta aplicada: una
-- migracion aplicada no se reescribe, se corrige con la siguiente. Reescribirla
-- funcionaria en una base nueva y dejaria roto cualquier entorno donde ya corrio.
--
-- Que la lista sea cerrada es a proposito: un tipo de evento es contrato entre
-- el servidor y todos los clientes, y la base es el lugar donde ese contrato no
-- se puede saltear por descuido. La molestia de tener que migrar para agregar
-- uno es justamente la que hace que nadie invente tipos sobre la marcha.

ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion',

        -- Modulo F. Un canal publico NO reparte sobres: emite este aviso y
        -- cada cliente se trae el historial cuando quiere. Con diez mil
        -- suscriptores, un sobre por dispositivo serian diez mil filas por
        -- publicacion.
        'canal_publicacion'
    )
);
