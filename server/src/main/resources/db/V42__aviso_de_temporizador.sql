-- El aviso de que alguien cambio el temporizador de mensajes.
--
-- ## Por que hace falta un aviso
--
-- Porque quien recibe es EL UNICO que puede borrar su copia. El servidor no
-- tiene el mensaje: entrega el sobre y lo olvida. Asi que el temporizador solo
-- se cumple si el otro telefono se entera de que existe, y hasta entonces todo
-- lo que le llegue se guarda como permanente.
--
-- Antes de esto la ruta `PUT /conversaciones/{id}/temporales` era de solo
-- escritura: se podia encender el temporizador y ningun cliente podia leerlo.
-- El resultado era que el mensaje temporal se borraba en el telefono de quien
-- lo escribio y se quedaba para siempre en el de quien lo leyo. La funcion
-- prometia una cosa y hacia la mitad, sin fallar ni avisar.
--
-- ## Y por que en su propia migracion
--
-- Porque V39 —que creo `temporales_segundos`— YA SE APLICO. Editarla seria
-- como se arman dos bases que no coinciden: el registro dice que la 39 esta
-- hecha, nadie vuelve a mirarla, y lo agregado no corre nunca ahi y si en las
-- instalaciones nuevas.
--
-- `evento_pendiente` valida el tipo contra una lista cerrada, y la defensa
-- funciono: sin esta linea, cambiar el temporizador devolvia 500 con el CHECK
-- violado. Un tipo de evento mal escrito no se entrega en silencio — que es
-- exactamente lo que se quiere de una lista cerrada.
--
-- Se reconstruye la restriccion entera porque Postgres no deja agregarle un
-- valor a un CHECK existente.
ALTER TABLE evento_pendiente DROP CONSTRAINT IF EXISTS tipo_evento_valido;
ALTER TABLE evento_pendiente ADD CONSTRAINT tipo_evento_valido CHECK (tipo = ANY (ARRAY[
    'agregado_grupo', 'sacado_grupo', 'grupo_renombrado', 'expulsado', 'silenciado',
    'rol_cambiado', 'solicitud_aprobada', 'solicitud_rechazada', 'solicitud_nueva',
    'mensaje_retirado', 'mensaje_editado', 'mensaje_fijado', 'mensaje_reaccion',
    'canal_publicacion', 'canal_aprobado', 'canal_rechazado', 'canal_comentario',
    'advertencia', 'sancion', 'sesion_revocada', 'dispositivo_vinculado',
    'dispositivo_revocado', 'historial_pedido', 'llamada_entrante',
    'llamada_contestada', 'llamada_terminada', 'llamada_participante',
    'conversacion_cerrada', 'conversacion_reabierta', 'mensaje_leido',
    'conversacion_vencida', 'conversacion_temporales'
]));

-- Nota sobre el plazo minimo, que NO se toca.
--
-- Se intento bajarlo de 60 a 30 segundos, copiando a Signal. El CHECK
-- `temporales_valido` de la V6 lo rechazo, y al mirar por que resulto tener
-- razon en esta app: el transporte es un buzon que reentrega, un sobre puede
-- quedar encolado minutos si el telefono destino esta apagado, y el
-- vencimiento se cuenta desde que se escribio. Con 30 segundos ese mensaje
-- llega ya vencido y el barrido se lo lleva antes de que nadie lo vea — no es
-- un mensaje que se borra pronto, es un mensaje que no llega. Queda en 60.
