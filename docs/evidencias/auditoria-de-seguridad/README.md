# Auditoría de seguridad · módulo AP

Un barrido adversarial del cliente y del servidor, mirando lo que un atacante
miraría y no lo que el código dice de sí mismo.

**Lo que está bien y conviene decirlo**, porque enmarca lo demás: Argon2id con
los parámetros de OWASP, comparaciones en tiempo constante en los cinco sitios
donde se comparan secretos, tokens hasheados en reposo, `allowBackup="false"`,
reglas de extracción declaradas, `MainActivity` sin más filtro que
`MAIN`/`LAUNCHER` —o sea, sin superficie de intent externo—, y la frase de paso
de SQLCipher envuelta con una clave no exportable del Keystore.

Los cinco hallazgos están todos en los bordes. Ninguno es una puerta abierta;
cuatro son puertas mal cerradas y uno es un candado que se traba solo.

---

## 1 · El límite de intentos de ingreso vivía en la memoria de un proceso

**Severidad: alta.** Corregido.

`Limitador` guardaba sus marcas en un `ConcurrentHashMap` del proceso. El
propio archivo argumentaba esa elección:

> *"Reiniciar el servidor perdona la ráfaga en curso, y eso está bien: el
> castigo dura segundos."*

Para mensajes es cierto. Para probar contraseñas no, por dos motivos que no se
parecen entre sí:

1. **Se multiplicaba por instancia.** Esta arquitectura guarda las sesiones en
   Redis *justamente* para poder correr varias copias detrás de un
   balanceador. Con cuatro copias, "8 fallos cada 15 minutos" eran 32, y nada
   en el código lo decía. El número del límite dejaba de ser el límite.
2. **Un reinicio lo perdonaba entero.** Para tres mensajes de más da igual;
   para un ataque de diccionario, un despliegue es un indulto.

El mismo archivo decía que la división entre limitadores era *"por la duración
del límite, no por su importancia"*. Este es el caso donde la importancia
manda: la ventana es corta, pero lo que protege no.

**El arreglo** mueve los límites de fallo a Redis con un conjunto ordenado por
marca de tiempo —ventana deslizante, no fija— para que se comporten **igual**
en desarrollo y en producción. Un límite que se comporta distinto en los dos
sitios es un límite sobre el que nadie puede razonar.

Se anota en los dos sitios, Redis y memoria. No es redundancia: si Redis se
cae a mitad de un ataque, el límite no puede quedarse en cero.

### La prueba

`pruebas/bus.mjs` es la única suite que habla con **dos instancias**, así que
es la única que puede ver este defecto:

```
=== el limite de fallos de ingreso es COMPARTIDO ===
  PASA  los fallos contra A se rechazan por clave, no por limite
  PASA  B ya sabe que se agotaron los intentos (429, no 401)
  PASA  a otra cuenta desde la misma IP no la corta el limite ajeno
```

Validada revirtiendo el arreglo y **reiniciando las dos instancias**: B
responde `401`, o sea empieza de cero. Reiniciar sólo una habría dejado la
prueba en verde por el motivo equivocado — ya pasó una vez en este proyecto.

---

## 2 · El arreglo destapó que el límite por IP era inservible

**Severidad: media (disponibilidad).** Corregido.

Al hacerlo compartido, la suite entera empezó a fallar con `429`. En Redis:

```
wtfuck:lim:ingreso_ip:0:0:0:0:0:0:0:1 = 50
```

Exactamente `FALLOS_POR_IP`. El comentario decía que esa regla *"es holgada a
propósito: tiene que tolerar el NAT"* — y 50 cada 15 minutos no tolera ningún
NAT. Detrás de una sola salida a internet puede haber decenas de miles de
personas, y basta con que **una de cada mil** se equivoque de contraseña en un
cuarto de hora para dejar fuera a todas las demás. En una institución con
cuarenta mil cuentas eso no es un caso raro: es la hora punta de un lunes.

No se veía porque el contador se repartía entre instancias y moría en cada
reinicio. **El arreglo no lo causó, lo destapó.**

Ahora son 300. Subirlo no afloja la protección de una cuenta, porque no es la
que protege una cuenta: eso lo hace `FALLOS_POR_USUARIO`, que son 8 y no se
tocan. La regla por IP es un tope grueso contra quien rocía desde un sitio, y
contra eso 300 sigue siendo un tope — quien ataque en serio usa muchas
direcciones y ninguna regla por IP lo va a parar.

> Un límite que deja fuera a gente que no hizo nada se acaba subiendo hasta
> que deja de servir, o peor, se apaga entero.

---

## 3 · `usesCleartextTraffic="true"`, global y también en producción

**Severidad: media-alta.** Corregido.

Era un interruptor de manifiesto: valía para **todos** los destinos y para la
compilación de producción, donde el servidor es `https://`.

Que no se usara no lo hacía inofensivo. Dejaba la puerta: quien pudiera torcer
el DNS o poner un proxy conseguía que la app hablara HTTP **sin un solo
aviso**. Por ahí van el token, los metadatos de quién habla con quién, el
contenido de los canales públicos —que va en claro a propósito— y, lo más
serio, las claves de identidad y las prekeys. Con eso último un intermediario
entrega una identidad falsa en el **primer contacto**, que es el único momento
en que el aviso de "la clave cambió" no puede saltar, porque no hay clave
anterior con que comparar.

