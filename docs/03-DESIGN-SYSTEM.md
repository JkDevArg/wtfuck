# wtfuck — Sistema de diseño

Paleta entregada por el proyecto:
`#6CF8F6` · `#F7B76D` · `#F7876D` · `#677878` · `#787067`

---

## Hallazgo que condiciona todo

Los contrastes se midieron, no se estimaron (WCAG 2.1, sobre `#161D1D`):

| Color | Contraste | Veredicto |
|---|---|---|
| `#6CF8F6` cian | **13.36:1** | Excelente. Texto y superficie |
| `#F7B76D` ámbar | **9.73:1** | Excelente |
| `#F7876D` coral | **7.04:1** | Bueno |
| `#677878` slate | **3.69:1** | ❌ **No alcanza 4.5:1 — no usar como texto** |
| `#787067` taupe | **3.51:1** | ❌ **No alcanza 4.5:1 — no usar como texto** |

Conclusión: **tienes tres acentos y dos neutros, no cinco colores de texto.**
Los dos neutros funcionan como bordes, contornos y superficies. Para texto se
deriva una escala aclarando slate hacia el blanco, conservando el tono:

```
TextoPrimario    #DCE3E3   13.15:1
TextoSecundario  #95A1A1    6.43:1
TextoTerciario   #7E8C8C    4.90:1   <- mínimo aceptable
Borde            #677878    3.69:1   <- solo líneas, nunca letras
```

Segunda regla no negociable **en el tema oscuro**: texto sobre los acentos
siempre oscuro. Blanco sobre cian da **1.28:1** — literalmente ilegible. Sobre
cian, ámbar o coral siempre va `#0E1313`. En el tema claro la regla se
**invierte**; ver abajo.

---

## El tema claro: otra escala, no la misma dada vuelta

El tema claro estuvo declarado fuera de alcance durante todo el proyecto, y la
razón era exactamente el hallazgo de arriba: **`#6CF8F6` da 1.28:1 sobre
blanco.** Como texto es invisible; como relleno con texto blanco encima,
también. La paleta de marca es una escala pensada para fondo oscuro y no se
puede reusar tal cual.

Tenerlo exigió **rederivar los acentos midiendo**. Los valores son los primeros
que pasan 4.5:1 contra las tres superficies claras **y** 4.5:1 con texto blanco
encima cuando se usan como relleno:

| Token | Claro | vs `#E8EFEF` | vs `#FFFFFF` | vs `#F7FAFA` | blanco encima |
|---|---|---|---|---|---|
| Cian | `#0B6E6D` | 5.11 | 6.06 | 5.86 | **6.06** |
| Ámbar | `#8A5300` | 5.33 | 6.33 | 6.12 | **6.33** |
| Coral | `#B3301A` | 5.28 | 6.26 | 6.05 | **6.26** |
| Slate (borde) | `#7E8E8E` | 3.02 | 3.42 | 3.32 | — |

```
BgBase           #E8EFEF   el fondo de la app. NO es blanco: ver abajo
BgSurface        #FFFFFF   tarjetas
BgElev           #F7FAFA   hojas, menús, burbuja ajena
TextoPrimario    #0F1717   15.32:1
TextoSecundario  #3C4A4A    7.80:1
TextoTerciario   #566565    5.14:1
TextoSobreAcento #FFFFFF    blanco, al revés que en oscuro
```

Tres decisiones que no son obvias:

1. **`TextoSobreAcento` se invierte entre temas.** En oscuro el acento es
   luminoso y pide tinta oscura; en claro es oscuro y pide tinta blanca. Un solo
   valor para los dos dejaría ilegible la mitad de los botones. Es la diferencia
   concreta entre "otra escala" y "la misma invertida".

2. **El fondo claro no es blanco.** En oscuro las tarjetas se distinguen del
   fondo porque son más claras. En claro, una tarjeta blanca sobre fondo blanco y
   **sin sombra** es invisible, y este diseño no usa sombras. `#E8EFEF` deja la
   tarjeta a 1.165 del fondo, que es lo que hace que se vea el bloque.

3. **El borde pasa el umbral de componente, no el de texto.** WCAG pide 3:1 para
   elementos de interfaz y 4.5:1 para letras: un divisor no es texto. El primer
   gris que se probó (`#A8B8B8`) daba 2.06 y al sol las tarjetas desaparecían;
   `#7E8E8E` da 3.42.

La semántica **no** cambia entre temas: el cian sigue queriendo decir "va bien",
el ámbar "esperando" y el coral "se rompió". Lo que cambia es el valor, no el
significado.

