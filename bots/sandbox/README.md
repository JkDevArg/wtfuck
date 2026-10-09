# Imagen del sandbox (agente-shell)

Imagen de pentesting liviana para `/pentest` (ver `docs/15-AGENTE-SHELL.md`).
Alternativa a Kali entera: solo las tools del catálogo + utilidades del agente.

## Build

En el VPS (necesita Docker):

```bash
docker build -t wtfuck-pentest:latest bots/sandbox
```

Y en `bots/.env`:

```
WTFUCK_BOT_SANDBOX=true
WTFUCK_BOT_IMAGEN=wtfuck-pentest:latest
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
