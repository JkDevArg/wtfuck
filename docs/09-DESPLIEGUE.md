# Cómo se levanta esto

Dos partes: **en tu máquina** (que es lo que se usa a diario) y **en un
servidor de verdad** (que tiene tres cosas que en desarrollo se dejan pasar y
en producción no).

---

## En tu máquina

### Lo que hace falta

| Cosa | Versión | Por qué esa |
|---|---|---|
| JDK | 21 | El servidor y el toolchain de Gradle |
| Docker | cualquiera reciente | Postgres y MinIO |
| Android SDK | API 37 | `compileSdk = 37`. El emulador vive en `G:\Android` |
| Node | 20+ | Sólo para las suites de integración |

### Los cuatro pasos

```bash
docker compose up -d
```

Levanta Postgres en **5433** —no 5432, para no chocar con una instalación
local— y MinIO en 9000/9001. Los dos con `healthcheck`, así que `docker compose
ps` dice si están listos de verdad y no sólo arrancados.

```bash
./gradlew :server:installDist
```

No `run`: `installDist` deja un lanzador en
`server/build/install/server/bin/server`, que es lo que se usa para arrancarlo
fuera de Gradle. **Ojo:** este paso también empaqueta los recursos —las
migraciones `.sql` y la consola web—, así que tocar un `.sql` y arrancar sin
volver a ejecutarlo aplica el archivo viejo del jar. Esa confusión cuesta media
tarde.

```bash
WTFUCK_PROPIETARIO=tu_usuario \
WTFUCK_PEPPER_TELEFONO=pepper-de-pruebas-local-no-produccion \
WTFUCK_TURN_SECRETO=secreto-turn-de-pruebas \
  server/build/install/server/bin/server
```

Las migraciones se aplican solas al arrancar, en orden, una vez cada una. El log
dice cuál aplicó.

> Los dos secretos de arriba tienen **esos valores exactos** en desarrollo
> porque dos suites (`identidad` y `llamadas`) recalculan el HMAC por su cuenta
> y comparan. Con otros valores fallan ocho pruebas que no tienen nada roto.

```bash
./gradlew :app:installDebug
```

Y los puentes para que el emulador vea el servidor **y el almacén**:

```bash
.\pruebas\conectar-emuladores.ps1
```

Son dos, y olvidar el segundo no rompe lo mismo que olvidar el primero:

| Túnel | Para qué | Si falta |
|---|---|---|
| `tcp:8088 → tcp:8300` | el servidor | todo lo que necesite red sale vacío |
| `tcp:9000 → tcp:9000` | MinIO | el texto va bien y **sólo fallan fotos y vídeos**, con "No se pudo subir el archivo" |

Hay que repetirlos **cada vez que el emulador arranca**.

El segundo costó una sesión entera de diagnóstico: publicar una historia de
texto funcionaba y con foto no, lo que se lee como un fallo de la función y era
un túnel que faltaba.

**El del almacén tiene que ser 9000 a los dos lados.** La URL de subida va
firmada con SigV4 y la firma incluye el header `Host`: si el cliente llega por
otro puerto, el almacén responde 403. Por eso no se puede mapear a un puerto
libre cualquiera. Ver la nota de `Almacen.kt`.

### Si el puerto no abre y `netstat` sale vacío

En Windows, Hyper-V reserva rangos al azar en cuanto Docker Desktop arranca, y
8081-8180 cae ahí a menudo. El servidor lo diagnostica solo: lee
`netsh int ipv4 show excludedportrange` y lo dice en el log en lugar de soltar un
`BindException` sin contexto. Por eso el puerto por defecto es **8300**.

### Las pruebas

```bash
./gradlew :server:test :app:testDebugUnitTest
```

```bash
node pruebas/correr.mjs
```

138 de JUnit y 1175 de integración en 31 suites. El runner necesita el servidor
levantado y lo comprueba **una vez** antes de empezar: treinta y una suites
fallando por "connection refused" son treinta y una veces el mismo error y
ninguna pista. Para correr sólo algunas:

```bash
node pruebas/correr.mjs mensajes l1 contenido
```

Las suites van **en serie** y no en paralelo: varias comparten limitadores de
frecuencia por IP, y corriéndolas juntas se limitan entre ellas y fallan por
algo que no es lo que estaban probando.

