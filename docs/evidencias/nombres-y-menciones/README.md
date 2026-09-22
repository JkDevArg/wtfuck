# Evidencias · Nombres de contacto y menciones

| # | Captura | Qué muestra |
|---|---|---|
| 01 | `01-lista-con-nombre.png` | La lista sin "@". `@joaquin` tiene a `tatiana` agendada como **Tati**, y eso es lo que se ve — también en el "Tati:" del último mensaje del grupo |
| 02 | `02-selector-mencion.png` | Al teclear `@` en un grupo, la tira de candidatos con **mi** nombre para cada uno |
| 03 | `03-autor-en-grupo.png` | La etiqueta de autor dentro del grupo: **Tati**, no `@tatiana` |
| 04 | `04-mencion-recibida.png` | Lo mismo visto por `@tatiana`: **@tatiana** en negrita con fondo, `@joaquin` con fondo tenue |

## Los dos lados de la regla, comprobados en dos aparatos

La regla del nombre depende de **quién mira**, así que una sola captura no la
prueba. Se verificó en los dos emuladores a la vez, con la misma conversación:

| Quién mira | Tiene agendada a la otra | Qué ve |
|---|---|---|
| `@joaquin` | sí, como "Tati" | **Tati** |
| `@tatiana` | no | **joaquin** |

En la base de desarrollo hay exactamente una fila:

```
 joaquin | tatiana | Tati
```

## La mención, de punta a punta

1. `@joaquin` teclea `@` en el grupo → aparecen **Tati** y **rocio** (captura 02).
2. Toca "Tati" → el campo queda con `@tatiana `, con el **username**, que es lo
   único que el servidor resuelve.
3. Envía `@tatiana y @joaquin prueba de mencion`.
4. En su propia burbuja, `@joaquin` sale en negrita (es a él).
5. En el aparato de `@tatiana`, la negrita es `@tatiana` (captura 04).

O sea: cada uno ve marcada **la suya**, que es para lo que sirve la mención.

## Lo que las capturas no cubren

Los casos que importan no se ven tocando la app y viven en
[`MencionesTest`](../../../app/src/test/java/com/wtfuck/app/MencionesTest.kt) (24) y
[`NombreEnLaListaTest`](../../../app/src/test/java/com/wtfuck/app/NombreEnLaListaTest.kt) (8):

- Que **lo resaltado sea exactamente lo que se manda** en `menciones`. Son dos
  sitios distintos y, si se separan, la burbuja pinta un nombre que no avisó a
  nadie.
- Que un correo (`joaquin@ejem`) **no** abra el selector.
- Que una cuenta llamada `impostor99` que se puso de nombre "Tatiana" **no**
  aparezca en la lista como Tatiana.

Validado por reversión: con la regla vieja del título caen 3 de las 8.
