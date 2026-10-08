# wtfuck web

El cliente web de wtfuck. El plan, las decisiones y el modelo de confianza
están en `docs/12-VERSION-WEB.md`.

## Qué hay aquí

| Carpeta | Qué es |
|---|---|
| `cripto/` | libsignal-protocol **v0.86.5** (la misma de la app Android) compilada a WebAssembly, con un envoltorio mínimo en Rust (`wasm-bindgen`). |
| `interop/lado-web.mjs` | El WebAssembly manejado desde Node, un pedido JSON por línea. Lo usa la prueba de la JVM. |
| `interop/navegador.html` | La misma libsignal dentro de un navegador de verdad: sesiones, ratchet, grupos y huella. |
| `interop-jvm/` | Módulo de Gradle `:interop-web`. Hace de teléfono con la libsignal oficial de la JVM y habla con el WebAssembly. |

La aplicación en React llega en W2.

## Requisitos (una vez)

Este README asume Git Bash en Windows; son los comandos que se usaron.

```bash
rustup target add wasm32-unknown-unknown
```
```bash
cargo install wasm-bindgen-cli --version 0.2.129 --locked
```

También hace falta `protoc`: libsignal compila sus `.proto` al construirse.
El script lo busca en `G:/PROYECTOS/_ext/protoc/bin/protoc.exe`; si está en
otro lado, define `PROTOC`. Se baja de las releases de
[protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf/releases).

## Armar el WebAssembly

```bash
bash web/cripto/construir.sh
```

Deja `web/cripto/pkg/`, que no se versiona: unos 1,1 MB de `.wasm` y el
pegamento en JS.

## Probar

Las pruebas nativas del envoltorio:

```bash
cd web/cripto && cargo test --release
```

El navegador contra el teléfono, con la libsignal de la JVM:

```bash
./gradlew :interop-web:test
```

Si `pkg/` no está armado o no hay Node, esta prueba se omite con un aviso.

Dentro de un navegador, servir `web/` y abrir la página:

```bash
python -m http.server 8765 --bind 127.0.0.1 --directory web
```

Después, abrir `http://127.0.0.1:8765/interop/navegador.html`.
