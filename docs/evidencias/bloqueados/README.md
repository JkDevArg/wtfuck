# Bloqueados, y el ícono del botón Nuevo

## Lo que faltaba

Se podía bloquear desde el chat y desde la ficha de una persona, pero no había
forma de deshacerlo. El bloqueo vive en el servidor, no había ruta para
listarlo y ninguna pantalla llamaba a `Repositorio.desbloquear`. Un bloqueo
por error era para siempre. Se encontró al cerrar la fase 1 del modo cerca:
ver `docs/evidencias/modo-cerca-fase1/`.

## Dos defectos del servidor que destapó la prueba

La suite nueva, `pruebas/bloqueados.mjs`, falló contra el servidor de antes en
dos cosas que no eran de la pantalla:

1. **No se podía desbloquear a quien se oculta de la búsqueda.** El `DELETE`
   resolvía el username con `Repo.buscar`, que respeta el ajuste "quién me
   encuentra" del otro. Si esa persona ponía la búsqueda en "nadie", la ruta
   respondía 404 ("No existe ese usuario") y el bloqueo quedaba para siempre.
2. **No se podía bloquear a quien se oculta de la búsqueda, aunque hubiera un
   chat con esa persona.** El `POST` resolvía igual. Es justo el caso para el
   que existe bloquear: alguien que molesta en un chat y no deja que lo
   encuentren.

**Arreglo** (en `Autz`):

- **Desbloquear** busca primero entre MIS bloqueos (`bloqueadoPorNombre`).
  Ahí no hay nada que averiguar: solo encuentra a quien ya bloqueé.
- **Bloquear** acepta la búsqueda **o** una conversación compartida, aunque
  alguno ya haya salido de ella (`conocidoPorNombre`). No se resuelve por el
  username a secas, para que la ruta no sirva para averiguar si un usuario
  existe: con un desconocido oculto sigue respondiendo 404, igual que con uno
  que no existe.

## Lo que se agregó

| Dónde | Qué |
|---|---|
| Servidor | **`GET /v1/bloqueos`**: a quiénes bloqueé, el más reciente primero. Trae solo `usuarioId`, `username` y `desde`; la foto y el nombre los rige la privacidad del otro. |
| Protocolo | `Bloqueado` en `Grupos.kt`. |
| App | Pantalla **Bloqueados**, desde **Privacidad**. Lista con "Desbloquear", confirmación, estado vacío y estado de error con reintento. |
| App | La **ficha de una persona** bloqueada ofrece "Desbloquear a @…" en lugar de "Bloquear". |
| Modo cerca | `Repositorio.bloqueados()` pone la lista local del modo cerca al día con la del servidor. Se llama al abrir la lista y al encender el modo cerca. Un bloqueo hecho en otro aparato mío se aísla igual que uno hecho aquí: borra las balizas, rota la clave y corta el enlace. Uno deshecho en otro aparato sale de la lista local. |

## El botón Nuevo

El "+" del botón flotante de la lista de chats pasa a ser **el dedo del
medio**, como el nombre de la app (`ui/IconoDedo.kt`). Al abrirse el menú
cambia a una X ("Cerrar") con un fundido. Antes el "+" giraba 45° hasta
volverse X, pero el dedo girado no dice nada.

**Por qué es un ícono dibujado y no un emoji:**

- un emoji trae sus propios colores y cambia según el fabricante;
- este sigue la grilla de 24 y el relleno de un solo color de Material, así
  que `Icon` le pone el tinte del botón.

**El dibujo:**

- es el dorso de una mano derecha;
- cada dedo es una columna de punta redonda, con ranuras de 0.6 entre ellas
  para que los nudillos se lean a 24 dp.

El diseño se iteró rasterizando el path fuera de la app (tres versiones) antes
de llevarlo a Compose.

## Pruebas

**`pruebas/bloqueados.mjs`, 24 casos.** Contra el servidor anterior fallaban
8, y en esos 8 están los dos defectos de arriba. Comprueba:

- la lista empieza vacía y sin sesión responde 401;
- trae solo mis bloqueos, sin repetir, el más reciente primero, con id y
  fecha;
- quien fue bloqueado no ve nada;
- se desbloquea a quien se oculta de la búsqueda;
- desbloquear a quien no estaba bloqueado no falla, y a quien no existe da 404;
- se bloquea a quien se oculta y comparte un chat conmigo;
- a un oculto sin chat en común, 404, igual que a alguien que no existe.

**Unitarias:** 676 de la app y 87 de JUnit en el servidor, sin fallos.

### En el emulador (5554 = @xampl3)

| Paso | Resultado |
|---|---|
| Lista de chats | El botón muestra el dedo y "Nuevo" (`1-boton-nuevo.png`). Al tocarlo, X y "Cerrar" con el menú abierto (`2-boton-abierto.png`). |
| Privacidad | La entrada "Bloqueados", debajo de "Listas personalizadas" y antes de "Este aparato" (`3-entrada-en-privacidad.png`). |
| Bloqueados, sin nadie | "No has bloqueado a nadie" (`4-lista-vacia.png`). |
| Bloquear a @goblin2026 desde el menú del chat | Queda en `bloqueo` del servidor y en la lista local del modo cerca. |
| Su ficha | "Desbloquear a @goblin2026 · Lo bloqueaste" (`5-ficha-con-desbloquear.png`). |
| Bloqueados | @goblin2026 con la fecha y el botón (`6-lista-con-uno.png`). |
| Desbloquear y confirmar | Diálogo (`7-confirmar-desbloqueo.png`). La lista queda vacía (`8-desbloqueado.png`). En el servidor no queda la fila, y la lista local del modo cerca queda vacía. |
| Bloqueo hecho "en otro aparato" (fila insertada directo en la base) y abrir la lista | Aparece. La lista local del modo cerca lo incorpora y la clave de baliza rota: su versión cambió de `1791267574941` a `1791267664607`. |
| Desbloqueo hecho "en otro aparato" (fila borrada) y abrir la lista | Lista vacía, y la lista local del modo cerca también. |

## Para desplegar

Va en la 0.6.4 **con el servidor**: la app nueva contra un servidor viejo
recibiría 404 en `GET /v1/bloqueos` y la pantalla mostraría "No se pudo cargar
la lista". Ver `despliegue/notas/0.6.4.md`.
