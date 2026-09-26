# Módulo BC · Invitaciones de registro

Cerrar el registro de un despliegue sin dejar de repartir el APK.

---

## El problema

El registro era abierto: quien tuviera el APK se creaba una cuenta. Para un
servidor público eso está bien. Para el de un equipo, un laboratorio o una
familia, no — y la única alternativa que había era **no repartir el APK**, o
sea, no tener app.

## Lo que esto no es

Una invitación controla **quién** puede registrarse, no si paga. El registro
sigue siendo gratis. Lo que deja de ser es anónimo para el servidor, que ahora
sabe quién invitó a cada quien.

## Abierto por defecto

`WTFUCK_REGISTRO` vale `abierto` mientras nadie diga otra cosa. Actualizar el
servidor no puede cerrarle el registro a quien no pidió cerrarlo: un cambio que
rompe un despliegue ajeno por venir activado de fábrica es peor que uno que hay
que encender a mano.

Para cerrarlo:

```
WTFUCK_REGISTRO=invitacion
WTFUCK_PROPIETARIO=tu_usuario
```

---

## El huevo y la gallina, que costó encontrar

Un servidor que arranca en modo invitación **no tiene ninguna cuenta**, y crear
invitaciones exige ser administrador. Sin una excepción el despliegue nace
inservible: nadie puede entrar, nadie puede invitar, y la única salida es
apagarlo, abrirlo, registrarse y volver a cerrarlo.

Se encontró probándolo. Con `WTFUCK_REGISTRO=invitacion` puesto desde el primer
arranque, hasta el propietario recibía *"Hace falta un código de invitación"*.

La excepción vale para **un** username concreto —el que eligió quien desplegó—
y sólo mientras no exista. En cuanto esa cuenta se crea, el segundo intento
choca con la unicidad del username: la excepción se cierra sola, sin ninguna
marca que mantener.

### El segundo, que salió del mismo sitio

`Panel.sembrarPropietario` corre **al arrancar el servidor**. Si la cuenta del
propietario todavía no existe en ese momento —que es el caso normal en un
despliegue nuevo— no promueve a nadie, y quien se registra después se queda sin
nivel de staff. En modo abierto eso es una molestia; en modo invitación es un
bloqueo, porque repartir el primer código exige ser administrador.

Ahora el propietario queda con su nivel **al registrarse**, dentro de la misma
transacción, en `Repo.registrar()`.

> Este defecto se manifestó en producción antes de estar arreglado: la cuenta
> `jcenturion` del VPS se registró después de arrancar el servidor y se quedó
> sin panel de administración. El arreglo inmediato fue reiniciar el
> contenedor; el permanente es éste.

---

## Las decisiones

### La carrera se cierra en el `WHERE`, no en Kotlin

```sql
UPDATE invitacion_registro
   SET usos = usos + 1
 WHERE codigo = ? AND revocada_en IS NULL
   AND (expira_en IS NULL OR expira_en > now())
   AND usos < usos_max
```

Leer el contador, comprobarlo y escribirlo desde el servidor deja una ventana
entre la lectura y la escritura, y dos personas canjeando el último uso a la
vez la encuentran. Así la condición y el incremento son **la misma operación**,
y el `CHECK` de la tabla es la segunda red.

El canje va **dentro** de la transacción que crea la cuenta: si el registro
falla después —un username ya cogido— la reserva se deshace con todo lo demás,
y el código no se queda gastado por un alta que nunca ocurrió.

### Un solo mensaje para cuatro fallos

"No existe", "caducada", "revocada" y "agotada" contestan lo mismo.
Distinguirlos ayudaría a quien se equivocó de letra, y también a quien está
probando códigos a ciegas: le diría cuáles existen. Quien tiene una invitación
de verdad no necesita el matiz — la suya funciona.

### El alfabeto no lleva `0`, `O`, `1`, `I` ni `l`

Un código se dicta por teléfono y se copia a mano de una captura, y esas cinco
son las que se confunden. Doce caracteres de treinta y uno son unos **59 bits**:
adivinar uno exige más intentos de los que el limitador por IP deja hacer en
varias vidas.

Se normaliza en los dos lados —mayúsculas, sin espacios ni guiones—. El
servidor lo hace por corrección; la app además lo hace **mientras se escribe**,
para que se vea igual al que le pasaron y no parezca que escribió otra cosa.

### Revocar no borra

Borrar la fila se llevaría por delante, en cascada, el rastro de quién entró
con ese código — que es exactamente lo que hace falta conservar cuando se
revoca algo. Se marca y se queda.

Revocar tampoco expulsa a nadie: las cuentas que ya entraron siguen como
estaban. El diálogo de la app lo dice con ese número delante.

### `invitacion_registro`, no `invitacion`

`invitacion` **ya existe** y es otra cosa: la de entrar a una conversación. Dos
tablas con el mismo nombre para dos permisos distintos es como se acaba
autorizando lo que no era.

El mismo error se cometió en la capa de rutas: la primera versión llamó
`RUTA_INVITACIONES` a la nueva, chocando con la de grupos. Lo cazó el
compilador, pero sólo porque las dos están en el mismo módulo. Ahora es
`RUTA_INVITACIONES_REGISTRO`, bajo `/v1/registro/`.

### 404 y no 403 a quien no es staff

