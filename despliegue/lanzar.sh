#!/usr/bin/env bash
#
# Publicar una version nueva del APK en UN comando.
#
#   bash despliegue/lanzar.sh 0.5.1                   # mejora pequena: sube el ultimo
#   bash despliegue/lanzar.sh 0.6.0 --con-servidor    # mejora grande o cambio del servidor
#
# ## ESTO ES BASH. No lo corras desde PowerShell.
#
# En PowerShell, un argumento que empieza por `-` y lleva `=` se PARTE en el
# primer punto. O sea que `-Papi=apiwtf.hackl4bs.com` le llega a Gradle como
# dos cosas, y la segunda —`.hackl4bs.com`— la toma por el nombre de una
# tarea:
#
#   Task '.hackl4bs.com' not found in root project 'wtfuck'
#
# El mensaje no se parece en nada a la causa, que es lo que lo hace costar una
# tarde. En Git Bash no pasa. Si hay que lanzar a mano desde PowerShell, cada
# `-P` va ENTRE COMILLAS:
#
#   .\gradlew.bat :app:assembleRelease "-Papi=apiwtf.hackl4bs.com" "-Pabi=arm64-v8a"
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

# Que esto sea BASH de verdad, y no PowerShell haciendo de bash.
#
# Si alguien lo corre desde PowerShell, los `-P` de Gradle se parten en el
# primer punto y el error que sale —Task '.hackl4bs.com' not found— no se
# parece en nada a la causa. Mejor pararlo aqui con el motivo escrito.
if [ -z "${BASH_VERSION:-}" ]; then
  echo "ERROR: esto tiene que correr en bash, no en otro interprete."
  exit 1
fi

# Y que sea GIT BASH, no WSL.
#
# Escribir `bash` en PowerShell resuelve C:\Windows\system32ash.exe, que
# es el lanzador de WSL. Y WSL tambien es bash, asi que la comprobacion de
# arriba lo deja pasar tan contento.
#
# El problema es que dentro de WSL el disco de Windows esta en /mnt/c, no en
# /c ni en C:/. Asi que la deteccion del JDK falla aunque la ruta sea
# correcta, y el error que sale -"no encuentro un JDK"- no menciona WSL por
# ningun lado. Y aunque se arreglara la ruta, seguiria mal: el keystore, el
# adb y el gradlew.bat son de Windows.
#
# Git Bash tiene /c montado; WSL no. Esa es la forma mas simple de
# distinguirlos sin depender de variables que pueden faltar.
if [ ! -d "/c/Windows" ] && [ -d "/mnt/c/Windows" ]; then
  echo "ERROR: esto se esta ejecutando en WSL, y tiene que ser Git Bash."
  echo ""
  echo "       Escribir 'bash' en PowerShell abre WSL -es el bash.exe de"
  echo "       system32-, y ahi el disco de Windows esta en /mnt/c: no se"
  echo "       encuentra ni el JDK, ni el keystore, ni adb."
  echo ""
  echo "       Abre Git Bash y corre el script desde ahi. O desde PowerShell,"
  echo "       llamando al bash de Git por su ruta completa:"
  echo ""
  echo "         & 'G:\laragon\bin\git\bin\bash.exe' despliegue/lanzar.sh <version>"
  exit 1
fi

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
command -v curl >/dev/null 2>&1 || { echo "ERROR: falta 'curl' para preguntar la version."; exit 1; }
# Se guarda la respuesta entera para distinguir "el servidor dice 0" (nuevo, y
# 0 es una respuesta valida -> siguiente 1) de "no hubo respuesta" (curl fallo o
# el servidor no contesta). En el segundo caso NO se adivina: publicar un
# versionCode inventado seria un downgrade que Android rechaza, en silencio.
RESP=$(curl -s -m 15 "https://$DOMINIO_API/v1/version" || true)
ACTUAL=$(printf '%s' "$RESP" | grep -oE '"versionCode"[: ]*[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -z "$ACTUAL" ]; then
  echo "ERROR: no pude leer la version de https://$DOMINIO_API/v1/version"
  echo "       respuesta: ${RESP:0:100}"
  echo "       Revisa que el servidor responda antes de publicar."
  exit 1
fi
CODE=$((ACTUAL + 1))

# El contador del servidor PUEDE IR HACIA ATRAS, y eso rompe la actualizacion
# en silencio.
#
# Paso de verdad: se limpiaron las WTFUCK_APK_* del .env y `/v1/version` volvio
# a decir 0. El siguiente lanzamiento publico un versionCode mas BAJO que el ya
# instalado en los telefonos, y `hayQueBajar` lo rechazo -correctamente: es la
# defensa contra un servidor que ofrece una version vieja-. Resultado: el
# servidor anunciaba la version nueva, la pagina de descarga estaba bien, y a
# nadie le salia el aviso. Ningun error en ningun sitio.
#
# Asi que el numero sale del MAXIMO entre lo que dice el servidor y una marca
# local que solo sube nunca baja. La marca esta versionada -no en .gitignore-
# para que sobreviva a clonar el repo en otra maquina, que era la objecion
# original a llevar un contador local.
MARCA=despliegue/ultimo-versioncode
ULTIMO=$(cat "$MARCA" 2>/dev/null || echo 0)
case "$ULTIMO" in ''|*[!0-9]*) ULTIMO=0 ;; esac
if [ "$CODE" -le "$ULTIMO" ]; then
  echo "    AVISO: el servidor dice $ACTUAL, pero ya se publico el $ULTIMO."
  echo "           Se usa $((ULTIMO + 1)): un numero mas bajo que el instalado"
  echo "           no se ofrece como actualizacion, y no avisaria de nada."
  CODE=$((ULTIMO + 1))
