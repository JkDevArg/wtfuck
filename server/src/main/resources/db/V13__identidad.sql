-- ============================================================
--  V13: identidad y cuenta
-- ============================================================
--
-- El registro de wtfuck es por username y sin telefono. Eso resolvio bien la
-- privacidad y dejo tres cosas imposibles:
--
--   1. Recuperar una cuenta. Sin ningun canal de verificacion, olvidar la
--      contrasena es perder la cuenta. Hasta hoy eso era literal.
--   2. Encontrar a alguien. Sin agenda de telefono no hay a que cruzar.
--   3. Saber quien es alguien dentro de EducaD.
--
-- Las tres se resuelven con lo mismo: un correo institucional opcional. Y la
-- forma en que se guarda es la decision central de esta migracion.


-- ============================================================
--  EL CORREO: solo el hash, y aun asi sirve para recuperar
-- ============================================================
--
-- Parece una contradiccion: si el servidor solo tiene un hash, no puede
-- mandarle un correo a nadie. Se resuelve mirando CUANDO hace falta la
-- direccion:
--
--   Al registrar:  el usuario la escribe -> se le manda el codigo a esa misma
--                  direccion -> se confirma -> se guarda SOLO el hash y la
--                  direccion se descarta.
--   Al recuperar:  el usuario la escribe OTRA VEZ -> se hashea -> ¿coincide con
--                  la cuenta? -> se le manda el codigo a la direccion que acaba
--                  de escribir.
--
-- El servidor nunca necesita tener la direccion guardada, porque siempre la
-- tiene delante en el momento en que la usa. Una fuga de la base no entrega ni
-- un correo.
--
-- OJO CON EL PEPPER. Un correo institucional es adivinable
-- (nombre.apellido@dominio), asi que un hash a secas se rompe por diccionario
-- en minutos. El hash se calcula con un PEPPER que vive en la variable de
-- entorno WTFUCK_PEPPER_CORREO y NO en la base: con la base sola no se puede
-- probar ningun candidato. Es la unica razon por la que un hash rapido alcanza
-- aqui; sin pepper haria falta Argon2 y entonces no se podria buscar por hash.
--
-- El dominio SI se guarda en claro, y a proposito: no identifica a nadie por si
-- mismo y es lo que permite decir "verificado por cientifica.edu.pe" en la
-- pantalla y separar poblaciones sin tocar datos personales.

ALTER TABLE usuario
    ADD COLUMN correo_hash        bytea,
    ADD COLUMN correo_dominio     text,
    ADD COLUMN correo_verificado_en timestamptz,

    -- Biografia, que el brief pide como campo aparte del estado.
    -- `estado_texto` (V2) es el "¿que estas haciendo?" que cambia seguido;
    -- esto es el "¿quien eres?" que casi nunca cambia. Mezclarlos obligaria a
    -- elegir entre las dos cosas.
    ADD COLUMN biografia          text,

    -- Descubrimiento. Por defecto SI, y el motivo es que descubrir exige
    -- conocer la direccion exacta: quien la sabe ya te conoce. Apagarlo existe
    -- para quien no quiere ni eso.
    ADD COLUMN descubrible        boolean NOT NULL DEFAULT true,

    -- 2FA. El secreto TOTP es un secreto compartido: si se filtra la base se
    -- filtra el segundo factor. Se guarda cifrado con WTFUCK_CLAVE_TOTP, que
    -- tambien vive fuera de la base.
    ADD COLUMN totp_secreto       bytea,
    ADD COLUMN totp_activado_en   timestamptz,

    -- Eliminacion con periodo de gracia. Ver la nota de abajo.
    ADD COLUMN eliminacion_pedida_en timestamptz;

-- Unico y parcial: dos cuentas no comparten correo, pero muchas pueden no
-- tener ninguno. Un UNIQUE normal trataria todos los NULL como distintos, que
-- es lo correcto, pero el indice parcial deja claro el proposito y no indexa
-- las filas sin correo.
CREATE UNIQUE INDEX usuario_correo_unico
    ON usuario (correo_hash) WHERE correo_hash IS NOT NULL;

ALTER TABLE usuario
    ADD CONSTRAINT correo_coherente CHECK (
        (correo_hash IS NULL AND correo_verificado_en IS NULL) OR
        (correo_hash IS NOT NULL)
    );

COMMENT ON COLUMN usuario.correo_hash IS
    'SHA-256 de (pepper || correo en minusculas). La direccion NO se guarda: se recibe cada vez que hace falta usarla.';


-- ============================================================
--  CODIGOS DE VERIFICACION
-- ============================================================
--
-- Un solo mecanismo para tres propositos distintos (verificar el correo,
-- recuperar la cuenta, confirmar la eliminacion) porque las tres necesitan lo
-- mismo: un codigo corto, con vencimiento, con tope de intentos y de un solo
-- uso.
--
-- `destino_hash` guarda a DONDE se mando, otra vez como hash. Sirve para dos
-- cosas: que al canjear el codigo se pueda comprobar que quien lo canjea sabe
-- la direccion, y que un codigo emitido para una direccion no valga para otra.
--
-- El codigo tambien va hasheado. Es corto y de vida breve, pero una fuga de la
-- base no deberia entregar codigos vivos: eso convertiria un leak de lectura en
-- un robo de cuentas.

