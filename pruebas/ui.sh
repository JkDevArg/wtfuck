#!/usr/bin/env bash
# Manejo de la UI por adb, para las pruebas de dos emuladores.
#
# Por que existe: `uiautomator dump` devuelve un XML enorme y leerlo entero
# cuesta; aqui se filtra a "texto -> coordenadas" y se toca por texto, que es
# lo que se quiere decir. Tocar por pixel fijo se rompe con cualquier cambio
# de diseno y el fallo parece del codigo.
D=${D:-emulator-5554}
adbs() { adb -s "$D" "$@"; }

volcar() {
  adbs shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adbs shell cat /sdcard/ui.xml 2>/dev/null
}

# Lista lo que hay en pantalla: texto o content-desc, con su centro.
mirar() {
  volcar | tr '<' '\n' | grep -E 'text="[^"]|content-desc="[^"]' | \
  sed -E 's/.*text="([^"]*)".*content-desc="([^"]*)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1|\2|\3 \4 \5 \6/' | \
  awk -F'|' 'NF==3 && ($1!="" || $2!="") { split($3,b," "); printf "%-46s %-22s @ %d,%d\n", substr($1,1,45), substr($2,1,21), (b[1]+b[3])/2, (b[2]+b[4])/2 }'
}

# Toca el primer elemento cuyo texto o content-desc contenga $1.
tocar() {
  local xy
  xy=$(mirar | grep -iF -- "$1" | head -1 | grep -oE '@ [0-9]+,[0-9]+' | tr -d '@ ')
  if [ -z "$xy" ]; then echo "NO ENCONTRADO: $1" >&2; return 1; fi
  adbs shell input tap "${xy%,*}" "${xy#*,}"
  echo "toque '$1' en $xy"
}

escribir() { adbs shell input text "$(echo "$1" | sed 's/ /%s/g')"; }
foto() { adbs exec-out screencap -p > "$1"; echo "$1 $(stat -c%s "$1") bytes"; }

case "$1" in
  mirar) mirar ;;
  tocar) shift; tocar "$@" ;;
  escribir) shift; escribir "$@" ;;
  tap) adbs shell input tap "$2" "$3" ;;
  foto) foto "${2:-captura.png}" ;;
  *) echo "uso: D=emulator-XXXX $0 {mirar|tocar TEXTO|escribir TEXTO|tap X Y|foto ARCHIVO}" ;;
esac
