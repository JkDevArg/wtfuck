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

# El `versionCode` del APK, que es lo unico que Android mira para decidir si
# una version es mas nueva que la instalada. Se LEE del archivo en vez de
# escribirlo a mano aqui: un numero copiado se desincroniza del APK en la
# primera prisa, y entonces el servidor anuncia una version que no existe —
# los telefonos descargan, la huella cuadra, y el instalador la rechaza por no
# ser mas nueva. El sintoma es "la actualizacion no hace nada".
CODIGO=""

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
# Donde esta el SDK. Se busca en varios sitios porque este script corre en Git
# Bash, donde ANDROID_HOME casi nunca esta puesto —lo pone env.ps1, pero solo
# para PowerShell—. Sin encontrarlo, antes el versionCode salia como
# "PONLO-A-MANO", que apaga las actualizaciones si se pega entero.
#
#   1. ANDROID_HOME / ANDROID_SDK_ROOT del entorno, si estan.
#   2. sdk.dir de local.properties, donde Gradle lo tiene apuntado.
#   3. Rutas tipicas, incluida cualquier unidad de Windows (el SDK de este
#      equipo vive en G:\Android\Sdk, fuera del home).
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK=$(grep -E "^sdk.dir=" local.properties | head -1 | cut -d= -f2-)
fi
# En Windows la variable y local.properties traen barras invertidas —G:\Android
# o G:\\Android— y en Git Bash eso no es una ruta.
SDK="${SDK//\\//}"
if [ ! -d "$SDK/build-tools" ]; then
  for cand in "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk" \
              /?/Android/Sdk /?/Users/*/AppData/Local/Android/Sdk; do
    c="${cand//\\//}"
    [ -d "$c/build-tools" ] && { SDK="$c"; break; }
  done
fi
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

# El versionCode, con la misma tolerancia que el certificado: si no se puede
# leer, se avisa y se sigue. La pagina de descarga no lo necesita; el bloque
# de variables del servidor si, y ahi es donde se dice que falta.
if [ -d "$SDK/build-tools" ]; then
  AAPT=$(find "$SDK/build-tools" \( -name aapt2.exe -o -name aapt2 \)     -not -path "*/lib/*" 2>/dev/null | sort -r | head -1 || true)
  if [ -n "$AAPT" ]; then
    CODIGO=$("$AAPT" dump badging "$SALIDA/$NOMBRE" 2>/dev/null |
      grep -oE "versionCode='[0-9]+'" | head -1 | grep -oE "[0-9]+" || true)
  fi
fi
# Respaldo sin SDK: AGP escribe `output-metadata.json` junto al APK, con el
# versionCode y el versionName dentro. No necesita aapt ni ANDROID_HOME, asi
# que cubre justo el caso de correr esto en Git Bash sin el SDK en el entorno.
META=app/build/outputs/apk/release/output-metadata.json
if [ -z "$CODIGO" ] && [ -f "$META" ]; then
  CODIGO=$(grep -oE '"versionCode"[: ]+[0-9]+' "$META" | grep -oE '[0-9]+' | head -1)
fi
NOMBRE_VER=""
if [ -f "$META" ]; then
  NOMBRE_VER=$(grep -oE '"versionName"[: ]+"[^"]*"' "$META" | sed -E 's/.*"([^"]*)"$/\1/' | head -1)
fi
# El nombre visible: el versionName si se pudo leer, si no la fecha. Nunca el
# versionCode a secas, que es un numero interno.
[ -n "$NOMBRE_VER" ] && VERSION_VISIBLE="$NOMBRE_VER" || VERSION_VISIBLE="$VERSION"
if [ -z "$CODIGO" ]; then
  # Sin versionCode NO se imprime un bloque pegable: un valor de relleno aqui
  # se pega entero y apaga las actualizaciones sin que nadie lo note. Mejor
  # parar y decir como sacarlo.
  echo
  echo "ERROR: no pude leer el versionCode del APK (ni por aapt ni por"
  echo "       output-metadata.json). El APK esta en $SALIDA/$NOMBRE, pero"
  echo "       NO imprimo el bloque del servidor: pegar un versionCode a medias"
  echo "       apaga las actualizaciones en silencio."
  echo
  echo "       Sacalo con:  unzip -p \"$SALIDA/$NOMBRE\" AndroidManifest.xml | strings | grep -A1 versionCode"
  echo "       o recompila y vuelve a correr esto."
  exit 1
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
echo

# ---------------------------------------------------------------------------
#  Y lo que hace que la gente se entere de que existe
# ---------------------------------------------------------------------------
#
# Subir el APK no actualiza a nadie: quien lo tiene instalado se queda en su
# version hasta que vuelve a la pagina por su cuenta, o sea casi nunca. Estas
# cuatro variables son las que hacen que la app lo pregunte sola.
#
# Se imprimen ya rellenas, con la huella de ESTE archivo, porque el paso donde
# se falla es justo ese: una huella copiada de la publicacion anterior hace
# que ninguna descarga cuadre y la actualizacion no funcione para nadie, sin
# ningun error visible en el servidor.
URL_PUBLICA="https://hackl4bs.com/wtfuck/$NOMBRE"
cat <<FIN
Para que la app avise sola, pega esto en .env.produccion del servidor
y reinicialo (el reinicio es lo que hace que los clientes se enteren:
reconectan y preguntan):

WTFUCK_APK_VERSION=$CODIGO
WTFUCK_APK_NOMBRE=$VERSION_VISIBLE
WTFUCK_APK_URL=$URL_PUBLICA
WTFUCK_APK_SHA256=$HUELLA

Comprueba la URL antes de pegarla: tiene que ser donde acabas de subir el
archivo, y por https. La app se niega a bajar nada por http.

Y OJO con el versionCode: si no subio respecto a la version anterior,
Android no la va a tomar como actualizacion. Se sube al compilar:
  ./gradlew :app:assembleRelease -PversionCode=N -PversionName=0.N.0
FIN
