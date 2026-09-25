#!/bin/bash
# Modulo AF · Una llamada de grupo de punta a punta, en dos emuladores.
#
# Hace el recorrido entero -abrir el grupo, elegir a quien, llamar en video,
# contestar en el otro aparato- y deja una captura de cada paso.
#
# ## Por que un guion y no a mano
#
# El paso que importa -contestar- hay que darlo antes de que el timbre se
# agote, y a mano, saltando entre dos ventanas de emulador, no llega. Ademas
# los toques por coordenada son reproducibles y una sesion a mano no lo es.
#
# ## Por que bash y no PowerShell
#
# PowerShell convierte a texto la salida de un programa externo, asi que un PNG
# que pasa por una tuberia suya llega corrupto. Es el mismo problema que
# `adb shell cat` con binarios: hay que usar `exec-out` y redirigir en un shell
# que no reinterprete nada.
#
# ## Lo que hace falta antes
#
#   - Dos emuladores corriendo, con la app instalada y la sesion iniciada.
#   - El servidor de desarrollo arriba (`pruebas/arrancar-servidor.ps1`).
#   - Un coturn, o el medio no conecta. Ver docs/evidencias/llamadas-grupales.
#   - Un grupo llamado como diga GRUPO, abierto en la primera fila de chats.
#
# Las coordenadas son de una pantalla de 1080x2424. En otra resolucion hay que
# ajustarlas: se sacan con `adb shell uiautomator dump` y mirando los `bounds`.
set -e

SALIDA="${1:-./capturas-llamada-grupo}"
ADB="${ADB:-adb}"
A="${EMU_A:-emulator-5554}"   # quien llama
B="${EMU_B:-emulator-5556}"   # quien contesta
GRUPO="${GRUPO:-Equipo seguridad}"
mkdir -p "$SALIDA"

foto() { "$ADB" -s "$1" exec-out screencap -p > "$SALIDA/$2.png"; }
toque() { "$ADB" -s "$1" shell input tap "$2" "$3" >/dev/null; }

for d in "$A" "$B"; do
  "$ADB" -s "$d" shell am force-stop com.wtfuck.app >/dev/null
  "$ADB" -s "$d" shell monkey -p com.wtfuck.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
done
sleep 8

# 1. El grupo, con el boton de llamar que antes no estaba.
toque "$A" 371 1035; sleep 3
foto "$A" 1-boton-en-el-grupo

# 2. La hoja para elegir a quien.
toque "$A" 880 225; sleep 3
foto "$A" 2-hoja-vacia

# 3. Dos elegidos: tres de cuatro.
toque "$A" 100 1830; sleep 1
toque "$A" 100 1971; sleep 2
foto "$A" 3-dos-elegidos

# 4. Videollamada.
toque "$A" 788 2265; sleep 6
foto "$A" 4-llamando
foto "$B" 5-suena-en-el-otro

# 5. Contestar en el segundo aparato.
toque "$B" 731 2204; sleep 14
foto "$A" 6-conectada-quien-llamo
foto "$B" 7-conectada-quien-contesto

echo "--- estado en la base ---"
docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c \
  "SELECT l.estado || ' / ' || coalesce(l.fin_motivo,'-') FROM llamada l
     JOIN conversacion cv ON cv.id=l.conversacion_id
    WHERE cv.nombre='$GRUPO' ORDER BY l.iniciada_en DESC LIMIT 1"
docker exec wtfuck_db psql -U wtfuck -d wtfuck -t -A -c \
  "SELECT p.estado || ' ' || u.username FROM llamada_participante p
     JOIN usuario u ON u.id=p.usuario_id
    WHERE p.llamada_id=(SELECT l.id FROM llamada l
                          JOIN conversacion cv ON cv.id=l.conversacion_id
                         WHERE cv.nombre='$GRUPO' ORDER BY l.iniciada_en DESC LIMIT 1)"

echo
echo "Capturas en $SALIDA"
