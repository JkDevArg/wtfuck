-- ============================================================
--  V12: moderacion
-- ============================================================
--
-- Hasta aqui todos los permisos eran POR CONVERSACION: ser administrador de un
-- grupo no dice nada sobre otro grupo. Eso alcanza mientras el problema esta
-- dentro de una conversacion, y deja de alcanzar en el momento en que alguien
-- denuncia a una persona: una denuncia sobre un usuario no pertenece a ninguna
-- conversacion, y no hay a quien pedirle permiso.
--
-- De ahi sale lo unico conceptualmente nuevo del modulo: un nivel de PLATAFORMA,
-- separado del de conversacion.


-- ============================================================
--  STAFF: el nivel de plataforma
-- ============================================================
--
-- Se reusan los mismos numeros que la jerarquia de roles de conversacion
-- (50 moderador, 80 administrador, 100 propietario) para no tener dos escalas
-- que signifiquen lo mismo. Pero son AMBITOS distintos: ser staff no te da
-- nada dentro de un grupo, y ser dueno de un grupo no te hace staff.
--
-- Es una columna y no una tabla porque un usuario tiene un solo nivel a la vez;
-- cuando se lo dieron y quien se lo dio vive en `auditoria`, que ya existe.

ALTER TABLE usuario
    ADD COLUMN staff_nivel smallint NOT NULL DEFAULT 0,
    -- Suspension con vencimiento. `suspendido_en` (V4) ya decia "esta
    -- suspendido"; faltaba "hasta cuando". Una suspension sin fecha es un baneo
    -- permanente, y conviene que las dos cosas se distingan a simple vista.
    ADD COLUMN suspendido_hasta timestamptz,
    ADD COLUMN suspendido_por   uuid REFERENCES usuario(id) ON DELETE SET NULL;

ALTER TABLE usuario
    ADD CONSTRAINT staff_nivel_valido CHECK (staff_nivel IN (0, 50, 80, 100));

COMMENT ON COLUMN usuario.staff_nivel IS
    'Nivel de plataforma: 0 nadie, 50 moderador, 80 administrador, 100 propietario. Ortogonal a los roles de conversacion.';

CREATE INDEX usuario_staff ON usuario (staff_nivel) WHERE staff_nivel > 0;


-- ============================================================
--  DENUNCIAS
-- ============================================================

CREATE TABLE denuncia (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    denunciante_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    tipo            text NOT NULL,
    -- Que se denuncia. Se guardan los tres por separado y no un `objetivo_id`
    -- generico: con columnas propias, Postgres puede poner llaves foraneas de
    -- verdad y una denuncia no puede quedar apuntando a nada.
    objetivo_usuario_id     uuid REFERENCES usuario(id) ON DELETE CASCADE,
    objetivo_conversacion_id uuid REFERENCES conversacion(id) ON DELETE CASCADE,
    objetivo_mensaje_id     uuid REFERENCES mensaje_meta(id) ON DELETE SET NULL,

    motivo          text NOT NULL,
    detalle         text,

    estado          text NOT NULL DEFAULT 'pendiente',
    revisor_id      uuid REFERENCES usuario(id) ON DELETE SET NULL,
    tomada_en       timestamptz,
    resuelta_en     timestamptz,
    resolucion      text,

    creada_en       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT tipo_denuncia_valido CHECK (tipo IN ('usuario','mensaje','grupo','canal')),
    CONSTRAINT motivo_denuncia_valido CHECK (motivo IN (
        'spam', 'acoso', 'discurso_de_odio', 'contenido_sexual',
        'violencia', 'suplantacion', 'estafa', 'otro'
    )),
    CONSTRAINT estado_denuncia_valido CHECK (estado IN (
        'pendiente', 'en_revision', 'resuelta', 'descartada'
    )),

    -- El tipo obliga a que venga el objetivo que le corresponde. Sin esto se
    -- podia crear una denuncia de tipo 'mensaje' sin mensaje, y la cola de
    -- revision mostraba filas sobre las que no se puede hacer nada.
    CONSTRAINT objetivo_coherente CHECK (
        (tipo = 'usuario'  AND objetivo_usuario_id IS NOT NULL) OR
        (tipo = 'mensaje'  AND objetivo_mensaje_id IS NOT NULL) OR
        (tipo IN ('grupo','canal') AND objetivo_conversacion_id IS NOT NULL)
    )
);