fi

echo "    publicado: $ACTUAL  ->  nuevo: $CODE ($VERSION_NOMBRE)"

# ------------------------------------------------------------------
#  2 y 3. Compilar y generar la pagina de descarga
# ------------------------------------------------------------------
# El lanzador de gradlew necesita Java para arrancar, ANTES de leer
# gradle.properties. En Git Bash a veces no hay ni JAVA_HOME ni `java` en el
# PATH -eso lo pone env.ps1, y solo en PowerShell-. El JDK ya esta declarado en
# gradle.properties (org.gradle.java.home); se toma de ahi para no repetir la
# ruta ni depender de como este el entorno.
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  # El JDK que fija gradle.properties es la primera opcion (es el que usa Gradle
  # para compilar). Se desescapa con `tr`: el `\:` del formato .properties, y de
  # paso cualquier \r si el archivo quedara con fin de linea de Windows. Se usa
  # `tr` y no la expansion ${//} porque esa se porta distinto segun la bash.
  # Solo se quita el backslash del escape `\:`. Nada de `\r` en el tr: el `tr`
  # de MSYS lo interpreta como la letra 'r' y se comeria las erres de la ruta
  # ("Program" -> "Pogam"). El archivo es LF; si algun dia trae CR, el fallback
  # de abajo encuentra el JDK igual.
  PROP=$(grep -E '^org\.gradle\.java\.home=' gradle.properties 2>/dev/null |
         head -1 | cut -d= -f2- | tr -d '\\' 2>/dev/null)
  # Se prueba esa y, si no esta, ubicaciones habituales de JDK en Windows. Al
  # lanzador de gradlew le sirve CUALQUIER JDK para arrancar; la version exacta
  # de compilacion la sigue mandando gradle.properties. Con `-e` (existe) y no
  # `-x` (ejecutable): el bit de ejecucion bajo "Program Files" no es fiable en
  # MSYS, pero si el java.exe esta, corre.
  JH=""
  for c in "$PROP" \
           "/c/Program Files/Java/"jdk-21* \
           "/c/Program Files/Java/"jdk* \
           "/c/Program Files/Microsoft/"jdk* \
           "/c/Program Files/Eclipse Adoptium/"jdk* \
           "/c/Program Files/Zulu/"zulu* \
           "/c/Program Files/Android/Android Studio/jbr"; do
    if [ -n "$c" ] && { [ -e "$c/bin/java.exe" ] || [ -e "$c/bin/java" ]; }; then
      JH="$c"; break
    fi
  done
  if [ -n "$JH" ]; then
    export JAVA_HOME="$JH"
    echo "    (Java no estaba en el entorno; usando: $JH)"
  else
    echo "ERROR: no encuentro un JDK. Define JAVA_HOME, o instala JDK 21, o"
    echo "       corrige org.gradle.java.home en gradle.properties."
    echo "       (probe: '$PROP' y las rutas tipicas bajo C:/Program Files)"
    exit 1
  fi
fi

echo "==> Compilando el APK de release..."
./gradlew :app:assembleRelease \
  "-Papi=$DOMINIO_API" "-PversionCode=$CODE" "-PversionName=$VERSION_NOMBRE" "-Pabi=$ABIS" -q

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
# Como se eleva a root en el VPS. Vacio si entras como root; "sudo" si entras
# como un usuario normal (ubuntu) que necesita sudo para docker y para escribir
# en /opt y en el docroot. Un `sudo su` no sirve aqui -abre un shell
# interactivo- ; lo que hace falta es correr los comandos CON sudo.
SUDO="${SUDO:-}"

