# Evidencias · Bloqueo de la app y estados de error

| # | Captura | Qué muestra |
|---|---|---|
| 01 | `01-bloqueo.png` | La app bloqueada al volver de segundo plano, pidiendo el PIN del teléfono |
| 02 | `02-canal-sin-red.png` | Un canal sin conexión: **el nombre en la cabecera** y un error con Reintentar |
| 03 | `03-cuenta-sin-red.png` | "Cuenta y seguridad" sin conexión |
| 04 | `04-directorio-sin-red.png` | El directorio de canales sin conexión |

## Qué se veía antes

Las tres últimas capturas son de pantallas que, sin red, mostraban esto:

| Pantalla | Lo que decía | Por qué está mal |
|---|---|---|
| Canal | cabecera **vacía** + "Este canal todavía no tiene publicaciones" | Afirma algo sobre el canal sin haber podido preguntar |
| Cuenta y seguridad | una línea gris: "No se pudo leer el estado de la cuenta." | Se lee como que la app está rota, y no ofrece salida |
| Directorio | "Todavía no hay canales públicos." | Afirma algo sobre la plataforma entera basándose en un timeout |

La causa de fondo era la misma en los tres: `getOrElse { emptyList() }` en el
repositorio. **"No pude preguntar" y "pregunté y no hay" acababan siendo el
mismo valor**, y la pantalla creía el segundo.

## Verificado a mano, con la red cortada de verdad

El túnel que usa la app (`adb reverse tcp:8088 tcp:8300`) se quitó para
provocar el fallo real, no uno simulado:

1. Sin túnel → el directorio muestra el error (captura 04).
2. Se restaura el túnel y se toca **Reintentar** → el directorio carga, sin
   reiniciar la app.
3. Sin túnel → el canal muestra **"Auditoria EducaD"** en la cabecera, sacado
   de la copia local, y el error debajo (captura 02).
4. Sin túnel → la cuenta muestra su error; Reintentar la carga (captura 03).

Un defecto encontrado y corregido durante esta comprobación: el nombre local se
leía con `remember(conversacionId)`, y como la lista de chats llega por un
`Flow`, en la primera composición estaba vacía — la cabecera decía "Canal"
teniendo el nombre a mano en la base. La clave del `remember` ahora incluye la
lista.

## El bloqueo

Probado con un PIN de verdad puesto en el emulador
(`adb shell locksettings set-pin 1234`):

1. Ajuste en **"Al salir de la app"**.
2. Botón de inicio → volver a abrir → pide autenticación (captura 01).
3. PIN correcto → vuelve **exactamente a donde estaba**, no al inicio.

Lo que no se puede comprobar así son los casos en que fallaría **abriéndose**
—un reinicio, un valor guardado que no existe, el reloj movido— y ésos están en
[`BloqueoTest`](../../../app/src/test/java/com/wtfuck/app/BloqueoTest.kt), 13
pruebas. Tres de ellas se cayeron al escribirlas y destaparon una ambigüedad
real del contrato: el `0` significa "nunca", no "el instante cero".
