# Antes del arreglo: 45 guardados seguidos, 45 doscientos

```
=== 1 · guardar la ficha en bucle ===
  FALLA en algun momento responde 429 y no 200 indefinidamente  {"200":45}
  FALLA los guardados quedaron contados en la base  0
  FALLA alternar el tipo en bucle tambien se corta  {"200":30}
  FALLA y lista el limite de la ficha  [... 15 claves, ninguna del modulo P]

=== 4 pasan, 12 fallan ===
```

# Despues

```
=== 1 · guardar la ficha en bucle ===
  PASA  la cuenta se declara empresa
  PASA  en algun momento responde 429 y no 200 indefinidamente
  PASA  y el 429 dice cuanto esperar, no solo que no
  PASA  pero no antes de que se pueda usar de verdad (>= 10 guardados)

=== 2 · el cupo que un reinicio no perdona ===
  PASA  el panel sube el limite de rafaga de la ficha
  PASA  los guardados quedaron contados en la base
  PASA  con el contador diario pasado, se rechaza
  PASA  y el mensaje habla del dia, no de segundos
  PASA  el rechazo no dejo escrito el nombre nuevo
  PASA  y queda constancia en el registro de seguridad
  PASA  y el limite de fabrica se restaura

=== 3 · alternar el tipo de cuenta ===
  PASA  volver a personal sigue funcionando con la ficha agotada
  PASA  alternar el tipo en bucle tambien se corta
  PASA  y no antes de un par de cambios legitimos (>= 5)

=== 4 · visibles en el panel de limites ===
  PASA  el panel de limites se lee
  PASA  y lista el limite de la ficha
  PASA  y el de cambiar el tipo

=== 19 pasan, 0 fallan ===
```
