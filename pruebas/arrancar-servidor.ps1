# Levanta el servidor de desarrollo con las variables minimas.
#
# Los valores de aqui son de DESARROLLO y estan a la vista a proposito: no
# protegen nada. En produccion salen del entorno y no de un archivo del repo.
# Ver docs/09-DESPLIEGUE.md.
#
# Antes de correr esto hace falta `./gradlew :server:installDist`. Ojo: los
# recursos -las migraciones .sql y el HTML de la consola- solo se copian en ese
# paso, asi que un cambio en un .sql sin volver a instalar no se aplica.
#
# Uso:
#   .\pruebas\arrancar-servidor.ps1                    una instancia, 8300
#   .\pruebas\arrancar-servidor.ps1 -ConRedis          con bus entre instancias
#   .\pruebas\arrancar-servidor.ps1 -Puerto 8301 -ConRedis    la segunda
#   .\pruebas\arrancar-servidor.ps1 -ConPushDeMentira  para probar el push
#   .\pruebas\arrancar-servidor.ps1 -Puerto 8310 -SinEmuladores
#                       con el defecto de produccion: rechaza SOFTWARE_DEV.
#                       Es la que pide `pruebas/nivel-hardware.mjs`.
#
# Para `pruebas/bus.mjs` hacen falta las DOS con -ConRedis y
# `docker compose up -d redis`.
param(
    [int]$Puerto = 8300,
    [switch]$ConRedis,
    [switch]$ConPushDeMentira,
    [switch]$SinEmuladores
)

$raiz = Split-Path -Parent $PSScriptRoot
$log = Join-Path $env:TEMP "wtfuck-servidor-$Puerto.log"

# ------------------------------------------------------------------
#  Secretos de verdad: salen de .env, que NO esta en el repo
# ------------------------------------------------------------------
#
# Los valores de este archivo son de desarrollo y estan a la vista porque no
# protegen nada. Una clave real de un proveedor SI protege algo -aunque sea
# poco, como la de Giphy, que es de lectura y con limite de tasa- y no puede
# vivir en un archivo versionado: una vez commiteada queda en el historial
# aunque despues se borre.
#
# Asi que se leen de `.env` en la raiz, que ya esta en .gitignore. Formato
# CLAVE=valor, una por linea. Hay una plantilla en `.env.ejemplo`.
#
# Una variable que YA este en el entorno gana sobre el archivo: asi el
# despliegue de produccion, que las inyecta de otra forma, no necesita un .env.
$dotenv = Join-Path $raiz '.env'
if (Test-Path $dotenv) {
    foreach ($linea in Get-Content $dotenv) {
        $t = $linea.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $nombre = $t.Substring(0, $i).Trim()
        $valor = $t.Substring($i + 1).Trim()
        # Sin pisar lo que ya venga del entorno.
        if (-not [Environment]::GetEnvironmentVariable($nombre)) {
            Set-Item -Path "env:$nombre" -Value $valor
        }
    }
    Write-Host "Cargado .env"
}

$env:WTFUCK_PUERTO = "$Puerto"
$env:WTFUCK_PROPIETARIO = 'joaquin'
# El TURN de desarrollo, en la direccion que el EMULADOR puede alcanzar.
#
# Estaba en 127.0.0.1, que desde un emulador de Android es el emulador mismo:
# una llamada entre dos emuladores nunca podia relevar y el medio se quedaba
# en ICE CHECKING para siempre. 10.0.2.2 es como el emulador ve la maquina
# anfitriona, que es donde corre el coturn.
#
# Sigue siendo un valor de desarrollo. En produccion sale del entorno.
if (-not $env:WTFUCK_TURN_URL) {
    $env:WTFUCK_TURN_URL = 'turn:10.0.2.2:3478?transport=udp'
}
$env:WTFUCK_TURN_SECRETO = 'secreto-turn-de-pruebas'
# La version web (`/web`). Si ya se armo la app (web/app/dist) se sirve esa;
# si no, la pagina minima con la que `pruebas/web.mjs` comprueba las cabeceras.
if (-not $env:WTFUCK_WEB_DIR) {
    $armada = Join-Path $raiz 'web\app\dist'
    $env:WTFUCK_WEB_DIR = if (Test-Path (Join-Path $armada 'index.html')) { $armada } else { Join-Path $raiz 'pruebas\web-de-prueba' }
}
# Emuladores. El servidor los RECHAZA por defecto desde el 2026-10-08 -antes
# los aceptaba si nadie decia nada, y un despliegue que se olvidara la variable
# quedaba abierto-. Aqui se abre a proposito y a la vista, porque sin esto los
# AVDs y todas las suites de `pruebas/` se quedan fuera: ninguna tiene enclave.
#
# `-SinEmuladores` la QUITA en vez de ponerla en false: lo que se quiere probar
# es el defecto, o sea el despliegue que se la olvido.
if ($SinEmuladores) {
    Remove-Item env:WTFUCK_PERMITIR_SOFTWARE_DEV -ErrorAction SilentlyContinue
} else {
    $env:WTFUCK_PERMITIR_SOFTWARE_DEV = 'true'
}
$env:WTFUCK_PEPPER_TELEFONO = 'pepper-de-pruebas-local-no-produccion'
# Modulo P. La beta de tipos de cuenta se resuelve por username y se lee UNA
# vez al arrancar, asi que la suite no puede meterse sola en la lista. Se
# anade aqui un username FIJO que `pruebas/cuentas.mjs` toma prestado, para
# poder probar los dos lados de la puerta: dentro y fuera.
$env:WTFUCK_CUENTAS_BETA = 'joaquin,beta_pruebas'

