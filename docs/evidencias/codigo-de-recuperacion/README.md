# Código de recuperación · la salida para "perdí el teléfono"

Estado: **etapas 1, 2 y 3 hechas.** Falta la 4 (pantallas).

---

## El problema

La cuenta está atada al hardware **a propósito**: para ingresar hay que ser un
dispositivo registrado (`Repo.kt`, el `WHERE usuario_id = ? AND hardware_hash = ?`),
vincular uno nuevo exige un código emitido **desde el principal**, y el
principal no se puede revocar. Es una defensa fuerte y deliberada: con la
contraseña robada, nadie entra desde su teléfono.

El precio era absoluto:

> **Teléfono perdido = cuenta perdida para siempre.** Con copia de seguridad o
> sin ella.

Y eso dejaba coja la copia que se entregó en 0.6.0-beta, porque su propia
pantalla decía *"para restaurar en un teléfono nuevo, primero entra a tu
cuenta"* — y en un teléfono nuevo **no se puede entrar**. La función existía
para un caso que no tenía camino.

Hay un segundo daño, más silencioso: al restaurar en otro aparato,
`AlmacenSignal.asegurarIdentidad()` genera una identidad nueva, y entonces **a
todos los contactos les salta el aviso de que la clave cambió**. Esa alarma es
la que avisa de un intento de suplantación; dispararla cada vez que alguien
cambia de teléfono enseña a ignorarla, y deja de servir el día que es de verdad.

---

## La decisión

Se evaluaron cuatro caminos y se eligió **añadir una salida, no debilitar el
atado al hardware**:

| Opción | Por qué no |
|---|---|
| Vincular con SMS + 2FA | Quien tenga tu número, tu clave y tu 2FA entra desde su teléfono. Debilita justo lo que protege |
| Solo ser honesto en la pantalla | Barato, pero la copia queda sin su caso de uso principal |
| Solo meter la identidad en la copia | Arregla el "cambió la clave" pero **no** el bloqueo: seguirías sin poder entrar |
| **Código de recuperación** ✅ | Mantiene el atado para todo, y añade **una** puerta, en papel, que la persona controla |

---

## Por qué un código y no doce palabras

Doce palabras se transcriben mejor — es la razón de ser de BIP-39. Pero piden
una lista fija de 2048 palabras revisada (sin duplicados, prefijos de cuatro
letras únicos), y traerla significaba **meter un archivo externo al repositorio
para ganar comodidad al escribir, no seguridad**.

El código da los mismos 128 bits sin dependencia nueva. No está pensado para
dictarlo de memoria: se anota una vez y se guarda.

```
XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX     28 símbolos, 7 grupos de 4
```

16 bytes de azar + 1 byte de control = 17 bytes = 136 bits → 28 símbolos en
base32. **Alineado a byte a propósito**: así los 16 bytes de entropía entran tal
cual en el HKDF y no hay que recortar bits sueltos.

**Alfabeto Crockford**, sin `I`, `L`, `O` ni `U`. Al leer, una `I` o una `L` se
entienden como `1` y una `O` como `0`, porque quien lo copió en papel pudo
dibujar cualquiera de las dos. La `U` se excluye para que no salgan palabras
desafortunadas por azar.

El byte de control detecta 255 de cada 256 erratas. No es integridad
criptográfica —no hace falta, el código no lo elige un atacante— sino que la
pantalla pueda decir *"lo copiaste mal"* en vez de *"no se pudo restaurar"*.

---

## Las dos claves, y por qué están separadas

Del mismo código salen dos cosas que **nunca** deben poder deducirse una de la
otra:

| Clave | Para qué | Dónde vive |
|---|---|---|
| `claveDeIdentidad` | Cifra la identidad Signal dentro de la copia | Nunca sale del teléfono |
| `verificadorServidor` | Autoriza el alta de un teléfono nuevo | El servidor guarda un **hash** |

Se derivan con **HKDF y etiquetas distintas**. Esa separación es lo que hace
que **el servidor no pueda descifrar tu identidad** aunque le roben la base
entera: de `verificadorServidor` no se llega a `claveDeIdentidad`, porque HKDF
no es invertible y las etiquetas producen salidas independientes.

### Sin PBKDF2, y es deliberado

PBKDF2 y Argon2 existen para estirar secretos de **baja entropía** —una
contraseña que eligió una persona—. Aquí la entrada son 128 bits de
`SecureRandom`, que no se adivinan a lo bruto ni con todo el hardware del
mundo. Poner 210.000 iteraciones encima costaría tiempo y no compraría nada.

Tampoco sal: la sal sirve para que dos secretos iguales no den la misma clave, y
dos códigos de 128 bits nunca son iguales. La separación que sí hace falta la
da la etiqueta.

---

## La identidad dentro de la copia (etapa 2)

### Dos cerraduras, no una

