-- ============================================================
--  V50: contador por red, para lo que pasa antes de tener cuenta
-- ============================================================
--
-- `contador_uso` (V12) cuenta por usuario y su clave cuelga de `usuario`. El
-- registro no tiene usuario todavia: lo unico que se sabe de quien llama es de
-- donde viene. Esta tabla es la misma idea con otra clave.
--
-- `red` es lo que devuelve `Seguridad.redDe`: la IPv4, o el /64 de una IPv6.
-- Es texto y no `inet` porque el /64 ya viene agregado y porque, si la IP no
-- es un literal, se guarda tal cual.
--
-- Es un dato personal (una IP) y por eso vive poco: `Cupos.barrer` borra las
-- ventanas de mas de dos dias, lo mismo que con `contador_uso`.
CREATE TABLE contador_red (
    red         text NOT NULL,
    accion      text NOT NULL,
    ventana     timestamptz NOT NULL,   -- inicio de la ventana, ya redondeado
    n           integer NOT NULL DEFAULT 0,

    PRIMARY KEY (red, accion, ventana)
);

CREATE INDEX contador_red_viejo ON contador_red (ventana);
