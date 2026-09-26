#!/usr/bin/env bash
#
# Genera `.env.produccion` y deja `turnserver.conf` listo.
#
# Existe porque este es el paso donde mas se falla, y falla en silencio: un
# secreto vacio o uno escrito a mano no da ningun error al arrancar. Da un
# servicio que funciona y es inseguro, que es peor que uno que no arranca.
#
#   bash despliegue/preparar.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env.produccion ]; then
  echo "Ya existe .env.produccion. No lo toco."
  echo
  echo "Si quieres empezar de cero, guarda una copia primero:"
  echo "  mv .env.produccion .env.produccion.viejo"
  echo
  echo "OJO con WTFUCK_PEPPER_TELEFONO: cambiarlo invalida todos los hashes"
  echo "de telefono guardados. Nadie se encuentra por numero y nadie recupera"
  echo "su cuenta. No es un cambio de variable, es una migracion."
  exit 1
fi

secreto() { openssl rand -base64 32 | tr -d '\n/+=' | head -c 40; }

echo "=== Montaje de wtfuck ==="
echo
read -rp "Dominio de la API      (ej: apiwtf.hackl4bs.com): " D_API
read -rp "Dominio de los medios  (ej: mediawtf.hackl4bs.com): " D_MEDIA
read -rp "Tu usuario, el que sera propietario: " PROPIETARIO

for v in D_API D_MEDIA PROPIETARIO; do
  if [ -z "${!v}" ]; then echo "Falta un dato. Nada escrito."; exit 1; fi
done

# El registro: abierto o por invitacion.
#
# Se pregunta AQUI y no se deja para despues porque cambiarlo luego exige
# editar el .env a mano y reiniciar, y quien monta un servidor para su equipo
# normalmente ya sabe que no lo quiere abierto. Preguntarlo cuesta una linea;
# descubrirlo cuando ya entraron desconocidos, no.
#
# El valor por defecto es ABIERTO, que es el del servidor: la pregunta no
# puede cambiar el comportamiento de quien pulsa Enter sin leer.
echo
echo "Registro:"
echo "  1) Abierto     - cualquiera con el APK se crea una cuenta"
echo "  2) Invitacion  - solo se entra con un codigo que tu repartes"
read -rp "Elige [1]: " MODO
if [ "${MODO:-1}" = "2" ]; then
  REGISTRO=invitacion
else
  REGISTRO=abierto
fi

DB_PASS=$(secreto)
REDIS_PASS=$(secreto)
MINIO_PASS=$(secreto)
BUS=$(secreto)
PEPPER=$(secreto)
TURN=$(secreto)

cat > .env.produccion <<EOF
# Generado por despliegue/preparar.sh el $(date -Iseconds)
#
# ESTE ARCHIVO TIENE SECRETOS. No va a git (ya esta en .gitignore) y no se
# comparte por chat ni por correo.

DOMINIO_API=$D_API
DOMINIO_MEDIA=$D_MEDIA

DB_PASS=$DB_PASS
REDIS_PASS=$REDIS_PASS
MINIO_USER=wtfuck
MINIO_PASS=$MINIO_PASS

WTFUCK_BUS_SECRETO=$BUS

# NO SE PUEDE ROTAR sin una migracion: cambiarlo invalida todos los hashes de
# telefono guardados.
WTFUCK_PEPPER_TELEFONO=$PEPPER

WTFUCK_TURN_SECRETO=$TURN
WTFUCK_TURN_URL=turn:$D_API:3478?transport=udp

# Dos direcciones y no una. La interna es para que el servidor hable con el
# almacen; la publica es la que se FIRMA y ve el telefono. Con una sola, los
# adjuntos no funcionan.
WTFUCK_S3_URL=http://almacen:9000
WTFUCK_S3_PUBLICO=https://$D_MEDIA
WTFUCK_S3_USER=wtfuck
WTFUCK_S3_PASS=$MINIO_PASS

