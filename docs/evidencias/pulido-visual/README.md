# Módulo AE · Pulido visual

*"¿Qué más hay para mejorar, estéticamente?"*

Se miraron las cuatro pestañas principales una por una. Lo que salió no fue
cuestión de gusto: seis cosas concretas, y una de ellas era un defecto
funcional.

## 1. La app entera estaba sin tildes

**266 palabras en 32 archivos.** `Diagnostico`, `todavia`, `telefono`,
`numero`, `mas`, `contrasena`, `sesion`, `camara`. Y era **inconsistente**: las
pantallas nuevas (comunidades) sí las tenían, así que la app parecía
medio traducida.

Es lo que más pesaba visualmente y lo que menos se nota al escribir el código.

Se hizo con un script, con **dos guardias que nacieron de fallos reales**:

- **Sólo texto, nunca un identificador.** En la interfaz hay cadenas que son
  rutas, claves y tipos —`"canal"`, `"grupo"`, `"mi-cuenta"`—. Cambiar una por
  su versión con tilde rompe el contrato **en silencio**: compila igual y falla
  al comparar. Regla: se traduce si tiene un espacio (es una frase) o si
  empieza en mayúscula (es una etiqueta).
- **Las interpolaciones son código.** La primera versión no lo distinguía y
  produjo cinco errores: `Unresolved reference 'publicación'`, de
  `"${publicacion.autor}"`. **Los cazó el compilador**, que es exactamente
  para lo que sirve, pero el script ya no los escribe.

Y dos pruebas de accesibilidad fallaron —afirman sobre el texto que se anuncia—
lo cual es la suite haciendo su trabajo. Se actualizaron sus expectativas.

## 2. El mismo canal dibujado de dos maneras en la misma pantalla

| | Qué muestra |
|---|---|
| `01-canales-antes.png` | Arriba, "Mis canales" con el icono de **dos personas**. Abajo, "Descubrir" con el **megáfono**. El mismo canal, dos iconos. |
| `02-canales-despues.png` | Los dos con megáfono, el mismo tamaño y la misma sangría. |

`FilaMiCanal` pasaba `esGrupo = true` a un canal. `Avatar` ya tenía la bandera
`esCanal`; la fila pasaba la otra.

## 3. Dos maquetaciones, una encima de la otra

Las filas de "Descubrir" dibujaban **su propio círculo a mano** —46 dp y
siempre cian— mientras "Mis canales" usaba el `Avatar` compartido —44 dp y
color derivado del nombre—. Dos implementaciones de lo mismo en la misma
pantalla: se leía como dos listas de dos aplicaciones distintas.

Ahora las dos usan el `Avatar` compartido.

Y los dos encabezados de sección eran de colores distintos —uno cian, otro
gris— sugiriendo una jerarquía que no existe. Ahora son iguales.

## 4. El vacío de Contactos

| | Qué muestra |
|---|---|
| `03-contactos-antes.png` | Sin icono, y con el título centrado pero el cuerpo **alineado a la izquierda**: el bloque se veía torcido. |
| `04-contactos-despues.png` | Icono, título y cuerpo centrados, y el botón. |

El `Column` estaba centrado, pero al texto le faltaba `textAlign = Center`, así
que con dos líneas la segunda se iba a la izquierda. Y era el único vacío de la
app sin icono.

## 5. El botón flotante tapaba la última conversación

| | Qué muestra |
|---|---|
| `05-chats-antes.png` | La lista termina justo debajo del botón "Nuevo". |
| `06-chats-despues.png` | Con hueco al final. |

`LazyColumn(Modifier.fillMaxSize())` sin `contentPadding`. Con pocos chats no
se nota porque la lista no llega hasta abajo; con la lista llena, **la última
conversación queda debajo del botón y no se puede abrir**.

## 6. "24 h" en la columna de las horas

La fila de historias decía `24 h` alineado a la derecha, que es exactamente
donde las filas de abajo ponen `Ayer` y `Martes`. Se leía como una marca de
tiempo. Ahora dice **"dura 24 h"**: una palabra que sobra en cualquier otro
sitio y aquí es la que desambigua.

## Y uno que no era estético

**El canal de anuncios de una comunidad abría en la pantalla de chat.** Mi
propia pantalla de comunidades navegaba a `chat/$id` para todo, y un canal
abierto ahí no tiene muro, ni reacciones, ni comentarios — justo lo que hace
que un canal sea un canal.

Ahora son dos callbacks: `onAbrirCanal` va a `canal/$id` y `onAbrirChat` a
`chat/$id`. Un solo callback para dos destinos distintos compila, navega, y
lleva al sitio equivocado.

## Cómo se tomaron

```
G:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 exec-out screencap -p > captura.png
```

**1707 pruebas en verde**, sin cambios de conteo: dos de accesibilidad
cambiaron de expectativa, ninguna se agregó ni se quitó.
