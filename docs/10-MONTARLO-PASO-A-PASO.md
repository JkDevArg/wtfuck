# Montarlo paso a paso

Para una VPS que **ya tiene CloudPanel y Docker**, con otras cosas corriendo.

Al final: el servidor en internet y el APK instalado en tu teléfono.

Cada paso tiene una **comprobación**. Si la comprobación no da lo que dice,
no sigas al siguiente — el error se arrastra y el síntoma aparece tres pasos
después, donde no se parece a su causa.

---

## Antes de empezar

Dos comandos en la VPS, para saber con qué cuentas:

```bash
free -h && df -h / && docker ps --format "{{.Names}}\t{{.Ports}}"
```

Lo que hay que mirar:

- **RAM libre.** Vas a añadir un JVM, un Postgres, un Redis y un MinIO. Con
  menos de **3 GB libres** esto va a competir con tu CTF y el que pierda va a
  ser el que más memoria pida en ese momento, no el que menos importe.
- **Disco.** MinIO guarda los adjuntos. 20 GB libres para empezar.
- **Puertos.** Los tuyos son 8085 y 8090. Esto usa `8300` y `9000`, los dos
  **sólo en 127.0.0.1**. No chocan.

> Tu `ctfnew-db` y `ctfnew-cache` no publican puertos al exterior, así que
> tampoco hay conflicto ahí. Esto levanta **su propio** Postgres y su propio
> Redis: no se tocan con los del CTF y no comparten datos.

---

## Paso 1 — Los DNS

En el panel de tu dominio, dos registros **A** apuntando a la IP de la VPS:

```
apiwtf.hackl4bs.com     A    <IP-DE-TU-VPS>
mediawtf.hackl4bs.com   A    <IP-DE-TU-VPS>
```

**Comprobación** (desde tu PC, no desde la VPS):

```bash
nslookup apiwtf.hackl4bs.com
nslookup mediawtf.hackl4bs.com
```

Los dos tienen que devolver la IP de la VPS. Si no, **espera** — puede tardar
de minutos a un par de horas. Let's Encrypt necesita esto resuelto, y si lo
intentas antes te quedas sin intentos: hay un límite de 5 fallos por hora.

---

## Paso 2 — Subir el código

En la VPS:

```bash
cd /opt && git clone https://github.com/JkDevArg/wtfuck.git && cd wtfuck
```

**En `/opt` y no en el directorio del sitio de CloudPanel.** Esto no es una web
que nginx sirva desde disco: son contenedores. CloudPanel sólo hace de proxy
hacia un puerto local, y lo que haya en la carpeta del sitio le da igual.

Para actualizar más adelante, `git pull` y volver a levantar. Nada de copiar
carpetas.

**Comprobación:**

```bash
ls docker-compose.tras-proxy.yml Dockerfile despliegue/
```

Los tres tienen que existir.

---

## Paso 3 — Los secretos

```bash
cd /opt/wtfuck && bash despliegue/preparar.sh
```

Te pregunta cuatro cosas y genera el resto. **No escribas secretos a mano**:
una clave pensada por una persona tiene la entropía de una persona.

La cuarta es si el registro queda **abierto** o **por invitación**:

- **Abierto** — cualquiera que tenga el APK se crea una cuenta. Es lo que
  quieres si el servidor es público.
- **Invitación** — sólo se entra con un código. Tú entras igual sin ninguno
  —eres el propietario— y luego repartes códigos desde **Panel →
  Invitaciones**, dentro de la app.

Se puede cambiar después: `WTFUCK_REGISTRO` en `.env.produccion`, y reiniciar
el servidor. Lo que **no** se puede es cerrar un servidor por el que ya pasó
gente que no querías.

El script comprueba solo que no quedó nada pendiente, y lo dice. Si ya lo
ejecutaste con una versión anterior se niega a pisar lo que hay; bórralo y
empieza de nuevo:

```bash
rm -f .env.produccion && git pull && bash despliegue/preparar.sh
```

