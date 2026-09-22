# Evidencias · Módulo P.2 · La ficha de empresa

Capturas tomadas en dos emuladores contra el servidor de desarrollo, el
2026-09-22. Los siete campos se llenaron **por la interfaz**, no por la API, y
después se comparó lo guardado contra lo escrito.

| # | Captura | Qué prueba |
|---|---|---|
| 01 | `01-ficha-vacia.png` | El formulario al elegir "Empresa": nace vacío y dice "Sin verificar. El distintivo lo pone el equipo, no se activa solo." |
| 02 | `02-rubros.png` | Los 14 rubros son una lista cerrada, no un campo libre |
| 03 | `03-siete-campos.png` | Los siete campos llenos, con el contador en 181/600 |
| 04 | `04-perfil-propio.png` | La tarjeta en el perfil propio |
| 05 | `05-vista-por-otro.png` | **Lo que faltaba.** @tatiana, que no está en la beta, ve la ficha de @joaquin desde "Ver contacto" |
| 06 | `06-verificada.png` | El distintivo, puesto por un administrador (no por la propia cuenta) |

## Lo que se verificó, no solo se vio

**La descripción se comparó byte a byte.** El emulador tiene el teclado en
inglés y **autocorrige palabras en español** al confirmar con espacio o
puntuación: en el primer intento "adversarios" se guardó como "adversaries" y
"empresas" como "empress". Eso no es un defecto de la app —el texto que sale del
teclado ya viene cambiado— pero sí invalida cualquier captura como prueba de que
el dato viaja entero. La verificación real fue:

```
OK descripcion identica (181 bytes)
```

**El distintivo de la captura 06 se retiró después.** Nadie comprobó de verdad
que esa cuenta sea esa empresa, y dejar un "verificada" que nadie verificó en la
base es exactamente lo que el módulo existe para evitar.

## Lo que las capturas no cubren

La ficha viaja también en los `participantes` de `GET /v1/conversaciones`, y esa
es otra consulta: es donde estaba el defecto que ninguna captura habría mostrado
—un 500 al abrir cualquier chat—. Eso lo cubre la sección 6 de
[`pruebas/empresa-publica.mjs`](../../../pruebas/empresa-publica.mjs).