-- La consulta caliente es la cola: lo pendiente, lo mas viejo primero.
CREATE INDEX denuncia_cola ON denuncia (creada_en)
    WHERE estado IN ('pendiente', 'en_revision');

-- Para "cuantas denuncias acumula esta persona", que es lo que decide si una
-- denuncia mas es un caso o un patron.
CREATE INDEX denuncia_por_objetivo ON denuncia (objetivo_usuario_id)
    WHERE objetivo_usuario_id IS NOT NULL;

-- Una persona no denuncia dos veces lo mismo. No es solo higiene: sin esto,
-- diez denuncias del mismo denunciante parecian diez personas quejandose.
CREATE UNIQUE INDEX denuncia_sin_repetir_mensaje
    ON denuncia (denunciante_id, objetivo_mensaje_id)
    WHERE objetivo_mensaje_id IS NOT NULL;
CREATE UNIQUE INDEX denuncia_sin_repetir_usuario
    ON denuncia (denunciante_id, objetivo_usuario_id)
    WHERE objetivo_usuario_id IS NOT NULL AND estado IN ('pendiente','en_revision');


-- ============================================================
--  LA SEGUNDA EXCEPCION DECLARADA: el servidor SI guarda esto
-- ============================================================
--
-- La primera excepcion al buzon tonto fueron los canales publicos (V10). Esta
-- es la segunda, y es mas incomoda de escribir.
--
-- El servidor no puede moderar lo que no puede leer. Con E2EE de verdad, una
-- denuncia sobre un mensaje llega al moderador como un identificador y unos
-- bytes opacos: no hay nada que revisar, y "confia en el denunciante" no es
-- moderacion, es sortear quien escribio primero.
--
-- La unica salida posible es que el TEXTO LO ENTREGUE EL DENUNCIANTE. Su
-- telefono ya lo descifro, es el unico que puede, y al denunciar renuncia
-- voluntariamente a la confidencialidad de ESOS mensajes. Es exactamente lo que
-- hace WhatsApp, y por el mismo motivo.
--
-- Lo que se hace para que no sea un agujero encubierto:
--   1. La tabla se llama `denuncia_evidencia` y esta comentada.
--   2. Solo llega por la ruta de denuncia, nunca como efecto de otra cosa.
--   3. La pantalla lo dice ANTES de confirmar, no despues.
--   4. Se guarda una ventana corta de contexto, no la conversacion.
--   5. Se borra al resolver la denuncia (`purgarEvidencia`): sirve para decidir,
--      no para archivar.

CREATE TABLE denuncia_evidencia (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    denuncia_id   uuid NOT NULL REFERENCES denuncia(id) ON DELETE CASCADE,
    orden         integer NOT NULL,
    autor_username citext NOT NULL,
    enviado_en    timestamptz NOT NULL,

    -- Texto en claro. Puesto aqui por el denunciante, no interceptado.
    contenido     text NOT NULL,

    UNIQUE (denuncia_id, orden)
);
COMMENT ON TABLE denuncia_evidencia IS
    'EXCEPCION DECLARADA: texto en claro que el denunciante entrega al denunciar. El servidor no lo descifro; no puede. Se purga al resolver.';


-- ============================================================
--  ADVERTENCIAS
-- ============================================================
--
-- Una advertencia no quita nada: es un aviso que queda anotado. Su valor esta
-- en que se SUMA, y la suma es lo que convierte "una persona tuvo un mal dia"
-- en "esta persona hace esto siempre".
--
-- `reconocida_en` existe porque una advertencia que el usuario nunca vio no
-- deberia contar igual que una que leyo y repitio.