La identidad viaja **dentro del manifiesto**, que ya va cifrado con la frase, y
además **sellada con la clave del código**. Es deliberado: la clave privada de
identidad es el secreto más peligroso de la app. Con el historial robado se lee
lo que se dijo; **con la identidad robada se puede *ser* esa persona** de ahí en
adelante.

Las dos cerraduras son secretos de naturaleza distinta a propósito: la frase se
teclea y se recuerda, el código se escribe en papel una vez. Que un descuido
con una no entregue la otra.

> **Consecuencia honesta:** sin el código no se recupera la identidad, aunque se
> tenga la frase y el archivo. Sí se recuperan los mensajes, que es la mayor
> parte de lo que la gente teme perder. Está escrito como prueba:
> `sin el codigo no se saca la identidad aunque se tenga la frase`.

### La restauración NO pisa una identidad que ya esté en uso

Sería destructivo y silencioso. Si el teléfono ya habló con alguien, tiene
sesiones montadas sobre su identidad actual; cambiarla por debajo las invalida
—los mensajes que lleguen no se podrán abrir— y encima dispara el aviso de
clave cambiada en todos los contactos.

Restaurar una copia sobre una cuenta que ya funciona es un caso **real** —la
gente restaura para recuperar mensajes viejos— y no tiene por qué costar la
identidad. Así que solo se escribe cuando no hay ninguna o cuando es la misma.
El escenario que esto existe para servir —un teléfono nuevo— cae siempre en el
primer caso.

El resultado se informa con precisión, porque cada caso pide algo distinto de
la persona:

| Resultado | Qué significa |
|---|---|
| `NO_VENIA` | Copia v1/v2, o hecha sin código |
| `RESTAURADA` | Los contactos **no** verán que la clave cambió |
| `SIN_CODIGO` | La copia la trae, pero no se dio ningún código |
| `CODIGO_NO_ABRE` | El código no es el de esta copia |
| `YA_HABIA_OTRA` | Había otra identidad en uso y se respetó |

### Detalles que no son detalles

- **`publicadoEn = 0` al restaurar**, para que el aparato vuelva a publicar sus
  prekeys. Las de la copia son del teléfono viejo y el servidor puede haberlas
  entregado ya; sin republicar, nadie podría abrir una sesión nueva.
- **Los contadores de prekey se conservan.** Podrían volver a 1 y no se hace: si
  el servidor todavía tiene prekeys del aparato anterior con esos ids, las
  nuevas chocarían. Seguir contando no cuesta nada.
- **El código es opcional al exportar.** Quien no lo tenga a mano debe poder
  guardar sus mensajes igual. Perder el historial por no poder guardar la
  identidad sería el peor cambio posible.
- **Una copia v1/v2 se sigue abriendo.** El campo falta y queda en `null`.

---

---

## El servidor (etapa 3)

### Las cuatro puertas

`POST /v1/cuenta/recuperar-dispositivo` hace **exactamente lo que el atado al
hardware existe para impedir**: dar de alta un aparato que nadie autorizó desde
dentro. Así que se paga caro, a propósito:

| Puerta | Contra quién |
|---|---|
| Código del SMS | Quien encontró el papel pero no controla el número |
| Código de recuperación | Quien te clonó la SIM pero no tiene el papel |
| Segundo factor | Los dos anteriores a la vez, si está activado |
| Contraseña nueva | Cierra las sesiones viejas: si te robaron el teléfono, deja de valer |

**El SMS no sobra aunque parezca el factor débil.** Sin él, quien entrara a tu
casa y encontrara el papel, sabiendo tu usuario, entraría: un secreto en papel
tiene un modelo de robo muy distinto al de una contraseña.

### El orden de las comprobaciones no es casual

Primero el SMS, y solo después el verificador y el segundo factor. Así la ruta
**no es un oráculo**: sin tener el número no se puede averiguar si una cuenta
tiene código de recuperación configurado ni si tiene dos pasos. Está probado:

```
PASA  y la respuesta no delata si el codigo de recuperacion era bueno
```

### Un rechazo no quema el código del SMS

Todo corre dentro de `Db.tx`, que hace `rollback`. Sin esto haría falta un SMS
nuevo por cada intento y la pantalla sería inusable — la misma propiedad que se
verificó al exigir el segundo factor en `recuperar`.

```
PASA  el SMS aguanto los dos intentos fallidos sin quemarse
```

### Decisiones que podrían haber ido al revés

- **Se revocan TODOS los aparatos, no solo las sesiones.** El escenario es
  "perdí el teléfono": dejarlo vinculado sería dejar dentro justo al que puede
  tenerlo en la mano. Quien tenga otro aparato legítimo lo vuelve a vincular
  desde este, que es el camino normal.
- **El código NO es de un solo uso.** Tentaba —suena más seguro— y sería peor:
  si la persona se queda a medias, vuelve a estar bloqueada y sin salida. Se
  anota el uso y la pantalla ofrecerá rotarlo al entrar.
