-- wtfuck V57 - recuperacion con frase (Fase 1)
--
-- El codigo de recuperacion (28 simbolos, 128 bits) es la unica salida cuando se
-- pierde el telefono, pero hay que anotarlo en papel y la gente lo pierde. Esto
-- agrega una comodidad SIN debilitar nada: el codigo sigue siendo la raiz.
--
-- El telefono cifra el codigo con una clave derivada de una FRASE que elige la
-- persona (PBKDF2 fuerte + sal) y sube ese "sobre" aqui. En un telefono nuevo,
-- con la frase, se descifra el codigo y se sigue la recuperacion de siempre.
--
-- Por que guardar el sobre aqui es aceptable: el codigo es UNA de las cuatro
-- puertas de la recuperacion (SMS, codigo, segundo factor, contrasena nueva), y
-- la copia de seguridad -lo unico que el codigo descifra de verdad- NO vive en
-- el servidor. Aun asi se usa un KDF caro y se limita la entrega del sobre, para
-- que una fuga de la base no regale frases a fuerza bruta.
--
-- bytea y no texto: es [sal(16)][nonce(12)][codigo cifrado + tag GCM], binario.

ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS recuperacion_sobre bytea;
