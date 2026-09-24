# Módulo AA · Evidencias

Siete capturas de emulator-5554 (1080×2424 @420 dpi, API 37). Salieron de una
pregunta del usuario: *"para las páginas cuando hay comentarios no veo los
comentarios ¿por qué?"*.

La respuesta no era la que parecía.

## Lo que se veía, y lo que pasaba de verdad

| | Qué muestra |
|---|---|
| `01-antes-contador-sin-contenido.png` | La tarjeta del canal diciendo **"1 comentario"**. Era texto, sin nada detrás: no había forma de abrirlo, de escribir uno ni de reaccionar. |
| `02-la-contradiccion.png` | La hoja de comentarios recién hecha, abierta sobre esa misma publicación: **"Todavía nadie comentó esta publicación"**. Las dos cosas eran ciertas a la vez. |

**El texto de los comentarios no existía en ninguna parte.** No era una pantalla
que faltaba.

Un canal público **no reparte sobres** —es lo que le permite escalar: con diez
mil suscriptores, un sobre por dispositivo serían diez mil filas por
publicación—. Y el comentario se mandaba como mensaje cifrado, por el buzón.
En un grupo eso funciona; en un canal público no hay a quién entregarle el
sobre. Quedaba la fila de metadatos, **el contador subía**, y el cuerpo se iba
al vacío.

Medido en la base antes de tocar nada, sobre las 196 filas del canal de pruebas:

```
 autor   | es_comentario | sobres | cuerpo
---------+---------------+--------+--------
 fasfqb1 | f             |      0 |      1   ← publicación, con cuerpo
 fbsfqb1 | t             |      0 |      0   ← comentario: ni sobre ni cuerpo
```

Cada comentario, sin excepción: cero sobres y cero cuerpo guardado.

## El arreglo

| | Qué muestra |
|---|---|
| `03-contador-honesto.png` | La misma tarjeta después. Dice **"Comentar"**, no "1 comentario": ese comentario viejo perdió su texto para siempre, así que ya no se cuenta. Un contador que cuenta cosas que nadie puede ver no le sirve a nadie. |
| `04-comentario-ida-y-vuelta.png` | Un comentario escrito desde el emulador —`@joaquin`, 19:02— guardado y vuelto a leer. |
| `05-contador-al-dia.png` | Y el contador en **"1 comentario"**, esta vez con un comentario que se puede abrir. El número y el contenido dicen lo mismo. |

El cuerpo va al servidor **en claro**, bajo la excepción que ya estaba
argumentada en `V10__canales.sql` para el cuerpo de la publicación, y sin
estirar ninguno de sus tres motivos: un comentario en una publicación pública
es tan público como la publicación. En un canal **privado** sigue siendo un
mensaje cifrado, y la hoja lo dice: ahí sólo se ven los comentarios que
llegaron a ese teléfono.

## Las reacciones, que tampoco se veían

| | Qué muestra |
|---|---|
| `06-reacciones-visibles.png` | La fila de reacciones, con 👍 en 1. Esa reacción **ya existía en la base**; era la primera vez que se dibujaba. |
| `07-reaccion-propia.png` | ❤️ tocado, resaltado y contado. Tocarlo otra vez la quita. |

`Publicacion.reacciones` existía en el contrato desde el módulo F y **la
consulta del muro nunca las leía**. El campo tenía `= emptyList()` por defecto,
así que viajaba siempre vacío: no fallaba nada, simplemente no había nunca nada
que dibujar, y el valor por defecto lo hacía indistinguible de "esta
publicación no tiene reacciones".

Es el mismo error del módulo X con otra cara, esta vez en el servidor.

## Y un tercer defecto, que salió de paso

**El muro venía ordenado al azar.** Ordenaba por `mensaje_id DESC`, y el id de
un mensaje lo genera el cliente con `randomUUID()`: un v4, o sea un número
aleatorio. Medido en la base:

```
 mensaje_id                           | creado_en
--------------------------------------+---------------------
 fceee252-18f4-4eef-8737-bfc517f759e1 | 2026-09-19 22:43
 fc987324-6b91-44a6-a28e-59946403f1bb | 2026-09-19 15:43
 fa71b6cc-828a-4ddc-a847-f5495fdab476 | 2026-09-20 03:56
 f9a1e83e-3ff4-4cd8-ba3a-fe609ceaed89 | 2026-09-19 16:11
 f2dcf525-eaa1-4d6a-9a85-89686a8069f7 | 2026-09-18 04:46
 f23644a3-fe40-4067-a6c5-d45befd54534 | 2026-09-23 02:34
```

19, 19, 20, 19, 18, 23 de septiembre. Nadie lo había visto porque hace falta
más de una publicación para notarlo, y el canal de pruebas tenía una.

Ahora ordena por `creado_en` con el id como desempate, y el cursor de
paginación usa el mismo par —un cursor que ordena por una clave y corta por
otra saltea filas o las repite—. La prueba de regresión **elige los ids a
propósito** para que el orden por id sea el contrario al orden por fecha: con
dos ids al azar, pasaría la mitad de las veces contra el código roto, que es
peor que no tenerla.

## Lo que la suite no veía, y por qué

La suite de canales ya probaba los comentarios. Tenía esto:

```js
ck('ahora el suscriptor SI puede comentar', r.s === 200);
ck('la publicacion cuenta su comentario', r.b[0]?.comentarios === 1);
```

Las dos pasaban, y **las dos seguían pasando con el defecto puesto**: el
contador contaba filas de metadatos, y el metadato se registraba bien. Lo que
no se comprobaba era lo único que le importa a quien lee — que el texto se
pueda recuperar.

**Una prueba que afirma el contador no afirma el contenido.**

Esa segunda línea dice ahora lo contrario, y es lo correcto: registrar sólo el
metadato **no** cuenta como comentario, porque no hay nada que leer. El
contador pasa a 1 cuando el cuerpo está guardado.

## Validación rompiendo el código

Con el orden por id restaurado y las reacciones sin leer, la suite da:

```
FALLA el muro trae las reacciones de la publicacion  []
FALLA con su emoji y su recuento  []
FALLA y marcadas como mias para quien reacciono
FALLA y NO como mias para otra persona  []
FALLA el muro pone la mas nueva primero, aunque su id sea menor  ["la primera","la segunda",...]
FALLA con limite 1 trae solo la mas nueva  ["la primera"]
```

## Un error mío en el camino

`V34` agregó la tabla y **se olvidó del tipo de aviso**. `evento_pendiente`
tiene un CHECK con la lista cerrada de avisos que el servidor puede fabricar, y
el INSERT reventó con `violates check constraint tipo_evento_valido`: la ruta
respondió 500 en la primera ejecución de la suite.

Lo cazó la base, que es donde tenía que cazarse. Esa lista cerrada es la razón
por la que el defecto salió en el acto y no seis meses después, en forma de
clientes recibiendo un tipo de evento que no saben interpretar.

Se corrigió en `V35` y no reescribiendo `V34`, porque `V34` ya estaba aplicada.
Es la regla que dejó escrita `V11__evento_canal.sql` —*una migración aplicada
no se reescribe, se corrige con la siguiente*— y esta vez me la salté yo.

## Cómo se tomaron

```
G:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 exec-out screencap -p > captura.png
```

Con los dos emuladores corriendo, los dos túneles abiertos
(`pruebas\conectar-emuladores.ps1`) y el APK instalado en los dos. Para `06` y
`07` se encendieron las reacciones del canal de pruebas, que la suite deja
apagadas al terminar.