Un 403 confirmaría que la ruta existe y que este servidor usa invitaciones.
Eso ya es información para quien está probando rutas a ciegas.

---

## La app

El módulo vivía entero en el servidor: se podían crear códigos con `curl` y
nada más. Eso sirve para probarlo, no para usarlo.

### El campo, en la pantalla de registro

Se pregunta `GET /v1/registro/modo` al abrir la pantalla —no al pulsar "crear
cuenta"— para que el campo ya esté cuando haga falta, en vez de aparecer de
golpe debajo del dedo.

**Se pregunta al servidor en vez de compilarlo en la app** porque el mismo APK
sirve a despliegues distintos: el público no pide código y el de un equipo sí.
Un campo fijo obligaría a compilar dos versiones.

Ante un fallo de red se asume **abierto**. Es la respuesta menos dañina de las
dos: si el servidor sí pide código y aquí se asume que no, el campo no aparece
y el registro falla con el mensaje del servidor, que lo explica. Al revés se le
plantaría un campo obligatorio a quien no tiene ninguno, y ahí no hay salida.

### La pantalla del staff

`Panel → Invitaciones`, con el resto de administrador (80), que es lo que pide
el servidor.

- **El recién creado se destaca arriba.** En la lista, ordenada por fecha, el
  nuevo es la primera fila y se confunde con las demás.
- **Copiar y compartir desde ahí**, porque el paso siguiente a crear un código
  es siempre mandárselo a alguien. Dictarlo es el peor caso, no el normal.
- **El texto que se comparte lleva instrucciones.** Quien recibe doce letras
  sueltas no sabe qué son ni dónde se ponen; si el trabajo de explicarlo recae
  en quien invita, cada uno lo explica distinto y algunos no lo explican.
- **Avisa si el servidor está abierto.** Se pueden crear códigos con el
  registro abierto: funcionan, pero no hacen falta, y quien los reparte se
  queda creyendo que el servidor está cerrado. Es el único sitio donde alguien
  lo va a leer.
- **No ofrece "revocar" en lo que ya está muerto**, porque el servidor
  contesta 404 y sería ofrecer un botón que falla.

---

## Las pruebas

`pruebas/invitaciones.mjs` corre **en los dos modos** y hace lo que puede en
cada uno. Cuando el flujo del canje no se prueba, **lo dice**: una suite que
pasa sin haber probado lo importante es peor que una que falla.

```
servidor ABIERTO      →   7 pasan, 0 fallan   (+ el aviso de que falta el canje)
servidor INVITACION   →  28 pasan, 0 fallan
```

Para el completo:

```bash
WTFUCK_PUERTO=8302 WTFUCK_PROPIETARIO=jefe_inv WTFUCK_REGISTRO=invitacion \
  server/build/install/server/bin/server
WTFUCK_BASE=http://127.0.0.1:8302 node pruebas/invitaciones.mjs
```

### Dos cosas que la suite declara en voz alta

**Entra con la cuenta del propietario si ya existe.** Su nombre lo fija
`WTFUCK_PROPIETARIO` al arrancar el servidor, así que no se puede aleatorizar
como el resto: en la segunda corrida contra la misma base el registro devuelve
409 y la suite entera se caía — no por un defecto del servidor, sino por exigir
una base virgen. Una suite que sólo pasa la primera vez deja de correrse.

**Y avisa de que, en ese caso, una comprobación no concluye.** "El propietario
es administrador desde que se registra" sólo lo demuestra si la cuenta se acaba
de crear; si ya existía, `sembrarPropietario` la promovió al arrancar y el 200
no distingue las dos cosas. La suite lo imprime en vez de callarlo.

### Lo que encontró la prueba de acceso ajeno

Añadir las rutas hizo fallar `ajeno.mjs` y `ajeno-lectura.mjs`, que exigen que
**toda** ruta del servidor esté cubierta por un ataque o eximida con un motivo
escrito. Funcionó exactamente como debía: tres rutas nuevas, tres huecos
señalados por nombre.

| Ruta | Qué se exige |
|---|---|
| `GET /v1/registro/modo` | Eximida: pública a propósito, la pregunta quien aún no tiene cuenta |
| `GET /v1/registro/invitaciones` | Nivel 80. La lista dice quién invitó a quién |
| `DELETE /v1/registro/invitaciones/{}` | Una cuenta de a pie no puede anular el código de otro |

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`V41__invitaciones_de_registro.sql`](../../../server/src/main/resources/db/V41__invitaciones_de_registro.sql) | Las dos tablas |
| [`Invitaciones.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Invitaciones.kt) | Generar, canjear, listar, revocar |
| [`Repo.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Repo.kt) | El canje dentro de la transacción del alta |
| [`Api.kt`](../../../protocol/src/main/kotlin/com/wtfuck/protocol/Api.kt) | Rutas y tipos |
| [`AuthPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/AuthPantalla.kt) | El campo del código |
| [`InvitacionesPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/InvitacionesPantalla.kt) | La pantalla del staff |
| [`invitaciones.mjs`](../../../pruebas/invitaciones.mjs) | La suite |

> **Ojo al agregar una migración:** `Db.kt` lleva una **lista explícita**, no
> escanea el directorio. Un `.sql` nuevo que no se anote ahí no se aplica y
> nadie se entera hasta que algo falla por una tabla que no existe.