> **Si ya habías levantado los contenedores antes, borra también sus
> volúmenes.** Regenerar el `.env` crea contraseñas nuevas, y **Postgres graba
> la suya la primera vez que inicializa su volumen y nunca más**: cambiar
> `POSTGRES_PASSWORD` después no hace nada. El servidor entra en bucle de
> reinicio con
> `FATAL: password authentication failed for user "wtfuck"`.
>
> ```bash
> docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml down
> docker volume rm wtfuck_pgdata wtfuck_redisdatos
> ```
>
> Sólo mientras no haya cuentas creadas, claro. Después de eso ese comando
> borra la base de verdad.

---

## Paso 4 — Levantarlo

```bash
cd /opt/wtfuck && docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml up -d --build
```

**El `--env-file` no es opcional.** `env_file:` dentro del compose inyecta
variables en los contenedores, pero las `${VARIABLES}` del propio archivo las
resuelve Compose *antes*, y para eso sólo mira `.env` o lo que diga esa
bandera. Sin ella arrancaría un Postgres sin contraseña.

> Ya no puede pasar en silencio: cada variable lleva `:?`, así que Compose se
> **para** diciendo cuál falta en vez de seguir con cadenas vacías. Pero es
> más rápido ponerla que leer el error.

La primera vez compila el servidor dentro de Docker: **tarda entre 5 y 15
minutos** y parece colgado. No lo es.

> **Si el almacén queda `unhealthy`**, casi siempre es el permiso de su
> configuración. SeaweedFS corre como el usuario `seaweed` (uid 1000) y no
> puede leer un archivo de root:
>
> ```bash
> sudo chown 1000:1000 despliegue/s3.json
> docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml up -d
> ```
>
> El síntoma engaña: Compose dice "unhealthy" y apunta al chequeo de salud,
> pero el contenedor ni llega a levantar. El motivo real sale en
> `docker logs wtfuck-almacen-1`, en una línea que empieza por `F`.

**Comprobación:**

```bash
docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml ps
```

`wtfuck-db-1`, `wtfuck-redis-1` y `wtfuck-almacen-1` tienen que decir
**`healthy`**, y `wtfuck-servidor-1` **`Up`**. Después:

```bash
curl -s http://127.0.0.1:8300/salud
```

Si responde, el servidor está vivo. Si no:

```bash
docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml logs servidor --tail 50
```

En ese log tienen que aparecer las migraciones aplicándose. Es la señal de que
la base está bien conectada.

---

## Paso 5 — CloudPanel

Los dos se crean **desde CloudPanel**, no a mano. El panel gestiona el vhost y
la renovación del certificado; un vhost escrito a mano lo puede pisar en la
siguiente actualización, y el certificado habría que renovarlo tú.

**Sites → Add Site → Create a Reverse Proxy.** Ese tipo y no PHP, Node ni
Static: los otros esperan servir archivos de un directorio.

| Domain | Reverse Proxy URL |
|---|---|
| `apiwtf.hackl4bs.com` | `http://127.0.0.1:8300` |
| `mediawtf.hackl4bs.com` | `http://127.0.0.1:9000` |

En cada uno: **SSL/TLS → Let's Encrypt → Install**.

> CloudPanel te va a crear un directorio por sitio, algo como
> `/home/<usuario>/htdocs/apiwtf.hackl4bs.com`. **Va a quedarse vacío y está
> bien.** El código vive en `/opt/wtfuck` y nadie sirve archivos desde disco:
> nginx sólo reenvía a un puerto local. Si esperabas poner el proyecto ahí, no
> es ese el sitio.

**Comprobación** (desde tu PC):

```bash
curl -s https://apiwtf.hackl4bs.com/salud
```

Tiene que responder lo mismo que el `curl` de la VPS, ahora con HTTPS.

---

## Paso 6 — La configuración del proxy

Esto es lo que más cuesta si se salta, así que va aparte.

En CloudPanel, para **cada** sitio: **Vhost → Vhost Editor**.

> **No pegues un `location /` nuevo.** CloudPanel ya escribió uno al crear el
> sitio, y dos con la misma ruta hacen que nginx no arranque:
> `nginx: [emerg] duplicate location "/"`.
>
> Lo que hay que hacer es **abrir el `location /` que ya está y pegar las
> líneas dentro**, dejando su `proxy_pass` como está.

Las líneas están en
[`despliegue/nginx-tras-panel.conf`](../despliegue/nginx-tras-panel.conf), una
sección por sitio.

Después:

