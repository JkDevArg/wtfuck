-- Modulo Q · Los indices que faltaban para las consultas que se repiten.
--
-- ## Por que hace falta una migracion entera solo para esto
--
-- Ninguna de estas consultas esta mal escrita, y todas responden en
-- milisegundos con la base de desarrollo. Ese es justamente el problema: con
-- nueve mil filas Postgres lee la tabla entera mas rapido de lo que tardaria
-- en decidir usar un indice, asi que una prueba local nunca va a delatar a
-- ninguna. La cuenta que importa es la de produccion —40.000 personas, cada
-- una con uno o dos aparatos y un buzon que crece cada dia— y ahi la misma
-- consulta hace el mismo recorrido completo sobre una tabla cien veces mayor.
--
-- Se anaden CINCO indices y no los treinta que hacen falta para cubrir todas
-- las claves foraneas sin indice de la base. Un indice que nadie usa no es
-- gratis: ocupa disco, se escribe en cada INSERT y UPDATE de la tabla, y hay
-- que vacuumearlo. Los que estan aqui salieron de leer las consultas del
-- servidor y quedarse solo con las que se repiten mucho o las que crecen de
-- forma cuadratica. El resto se explica al final.


-- ## 1. `dispositivo (usuario_id)` — el unico que duele de verdad hoy
--
-- Este servidor es multidispositivo: nada se entrega "a una persona", todo se
-- entrega a CADA aparato vivo de esa persona. Por eso el patron
--
--     JOIN dispositivo d ON d.usuario_id = <alguien> AND d.revocado_en IS NULL
--
-- aparece en el reparto de cada mensaje (`Repo.destinos`), en el de cada
-- evento (`Eventos.aQuien`), en las claves de la conversacion
-- (`Claves.destinos`), en el aviso de "escribiendo", en las confirmaciones de
-- lectura, en la lista de miembros de un grupo y en el reparto de historias.
-- Es, con diferencia, el JOIN mas repetido del servidor.
--
-- Y no habia forma de resolverlo por indice. Los dos indices que tocan
-- `usuario_id` son parciales y ninguno sirve: `dispositivo_un_principal` solo
-- contiene el aparato principal —dejaria fuera al resto, que es justo a quien
-- hay que entregarle— y `dispositivo_un_hardware_una_cuenta` va por
-- `hardware_hash`. El plan real de `Repo.destinos` en la base de desarrollo,
-- antes de esta migracion, era:
--
--     Hash Join
--       ->  Seq Scan on dispositivo  (rows=9510)   <-- la tabla entera
--       ->  Index Scan on participante_pkey (rows=3)
--
-- Nueve mil quinientas filas leidas para devolver tres. Con 40.000 cuentas eso
-- son ~80.000 filas leidas POR MENSAJE ENVIADO, y otras tantas por cada
-- "escribiendo" y cada acuse de lectura. No es una consulta lenta: es una
-- consulta barata multiplicada por el trafico entero de la plataforma.
--
-- ### Por que NO es parcial por `revocado_en IS NULL`
--
-- Seria lo natural, porque casi todas las consultas filtran asi. Pero hay dos
-- que no —`Identidad.revocarTodas` busca los aparatos de una cuenta incluidos
-- los ya revocados, y `Dispositivos` baja la bandera `principal` igual— y esas
-- se quedarian sin indice. Aparte, el ahorro seria ninguno: en la base actual
-- solo 60 de 9.717 filas estan revocadas, o sea que el indice parcial tendria
-- el 99,4% del tamano del completo. Se paga el mismo disco por cubrir menos.
CREATE INDEX IF NOT EXISTS dispositivo_por_usuario ON dispositivo (usuario_id);


-- ## 2. `mensaje_meta (responde_a)` — el que se vuelve cuadratico
--
-- La lista de publicaciones de un canal (`Canales.publicaciones`) trae hasta
-- 100 filas y por cada una cuenta sus respuestas:
--
--     (SELECT count(*) FROM mensaje_meta cm WHERE cm.responde_a = p.mensaje_id)
--
-- `responde_a` no tenia ningun indice —ni siquiera uno parcial que el
-- planificador pudiera forzar: con `enable_seqscan=off` seguia eligiendo Seq
-- Scan porque no habia alternativa—, asi que cada una de esas 100 filas
-- recorre `mensaje_meta` de punta a punta. Y `mensaje_meta` es la tabla que
-- mas crece de toda la base: una fila por mensaje de la plataforma, millones
-- en produccion. Cien recorridos completos de millones de filas para pintar
-- una pantalla de canal es el tipo de consulta que tumba un servidor, no que
-- lo pone lento.
--
-- ### Por que este SI es parcial
--
-- Al reves que el anterior. La gran mayoria de los mensajes no responden a
-- nada, asi que `responde_a IS NOT NULL` deja fuera la mayor parte de la tabla
-- y el indice queda pequeno. Y no pierde nada: la unica consulta que busca por
-- esta columna pregunta `responde_a = <uuid>`, que implica NOT NULL, de modo
-- que el planificador puede usar el indice parcial igual. Las otras dos
-- consultas que mencionan la columna (`Canales.estadisticas`) filtran primero
-- por `conversacion_id` y ya tienen su indice.
CREATE INDEX IF NOT EXISTS mensaje_por_responde_a ON mensaje_meta (responde_a)
    WHERE responde_a IS NOT NULL;


