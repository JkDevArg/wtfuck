# El texto de la burbuja propia no se leía

Lo reportó el usuario mirando la pantalla, que es como se encuentran estas
cosas.

| Antes | Después |
|---|---|
| ![antes](1-antes.png) | ![después](2-despues.png) |

## Qué pasaba

`textoConMenciones` tiene un atajo deliberado: **sin menciones devuelve el
texto sin ningún tramo de color**, para no construir un `AnnotatedString` por
cada mensaje de la lista que más se desplaza. La decisión es correcta.

Lo que faltaba era su consecuencia —que entonces el color lo tiene que poner
el `Text`— y el `Text` no lo ponía:

```kotlin
Text(
    textoConMenciones(m.texto, miUsuario, colorTexto, colorMencion),
    fontSize = 16.sp,
)
```

`colorTexto` se calculaba bien, se pasaba a `textoConMenciones`, y en el caso
común se tiraba. El color caía al de contenido por defecto de Material: claro,
sobre una burbuja cian.

Contraste medido: **1.2:1**. La WCAG pide 4.5:1.

## Por qué sobrevivió tanto

Por dos razones que se reforzaban.

**Sólo se veía en media pantalla.** En las burbujas recibidas —fondo oscuro—
el color por defecto coincide con el correcto. El mismo código estaba bien de
un lado y roto del otro, así que leerlo no delataba nada.

**Y la burbuja parecía correcta de un vistazo.** La hora y los checks sí usan
`colorTexto`, y se veían negros sobre el cian como debían. Sólo el cuerpo del
mensaje estaba mal, y el ojo pasa por encima de un texto que "casi" se lee.

Se confirmó midiendo los píxeles en vez de razonando: la hora daba
`(14, 19, 19)` —el color correcto— y el cuerpo, un gris claro lavado. Dos
colores distintos dentro de la misma burbuja era la prueba de que el problema
no era el token sino quién lo aplicaba.

## Lo que fijan las pruebas

No el color del `Text`, que no se puede leer desde la JVM. Las dos cosas que
sí se pueden comprobar y que juntas cierran el caso:

- **El color por defecto NO se lee sobre el cian** (contraste < 2:1). Fija que
  es una elección equivocada, no una alternativa aceptable: si alguien vuelve
  a dejar un `Text` sin `color` dentro de la burbuja propia, el resultado es
  ese número.
- **El atajo devuelve texto sin color propio.** No para impedir que cambie,
  sino para que quien lo lea sepa que el color es responsabilidad de quien
  llama — y si algún día devolviera un tramo con color, la prueba falla y
  quien la arregle se encuentra con la explicación.

Más los dos lados del caso con menciones, para que "quitar el color del atajo"
no pueda degenerar en "no colorear nunca" sin que nada lo note.
