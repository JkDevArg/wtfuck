-- ============================================================
--  V49: la version web, un aparato de nivel NAVEGADOR
-- ============================================================
--
-- Un navegador no tiene enclave ni atestacion: sus claves viven en el propio
-- navegador. Entra a una cuenta solo VINCULANDOSE desde un aparato que ya esta
-- dentro (lo hace cumplir `Repo.nivelParaVincular`), y nunca puede ser el
-- principal: el principal es el que autoriza a los demas, y eso no se le
-- confia a un navegador. Lo segundo lo garantiza ademas la base, para que un
-- error en el codigo no pueda dejarlo de principal.
--
-- Ver docs/12-VERSION-WEB.md.
ALTER TABLE dispositivo DROP CONSTRAINT IF EXISTS nivel_valido;
ALTER TABLE dispositivo ADD CONSTRAINT nivel_valido
    CHECK (hardware_nivel IN ('STRONGBOX','TEE','SOFTWARE_DEV','NAVEGADOR'));

ALTER TABLE dispositivo DROP CONSTRAINT IF EXISTS navegador_nunca_principal;
ALTER TABLE dispositivo ADD CONSTRAINT navegador_nunca_principal
    CHECK (NOT (principal AND hardware_nivel = 'NAVEGADOR'));
