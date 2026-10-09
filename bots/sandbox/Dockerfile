# Imagen de pentesting para el sandbox del agente-shell (ver docs/15-AGENTE-SHELL.md).
#
# Liviana y reproducible: NO es Kali entera. Trae las tools del catálogo + las
# utilidades que el agente suele usar. Multi-stage: un stage con Go compila las
# tools de ProjectDiscovery/amass (@latest, sin fijar versiones viejas); la imagen
# final es debian:stable-slim con solo los binarios, sin el toolchain de Go.
#
# Build (en el VPS):
#   docker build -t wtfuck-pentest:latest bots/sandbox
# Y en bots/.env:  WTFUCK_BOT_IMAGEN=wtfuck-pentest:latest
#
# El contenedor corre con cap NET_RAW (nmap/masscan/naabu) y NET_ADMIN (iptables
# del egress), sin --privileged. El bot le aplica el firewall de egress al crear.

# ---------- stage 1: compilar las tools en Go ----------
FROM golang:bookworm AS gobuild
# naabu necesita libpcap para compilar.
RUN apt-get update && apt-get install -y --no-install-recommends libpcap-dev && rm -rf /var/lib/apt/lists/*
ENV GOBIN=/out
RUN mkdir -p /out && \
    go install github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest && \
    go install github.com/projectdiscovery/naabu/v2/cmd/naabu@latest && \
    go install github.com/projectdiscovery/dnsx/cmd/dnsx@latest && \
    go install github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest && \
    go install github.com/owasp-amass/amass/v4/...@master

# ---------- stage 2: imagen final ----------
FROM debian:stable-slim

# Tools por apt + utilidades que usa el agente. iptables para el egress; libpcap
# en runtime para naabu/masscan; pipx para las de Python.
RUN apt-get update && apt-get install -y --no-install-recommends \
      nmap masscan whois dnsutils dnsenum fierce nikto \
      iputils-ping netcat-openbsd curl wget git jq unzip ca-certificates \
      iptables libpcap0.8 \
      python3 python3-venv pipx \
    && rm -rf /var/lib/apt/lists/*

# iptables en modo legacy: más predecible dentro de contenedores que el backend nft.
RUN update-alternatives --set iptables /usr/sbin/iptables-legacy || true

# Binarios de Go desde el stage anterior.
COPY --from=gobuild /out/ /usr/local/bin/

# Tools de Python (siempre la última de PyPI).
ENV PATH=/root/.local/bin:$PATH
RUN pipx install theHarvester && pipx install bbot

# rustscan y findomain: último release de GitHub (sin fijar versión).
RUN set -eux; \
    url=$(curl -fsSL https://api.github.com/repos/bee-san/RustScan/releases/latest \
      | jq -r '.assets[].browser_download_url' | grep -iE 'amd64\.deb$' | head -n1); \
    if [ -n "$url" ]; then curl -fsSL "$url" -o /tmp/rustscan.deb && (dpkg -i /tmp/rustscan.deb || apt-get install -yf) && rm -f /tmp/rustscan.deb; \
    else echo "AVISO: no encontré .deb de rustscan; sáltalo o instalalo a mano"; fi

RUN set -eux; \
    url=$(curl -fsSL https://api.github.com/repos/findomain/findomain/releases/latest \
      | jq -r '.assets[].browser_download_url' | grep -iE 'findomain-linux\.zip$' | head -n1); \
    if [ -n "$url" ]; then curl -fsSL "$url" -o /tmp/findomain.zip && unzip -o /tmp/findomain.zip -d /usr/local/bin && chmod +x /usr/local/bin/findomain && rm -f /tmp/findomain.zip; \
    else echo "AVISO: no encontré el zip de findomain; sáltalo o instalalo a mano"; fi

# Templates de nuclei (si hay red en el build). No falla el build si no puede.
RUN nuclei -update-templates || true

WORKDIR /root
# El bot arranca el contenedor con `sleep infinity` y corre comandos por `docker exec`.
CMD ["sleep", "infinity"]
