#!/usr/bin/env bash
#
# Prepara el APK para repartirlo: lo copia con un nombre con version, calcula
# su huella y genera la pagina de descarga con esa huella dentro.
#
# La huella se genera AQUI y no se escribe a mano por una razon concreta: una
# huella copiada a mano se queda vieja en la primera recompilacion, y una
# huella que no cuadra con el archivo es peor que ninguna — ensena a la gente
# a ignorarla.
#
#   bash despliegue/publicar-apk.sh
#
# Deja todo en despliegue/descarga/, listo para subir al sitio estatico.
#
set -euo pipefail
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/release/app-release.apk
SALIDA=despliegue/descarga

if [ ! -f "$APK" ]; then
  echo "No encuentro $APK"
  echo
  echo "Compilalo primero:"
  echo "  ./gradlew :app:assembleRelease -Papi=TU-DOMINIO -Pabi=arm64-v8a"
  exit 1
fi

case "$(basename "$APK")" in
  *-unsigned*)
    echo "Ese APK esta SIN FIRMAR y no se puede instalar."
    echo "Falta keystore.properties. Ver docs/10-MONTARLO-PASO-A-PASO.md, paso 8."
    exit 1
    ;;
esac

VERSION=$(date +%Y%m%d)
NOMBRE="wtfuck-$VERSION.apk"

mkdir -p "$SALIDA"
cp "$APK" "$SALIDA/$NOMBRE"

HUELLA=$(sha256sum "$SALIDA/$NOMBRE" | cut -d' ' -f1)
TAMANO=$(du -h "$SALIDA/$NOMBRE" | cut -f1)

# La huella del CERTIFICADO, que es distinta de la del archivo y responde otra
# pregunta: el archivo cambia en cada compilacion, el certificado no. Es lo que
# permite saber que una actualizacion viene de quien hizo la primera version.
# Todo el bloque va con `|| true`: sin `ANDROID_HOME` definido, el `find`
# falla y `set -e` mataba el script AQUI, despues de copiar el APK y antes de
# escribir la pagina. Quedaba media publicacion y sin ningun mensaje.
#
# La huella del certificado es util pero no imprescindible; la del archivo si.
# Que falte la primera no puede impedir que se genere la pagina.
CERT=""
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
# En Windows la variable trae barras invertidas —`G:\Android\Sdk`— y en Git
# Bash eso no es una ruta: el `find` no encuentra nada y la pagina salia sin
# la huella del certificado, avisando de que faltaba ANDROID_HOME cuando la
# variable estaba puesta. El aviso mentia y mandaba a mirar donde no era.
SDK="${SDK//\\//}"
if [ -d "$SDK/build-tools" ]; then
  # `-name apksigner*` cogia el `lib/apksigner.jar`, que no es ejecutable.
  # Se busca el lanzador: `.bat` en Windows, sin extension en el resto, y de
  # la version mas alta de build-tools.
  FIRMADOR=$(find "$SDK/build-tools" \( -name apksigner.bat -o -name apksigner \) \
    -not -path "*/lib/*" 2>/dev/null | sort -r | head -1 || true)
  if [ -n "$FIRMADOR" ]; then
    CERT=$("$FIRMADOR" verify --print-certs "$SALIDA/$NOMBRE" 2>/dev/null |
      grep -oE "certificate SHA-256 digest: [0-9a-f]+" | head -1 | sed 's/.*: //' || true)
  fi
fi
if [ -z "$CERT" ]; then
  CERT="(no calculada)"
  echo "AVISO: no encontre apksigner en: $SDK"
  echo "       La pagina saldra sin la huella del certificado. La del archivo si esta."
fi

sed -e "s|@NOMBRE@|$NOMBRE|g" \
    -e "s|@HUELLA@|$HUELLA|g" \
    -e "s|@CERT@|$CERT|g" \
    -e "s|@TAMANO@|$TAMANO|g" \
    -e "s|@FECHA@|$(date +%d/%m/%Y)|g" \
    despliegue/descarga.plantilla.html > "$SALIDA/index.html"

echo "Listo en $SALIDA/"
echo "  $NOMBRE   ($TAMANO)"
echo "  index.html"
echo
echo "Huella del archivo:     $HUELLA"
echo "Huella del certificado: $CERT"
echo
echo "Subelo al sitio estatico:"
echo "  scp $SALIDA/* root@TU-VPS:/home/hackl4bs/htdocs/hackl4bs.com/wtfuck/"
