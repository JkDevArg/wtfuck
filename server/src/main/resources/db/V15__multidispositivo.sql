-- ============================================================
--  V15: varios dispositivos por cuenta
-- ============================================================
--
-- Este es el modulo que el esquema venia esperando. Desde V1, la direccion
-- criptografica de una sesion de Signal es el DISPOSITIVO y no la persona, y
-- `Claves.destinos` ya devolvia todos los dispositivos activos de todos los
-- participantes. El reparto de sobres por dispositivo funciona desde el modulo
-- E. Lo unico que faltaba era permitir que un usuario tenga mas de uno.


-- ============================================================
--  LAS DOS REGLAS NO ERAN LA MISMA, aunque se escribieron juntas
-- ============================================================
--
-- V1 puso dos indices unicos parciales y los llamo "REGLA 1" y "REGLA 2":
--
--   1. un solo dispositivo activo por USUARIO
--   2. un solo dispositivo activo por HARDWARE
--
-- Se leian como una sola idea -"una cuenta, un telefono"- y son dos cosas
-- distintas con proposito distinto:
--
--   * La 2 impide DUPLICAR CUENTAS: un telefono, una identidad. Es la que
--     sostiene el device binding de docs/04-DEVICE-BINDING.md y **se queda**.
--   * La 1 impedia tener la misma cuenta en el telefono y en la tablet. Eso
--     nunca fue un requisito de seguridad: era la consecuencia de no haber
--     construido todavia la vinculacion. **Se va.**
--
-- Confundirlas habria costado caro: levantar las dos juntas habria abierto la
-- puerta a varias cuentas por telefono, que es justo lo que el proyecto no
-- quiere.

DROP INDEX dispositivo_unico_por_usuario;

-- La 2 sigue en pie. Se recrea con un nombre que dice lo que hace, para que no
-- vuelva a leerse como parte de un par.
DROP INDEX dispositivo_unico_por_hardware;
CREATE UNIQUE INDEX dispositivo_un_hardware_una_cuenta
    ON dispositivo (hardware_hash) WHERE revocado_en IS NULL;

COMMENT ON INDEX dispositivo_un_hardware_una_cuenta IS
    'Un hardware activo no puede pertenecer a dos cuentas. Es la regla anti-duplicacion; NO limita cuantos dispositivos tiene una cuenta.';


-- ============================================================
--  QUIEN ES EL PRINCIPAL, Y POR QUE HACE FALTA UNO
-- ============================================================
--
-- Con N dispositivos hay decisiones que no puede tomar cualquiera: autorizar
-- uno nuevo, por ejemplo. Si todos pudieran, robar un dispositivo secundario
-- alcanzaria para vincular mas y la cuenta no se podria recuperar nunca.
--
-- El principal es el que registro la cuenta. No se puede revocar a si mismo
-- -eso dejaria la cuenta sin quien autorice-, y para cambiarlo hay que
-- promover otro explicitamente.

ALTER TABLE dispositivo
    ADD COLUMN principal boolean NOT NULL DEFAULT false,
    -- Quien autorizo este dispositivo. Null en el principal, que se autorizo
    -- a si mismo al registrarse. Sirve para responder "¿de donde salio esto?"
    -- cuando alguien encuentra un dispositivo que no reconoce.
    ADD COLUMN vinculado_por uuid REFERENCES dispositivo(id) ON DELETE SET NULL,
    ADD COLUMN revocado_por  uuid REFERENCES dispositivo(id) ON DELETE SET NULL;

-- Las cuentas que ya existen: su unico dispositivo es el principal.
UPDATE dispositivo SET principal = true WHERE revocado_en IS NULL;

-- Un solo principal activo por cuenta. Que lo garantice la base y no el codigo
-- es lo que hace imposible el estado "dos principales" por una carrera entre
-- dos promociones simultaneas.
CREATE UNIQUE INDEX dispositivo_un_principal
    ON dispositivo (usuario_id) WHERE principal AND revocado_en IS NULL;