```bash
nginx -t && systemctl reload nginx
```

`nginx -t` tiene que decir `syntax is ok`. Si no, **no recargues**: nginx se
queda con la configuración anterior y tu CTF sigue funcionando. Si recargas con
error, se cae todo.

### Por qué cada línea

| Si falta | Qué ves |
|---|---|
| `Upgrade` / `Connection` | los mensajes se quedan "enviando" **para siempre**, sin ningún error en ningún log |
| `X-Forwarded-For` | todo se registra como `127.0.0.1`; cinco personas fallando la contraseña bloquean a la sexta |
| `Host $host` en **medios** | 403 en el almacén: el texto va bien y **sólo** fallan fotos, vídeos y audios |
| `client_max_body_size 0` | 413 en cualquier foto: el defecto de nginx es **1 MB** |

Los cuatro dan síntomas que no se parecen a su causa. Por eso están escritos.

---

## Paso 7 — El cortafuegos (las llamadas)

```bash
ufw allow 3478/tcp
ufw allow 3478/udp
ufw allow 49160:49200/udp
ufw status | grep -E "3478|49160"
```

**Y en el panel de tu proveedor** (Hetzner, DigitalOcean, Contabo…) si tiene
cortafuegos propio: **los mismos puertos, otra vez**. Tener uno abierto y el
otro cerrado es el caso más común.

Ese rango UDP es lo que se olvida siempre, y el síntoma es **una llamada que se
queda conectando para siempre sin ningún error**.

**Comprobación:**

```bash
docker logs wtfuck-coturn-1 --tail 20
```

Tiene que decir `Listener opened on : 3478` y **no** errores de bind.

---

## Paso 8 — El APK

**En tu PC:**

```bash
keytool -genkeypair -v -keystore wtfuck-publicacion.jks -alias publicacion -keyalg RSA -keysize 4096 -validity 10000
```

Guarda ese `.jks` y su contraseña **fuera de tu PC**. No se puede rotar: quien
lo pierda no puede volver a actualizar la app nunca.

Copia `keystore.properties.ejemplo` a `keystore.properties` y rellénalo.

**Hace falta el NDK** (solo para compilar, no en el servidor). Sin él, las
bibliotecas nativas salen sin strippear y el APK pesa 162 MB en vez de ~40. Se
instala una vez:

```bash
sdkmanager "ndk;28.2.13676358"
```

Luego:

```bash
cd /g/PROYECTOS/wtfuck
./gradlew :app:assembleRelease -Papi=apiwtf.hackl4bs.com -Pabi=arm64-v8a
```

`-Pabi=arm64-v8a` deja el APK en **~26 MB** (el 99% de los teléfonos actuales).
Sin `-Pabi` incluye también `armeabi-v7a` (teléfonos de 32 bits, pre-2018) y
pesa **~38 MB** — más grande, pero no deja fuera a nadie. Tú eliges según a
quién reparte.

**Comprobación:**

```bash
ls -la app/build/outputs/apk/release/
```

Tiene que salir `app-release.apk` (sin el `-unsigned`). Si sale con
`-unsigned`, `keystore.properties` no se está leyendo.

**Instalarlo:**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Paso 9 — Que funcione

Abre la app y **regístrate**. Es la prueba de fuego: registrarse toca el
servidor, la base, el vínculo de hardware y la generación de claves.

Después, en este orden — cada uno prueba una capa distinta:

| Prueba | Qué comprueba |
|---|---|
| Registrarte | servidor + base + claves |
| Mandarte un mensaje a ti mismo desde otro aparato | el WebSocket (paso 6) |
| Mandar una **foto** | el almacén y el `Host` del proxy |
| Una **llamada** entre dos aparatos | coturn (paso 7) |

Si el texto va y las fotos no, es el paso 6. Si todo va y la llamada se queda
conectando, es el paso 7. Esa correspondencia es a propósito.

---

## Lo que NO va a funcionar todavía, y no es un fallo

**Las notificaciones con la app cerrada.** Hace falta Firebase (`WTFUCK_FCM_*`).
Con la app abierta llega todo; cerrada, no suena. Ver
[`09-DESPLIEGUE.md`](09-DESPLIEGUE.md), "Push: encenderlo".

**El modo cerca** necesita dos teléfonos reales.

