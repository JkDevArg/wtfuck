#!/usr/bin/env bash
#
# Publicar una version nueva del APK en UN comando.
#
#   bash despliegue/lanzar.sh 0.5.1                   # mejora pequena: sube el ultimo
#   bash despliegue/lanzar.sh 0.6.0 --con-servidor    # mejora grande o cambio del servidor
#
# Como numerar (semver): mejora pequena sube el ULTIMO numero (0.5.0 -> 0.5.1);
# mejora grande sube el DEL MEDIO y el ultimo vuelve a 0 (0.5.3 -> 0.6.0). El
# versionCode interno lo sube el script solo, sin importar el nombre.
#
# Hace, en orden, lo que antes eran cinco pasos a mano:
#
#   1. Pregunta al servidor que versionCode esta publicado y usa el siguiente.
#   2. Compila el APK de release (firmado, con ese versionCode y el nombre dado).
#   3. Genera la pagina de descarga y la huella (llama a publicar-apk.sh).
#   4. Sube el APK y el index.html al sitio estatico.
#   5. Actualiza las 4 variables WTFUCK_APK_* en el .env del servidor y lo reinicia.
#   6. Comprueba que el servidor ya anuncia la version nueva.
#
# ## Por que el versionCode sale del servidor y no de un contador local
#
# Un contador en un archivo se desincroniza en cuanto compilas dos veces, o
# clonas el repo en otra maquina. El servidor ES la fuente de verdad: sabe que
# version esta publicada de verdad. El siguiente es ese mas uno, siempre.
#
# ## Que NO hace, a proposito
#
# No inventa el changelog: las Novedades las escribes tu en NovedadesPantalla.kt
# antes de lanzar, porque solo tu sabes que cambio para el usuario. El script
# te lo recuerda.
#
# ## Lo que toca tu servidor
#
# Los pasos 4 y 5 usan scp y ssh contra el VPS. Antes de hacerlos el script te
# muestra que va a subir y a donde, y PIDE confirmacion. Nada sale sin tu ok.

set -euo pipefail
cd "$(dirname "$0")/.."

# ------------------------------------------------------------------
#  Configuracion
# ------------------------------------------------------------------
CONF=despliegue/despliegue.conf
if [ ! -f "$CONF" ]; then
  echo "Falta $CONF."
  echo "Copialo de la plantilla y rellenalo:"
  echo "  cp despliegue/despliegue.conf.ejemplo despliegue/despliegue.conf"
  exit 1
fi
# shellcheck disable=SC1090
source "$CONF"

for v in VPS_SSH DOCROOT DOMINIO_DESCARGA DOMINIO_API OPT_DIR COMPOSE ABIS; do
  if [ -z "${!v:-}" ] || [[ "${!v}" == *TU-VPS* ]]; then
    echo "Falta configurar $v en $CONF (sigue con un valor de plantilla)."
    exit 1
  fi
done

VERSION_NOMBRE="${1:-}"
if [ -z "$VERSION_NOMBRE" ]; then
  echo "Uso: bash despliegue/lanzar.sh <versionName> [--con-servidor]"
  echo "  ej: bash despliegue/lanzar.sh 0.5.0"
  exit 1
fi
CON_SERVIDOR=0
[ "${2:-}" = "--con-servidor" ] && CON_SERVIDOR=1

# ------------------------------------------------------------------
#  Recordatorio del changelog
# ------------------------------------------------------------------
NOV=app/src/main/java/com/wtfuck/app/ui/NovedadesPantalla.kt
if ! grep -q "version = \"$VERSION_NOMBRE\"" "$NOV"; then
  echo "AVISO: no veo la version $VERSION_NOMBRE en las Novedades ($NOV)."
  echo "       Los usuarios no van a ver que cambio. Agrega la entrada antes de lanzar."
  read -rp "Seguir igual? [s/N] " r
  [ "$r" = "s" ] || [ "$r" = "S" ] || { echo "Cancelado."; exit 1; }
fi

