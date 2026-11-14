#!/usr/bin/env bash
#
# Publica el bot oficial de recepcion (@rootbot): arma la imagen en la PC y la
# lleva al VPS.
#
#   bash despliegue/publicar-bot.sh
#
# ## Por que en la PC y no en el VPS
#
# El bot carga el WASM de web/cripto/pkg, que se genera con Rust/wasm-pack
# (web/cripto/construir.sh) y esta en .gitignore. Igual que la version web, el
# artefacto se arma donde estan las herramientas (la PC); al VPS va el resultado,
# aca la imagen Docker por `save | load` (sin registro de por medio).
#
# ## Antes de la PRIMERA vez
#
#   1. rootbot tiene que estar en WTFUCK_BOTS del servidor (.env.produccion) para
#      poder registrarse sin enclave. Ver docs/14-BOTS.md.
#   2. En el .env.produccion del VPS:
#        ROOTBOT_PASS=$(openssl rand -base64 32)   # la clave de la cuenta del bot
#        WTFUCK_BOT_WEB=webfck.tu-dominio.com      # opcional, para los textos
#   3. El servicio `rootbot` del compose viene por `git pull` (este script lo hace).
#
# ESTO ES BASH (Git Bash en Windows). Necesita Docker en la PC.

set -euo pipefail
cd "$(dirname "$0")/.."

CONF=despliegue/despliegue.conf
# shellcheck disable=SC1090
[ -f "$CONF" ] && source "$CONF"
VPS_SSH="${VPS_SSH:?falta VPS_SSH en despliegue/despliegue.conf}"
SUDO="${SUDO:-}"
OPT_DIR="${OPT_DIR:-/opt/wtfuck}"
COMPOSE="${COMPOSE:-docker-compose.tras-proxy.yml}"

command -v docker >/dev/null 2>&1 || { echo "ERROR: falta Docker en la PC."; exit 1; }

WASM=web/cripto/pkg/wtfuck_cripto_bg.wasm
[ -f "$WASM" ] || { echo "ERROR: falta $WASM. Arma el WASM primero:"; echo "  bash web/cripto/construir.sh"; exit 1; }

echo "==> Construyendo imagen wtfuck-rootbot (contexto: raiz del repo)..."
docker build -f bots/Dockerfile -t wtfuck-rootbot:latest .

echo "==> Enviando la imagen al VPS (docker save | gzip | ssh docker load)..."
docker save wtfuck-rootbot:latest | gzip | ssh "$VPS_SSH" "${SUDO:+$SUDO }docker load"

echo "==> Trayendo el compose nuevo y levantando el servicio rootbot..."
ssh "$VPS_SSH" "cd '$OPT_DIR' && ${SUDO:+$SUDO }git pull && ${SUDO:+$SUDO }docker compose --env-file .env.produccion -f '$COMPOSE' up -d rootbot"

echo
echo "LISTO. Ver los logs del bot:"
echo "  ssh $VPS_SSH \"cd $OPT_DIR && ${SUDO:+$SUDO }docker compose --env-file .env.produccion -f $COMPOSE logs -f rootbot\""
