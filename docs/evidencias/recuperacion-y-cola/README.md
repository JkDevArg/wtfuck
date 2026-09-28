# Tres defectos que encontró una auditoría del propio código

Ninguno estaba marcado con un `TODO`. Es justamente por eso que sobrevivieron:
un barrido de `TODO|FIXME|HACK` sobre los tres `src/main` devuelve **cero**
marcadores reales. El código no autodeclara sus huecos.

---

## 1 · El SMS puenteaba la verificación en dos pasos

### El defecto

`POST /v1/cuenta/recuperar` cambia la contraseña y **cierra todas las
sesiones**. No exigía el segundo factor.

Así que con el 2FA encendido, quien controlara el número de teléfono —un cambio
de SIM, un empleado del operador— podía cambiar la contraseña y echar a la
persona de todas sus sesiones **sin tocar el segundo factor**. El segundo factor
existe precisamente para que tener el número no alcance; una ruta de
recuperación que lo salta lo anula.

### Lo que NO es

No es una toma de control completa: el ingreso sigue exigiendo un
`hardware_hash` vinculado, así que el atacante no entra desde su teléfono. Lo
que consigue es **dejar fuera a la víctima** y quedarse con la contraseña. Grave,
pero conviene decirlo con precisión.

### La prueba certificaba el agujero

Lo más incómodo del hallazgo:

```js
// pruebas/identidad.mjs, ANTES
r = await post('/v1/cuenta/recuperar', null, {
  username: ana.user, telefono: TEL_ANA, codigo: COD_REC, passwordNueva: CLAVE_NUEVA,
});
ck('se recupera la cuenta', r.s === 204, ...);
```

Ana **tiene 2FA activado** en esa suite —se usa `totp(SECRETO)` para entrar dos
líneas más abajo—. La comprobación exigía un 204 sin mandar el TOTP. O sea que
la prueba no pasaba por casualidad: **afirmaba el comportamiento incorrecto como
si fuera el correcto**. Una prueba escrita mirando lo que el código hace, en vez
de lo que debería hacer, convierte un agujero en una promesa.

### El arreglo

`exigirSegundoFactor(c, id, req.totp)`, y va **después** de canjear el SMS a
propósito: así la ruta no sirve para averiguar si una cuenta tiene 2FA sin tener
antes el SMS.

Dos propiedades que había que verificar, no suponer:

1. **No deja fuera a quien perdió el teléfono.** `exigirSegundoFactor` acepta
   también un **código de respaldo**, que es para lo que se emiten.
2. **El SMS no se quema en el rechazo.** `recuperar` corre dentro de `Db.tx`, y
   `Db.tx` hace `rollback` en cualquier excepción — así que el `canjear` se
   deshace y el mismo código del SMS sirve para el reintento. Sin esto, la
   defensa se pagaría con una pantalla inusable: un SMS nuevo por cada intento.

La app no pregunta el código de dos pasos de entrada —la mayoría no lo tiene—:
manda sin él, y si recibe `401` abre el campo y reintenta con el mismo SMS.

---

## 2 · `despachar()` podía enviar el mismo sobre dos veces

La cola de salida se selecciona por `estado = 'PENDIENTE'` y **no se marca en
vuelo**. Dos invocaciones concurrentes recorren la misma lista y registran y
entregan los mismos sobres.

Y pasa de verdad: hay **ocho** llamadores, y al menos dos son `launch` hermanos
del mismo ámbito —el `collect` de la reconexión y el manejo de `sinCopia`—, así
que una reconexión mientras se completan copias los dispara juntos.

**El síntoma no se parece a la causa.** El receptor descarta el duplicado por el
id (`OnConflictStrategy.IGNORE`), así que *no se ve un mensaje repetido*. Se ve
como trabajo de más, cupo del servidor gastado al doble y, en un grupo, claves
de emisor dadas por repartidas por un envío que la otra corrutina ya estaba
haciendo.

Arreglado con un `Mutex`. **`Mutex` y no un `Boolean`**: con un flag, dos
corrutinas pueden leerlo en `false` antes de que ninguna lo ponga en `true` —
el mismo error que el servidor ya evita consumiendo el código de respaldo con el
`UPDATE` mismo en vez de leer-y-después-marcar.

Y `withLock` en vez de "salir si está ocupado": si otra vuelta está en curso, lo
correcto es esperarla y volver a mirar la cola. Salir en silencio dejaría sin
despachar justo lo que se acaba de encolar.

