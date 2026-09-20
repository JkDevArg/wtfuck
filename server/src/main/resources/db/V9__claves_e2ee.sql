-- ============================================================
--  V9: material de claves para E2EE real (modulo E)
-- ============================================================
--
-- Las tablas de prekeys existian desde V1, declaradas y vacias. Esto agrega lo
-- que falta para PQXDH, que es lo que exige libsignal 0.86:
--
--   * registration_id  en dispositivo. Es un numero aleatorio por dispositivo
--     que viaja en el paquete de claves. Sirve para detectar que la sesion que
--     uno tiene guardada ya no corresponde al dispositivo del otro lado
--     -reinstalo la app, por ejemplo- y hay que rehacerla.
--
--   * prekey_kyber. En PQXDH el intercambio combina X25519 con un KEM
--     post-cuantico, y la prekey Kyber es OBLIGATORIA en el paquete: sin ella
--     no se puede construir un PreKeyBundle.
--
-- El servidor sigue sin poder leer un solo mensaje: guarda y reparte claves
-- PUBLICAS. Las privadas no salen del telefono.

ALTER TABLE dispositivo
    ADD COLUMN IF NOT EXISTS registration_id integer NOT NULL DEFAULT 0;

-- Prekey Kyber firmada. Misma forma que prekey_firmada: hay una vigente por
-- dispositivo y se rota; no es de un solo uso.
CREATE TABLE IF NOT EXISTS prekey_kyber (
    dispositivo_id uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    key_id         integer NOT NULL,
    publica        bytea NOT NULL,
    firma          bytea NOT NULL,          -- firmada con la clave de identidad
    creada_en      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (dispositivo_id, key_id)
);

-- ============================================================
--  Por que las prekeys de un solo uso se BORRAN y no se marcan
-- ============================================================
--
-- V1 dejaba `consumida_en` y un indice parcial. Se mantiene la columna, pero el
-- servidor ahora BORRA la fila al entregarla.
--
-- El motivo es que una prekey consumida no vuelve a servir para nada: dejarla
-- solo hace crecer la tabla y el indice sin aportar un dato que alguien vaya a
-- consultar. Y hay un motivo de privacidad: la cuenta de prekeys consumidas de
-- alguien es, en la practica, la cuenta de con cuanta gente distinta empezo a
-- hablar. Ese dato no hace falta para operar, asi que no se guarda.

CREATE INDEX IF NOT EXISTS prekey_unica_por_dispositivo
    ON prekey_unica (dispositivo_id, key_id);

-- ============================================================
--  Aviso de cambio de identidad (E.6)
-- ============================================================
--
-- Cuando alguien reinstala, su clave de identidad cambia. Quien ya hablaba con
-- esa persona TIENE que enterarse: si no, un servidor malicioso podria cambiar
-- la clave por la suya y leer todo sin que nadie lo note. Eso es exactamente
-- el ataque que la verificacion de huella previene.
--
-- El servidor guarda cuando cambio, no la clave anterior: al cliente le alcanza
-- con saber que cambio para pedir que se vuelva a verificar.
CREATE TABLE IF NOT EXISTS cambio_identidad (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    usuario_id     uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    dispositivo_id uuid NOT NULL REFERENCES dispositivo(id) ON DELETE CASCADE,
    ocurrio_en     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS cambio_identidad_por_usuario
    ON cambio_identidad (usuario_id, ocurrio_en DESC);

-- ============================================================
--  El buzon guarda el tipo de cuerpo
-- ============================================================
--
-- Con E2EE el cuerpo puede ser un SignalMessage, un PreKeySignalMessage o un
-- SenderKeyMessage, y quien recibe necesita saber cual antes de intentar
-- abrirlo. El servidor no puede deducirlo -para el son bytes opacos-, asi que
-- lo transporta tal como se lo dio el remitente.
--
-- 0 = sin cifrar (fase de depuracion) · 2 = sesion · 3 = abre sesion · 7 = grupo

ALTER TABLE sobre_pendiente
    ADD COLUMN IF NOT EXISTS tipo_cifrado smallint NOT NULL DEFAULT 0;