---

## Repartir el acceso

Si elegiste **invitación** en el paso 3, entra con tu usuario —el propietario
entra sin código— y ve a **Panel → Invitaciones**.

- **Un uso** para invitar a una persona concreta.
- **Varios usos** para un código que se pega en un canal.
- **Sin caducidad** sólo si sabes por qué; lo normal es una semana.

Revocar deja de dar cuentas nuevas y **no expulsa** a quien ya entró.

### Si no ves el panel de administración

Es el caso más probable, y no es un fallo de la app: tu cuenta se registró
**después** de arrancar el servidor. La promoción del propietario corría sólo
al arrancar, así que no encontró a nadie a quien promover.

```bash
docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml restart servidor
```

Al arrancar vuelve a mirar, ahora la cuenta existe, y la promueve. Cierra la
app del todo y vuelve a abrirla: el nivel se pide al servidor cada vez que se
abre el perfil.

Comprueba que el nombre coincide exactamente —minúsculas, sin espacios—:

```bash
grep WTFUCK_PROPIETARIO .env.produccion
```

> Desde la versión con el módulo de invitaciones el propietario queda con su
> nivel **al registrarse**, así que esto sólo hace falta en despliegues
> anteriores. No cuesta nada saberlo igual: es el mismo comando de cuando algo
> se rompe.

---

## Publicar una versión nueva

La app avisa sola cuando hay una versión más nueva, la descarga y deja la
instalación en **un toque**. No lo hace por magia: hay que decírselo al
servidor.

### Cómo numerar las versiones (semver)

El número tiene tres partes: **MAYOR.MENOR.PARCHE** (ej. `0.5.1`).

- **Mejora pequeña** (un arreglo, un pulido): sube el **último**. `0.5.0 → 0.5.1`.
- **Mejora grande** (una función nueva, algo que se nota mucho): sube el **del
  medio** y el último vuelve a 0. `0.5.3 → 0.6.0`.
- El primer número se queda en `0.x` hasta que la app se dé por terminada; ahí
  pasa a `1.0.0`.

El `versionCode` (el número interno que Android compara) lo maneja `lanzar.sh`
solo: siempre sube de a uno, sin importar el `versionName`.

### La forma fácil: un solo comando

Antes esto eran cinco pasos a mano (compilar, generar la página, subir el APK,
subir el `index.html`, editar el `.env` y reiniciar). Ahora hay un script que
lo hace todo:

```bash
bash despliegue/lanzar.sh 0.5.0
```

La primera vez, copia la config y pon el host de tu VPS:

```bash
cp despliegue/despliegue.conf.ejemplo despliegue/despliegue.conf
# edita despliegue/despliegue.conf: VPS_SSH="root@tu-servidor"
```

Qué hace `lanzar.sh`, en orden:

1. Le pregunta al servidor **qué versionCode está publicado** y usa el
   siguiente. No hay contador local que se desincronice: el servidor es la
   fuente de verdad.
2. Compila el APK de release firmado, con ese número y el nombre que le pasas.
3. Genera la página de descarga y la huella (llama a `publicar-apk.sh`).
4. **Te muestra qué va a subir y pide confirmación.** Nada sale sin tu ok.
5. Sube el APK y el `index.html`, actualiza las 4 variables `WTFUCK_APK_*` en el
   `.env.produccion` y reinicia el servidor.
6. Comprueba que el servidor ya anuncia la versión nueva.

Si además cambiaste **código del servidor** (algo en `server/`), añade
`--con-servidor` y hará `git pull` + reconstrucción del contenedor en el VPS:

```bash
bash despliegue/lanzar.sh 0.5.0 --con-servidor
```

**Antes de lanzar, escribe el changelog** en
[`NovedadesPantalla.kt`](../app/src/main/java/com/wtfuck/app/ui/NovedadesPantalla.kt):
el script no puede inventar qué cambió para el usuario, y te avisa si la versión
que lanzas no está en las Novedades.

> Necesita el NDK instalado (`sdkmanager "ndk;28.2.13676358"`) y `keystore.properties`
> con la clave de firma. Los dos ya están en tu equipo.

---

### La forma manual, paso a paso

Si prefieres controlar cada paso, o el script falla, esto es lo que hace por
dentro.

