# wtfuck — Arquitectura

## Principio rector

El servidor es un **buzón tonto**. Almacena y reenvía sobres opacos.
No sabe leer contenido, no reconstruye conversaciones, no indexa texto.

Todo lo que el servidor conoce de un mensaje es: quién lo dejó, para qué
dispositivo, cuándo, y cuántos bytes pesa. Nada más.

Esta decisión es la que hace el sistema escalable *y* privado al mismo tiempo:
un buzón sin lógica se replica horizontalmente sin coordinación.

---

## Módulos

```
wtfuck/
├── protocol/   Kotlin puro (sin Android, sin Ktor)
│               El contrato. Define el Sobre y los tipos de carga.
│               Lo compilan el servidor y la app. Fuente única de verdad.
│
├── server/     Ktor + PostgreSQL
│               HTTP para registro/login/prekeys. WebSocket para entrega.
│               La sesión y el buzón viven en Postgres. El registro de
│               sockets vivos vive en memoria, con bus opcional a Redis.
│
└── app/        Android nativo — Kotlin + Compose
                Room + SQLCipher local. La clave privada nunca sale de aquí.
```

`protocol/` es el módulo más importante. Si cambias un campo del sobre, el
compilador rompe el servidor **y** la app en el mismo build. En un proyecto de
una sola persona eso vale más que cualquier optimización de runtime.

---

## La capa de transporte (la decisión que habilita `msg off`)

La app **nunca** "manda por WebSocket". La app **encola un sobre**:

```
 Caso de uso
      │
      ▼
 ColaDeSalida  ──► persiste el sobre en Room, estado = PENDIENTE
      │
      ▼
 Despachador   ──► pregunta a cada transporte: ¿puedes entregar esto?
      │
      ├──► TransporteWebSocket   (hay internet)        ← Fase 2
      ├──► TransporteMalla       (hay un peer cerca)   ← Fase 7
      └──► (ninguno)             → se queda PENDIENTE, se reintenta
```

El sobre no sabe por dónde va a salir. El despachador no sabe qué lleva dentro.
Agregar malla P2P en la fase 7 es **registrar un transporte más**, no reescribir
la app.

Si esta capa no existe desde el día uno, `msg off` obliga a rehacer todo.

---

## Qué hace escalable a este diseño

| Decisión | Por qué escala |
|---|---|
| **Servidor casi sin estado** | La sesión, el buzón y los permisos viven en Postgres, así que N instancias comparten todo lo que importa. Lo único en memoria es el registro de **sockets vivos** (`Hub`), y desde el módulo N hay un bus **opcional** para que deje de ser un problema: con `WTFUCK_REDIS_URL`, cada instancia se suscribe a los canales de sus propios sockets y publica lo que va a los de las otras. Sin la variable no abre ninguna conexión y se comporta como siempre, que es lo correcto porque una sola instancia es el caso normal. Verificado con dos instancias en `pruebas/bus.mjs` |
| **UUIDv7 como PK** | Ordenado por tiempo (índices B-tree sin fragmentación) y sin revelar cuántos usuarios tienes. Un `bigserial` obliga a coordinar al fragmentar |
| **Fan-out en escritura** | Al enviar, el sobre se copia al buzón de **cada dispositivo destino**. Leer es un `SELECT` por índice, no un JOIN. Es el modelo de Signal y WhatsApp |
| **Buzón que se vacía** | El sobre se **borra** al confirmarse la entrega. La tabla caliente se mantiene pequeña sin importar cuánto crezca el historial |
| **Historial solo en el cliente** | El servidor no guarda historial. No hay tabla que crezca sin límite, ni backup que filtre conversaciones |
| **`dispositivo` separado de `usuario`** | Hoy es 1:1 por regla de negocio, pero el esquema ya admite N dispositivos. Multi-dispositivo será una migración, no una reescritura |
| **`conversacion` unifica 1:1 y grupo** | Chat directo y grupo son la **misma** tabla con distinto `tipo`. La fase 5 (grupos) no toca el esquema de mensajes |
| **Cuerpo opaco (`bytea`)** | El servidor no parsea. Cambiar el formato interno del mensaje no requiere migrar la base |

---

## Lo que deliberadamente NO está en el MVP

- Multi-dispositivo por usuario (el esquema lo admite, la regla lo prohíbe)
- Llamadas de voz/video (es otro proyecto: WebRTC + SFU + TURN)
- Federación entre servidores
- Historial en servidor

Cada uno de estos, metido temprano, dobla el tiempo del MVP.
