# Tanda 8 a 18: lo que falta frente a WhatsApp, Telegram y Signal

Continúa la tanda 1 a 7. Cada punto se probó en los dos emuladores (5554 =
`xampl3`, 5556 = `goblin2026`) y, si tocó el servidor, en la suite de
integración.

## 8. Vista previa de enlaces

Al escribir un enlace aparece una tarjeta sobre el campo de texto, con
miniatura, título y dominio. La X la quita para ese enlace. Lo que se envía
lleva la tarjeta y lo que se recibe la muestra encima del texto. Los enlaces del
texto ahora se pueden tocar y van subrayados (`8-vista-previa.png`: a la
izquierda quien escribe, a la derecha quien recibe).

**Quién arma la tarjeta.** El teléfono que **envía**, y la tarjeta viaja dentro
del sobre cifrado, como en Signal. Las alternativas eran peores:

- **Que la arme quien recibe:** con solo abrir el chat, el sitio vería la IP de
  cada persona que lo lee, sin que nadie toque nada.
- **Que la arme nuestro servidor, como los GIFs:** vería qué enlaces se mandan
  dentro de conversaciones cifradas. Con los GIFs se aceptó que viera
  búsquedas, pero un enlace dice mucho más de una charla.

El costo, que el ajuste dice tal cual, es que el sitio ve la IP de quien envía,
igual que si lo abriera. Se apaga en *Privacidad → Este aparato → Vista previa
de enlaces*. Viene encendida, como en Signal y WhatsApp.

**Lo que no se le cree a quien envía.** La tarjeta la arma otra persona, así
que podría mentir:

- Una tarjeta solo se guarda si **su URL está en el texto** del mensaje. Si no,
  alguien podría mandar una tarjeta que dice "banco.com" y que abre otro sitio.
- El dominio que se muestra sale de la URL, **no** del campo `sitio` que mandó
  quien escribió. `https://banco.com@malo.com/` se muestra como `malo.com`
  (está probado).
- Cada campo y la miniatura tienen un tope.

**Al armarla:**

- solo se leen 512 KB de HTML y solo la cabecera;
- la miniatura se baja a 360 px en JPEG;
- se usa un cliente HTTP aparte, sin cookies, con tiempos cortos y sin el
  pinning de nuestro servidor;
- espera 0,7 s tras dejar de escribir para no pedir la página por cada letra.

El lector de metadatos es una función pura sin librerías, con 10 pruebas: Open
Graph en cualquier orden de atributos, entidades HTML, rutas relativas y
recortes.

Room pasa a la versión 23 (`mensaje.previaJson`). El servidor no cambió: la
tarjeta es parte del sobre.