Hablan con el servidor **real** contra la base real: son lentas y por eso
encuentran cosas que un mock no encontraría nunca. Dos ejemplos de este
proyecto: el límite configurable que se guardaba bien y no se aplicaba durante
30 s —lo encontró la prueba que pedía por la ruta, no la que llamaba a la
función—, y el id del mensaje que en un grupo de tres era distinto en cada
teléfono.

> Ojo con `installDist`: los **recursos** —las migraciones `.sql` y el HTML de
> la consola— sólo se copian en ese paso. Un cambio en un `.sql` sin volver a
> instalar no se aplica, y el síntoma es una migración que "no existe".

---

## Dónde montarlo

### En una máquina, con Docker

```bash
cp .env.produccion.ejemplo .env.produccion   # y rellenarlo
docker compose -f docker-compose.produccion.yml up -d
```

Eso levanta el servidor, Postgres, Redis, MinIO, coturn, Caddy con TLS
automático y un `pg_dump` diario. **Sólo Caddy y coturn tocan internet**: la
base, Redis y el almacén no publican ni un puerto.

> El compose de desarrollo saca Postgres en el 5433 y MinIO en el 9000 porque
> ahí es cómodo. Copiar eso a un servidor público es como se regalan las bases
> de datos: un Postgres escuchando en `0.0.0.0` lo encuentra un escáner en
> horas, no en meses.

### Detrás de un panel (CloudPanel, Plesk, aaPanel)

Si la máquina ya tiene un panel, **no hace falta una VPS limpia**. Lo único que
choca es el proxy: el panel ya es dueño del 80 y el 443, y
`docker-compose.produccion.yml` trae su propio Caddy que quiere esos mismos.
Dos cosas no pueden escuchar en el mismo puerto.

Se elige el del panel —es el que ya gestiona los certificados y el que el panel
sabe reconfigurar— y se usa el otro archivo:

```bash
docker compose -f docker-compose.tras-proxy.yml up -d
```

Ese compose es el mismo menos Caddy, y publica `servidor` y `minio` **sólo en
`127.0.0.1`**. El resto —base, Redis— no publica nada.

> **El `127.0.0.1:` del `ports:` no es decorativo.** Sin él, Docker abre el
> puerto en todas las interfaces *y* le escribe una regla a iptables que se
> salta el cortafuegos del anfitrión: `ufw status` dice que está cerrado y
> está abierto a internet. Es de los errores más comunes al juntar Docker con
> un panel, y no avisa.

Después, en el panel, **dos sitios de tipo "Reverse Proxy"**, cada uno con su
certificado:

| Sitio | Apunta a |
|---|---|
| `apiwtf.hackl4bs.com` | `http://127.0.0.1:8300` |
| `mediawtf.hackl4bs.com` | `http://127.0.0.1:9000` |

La configuración de nginx está en
[`despliegue/nginx-tras-panel.conf`](../despliegue/nginx-tras-panel.conf), lista
para pegar. No es una plantilla genérica: cada bloque tapa un fallo concreto, y
**tres dan síntomas que no se parecen a su causa**.

1. **Sin `Upgrade`/`Connection`**, el WebSocket no se establece nunca. Los
   mensajes se quedan "enviando" para siempre y no hay error en ningún log.
2. **Sin `X-Forwarded-For`**, todo queda registrado con `127.0.0.1` y el límite
   de intentos fallidos cuenta a todo el mundo como una sola persona: cinco
   personas equivocándose de contraseña bloquean a la sexta.
3. **Sin `proxy_set_header Host $host` en el dominio de medios**, la firma
   SigV4 deja de cuadrar y el almacén responde 403 a todo. El texto va
   perfecto y **sólo** fallan fotos, vídeos y audios. Parece un problema de la
   función de adjuntos y es una línea del proxy.
4. `client_max_body_size 0`, porque el defecto de nginx es **1 MB** y cualquier
   foto de un teléfono de hoy se rechaza con un 413 antes de llegar al almacén.

Y en el `.env.produccion`, las dos direcciones del almacén:

```
DOMINIO_API=apiwtf.hackl4bs.com
DOMINIO_MEDIA=mediawtf.hackl4bs.com
WTFUCK_S3_URL=http://almacen:9000
WTFUCK_S3_PUBLICO=https://mediawtf.hackl4bs.com
```

