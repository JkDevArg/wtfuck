# Llamadas de grupo · módulo AF

Recorrido completo en dos emuladores, con el guion
`pruebas/llamada-de-grupo.sh`: abrir el grupo, elegir a quién, llamar en vídeo,
contestar en el otro aparato.

Se hace con un guion y no a mano porque el paso que importa —contestar— hay que
darlo antes de que el timbre se agote, y a mano, entre dos ventanas, no llega.

---

## 1 · El botón que no existía

![El grupo, con el botón de llamar](1-boton-en-el-grupo.png)

En un grupo **no había botón de llamar**, y el comentario que lo ocultaba decía
la verdad a medias:

> *"En un grupo la llamada en malla está limitada a 4 y no hay interfaz para
> elegir a quién, así que ofrecer el botón sería prometer algo que la pantalla
> no cumple."*

Era cierto. El servidor era peor: llamaba a **todos** los de la conversación y
rechazaba la llamada entera si eran más de cuatro. O sea que **un grupo de ocho
no podía tener una llamada nunca**, ni entre tres de sus miembros. El tope de la
malla se estaba aplicando al grupo en vez de a la llamada.

## 2 · Elegir a quién

![La hoja de invitados](2-hoja-vacia.png)
![Dos elegidos, tres de cuatro](3-dos-elegidos.png)

El tope **se dice, no se descubre**: se explica arriba y las filas de más se
deshabilitan en vez de desaparecer. Y se cuenta `1 + elegidos`, porque quien
llama también ocupa un sitio — olvidarlo es cómo se llega a una llamada de cinco
que el servidor rechaza *después* de que la persona ya eligió.

Audio o vídeo se decide **aquí**, junto con la gente: es una misma decisión.

## 3 · Suena sólo a quien se eligió

![Llamando](4-llamando.png)
![Suena en el otro aparato](5-suena-en-el-otro.png)

Quien recibe ve **"llamada de grupo · Equipo seguridad"**. Antes veía sólo
`@tatiana` y no tenía cómo saber que era una llamada de grupo ni de cuál:
contestar sin saber quién más está del otro lado no es lo mismo que contestarle
a una persona.

## 4 · Conectada, con vídeo de verdad

![Quien llamó](6-conectada-quien-llamo.png)
![Quien contestó](7-conectada-quien-contesto.png)

Las dos pantallas dicen cosas **distintas**, porque la pregunta es distinta:

| | Tercera línea | Por qué |
|---|---|---|
| Quien llama | `con joaquin, rocio` | Ya sabe a qué grupo: el título **es** el grupo. Lo que no ve es a quién eligió |
| Quien recibe | `llamada de grupo · Equipo seguridad` | Ve un nombre de persona y no sabe de dónde sale |

Estado en la base durante la captura:

```
llamada:  en_curso
          dentro   tatiana     (llamó)
          dentro   joaquin     (contestó)
          sonando  rocio       (todavía suena)
```

La tercera **sigue sonando** con la llamada ya en curso. Antes no podía: que uno
rechazara mataba la llamada entera (ver AF.5 más abajo).

---

## Lo que se arregló por el camino

Nada de esto salió de leer el código. Salió de hacer la llamada.

### AF.5 · Un rechazo cortaba el timbre de los demás

Se llamó a dos, el primero declinó y la llamada terminó entera con el segundo
todavía sonando. La condición para seguir viva era:

```kotlin
if (dentro >= 2 && l.estado == "en_curso") return@tx emptyList()
```

Mientras la llamada **sonaba**, cualquier rechazo la mataba. Ahora son dos
cuentas y las dos hacen falta:

```kotlin
if (dentro >= 1 && vivos >= 2) return@tx emptyList()
```

- `dentro >= 1` — si no queda nadie dentro no hay llamada a la que entrar. Es lo
  que hace que **cancelar siendo quien llama** la termine, aunque los demás
  sigan sonando.
- `vivos >= 2` — una llamada de uno no es una llamada. Cuenta también a los que
  suenan, porque todavía pueden contestar.

### AF.6 · Abrir la app mataba la llamada entrante

El peor de los dos. Al arrancar, la app pedía `/en-curso` y **colgaba lo que
viniera**. El razonamiento era correcto para una llamada en la que uno ya
estaba: las sesiones WebRTC murieron con el proceso y no se retoman.

