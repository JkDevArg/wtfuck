# Módulo BE · Copia de seguridad cifrada

Que perder el teléfono no sea perder los chats.

---

## El problema

El historial vive **solo en el teléfono** (el servidor es un buzón tonto que no
guarda mensajes). La sincronización entre dispositivos resuelve "tengo el viejo
y el nuevo a la vez"; **no** resuelve "se me rompió y no tengo otro". Para eso
está esto: un archivo cifrado que la persona guarda donde quiera y restaura en
un teléfono nuevo.

## Qué incluye (v1)

El **texto** de los chats: por cada conversación, sus mensajes (id, autor, si es
mío, texto, fecha). No incluye archivos ni fotos —pesan y viven aparte— ni los
mensajes de sistema, retirados o vacíos, que son ruido al restaurar.

## La cripto — dónde y por qué así

`CopiaSeguridad` es un objeto **puro, sin `Context`**: todo lo que decide algo
de cripto está ahí para poder probarlo en JUnit normal.

- **Derivación**: `PBKDF2WithHmacSHA256`, 210 000 iteraciones (OWASP), salt de
  16 bytes aleatorio → clave AES de 256 bits. La copia se descifra una vez cada
  tanto; el costo alto lo paga quien intente adivinar la frase a lo bruto.
- **Cifrado**: `AES-256-GCM`, nonce de 12 bytes aleatorio, etiqueta de 128 bits.
- **Cabecera autenticada (AAD)**: la magia `WTFBKP01` va autenticada, así que
  bajar las iteraciones de un archivo robado para debilitarlo lo invalida.
- **Formato**: `MAGIA(8) · salt(16) · iteraciones(4) · nonce(12) · cifrado`.

**Esto no es sealed sender ni toca el núcleo E2EE.** Es cifrado simétrico de un
archivo con una clave derivada de una frase — rutina bien entendida. El servidor
nunca ve nada: se cifra y descifra entero en el teléfono.

## La decisión de UX que no se puede omitir

La frase **no se guarda en ningún lado** —ese es el punto: ni el servidor ni
nadie puede abrir la copia—. Si se olvida, el archivo es inservible. Por eso al
exportar se pide **dos veces** (un error de tipeo en algo que no se ve dejaría
una copia que no abre, y no habría cómo saberlo hasta necesitarla) y con una
advertencia con todas las letras. Mínimo 8 caracteres: una copia protegida por
"1234" no está protegida.

## Restaurar

`restaurarCopia` descifra, y por cada conversación guarda los mensajes que aún
no estaban. `guardarMensaje` es un upsert por id, así que **restaurar dos veces
no duplica**. Si una conversación no existe localmente todavía (teléfono recién
reinstalado), se crea una fila mínima para que los mensajes tengan dónde vivir;
la membresía y las claves las completa la sincronización normal.

## Las pruebas

`CopiaSeguridadTest` (JUnit, corre en JVM sin emulador) cubre lo único que de
verdad importa: **que se pueda restaurar con la frase correcta, y de ninguna
otra forma.**

```
- la frase correcta recupera exactamente el contenido
- una frase equivocada no abre
- un archivo manipulado se rechaza (GCM)
- bajar las iteraciones en el archivo lo invalida (AAD)
- un archivo que no es una copia se reconoce por el formato
- dos copias del mismo contenido no son iguales (salt/nonce nuevos)
```

6 pruebas, verdes. Suite de app: 385, 0 fallos.

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`CopiaSeguridad.kt`](../../../app/src/main/java/com/wtfuck/app/datos/CopiaSeguridad.kt) | La cripto y el formato (puro, testeable) |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `exportarCopia` / `restaurarCopia` (reúsan el historial local) |
| [`CopiaSeguridadPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/CopiaSeguridadPantalla.kt) | Exportar/importar con selector de archivo del sistema |
| [`CopiaSeguridadTest.kt`](../../../app/src/test/java/com/wtfuck/app/CopiaSeguridadTest.kt) | El round-trip |

## Lo que NO está verificado

El **flujo on-device** (elegir archivo con el selector del sistema, exportar a
un archivo real, importarlo) **no se ejecutó en emulador esta sesión**: el
entorno estaba caído (Docker con puertos reservados por Windows, el AVD
cerrado). Lo que sí está probado es la cripto (JUnit) y que el compilador validó
la consulta Room nueva. El selector de archivos usa el mismo patrón ya presente
en el chat, y un fallo ahí sería un "no se pudo" visible, no pérdida silenciosa
—esa (la cripto) está cubierta—. Queda como pendiente correrlo en un teléfono o
AVD con la sesión iniciada.
