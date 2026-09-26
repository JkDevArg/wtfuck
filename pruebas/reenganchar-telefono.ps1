<#
.SYNOPSIS
    Vuelve a enganchar un telefono fisico despues de que se cae la depuracion
    inalambrica.

.DESCRIPTION
    Android le asigna un puerto NUEVO a la depuracion inalambrica cada vez que
    se reactiva, asi que el `adb connect` de la vez pasada ya no sirve. Y el
    tunel de `adb reverse` vive dentro de la conexion ADB, no dentro del
    telefono: cuando la conexion se cae, el tunel se va con ella.

    Por eso la app dice "sin conexion" aunque el servidor este perfecto. No se
    cayo el servidor: dejo de existir el camino. La compilacion de depuracion
    apunta a http://127.0.0.1:8088, que en el telefono es EL TELEFONO, y sin el
    tunel ahi no hay nadie escuchando.

    Este script hace los tres pasos: encuentra el telefono por mDNS (no hace
    falta el numero, porque el emparejamiento si sobrevive), conecta, y repone
    los dos tuneles.

    El emparejamiento solo hay que hacerlo UNA vez por PC, a mano:
      Ajustes > Opciones de desarrollador > Depuracion inalambrica
        > Vincular dispositivo con codigo de vinculacion
      adb pair <ip>:<puerto-de-vinculacion> <codigo>

.PARAMETER Puerto
    El puerto del servidor de desarrollo en ESTA maquina. 8300 es el que levanta
    `arrancar-servidor.ps1`. El telefono siempre pide el 8088: eso esta fijo en
    BuildConfig y es el extremo del tunel, no el del servidor.

.PARAMETER Serial
    Saltarse el mDNS y usar este `ip:puerto` directamente. Para cuando el
    telefono esta en otra red o el descubrimiento no pasa por el router.

.EXAMPLE
    .\reenganchar-telefono.ps1
    .\reenganchar-telefono.ps1 -Puerto 8301
    .\reenganchar-telefono.ps1 -Serial 192.168.18.42:42493
#>
param(
    [int]$Puerto = 8300,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

function Paso($texto) { Write-Host "  $texto" -ForegroundColor DarkGray }
function Bien($texto) { Write-Host "  $texto" -ForegroundColor Green }
function Mal($texto)  { Write-Host "  $texto" -ForegroundColor Red }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Mal "No hay 'adb' en el PATH. Carga el entorno primero:  . G:\Android\env.ps1"
    exit 1
}

# --- 1. Encontrar el telefono -----------------------------------------
if ($Serial -eq "") {
    Paso "Buscando el telefono por mDNS..."
    # `_adb-tls-connect._tcp` solo lo anuncian los aparatos YA emparejados con
    # esta maquina, asi que lo que salga aqui es de confianza.
    $linea = (adb mdns services 2>&1 |
        Select-String -Pattern "_adb-tls-connect\._tcp" |
        Select-Object -First 1)

    if (-not $linea) {
        Mal "No aparecio ningun telefono."
        Write-Host ""
        Write-Host "  Revisa, en este orden:" -ForegroundColor Yellow
        Write-Host "   1. Que la Depuracion inalambrica este ENCENDIDA en el telefono."
        Write-Host "      Se apaga sola al reiniciar y a veces al dormirse."
        Write-Host "   2. Que el telefono y esta PC esten en la MISMA red wifi."
        Write-Host "      Una red de invitados aisla los aparatos entre si."
        Write-Host "   3. Que este emparejado con esta PC. Si nunca lo estuvo:"
        Write-Host "      adb pair <ip>:<puerto-de-vinculacion> <codigo>"
        Write-Host ""
        Write-Host "  Si el descubrimiento no pasa por tu router, pasale el"
        Write-Host "  numero a mano:  -Serial 192.168.1.50:42493"
        exit 1
    }

    if ($linea.Line -notmatch "(\d+\.\d+\.\d+\.\d+:\d+)") {
        Mal "El anuncio de mDNS no traia una direccion reconocible:"
        Write-Host "  $($linea.Line)"
        exit 1
    }
    $Serial = $Matches[1]
    Bien "Encontrado en $Serial"
}

# --- 2. Conectar -------------------------------------------------------
Paso "Conectando a $Serial ..."
$r = (adb connect $Serial 2>&1) -join " "
if ($r -notmatch "connected to") {
    Mal "No conecto: $r"
    Write-Host ""
    Write-Host "  Lo mas comun es que el puerto ya cambio: apaga y enciende la" -ForegroundColor Yellow
    Write-Host "  Depuracion inalambrica y vuelve a correr esto sin -Serial."
    exit 1
}
Bien $r.Trim()

# --- 3. Los tuneles ----------------------------------------------------
# 8088 es lo que pide la app (BuildConfig.SERVIDOR); $Puerto es donde escucha
# el servidor aqui. Los dos numeros son distintos a proposito y por eso el
# mapeo no es simetrico.
Paso "Reponiendo tuneles: 8088 -> $Puerto y 9000 -> 9000 ..."
adb -s $Serial reverse --remove-all 2>&1 | Out-Null
adb -s $Serial reverse tcp:8088 "tcp:$Puerto" | Out-Null
adb -s $Serial reverse tcp:9000 tcp:9000 | Out-Null
adb -s $Serial reverse --list | ForEach-Object { Paso $_ }

# --- 4. Comprobar de verdad -------------------------------------------
# Desde el TELEFONO y no desde aqui: que el servidor responda en esta maquina
# no dice nada sobre si el tunel quedo bien puesto, que es justo lo que fallaba.
Paso "Probando desde el telefono..."
$codigo = (adb -s $Serial shell "curl -s -m 8 -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/salud" 2>&1).Trim()

Write-Host ""
if ($codigo -eq "200") {
    Bien "Listo. El telefono ve el servidor (HTTP 200)."
    Write-Host ""
    Write-Host "  Si la app seguia abierta, reinicia para que reconecte ya:" -ForegroundColor DarkGray
    Write-Host "    adb -s $Serial shell am force-stop com.wtfuck.app" -ForegroundColor DarkGray
} else {
    Mal "El tunel quedo puesto pero el telefono no llega al servidor (respuesta: '$codigo')."
    Write-Host ""
    Write-Host "  Casi siempre es que el servidor no esta levantado en el $Puerto." -ForegroundColor Yellow
    Write-Host "    .\arrancar-servidor.ps1 -Puerto $Puerto -ConRedis"
    Write-Host ""
    Write-Host "  Si lo levantaste en otro puerto, decimelo:  -Puerto 8301"
    exit 1
}