### 1 · Compila subiendo el número

```bash
./gradlew :app:assembleRelease -Papi=TU-DOMINIO -Pabi=arm64-v8a -PversionCode=2 -PversionName=0.2.0
```

`-Pversion` es lo único que Android mira para saber si un APK es más nuevo que
el instalado. **Tiene que subir en cada publicación y no puede bajar nunca.**
Si no sube, el instalador no actualiza — y no da ningún error que lo explique.

### 2 · Prepara la descarga

```bash
bash despliegue/publicar-apk.sh
```

Al final imprime el bloque de variables **ya relleno**, con la huella de ese
archivo exacto.

### 3 · Sube el APK y pega las variables

```bash
scp despliegue/descarga/* root@TU-VPS:/home/hackl4bs/htdocs/hackl4bs.com/wtfuck/
```

Pega las cuatro `WTFUCK_APK_*` en `.env.produccion` y reinicia:

```bash
cd /opt/wtfuck && docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml up -d --force-recreate servidor
```

**El reinicio es parte del mecanismo, no un efecto secundario.** Corta todos
los sockets, los teléfonos reconectan, y en esa reconexión preguntan por la
versión. Por eso no hace falta ningún push.

### Lo que no se puede hacer, y no es un fallo

**Nadie puede instalar una app en silencio en Android** salvo que el teléfono
esté administrado o la app venga preinstalada de fábrica. Android va a enseñar
su diálogo de confirmación siempre. Lo que se automatiza es enterarse y
descargar; decidir, no.

### Si quieres forzar a actualizar

`WTFUCK_APK_MINIMA=N` marca como obsoleta cualquier versión por debajo de `N`:
esas ven un aviso que no se puede cerrar. Es para cuando un cambio de protocolo
rompe a los clientes viejos de verdad — no para empujar una versión.

> Ponerla **mayor** que `WTFUCK_APK_VERSION` dejaría a todo el mundo fuera,
> incluida la que acabas de subir. El servidor la recorta solo, pero conviene
> no depender de eso.

### Comprobarlo

```bash
curl -s https://TU-DOMINIO/v1/version
```

Tiene que devolver el `versionCode` que subiste y una `url` con `https`. La app
se niega a descargar por http.

---

## Los cuatro fallos que dan el mismo síntoma

Cuatro veces durante el primer montaje de esto, Compose dijo `unhealthy` o el
servidor entró en bucle, y **ninguna vez la causa estaba donde apuntaba el
mensaje**. La regla que sale de ahí:

> `docker compose ps` dice **qué** contenedor falla. `docker logs <nombre>`
> dice **por qué**. El primero nunca basta.

| Lo que se ve | Dónde estaba de verdad |
|---|---|
| `almacen is unhealthy` | el chequeo no aceptaba el 403 del S3 |
| `almacen is unhealthy` | `/datos` sin permiso de escritura para el uid 1000 |
| `almacen is unhealthy` | `s3.json` en root:600, ilegible para el uid 1000 |
| `servidor Restarting (1)` | el compose no le pasaba `WTFUCK_DB_PASS` al servidor |
| `Almacen no disponible` | `WTFUCK_S3_URL` con el nombre viejo del servicio |

En los tres primeros el contenedor **ni llegaba a levantar**, así que ningún
chequeo de salud podía pasar. El motivo salía siempre en `docker logs`, en una
línea que empieza por `F`.

El cuarto merece una nota aparte: `password authentication failed` se lee como
"la contraseña no coincide" y lleva derecho a sospechar del volumen de
Postgres. Es la lectura razonable y la equivocada — nadie le estaba pasando la
variable al servidor, que caía a su clave de desarrollo. Borrar el volumen no
cambia nada.

Y el quinto no tumba nada: el servidor **arranca igual** y avisa de que los
adjuntos fallarán. Es deliberado — el chat de texto no debería caerse porque
el almacén esté mal.

## Si algo se rompe

```bash
docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml logs --tail 100
docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml restart servidor
```

Y para empezar de cero **borrando los datos** — cuidado, esto borra cuentas:

```bash
docker compose --env-file .env.produccion -f docker-compose.tras-proxy.yml down -v
```

Sin `-v` para y no borra nada: es lo que quieres el 90 % de las veces.
