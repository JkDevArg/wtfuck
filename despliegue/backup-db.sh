#!/usr/bin/env bash
#
# Backup de la base de wtfuck con restic: pg_dump -> restic (cifra y deduplica).
#
#   bash despliegue/backup-db.sh
#
# Pensado para correr por cron en el VPS. La primera vez inicializa el
# repositorio de restic solo si hace falta.
#
# ## Por que restic y no solo pg_dump | gzip
#
# El volcado tiene datos personales (usuarios, dispositivos, invitaciones) —Ley
# 29733—, asi que el backup TIENE que ir cifrado. restic cifra por defecto,
# deduplica (30 dias de backups diarios no ocupan 30 veces), maneja la rotacion
# y puede escribir a un destino remoto. Hacer todo eso a mano con gzip+gpg+cron
# es reinventarlo peor.
#
# ## Que NO cubre
#
# El historial de mensajes NO esta en el servidor (vive en los telefonos), asi
# que esto NO respalda conversaciones. Respalda lo que el servidor SI tiene:
# cuentas, dispositivos, claves publicas, invitaciones, moderacion. Lo que se
# perderia si el VPS muere y con el la base.

set -euo pipefail
cd "$(dirname "$0")/.."

CONF=despliegue/backup.conf
if [ ! -f "$CONF" ]; then
  echo "Falta $CONF. Copialo de la plantilla y rellenalo:"
  echo "  cp despliegue/backup.conf.ejemplo despliegue/backup.conf"
  exit 1
fi
# shellcheck disable=SC1090
source "$CONF"

command -v restic >/dev/null 2>&1 || {
  echo "Falta restic. Instalalo:  sudo apt-get install -y restic"
  exit 1
}

: "${RESTIC_REPOSITORY:?falta en backup.conf}"
: "${RESTIC_PASSWORD:?falta en backup.conf}"
DB_CONTAINER="${DB_CONTAINER:-wtfuck-db-1}"
DB_USER="${DB_USER:-wtfuck}"
DB_NAME="${DB_NAME:-wtfuck}"

# Inicializa el repo la PRIMERA vez. `cat` para no fallar si ya existe: `restic
# init` sobre un repo existente da error, y eso mataria el script cada noche.
if ! restic snapshots >/dev/null 2>&1; then
  echo "==> Inicializando repositorio restic en $RESTIC_REPOSITORY"
  restic init
fi

echo "==> Volcando la base y subiendola cifrada..."
# --format=custom: el volcado comprimido y restaurable con pg_restore, no un SQL
# plano. Se transmite por la tuberia directo a restic; nada toca el disco sin
# cifrar. El nombre fijo hace que restic deduplique contra los dias anteriores.
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom |
  restic backup --stdin --stdin-filename wtfuck.dump --tag automatico

echo "==> Rotando backups viejos..."
restic forget \
  --keep-daily "${KEEP_DAILY:-7}" \
  --keep-weekly "${KEEP_WEEKLY:-4}" \
  --keep-monthly "${KEEP_MONTHLY:-6}" \
  --prune

echo "==> Listo. Ultimos snapshots:"
restic snapshots --compact | tail -6
