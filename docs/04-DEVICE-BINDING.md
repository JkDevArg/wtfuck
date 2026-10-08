# Un usuario por dispositivo — qué se hace, qué no, y qué haría falta

Requisito: *"solo se permite un usuario por dispositivo o hardware, así no se
podrían duplicar cuentas."*

Hay que ser exacto sobre **qué garantiza** el mecanismo y qué no, porque en
Android casi todo lo que la gente asume sobre identificadores de hardware es
falso desde 2019 — y porque este documento, hasta el 2026-10-08, prometía una
verificación que el servidor **no hace**. La corrección está en
[«Lo que el servidor verifica hoy»](#lo-que-el-servidor-verifica-hoy) y la
evidencia en [`evidencias/nivel-de-hardware/`](evidencias/nivel-de-hardware/README.md).

---

## Lo que NO sirve (y por qué)

| Identificador | Por qué no |
|---|---|
| **IMEI / número de serie** | Bloqueado desde Android 10. Requiere `READ_PRIVILEGED_PHONE_STATE`, permiso exclusivo de apps del sistema. Tu app **no** puede leerlo. Punto |
| **Dirección MAC de Wi-Fi** | Aleatorizada por dispositivo desde Android 10. Devuelve un valor falso |
| **Advertising ID** | El usuario lo resetea con dos toques, y usarlo para identificación persistente viola la política de Play |
| **`ANDROID_ID` (SSAID) solo** | Sirve, pero es un dato que la app *declara*. Un cliente modificado devuelve lo que quiera. Es una señal, no una prueba |

El patrón común de todos: son valores que **el cliente le dice al servidor**.
Cualquier cosa que el cliente afirma, el cliente puede mentir.

---

## Lo que el servidor verifica hoy

**Nada de hardware.** Las tres cosas que llegan al registrarse son
declaraciones del cliente:

| Campo | Qué es | Quién lo comprueba |
|---|---|---|
| `hardwareNivel` | `STRONGBOX` / `TEE` / `SOFTWARE_DEV`, calculado en el teléfono por `Hardware.nivelDe()` | Nadie. El servidor solo mira **que el texto sea uno de la lista** y, si es `SOFTWARE_DEV`, que el interruptor lo permita |
| `hardwareHash` | `SHA-256("wtfuck:v1:" ‖ SSAID)`, calculado en el teléfono | Nadie. El servidor solo exige que no esté repetido entre aparatos activos |
| `identidadPub` | La clave pública del Keystore | Nadie. Se guarda tal cual |

Dónde se ve en el código:

- `Repo.nivelParaPrincipal` / `nivelParaVincular` / `nivelDeTelefono`
  (`server/.../Repo.kt`): comparan un string, no hay cadena que validar.
  Los usan el registro (`Repo.registrar`), la recuperación
  (`Identidad.recuperarDispositivo`) y la vinculación (`Dispositivos.vincular`).
- `Hardware.generar()` (`app/.../datos/Hardware.kt`): el reto de
  `setAttestationChallenge` lo **inventa el propio teléfono**, y la cadena de
  certificados (`KeyStore.getCertificateChain`) **nunca se envía**. Un reto
  que no emite el servidor no impide reciclar nada.

### Qué significa en la práctica

- **La app honesta en un emulador** se declara `SOFTWARE_DEV` y el servidor la
  rechaza si `WTFUCK_PERMITIR_SOFTWARE_DEV` no es `true`. Eso sí funciona.
- **Un cliente modificado** —o un script contra la API— declara `TEE`, manda
  un `hardwareHash` al azar y entra, con el interruptor como esté. Cada
  `hardwareHash` distinto es una cuenta más. Lo fijan a propósito
  `NivelHardwareTest` («un TEE declarado se acepta sin prueba») y la sección 3
  de `pruebas/nivel-hardware.mjs`.
- Por eso **«una cuenta por hardware» hoy se cumple contra la app sin
  modificar, no contra alguien que la modifique**. Eleva el costo de «instalar
  la app dos veces» y no el de «escribir un script».

Frenos que sí existen y no dependen de esto: `WTFUCK_REGISTRO=invitacion`
(módulo BC: sin código de invitación no hay registro), la moderación y, desde
el 2026-10-08, un **límite de ritmo por red** en `POST /v1/registro` (300 por
minuto y 2000 por día por IPv4 o por /64 de IPv6, ajustables desde el panel;
ver `Limitador.REGISTRO_RED`). Ese límite convierte "sin tope" en "un tope por
sitio" y nada más: es alto a propósito por el NAT de un campus, y no frena a
quien reparte el script entre muchas IPs. Contra eso, las dos salidas son la
invitación o verificar la atestación.

### El defecto de configuración que se corrigió (2026-10-08)

`WTFUCK_PERMITIR_SOFTWARE_DEV` valía `true` **si nadie la definía**. Producción
la fijaba en `false` en los `docker-compose`, pero un despliegue que se olvidara
la línea aceptaba emuladores sin aviso: fail-open. Ahora:

- El defecto es `false`. Solo `"true"` abre; cualquier otro valor —vacío,
  `1`, `si`, una errata— cierra (`Config.leerPermitirSoftwareDev`).
- `pruebas/arrancar-servidor.ps1` la pone en `true` a la vista, y su opción
  `-SinEmuladores` levanta una instancia con el defecto para probarlo.
- Con `true`, el servidor lo advierte en la bitácora al arrancar.

Es una mejora real pero **pequeña**: por lo dicho arriba, el interruptor frena
a la app honesta, no a un atacante.

---

## Lo que haría falta para verificar de verdad: Key Attestation

**No está implementado.** Esto es lo que habría que construir, con su costo.

### El mecanismo

```
1. El SERVIDOR emite un reto aleatorio de un solo uso (p. ej. 32 bytes,
   vence en 5 min) para este intento de registro / vínculo / recuperación.

2. La app genera un par de claves NUEVO en el Keystore con
   setAttestationChallenge(reto_del_servidor).

3. El TEE / StrongBox devuelve una cadena X.509; la app la manda entera
   (getCertificateChain) junto con el registro.

4. El servidor:
     a. valida las firmas de la cadena hasta una raíz de atestación de Google;
     b. comprueba que ningún certificado esté revocado
        (lista de estado: https://android.googleapis.com/attestation/status);
     c. lee la extensión de atestación (OID 1.3.6.1.4.1.11129.2.1.17) y exige:
          - attestationChallenge == el reto que emitió (y lo quema);
          - attestationSecurityLevel = TrustedEnvironment o StrongBox;
          - origin = GENERATED (la clave nació dentro, no se importó);
          - rootOfTrust: verifiedBootState = VERIFIED y deviceLocked = true;
          - attestationApplicationId: el paquete y el certificado de firma
            de NUESTRA app (eso es lo que deja fuera al cliente modificado);
     d. guarda el nivel QUE LEYÓ, no el que declaró el cliente.
```

La diferencia con lo de hoy: el servidor verifica una firma que viene del chip,
no la palabra del cliente.

### Dos cosas distintas que conviene no mezclar

| Necesidad | Qué la resuelve | Hoy |
|---|---|---|
| **Probar que es hardware real y nuestra app** | La cadena de atestación, validada en el servidor | ❌ No se hace: el nivel es declarado |
| **Reconocer el mismo aparato a lo largo del tiempo** | El SSAID | ✅ Sobrevive a borrar los datos de la app |

Ojo: la atestación **no da un identificador único del aparato** a una app
normal (los ID de dispositivo en la atestación exigen permisos de sistema o de
propietario del dispositivo, y las claves de atestación se rotan justamente
para que no sirvan de huella). El `hardware_hash` seguiría saliendo del SSAID.
Lo que cambia es que el SSAID lo mandaría **nuestra app sin modificar en un
teléfono con arranque verificado**, porque eso es lo que la cadena acredita, y
entonces sí es difícil de falsear.

### El costo, dicho antes de empezar

| Qué | Estimación / riesgo |
|---|---|
| Servidor: ruta del reto, almacén de retos de un solo uso, verificador de cadena, caché de la lista de revocación, pruebas | 2–4 días. Hay una biblioteca de Google en Kotlin (`android/keyattestation`, también en `platform/external/keyattestation` de AOSP); verificar su estado y versión antes de adoptarla, no fijarla de memoria |
| App: clave nueva por intento con el reto del servidor, enviar la cadena en registro / vínculo / recuperación; contrato nuevo en `protocol/` | 1–2 días |
| **Fixtures reales**: un emulador no produce una cadena válida, así que las pruebas necesitan cadenas capturadas de **teléfonos físicos** (al menos uno con TEE y uno con StrongBox) | Requiere hardware; sin eso no hay prueba honesta |
| **Raíces que rotan**: Google ya cambió la raíz para los aparatos con aprovisionamiento remoto (RKP) —de RSA a ECDSA P-384, obligatoria desde abril de 2026—. Un verificador con la raíz vieja rechaza aparatos legítimos | Mantenimiento permanente: tomar las raíces de la fuente oficial (`https://android.googleapis.com/attestation/root`) y revisarlas |
| **Falsos rechazos**: aparatos sin certificación de Google, ROMs alternativas, bootloader desbloqueado | Personas legítimas que no podrían registrarse. Decidir política antes, no después |
| **Fugas de keybox**: claves de atestación filtradas de fabricantes permiten falsificar cadenas hasta que Google las revoca | Por eso la lista de revocación no es opcional; aun así es una carrera |

**Recomendación de despliegue si se hace:** primero en modo *solo registrar*
—verificar, guardar el resultado en una columna y **no** rechazar— durante unas
semanas, para medir cuántos aparatos reales fallan y por qué. Solo con ese dato
se decide si se exige. Activarlo de golpe convierte un defecto de seguridad en
uno de disponibilidad.

**Alternativa a evaluar:** Play Integrity API, que hace esta verificación del
lado de Google. A cambio exige Google Play Services y que la app esté en Play,
y esta app se distribuye fuera de la tienda.

---

## La huella del aparato: por qué solo el SSAID

El primer intento fue derivar la huella de la clave del Keystore **y** el SSAID:

```
hardware_hash = SHA-256( clave_pública_del_keystore || SSAID )    ← INCORRECTO
```

Se ve más robusto y es justo lo contrario. La clave del Keystore **se regenera
al borrar los datos de la app**, así que la huella cambiaba con cada "borrar
datos" y el vínculo se soltaba solo: exactamente el agujero que se quería cerrar.
Se detectó porque una reinstalación durante las pruebas cerró la sesión sola.

Lo correcto es derivar la identidad del factor que **persiste**:

```
hardware_hash = SHA-256( "wtfuck:v1:" || SSAID )
```

Borrar los datos de la app ya no libera el vínculo; solo un factory reset lo
hace, que es el techo declarado más abajo. La clave del Keystore queda para
otra cosa —el nivel de hardware—, que hoy es declarado (ver arriba).

En el esquema esto es la columna `dispositivo.hardware_hash`, con:

```sql
CREATE UNIQUE INDEX dispositivo_unico_por_hardware
    ON dispositivo (hardware_hash) WHERE revocado_en IS NULL;
```

Un hardware activo, una cuenta. Verificado: el test T6 confirma que un segundo
usuario con el mismo `hardware_hash` es rechazado por la base. Lo que la base no
puede saber es si ese hash salió de un SSAID real o de un generador al azar.

---

## Límites reales — dilos antes de que te los digan

| Evasión | ¿Se puede evitar? |
|---|---|
| **Cliente modificado / script contra la API** → declara `TEE` y un `hardwareHash` al azar | ❌ **Hoy no.** Se cerraría con Key Attestation (sección anterior). Mientras tanto: `WTFUCK_REGISTRO=invitacion` y, pendiente, un límite de ritmo en el registro |
| **Emulador con la app honesta** | ✅ Rechazado si `WTFUCK_PERMITIR_SOFTWARE_DEV` no es `true` (el defecto desde el 2026-10-08) |
| **Emulador / rooteado con la app modificada** | ❌ Hoy no: es el mismo caso que el cliente modificado. Con atestación sería detectable (`verifiedBootState`, `attestationApplicationId`) |
| **Factory reset** → claves y SSAID nuevos → cuenta nueva | ❌ No, ni con atestación. Ni WhatsApp lo evita. Es el techo de la técnica |
| **Otro teléfono** | ❌ No, y no deberías: es hardware distinto, es una cuenta legítima |
| **Perfiles de trabajo / multiusuario Android** | ⚠️ SSAID distinto por perfil. Una cuenta más por perfil |

Lo que esto **sí** logra hoy: que la app sin modificar no cree dos cuentas en el
mismo teléfono sin un factory reset. Lo que **no** logra hoy es frenar a alguien
que hable con la API directamente. Con atestación, el piso subiría a "un
teléfono físico con arranque verificado y un factory reset por cuenta".

Si necesitas certeza real de unicidad de persona, el vínculo no puede ser el
hardware: tiene que ser el registro institucional (código de estudiante o
colaborador) validado contra el directorio. El hardware complementa, no sustituye.

---

## El problema con los emuladores

Un emulador **no tiene TEE**. No produce una cadena de atestación válida, y la
app se declara `SOFTWARE_DEV`. Con la regla estricta los AVDs no podrían
registrarse nunca y no habría forma de probar el chat.

Por eso `hardware_nivel` es una columna y no un booleano:

```
STRONGBOX     chip dedicado. Pixel y gama alta reciente      (declarado)
TEE           enclave seguro del SoC. La mayoría de Android  (declarado)
SOFTWARE_DEV  sin enclave. Solo con WTFUCK_PERMITIR_SOFTWARE_DEV=true
NAVEGADOR     la versión web (V49). Sin enclave: entra SOLO vinculándose desde
              un aparato de la cuenta, nunca es el principal y su sesión dura
              30 días. Ver docs/12-VERSION-WEB.md
```

Reglas de despliegue:

```kotlin
// app/build.gradle.kts  ->  buildConfigField (lo que la app muestra en Diagnóstico)
// debug:   PERMITIR_SOFTWARE_DEV = true
// release: PERMITIR_SOFTWARE_DEV = false
```

```
# servidor
WTFUCK_PERMITIR_SOFTWARE_DEV   sin definir -> false (rechaza SOFTWARE_DEV)
                               "true"      -> acepta emuladores; solo desarrollo
```

El servidor decide **qué niveles admite**, no la app: un cliente modificado
puede mentir sobre su propio build. Lo que el servidor no puede decidir hoy es
**si el nivel es cierto** — ver [«Lo que el servidor verifica hoy»](#lo-que-el-servidor-verifica-hoy).

En los emuladores `Pixel9_API36` y `Pixel10_API37` la identidad se deriva igual
(par de claves + SSAID, que sí son distintos entre AVDs), marcada como
`SOFTWARE_DEV`. Se puede probar el chat completo contra un servidor de
desarrollo.

---

## Nota legal (Ley 29733)

Vincular una cuenta a un dispositivo es, técnicamente, *fingerprinting*. Para un
despliegue dentro de EducaD hay que declararlo en el aviso de privacidad: qué se
guarda (un hash, no un identificador reversible), para qué (evitar cuentas
duplicadas), y cuánto tiempo. No se almacena el SSAID ni la clave en claro —
solo el hash — y eso es lo que hace la medida proporcional. Si se implementa la
atestación, la cadena de certificados es un dato nuevo que habría que declarar
igual (y decidir si se guarda o se descarta tras verificarla; lo proporcional
es descartarla y quedarse con el resultado).
