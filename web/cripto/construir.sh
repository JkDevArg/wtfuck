#!/usr/bin/env bash
#
# Arma el WebAssembly de libsignal para el navegador en web/cripto/pkg.
#
#   bash web/cripto/construir.sh
#
# Hace falta, una vez:
#   rustup target add wasm32-unknown-unknown
#   cargo install wasm-bindgen-cli --version 0.2.129 --locked
#   protoc (libsignal compila sus .proto al construirse): PROTOC=ruta/protoc,
#   o en G:/PROYECTOS/_ext/protoc/bin/protoc.exe, que es donde se busca solo.
#
# La version de wasm-bindgen-cli TIENE que ser la misma que la del crate
# (Cargo.toml fija `=0.2.129`): con otra, el pegamento JS y el .wasm no se
# entienden y el error sale recien al cargar en el navegador.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${PROTOC:-}" ]; then
  for c in ../../../_ext/protoc/bin/protoc.exe ../../../_ext/protoc/bin/protoc "$(command -v protoc 2>/dev/null || true)"; do
    if [ -n "$c" ] && [ -f "$c" ]; then export PROTOC="$(cd "$(dirname "$c")" && pwd)/$(basename "$c")"; break; fi
  done
fi
[ -n "${PROTOC:-}" ] || { echo "Falta protoc: define PROTOC o instalalo."; exit 1; }

esperada=$(grep -oE 'wasm-bindgen = "=[0-9.]+"' Cargo.toml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
tengo=$(wasm-bindgen --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)
if [ "$esperada" != "$tengo" ]; then
  echo "wasm-bindgen-cli es '$tengo' y el crate pide '$esperada'."
  echo "  cargo install wasm-bindgen-cli --version $esperada --locked"
  exit 1
fi

echo "==> cargo build (wasm32, release)"
cargo build --release --target wasm32-unknown-unknown

echo "==> wasm-bindgen --target web"
rm -rf pkg
wasm-bindgen --target web --out-dir pkg target/wasm32-unknown-unknown/release/wtfuck_cripto.wasm

ls -la pkg
echo "sha256 del .wasm: $(sha256sum pkg/wtfuck_cripto_bg.wasm | cut -d' ' -f1)"
