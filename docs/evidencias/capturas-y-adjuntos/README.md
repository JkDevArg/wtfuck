# Bloquear capturas, y decir a tiempo que un archivo no cabe

Dos mejoras pequeñas y autocontenidas. Y una tercera que **decidí no hacer**,
con su motivo.

---

## 1 · Bloquear capturas de pantalla (opcional)

### La decisión previa era correcta y no se pisa

`MainActivity` ya explicaba por qué **no** se usaba `FLAG_SECURE`:

> *"`FLAG_SECURE` también taparía la miniatura, pero de paso prohíbe toda
> captura de pantalla dentro de la app, y eso es una decisión distinta que
> nadie pidió: hay motivos legítimos para capturar una conversación propia."*

Eso sigue siendo verdad. Así que **no se fuerza**: se ofrece.

### Los dos huecos que sí había

1. **`setRecentsScreenshotEnabled` solo existe desde Android 13.** Por debajo,
   la miniatura de la app queda expuesta en la lista de recientes **aunque el
   bloqueo esté activo** — y esa lista se ve sin desbloquear nada. El bloqueo
   tenía un agujero en todos los Android anteriores al 13.
2. **Quien sí quería bloquear capturas no podía.**

### Lo que se hizo

Un ajuste, apagado por defecto. Encendido → `FLAG_SECURE` siempre, con su
coste y su beneficio, y los dos dichos **donde se decide**:

> *"Tú tampoco podrás capturar tus propias conversaciones."*

Y en Android < 13, el texto del bloqueo ahora dice **lo contrario** de lo que
decía:

| Antes | Ahora (API < 33) |
|---|---|
| "la app sale en blanco en la lista de recientes" | "la miniatura **SÍ se ve**, sin desbloquear. Para taparla, activa Bloquear capturas" |

Prometer una protección que no está es peor que no tenerla: **quien lo cree
deja de tener cuidado.**

### El detalle que lo habría dejado a medias

El ajuste se aplicaba en `MainActivity.onStart`, y llegar a Ajustes **no pasa
por `onStart`**: es la misma Activity. Alguien lo encendía, probaba a capturar,
lo conseguía, y concluía —con razón— que el interruptor no hacía nada. Ahora se
aplica a la ventana en el momento, subiendo por los `ContextWrapper` hasta la
Activity.

---

## 2 · Un archivo que no cabe se dice antes, no después

### El defecto

El límite se comprobaba dentro de `subirAdjunto`, cuando el mensaje **ya
existía en el chat**. La persona veía una burbuja roja de "falló", con el
motivo escondido.

Y no es un caso raro: el tope de vídeo son **64 MB**, y un minuto en 4K de un
teléfono moderno pasa de **300 MB**. Es el camino normal, no el excepcional.

### Lo que se hizo

La comprobación sube al principio de `enviarAdjunto`, antes de crear nada, y el
aviso dice **los dos números**:

> *"Ese video pesa 340 MB y el límite son 64 MB. Prueba a grabarlo en menor
> calidad, o recórtalo antes de enviarlo."*

"El archivo es demasiado grande" obliga a adivinar cuánto hay que recortar.

### Detalles que importan

- **El sobrecosto del cifrado se cuenta igual en los dos sitios.** Si no
  coincidieran, un archivo justo en el borde pasaría la comprobación nueva y
  fallaría en la vieja — o sea, volvería el defecto original, pero solo para
  los archivos del borde y por tanto mucho más difícil de ver. Tiene prueba.
- **La comprobación vieja se queda.** Una imagen se recomprime *después* de
  esto y podría seguir sin caber; y dos guardias en un límite de red nunca
  sobran.
- **Las imágenes se saltan la comprobación temprana** justo por eso: se
  reducen antes de subir, y rechazar por el tamaño original sería rechazar
  fotos que sí caben una vez comprimidas.
- **Solo el vídeo sugiere recortar.** Un documento no se "recorta", y un
  consejo que no aplica hace dudar de los que sí.

---

## 3 · Lo que NO hice: recomprimir vídeo

Lo había propuesto como "autocontenida y medible". **Me equivoqué, y conviene
dejarlo escrito.**

Transcodificar vídeo en Android sin biblioteca es `MediaCodec` +
`MediaMuxer` + `MediaExtractor` a mano: decodificación superficie a
superficie, gestión de timestamps, rotación, sincronía de audio. Cientos de
líneas que **no se pueden probar sin un dispositivo** — y el emulador de esta
máquina está corrupto.

El modo de fallo es el peor posible: **un transcodificador mal hecho no falla,
entrega un archivo roto.** Se ve como "el vídeo que mandé no se abre", meses
después, en el teléfono de otra persona.

La alternativa correcta es **Media3 Transformer** (oficial de AndroidX,
diseñado exactamente para esto). Pero el proyecto no usa media3 ni ExoPlayer —
el reproductor es `MediaPlayer` pelado— así que sería una dependencia nueva de
varios MB en un APK cuyo tamaño ya costó una batalla (162 MB → 26 MB), para una
función que no podría verificar.

Queda pendiente, con el camino ya decidido: **Media3 Transformer, y con
dispositivo delante.** Mientras tanto, al menos la persona se entera a tiempo y
sabe qué hacer.

---

## Los archivos

| Archivo | Qué hace |
|---|---|
| [`CabeAdjunto.kt`](../../../app/src/main/java/com/wtfuck/app/datos/CabeAdjunto.kt) | **Nuevo.** El veredicto y el aviso, puros |
| [`Ajustes.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Ajustes.kt) | `bloquearCapturas` |
| [`MainActivity.kt`](../../../app/src/main/java/com/wtfuck/app/MainActivity.kt) | Aplica `FLAG_SECURE` según el ajuste |
| [`CuentaPantalla.kt`](../../../app/src/main/java/com/wtfuck/app/ui/CuentaPantalla.kt) | El interruptor, el aviso honesto en < 13, y la aplicación inmediata |
| [`Repositorio.kt`](../../../app/src/main/java/com/wtfuck/app/datos/Repositorio.kt) | La comprobación sube al principio de `enviarAdjunto` |

## Lo verificado

| Qué | Resultado |
|---|---|
| `CabeAdjuntoTest` | ✅ 9 pruebas |
| Suite unitaria del app | ✅ **463 pruebas, 0 fallos** |
| R8 sobre release | ✅ |

## Lo que NO está verificado

- **`FLAG_SECURE` no se ha visto funcionando.** Que el sistema bloquee de
  verdad la captura y tape la miniatura solo se comprueba con el teléfono en
  la mano. La lógica —qué bandera se pone y cuándo— sí está.
- **El texto de Android < 13 no se ha visto en un Android < 13.** La rama está
  escrita y compila; nadie la ha ejecutado en un aparato de esa versión.
- **Interacción con compartir pantalla en una llamada:** con el ajuste
  encendido, si alguien comparte su pantalla, la ventana de wtfuck saldrá en
  negro. Es el comportamiento correcto de `FLAG_SECURE`, pero no está
  comprobado ni avisado en la pantalla de llamada.
