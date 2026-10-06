#!/usr/bin/env bash
# El puente de pruebas del modo cerca: dos emuladores "enlazados" por TCP en vez
# de Bluetooth, porque sus radios estan aisladas y nunca se ven.
#
# Solo sirve con la app en DEBUG: el archivo se escribe con `run-as`, que exige
# una app depurable, y `TransporteCerca.puente()` lo ignora si no es debug.
# Todo lo de arriba del enlace -saludo, sobres, acuses, el despacho sin red- es
# el codigo de verdad; lo unico simulado es la radio.
#
# Uso:
#   pruebas/puente-cerca.sh poner              crea el puente (5554 <-> 5556)
#   pruebas/puente-cerca.sh sin-servidor       ademas desenchufa el servidor
#   pruebas/puente-cerca.sh con-servidor       lo vuelve a enchufar
#   pruebas/puente-cerca.sh quitar             deshace todo
#
# Despues de "sin-servidor" hay que reiniciar la app: quitar el `reverse` no
# corta el WebSocket que ya estaba abierto.
#
# Puertos: A=emulator-5554 escucha en 7701 y llama al 7702; B=emulator-5556 al
# reves. Se cruzan por el host: A:7702 -> host:17702 -> B:7702, y simetrico.
set -euo pipefail
export MSYS2_ARG_CONV_EXCL="*"
A=${A:-emulator-5554}
B=${B:-emulator-5556}
PKG=com.wtfuck.app

case "${1:-}" in
  poner)
    adb -s "$A" shell "run-as $PKG sh -c 'echo 7701:7702 > files/puente-cerca.txt'"
    adb -s "$B" shell "run-as $PKG sh -c 'echo 7702:7701 > files/puente-cerca.txt'"
    adb -s "$A" reverse tcp:7702 tcp:17702 >/dev/null
    adb -s "$B" forward tcp:17702 tcp:7702 >/dev/null
    adb -s "$B" reverse tcp:7701 tcp:17701 >/dev/null
    adb -s "$A" forward tcp:17701 tcp:7701 >/dev/null
    echo "Puente puesto. Enciende el modo cerca en los dos."
    ;;
  sin-servidor)
    for d in "$A" "$B"; do
      adb -s "$d" reverse --remove tcp:8088 2>/dev/null || true
      adb -s "$d" reverse --remove tcp:9000 2>/dev/null || true
    done
    echo "Servidor desenchufado. Reinicia la app en los dos."
    ;;
  con-servidor)
    for d in "$A" "$B"; do
      adb -s "$d" reverse tcp:8088 tcp:8300 >/dev/null
      adb -s "$d" reverse tcp:9000 tcp:9000 >/dev/null
    done
    echo "Servidor enchufado."
    ;;
  quitar)
    for d in "$A" "$B"; do
      adb -s "$d" shell "run-as $PKG rm -f files/puente-cerca.txt" || true
    done
    adb -s "$A" reverse --remove tcp:7702 2>/dev/null || true
    adb -s "$B" reverse --remove tcp:7701 2>/dev/null || true
    adb -s "$B" forward --remove tcp:17702 2>/dev/null || true
    adb -s "$A" forward --remove tcp:17701 2>/dev/null || true
    echo "Puente quitado."
    ;;
  *)
    echo "uso: $0 {poner|sin-servidor|con-servidor|quitar}"
    exit 2
    ;;
esac
