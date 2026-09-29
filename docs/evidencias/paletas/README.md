# Color de acento elegible

![El selector](selector.png)

## Por qué salió barato

El tema ya estaba centralizado de una forma que jugó a favor. Ninguna pantalla
tiene colores propios: todas leen tokens que son *getters* sobre una variable
de estado.

```kotlin
val Cian: Color get() = if (claro) CianCla else CianOsc
```

Añadir "acento elegido" fue **añadir una dimensión más a esos mismos getters**.
Las 58 pantallas se enteraron solas, sin tocar ninguna — que normalmente es el
90% del trabajo de un cambio así.

```kotlin
val Cian: Color get() = if (claro) paleta.acentoCla else paleta.acentoOsc
```

## Qué cambia y qué no

**Solo el primario**: la burbuja propia, los botones, "conectado".

`Ambar` y `Coral` **se quedan fijos** porque no son decoración, son
**significado**: ámbar es "pendiente", coral es "error". Si el acento fuera
naranja y el ámbar también, un aviso de error y uno de espera se verían igual
— y la persona lleva toda la app aprendiendo que no lo son.

Se ve en la captura: con el acento en violeta, *"Sin teléfono verificado no se
puede recuperar"* sigue en ámbar.

Los fondos y los textos tampoco cambian, por lo mismo que no cambian entre
pantallas: son el lienzo, no la marca.

---

## Por qué una lista cerrada y no un selector RGB

Porque este proyecto **mide** el contraste en vez de elegirlo a ojo:

- Los acentos claros se rederivaron midiendo, no eligiendo.
- `ContrasteBurbujaTest` exige 4.5:1.
- Dos neutros de la paleta se usan solo como borde porque dan 3.69 y 3.51.

> Un selector de color libre rompe esa garantía **en silencio**. Alguien elige
> un amarillo pálido, el texto de su propia burbuja deja de leerse, y ningún
> test lo detecta porque el color lo puso el usuario.

Con una lista cerrada, cada entrada pasa por la misma medida que todo lo demás.

### Los números, calculados

Los cuatro contrastes de cada paleta se **calcularon** antes de escribir ni un
color, no se estimaron:

| Paleta | Acento/sup. oscura | Tinta/acento | Acento/sup. clara | Tinta/acento |
|---|---|---|---|---|
| Cian | 13.36 | 14.62 | 6.06 | 6.06 |
| Azul | 9.16 | 10.03 | 5.75 | 5.75 |
| Violeta | 8.95 | 9.79 | 7.34 | 7.34 |
| Verde | 10.89 | 11.92 | 6.28 | 6.28 |
| Naranja | 9.50 | 10.40 | 6.33 | 6.33 |
| Rosa | 8.91 | 9.76 | 7.06 | 7.06 |

El peor de los 24 es **5.75:1** sobre un mínimo exigido de 4.5. Y la prueba los
**recalcula**: quien agregue una paleta a ojo se entera al correr los tests, no
cuando alguien no pueda leer su propia burbuja.

---

## Dos detalles que lo habrían dejado a medias

**El `remember` del esquema de Material.** Estaba cerrado solo sobre `esClaro`.
Sin añadir la paleta a la clave, los tokens propios —que son getters— habrían
cambiado al instante y los componentes de Material3 se habrían quedado con el
color viejo: media pantalla en el color nuevo y media en el anterior.

**La tinta sale de la paleta, no es fija.** Cada acento pide la suya: uno
luminoso pide tinta negra, uno oscuro pide blanca. Un solo valor para todos
dejaría ilegible la mitad.

## El nombre sigue siendo `Cian`

Renombrarlo a `Acento` habría tocado 58 archivos y cientos de líneas para no
cambiar ni un píxel — un diff enorme donde no se vería el cambio real. El
nombre miente un poco cuando la paleta es rosa; el KDoc no.

---

## Verificado **en el emulador**

Primera vez en toda la sesión: el AVD `Pixel9_API36` estaba corrupto (su
`PackageManager` no lanzaba la app, `df /data` venía vacío), pero
**`Pixel10_API37` está sano**. Se registró una cuenta contra el servidor local
y se recorrió la app.

![La lista de chats con el acento aplicado](chats-naranja.png)

| Qué | Resultado |
|---|---|
| El selector se dibuja y marca la elegida | ✅ |
| Cambiar de paleta repinta **toda** la app al instante | ✅ naranja → violeta |
| El check sobre el círculo se lee | ✅ tinta calculada, no fija |
| Ámbar y coral **no** siguen la paleta | ✅ visible en la captura |
| La barra inferior, el FAB, las pestañas, los iconos | ✅ todo siguió |
| `ContrasteBurbujaTest` con las 6 paletas | ✅ 24 medidas |
| Suite unitaria del app | ✅ **497 pruebas, 0 fallos** |

De paso, y sin buscarlo, se verificaron en pantalla otras dos cosas de esta
sesión: el botón **"Perdí mi teléfono"** aparece solo en modo ingreso, y el
recordatorio **"Nunca has hecho una copia"** sale en el perfil.

## Lo que NO está verificado

- **El tema claro con las paletas nuevas no se vio.** Los números están
  calculados y la prueba los comprueba, pero nadie ha mirado una pantalla
  clara en violeta.
- **Solo se probaron naranja y violeta** de las seis.
- **No se probó en un teléfono real**, solo en el emulador.
- **Un hallazgo lateral:** el emulador produjo una huella de hardware que ya
  pertenecía a una cuenta vieja de la base de desarrollo (`tatiana`), y hubo
  que revocar ese dispositivo para poder registrar. No afecta a producción
  —allí se exige TEE o StrongBox— pero sugiere que en modo `SOFTWARE_DEV` la
  huella puede no ser única entre emuladores. Queda anotado, sin investigar.
