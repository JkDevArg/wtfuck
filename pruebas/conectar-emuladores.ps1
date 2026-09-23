# Abre los tuneles que la app de debug necesita, en TODOS los emuladores.
#
# ## Por que hace falta esto
#
# La app de debug habla con `http://127.0.0.1:8088` y con el almacen en
# `http://127.0.0.1:9000`, y llega a los dos por `adb reverse`. NO se usa
# 10.0.2.2: el AVD de API 37 tiene eth0 y wlan0 en la misma subred y ese alias
# da SocketTimeout desde la app aunque `ping` desde el shell si pase.
#
# **Los tuneles se pierden al reiniciar el emulador**, y el sintoma no se
# parece a la causa:
#
#   - sin el 8088: las pantallas que dependen del servidor salen vacias, y
#     parece un problema de diseno;
#   - sin el 9000: el texto funciona y **solo fallan las fotos y los videos**,
#     con "No se pudo subir el archivo".
#
# El segundo caso costo una sesion entera: publicar una historia de texto
# andaba y con foto no, que se lee como un bug de la funcion y era un tunel.
#
# ## Por que el 9000 tiene que ser 9000 -> 9000 y no otro numero
#
# La URL de subida va **firmada** (SigV4) y la firma incluye el header Host.
# Reescribir el host despues de firmar la invalida: el almacen responde 403.
# Asi que el cliente tiene que llegar por exactamente el mismo host:puerto con
# el que se firmo, que por defecto es `127.0.0.1:9000`. Ver la nota de
# `Almacen.kt`. Mapear 9000 a otro puerto local rompe la firma.
#
# Uso:
#   .\pruebas\conectar-emuladores.ps1
#   .\pruebas\conectar-emuladores.ps1 -PuertoServidor 8301

param(
    [int]$PuertoServidor = 8300
)

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'G:\Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "No encuentro adb. Define ANDROID_HOME." }

$dispositivos = & $adb devices |
    Select-String -Pattern '^(\S+)\s+device$' |
    ForEach-Object { $_.Matches[0].Groups[1].Value }

if (-not $dispositivos) {
    Write-Warning 'No hay ningun emulador ni telefono conectado.'
    exit 1
}

foreach ($d in $dispositivos) {
    # 8088 -> el servidor. El puerto de dentro es fijo porque lo lleva
    # compilado el APK (`BuildConfig.SERVIDOR`); el de fuera se puede cambiar
    # para apuntar a la segunda instancia.
    & $adb -s $d reverse tcp:8088 "tcp:$PuertoServidor" | Out-Null
    # 9000 -> MinIO. Mismo numero a los dos lados, obligatorio: ver arriba.
    & $adb -s $d reverse tcp:9000 tcp:9000 | Out-Null

    $lista = (& $adb -s $d reverse --list) -join ' | '
    Write-Host "$d  ->  $lista"
}

Write-Host ''
Write-Host "Listo. Servidor en :$PuertoServidor, almacen en :9000."
Write-Host 'Si las fotos siguen sin subir, comproba que el contenedor de MinIO este arriba:'
Write-Host '  docker compose up -d minio'
