-- W5e · Key Attestation de Android. Ver `Atestacion.kt` y
-- docs/04-DEVICE-BINDING.md.

-- Los retos: 32 bytes al azar que el chip mete DENTRO del certificado. De un
-- solo uso y con vencimiento, en la base y no en memoria: con varias
-- instancias, el reto lo emite una y lo puede canjear otra.
CREATE TABLE IF NOT EXISTS reto_atestacion (
    reto       bytea PRIMARY KEY,
    creado_en  timestamptz NOT NULL DEFAULT now(),
    expira_en  timestamptz NOT NULL,
    usado_en   timestamptz
);
CREATE INDEX IF NOT EXISTS reto_atestacion_viejo ON reto_atestacion (expira_en);

-- Lo que se leyo de la atestacion de cada aparato: `verificada:TEE`,
-- `fallida:bootloader-desbloqueado`, `no-aplica` (navegador)... En el modo
-- `registrar` es la MEDICION que decide si se puede exigir: cuantos telefonos
-- reales fallan y por que.
ALTER TABLE dispositivo ADD COLUMN IF NOT EXISTS atestacion text;
ALTER TABLE dispositivo ADD COLUMN IF NOT EXISTS atestacion_en timestamptz;
