# Módulo BF · Cert pinning (fijado de certificado)

Atar la app a la clave pública de *tu* servidor, para que un MITM no funcione
ni aunque el teléfono confíe en una CA falsa.

---

## Qué protege

El canal sensible: la **API** y el **WebSocket** (donde viajan el token de
sesión y el routing de los mensajes). Aunque alguien logre que el teléfono
confíe en una CA falsa —un proxy corporativo, un equipo intervenido, una CA
comprometida—, la conexión se corta si el certificado no es el tuyo.

No pinea el host de **descarga del APK** ni el de **medios**: el APK se verifica
por su SHA-256 + firma, y los medios van cifrados E2EE (un MITM ahí ve
ciphertext).

## Por qué es OPT-IN (apagado por defecto)

El pinning es un arma de doble filo. Si el certificado del servidor cambia a una
clave que no está fijada y la app no se actualizó, **la app deja de conectar
para todos** hasta que instalen un APK nuevo. En una app que se reparte fuera de
una tienda, eso es un ladrillo remoto autoinfligido.

Por eso `PIN_HASHES` viene **vacío** y no hace nada salvo que se active a
propósito al compilar:

```bash
./gradlew :app:assembleRelease -Papi=apiwtf.hackl4bs.com \
  -Ppin=<pin-leaf>,<pin-de-respaldo>
```

Sin `-Ppin`, validación de CA normal, como siempre.

## Cómo no dispararse en el pie

1. **Siempre un pin de RESPALDO** además del principal. El respaldo es la clave
   de un certificado que aún no usas pero controlas (o el intermedio/raíz de tu
   CA). Si tienes que rotar la clave del servidor, emites con la de respaldo y
   nadie queda fuera.
2. **Renueva reusando la clave** (`certbot --reuse-key` / `certbot renew
   --reuse-key`). Así la renovación de Let's Encrypt cada 90 días **no cambia la
   clave** y el pin sigue valiendo.
3. El pin es del **SPKI** (la clave pública), no del archivo del certificado:
   por eso sobrevive a una renovación que reúsa la clave.

## Cómo sacar los pines

```bash
echo | openssl s_client -servername TU-DOMINIO -connect TU-DOMINIO:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
```

Para el de respaldo, lo mismo sobre el certificado intermedio o raíz (con
`-showcerts`), o sobre una clave de respaldo que generes y guardes.

## ⚠️ Aviso sobre el certificado actual

Al medir `apiwtf.hackl4bs.com` la cadena salió con nombres **no estándar** de
Let's Encrypt (intermedio "Let's Encrypt YR2", raíz "ISRG Root YR"; los de
producción son R10/R11/E5/E6 y "ISRG Root X1"). Eso apunta a un certificado de
**staging** o no definitivo. **Pinear un cert de staging garantiza el brick**
cuando pase a producción. Antes de activar el pinning: confirma que el
certificado servido es el de producción, y recalcula los pines.

Pines medidos (a la fecha; verifícalos antes de usar):

```
Leaf (apiwtf):     KNX/BKM3mELOZcbebFrbu52j2tVEuKQmhAqDmt+Sphk=
Intermedio (YR2):  nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=
Raíz (Root YR):    fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=
```

Recomendación: pin **leaf** (con `--reuse-key`) + pin **intermedio o raíz** como
respaldo, y expiración/plan de actualización antes de que caduque el cert.

## Si algo se pinea mal y la app no conecta

Es el escenario que hay que tener pensado: publica de inmediato un APK **sin**
`-Ppin` (o con los pines correctos) por el sistema de actualización. Como el
pinning es opt-in, un APK compilado sin `-Ppin` vuelve a la validación normal y
desatasca a todos.

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`Pinning.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Pinning.kt) | Construye el `CertificatePinner` desde `BuildConfig.PIN_HASHES` |
| [`Api.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Api.kt) | Aplica el pinning al cliente de la API |
| [`Socket.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Socket.kt) | Aplica el pinning al WebSocket |
| [`build.gradle.kts`](../../../app/build.gradle.kts) | `PIN_HASHES` desde `-Ppin` (vacío por defecto) |

## Lo que NO está verificado

El comportamiento **on-device** del pinning (que conecte con el pin correcto y
que rechace un cert distinto) **no se ejecutó** esta sesión: el entorno estaba
caído y, además, requiere un cert de producción definitivo. Lo verificable sí:
el pin fluye a `BuildConfig` con `-Ppin`, la app compila con y sin pinning, y la
lógica es el `CertificatePinner` estándar de OkHttp. Queda pendiente probarlo
contra el servidor real de producción antes de activarlo para usuarios.
