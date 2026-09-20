-- ============================================================
--  V27: despertar un aparato que no tiene el socket abierto
-- ============================================================
--
-- ## El problema que resuelve, y el unico que resuelve
--
-- El servicio en primer plano (K.8) hizo que una llamada EN CURSO sobreviva a
-- salir de la app. No resuelve el caso de la app **cerrada**: ahi no hay
-- proceso al que avisarle, el socket no existe, y una llamada entrante no
-- suena. Eso no se arregla con mas codigo dentro de la app: hace falta que
-- alguien de afuera la despierte, y en Android ese alguien es el servicio de
-- mensajeria del sistema.
--
-- ## Lo que NO viaja en el aviso
--
-- Nada. Ni el texto, ni quien escribe, ni la conversacion. El aviso es un
-- "tenes algo, conectate" y punto; el telefono abre el socket y se baja sus
-- sobres, que es lo que ya hacia al arrancar.
--
-- Eso es deliberado y es la diferencia entre este push y el de casi cualquier
-- otra app. Poner el texto en el payload seria entregarle el contenido a un
-- tercero justo despues de haberlo cifrado de extremo a extremo, y poner el
-- nombre de quien escribe le entregaria el grafo social. Lo que el proveedor
-- aprende es que este aparato recibio un aviso a esta hora: metadato que ya
-- tiene de cualquier app instalada.
--
-- ## Por que la columna va en `dispositivo` y no en `usuario`
--
-- Porque el token identifica una **instalacion**, no una persona: si la misma
-- cuenta tiene telefono y tablet, son dos tokens y los dos hay que despertar.
-- Es la misma razon por la que la direccion de Signal en este proyecto es el
-- dispositivo y no el usuario.

ALTER TABLE dispositivo ADD COLUMN IF NOT EXISTS push_token text;

-- Que servicio lo entrega. Hoy solo 'fcm', pero la columna existe para que
-- agregar otro -UnifiedPush, APNs- no sea una migracion con datos adentro.
ALTER TABLE dispositivo ADD COLUMN IF NOT EXISTS push_proveedor text;

ALTER TABLE dispositivo ADD COLUMN IF NOT EXISTS push_en timestamptz;

-- Cuantas veces seguidas fallo el envio a este token.
--
-- Sirve para dejar de intentar: un token de una app desinstalada falla para
-- siempre, y sin un contador el servidor gasta una peticion HTTP por cada
-- mensaje que le llegue a esa cuenta, para nada.
ALTER TABLE dispositivo ADD COLUMN IF NOT EXISTS push_fallos int NOT NULL DEFAULT 0;

ALTER TABLE dispositivo DROP CONSTRAINT IF EXISTS push_proveedor_valido;
ALTER TABLE dispositivo ADD CONSTRAINT push_proveedor_valido
    CHECK (push_proveedor IS NULL OR push_proveedor IN ('fcm'));

-- El token y el proveedor van juntos o no van: un token sin proveedor no se
-- puede entregar y un proveedor sin token no apunta a nada.
ALTER TABLE dispositivo DROP CONSTRAINT IF EXISTS push_completo;
ALTER TABLE dispositivo ADD CONSTRAINT push_completo
    CHECK ((push_token IS NULL) = (push_proveedor IS NULL));

-- Indice parcial: la mayoria de los dispositivos no tendra token -el push es
-- opcional y depende de que el servidor este configurado-, y un indice sobre
-- una columna casi toda nula ocupa de mas si no se filtra.
CREATE INDEX IF NOT EXISTS dispositivo_con_push
    ON dispositivo (id) WHERE push_token IS NOT NULL;
