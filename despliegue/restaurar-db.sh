#!/usr/bin/env bash
#
# Restaura la base de wtfuck desde el ultimo backup de restic.
#
#   bash despliegue/restaurar-db.sh            # el ultimo
#   bash despliegue/restaurar-db.sh <snapshot> # uno concreto (id de restic)
#
# ## ESTO PISA LA BASE ACTUAL
#
# `pg_restore --clean` borra y recrea los objetos antes de cargar. Si la base
# tiene datos que no estan en el backup, se pierden. Por eso pide confirmacion.
# Un backup solo sirve si restaurar de verdad funciona: pruebalo alguna vez en
# un servidor de juguete antes de necesitarlo en serio.

set -euo pipefail
cd "$(dirname "$0")/.."

CONF=despliegue/backup.conf
[ -f "$CONF" ] || { echo "Falta $CONF."; exit 1; }
# shellcheck disable=SC1090
source "$CONF"
command -v restic >/dev/null 2>&1 || { echo "Falta restic."; exit 1; }

DB_CONTAINER="${DB_CONTAINER:-wtfuck-db-1}"
DB_USER="${DB_USER:-wtfuck}"
DB_NAME="${DB_NAME:-wtfuck}"
SNAP="${1:-latest}"

echo "Snapshots disponibles:"
restic snapshots --compact | tail -8
echo
echo "Voy a RESTAURAR el snapshot '$SNAP' sobre la base '$DB_NAME'."
echo "Esto PISA los datos actuales. No se puede deshacer."
read -rp "Escribe RESTAURAR para continuar: " r
[ "$r" = "RESTAURAR" ] || { echo "Cancelado."; exit 0; }

echo "==> Restaurando..."
# restic entrega el volcado por la salida; pg_restore lo carga dentro del
# contenedor. --clean borra lo viejo; --if-exists evita ruido si algo no estaba.
restic dump "$SNAP" wtfuck.dump |
  docker exec -i "$DB_CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --no-owner

echo "==> Listo. Reinicia el servidor para que tome la base restaurada:"
echo "  docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml restart servidor"
