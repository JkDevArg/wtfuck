-- wtfuck V58 - reseteo de contrasena iniciado por un administrador
--
-- El caso: una persona olvida la contrasena y NO tiene como probar su identidad
-- por SMS ni correo (el servidor puede no tener pasarela). El flujo normal de
-- "olvide mi contrasena" no le sirve, y fijar un codigo de recuperacion pide la
-- contrasena actual, que es justo la que no tiene.
--
-- Salida: un administrador genera un enlace de UN SOLO USO. La persona lo abre y
-- elige una contrasena nueva. El enlace vence, y al usarse se quema.
--
-- Por que es seguro aunque lo haga un admin: la contrasena SOLO sirve para
-- ingresar; no descifra los mensajes (E2EE) ni da de alta un telefono nuevo (eso
-- lo frena el device binding, que pide el codigo/frase). Un admin que resetea no
-- puede leer chats ni hacerse pasar por la persona desde otro aparato. Igual
-- queda auditado y limitado a quien esta por encima del objetivo.
--
-- Se guarda el HASH del token, no el token: una fuga de la base no entrega
-- enlaces usables.

CREATE TABLE reseteo_password (
    token_hash  bytea PRIMARY KEY,
    usuario_id  uuid NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    creado_por  uuid REFERENCES usuario(id) ON DELETE SET NULL,
    creado_en   timestamptz NOT NULL DEFAULT now(),
    expira_en   timestamptz NOT NULL,
    usado_en    timestamptz
);

CREATE INDEX reseteo_password_usuario ON reseteo_password (usuario_id);
