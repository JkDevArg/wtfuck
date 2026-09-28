# Sealed sender — diseño y decisión (NO implementado aún)

Este documento es el **análisis previo**, no una función terminada. Sealed
sender toca el núcleo del mensajero, y antes de escribir una línea hay que
entender qué se gana, qué cuesta y qué bloquea.

---

## El objetivo

Que el servidor **no sepa quién le escribe a quién**. Hoy el contenido ya va
cifrado de extremo a extremo —el servidor mueve `cuerpo` opacos que no puede
abrir— pero sí ve los **metadatos de routing**: qué dispositivo envió, a qué
dispositivos, en qué conversación y cuándo.

## Qué expone hoy el servidor, exactamente

Medido en el código, no de memoria:

- **Al enviar** (`manejarEnvio` en `Main.kt`): el WebSocket está autenticado, así
  que el servidor conoce al emisor (`yo.usuarioId` / `yo.dispositivoId`),
  comprueba que pertenece a la conversación y calcula los destinos desde la
  membresía. **Necesita saber quién es el emisor** para autorizar y rutear.
- **Mientras el sobre está pendiente** (`sobre_pendiente`, `V1__inicial.sql`): la
  fila guarda `origen_dispositivo`, `destino_dispositivo` y `conversacion_id`.
  O sea, **en reposo hay un vínculo emisor↔receptor↔conversación**.
- **Al entregar** (`Bajada.Entrega`): lleva `origenUsuarioId/username/dispositivo`
  al receptor. Eso está bien —el receptor *debe* saber quién le escribió— y no
  es lo que sealed sender oculta.

Lo que **ya** juega a favor de la privacidad:

- `sobre_pendiente` **se borra al entregar**. El vínculo en reposo es
  **transitorio**: existe solo mientras el mensaje no se entregó (máx. 30 días).
- El servidor **no guarda historial** de mensajes entregados. No hay un grafo
  social persistente: es un buzón tonto.

Es decir, la exposición que queda es: (1) el conocimiento **en el momento del
envío** y (2) el `origen_dispositivo` en los sobres **pendientes** (transitorio).

---

## Cómo lo hace Signal

1. **Certificado de emisor**: el servidor le firma a cada usuario un certificado
   (su clave de identidad + caducidad). El receptor lo verifica **sin el
   servidor** en el momento de la entrega.
2. **Sobre sellado**: el emisor cifra —contra la clave de identidad del
   receptor— un paquete que contiene *su propia identidad* + el mensaje Signal
   interno. El servidor ve solo `destino` + un blob opaco. No ve el emisor.
3. **Acceso no identificado**: para que no sea un coladero de spam, el emisor
   incluye una "clave de acceso" que el receptor compartió con sus contactos.
   Sin ella, el servidor exigiría autenticación normal.

---

## Los dos choques con la arquitectura de wtfuck

### 1. El modelo de conversación con membresía en el servidor

wtfuck rutea por **conversación** y el servidor **impone la membresía** (calcula
los destinos legítimos). Sealed sender de Signal **no** tiene membresía en el
servidor: el emisor conoce la dirección del receptor y entrega directo,
autenticándose de forma anónima. Entregar anónimo en wtfuck **rompe** la
comprobación de membresía y el control de abuso —habría que construir un
subsistema de "tokens de acceso no identificado" desde cero.

### 2. libsignal 0.86.5 NO trae el cifrador de sealed sender

Comprobado abriendo el `.aar`: la libsignal moderna **no expone**
`SealedSessionCipher` ni `SenderCertificate` (eran de la libsignal-java vieja).
Signal hoy implementa el sobre sellado en el código de SU app, sobre primitivas.

Consecuencia: el sobre sellado (cifrar identidad-del-emisor + mensaje contra la
clave del receptor, con un certificado de emisor firmado por el servidor)
**habría que construirlo a mano** con las primitivas ECC/AEAD. Es criptografía
artesanal en una app cuyo sentido es el cifrado — justo donde un error cuesta
caro.

---

## Dos niveles posibles

### Nivel A — "sellado en reposo"

El servidor **sigue** autenticando al emisor al enviar (en memoria,
transitorio) y comprueba la membresía —el control de abuso queda intacto— pero
**deja de guardar `origen_dispositivo`** en `sobre_pendiente`. La identidad del
emisor viaja **dentro** del blob cifrado, que solo el receptor abre.

- **Gana:** una foto de la base (o un servidor comprometido *en reposo*) no
  puede mapear el grafo de quién-escribe-a-quién de los mensajes pendientes.
- **No gana:** un servidor comprometido *en vivo*, observando los envíos, sigue
  viendo el emisor en el momento del envío.
- **Cuesta:** el receptor necesita saber qué dispositivo envió para elegir la
  sesión Signal con que descifrar. Hoy se lo da el servidor (`origenDispositivo`
  en `Entrega`); si se quita, hay que meter esa identidad **cifrada** en el blob
  y que el receptor la saque con su propia clave. Sin `SealedSessionCipher` en
  la librería, esa capa es artesanal. Además: migración para quitar la columna,
  cambios de protocolo en `Enviar`/`Entrega`, y tocar el descifrado en el
  cliente.

### Nivel B — "sealed sender completo"

Entrega anónima: el servidor **no sabe** el emisor ni siquiera al enviar.
Requiere certificados de emisor + tokens de acceso no identificado + rehacer la
autorización sin romper el anti-abuso. **Semanas**, y una superficie de abuso
nueva. Beneficio marginal sobre el Nivel A dado que wtfuck **ya** no guarda
grafo persistente.

---

## Recomendación honesta

Dos cosas pesan:

1. **wtfuck ya tiene metadatos mínimos**: sin historial en el servidor y con los
   sobres borrados al entregar, el grafo persistente ya no existe. Lo que sealed
   sender añade es tapar el envío *en vivo* (Nivel B) o el sobre pendiente *en
   reposo* (Nivel A).
2. **El costo/riesgo es alto**: sin soporte en la librería, la parte
   criptográfica es artesanal, en el camino más sensible del sistema.

Por eso, antes de construir, la decisión no es solo "qué nivel" sino "**vale la
pena ahora**". Alternativa a considerar: la **copia de seguridad cifrada**
—hoy pierdes el teléfono y pierdes todo— entrega más valor tangible al usuario
por bastante menos riesgo, y no toca el núcleo cripto.

Si se sigue con sealed sender, el orden sería:

- **Fase 1** — Certificado de emisor firmado por el servidor (aditivo, no toca
  la entrega, testeable solo). Común a los dos niveles.
- **Fase 2** — Sobre sellado artesanal (cifrar identidad+mensaje a la clave del
  receptor) con pruebas de cripto exhaustivas contra vectores conocidos.
- **Fase 3** — Nivel A: quitar `origen_dispositivo` del almacenamiento; el
  receptor saca el emisor del blob. Migración + protocolo + descifrado.
- **Fase 4** (opcional) — Nivel B: entrega anónima + tokens de acceso.

Cada fase con su suite, y ninguna que deje el mensajero a medias.
