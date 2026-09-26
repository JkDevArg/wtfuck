# Evidencias

Un documento por módulo: qué se decidió, por qué, qué defectos aparecieron y
cómo se comprobó que quedaron arreglados.

---

## Por qué no hay capturas

Los documentos de aquí se escribieron con capturas de pantalla al lado. **No
están en el repositorio**, y no es un descuido.

Se tomaron contra un entorno de desarrollo con conversaciones reales: nombres
de usuario de personas de verdad y el contenido de lo que se escribieron,
aunque fueran pruebas. Publicarlas en un repositorio público no es reversible —
los forks, las cachés y los rastreadores se quedan con ellas aunque después se
borren— y quienes aparecen en esas conversaciones no dieron permiso para eso.

Es además coherente con lo que hace la aplicación: una app que cifra los
mensajes de extremo a extremo no debería llevar los de sus pruebas en el
repositorio.

Lo que las capturas mostraban está descrito en el texto. Donde había una
imagen, ahora hay una línea que dice qué se veía.

## Cómo reproducirlas

Levantando el entorno de desarrollo —ver
[`09-DESPLIEGUE.md`](../09-DESPLIEGUE.md)— y siguiendo los pasos que cada
documento describe. Las comprobaciones que no son visuales —conteos de
píxeles, jerarquías de vistas, volcados de `adb`— están transcritas literales
en el texto, que era el punto de anotarlas así.