Ahora hay `res/xml/seguridad_de_red.xml`: claro **prohibido** por defecto,
permitido sólo en `127.0.0.1`, `localhost` y `10.0.2.2` — el propio aparato y
la máquina que lo emula, donde no hay red que interceptar.

Y de paso, lo que más vale de ese archivo: **las anclas de confianza son sólo
las del sistema**. Los certificados de usuario quedan fuera, que es lo que
hace que un proxy de interceptación funcione sin más. Tiene un costo asumido:
depurar la red con un proxy exige una compilación con esto cambiado. Es el
orden correcto — molestar a quien depura antes que abrirle la puerta a quien
ataca.

**Lo que queda abierto: no hay fijado de certificado.** Necesita el
certificado real de `api.wtfuck.com`, que todavía no existe, y un pin
equivocado deja la app inservible hasta la siguiente publicación. Queda
declarado, no olvidado.

---

## 4 · El token de sesión, en texto plano

**Severidad: media.** Corregido.

```xml
<string name="token">8z9xSS1l6lxkKjxAO2BXClhke0jt2ulIA6IDV0IeFrs</string>
```

Eso es lo que había en
`/data/data/com.wtfuck.app/shared_prefs/wtfuck_sesion.xml`. Es una credencial
portadora: quien la tenga **es la cuenta** ante el servidor.

Lo que lo convierte en hallazgo y no en opinión es la carpeta de al lado: la
frase de paso de SQLCipher ya se guardaba envuelta con una clave no exportable
del Keystore. Dos secretos, el mismo sitio, dos niveles de protección y
ninguna razón escrita para la diferencia.

> Las diferencias de protección sin motivo declarado son accidentes, no
> decisiones.

Ahora:

```xml
<string name="token_c">vu7Fhpk4Je8uOKFo:TByfyKZCNFym6cfpTakpFepZ3uB7WgwKNOCALiB4mfoGpVsXFcfp+rpdo3BtBqWahpFCeGvorgiP5F4=</string>
```

Comprobado en el emulador: el campo viejo desaparece, el nuevo está envuelto,
y **la sesión sobrevive** — hay una migración que lee el token en claro una
última vez y lo reescribe. Sin ella, actualizar la app habría cerrado la
sesión de todo el mundo, que es pagar el arreglo con la molestia de quien no
hizo nada.

No protege del proceso vivo y no puede: con la app corriendo, cualquier código
dentro de ella puede pedirle al Keystore que abra. Protege el **archivo en
reposo** — un aparato con root, una imagen forense, un volcado.

### Lo que se decidió NO hacer

`setUnlockedDeviceRequired(true)` era la tentación evidente: con eso, lo
envuelto no se abre mientras el teléfono está bloqueado, que es justo el
escenario en que se pierde.

**Habría roto la app.** Con la pantalla bloqueada tienen que seguir
funcionando un aviso push —abrir el socket exige el token—, una llamada
entrante, y el compartido de ubicación en vivo, que escribe en la base cada
medio minuto durante horas. Las tres dejarían de andar exactamente cuando el
teléfono está en el bolsillo, que es como se usa un teléfono.

> Endurecer hasta romper la función no es endurecer; es apagarla y llamarlo
> seguridad.

---

## 5 · Una clave del Keystore invalidada dejaba la app sin arrancar

**Severidad: baja, pero sin salida.** Corregido.

`ClaveBase.obtener` descifraba sin red de seguridad. Una clave del Keystore
**se puede invalidar** —cambio de credenciales del aparato en algunos
fabricantes, una restauración, una actualización que rota el almacén— y
entonces lo guardado es ruido. La app no podía abrir la base, que es lo
primero que hace: excepción en cada arranque, para siempre, sin más salida que
desinstalar.

Ahora se tira la clave rota y se empieza de nuevo. Se pierde el historial
local, y eso es lo correcto: el historial sólo vive ahí, así que no hay de
dónde recuperarlo, y lo único que estaba en juego era si la app vuelve a
arrancar. No es una puerta trasera — quien tenga el `.db` sigue sin poder
leerlo, porque la frase que lo abría se fue con la clave.

---

## Lo que se miró y estaba bien

| Qué | Resultado |
|---|---|
| Hash de contraseñas | Argon2id, 64 MiB / 3 pasadas / 4 hilos (OWASP) |
| Comparación de secretos | `MessageDigest.isEqual` en los cinco sitios |
| Tokens en la base | Hasheados, nunca en claro |
| Copias de seguridad | `allowBackup="false"` + reglas de extracción |
| Superficie de intents | `MainActivity` sólo `MAIN`/`LAUNCHER`; todo lo demás `exported="false"` |
| `FileProvider` | `exported="false"`, permisos acotados por intent |
| Bus entre instancias | HMAC-SHA256, falla cerrado sin secreto, verifica **antes** de parsear |
| Capturas de pantalla | `setRecentsScreenshotEnabled` con el bloqueo puesto (decisión documentada) |
| Token a terceros | El `ImageLoader` añade `Authorization` sólo al host propio — y ahora hay un host de terceros de verdad, así que la regla pasó de teórica a de carga |

**1903 pruebas en verde**: 1523 de integración en 36 suites, 305 JUnit de app
y 75 de servidor.