---

## 3 · Tres escaneos completos de tabla por cada mensaje recibido

`mensaje` es la tabla más grande y la que más se escribe. Room **reinvalida toda
consulta que la toque en cada escritura**, así que un `Flow` sin índice no cuesta
una vez: cuesta **una vez por mensaje que entra**.

La pantalla principal tenía tres de esos a la vez —el tamaño de la cola, el de
los fallidos y las conversaciones con fallidos—, todos filtrando por `estado`,
que no tenía índice.

| Índice nuevo | Para qué |
|---|---|
| `(esMio, estado)` | La cola de salida y los fallidos. Compuesto y en ese orden porque todas esas consultas filtran por ambos, empezando por `esMio` |
| `expiraEn` | El barrido de temporales, que corre al abrir la app y cada chat. La tabla `historia` ya tenía el suyo para lo mismo; aquí faltaba |
| `adjuntoEstado` | Recuperar subidas interrumpidas al arrancar |

No se indexa `rutaLocal` —solo la mira la pantalla de almacenamiento, a mano— ni
`oculto`, que casi siempre vale lo mismo y no separa nada.

> **La trampa de Room:** los `indices` de la anotación solo se aplican al
> **crear** la tabla. En una base que ya existe, declararlos sin migración no
> crea nada *y* Room falla la validación del esquema. De ahí la migración
> `20 → 21`, que no cambia ni una columna.

---

## Y un cuarto: una prueba intermitente propia

La suite `temporales.mjs` esperaba el barrido con un `setTimeout` de 12 s para un
barrido que corre cada 10 s. Fallaba **de vez en cuando, solo en la corrida
completa**: con el servidor cargado por las 37 suites anteriores, el barrido se
atrasa lo justo para pasarse.

Cambiado por un sondeo de hasta 30 s. Una prueba intermitente es peor que
ninguna: enseña a ignorar los fallos, y el día que esa falle de verdad nadie va a
mirar.

---

## Los archivos

| Archivo | Qué cambió |
|---|---|
| [`Identidad.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Identidad.kt) | `recuperar` exige el segundo factor |
| [`Identidad.kt` (protocolo)](../../../protocol/src/main/kotlin/com/wtfuck/protocol/Identidad.kt) | `RecuperarReq.totp` |
| [`AuthPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/AuthPantalla.kt) | Pide el código solo si el servidor responde 401, y reintenta con el mismo SMS |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | `Mutex` en `despachar()`; pasa el `totp` |
| [`BaseLocal.kt`](../../../app/src/main/java/com/wtfuck/app/datos/BaseLocal.kt) | Tres índices en `mensaje` + migración 20→21 |
| [`identidad.mjs`](../../../pruebas/identidad.mjs) | La comprobación que certificaba el agujero, dada la vuelta |
| [`temporales.mjs`](../../../pruebas/temporales.mjs) | Sondeo en lugar de espera fija |

## Lo verificado

| Qué | Resultado |
|---|---|
| `pruebas/identidad.mjs` | ✅ 101 pasan, 0 fallan — incluidas las 3 nuevas del segundo factor |
| **38 suites de integración** | ✅ **1582 pasan, 0 fallan** |
| `:server:test` + `:app:testDebugUnitTest` | ✅ |

Las tres comprobaciones que cubren el defecto de seguridad:

```
PASA  sin el segundo factor NO se recupera, aunque el SMS sea correcto
PASA  con un segundo factor invalido tampoco
PASA  con el SMS y el segundo factor si se recupera -y el SMS no se habia quemado-
```

## Lo que NO está verificado

- **El efecto de los índices no se midió.** El razonamiento es correcto (Room
  reinvalida por escritura; `EXPLAIN QUERY PLAN` sobre esas cláusulas usaría los
  índices nuevos), pero no hay un antes/después con un historial grande. Lo
  honesto: es una mejora *esperada*, no una *medida*.
- **El `Mutex` no tiene prueba.** Reproducir la carrera pide dos corrutinas
  entrando a la vez con la cola llena, y no hay banco de pruebas de corrutinas
  en el proyecto. El razonamiento sí está escrito en el KDoc.
- **La pantalla de dos pasos en la recuperación, on-device.** El camino del
  servidor está probado; el `401 → abrir el campo → reintentar` de la app no se
  ejecutó en un teléfono.
