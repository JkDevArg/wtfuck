-- Version web, W4c: avisos con el navegador cerrado (Web Push, RFC 8030).
--
-- Un segundo proveedor junto a FCM. El token de un `webpush` es la URL del
-- endpoint que le dio al navegador su servicio de push (Google, Mozilla, Apple
-- o Microsoft); el servidor le hace un POST VACIO firmado con VAPID. Ver
-- `Push.kt` y `ConfigWebPush` en el protocolo.
--
-- La columna ya admitia hasta 4096 caracteres y la unicidad del token ya se
-- aplicaba en `guardarTokenPush`: solo cambia la lista de proveedores.
ALTER TABLE dispositivo DROP CONSTRAINT IF EXISTS push_proveedor_valido;
ALTER TABLE dispositivo ADD CONSTRAINT push_proveedor_valido
    CHECK (push_proveedor IS NULL OR push_proveedor IN ('fcm', 'webpush'));
