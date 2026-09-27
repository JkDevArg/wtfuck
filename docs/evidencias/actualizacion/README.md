# Módulo BD · Actualización desde la app

Que la gente se entere de que hay una versión nueva, y que instalarla sea un
toque en vez de una excursión.

---

## El problema

El APK se reparte fuera de una tienda. Nadie avisa de que hay algo nuevo: quien
lo instaló se queda en esa versión hasta que vuelve a la página de descarga por
su cuenta — o sea, casi nunca.

Y aunque vuelva, el camino es: bajar con el navegador, buscar el archivo, abrir
el gestor de archivos, tocar el APK, pelearse otra vez con el aviso de
orígenes desconocidos. Cinco pasos para cada versión. Eso no lo hace nadie dos
veces.

## Lo primero, porque condiciona todo lo demás: **no se puede instalar en silencio**

Una app normal **no puede actualizarse sola** en Android. Las únicas formas son:

| Vía | Qué exige |
|---|---|
| *Device owner* | Un teléfono administrado, que se enrola con un borrado de fábrica |
| App de sistema | Venir preinstalada y firmada con la clave de la plataforma |
| Root | Root |

Un APK que la gente baja de una página no es ninguna de las tres. **No es una
limitación de esta implementación: es el techo del sistema operativo.**

Y es un techo razonable. Una app que pudiera instalarse sola en silencio es
exactamente la que nadie querría tener instalada.

Lo que sí se puede, y es lo que hace esto:

1. La app se entera sola.
2. Descarga en segundo plano, con barra de progreso.
3. Android enseña **su** diálogo de confirmación — obligatorio, sin saltárselo.
4. Un toque.

Es lo mismo que hacen el APK directo de Signal, el de Telegram y F-Droid.

---

## La decisión que más se piensa: esto es un canal de ejecución remota de código

Visto de frente, un mecanismo de actualización automática dice: *"servidor,
dime qué binario descargo e instalo"*. Si el servidor puede decidir eso,
comprometer el servidor es comprometer todos los teléfonos.

Para una app cuya premisa entera es **el servidor es un buzón tonto en el que
no se confía**, eso sería una contradicción — no un detalle.

### Lo que la cierra

**Android rechaza una actualización firmada con una clave distinta.** El ancla
de confianza es el keystore, no el servidor. Un servidor comprometido no puede
inyectar código propio: el sistema operativo no deja instalar el APK encima.

### Lo que Android *no* tapa, y sí depende de este código

Un servidor hostil no puede firmar un APK propio, pero sí puede servir uno
**antiguo y legítimo** — por ejemplo, uno con un fallo que ya se arregló. Eso
es un downgrade, y de eso se defiende el cliente:

```kotlin
fun hayQueBajar(v: VersionResp, instalada: Int): Boolean =
    v.versionCode > instalada &&          // > y no !=: nunca hacia atrás
        v.url.startsWith("https://") &&
        v.sha256.length == 64
```

`> instalada` y no `!=`. Es una diferencia de un carácter y es toda la defensa.

### Por qué la política está separada del resto

[`PoliticaActualizacion`](../../../app/src/main/java/com/wtfuck/app/datos/Actualizador.kt)
es un objeto sin `Context`. Es la única parte de la actualización que **decide**
algo con consecuencias de seguridad; el resto es fontanería.

Separarla la deja probable en JUnit normal, sin emulador ni Robolectric. Una
regla que se comprueba en medio segundo se comprueba siempre; una que necesita
un teléfono conectado se mira a ojo una vez y nunca más.

### Y el SHA-256, que no es lo que parece

Se comprueba, pero **no es la defensa principal** — esa es la firma. La huella
sirve contra una descarga cortada o corrupta, que es el caso que pasa de
verdad. Decir que protege contra un servidor hostil sería mentir: quien controla
el servidor controla también la huella que publica.

---

## Por qué no hace falta un push

La idea natural es un mensaje silencioso por Firebase que despierte a la app.
No hace falta, y sale más caro de lo que parece.

Publicar una versión significa cambiar `WTFUCK_APK_VERSION` y **reiniciar el
servidor**. Un reinicio desconecta todos los sockets. Los clientes reconectan
—ya lo hacen, es el comportamiento normal— y en esa reconexión preguntan.

```
app cerrada        →  pregunta al abrir
app abierta        →  pregunta al reconectar el socket
```

Entre las dos no queda nadie fuera. Y a cambio se evita:

- una dependencia de Firebase, que además **no está configurada** en este
  proyecto (`WTFUCK_FCM_*` sigue pendiente);
- contarle a Google cada vez que se publica un build, que en una app de
  mensajería privada no es un detalle menor.

La consulta al reconectar respeta el intervalo de 6 horas: en una red mala el
socket reconecta muchas veces y no tiene sentido preguntar en cada una. La del
arranque va forzada.

---

## Las otras decisiones

### Apagado por defecto

Sin `WTFUCK_APK_VERSION` el endpoint contesta `versionCode = 0` y la app no
hace nada. Actualizar el servidor no puede encenderle a nadie un mecanismo que
descarga e instala cosas sin que lo haya pedido — la misma regla que en
[invitaciones](../invitaciones/README.md).

Y apagado significa apagado **del todo**: no devuelve la URL ni la huella. Si
lo hiciera, estaría publicando por accidente lo que alguien dejó a medio
configurar.

### La ruta es pública y sin autenticar