# ------------------------------------------------------------------
#  1. El siguiente versionCode, preguntando al servidor
# ------------------------------------------------------------------
echo "==> Preguntando al servidor la version publicada..."
ACTUAL=$(curl -s -m 15 "https://$DOMINIO_API/v1/version" |
  grep -oE '"versionCode"[: ]*[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
ACTUAL=${ACTUAL:-0}
CODE=$((ACTUAL + 1))
echo "    publicado: $ACTUAL  ->  nuevo: $CODE ($VERSION_NOMBRE)"

# ------------------------------------------------------------------
#  2 y 3. Compilar y generar la pagina de descarga
# ------------------------------------------------------------------
echo "==> Compilando el APK de release..."
./gradlew :app:assembleRelease \
  -Papi="$DOMINIO_API" -PversionCode="$CODE" -PversionName="$VERSION_NOMBRE" -Pabi="$ABIS" -q

echo "==> Generando la pagina de descarga y la huella..."
bash despliegue/publicar-apk.sh >/dev/null

APK=$(ls -t despliegue/descarga/wtfuck-*.apk | head -1)
NOMBRE_ARCHIVO=$(basename "$APK")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
URL="$DOMINIO_DESCARGA/$NOMBRE_ARCHIVO"
echo "    $NOMBRE_ARCHIVO"
echo "    sha256: $SHA"
echo "    url:    $URL"

# ------------------------------------------------------------------
#  4 y 5. Lo que toca el servidor. Con confirmacion.
# ------------------------------------------------------------------
echo
echo "Voy a SUBIR al servidor y REINICIARLO:"
echo "  scp  $NOMBRE_ARCHIVO + index.html  ->  $VPS_SSH:$DOCROOT/"
echo "  .env WTFUCK_APK_VERSION=$CODE, NOMBRE=$VERSION_NOMBRE, URL, SHA256"
[ "$CON_SERVIDOR" = 1 ] && echo "  y RECONSTRUIR el servidor (--con-servidor)" \
                        || echo "  y reiniciar el servidor (sin reconstruir)"
read -rp "Continuar? [s/N] " r
[ "$r" = "s" ] || [ "$r" = "S" ] || { echo "Cancelado. El APK quedo en despliegue/descarga/."; exit 0; }

echo "==> Subiendo APK y pagina..."
scp "$APK" despliegue/descarga/index.html "$VPS_SSH:$DOCROOT/"

echo "==> Actualizando el .env del servidor y reiniciando..."
# `git pull` primero SOLO si se pidio reconstruir: el codigo nuevo del servidor
# ya tiene que estar en el VPS para que --build lo tome. Si es solo APK, no hace
# falta tocar el codigo del servidor.
RECREA="up -d --force-recreate servidor"
[ "$CON_SERVIDOR" = 1 ] && RECREA="up -d --build servidor"
# Se borran las lineas WTFUCK_APK_* viejas y se anaden las nuevas: asi no se
# duplican ni quedan mezcladas con las de una publicacion anterior.
ssh "$VPS_SSH" "cd '$OPT_DIR' && \
  { [ $CON_SERVIDOR = 1 ] && git pull || true; } && \
  sed -i '/^WTFUCK_APK_/d' .env.produccion && \
  printf 'WTFUCK_APK_VERSION=%s\nWTFUCK_APK_NOMBRE=%s\nWTFUCK_APK_URL=%s\nWTFUCK_APK_SHA256=%s\n' \
    '$CODE' '$VERSION_NOMBRE' '$URL' '$SHA' >> .env.produccion && \
  docker compose --env-file .env.produccion -f '$COMPOSE' $RECREA"

# ------------------------------------------------------------------
#  6. Comprobar
# ------------------------------------------------------------------
echo "==> Comprobando que el servidor anuncia la version nueva..."
sleep 4
PUB=$(curl -s -m 15 "https://$DOMINIO_API/v1/version" |
  grep -oE '"versionCode"[: ]*[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
if [ "$PUB" = "$CODE" ]; then
  echo "LISTO. Publicada la $VERSION_NOMBRE (versionCode $CODE)."
  echo "Los telefonos la veran al abrir la app o al reconectar."
else
  echo "OJO: el servidor anuncia '$PUB', esperaba '$CODE'."
  echo "     Revisa el .env.produccion y el reinicio en el VPS."
  exit 1
fi