Son dos porque la firma SigV4 incluye el `Host`: el servidor alcanza el almacén
por la red interna de Docker y el teléfono por el dominio público, y **con una
sola dirección hay que elegir y las dos elecciones rompen algo**. Con la
interna, el teléfono recibe `http://almacen:9000/...` y no hay adjuntos. Con la
pública, el servidor no puede crear el bucket al arrancar hasta que el DNS y el
certificado existan — y el certificado no existe hasta el primer arranque.

### Lo que el panel NO cubre

**coturn**. Ningún panel usa el 3478 ni el rango de relevos, así que no hay
choque — pero tampoco los abre. Hay que hacerlo a mano en el cortafuegos:

```bash
ufw allow 3478/tcp
ufw allow 3478/udp
ufw allow 49160:49200/udp
```

Ese rango UDP es lo que se olvida, y el síntoma es **una llamada que se queda
conectando para siempre sin ningún error**. Si el proveedor tiene además un
cortafuegos propio en su panel —la mayoría lo tiene— hay que abrirlo en los dos
sitios.

### Qué tipo de sitio sirve, y cuál no

**coturn es lo que manda**, y se pasa por alto siempre. Necesita una IP
pública propia y un rango de **puertos UDP** para los relevos. Eso descarta de
entrada la mayoría de las plataformas de aplicaciones —App Service, Heroku,
Cloud Run, App Runner— que sólo enrutan HTTP.

Se puede partir: la API en una plataforma gestionada y coturn en una máquina
aparte. Pero entonces son dos sitios que mantener, dos facturas y dos formas
de fallar, para una app cuyo cuello de botella no es el cómputo. **Una VPS con
todo junto es la respuesta correcta para empezar**, y sigue siéndolo bastante
más arriba de lo que parece.

### Qué tamaño

Como punto de partida para las primeras centenas de personas: **4 vCPU, 8 GB
de RAM y 80 GB de disco**. El reparto real:

- El **servidor** es ligero: mueve sobres opacos y no los abre. Un JVM con
  1–2 GB va sobrado.
- **Postgres** es donde crece la cosa, pero menos de lo que se teme: el
  historial vive en los teléfonos, no aquí. La base guarda cuentas, grupos,
  metadatos y la cola de sobres pendientes, que se **borra al entregar**.
- **MinIO** es lo que come disco de verdad. Los adjuntos van cifrados y con
  vencimiento; el disco se dimensiona por lo que se retenga, no por usuarios.
- **coturn** es lo que come **tráfico**: una videollamada relevada son unos
  2 Mbit/s en cada sentido, y no todas se relevan —sólo las que ICE no puede
  conectar directo, típicamente entre un 10 % y un 20 % en redes móviles—.
  Ese es el número que hay que mirar al elegir proveedor, no la CPU.

**Y ahí está la trampa de costos.** Varios proveedores venden la máquina
barata y cobran el tráfico aparte, caro. Para esta app eso invierte el
cálculo: lo que se paga no es el servidor, son los relevos. Conviene mirar
cuánto tráfico incluye el plan **antes** que los vCPU.

### Dónde, geográficamente

Para usuarios en Perú, **la latencia de la señalización no importa mucho y la
del relevo sí**. Un mensaje tolera 200 ms sin que nadie lo note; una llamada
relevada por un TURN en Europa suma ida y vuelta al otro lado del Atlántico y
se nota entero.

Así que el orden es: **São Paulo o Miami** antes que Europa, y Europa antes
que Asia. Si el proveedor sólo tiene Europa, la API aguanta; lo que hay que
acercar es coturn.

### Lo que hace falta además de la máquina

1. **Un dominio**, con dos nombres apuntando a la IP: `api.` y `media.`.
   Caddy saca los certificados solo la primera vez que arranca.
2. **El cortafuegos abierto** en 80, 443 (TCP y UDP), 3478 y el rango
   `49160-49200` en **UDP**. Ese rango es lo que se olvida, y el síntoma es
   una llamada que se queda conectando para siempre sin ningún error.
