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
WTFUCK_S3_URL=http://minio:9000
WTFUCK_S3_PUBLICO=https://$D_MEDIA
WTFUCK_S3_USER=wtfuck
WTFUCK_S3_PASS=$MINIO_PASS

WTFUCK_PROPIETARIO=$PROPIETARIO
EOF

chmod 600 .env.produccion

# El secreto de coturn tiene que ser EL MISMO que el del servidor: es un HMAC
# compartido. Si no coinciden, las llamadas conectan cuando ICE encuentra un
# camino directo y fallan cuando hace falta relevo — o sea, funcionan en tu
# wifi y no en datos moviles.
sed -i "s|^static-auth-secret=.*|static-auth-secret=$TURN|" despliegue/turnserver.conf
sed -i "s|^realm=.*|realm=$D_API|" despliegue/turnserver.conf

echo
echo "Listo:"
echo "  .env.produccion            (600, con secretos generados)"
echo "  despliegue/turnserver.conf (realm y secreto puestos)"
echo
echo "Comprobacion rapida de que no quedo ningun CAMBIAR:"
grep -c CAMBIAR despliegue/turnserver.conf || true
echo "(un 0 de arriba es lo correcto)"
echo
echo "Siguiente paso: docs/10-MONTARLO-PASO-A-PASO.md, paso 4."
