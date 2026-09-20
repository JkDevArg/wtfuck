-- Modulo O · Historias (los "estados" de WhatsApp, las "historias" de Telegram).
--
-- ## Por que se llaman historias y no estados
--
-- Porque `estado` ya esta ocupado en este proyecto, y por dos cosas a la vez:
-- `usuario.estado_texto` es la frase del perfil, y `priv_estado` dice quien
-- puede verla. Llamar "estado" tambien a esto daria `priv_estado` y
-- `priv_estados`, que es la clase de par que alguien confunde a las tres de la
-- manana. Telegram ya usa "historias" y no se pierde nada.
--
-- ## Lo que el servidor guarda y lo que NO
--
-- Aqui no hay contenido. Una historia viaja **cifrada de extremo a extremo**,
-- igual que un mensaje de grupo: el cliente resuelve a quien le toca verla, la
-- cifra una vez por dispositivo de destino y la manda por el buzon de siempre.
-- Lo que se guarda aqui es el metadato que el servidor SI necesita para hacer
-- su trabajo: quien publico, cuando, cuando caduca, y quien la vio.
--
-- ## Por que la audiencia se congela al publicar
--
-- `historia_destino` fija a quien iba dirigida en el momento de publicar, y no
-- se recalcula despues. No es una simplificacion: es la unica opcion coherente
-- con el cifrado. La historia se cifro contra los dispositivos que existian
-- entonces; alguien que abre una conversacion conmigo manana no tiene forma de
-- descifrar lo que publique hoy, y fingir que si la ve seria mentir en la
-- interfaz.
--
-- Tambien es lo que hace WhatsApp, y por el mismo motivo.

CREATE TABLE IF NOT EXISTS historia (
    id            uuid PRIMARY KEY,
    autor_id      uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,

    -- `texto` | `imagen` | `video`. El servidor no puede comprobarlo -el
    -- contenido va cifrado-, igual que con la clase de un mensaje: sirve para
    -- que el cliente sepa que dibujar antes de descifrar, y para nada mas.
    clase         text NOT NULL,

    creada_en     timestamptz NOT NULL DEFAULT now(),

    -- Caduca sola. No hay proceso que barra: todas las consultas filtran por
    -- `expira_en > now()`, asi que una historia vencida deja de existir para
    -- quien pregunta en el mismo instante en que vence, sin depender de que un
    -- barrendero haya pasado.
    --
    -- El borrado fisico se hace aparte y sin prisa; ver el indice de abajo.
    expira_en     timestamptz NOT NULL,

    -- Retirarla antes de tiempo. Se marca en vez de borrar para que las vistas
    -- ya registradas sigan teniendo a que apuntar.
    retirada_en   timestamptz
);

-- Lo que mas se consulta: "que historias vivas hay para mi". Va por destino y
-- por caducidad, que son las dos condiciones de esa pregunta.
CREATE INDEX IF NOT EXISTS historia_vivas ON historia (expira_en)
    WHERE retirada_en IS NULL;

CREATE INDEX IF NOT EXISTS historia_por_autor ON historia (autor_id, creada_en DESC);

-- ---------------------------------------------------------------------------
--  A quien iba dirigida
-- ---------------------------------------------------------------------------
--
--  Se resuelve al publicar aplicando la privacidad de quien publica, y se
--  congela. Ver la nota de arriba.
CREATE TABLE IF NOT EXISTS historia_destino (
    historia_id uuid NOT NULL REFERENCES historia(id) ON DELETE CASCADE,
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    PRIMARY KEY (historia_id, usuario_id)
);

CREATE INDEX IF NOT EXISTS historia_destino_por_usuario
    ON historia_destino (usuario_id);

-- ---------------------------------------------------------------------------
--  Quien la vio
-- ---------------------------------------------------------------------------
--
--  ## Por que esto respeta el ajuste de confirmaciones de lectura
--
--  Ver una historia es exactamente lo mismo que leer un mensaje: la otra
--  persona se entera de que estuviste. Tener un interruptor para una cosa y no
--  para la otra convertiria las historias en la puerta de atras del ajuste que
--  alguien apago a proposito.
--
--  Asi que quien tiene `priv_lectura` apagado no registra vistas, y por la
--  misma reciprocidad tampoco ve quien vio las suyas. La fila simplemente no
--  se crea; no hay nada que esconder despues.
CREATE TABLE IF NOT EXISTS historia_vista (
    historia_id uuid NOT NULL REFERENCES historia(id) ON DELETE CASCADE,
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    vista_en    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (historia_id, usuario_id)
);

-- ---------------------------------------------------------------------------
--  Quien puede ver mis historias
-- ---------------------------------------------------------------------------
--
--  Columna propia y no reutilizar `priv_estado`: son dos cosas distintas. La
--  frase del perfil la ve cualquiera que abra tu ficha; una historia es
--  contenido que se publica, y mucha gente quiere el perfil abierto y las
--  historias cerradas. Mezclarlas obligaria a elegir.
--
--  Defecto `conocidos` y no `todos`, al contrario que la mayoria de los
--  ajustes de aqui. Una historia es contenido, no un dato del perfil: que
--  aparezca por defecto ante cualquiera que tenga tu username es mas de lo que
--  alguien espera al publicar por primera vez.
ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS priv_historias text NOT NULL DEFAULT 'conocidos';

-- ---------------------------------------------------------------------------
--  Las historias entran en la lista de ajustes personalizables
-- ---------------------------------------------------------------------------
--
--  Este CHECK y `Privacidad.PERSONALIZABLES` en el protocolo son **la misma
--  lista escrita dos veces**, y se separaron en cuanto se agrego un ajuste:
--  el protocolo ya ofrecia `historias` mientras la base seguia rechazandolo, y
--  guardar una excepcion daba un 500.
--
--  Se deja anotado porque el error no es el CHECK sino la duplicacion. La
--  alternativa —quitar el CHECK y confiar en el codigo— es peor: este es el
--  ultimo sitio donde una lista de excepciones mal escrita se detiene antes de
--  quedar guardada.
--
--  `personalizado` es de los niveles que mas se usan en historias: lo habitual
--  no es "todos" ni "nadie" sino "todos menos tres".
ALTER TABLE privacidad_excepcion DROP CONSTRAINT IF EXISTS ajuste_valido;
ALTER TABLE privacidad_excepcion ADD CONSTRAINT ajuste_valido CHECK (
    ajuste IN ('foto', 'estado', 'nombre', 'grupos', 'llamadas', 'busqueda',
               'ultima_vez', 'historias')
);
