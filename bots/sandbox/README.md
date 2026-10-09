# Imagen del sandbox (agente-shell)

Imagen de pentesting para `/pentest` (ver `docs/15-AGENTE-SHELL.md`). Hay **dos**,
elegís según cuánto arsenal querés. El sandbox **no usa docker-compose**: el bot
crea/destruye un contenedor por sesión con `docker run`; estas imágenes solo
definen qué herramientas hay adentro.

## Opción A — liviana (`Dockerfile`)

Debian-slim con ~16 tools del catálogo + utilidades. Rápida y chica. **No** trae
sqlmap, wpscan, hydra, metasploit, etc.

```bash
docker build -t wtfuck-pentest:latest bots/sandbox
```

## Opción B — Kali, arsenal completo (`Dockerfile.kali`)

Basada en Kali: **sqlmap, wpscan, hydra, gobuster, metasploit, nikto, nmap…** y
cientos más. Pesa varios GB y el build tarda.

```bash
# arsenal amplio (default: kali-linux-large)
docker build -f bots/sandbox/Dockerfile.kali -t wtfuck-kali:latest bots/sandbox

# más liviano (set estándar sin GUI)
docker build -f bots/sandbox/Dockerfile.kali --build-arg KALI_META=kali-linux-headless -t wtfuck-kali:headless bots/sandbox

# literalmente todo (ENORME, >15 GB)
docker build -f bots/sandbox/Dockerfile.kali --build-arg KALI_META=kali-linux-everything -t wtfuck-kali:all bots/sandbox
```

Metapaquetes: `kali-linux-headless` (top tools), `kali-linux-large` (arsenal
amplio, incluye sqlmap/wpscan/hydra), `kali-linux-everything` (todo).

## Config (cualquiera de las dos)

En `bots/.env`, apuntá a la que construiste:

```
WTFUCK_BOT_SANDBOX=true
WTFUCK_BOT_IMAGEN=wtfuck-kali:latest     # o wtfuck-pentest:latest
```

(sin `WTFUCK_BOT_SANDBOX_FALSO`). El usuario que corre el bot necesita acceso al
Docker daemon.

## Qué trae

- **Puertos/servicios:** nmap, masscan, naabu, rustscan
- **Subdominios/activos:** amass, subfinder, findomain, bbot
- **DNS:** dnsx, dnsenum, fierce, dnsutils (dig)
- **OSINT:** theHarvester
- **Web/vulns:** nikto, nuclei (+ templates)
- **Utilidades:** curl, wget, netcat, jq, git, python3, ping, whois, iptables

Las de Go (subfinder, naabu, dnsx, nuclei, amass) se compilan `@latest` en un
stage aparte; theHarvester y bbot vienen de PyPI; rustscan y findomain del último
release de GitHub. Nada fijado a versiones viejas.

## Notas

- El build baja bastante (Go + tools); corré con red. Si la API de GitHub te
  limita (rate limit), reintentá o pasá un token.
- `iptables` queda en modo *legacy* (más predecible en contenedores). El egress lo
  aplica el bot al crear el contenedor; este Dockerfile solo deja la herramienta.
- Corre como root **dentro** del contenedor (nmap/masscan/iptables lo necesitan),
  pero el contenedor va sin `--privileged`, con `cap-drop ALL` + solo `NET_RAW` y
  `NET_ADMIN`, `no-new-privileges` y la red confinada al objetivo aprobado.
- Si algún asset (rustscan/findomain) cambió de nombre y el build lo saltea, se
  avisa en el log; instalalo a mano o ajustá el `grep` del Dockerfile.
- Para agregar una tool nueva: sumala acá **y** al catálogo en
  `bots/src/herramientas.ts` si querés que tenga comando propio (el agente-shell
  igual puede invocar cualquier binario presente).
