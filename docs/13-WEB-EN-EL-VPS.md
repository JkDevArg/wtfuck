# La versión web en el VPS, en `webfck.hackl4bs.com`

Guía para subir lo nuevo (W1 a W5) a un servidor que ya corre la API en
`apiwtf.hackl4bs.com` detrás de CloudPanel, y dejar la web en su propio
subdominio. Los comandos los corres tú; ninguno toca nada sin que lo pegues.

## TL;DR

1. **DNS:** registro `A` de `webfck` → la IP del VPS.
2. **VPS:** `git pull`, nuevas variables en `.env.produccion` y reconstruir el
   servidor. Las migraciones (V49 a V53) corren solas al arrancar.
3. **Tu PC:** subir el paquete de la web (`despliegue/descarga/wtfuck-web-….tar.gz`)
   y descomprimirlo en `/opt/wtfuck/web-publicada`.
4. **CloudPanel:** un sitio nuevo `webfck.hackl4bs.com` de tipo Reverse Proxy
   al **mismo** servidor (`http://127.0.0.1:8300`). Lleva las mismas líneas que
   la API y una más, que manda `/` a `/web/`.
5. **Comprobar** con los `curl` del final.

## Por qué el subdominio apunta al MISMO servidor

La web no es un sitio aparte: la sirve el propio servidor de wtfuck en
`/web/`, y la página le habla a la API en `/v1/...` **del mismo origen**. Así
no hace falta abrir CORS, y la CSP queda en `connect-src 'self'`.

Si `webfck.hackl4bs.com` apuntara a otra cosa, la página no podría hablar con
la API. Apuntando al mismo `127.0.0.1:8300`, en `webfck.hackl4bs.com`:

- `/web/` es la página;
- `/v1/...` es la API, la misma que usa la app en `apiwtf`;
- `/` redirige a `/web/`.

Las cuentas, los mensajes y los avisos son los mismos que en `apiwtf`: es un
nombre más para el mismo servidor. `apiwtf.hackl4bs.com/web/` también va a
funcionar; el subdominio es la dirección "bonita" para compartir.

## 1. DNS

En el panel de tu dominio, un registro nuevo:

| Tipo | Nombre | Valor |
|---|---|---|
| A | `webfck` | la IP del VPS (la misma de `apiwtf`) |

Espera a que resuelva antes del paso 4 (Let's Encrypt lo necesita):

```bash
nslookup webfck.hackl4bs.com
```

## 2. En el VPS: código, variables y servidor

```bash
cd /opt/wtfuck && git pull
```

Agrega a `/opt/wtfuck/.env.produccion` lo nuevo. Los valores secretos los
pones tú; nada de esto va al repo.

```bash
# Avisos con el navegador cerrado (Web Push). Se generan en TU PC con:
#   node despliegue/generar-vapid.mjs
WTFUCK_VAPID_PUBLICA=...
WTFUCK_VAPID_PRIVADA=...
WTFUCK_VAPID_CONTACTO=mailto:tu-correo@ejemplo.com

# Registro desde la web: el servicio de correo que manda el codigo de 6
# digitos. SIN ESTO EL REGISTRO WEB QUEDA CERRADO (vincular sigue andando).
# Plantillas de Resend y Brevo en docs/09-DESPLIEGUE.md, "Correo".
WTFUCK_CORREO_URL=https://api.resend.com/emails
WTFUCK_CORREO_CABECERA=Authorization
WTFUCK_CORREO_TOKEN=Bearer re_xxxxxxxx
WTFUCK_CORREO_REMITENTE=wtfuck <no-responder@hackl4bs.com>
WTFUCK_CORREO_CUERPO={"from":"{remitente}","to":["{destino}"],"subject":"{asunto}","text":"{texto}"}

# Key Attestation de Android (W5e). "registrar" mide sin rechazar a nadie.
# La huella es la del certificado con que firmas el APK; ya esta calculada
# del APK publicado (apksigner verify --print-certs):
WTFUCK_ATESTACION=registrar
WTFUCK_ATESTACION_FIRMAS=f1ab48bc657191974f98ec228ea613de196420b380fa752ce7cab9353e38cd23
```

Reconstruye el servidor:

```bash
cd /opt/wtfuck && sudo docker compose -f docker-compose.tras-proxy.yml --env-file .env.produccion up -d --build servidor
```

Mira el arranque. Tienen que aparecer las migraciones nuevas y ningún error:

```bash
sudo docker compose -f docker-compose.tras-proxy.yml logs --tail=80 servidor
```

> Si el `.env` de la clave VAPID o del correo tiene un error de copia, el
> servidor arranca igual y lo dice en esa bitácora ("Claves VAPID
> inservibles", "Sin WTFUCK_CORREO_URL"). No es una caída: es una función
> apagada.

## 3. Subir la web (desde tu PC, en Git Bash)

El paquete ya armado es `despliegue/descarga/wtfuck-web-20261008-1200.tar.gz`
(con W5: registro, contactos y PWA). Si cambias algo de la web, se vuelve a
armar con `bash despliegue/publicar-web.sh`.

```bash
scp despliegue/descarga/wtfuck-web-20261008-1200.tar.gz ubuntu@144.217.161.94:/tmp/
```

En el VPS:

```bash
sudo mkdir -p /opt/wtfuck/web-publicada && sudo find /opt/wtfuck/web-publicada -mindepth 1 -delete && sudo tar -xzf /tmp/wtfuck-web-20261008-1200.tar.gz -C /opt/wtfuck/web-publicada
```

El servidor la sirve sin reiniciar: lee la carpeta en cada pedido. La carpeta
ya está montada en el contenedor (`./web-publicada:/app/web:ro` en
`docker-compose.tras-proxy.yml`).

## 4. CloudPanel: el sitio `webfck.hackl4bs.com`

1. **Sites → Add Site → Create a Reverse Proxy.**
   - Domain: `webfck.hackl4bs.com`
   - Reverse Proxy Url: `http://127.0.0.1:8300` (el MISMO que `apiwtf`)
2. **SSL/TLS → Actions → New Let's Encrypt Certificate.**
3. **Vhost Editor.** Hay dos cambios.

**a) DENTRO del `location /` que creó el panel,** junto a su `proxy_pass`,
las mismas líneas que el sitio de la API (`despliegue/nginx-tras-panel.conf`,
"SITIO 1"). La web usa el mismo WebSocket:

```nginx
    proxy_http_version 1.1;
    proxy_set_header Upgrade    $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout  120s;
    proxy_send_timeout  120s;
```

**b) FUERA de ese bloque, como un `location` nuevo,** la redirección de la
raíz. Es `location = /` (exacto), así que no choca con el `location /` del
panel:

```nginx
    location = / {
        return 302 /web/;
    }
```

Antes de recargar, siempre:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 5. Comprobar

```bash
curl -sI https://webfck.hackl4bs.com/ | grep -i location
```

Tiene que decir `location: /web/`.

```bash
curl -sI https://webfck.hackl4bs.com/web/ | grep -i content-security-policy
```

La CSP de la web (`default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; …`).

```bash
curl -s https://webfck.hackl4bs.com/web/HUELLAS.txt | grep wasm
```

La huella del `.wasm` tiene que ser la que imprimió `publicar-web.sh`
(`992a65e0…d5d`).

```bash
curl -s https://webfck.hackl4bs.com/v1/registro/modo
```

`{"requiereInvitacion":…,"registroWeb":true}`. Si dice `registroWeb:false`,
falta el correo (paso 2).

```bash
curl -sI https://webfck.hackl4bs.com/web/manifest.webmanifest | grep -i content-type
```

`application/manifest+json`: la PWA instalable.

Y por último el permiso CORS del almacén, que en producción nunca se probó.
Sin él, el texto anda y fallan las fotos y las notas de voz:

```bash
curl -si -X OPTIONS https://mediawtf.hackl4bs.com/x -H "Origin: https://webfck.hackl4bs.com" -H "Access-Control-Request-Method: PUT" | grep -i access-control
```

Tiene que traer `Access-Control-Allow-Origin`.

## 6. Probarlo

- **Desde una PC:** abre `https://webfck.hackl4bs.com`. Con "Ya tengo la app",
  vincúlala con el código que genera el teléfono.
- **Desde un iPhone:**
  1. Quien ya tiene cuenta abre la web (vinculada a su teléfono, o una cuenta
     web) y toca **"Invitar a alguien"**. Sale el enlace
     `https://webfck.hackl4bs.com/web/?invitacion=…`. La app Android todavía no
     tiene ese botón: por ahora las invitaciones se crean desde la web.
  2. En el iPhone, ábrelo en Safari, **Compartir → Agregar a pantalla de
     inicio**, y crea la cuenta desde el ícono.
- **Avisos con la web cerrada:** en Chrome de escritorio o Android, el
  interruptor de avisos. En iPhone solo con la web instalada e iOS 16.4 o más
  nuevo.

## Medir la Key Attestation antes de exigirla

Con `WTFUCK_ATESTACION=registrar`, cada teléfono que se registra, vincula o
recupera queda anotado con lo que dijo su cadena. Para ver cuántos pasan y por
qué fallan los que fallan:

```bash
sudo docker compose -f docker-compose.tras-proxy.yml exec db psql -U wtfuck -d wtfuck -c "SELECT atestacion, count(*) FROM dispositivo WHERE atestacion_en > now() - interval '30 days' GROUP BY 1 ORDER BY 2 DESC"
```

Cuando casi todos digan `verificada:…`, se pasa a `WTFUCK_ATESTACION=exigir` y
se reinicia el servidor. Desde ahí, un script que se haga pasar por la app ya
no se registra.

> **Ojo:** esta sección depende de W5e (Key Attestation), que todavía no está
> en origin. Hasta que se suba, esas variables no hacen nada y la consulta
> falla porque la columna no existe.