-- ============================================================
--  CODIGOS DE VINCULACION
-- ============================================================
--
-- Como entra un dispositivo nuevo. El principal genera un codigo corto y la
-- persona lo escribe en el aparato nuevo.
--
-- Se eligio un codigo para TECLEAR en vez de un QR para escanear por una razon
-- practica: no hay lector de QR implementado, y un codigo de ocho caracteres se
-- copia a mano entre dos pantallas que uno tiene delante. El QR es mas comodo
-- pero no mas seguro, y se puede agregar despues sin tocar nada de esto.
--
-- La direccion importa y no es arbitraria: **lo genera el que YA tiene la
-- cuenta**, no el aparato nuevo. Al reves, cualquiera podria generar un codigo
-- y pedirle a la victima que lo apruebe, que es el patron de estafa de las
-- vinculaciones de WhatsApp Web.

CREATE TABLE codigo_vinculacion (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id    uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    -- Quien lo emitio. Queda anotado para que el dispositivo nuevo pueda decir
    -- "autorizado desde el Pixel 9" y no solo "autorizado".
    emisor_id     uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,

    -- Hash del codigo, nunca el codigo. Vive cinco minutos, pero una fuga de
    -- lectura de la base no deberia entregar una vinculacion viva: eso
    -- convertiria un leak en un robo de cuenta.
    codigo_hash   bytea NOT NULL,

    -- Tope de intentos. Ocho caracteres son muchas combinaciones, pero sin
    -- tope el vencimiento de cinco minutos no es una defensa completa.
    intentos      smallint NOT NULL DEFAULT 0,

    expira_en     timestamptz NOT NULL,
    usado_en      timestamptz,
    -- Que dispositivo lo consumio. Cierra el rastro: del codigo al aparato.
    usado_por     uuid REFERENCES dispositivo(id) ON DELETE SET NULL,
    creado_en     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX codigo_vinculacion_vivo ON codigo_vinculacion (usuario_id)
    WHERE usado_en IS NULL;
CREATE INDEX codigo_vinculacion_viejo ON codigo_vinculacion (expira_en);


-- ============================================================
--  J.4: EL HISTORIAL NO ESTA EN EL SERVIDOR
-- ============================================================
--
-- Un dispositivo nuevo arranca vacio, y no hay forma de que el servidor lo
-- llene: nunca tuvo el historial. Los sobres se borran al confirmarse la
-- entrega; el historial vive cifrado en los telefonos.
--
-- La unica forma de que el aparato nuevo vea algo de antes es que **otro
-- dispositivo de la misma persona se lo mande**, cifrado, como se manda
-- cualquier otra cosa. Eso es lo que hace el modulo J.4: el principal reenvia
-- una ventana de mensajes recientes al dispositivo nuevo, por el mismo camino
-- que un mensaje normal, con una marca para que el receptor no los notifique
-- como si acabaran de llegar.
--
-- El servidor no participa mas que como buzon. Esta tabla existe solo para que
-- el principal sepa que ya lo hizo y no repita el reenvio en cada arranque.

CREATE TABLE historial_enviado (
    origen_id   uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    destino_id  uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    cuantos     integer NOT NULL DEFAULT 0,
    enviado_en  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (origen_id, destino_id)
);


-- Avisos nuevos. Lista cerrada, como siempre.
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

        -- Modulo J. Que se vinculo un dispositivo tiene que llegarle a los
        -- demas: un aparato nuevo en tu cuenta que aparece sin aviso es
        -- exactamente lo que no se debe poder hacer en silencio.
        'dispositivo_vinculado', 'dispositivo_revocado',

        -- El aparato nuevo pide historial y el principal responde. El aviso
        -- viaja por el servidor porque el servidor sabe quien esta conectado;
        -- el CONTENIDO va cifrado por el buzon normal.
        'historial_pedido'
    )
);

-- Estos dos tipos ya estaban declarados en el CHECK de eventos de seguridad
-- desde V12, esperando quien los escribiera. Con este modulo lo tienen.
COMMENT ON TABLE codigo_vinculacion IS
    'Codigos de un solo uso para autorizar un dispositivo nuevo. Los emite el dispositivo PRINCIPAL, nunca el aparato que quiere entrar.';