Tiene que poder contestarle a una app **tan vieja que ya no puede entrar**. Si
un cambio de protocolo la dejó fuera, *"actualízate"* es exactamente la
respuesta que necesita, y no puede depender de un login que ya no le funciona.

Detrás de `autenticar()` esta ruta no serviría para el caso que la justifica.
Lo que publica es lo mismo que hay en la página de descarga.

### `minima` se recorta a la publicada

```kotlin
minima = (env("WTFUCK_APK_MINIMA").toIntOrNull() ?: 0).coerceAtMost(code)
```

Una mínima **mayor** que la versión publicada deja a todo el mundo fuera,
incluida la que se acaba de subir. Es un error de dedo fácil —poner el número
de build en la casilla de al lado— e imposible de diagnosticar desde el
teléfono, donde sólo se ve *"tienes que actualizar"* contra una versión que ya
es la última.

### El aviso se puede cerrar, salvo cuando no

Un diálogo que no se puede cerrar en mitad de una conversación es una app que
deja de funcionar porque el servidor lo decidió. Se cierra, y no vuelve a
salir **para esa versión** — guardado por número y no con un booleano, para que
decir "ahora no" a la 4 no silencie también la 5.

La excepción es `minima`: ahí la app ya no puede hablar con el servidor, así
que cerrar el aviso no dejaría nada usable detrás.

### Se avisa del diálogo del sistema ANTES de pulsar

> *"Se descarga aquí y Android te pide confirmar la instalación."*

Quien no lo espera cree que algo falló y cancela. Cuesta una línea.

### `PackageInstaller` y no un `Intent` con `FileProvider`

La sesión recibe el APK por un flujo, así que no hace falta exponer el archivo
a otra app ni declarar un provider para un temporal de la caché.

El APK va a la caché y no a Descargas: es un archivo que sólo le sirve al
instalador, y dejarlo en una carpeta pública sería dejar un APK suelto en el
teléfono de todo el mundo después de cada actualización.

---

## El defecto que estuvo ahí todo el tiempo

```kotlin
versionCode = 1   // desde el primer commit
```

**Con eso ninguna actualización funciona.** Android compara ese número para
decidir si un APK es más nuevo que el instalado, y dos builds con el mismo
número son la misma versión para el sistema. El instalador no se queja de nada
raro: simplemente no actualiza, o pide desinstalar primero — que en una app de
mensajería significa perder el historial.

Ahora sale de una propiedad:

```bash
./gradlew :app:assembleRelease -PversionCode=3 -PversionName=0.3.0
```

Y `publicar-apk.sh` lo **lee del APK** con `aapt2` en vez de que se escriba a
mano. Un número copiado se desincroniza en la primera prisa, y entonces el
servidor anuncia una versión que no existe: los teléfonos descargan, la huella
cuadra, y el instalador la rechaza por no ser más nueva. El síntoma sería *"la
actualización no hace nada"*, que no señala a ninguna parte.

---

## Las pruebas

### Integración — `pruebas/actualizacion.mjs`

Corre contra cualquier servidor y **dice** cuando no pudo probar lo importante.

```
sin versión configurada  →  6 pasan   (+ el aviso de que falta la publicación)
con versión publicada    →  7 pasan
```

El caso que más vale es la mínima recortada: se arrancó un servidor con
`WTFUCK_APK_MINIMA=99` y `WTFUCK_APK_VERSION=7`, y contestó `minima: 7`.

### Unitarias — `PoliticaActualizacionTest`

10 pruebas sobre la decisión de qué se instala. Se comprobó que cazan de verdad
inyectando el defecto:

```
- v.versionCode >  instalada
+ v.versionCode != instalada
```

→ `una version ANTERIOR no se baja aunque el servidor la anuncie  FAILED`

Una sola prueba, la correcta. Restaurado, las 10 en verde.

### Cobertura de acceso

`/v1/version` está eximida en `ajeno-lectura.mjs` con su motivo escrito:
pública a propósito, porque tiene que contestarle a una app que ya no puede
entrar.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`Actualizacion.kt`](../../../server/src/main/kotlin/com/wtfuck/server/Actualizacion.kt) | Lee el entorno y publica la versión |
| [`Actualizador.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Actualizador.kt) | Consultar, descargar, verificar, instalar — y la política |
| [`ActualizacionDialogo.kt`](../../../app/src/main/java/com/wtfuck/app/ui/ActualizacionDialogo.kt) | El aviso y el enganche al arranque y a la reconexión |
| [`publicar-apk.sh`](../../../despliegue/publicar-apk.sh) | Genera el bloque de variables ya relleno |
| [`actualizacion.mjs`](../../../pruebas/actualizacion.mjs) | La suite |
| [`PoliticaActualizacionTest.kt`](../../../app/src/test/java/com/wtfuck/app/PoliticaActualizacionTest.kt) | Lo que se acepta instalar y lo que no |

---

## Lo que queda sin verificar

**La instalación de verdad no se ha ejecutado.** Todo lo anterior —consulta,
descarga, huella, política— está probado. El paso final, `PackageInstaller`
entregando el APK y Android enseñando su diálogo, necesita **dos versiones
firmadas con la misma clave y un teléfono real**: un emulador con un APK de
depuración no reproduce ni la firma ni el permiso.

Se dice en vez de darlo por hecho. Es exactamente el tipo de paso que se
comprueba a ojo, se da por bueno, y falla en el teléfono de otro.