Pero el servidor devuelve también **la llamada que te está sonando**. Con un
aviso de llamada, abrir la app —que es lo que hace cualquiera— la mataba antes
de que sonara. En el log:

```
Llamadas: Habia una llamada abierta al arrancar: se cierra
```

Ahora decide por `miEstado`, no por que exista la llamada: `dentro` se cierra,
`sonando` **no se toca** y la oferta cifrada que sigue en el buzón hace sonar el
teléfono al llegar.

### AF.7 · El barrido cerraba la llamada y no avisaba a nadie

`cerrarTimbresVencidos` cerraba la llamada en la base y se callaba. La pantalla
del que llamaba se quedaba en *"Llamando…"* para una llamada que ya no existía,
y el otro teléfono seguía sonando.

> La prueba que existía miraba la **fila**. Una prueba que afirma sobre un
> estado no afirma sobre el aviso.

### AF.8 · El TURN de desarrollo apuntaba al propio emulador

`turn:127.0.0.1:3478`. Desde un emulador de Android, `127.0.0.1` es el emulador
mismo, así que una llamada entre dos emuladores **nunca podía relevar** y el
medio se quedaba en `ICE CHECKING` para siempre. Ahora es `10.0.2.2`, que es
como el emulador ve la máquina anfitriona.

Con eso, el vídeo de la captura 7 es vídeo de verdad viajando por el relevo:

```
MotorWebRtc: ICE: CHECKING
MotorWebRtc: ICE: CONNECTED
```

---

## Cómo se comprobó que las pruebas sirven

Siete guardias del servidor, rotos **de a uno** y cada uno con su servidor
recompilado. Dos defectos a la vez se tapan entre sí — ya pasó en el módulo AD.

| Roto | Pruebas que fallan |
|---|---|
| El filtro de destinos al iniciar | 2 |
| Que el invitado esté en la conversación | 1 |
| El recorte de destinos al contestar | 1 |
| Los bloqueos en una llamada dirigida | 3 |
| La regla de cuándo sigue viva | 6 |
| `miEstado` en `/en-curso` | 3 |
| El aviso del barrido | 3 |

Y una lección sobre el propio arnés: la primera versión del guion de validación
mandaba la salida de Gradle a `Out-Null`, así que un defecto **que no
compilaba** dejaba el jar anterior —el bueno— y la suite pasaba entera. El
guion decía *"ninguna prueba lo caza"* cuando el defecto nunca se había
instalado.

> Una validación que no comprueba que el defecto llegó a instalarse no valida
> nada.

---

## 8 · Uno rechaza y la llamada sigue

![Uno rechaza y la llamada sigue](8-uno-rechaza-y-la-llamada-sigue.png)

`llamando a rocio · joaquin no entró`, con la llamada viva:

```
dentro   tatiana     (llamó)
rechazo  joaquin     (dijo que no)
sonando  rocio       (todavía suena)
```

Esta captura salió de **buscar el defecto gemelo**. El módulo AF arregló en el
servidor que un rechazo matara la llamada entera; al probarlo se vio que la
pantalla de quien llamó volvía al chat igual, con la llamada viva en la base.
El cliente hacía lo mismo por su cuenta: `finEntrante` llamaba a `limpiar()`
sin mirar nada, así que **el primer "fin" que llegara cerraba la pantalla**.

> Arreglar una instancia de un defecto no es arreglar el defecto.

Ahora se va ese aparato y la llamada sigue mientras quede alguien. Y el
recuento mira **quién sigue en la llamada**, no los motores WebRTC: mientras
alguien suena todavía no hay motor de su lado, y cerrar ahí sería colgarle a
quien aún podía contestar.

---

## Lo que NO se probó

- **Tres aparatos a la vez.** Hay dos emuladores, así que la malla se probó con
  dos conexiones reales y con el tercero sonando. El cierre de malla —la regla
  de desempate del *glare*— está cubierto por `MallaTest`, que simula la llamada
  entera para 2, 3 y 4 participantes y para cada uno como iniciador.
- **Más de cuatro.** Es el tope declarado desde el módulo K y el servidor lo
  rechaza; la hoja no deja elegir más.
