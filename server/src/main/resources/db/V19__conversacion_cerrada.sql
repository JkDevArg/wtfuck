-- ============================================================
--  V19: una conversacion se puede cerrar desde el panel (H.3)
-- ============================================================
--
-- ## Que es "cerrada" y que NO es
--
-- Cerrada = **nadie escribe mas**. No es borrada, no es oculta y no es
-- expulsar a nadie: los miembros siguen siendo miembros y el historial que ya
-- tienen en sus telefonos sigue siendo suyo. Lo unico que cambia es que la
-- conversacion deja de producir contenido nuevo.
--
-- Esa distincion no es cosmetica. El servidor **no puede** borrar lo que ya se
-- entrego -esta cifrado en aparatos ajenos- y prometer un "borrar el grupo"
-- que solo esconde la fila seria mentir en la pantalla de moderacion, que es
-- el peor sitio para mentir.
--
-- ## Por que no se reusa `restriccion` por persona
--
-- Silenciar a los treinta miembros de un grupo uno por uno tiene tres
-- problemas: es N escrituras, deja de aplicarse a quien entre despues, y al
-- reabrir hay que recordar quien estaba silenciado ANTES para no
-- desilenciarlo por error. El cierre es una propiedad de la conversacion, no
-- de sus miembros, y se guarda donde vive.

ALTER TABLE conversacion ADD COLUMN IF NOT EXISTS cerrada_en timestamptz;
ALTER TABLE conversacion ADD COLUMN IF NOT EXISTS cerrada_por uuid
    REFERENCES usuario(id) ON DELETE SET NULL;
ALTER TABLE conversacion ADD COLUMN IF NOT EXISTS cierre_motivo text;

-- Cerrar sin decir por que deja a treinta personas sin saber que paso ni a
-- quien preguntar.
ALTER TABLE conversacion DROP CONSTRAINT IF EXISTS cierre_tiene_motivo;
ALTER TABLE conversacion ADD CONSTRAINT cierre_tiene_motivo CHECK (
    cerrada_en IS NULL OR (cierre_motivo IS NOT NULL AND cierre_motivo <> '')
);

CREATE INDEX IF NOT EXISTS conversacion_cerrada
    ON conversacion (cerrada_en) WHERE cerrada_en IS NOT NULL;


-- ============================================================
--  El aviso a los miembros
-- ============================================================
--
-- Un grupo que de golpe rechaza todos los mensajes sin explicacion parece la
-- app rota. El aviso es metadato -el servidor ya sabe que cerro ese grupo- y
-- va por el canal de eventos en claro, como los demas.
ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion',
        'canal_publicacion',
        'advertencia', 'sancion',
        'sesion_revocada',
        'dispositivo_vinculado', 'dispositivo_revocado',
        'historial_pedido',
        'llamada_entrante', 'llamada_contestada', 'llamada_terminada',
        'canal_aprobado', 'canal_rechazado',

        -- H.3: la plataforma cerro o reabrio esta conversacion.
        'conversacion_cerrada', 'conversacion_reabierta'
    )
);
