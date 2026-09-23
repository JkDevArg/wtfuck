# Evidencias · La lista de chats

| # | Captura | Qué muestra |
|---|---|---|
| 01 | `01-boton-nuevo.png` | El botón ya no es un "+": dice **Nuevo** y despliega Conversación, Grupo y Canal |
| 02 | `02-seleccion-multiple.png` | Dos chats marcados y la barra contextual con las cinco acciones en lote |

## Lo que se probó de verdad, no solo se vio

Una captura de una barra de botones no prueba que los botones hagan algo. Lo
que se comprobó a mano en el emulador, contra el servidor:

1. Mantener pulsado sobre `@tatiana` → entra en selección con 1.
2. Tocar *Equipo seguridad* → 2 seleccionados, y **"Más" desaparece** (sus
   acciones no tienen versión en lote).
3. Con `@tatiana` ya silenciada y el grupo no, el botón ofrece **"Silenciar"**,
   no "Quitar silencio". Es la regla de los lotes mixtos: el botón hace lo que
   *falta*.
4. Tocar "Fijar arriba" → los dos quedan fijados **en el servidor**:

   ```
    directa | t | t
    grupo   | t | f
   ```

5. Volver a seleccionarlos → el mismo botón ahora dice **"Quitar de fijados"**.
6. Quitarlo, quitar el silencio, y la base vuelve a `0` filas con preferencias.

El estado de desarrollo quedó como estaba: nada fijado y nada silenciado.

## Lo que las capturas no cubren

La regla de los lotes mixtos vive en `loteDe()` y la cubre
[`SeleccionDeChatsTest`](../../../app/src/test/java/com/wtfuck/app/SeleccionDeChatsTest.kt),
12 pruebas. Los casos que importan —once fijados y uno no, un silencio
vencido, un chat marcado a mano como no leído— hay que construirlos a
propósito y no aparecen tocando la app.

Validado por reversión: quitando la guarda de lista vacía y volviendo a mirar
`silenciadoHasta` en crudo, caen 2 de las 12.

---

## Módulo V · La depuración visual

`03-lista-depurada.png` es la misma pantalla después de seis correcciones.

| # | Antes | Ahora |
|---|---|---|
| 1 | banda permanente que decía "Conectado" | sólo aparece cuando hay algo que hacer |
| 2 | franja de 90 dp con un "+" y el resto hueco | una línea; la franja vuelve cuando hay historias |
| 3 | un canal con icono de grupo + etiqueta "grupo" sólo en grupos | tres iconos distintos, sin etiqueta de texto |
| 4 | "21/09/26" para un mensaje de ayer | **Ayer**, el día de la semana, la fecha |
| 5 | la hora flotando a media altura de la fila | alineada con el nombre |
| 6 | buscador de 96 dp con borde | campo relleno, la mitad de alto, con X para borrar |

**Sobre la referencia.** El pedido llegó con capturas de Telegram. No se copió
su interfaz —es la regla del brief—: lo que se tomó son convenciones genéricas
de una lista de conversaciones, aplicadas con la identidad propia. Sigue siendo
oscura, cian y con nuestras formas.

Un defecto encontrado al probarlo en el emulador: el nombre del día salía del
idioma del teléfono, así que la lista decía **"Sunday"** entre textos en
español. Ahora el día va en español fijo; la fecha y la hora siguen el locale
del sistema, porque ahí no hay palabras.

