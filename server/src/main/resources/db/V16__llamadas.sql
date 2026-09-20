-- ============================================================
--  V16: llamadas
-- ============================================================
--
-- ## La decision central: la SEÑALIZACION va cifrada, no en claro
--
-- Una llamada WebRTC se negocia intercambiando SDP: que codecs, que
-- direcciones, y -lo importante- la **huella del certificado DTLS** de cada
-- lado. El medio va cifrado con DTLS-SRTP de fabrica, asi que nadie escucha el
-- audio por el camino.
--
-- Pero eso solo vale si las huellas son autenticas. Si la señalizacion pasa en
-- claro por el servidor, el servidor puede cambiar las huellas por las suyas,
-- montar dos llamadas -una con cada lado- y escuchar todo. El cifrado del
-- medio no lo impide: cada tramo estaria perfectamente cifrado *contra el
-- servidor*.
--
-- Por eso aqui el SDP y los candidatos ICE **viajan dentro de un sobre
-- cifrado** con las sesiones de Signal que ya existen, exactamente igual que un
-- mensaje. El servidor mueve bytes opacos y no puede cambiar una huella que no
-- puede leer. Es lo que hace Signal, y por el mismo motivo.
--
-- Consecuencia para esta migracion: **en la base no hay ni un SDP**. Lo que se
-- guarda es lo que el servidor ya sabe de todas formas -quien llamo a quien,
-- cuando, y como termino-, que es lo que hace falta para un historial de
-- llamadas y para los limites de abuso.


-- ============================================================
--  K.6 · Quien puede llamarme
-- ============================================================
--
-- Mismo vocabulario que el resto de la privacidad (V3): todos / conocidos /
-- nadie. Y "conocido" significa lo mismo que en todo el sistema desde el
-- modulo I: compartimos conversacion directa, o esta en mi libreta.
--
-- El valor por defecto es 'conocidos' y NO 'todos', al contrario que el resto
-- de los ajustes. Una llamada no es un mensaje: suena, interrumpe, y despierta.
-- Que cualquiera pueda hacer sonar tu telefono de madrugada es un vector de
-- acoso que un mensaje no tiene.

ALTER TABLE usuario
    ADD COLUMN priv_llamadas text NOT NULL DEFAULT 'conocidos';

ALTER TABLE usuario
    ADD CONSTRAINT priv_llamadas_valido CHECK (priv_llamadas IN ('todos','conocidos','nadie'));

COMMENT ON COLUMN usuario.priv_llamadas IS
    'Quien puede llamarme. Por defecto conocidos, no todos: una llamada suena e interrumpe, un mensaje no.';


-- ============================================================
--  LA LLAMADA: solo metadatos
-- ============================================================

CREATE TABLE llamada (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),

    -- La conversacion donde ocurre. Una llamada 1:1 vive en la directa y una
    -- de grupo en el grupo: asi los permisos, los bloqueos y las
    -- restricciones son los MISMOS que para escribir, sin un sistema aparte.
    conversacion_id uuid NOT NULL REFERENCES conversacion(id) ON DELETE CASCADE,

    -- Quien llamo, y desde que aparato. El dispositivo importa: con
    -- multi-dispositivo, contestar en el telefono y no en la tablet es
    -- informacion de la llamada.
    origen_id       uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    origen_dispositivo uuid REFERENCES dispositivo(id) ON DELETE SET NULL,

    con_video       boolean NOT NULL DEFAULT false,

    estado          text NOT NULL DEFAULT 'sonando',

    iniciada_en     timestamptz NOT NULL DEFAULT now(),
    -- Cuando alguien contesto. Null si nunca se contesto.
    contestada_en   timestamptz,
    terminada_en    timestamptz,

    -- Por que termino. Se guarda aparte del estado porque "colgada" no dice
    -- si la colgo quien llamaba o quien contestaba, y esa diferencia es la que
    -- separa "no me contesto" de "me colgo".
    fin_motivo      text,
    fin_por         uuid REFERENCES usuario(id) ON DELETE SET NULL,

    CONSTRAINT estado_llamada_valido CHECK (estado IN (
        'sonando', 'en_curso', 'terminada'
    )),
    CONSTRAINT fin_motivo_valido CHECK (fin_motivo IS NULL OR fin_motivo IN (
        'colgada', 'rechazada', 'sin_respuesta', 'ocupado',
        'cancelada', 'fallo_red', 'no_contestada'
    )),

    -- Coherencia de estados. Sin esto, un bug de la aplicacion deja filas que
    -- dicen "terminada" sin fecha de fin, y el historial empieza a mentir.
    CONSTRAINT llamada_coherente CHECK (
        (estado <> 'terminada' OR terminada_en IS NOT NULL) AND
        (estado <> 'en_curso'  OR contestada_en IS NOT NULL)
    )
);

