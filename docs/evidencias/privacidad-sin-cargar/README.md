# Evidencias · La pantalla de Privacidad mentía sin conexión

## El defecto, medido

Se pusieron cinco ajustes de `@joaquin` en `nadie` directamente en la base:

```
 priv_foto | priv_estado | priv_nombre | priv_ultima_vez | priv_biografia
 nadie     | nadie       | nadie       | nadie           | nadie
```

Con la red cortada, la pantalla de Privacidad mostraba:

```
  Quien ve mi foto        Todos
  Quien ve mi estado      Todos
  Quien ve mi biografia   Todos
  Quien me puede escribir Todos
```

**Lo contrario de la verdad**, sin ninguna señal de que no había podido
cargar — en la única pantalla cuyo trabajo es decir quién te ve.

## La causa

```kotlin
private val _privacidad = MutableStateFlow(Privacidad())      // ← los DEFECTOS
runCatching { api.privacidad() }.onSuccess { _privacidad.value = it }   // ← falla en silencio
```

Los valores por defecto de `Privacidad()` son los **más permisivos** —foto,
estado, nombre y biografía en `todos`— y quedaban en pantalla como si fueran la
configuración de la persona.

## Y la segunda cara

Guardar manda **los quince campos** y el servidor sobrescribe las quince
columnas. Partiendo de los defectos, tocar un solo ajuste escribiría los otros
catorce con los valores permisivos.

**No conseguí reproducirlo**: en las dos pruebas la lectura se recuperó antes
de que guardara. Pero la ventana existe —lectura fallida, escritura correcta— y
no depende de nadie que la cierre a mano, así que se cerró por construcción.

## El arreglo

`_privacidad` pasa a ser **nullable**: `null` = "todavía no se pudo leer".

- La pantalla no dibuja **ningún** ajuste sin datos: muestra el error con
  Reintentar (captura 01).
- `guardarPrivacidad` **falla** si nunca se leyó. No se puede escribir lo que
  no se pudo leer, y eso cierra la segunda cara sin depender del tiempo.
- El aviso de "escribiendo" no se emite mientras no se sepa: `null` es "no sé",
  y no saber no autoriza nada. Antes se emitía, porque el valor por defecto de
  `escribiendo` es `true`.

## Verificado

1. Sin red → error con Reintentar, **cero valores falsos**.
2. Se restaura la red y se toca Reintentar → aparecen los reales: `Nadie`.

Validado por reversión: volviendo `emiteEscribiendo` al comportamiento viejo
caen 2 de las 6 pruebas de `PrivacidadSinCargarTest`.