3. **Firma del APK** y dónde distribuirlo. Google Play exige política de
   privacidad y un formulario de seguridad de datos; para una app cerrada, un
   APK firmado servido desde el propio dominio evita esa cola — a cambio de no
   tener actualizaciones automáticas.
4. **FCM**, si se quiere que suene con la app cerrada. Ver "Push: encenderlo".

### Lo que NO se puede escalar a lo bruto

Más instancias del servidor funcionan —para eso está Redis y el módulo N.4—
pero **Postgres y MinIO son uno solo**. Antes de necesitar réplicas de base
hacen falta muchos más usuarios de los que este proyecto va a ver, y la
respuesta entonces no es más instancias: es un Postgres gestionado.

---

## En un servidor de verdad

### Lo que cambia, y no es opcional

**1. `WTFUCK_PERMITIR_SOFTWARE_DEV=false`.**

En desarrollo vale `true` y es lo que permite usar emuladores: acepta
dispositivos sin enclave seguro. En producción, con `true`, cualquiera se salta
el vínculo de hardware —la regla de "una cuenta por dispositivo"— con un
emulador. Es el interruptor más importante de esta lista.

**2. `WTFUCK_PEPPER_TELEFONO`, con un valor de verdad.**

Sin esta variable el servidor **arranca igual** —en desarrollo hace falta que
arranque— pero deja una advertencia ruidosa en el log, y tiene razón: el pepper
por defecto está en el código, así que los hashes de teléfono de ese entorno se
pueden romper por fuerza bruta.

Y **no se puede rotar sin más**: cambiarlo invalida todos los hashes guardados,
o sea que nadie se encuentra por teléfono y nadie recupera su cuenta. Rotarlo es
una migración, no un cambio de variable.

**3. HTTPS por delante.**

El servidor habla HTTP en claro; el TLS lo termina un proxy (Caddy, nginx,
Traefik).

> Esto **ya no hay que tocarlo en el manifiesto**. Hasta el módulo AP la app
> llevaba `usesCleartextTraffic="true"`, global y también en release, y aquí
> decía que había que quitarlo a mano en la variante de release — o sea, un
> paso manual que protegía a todo el mundo y que bastaba con olvidar una vez.
> Ahora lo declara `res/xml/seguridad_de_red.xml`: claro prohibido salvo en el
> propio aparato y el emulador, y sólo anclas de confianza del sistema.
> Lo único que queda por hacer es apuntar `BuildConfig.SERVIDOR` al dominio
> real, que ya está en `app/build.gradle.kts`.

El WebSocket necesita que el proxy pase el `Upgrade`:

```nginx
location /v1/ws {
    proxy_pass http://127.0.0.1:8300;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    # El servidor manda ping cada 20 s; el cliente corta a los 40.
    # Un timeout de proxy más corto rompe la conexión sin motivo.
    proxy_read_timeout 120s;
}
location / {
    proxy_pass http://127.0.0.1:8300;
    # Sin esto, todas las sesiones y eventos de seguridad quedan
    # registrados con la IP del proxy. El servidor ya prefiere
    # `remoteAddress`, pero detrás de un proxy hace falta esto.
    proxy_set_header X-Forwarded-For $remote_addr;
}
```

### TURN, si las llamadas tienen que funcionar fuera de una LAN

Dos teléfonos detrás de NAT no se ven, y con NAT simétrico —lo normal en redes
móviles— ICE no consigue ruta directa. Hace falta **coturn** con el esquema de
credenciales REST:

```
# /etc/turnserver.conf
use-auth-secret
static-auth-secret=<el mismo que WTFUCK_TURN_SECRETO>
realm=wtfuck.com
```

El servidor emite usuario `<vencimiento>:<algo>` y clave
`base64(HMAC-SHA1(secreto, usuario))`, que caduca en horas. El secreto vive sólo
en el servidor y en coturn.

**El relevo no rompe el cifrado**: coturn mueve paquetes DTLS-SRTP que no puede
abrir. Un TURN comprometido aprende metadatos —que hubo una llamada y cuánto
tráfico—, no contenido. Y sin TURN configurado la llamada **no falla con un
error raro**: el contrato dice `hay: false` y sólo conecta si ICE logra ruta
directa.

### MinIO o S3

