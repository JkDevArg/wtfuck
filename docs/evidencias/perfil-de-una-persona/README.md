# El perfil de una persona · módulo AO

Tocar el nombre en la cabecera de un chat abre la ficha de esa persona. La
referencia es el panel de contacto de Telegram, y la mayor parte traduce
directo — pero dos filas de ahí no existen acá, y una fila que sí existe no
está en Telegram.

## 1 · La ficha

![El perfil](1-el-perfil.png)

**No hay número de teléfono.** Esta app registra por username y no pide
teléfono ni correo, así que esa fila no existe. Poner un hueco donde otra app
pone un teléfono habría sido copiar la forma sin la sustancia.

**Tampoco hay id numérico.** La identidad acá es el `@username`; un número
interno sólo daría algo que copiar y pegar mal.

**Y hay una fila que allá no está: verificar el cifrado.** Es lo que ocupa el
sitio del teléfono, y no por casualidad — es el único dato con el que se puede
comprobar que se está hablando con quien uno cree. Llevaba existiendo desde el
módulo F, enterrada en el menú de tres puntos.

El nombre de arriba es **el mismo que el de la cabecera del chat**: el alias
que le puse yo, si le puse uno. La primera versión mostraba el username, así
que la pantalla decía "tatiana" un segundo después de que la cabecera dijera
"Tati" — dos nombres para la misma persona en dos pantallas seguidas, que es
justo lo que hace dudar de haber abierto lo que se quería abrir.

### Los recuentos los hace este teléfono

"6 fotos" no se le pregunta a nadie. El servidor es un buzón tonto que no
guarda el historial, así que **no sabe** cuántas fotos se mandaron en un chat
— ni podría decirlo si quisiera. La cuenta sale de la base local.

Es la propiedad central del producto vista desde el otro lado: la función no
le pide nada al servidor porque el servidor no tiene el dato.

Sólo aparece lo que existe. No hay una fila "0 videos" ocupando sitio para
decir que no hay nada que mirar.

Y el orden es **fijo**, no por cantidad. Ordenar por cuántos hay hace que la
lista se reacomode sola a medida que se usa la app: la fila de las fotos
aparecería hoy tercera y mañana primera, y nunca se aprendería dónde está
nada.

## 2 · Y se pueden abrir

![La galería](2-galeria.png)

Un recuento que no se puede abrir es decoración: dice "hay 6 fotos" y deja a
la persona haciendo scroll por el chat para encontrar una. **La razón de
contar es poder volver.**

Dos formas de listar y no una: las fotos, los videos y los stickers van en
rejilla, porque lo que identifica a una imagen es la imagen; los archivos, los
audios y los enlaces van en lista, porque lo que identifica a un documento es
su nombre y una rejilla de iconos iguales no distingue nada.

Las miniaturas ya están en la base, descifradas al guardarlas: la galería se
ve **sin red y sin descargar nada**. Y se decodifican con `miniaturaAjena`,
que es el decodificador endurecido del módulo D — esos bytes vienen de un
sobre de otra persona, y pasarlos por un cargador de imágenes cualquiera
habría salteado la única comprobación que hay.

## 3 · Tocar una lleva a su sitio en el chat

![El salto al mensaje](3-salto-al-mensaje.png)

No a un visor suelto: al mensaje, donde está el contexto de quién lo mandó y
qué se estaba diciendo. Reusa el salto a mensaje del módulo AB
(`chat/{id}?m={mensaje}`), que ya existía para las respuestas.

Los enlaces son la excepción y se abren directo, porque lo que alguien quiere
de un enlace es el enlace.

---

## Un defecto que se llevó puesto el diálogo que reemplaza

La ficha vieja era un `AlertDialog` en el chat, y pedía la tarjeta de empresa
con `chat.titulo`.

`titulo` **puede ser el alias que yo le puse**. El propio diálogo lo decía dos
líneas más abajo, en un comentario que explicaba por qué el username tenía que
aparecer igual. Así que para cualquier contacto renombrado la consulta salía
con un nombre que el servidor no conoce, y la tarjeta no aparecía nunca — en
silencio, porque el fallo se traga a propósito.

La pantalla nueva la pide con `chat.nombre`, que es el username.

El diálogo se borró entero en lugar de dejarlo al lado: dos sitios que
contestan "quién es esta persona" son dos respuestas que se separan.

---

## Lo que se ganó de paso

Tocar la cabecera **abre la ficha**. Antes eso sólo estaba en el menú de tres
puntos: el sitio donde nadie lo busca, porque el sitio donde todo el mundo lo
busca es el nombre.

## La clase de los enlaces

`_enlace` no es una clase del contrato: un enlace no es un adjunto, es un
mensaje de texto que además lleva una dirección. Vive del lado de la vista y
lleva un nombre con guión bajo que no puede chocar con los del protocolo —
hay una prueba que lo fija, porque si algún día el contrato definiera una
clase con ese nombre las dos se mezclarían en el mismo recuento sin que nada
lo avisara.

Se cuentan **mensajes con enlace** y no enlaces: un mensaje con tres
direcciones es una cosa que alguien mandó, y contarlo tres veces haría que el
número no coincidiera con las filas que se ven al abrirlo.