# El propietario. Entra sin codigo aunque el registro este cerrado -si no, un
# servidor en modo invitacion nace bloqueado: nadie puede entrar y hace falta
# estar dentro para invitar- y queda con nivel 100 al registrarse.
WTFUCK_PROPIETARIO=$PROPIETARIO

# `abierto` o `invitacion`. Se cambia aqui y se reinicia el servidor:
#   docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml #     up -d --force-recreate servidor
WTFUCK_REGISTRO=$REGISTRO
EOF

chmod 600 .env.produccion

# El secreto de coturn tiene que ser EL MISMO que el del servidor: es un HMAC
# compartido. Si no coinciden, las llamadas conectan cuando ICE encuentra un
# camino directo y fallan cuando hace falta relevo — o sea, funcionan en tu
# wifi y no en datos moviles.
sed -i "s|^static-auth-secret=.*|static-auth-secret=$TURN|" despliegue/turnserver.conf
sed -i "s|^realm=.*|realm=$D_API|" despliegue/turnserver.conf

# Las credenciales del almacen van en un JSON: SeaweedFS no las toma por
# variable de entorno, al reves que casi todo lo demas de este despliegue.
cat > despliegue/s3.json <<EOF
{
  "identities": [
    {
      "name": "wtfuck",
      "credentials": [
        { "accessKey": "wtfuck", "secretKey": "$MINIO_PASS" }
      ],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
EOF
# 600 para que no lo lea nadie mas del anfitrion, y propiedad del uid 1000
# porque ES QUIEN TIENE QUE LEERLO: el proceso de SeaweedFS baja al usuario
# `seaweed`, que en esa imagen es uid 1000.
#
# Con root:600 —que es lo que hacia antes— el contenedor arranca, monta el
# archivo y muere:
#
#   F auth_credentials.go:372 fail to load config file /etc/seaweedfs/s3.json:
#     permission denied
#
# Y lo que se ve desde fuera es `container wtfuck-almacen-1 is unhealthy`, que
# senala al chequeo de salud y no al permiso.
chmod 600 despliegue/s3.json
chown 1000:1000 despliegue/s3.json 2>/dev/null ||   echo "AVISO: no se pudo cambiar el dueno de s3.json. Si el almacen no arranca, prueba: sudo chown 1000:1000 despliegue/s3.json"

echo
echo "Listo:"
echo "  .env.produccion            (600, con secretos generados)"
echo "  despliegue/turnserver.conf (realm y secreto puestos)"
echo "  despliegue/s3.json         (600, credenciales del almacen)"
echo
if [ "$REGISTRO" = "invitacion" ]; then
  echo "Registro CERRADO. Entra tu primero con el usuario '$PROPIETARIO';"
  echo "despues reparte codigos desde Panel > Invitaciones."
else
  echo "Registro ABIERTO: cualquiera con el APK se crea una cuenta."
  echo "Para cerrarlo: WTFUCK_REGISTRO=invitacion en .env.produccion, y reiniciar."
fi
echo

# Se ignoran los comentarios. La version anterior los contaba y decia "1"
# porque queda un `# external-ip=CAMBIAR_IP_PUBLICA` comentado a proposito:
# una comprobacion que da falsos positivos entrena a ignorarla, que es peor
# que no tenerla.
faltan=$(grep -v '^[[:space:]]*#' despliegue/turnserver.conf | grep -c CAMBIAR || true)
if [ "$faltan" -eq 0 ]; then
  echo "Comprobacion: turnserver.conf sin nada pendiente. Correcto."
else
  echo "OJO: quedan $faltan valores sin poner en despliegue/turnserver.conf."
  grep -vn '^[[:space:]]*#' despliegue/turnserver.conf | grep CAMBIAR || true
  exit 1
fi

echo
echo "Siguiente paso, y el --env-file NO es opcional:"
echo
echo "  docker compose --env-file .env.produccion \\"
echo "    -f docker-compose.tras-proxy.yml up -d --build"
