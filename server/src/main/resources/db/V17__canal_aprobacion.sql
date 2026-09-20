-- ============================================================
--  V17: los canales los aprueba el dueno de la plataforma (F.7)
-- ============================================================
--
-- ## Por que un canal necesita permiso y un grupo no
--
-- Un grupo es privado: existe entre quienes se invitaron. Un canal publico es
-- lo contrario -una tribuna abierta a toda la plataforma, con historial que el
-- servidor SI guarda en claro (ver V10)- y eso lo convierte en la superficie
-- con mas alcance y menos control de todo el sistema. Quien responde por lo
-- que se publica ahi es el dueno de la plataforma, asi que es el dueno quien
-- decide que canales existen.
--
-- La consecuencia de diseno importa: la forma de encontrar canales deja de ser
-- un buscador abierto y pasa a ser una LISTA curada. Un buscador abierto sobre
-- contenido sin revisar convierte a la plataforma en el distribuidor de
-- cualquier cosa que alguien haya creado hace cinco minutos.
--
-- ## Por que tambien los privados
--
-- Un canal privado no aparece en ninguna lista, asi que podria auto-aprobarse
-- sin danar a nadie. Se decidio que NO: un canal es un canal, y tener una
-- regla ("los canales los aprueba el dueno") es mas facil de sostener y de
-- explicar que tener una regla con una excepcion que depende de un booleano
-- que ademas se puede cambiar despues con `PUT /v1/canales/{id}`. Un canal
-- privado aprobado que luego se hace publico no se colaria por esa grieta.

-- Los canales que YA existen quedan aprobados: la regla es para lo que venga,
-- no un castigo retroactivo. El truco de las dos sentencias -crear la columna
-- con un default y cambiarlo despues- es lo que consigue las dos cosas sin un
-- UPDATE aparte.
ALTER TABLE canal ADD COLUMN IF NOT EXISTS estado text NOT NULL DEFAULT 'aprobado';
ALTER TABLE canal ALTER COLUMN estado SET DEFAULT 'pendiente';

ALTER TABLE canal DROP CONSTRAINT IF EXISTS canal_estado_valido;
ALTER TABLE canal ADD CONSTRAINT canal_estado_valido
    CHECK (estado IN ('pendiente', 'aprobado', 'rechazado'));

ALTER TABLE canal ADD COLUMN IF NOT EXISTS revisado_por uuid
    REFERENCES usuario(id) ON DELETE SET NULL;
ALTER TABLE canal ADD COLUMN IF NOT EXISTS revisado_en timestamptz;

-- Rechazar sin decir por que es un callejon sin salida para quien lo creo.
ALTER TABLE canal ADD COLUMN IF NOT EXISTS motivo_rechazo text;

ALTER TABLE canal DROP CONSTRAINT IF EXISTS rechazo_tiene_motivo;
ALTER TABLE canal ADD CONSTRAINT rechazo_tiene_motivo CHECK (
    estado <> 'rechazado' OR (motivo_rechazo IS NOT NULL AND motivo_rechazo <> '')
);

-- La cola de revision: pocos canales, ordenados por antiguedad.
CREATE INDEX IF NOT EXISTS canal_pendiente
    ON canal (creado_en) WHERE estado = 'pendiente';

-- El directorio. Es el indice que reemplaza al buscador como forma normal de
-- encontrar un canal.
CREATE INDEX IF NOT EXISTS canal_directorio
    ON canal (conversacion_id) WHERE publico AND estado = 'aprobado';


-- ============================================================
--  Avisos
-- ============================================================
--
-- Quien crea un canal tiene que enterarse de la decision sin estar recargando
-- la pantalla. Son metadatos -el servidor ya sabe que ese canal existe y quien
-- lo creo-, asi que van por el canal de eventos en claro como los demas.
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

        -- F.7: la decision del dueno sobre un canal.
        'canal_aprobado', 'canal_rechazado'
    )
);