-- ## 3. `contacto (contacto_id)` — la libreta leida al reves
--
-- `Repo.quienesMeConocen` decide quien puede ver mi perfil, mi avatar, mi
-- presencia y mis historias, y una de sus dos mitades es "quien me tiene
-- guardado en SU libreta":
--
--     SELECT k.usuario_id FROM contacto k WHERE k.contacto_id = ?
--
-- La clave primaria es `(usuario_id, contacto_id)`, o sea que esta pregunta va
-- por la segunda columna. Postgres no se queda sin plan —hace un Index Only
-- Scan recorriendo el indice primario entero— pero eso sigue siendo leer todas
-- las libretas de la plataforma para responder por una persona. Con 40.000
-- cuentas y libretas de tamano normal son millones de entradas recorridas cada
-- vez que alguien abre un perfil.
--
-- Este indice es el gemelo exacto de `bloqueo_inverso`, que existe desde V3
-- por la misma razon: una relacion que se guarda en un sentido y se consulta
-- en los dos necesita un indice por cada sentido.
CREATE INDEX IF NOT EXISTS contacto_inverso ON contacto (contacto_id);


-- ## 4 y 5. `denuncia` por denunciante y por conversacion
--
-- Estos dos son mas discutibles que los anteriores y conviene decir por que se
-- anaden igual. Solo los usa el panel de staff, que abre un punado de personas
-- al dia; por frecuencia pura no valdrian la pena. Lo que los justifica es la
-- FORMA de las consultas:
--
--   - `Panel.usuarios` lista hasta 100 cuentas y por cada una cuenta las
--     denuncias que puso (`denunciante_id`). `objetivo_usuario_id` ya tiene
--     indice desde V12; `denunciante_id` se quedo sin el.
--
--   - `Panel.conversaciones` es peor. Ordena por el numero de denuncias
--     (`ORDER BY 7 DESC`), asi que el LIMIT 60 se aplica DESPUES de haber
--     contado las denuncias de TODOS los grupos y canales de la plataforma. No
--     son 60 subconsultas: son tantas como grupos existan, cada una recorriendo
--     `denuncia` entera.
--
-- Y el precio es practicamente cero: una denuncia se escribe cuando alguien
-- denuncia, que en el peor dia son unos cientos de filas. Dos entradas de
-- indice mas por INSERT no se notan en una tabla que casi solo se lee.
CREATE INDEX IF NOT EXISTS denuncia_por_denunciante ON denuncia (denunciante_id);

-- Parcial porque una denuncia apunta a UNA cosa: la mayoria son sobre un
-- mensaje o una persona y dejan esta columna nula. Mismo criterio que
-- `denuncia_por_objetivo` en V12.
CREATE INDEX IF NOT EXISTS denuncia_por_conversacion
    ON denuncia (objetivo_conversacion_id)
    WHERE objetivo_conversacion_id IS NOT NULL;


-- ## Lo que se miro y se dejo sin indice, a proposito
--
--   - `sobre_pendiente` y `evento_pendiente`: las dos colas se leen y se
--     vacian siempre por `destino_dispositivo` y ya tienen
--     `(destino_dispositivo, id)`, que cubre el WHERE y el ORDER BY de una vez.
--     El barrido de expirados tiene su `expira_en`. No falta nada.
--
--   - `sesion`: la consulta caliente de verdad es la de autenticar, que va por
--     `token_hash` y tiene indice unico. Listar y revocar sesiones entra por
--     `dispositivo_id` filtrando `revocado_en IS NULL`, que es exactamente el
--     indice parcial `sesion_activa`.
--
--   - `participante`: `participante_por_usuario` es parcial por
--     `salido_en IS NULL` y todas las consultas filtran asi. La clave primaria
--     cubre el otro sentido.
--
--   - `historia_destino` y `historia_vista`: la clave primaria empieza por
--     `historia_id` y ahi van todos los conteos y los EXISTS; el reparto entra
--     por `historia_destino_por_usuario`. Ya estaban cubiertas.
--
--   - `auditoria`: se escribe mucho y se lee poco, y las tres formas de leerla
--     ya tienen indice (`ORDER BY id DESC` usa la primaria, y estan
--     `auditoria_por_actor` y `auditoria_por_recurso`). Anadir indices a una
--     tabla que es basicamente un append-only es pagar escrituras por nada.
--
--   - `lectura`, `reaccion`, `mencion` por `usuario_id`: se consultan siempre
--     a partir del mensaje, no de la persona, y la clave primaria empieza por
--     `mensaje_id`. Indexarlas seria adivinar una consulta que no existe.
--
--   - `historial_enviado (destino_id)`: si es una busqueda por la segunda
--     columna de la clave primaria, como el caso 3. Se deja fuera porque la
--     tabla guarda una fila por par de aparatos que se transfirieron historial,
--     no por usuario ni por mensaje, y se consulta una vez al vincular un
--     aparato nuevo. Poca tabla y poca frecuencia: es el caso donde el indice
--     costaria mas de lo que ahorra.
