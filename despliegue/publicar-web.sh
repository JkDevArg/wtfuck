#!/usr/bin/env bash
#
# Arma la version web y la deja lista para subir, igual que el APK.
#
#   bash despliegue/publicar-web.sh
#
# Hace, en orden:
#   1. Arma el WebAssembly de libsignal (web/cripto/construir.sh).
#   2. Instala dependencias EXACTAS (npm ci) y arma la app (web/app/dist).
#   3. Corre las pruebas de la web: si fallan, no se publica nada.
#   4. Calcula la huella SHA-256 de cada archivo y la deja en HUELLAS.txt,
#      dentro de lo publicado: es lo que permite comprobar que la web que
#      sirve el servidor es la que se armo aqui (docs/12-VERSION-WEB.md).
#   5. Empaqueta todo en despliegue/descarga/wtfuck-web-AAAAMMDD-HHMM.tar.gz
#      e imprime los comandos para subirlo.
#
# NO sube nada ni toca el VPS: los comandos los corres tu.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> WebAssembly"
bash web/cripto/construir.sh >/dev/null

echo "==> App"
(cd web/app && npm ci --no-audit --no-fund >/dev/null && npm test --silent && npm run build --silent)

DIST=web/app/dist
(cd "$DIST" && find . -type f ! -name HUELLAS.txt | sort | xargs sha256sum > HUELLAS.txt)

SELLO=$(date +%Y%m%d-%H%M)
mkdir -p despliegue/descarga
PAQUETE=despliegue/descarga/wtfuck-web-$SELLO.tar.gz
tar -czf "$PAQUETE" -C "$DIST" .
SHA_PAQUETE=$(sha256sum "$PAQUETE" | cut -d' ' -f1)
SHA_WASM=$(grep '\.wasm$' "$DIST/HUELLAS.txt" | cut -d' ' -f1)

cat <<FIN

Listo: $PAQUETE
  sha256 del paquete: $SHA_PAQUETE
  sha256 del .wasm:   $SHA_WASM
  (todas las huellas en $DIST/HUELLAS.txt)

Para publicarla, en tu PC:
  scp $PAQUETE TU-USUARIO@TU-VPS:/tmp/

Y en el VPS:
  sudo mkdir -p /opt/wtfuck/web-publicada
  sudo find /opt/wtfuck/web-publicada -mindepth 1 -delete
  sudo tar -xzf /tmp/$(basename "$PAQUETE") -C /opt/wtfuck/web-publicada
  curl -s https://TU-API/web/HUELLAS.txt | grep wasm

El servidor la sirve en /web sin reiniciar: lee los archivos de la carpeta.
FIN