- **Fijar el código pide la contraseña**, porque fijar uno nuevo invalida el
  anterior: sin eso, quien se sentara un momento en una sesión abierta ajena
  podría dejar la cuenta con un código suyo y quedarse con la única salida.
- **Se reutiliza la fila del dispositivo** si el mismo hardware ya estaba
  (alguien reinstalando en el teléfono de siempre): el índice único parcial de
  `hardware_hash` no admite dos vivas.
- **Mismo limitador de ritmo que el ingreso.** Es una ruta sin sesión que, si
  acierta, entrega una. El código de recuperación son 256 bits y no se adivina;
  el del SMS son seis dígitos y sí.

### El hueco que tenía mi propia suite

La primera versión de `recuperacion.mjs` pasaba 33/33 **sin probar la puerta 3**:
ninguna cuenta de prueba tenía 2FA, así que esa puerta nunca se tocaba. Una
puerta que no se prueba es una puerta que no se sabe si cierra. Añadidas siete
comprobaciones, incluida la que importa para el caso real:

```
PASA  un codigo de RESPALDO sirve: quien perdio el telefono perdio el autenticador
```

### Lo que encontró la auditoría de rutas

`ajeno-lectura.mjs` falló con `/v1/cuenta/recuperacion`: exige que **cada ruta
esté cubierta o eximida con motivo escrito**. Funcionó como debe — una ruta
nueva no pasa desapercibida. Eximida con su razón: solo dice *si* hay código y
de cuándo, nunca el verificador, y siempre es el de quien pregunta.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`CodigoRecuperacion.kt`](../../../app/src/main/java/com/wtfuck/app/datos/CodigoRecuperacion.kt) | **Nuevo.** Generación, control, normalización y las dos claves |
| [`V43__codigo_de_recuperacion.sql`](../../../server/src/main/resources/db/V43__codigo_de_recuperacion.sql) | **Nueva.** `recuperacion_hash` + los dos eventos de seguridad |
| [`Identidad.kt` (servidor)](../../../server/src/main/kotlin/com/wtfuck/server/Identidad.kt) | `fijarRecuperacion`, `estadoRecuperacion`, `recuperarDispositivo` |
| [`Main.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Main.kt) | Las tres rutas, con limitador de ritmo en la pública |
| [`recuperacion.mjs`](../../../pruebas/recuperacion.mjs) | **Nueva.** 40 comprobaciones, la mayoría de lo que debe fallar |
| [`CopiaSeguridad.kt`](../../../app/src/main/java/com/wtfuck/app/datos/CopiaSeguridad.kt) | Formato v3: `IdentidadRespaldo`, `sellarIdentidad`, `abrirIdentidad` |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `identidadSellada` y `restaurarIdentidad`; el código entra en export e import |
| [`WtfuckApp.kt`](../../../app/src/main/java/com/wtfuck/app/WtfuckApp.kt) | Inyecta `SignalDao` en el repositorio |

## Lo verificado

| Qué | Resultado |
|---|---|
| `CodigoRecuperacionTest` | ✅ 15 pruebas |
| `IdentidadEnLaCopiaTest` | ✅ 13 pruebas |
| `recuperacion.mjs` | ✅ **40 comprobaciones** contra servidor y Postgres reales |
| Suite unitaria del app | ✅ 426 pruebas, 0 fallos |
| **39 suites de integración** | ✅ **1622 pasan, 0 fallan** |
| `:server:test` | ✅ |

Las tres que cubren fallos que no se notarían hasta que ya no tienen arreglo:

```
PASA  lo que se genera se acepta                     (2000 códigos)
PASA  lo que se sella se abre con el mismo código    (200 identidades)
PASA  el telefono nuevo NO puede entrar por las buenas   (la línea base)
```

Esa última es la que da sentido a todo el módulo: si no diera 403, no habría
nada que arreglar.

## Lo que NO está verificado

- **Todavía no es usable para nadie.** Falta la etapa 4: mostrar el código al
  registrarse, pedirlo al hacer y restaurar la copia, y la pantalla de
  "recuperar en un teléfono nuevo". Hasta entonces las rutas existen y
  funcionan, pero ninguna pantalla las llama.
- **El sellado se prueba con una identidad de mentira** (64 bytes de azar), no
  con un `IdentityKeyPair` real de libsignal. Se prueba el sobre, no libsignal
  — correcto para una prueba unitaria, pero conviene decirlo.
- **Sin prueba on-device del ciclo completo**: exportar en un teléfono,
  restaurar en otro, recuperar la cuenta y comprobar que la huella de seguridad
  **no cambió** para los contactos. Es la comprobación que de verdad cierra
  esto, y pide dos aparatos.
- **El HKDF del cliente y el de la prueba se escribieron dos veces a
  propósito** (Kotlin y JavaScript, independientes) y coinciden. Eso valida la
  derivación mucho mejor que importar la misma función en los dos sitios — pero
  ninguno se ha contrastado contra un vector de prueba oficial de RFC 5869.