# Modulo N.4. Sin esto el servidor no abre ninguna conexion a Redis y se
# comporta como siempre: una sola instancia es el caso normal.
if ($ConRedis) {
    $env:WTFUCK_REDIS_URL = 'redis://localhost:6380'
    # Obligatorio: lo que viaja por el bus son avisos que el cliente OBEDECE,
    # asi que van firmados. Sin este secreto el bus no arranca a proposito -un
    # bus a medio autenticar es peor que ninguno, porque se despliega creyendo
    # que esta protegido-. Las dos instancias tienen que usar el MISMO.
    $env:WTFUCK_BUS_SECRETO = 'secreto-del-bus-de-pruebas-no-produccion'
} else {
    Remove-Item env:WTFUCK_REDIS_URL -ErrorAction SilentlyContinue
    Remove-Item env:WTFUCK_BUS_SECRETO -ErrorAction SilentlyContinue
}

# Modulo N.1. Apunta el push a `pruebas/stub-fcm.mjs` en vez de a Google, con
# una clave RSA generada al vuelo. Sirve para ver QUE viaja en el aviso; no
# sirve para que suene un telefono de verdad.
if ($ConPushDeMentira) {
    $pem = Join-Path $env:TEMP 'wtfuck-fcm-de-mentira.pem'
    if (-not (Test-Path $pem)) {
        & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $pem 2>$null
    }
    if (-not (Test-Path $pem)) {
        Write-Error "No se pudo generar la clave (falta openssl en el PATH)."
        exit 1
    }
    $env:WTFUCK_FCM_PROYECTO = 'proyecto-de-prueba'
    $env:WTFUCK_FCM_EMAIL = 'stub@prueba.iam.gserviceaccount.com'
    $env:WTFUCK_FCM_CLAVE = (Get-Content $pem -Raw)
    $env:WTFUCK_FCM_OAUTH = 'http://localhost:8399/token'
    $env:WTFUCK_FCM_ENDPOINT = 'http://localhost:8399/send'
    $env:WTFUCK_FCM_APP_ID = '1:123:android:abc'
    $env:WTFUCK_FCM_API_KEY = 'AIza-de-prueba'
    $env:WTFUCK_FCM_REMITENTE = '123456789'
    Write-Host "Push apuntado al stub. Levantalo con: node pruebas/stub-fcm.mjs"
} else {
    foreach ($v in @('WTFUCK_FCM_PROYECTO','WTFUCK_FCM_EMAIL','WTFUCK_FCM_CLAVE',
                     'WTFUCK_FCM_OAUTH','WTFUCK_FCM_ENDPOINT','WTFUCK_FCM_APP_ID',
                     'WTFUCK_FCM_API_KEY','WTFUCK_FCM_REMITENTE')) {
        Remove-Item "env:$v" -ErrorAction SilentlyContinue
    }
}

$exe = Join-Path $raiz 'server\build\install\server\bin\server.bat'
if (-not (Test-Path $exe)) {
    Write-Error "No existe $exe. Corre primero: ./gradlew :server:installDist"
    exit 1
}

Start-Process -FilePath $exe `
    -RedirectStandardOutput $log `
    -RedirectStandardError "$log.err" `
    -WindowStyle Hidden

Write-Host "Servidor arrancando en http://localhost:$Puerto · log: $log"
