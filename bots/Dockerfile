# Cliente de bots de wtfuck (headless, E2EE) — imagen de ejecucion.
#
# Se CONSTRUYE EN LA PC con despliegue/publicar-bot.sh y se envia al VPS con
# `docker save | ssh docker load`. No se construye en el VPS a proposito: el bot
# carga el WASM de web/cripto/pkg, que se genera con Rust/wasm-pack
# (web/cripto/construir.sh) y esta en .gitignore. Igual que la version web, el
# artefacto se arma donde estan las herramientas (la PC) y al VPS va el resultado.
#
# El CONTEXTO de build es la RAIZ del repo (no bots/), porque el bot importa el
# contrato de web/app/src/datos y el WASM de web/cripto/pkg:
#
#   docker build -f bots/Dockerfile -t wtfuck-rootbot:latest .
#
# Las rutas dentro de la imagen replican las del repo para que los imports
# relativos del codigo (../../web/...) resuelvan igual que en la PC.
FROM node:22-bookworm-slim
WORKDIR /app/bots

# El manifiesto primero, para cachear la capa de dependencias (tsx). NO se pone
# NODE_ENV=production: tsx vive en devDependencies y hace falta en ejecucion.
COPY bots/package.json ./
RUN npm install --no-audit --no-fund

# Codigo del bot, contrato compartido y WASM, en las MISMAS rutas relativas.
COPY bots/src ./src
COPY web/app/src/datos /app/web/app/src/datos
COPY web/cripto/pkg /app/web/cripto/pkg

# datos/ (claves + sesion del bot) se monta como volumen en produccion; si no,
# index.ts lo crea solo al arrancar (mkdirSync recursivo).
CMD ["npm", "run", "bot"]
