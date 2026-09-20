-- ============================================================
--  V14: el canal verificado es el TELEFONO, no el correo
-- ============================================================
--
-- V13 puso un correo opcional como canal de verificacion. Se reemplaza por el
-- numero de telefono, que es lo que esta app tiene que usar.
--
-- Lo que NO cambia, y conviene decirlo: **el ingreso sigue siendo por username
-- y contrasena**. El telefono no es una credencial ni un identificador de
-- ingreso; es el canal por el que se recupera una cuenta y por el que otra
-- persona te encuentra. Si sirviera para entrar, volveria a ser el
-- identificador de la cuenta y se perderia lo que el registro por username
-- resolvio: que nadie necesite dar su numero para hablar.
--
-- Todo el mecanismo de V13 -codigos de un solo uso, vencimiento, tope de
-- intentos, descubrimiento por hash con pepper- se reusa tal cual. Lo unico
-- que cambia es QUE se hashea y por donde viaja el codigo.


-- ============================================================
--  Las columnas
-- ============================================================
--
-- Se BORRAN las de correo en vez de dejarlas. No hay datos que migrar -el
-- correo vivio unas horas y solo en desarrollo- y dejar columnas muertas es
-- como nacen los esquemas que nadie entiende: alguien las encuentra en un ano y
-- no sabe si se usan.

ALTER TABLE usuario
    DROP COLUMN IF EXISTS correo_hash,
    DROP COLUMN IF EXISTS correo_dominio,
    DROP COLUMN IF EXISTS correo_verificado_en;

ALTER TABLE usuario
    -- HMAC del numero YA NORMALIZADO a E.164 (+51987654321). La normalizacion
    -- es lo critico: el mismo telefono escrito "987 654 321",
    -- "+51 987-654-321" y "0051987654321" tiene que dar el MISMO hash, o el
    -- descubrimiento falla en silencio y nadie entiende por que.
    ADD COLUMN telefono_hash        bytea,

    -- El prefijo de pais, en claro. No identifica a nadie por si mismo -lo
    -- comparten millones- y permite mostrar "+51" en la pantalla sin guardar
    -- el numero.
    ADD COLUMN telefono_pais        text,

    ADD COLUMN telefono_verificado_en timestamptz;

CREATE UNIQUE INDEX usuario_telefono_unico
    ON usuario (telefono_hash) WHERE telefono_hash IS NOT NULL;

ALTER TABLE usuario DROP CONSTRAINT IF EXISTS correo_coherente;
ALTER TABLE usuario
    ADD CONSTRAINT telefono_coherente CHECK (
        telefono_verificado_en IS NULL OR telefono_hash IS NOT NULL
    );

COMMENT ON COLUMN usuario.telefono_hash IS
    'HMAC-SHA256(pepper, numero en E.164). El pepper vive en WTFUCK_PEPPER_TELEFONO, fuera de la base: sin el, una fuga no permite probar candidatos. El numero NO se guarda; se recibe cada vez que hace falta usarlo.';

COMMENT ON COLUMN usuario.telefono_pais IS
    'Prefijo de pais en claro (por ejemplo "51"). Lo comparten millones de personas, asi que no identifica a nadie.';


-- ============================================================
--  Los propositos de los codigos
-- ============================================================
--
-- El CHECK nombraba el correo. La tabla y el mecanismo son los mismos; lo que
-- se verifica ahora es un telefono.

-- EL ORDEN IMPORTA, y no de forma obvia: un CHECK se valida contra las filas
-- que YA estan cuando se crea. Poner el ADD CONSTRAINT antes del DELETE hace
-- que la migracion falle con "is violated by some row", porque las filas de
-- correo de V13 todavia estan ahi. Primero se limpia, despues se restringe.
ALTER TABLE codigo_verificacion DROP CONSTRAINT proposito_valido;

-- Los codigos de correo que quedaron de V13 no valen para nada: apuntan al
-- hash de una direccion que ya no existe en ninguna cuenta.
DELETE FROM codigo_verificacion WHERE proposito = 'verificar_correo';

ALTER TABLE codigo_verificacion ADD CONSTRAINT proposito_valido CHECK (
    proposito IN ('verificar_telefono', 'recuperar_cuenta', 'eliminar_cuenta')
);

COMMENT ON TABLE codigo_verificacion IS
    'Codigos de un solo uso, con vencimiento y tope de intentos. `destino_hash` es el hash del telefono al que se mando: sirve para que un codigo emitido para un numero no valga para otro.';
