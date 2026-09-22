-- Los cuatro ajustes de privacidad que el §3 del brief pedia y no estaban.
--
-- Hasta aqui habia once ajustes. Estos cuatro no son mas de lo mismo: cada uno
-- separa dos cosas que estaban pegadas y que la gente quiere decidir por
-- separado.
--
-- ## 1. `priv_biografia` — la bio no es el estado
--
-- Iban juntas bajo `priv_estado`. Son distintas: el estado es una frase que
-- cambia cada semana ("de viaje"), la biografia dice quien sos y suele llevar
-- donde trabajas. Quien la escribe para sus contactos no la escribio para
-- cualquiera que le busque el usuario.
--
-- ## 2. `priv_videollamadas` — el video no es el audio
--
-- `priv_llamadas` valia para las dos. Aceptar una llamada de voz de alguien no
-- es aceptar que te vea la cara ni lo que tenes detras, y esa diferencia
-- importa justo con quien menos confianza hay. Se aplica **ademas** de
-- `priv_llamadas`: si el audio ya esta cerrado, el video tambien.
--
-- ## 3. `priv_grabando` — grabar no es escribir
--
-- Hoy grabar una nota de voz emite el mismo aviso que teclear. Son dos cosas:
-- "esta escribiendo" dice que hay algo en camino; "esta grabando" dice ademas
-- que tiene el microfono abierto ahora mismo, y hay gente que no quiere
-- anunciar eso aunque no le importe lo otro.
--
-- Booleano y no nivel, igual que `priv_escribiendo`: es un aviso que se manda o
-- no se manda; no hay un "solo a mis contactos" que signifique algo cuando el
-- aviso solo viaja dentro de una conversacion que ya existe.
--
-- ## 4. `priv_solicitudes` — la alternativa al portazo
--
-- Con `priv_escribe = conocidos`, un desconocido recibe un 403 y se acabo. Eso
-- protege, pero tambien deja fuera a quien tenia algo legitimo que decir.
--
-- Con este ajuste encendido, quien no puede escribir directamente puede mandar
-- **una solicitud**: la conversacion nace marcada, vive aparte, y quien la
-- recibe decide. Aceptar la vuelve un chat normal; rechazar la borra.
--
-- Por que una columna en `conversacion` y no una tabla de solicitudes: una
-- solicitud **es** una conversacion, solo que en un estado distinto. Con tabla
-- aparte habria que copiar la conversacion y sus mensajes al aceptar, y mover
-- mensajes entre tablas es la clase de cosa que se rompe una vez y se nota un
-- mes despues.

ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_biografia text NOT NULL DEFAULT 'todos';
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_videollamadas text NOT NULL DEFAULT 'conocidos';
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_grabando boolean NOT NULL DEFAULT true;
-- Apagado por defecto, al contrario que los otros tres: encenderlo cambiaria
-- el significado de `priv_escribe` para quien ya lo tenia en 'conocidos'. Ver
-- la nota de `Privacidad.solicitudes`.
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS priv_solicitudes boolean NOT NULL DEFAULT false;

-- `priv_videollamadas` nace en 'conocidos' como `priv_llamadas`, y por el mismo
-- motivo: una llamada suena, interrumpe y despierta. El valor seguro es el
-- defecto porque quien no toca nunca los ajustes es justo quien mas lo necesita.

-- Quien pidio la conversacion, mientras siga siendo una solicitud.
--
-- NULL = conversacion normal. Es el estado de casi todas las filas, asi que el
-- indice es PARCIAL: indexar los NULL seria indexar la tabla entera para
-- responder a una pregunta que solo se hace sobre un punado de filas.
ALTER TABLE conversacion ADD COLUMN IF NOT EXISTS solicitud_de uuid
    REFERENCES usuario(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS conversacion_solicitudes
    ON conversacion (solicitud_de) WHERE solicitud_de IS NOT NULL;

-- Los cuatro ajustes nuevos entran en la lista de los que admiten excepciones,
-- salvo los dos booleanos: "todos menos Fulano" no significa nada sobre un
-- interruptor de si/no.
--
-- La lista vive DOS veces —aqui y en `Privacidad.PERSONALIZABLES`— y eso ya
-- rompio una vez: al agregar `historias` se actualizo una sola y guardar una
-- excepcion daba 500. Si tocas una, toca la otra.
ALTER TABLE privacidad_excepcion DROP CONSTRAINT IF EXISTS ajuste_valido;
ALTER TABLE privacidad_excepcion ADD CONSTRAINT ajuste_valido CHECK (
    ajuste IN (
        'foto', 'estado', 'nombre', 'busqueda', 'ultima_vez',
        'llamadas', 'grupos', 'historias',
        'biografia', 'videollamadas'
    )
);