CREATE TABLE advertencia (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    emisor_id       uuid REFERENCES usuario(id) ON DELETE SET NULL,
    denuncia_id     uuid REFERENCES denuncia(id) ON DELETE SET NULL,
    conversacion_id uuid REFERENCES conversacion(id) ON DELETE SET NULL,

    motivo          text NOT NULL,
    detalle         text,

    -- Una advertencia caduca. Sin vencimiento, un usuario quedaria marcado para
    -- siempre por algo de hace tres anos y la escalada automatica se volveria
    -- una condena diferida.
    vence_en        timestamptz,
    reconocida_en   timestamptz,
    revocada_en     timestamptz,
    creada_en       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX advertencia_vigentes ON advertencia (usuario_id)
    WHERE revocada_en IS NULL;


-- ============================================================
--  REGISTRO DE EVENTOS DE SEGURIDAD
-- ============================================================
--
-- Separado de `auditoria` a proposito, y esa separacion es la parte importante:
--
--   `auditoria`        = quien hizo QUE sobre un recurso. Responde "¿quien
--                        expulso a Ana?". Lo consulta un administrador.
--   `evento_seguridad` = que le PASO a una cuenta. Responde "¿desde donde
--                        entraron a mi cuenta?". Lo consulta el dueno.
--
-- Meterlos en la misma tabla obligaria a que el dueno de una cuenta pudiera
-- leer filas de moderacion para poder ver sus propios accesos, o a filtrar por
-- tipo en cada consulta y no equivocarse nunca.

CREATE TABLE evento_seguridad (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id  uuid REFERENCES usuario(id) ON DELETE CASCADE,
    tipo        text NOT NULL,

    -- Se guarda de donde vino porque es lo unico que permite al dueno de la
    -- cuenta reconocer un acceso como propio o ajeno.
    ip          inet,
    agente      text,
    detalle     jsonb,
    creado_en   timestamptz NOT NULL DEFAULT now(),

    -- Tres de estos todavia no los escribe nadie: 'sesion_cerrada',
    -- 'dispositivo_nuevo' y 'dispositivo_revocado'. Hoy el cierre de sesion es
    -- solo del lado del cliente -tira el token y nada mas- y no hay revocacion
    -- en el servidor; eso llega con el modulo I (gestion de sesiones y cierre
    -- remoto). Se dejan en la lista a proposito y anotado aqui, porque agregar
    -- un tipo cuesta una migracion y el modulo I los va a necesitar tal cual.
    CONSTRAINT tipo_evento_seguridad_valido CHECK (tipo IN (
        'registro', 'ingreso', 'ingreso_fallido', 'sesion_cerrada',
        'dispositivo_nuevo', 'dispositivo_revocado',
        'clave_identidad_cambiada',
        'limite_excedido', 'denuncia_creada',
        'advertencia_recibida', 'restriccion_aplicada',
        'cuenta_suspendida', 'cuenta_restaurada',
        'staff_otorgado', 'staff_retirado'
    ))
);

CREATE INDEX evento_seguridad_por_usuario ON evento_seguridad (usuario_id, creado_en DESC);
CREATE INDEX evento_seguridad_recientes ON evento_seguridad (creado_en DESC);


-- ============================================================
--  ANTISPAM DURABLE
-- ============================================================
--
-- El limitador de ritmo vive en memoria (ver Limites.kt): es barato y perder el
-- contador al reiniciar solo perdona una rafaga. Pero hay cosas donde reiniciar
-- el servidor NO debe perdonar nada, porque el castigo dura horas y el abuso
-- vale la pena si basta con esperar un reinicio.
--
-- Para esas, un contador en la base: ventanas de tiempo redondeadas, una fila
-- por (usuario, accion, ventana). Las viejas se barren.

CREATE TABLE contador_uso (
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    accion      text NOT NULL,
    ventana     timestamptz NOT NULL,   -- inicio de la ventana, ya redondeado
    n           integer NOT NULL DEFAULT 0,

    PRIMARY KEY (usuario_id, accion, ventana)
);

CREATE INDEX contador_uso_viejo ON contador_uso (ventana);


-- Avisos nuevos que el servidor puede fabricar. Otra vez: la lista es cerrada a
-- proposito, y agregar uno cuesta una migracion.
ALTER TABLE evento_pendiente DROP CONSTRAINT tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (
    tipo IN (
        'agregado_grupo', 'sacado_grupo', 'grupo_renombrado',
        'expulsado', 'silenciado', 'rol_cambiado',
        'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
        'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado',
        'mensaje_reaccion',
        'canal_publicacion',

        -- Modulo G. Una advertencia que el usuario no ve no sirve de nada: el
        -- punto de advertir es que haya oportunidad de corregir.
        'advertencia', 'sancion'
    )
);