En desarrollo va MinIO por Docker. En producción sirve cualquier S3 compatible:
las tres variables `WTFUCK_S3_*` apuntan donde sea. El chat de texto **sigue
funcionando con el almacén caído** —se declara en el arranque con
`Almacen.disponible()`— porque los adjuntos son una función aparte y no el
camino crítico.

### Copias de seguridad

Lo que hay que respaldar y lo que no:

- **Postgres: sí.** Contiene cuentas, claves públicas, permisos, denuncias y
  metadatos.
- **MinIO: sí.** Los adjuntos cifrados.
- **Los mensajes: no están.** El servidor sólo guarda los **no entregados**
  (`sobre_pendiente`), y los borra al confirmarse la entrega. El historial vive
  en los teléfonos.

Eso último es una propiedad de diseño y hay que decirla en voz alta: **una copia
de la base no permite reconstruir las conversaciones.** Ni para un respaldo, ni
para un requerimiento judicial, ni para un administrador curioso.

### Escalar a más de una instancia

Las sesiones del WebSocket viven en la memoria de cada proceso. Con una
instancia eso es correcto y es lo más rápido que hay; con dos, **un mensaje para
alguien conectado a la instancia B no llega** si lo manda alguien conectado a la
A. Eso ya está resuelto (módulo N.4) y es **opcional**:

```bash
docker compose up -d redis
```

```bash
WTFUCK_REDIS_URL=redis://localhost:6380 \
WTFUCK_BUS_SECRETO=<el mismo en todas las instancias> \
WTFUCK_PUERTO=8300 \
  server/build/install/server/bin/server
```

**`WTFUCK_BUS_SECRETO` es obligatorio** si hay Redis, y el servidor **no arranca
el bus sin él**. La razón es concreta: por el bus viajan avisos que el cliente
**obedece** —`expulsado` lo saca de un grupo, `mensaje_retirado` le borra un
mensaje—, así que van firmados con HMAC-SHA256 y lo que no verifica se descarta.
Sin firma, cualquiera que alcance ese Redis inyecta eventos en un cliente
conectado; el id de un dispositivo no es secreto. Ver `pruebas/bus-inyeccion.mjs`,
que hace el ataque.

Se falla cerrado a propósito: un bus a medio autenticar es peor que ninguno,
porque se despliega creyendo que está protegido.

Con esa variable puesta en todas las instancias, cada una se suscribe a los
canales de sus propios sockets y publica lo que va dirigido a los de las otras.
**Sin la variable no pasa nada**: no abre ninguna conexión y se comporta como
siempre, que es lo correcto porque una sola instancia es el caso normal.

Tres cosas que conviene saber antes de encenderlo:

1. **Por Redis pasan los mismos bytes opacos que por Postgres**: el sobre ya
   cifrado. No se agrega un lugar donde el contenido esté en claro. Quien
   administre el Redis ve lo mismo que quien administre la base: nada.
2. **La presencia vence a los 90 segundos** y cada instancia renueva las marcas
   de sus sockets cada 30. Si una instancia muere, la gente que tenía aparece
   desconectada en minuto y medio, no para siempre. Ese límite es a propósito:
   una marca que no vence se queda en `true` cuando un proceso muere, y entonces
   la plataforma miente sobre el único dato que la gente usa para decidir si la
   están ignorando.
3. **El Redis no necesita volumen.** Lo que guarda son avisos en vuelo —que
   además están en la base— y marcas que vencen. Un Redis vacío después de un
   reinicio es correcto.
4. **El Redis va en red privada y con contraseña.** El HMAC impide la
   **inyección**, que es la mitad grave, pero no oculta los metadatos —quién
   recibió algo y cuándo— ni impide que quien escriba ahí borre claves de
   presencia. En el compose de desarrollo el puerto está atado a `127.0.0.1`.

Para comprobarlo hay una suite que levanta el caso real:

```bash
node pruebas/bus.mjs
```

Necesita dos instancias escuchando (8300 y 8301) con la misma base y el mismo
Redis. Si la segunda no responde, la suite se omite y lo dice: marcar verde un
cruce entre instancias que no se hizo sería el peor resultado.

Postgres y MinIO ya escalan por su cuenta. El fan-out ya es **en escritura**
—una copia por dispositivo destino al enviar—, así que leer es barato y no hay
que rehacerlo para crecer.