RECREA="up -d --force-recreate servidor"
[ "$CON_SERVIDOR" = 1 ] && RECREA="up -d --build servidor"

echo
echo "Voy a SUBIR al servidor y REINICIARLO (conectando como $VPS_SSH${SUDO:+, con $SUDO}):"
echo "  APK + index.html  ->  $DOCROOT/"
echo "  .env WTFUCK_APK_VERSION=$CODE, NOMBRE=$VERSION_NOMBRE, URL, SHA256"
[ "$CON_SERVIDOR" = 1 ] && echo "  y RECONSTRUIR el servidor (--con-servidor)" \
                        || echo "  y reiniciar el servidor (sin reconstruir)"
read -rp "Continuar? [s/N] " r
[ "$r" = "s" ] || [ "$r" = "S" ] || { echo "Cancelado. El APK quedo en despliegue/descarga/."; exit 0; }

# Los archivos van primero a /tmp, que `ubuntu` SI puede escribir; de ahi los
# mueve root a su sitio. Subirlos directo al docroot fallaria: esa carpeta es
# de root o del usuario del sitio, no de quien entra por SSH.
echo "==> Subiendo APK y pagina a /tmp del servidor..."
scp "$APK" despliegue/descarga/index.html "$VPS_SSH:/tmp/"

# El resto se arma como un script y se corre de una sola vez bajo sudo. Asi se
# evita pelear con las comillas anidadas de meter sudo en cada linea, y todo lo
# que toca root -mover al docroot, editar el .env, reiniciar- pasa junto.
#
# Ojo: el script remoto corre como ROOT, asi que `~` seria /root; por eso los
# archivos se referencian desde /tmp y no desde el home.
echo "==> Aplicando en el servidor (mover, .env, reiniciar)..."
REMOTO=$(cat <<REMOTE
set -e
mv /tmp/$NOMBRE_ARCHIVO /tmp/index.html "$DOCROOT"/
chmod 644 "$DOCROOT/$NOMBRE_ARCHIVO" "$DOCROOT/index.html"
# El dueno del docroot, para que el servidor web lo pueda leer como el resto.
chown --reference="$DOCROOT" "$DOCROOT/$NOMBRE_ARCHIVO" "$DOCROOT/index.html" 2>/dev/null || true
cd "$OPT_DIR"
$([ "$CON_SERVIDOR" = 1 ] && echo 'git pull')
# Se borran las WTFUCK_APK_* viejas y se anaden las nuevas: ni duplicados ni
# mezcla con una publicacion anterior. WTFUCK_DESCARGA_URL va con ellas: es
# a donde manda el "Descargalo aqui" de la pagina del enlace de contacto
# (/c/<codigo>, ver EnlaceContacto), que es la pagina de descarga.
sed -i '/^WTFUCK_APK_/d; /^WTFUCK_DESCARGA_URL=/d' .env.produccion
cat >> .env.produccion <<VARS
WTFUCK_APK_VERSION=$CODE
WTFUCK_APK_NOMBRE=$VERSION_NOMBRE
WTFUCK_APK_URL=$URL
WTFUCK_APK_SHA256=$SHA
WTFUCK_DESCARGA_URL=$DOMINIO_DESCARGA/
VARS
docker compose --env-file .env.produccion -f "$COMPOSE" $RECREA
REMOTE
)
# Se manda por la entrada estandar y se corre con bash -s bajo sudo: nada queda
# escrito en el disco del servidor.
printf '%s\n' "$REMOTO" | ssh "$VPS_SSH" "$SUDO bash -s"

# ------------------------------------------------------------------
#  6. Comprobar
# ------------------------------------------------------------------
echo "==> Comprobando que el servidor anuncia la version nueva..."
sleep 4
PUB=$(curl -s -m 15 "https://$DOMINIO_API/v1/version" |
  grep -oE '"versionCode"[: ]*[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
if [ "$PUB" = "$CODE" ]; then
  # La marca se escribe SOLO al confirmar que el servidor la anuncia. Si se
  # escribiera antes, un fallo a mitad dejaria el contador adelantado y se
  # perderia un numero en cada intento fallido.
  printf '%s
' "$CODE" > "$MARCA"
  echo "LISTO. Publicada la $VERSION_NOMBRE (versionCode $CODE)."
  echo "Los telefonos la veran al abrir la app o al reconectar."
else
  echo "OJO: el servidor anuncia '$PUB', esperaba '$CODE'."
  echo "     Revisa el .env.produccion y el reinicio en el VPS."
  exit 1
fi