CREATE TABLE codigo_verificacion (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id    uuid REFERENCES usuario(id) ON DELETE CASCADE,
    proposito     text NOT NULL,
    destino_hash  bytea NOT NULL,
    codigo_hash   bytea NOT NULL,

    -- Tope de intentos. Un codigo de 6 digitos son un millon de posibilidades,
    -- que a fuerza bruta es nada: sin este tope el vencimiento no protege.
    intentos      smallint NOT NULL DEFAULT 0,

    expira_en     timestamptz NOT NULL,
    usado_en      timestamptz,
    creado_en     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT proposito_valido CHECK (proposito IN (
        'verificar_correo', 'recuperar_cuenta', 'eliminar_cuenta'
    ))
);

-- La consulta caliente: el ultimo codigo vivo para un proposito y un destino.
CREATE INDEX codigo_vivo ON codigo_verificacion (destino_hash, proposito)
    WHERE usado_en IS NULL;
CREATE INDEX codigo_viejo ON codigo_verificacion (expira_en);


-- ============================================================
--  CODIGOS DE RESPALDO DEL 2FA
-- ============================================================
--
-- Existen porque un segundo factor sin salida de emergencia convierte perder
-- el telefono en perder la cuenta, que es el problema que este modulo vino a
-- resolver. Se guardan hasheados y se consumen de a uno.

CREATE TABLE codigo_respaldo (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    codigo_hash bytea NOT NULL,
    usado_en    timestamptz,
    creado_en   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX respaldo_sin_usar ON codigo_respaldo (usuario_id) WHERE usado_en IS NULL;


-- ============================================================
--  SESIONES: de donde vienen
-- ============================================================
--
-- `sesion` existia desde V1 con lo minimo para autenticar. Para poder GESTIONAR
-- sesiones hace falta algo mas: una lista de "sesion 1, sesion 2, sesion 3" sin
-- mas datos no permite decidir cual cerrar.

ALTER TABLE sesion
    ADD COLUMN ip            inet,
    ADD COLUMN agente        text,
    -- Ultimo uso, no solo emision: lo que distingue una sesion viva de una que
    -- quedo abierta hace tres meses.
    ADD COLUMN ultimo_uso_en timestamptz,
    ADD COLUMN revocada_por  uuid REFERENCES usuario(id) ON DELETE SET NULL;


-- ============================================================
--  CONTACTOS
-- ============================================================
--
-- Hasta ahora "conocido" significaba "compartimos una conversacion directa", y
-- de eso dependen los ajustes de privacidad. Con una libreta de verdad hay dos
-- formas de no ser un desconocido, y **ahora significa la union de las dos**:
-- si hablamos, no sos un extrano; si te guarde, tampoco. Dejar solo una de las
-- dos haria que guardar a alguien no sirviera para nada, o que hablar con
-- alguien no contara.
--
-- `alias` es como YO lo llamo, no como el se llama. Es la diferencia entre una
-- libreta y una lista de usuarios: el nombre que le pongo es mio y no viaja a
-- ningun lado.

CREATE TABLE contacto (
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    contacto_id uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    alias       text,
    favorito    boolean NOT NULL DEFAULT false,
    creado_en   timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (usuario_id, contacto_id),
    CONSTRAINT no_autocontacto CHECK (usuario_id <> contacto_id)
);

CREATE INDEX contacto_favorito ON contacto (usuario_id) WHERE favorito;


-- ============================================================
--  ELIMINAR LA CUENTA: por que hay periodo de gracia
-- ============================================================
--
-- Borrar de inmediato parece mas honesto y es peor. Quien pide borrar su cuenta
-- a las tres de la manana enojado no siempre quiere eso, y un borrado
-- irreversible sin pausa convierte un mal momento en una perdida definitiva. El
-- periodo de gracia (30 dias, en Repo) existe para que cancelar sea posible.
--
-- Mientras la eliminacion esta pedida, la cuenta queda INUTILIZABLE pero no
-- borrada: no se puede ingresar, y entrar de nuevo es lo que la cancela. Es lo
-- mismo que hacen las plataformas grandes y por el mismo motivo.
--
-- Lo interesante de este producto: cuando finalmente se borra, **el servidor
-- casi no tiene nada que borrar**. No hay historial de mensajes, porque nunca
-- lo tuvo: los sobres se borran al confirmarse la entrega. Lo que se va son los
-- metadatos -usuario, dispositivo, membresias, claves publicas- y las dos
-- excepciones declaradas: las publicaciones de sus canales publicos y la
-- evidencia que haya entregado en denuncias abiertas. Los mensajes viven en los
-- telefonos de los demas, y eso ninguna cuenta puede deshacerlo; conviene
-- decirlo en vez de prometer un borrado que no existe.

CREATE INDEX usuario_eliminacion_pendiente ON usuario (eliminacion_pedida_en)
    WHERE eliminacion_pedida_en IS NOT NULL;


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

        -- Modulo I. Cerrar una sesion a distancia tiene que llegarle al
        -- dispositivo cerrado: si no, sigue mostrando la app como si nada
        -- hasta que intente hablar con el servidor.
        'sesion_revocada'
    )
);