### Push: encenderlo

El camino está entero; falta una credencial. Se saca de un proyecto de Firebase:
una **cuenta de servicio** (`Configuración del proyecto → Cuentas de servicio →
Generar clave privada`) y los datos de la app Android.

```bash
# Lo secreto: solo el servidor firma con esto.
WTFUCK_FCM_PROYECTO=mi-proyecto
WTFUCK_FCM_EMAIL=...@mi-proyecto.iam.gserviceaccount.com
WTFUCK_FCM_CLAVE="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"

# Lo que el servidor le SIRVE al cliente. No es secreto: identifica el
# proyecto y no autoriza nada por si solo.
WTFUCK_FCM_APP_ID=1:123456789:android:abcdef
WTFUCK_FCM_API_KEY=AIza...
WTFUCK_FCM_REMITENTE=123456789
```

La clave privada acepta los `\n` literales tal como vienen en el JSON de
Google: no hay que convertirlos a saltos de línea de verdad.

**No hace falta recompilar la app.** La configuración va por
`GET /v1/push/config` y el cliente inicializa Firebase a mano, así que con las
variables puestas el próximo arranque de cada teléfono se registra solo. Por eso
tampoco hay un `google-services.json` en el repositorio.

Si las variables no están, el servidor lo dice **una vez** en el log de arranque
y sigue. No es un error: es una función apagada.

Para probar el camino sin hablar con Google hay un FCM de mentira:

```bash
node pruebas/stub-fcm.mjs
```

y se arranca el servidor con `WTFUCK_FCM_OAUTH=http://localhost:8399/token` y
`WTFUCK_FCM_ENDPOINT=http://localhost:8399/send`. Entonces `node pruebas/push.mjs`
comprueba el formato del aviso, incluido lo que importa: que **no lleve
contenido**.

### Actualizaciones: anunciar una versión

Cuatro variables, y `despliegue/publicar-apk.sh` las imprime ya rellenas
después de compilar:

```bash
WTFUCK_APK_VERSION=2                                    # el versionCode del APK
WTFUCK_APK_NOMBRE=0.2.0                                 # lo que ve la gente
WTFUCK_APK_URL=https://tu-dominio/wtfuck/wtfuck-2.apk   # https obligatorio
WTFUCK_APK_SHA256=<64 hex>                              # del archivo exacto
WTFUCK_APK_MINIMA=0                                     # opcional; ver abajo
WTFUCK_APK_NOTAS=""                                     # opcional
```

Sin `WTFUCK_APK_VERSION` el endpoint contesta apagado y la app no hace nada.

**El reinicio del servidor es parte del mecanismo.** Corta los sockets, los
teléfonos reconectan y en la reconexión preguntan por la versión. Por eso no
hace falta ningún push para avisar.

`WTFUCK_APK_MINIMA=N` marca obsoleta cualquier versión por debajo de `N`: esas
ven un aviso que no se puede cerrar. Es para un cambio de protocolo que rompe
a los clientes viejos de verdad, no para empujar una versión. El servidor la
recorta a la publicada, porque ponerla más alta dejaría a todo el mundo fuera
—incluida la que se acaba de subir— y eso no se puede diagnosticar desde el
teléfono.

**Android no deja instalar nada en silencio.** Ni esto ni ninguna otra app que
no venga preinstalada. Lo que se automatiza es enterarse y descargar; el
diálogo de confirmación lo pone el sistema y no se puede saltar. Ver
[`docs/evidencias/actualizacion/`](evidencias/actualizacion/).

```bash
curl -s https://TU-DOMINIO/v1/version
```

---

## Lo que todavía no está, para no llevarse una sorpresa

- **Push, hasta que se ponga la credencial.** El camino está construido y
  probado contra un FCM de mentira, pero sin las variables `WTFUCK_FCM_*` una
  llamada o un mensaje con la app **cerrada** no despiertan el proceso. Una
  llamada en curso sí sobrevive a salir de la app (servicio en primer plano),
  pero eso es otro caso. Ver "Push: encenderlo".
- **SFU.** Las llamadas de grupo son malla con techo de 4.
- **Cliente web de mensajería.** Hay consola de administración en `/consola`; un
  cliente que descifre exige libsignal en el navegador.
