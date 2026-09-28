-- El codigo de recuperacion: la unica salida cuando se pierde el telefono.
--
-- ## El problema que resuelve
--
-- La cuenta esta atada al hardware a proposito: para ingresar hay que ser un
-- dispositivo registrado, vincular uno nuevo exige un codigo emitido desde el
-- principal, y el principal no se puede revocar. Es una defensa fuerte y
-- deliberada: con la contrasena robada, nadie entra desde su telefono.
--
-- El precio era absoluto: telefono perdido = cuenta perdida para siempre, con
-- copia de seguridad o sin ella. Y dejaba coja la copia, cuya pantalla decia
-- "para restaurar en un telefono nuevo, primero entra a tu cuenta" cuando en
-- un telefono nuevo no se podia entrar.
--
-- ## Que se guarda aqui, y que NO
--
-- Se guarda un HASH del verificador, no el verificador. El cliente deriva dos
-- claves independientes del mismo codigo con HKDF y etiquetas distintas:
--
--   codigo --HKDF("wtfuck/copia/identidad/v1")--> cifra la identidad Signal
--   codigo --HKDF("wtfuck/servidor/verificador/v1")--> lo que llega aqui
--
-- De lo que hay en esta columna NO se llega a la clave de la identidad: HKDF
-- no es invertible y las etiquetas producen salidas independientes. Por eso
-- una fuga de esta base no permite descifrar la identidad de nadie aunque
-- tambien se robe el archivo de la copia.
--
-- Y encima se guarda hasheado, con el mismo hash que las contrasenas: una
-- fuga tampoco entrega el verificador con el que vincular un telefono.
--
-- ## Por que una columna y no una tabla
--
-- Porque es un solo valor por cuenta y no tiene historia: rotar el codigo lo
-- sustituye. Una tabla aparte solo anadiria un JOIN a cada comprobacion.

ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS recuperacion_hash text,
    -- Cuando se fijo. Sirve para que la app pueda decir "tu codigo es de hace
    -- ocho meses, "y si no lo encuentras, generalo de nuevo" -y para auditar
    -- una rotacion sospechosa-.
    ADD COLUMN IF NOT EXISTS recuperacion_fijado_en timestamptz;

COMMENT ON COLUMN usuario.recuperacion_hash IS
    'Hash del verificador derivado del codigo de recuperacion. Nunca el codigo.';

-- Los dos eventos de seguridad nuevos.
--
-- `evento_seguridad` valida el tipo contra una lista cerrada (V12). Sin
-- agregarlos aqui, anotar la recuperacion fallaria con un CHECK violado — que
-- es exactamente lo que paso con `conversacion_temporales` en la V42, y la
-- defensa funciono: un tipo mal escrito no se guarda en silencio.
--
-- Se podria haber reusado 'ingreso' con un detalle, como hace
-- `Identidad.recuperar`. Se separan porque son los dos hechos que mas importan
-- en una auditoria de esta funcion: quien fijo un codigo y quien lo USO para
-- entrar desde un aparato nuevo. Mezclados con los ingresos normales habria
-- que filtrar por el JSON del detalle para encontrarlos.
--
-- Se reconstruye la restriccion entera porque Postgres no deja agregarle un
-- valor a un CHECK existente. La lista de abajo es la de la V12 mas dos.
ALTER TABLE evento_seguridad DROP CONSTRAINT IF EXISTS tipo_evento_seguridad_valido;
ALTER TABLE evento_seguridad ADD CONSTRAINT tipo_evento_seguridad_valido CHECK (tipo IN (
    'registro', 'ingreso', 'ingreso_fallido', 'sesion_cerrada',
    'dispositivo_nuevo', 'dispositivo_revocado',
    'clave_identidad_cambiada',
    'limite_excedido', 'denuncia_creada',
    'advertencia_recibida', 'restriccion_aplicada',
    'cuenta_suspendida', 'cuenta_restaurada',
    'staff_otorgado', 'staff_retirado',
    'recuperacion_fijada', 'recuperacion_usada'
));