-- La consulta caliente del historial: mis llamadas, las mas nuevas primero.
CREATE INDEX llamada_por_conversacion ON llamada (conversacion_id, iniciada_en DESC);

-- Y la de "¿hay una llamada viva aqui?", que es la que evita dos llamadas
-- simultaneas en la misma conversacion.
CREATE INDEX llamada_viva ON llamada (conversacion_id)
    WHERE estado <> 'terminada';


/*
 * Quien participo, y como le fue.
 *
 * Existe como tabla aparte y no como columnas en `llamada` porque una llamada
 * de grupo tiene N participantes y cada uno tiene su propia historia: uno
 * contesto, otro no, un tercero se fue antes. Con columnas en `llamada` eso no
 * se puede representar, y el historial de una llamada grupal quedaria reducido
 * a "hubo una llamada".
 */
CREATE TABLE llamada_participante (
    llamada_id    uuid NOT NULL REFERENCES llamada(id) ON DELETE CASCADE,
    usuario_id    uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    -- En que aparato contesto. Null mientras no contesta.
    dispositivo_id uuid REFERENCES dispositivo(id) ON DELETE SET NULL,

    estado        text NOT NULL DEFAULT 'sonando',
    unido_en      timestamptz,
    salido_en     timestamptz,

    PRIMARY KEY (llamada_id, usuario_id),

    CONSTRAINT estado_participante_valido CHECK (estado IN (
        'sonando', 'dentro', 'fuera', 'rechazo', 'no_contesto'
    ))
);

CREATE INDEX llamada_participante_usuario ON llamada_participante (usuario_id);


-- ============================================================
--  K.5 · Llamadas de grupo: malla, no SFU
-- ============================================================
--
-- Sin servidor de medios, una llamada de grupo se hace en MALLA: cada
-- participante abre una conexion con cada otro. Con N personas son N-1
-- conexiones por cabeza y N(N-1)/2 en total, y el que sufre es el enlace de
-- subida de cada telefono: con 5 personas, cada uno sube su video 4 veces.
--
-- Por eso el tope es 4 (ver `Llamadas.MAX_EN_LLAMADA`). No es una limitacion de
-- la base ni del protocolo: es lo que aguanta un telefono con datos moviles, y
-- es preferible un tope declarado a una llamada de ocho que se cae sola.
--
-- Subirlo exige un SFU -un servidor que recibe un flujo de cada uno y reparte-
-- y eso es infraestructura aparte, no una linea de codigo. Queda anotado aqui
-- porque es donde alguien va a buscar por que no se puede llamar a diez.


-- Avisos nuevos. Lista cerrada, como siempre.
--
-- OJO: estos avisos son METADATOS. El SDP y los candidatos ICE NO van por aqui:
-- van dentro de sobres cifrados, porque si el servidor pudiera leerlos podria
-- cambiar las huellas DTLS y escuchar la llamada.
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

        -- Modulo K. Que suene, que se contesto en otro aparato, y que termino.
        --
        -- `llamada_contestada` es el que hace falta con multi-dispositivo: si
        -- contestas en el telefono, la tablet tiene que DEJAR de sonar. Sin
        -- este aviso, cada aparato sonaria hasta que alguien lo silenciara a
        -- mano.
        'llamada_entrante', 'llamada_contestada', 'llamada_terminada'
    )
);
