# El aviso de "no se envió" deja de ser un callejón sin salida

## Antes

> ● 1 mensaje no se envió

Y nada más. No se podía tocar y **no decía en qué chat**. Para reintentarlo
había que abrir los chats uno por uno hasta dar con la burbuja coral.

## Ahora

> *Captura: El aviso dice dónde. Las capturas no se publican; ver la nota de `docs/evidencias/README.md`.*

> ● 1 mensaje no se envió a joaquin · toca para ir

> *Captura: Y lleva al mensaje. Las capturas no se publican; ver la nota de `docs/evidencias/README.md`.*

Y no lleva al chat: lleva **al mensaje**. En la captura está arriba del todo,
con su borde coral y su `No se pudo subir el archivo.` — y estaba en la
posición **18 de 39**, o sea justo el caso donde abrir el chat por el final no
alcanzaba.

## Tres detalles que no son obvios

**"a joaquin" y "en Equipo seguridad".** Un mensaje se le manda *a* una persona
y se manda *en* un grupo. La preposición equivocada se nota al leer aunque
nadie sepa decir por qué, y la decide quien tiene la lista de chats, no la
barra.

**La barra sólo se puede tocar cuando hay a dónde ir.** Si el chat no está en
la lista cargada, el aviso se queda sin toque: una barra que parece tocable y
no lleva a ningún lado es peor que una que no lo parece.

**El salto se da por hecho aunque falle.** Si el mensaje no aparece en la
lista, la marca se pone igual. Dejarla sin poner parecía inofensivo y dejaba la
pantalla **sin volver al final nunca más**, porque el salto al final estaba
condicionado a esa misma marca.

## Y un defecto que me hice yo

La primera versión tenía **dos efectos peleándose**: el salto al mensaje ponía
la marca, la marca era una clave del otro efecto, el otro efecto se relanzaba y
mandaba la lista al final. El salto ocurría y se deshacía en el mismo instante,
así que la pantalla quedaba exactamente igual que sin el arreglo — y durante un
rato parecía que el argumento de navegación no llegaba.

Ahora es **un solo efecto** y la prioridad se lee de arriba abajo: primero el
mensaje al que se vino, si lo hay; si no, el final.