> Los contrastes de esta tabla están calculados con la fórmula de luminancia
> relativa de WCAG 2.1, no estimados a ojo.

---

## Fondos

Derivados de slate, desaturado y oscurecido, para que el gris del chat sea
familia del acento y no un gris neutro genérico.

```
BgBase     #0E1313   fondo de la app
BgSurface  #161D1D   tarjetas, barras, lista de chats
BgElev     #1F2828   burbuja ajena, menús, hojas modales
```

---

## Semántica: un color, un significado

El usuario aprende tres colores. No se reutilizan para nada más.

| Color | Significa | Dónde aparece |
|---|---|---|
| **Cian** `#6CF8F6` | Va bien | Burbuja propia, botón de enviar, "conectado", entregado |
| **Ámbar** `#F7B76D` | Esperando | **Mensaje en cola sin red** — el estado visible de `msg off`. Reconectando |
| **Coral** `#F7876D` | Algo se rompió | Falló el envío, clave cambiada, dispositivo sin verificar, salir del grupo |
| **Slate** `#677878` | Inerte | Bordes, divisores, silenciado, sin conexión |

El ámbar es el color más importante del producto. Es lo que hace que `msg off`
se **sienta** en vez de ser una promesa: escribes sin señal, la burbuja aparece
en ámbar con el mensaje ya guardado, y al reconectar vira a cian. El usuario ve
que nada se perdió.

---

## Burbujas

```
Propia   fondo Cian #6CF8F6     texto #0E1313    14.62:1
         esquinas 16dp, la inferior derecha 4dp

Ajena    fondo BgElev #1F2828   texto #DCE3E3    11.59:1
         borde 1dp Slate #677878
         esquinas 16dp, la inferior izquierda 4dp

Pendiente  fondo Cian al 35% + borde 1dp Ámbar
           reloj ámbar en la esquina
```

El borde en la burbuja ajena es lo que le da trabajo al slate: aporta
separación sin necesidad de contraste de texto, que es justo lo que ese gris
no puede dar.

---

## Tipografía

Coherente con tu línea habitual, y aquí además funcional:

```
Display / marca   Rajdhani       titulares, splash, nombre de la app
Interfaz          Barlow         nombres, menús, botones
Cuerpo del chat   Barlow         lo que más se lee: neutro y legible
Monoespaciada     IBM Plex Mono  huellas de clave, safety numbers, IDs
```

La monoespaciada no es decoración: las huellas de verificación se comparan
carácter por carácter, y sin ancho fijo es incómodo y propenso a error.

Escala: 28 / 22 / 16 / 14 / 12 sp. El cuerpo del mensaje a 16sp.

---

## Espaciado y forma

```
Rejilla       4dp
Ritmo         8 · 12 · 16 · 24
Radio         burbuja 16dp (4dp en la esquina de la cola)
              tarjeta 12dp · campo 8dp
Toque mínimo  48dp
```

---

## Reglas de accesibilidad

1. **El color nunca es el único portador de información.** Pendiente lleva
   ámbar **y** un ícono de reloj; fallo lleva coral **y** un aspa. Un usuario
   con daltonismo tipo deuteranopia no distingue ámbar de coral.
2. Contraste mínimo 4.5:1 para texto. Los tokens ya lo garantizan.
3. Área táctil mínima 48dp, **medida y no supuesta**. Se comprueba volcando
   el árbol de accesibilidad del emulador; así aparecieron una opción de
   encuesta en 34 dp y una pastilla de reacción en 32. Cuando el dibujo tiene
   que ser más chico que 48 —doce opciones de encuesta no entran de otro modo—
   se usa `minimumInteractiveComponentSize`, que sube el área sin tocar el
   dibujo.

4. **Lo que a la vista es un bloque se lee como un bloque.** Una fila de la
   lista de chats o una burbuja se fusionan en un nodo con su descripción; lo
   que cambia —el estado de envío— va en `stateDescription`, para que se
   anuncie al cambiar. La excepción es un bloque que se **opera**: una encuesta
   no se fusiona, porque fusionarla borra sus controles.
5. La app tiene **los dos temas**, con la preferencia en el aparato y tres
   valores: como el sistema, siempre claro, siempre oscuro. El defecto sigue
   siendo oscuro, que es el tema con el que se diseno todo.

   Durante casi todo el proyecto fue solo oscuro, y no por falta de tiempo: un
   tema claro con esta paleta exigia volver a derivar la escala entera, porque
   el cian sobre blanco cae a 1.28:1. Eso es lo que se hizo; los valores estan
   arriba, medidos.
