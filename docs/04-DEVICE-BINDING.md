# Un usuario por dispositivo — cómo se hace de verdad

Requisito: *"solo se permite un usuario por dispositivo o hardware, así no se
podrían duplicar cuentas."*

Se puede, y bastante bien. Pero hay que ser exacto sobre **qué garantiza** el
mecanismo y qué no, porque en Android casi todo lo que la gente asume sobre
identificadores de hardware es falso desde 2019.

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

## Lo que sí sirve: atestación del Android Keystore

La única prueba real disponible es **Key Attestation**. El mecanismo:

```
1. La app pide al Keystore generar un par de claves,
   pasando un reto aleatorio del servidor (setAttestationChallenge).

2. El TEE / StrongBox del chip genera la clave y devuelve una
   cadena de certificados X.509 firmada por la raíz de atestación
   de Google.

3. La app manda esa cadena al servidor.

4. El servidor valida la cadena contra la raíz de Google y lee:
      - que la clave vive en hardware (TEE o StrongBox)
      - que NO es exportable
      - que el reto coincide  -> no es una cadena reciclada
      - el estado del bootloader (bloqueado / desbloqueado)
```

La diferencia con todo lo anterior: el servidor **verifica una firma de Google**,
no la palabra del cliente. La clave privada no puede salir del chip ni siquiera
con root.

### Dos cosas distintas que conviene no mezclar

Al implementarlo apareció una trampa que vale la pena dejar escrita, porque es
fácil caer en ella:

| Necesidad | Qué la resuelve | Por qué |
|---|---|---|
| **Probar que es hardware real** | La clave atestada del Keystore | El servidor verifica una firma de Google |
| **Reconocer el mismo aparato a lo largo del tiempo** | El SSAID | Sobrevive a borrar los datos de la app |

El primer intento fue derivar la huella de **ambos**:

```
hardware_hash = SHA-256( clave_pública_atestada || SSAID )    ← INCORRECTO
```

Se ve más robusto y es justo lo contrario. La clave del Keystore **se regenera
al borrar los datos de la app**, así que la huella cambiaba con cada "borrar
datos" y el vínculo se soltaba solo: exactamente el agujero que se quería cerrar.
Se detectó porque una reinstalación durante las pruebas cerró la sesión sola.

Lo correcto es derivar la identidad del factor que **persiste**:

```
hardware_hash = SHA-256( "wtfuck:v1:" || SSAID )
```

y usar la clave atestada para lo suyo: acreditar el enclave seguro y firmar.
Borrar los datos de la app ya no libera el vínculo; solo un factory reset lo hace,
que es el techo declarado más abajo.

En el esquema esto es la columna `dispositivo.hardware_hash`, con:

```sql
CREATE UNIQUE INDEX dispositivo_unico_por_hardware
    ON dispositivo (hardware_hash) WHERE revocado_en IS NULL;
```

Un hardware activo, una cuenta. Verificado: el test T6 confirma que un segundo
usuario con el mismo `hardware_hash` es rechazado por la base.

---

## Límites reales — dilos antes de que te los digan

Ningún esquema de vinculación a hardware es absoluto. Estos son los huecos que
quedan y no se pueden cerrar:

| Evasión | ¿Se puede evitar? |
|---|---|
| **Factory reset** → claves y SSAID nuevos → cuenta nueva | ❌ No. Ni WhatsApp lo evita. Es el techo de la técnica |
| **Otro teléfono** | ❌ No, y no deberías: es hardware distinto, es una cuenta legítima |
| **Emulador / dispositivo rooteado** | ⚠️ Detectable. Sin cadena válida de Google, el nivel baja a `SOFTWARE_DEV`. Tú decides si lo aceptas |
| **Perfiles de trabajo / multiusuario Android** | ⚠️ SSAID distinto por perfil. Una cuenta más por perfil |

Lo que esto **sí** logra: eleva el costo de crear cuentas masivas de "un script"
a "un factory reset por cuenta". Para un sistema cerrado, es más que suficiente.
Lo que **no** logra es impedir que una persona decidida tenga dos cuentas.

Si necesitas certeza real de unicidad de persona, el vínculo no puede ser el
hardware: tiene que ser el registro institucional (código de estudiante o
colaborador) validado contra el directorio. El hardware complementa, no sustituye.

---

## El problema con tus dos emuladores

Un emulador **no tiene TEE**. No produce una cadena de atestación válida.
Si aplicas la regla estricta, tus dos AVDs no podrán registrarse nunca — y te
quedas sin poder probar el chat.

Por eso `hardware_nivel` es una columna y no un booleano:

```
STRONGBOX     chip dedicado. Lo mejor. Pixel y gama alta reciente
TEE           enclave seguro del SoC. La mayoría de Android moderno
SOFTWARE_DEV  sin atestación válida. SOLO en build de depuración
```

Regla de despliegue:

```kotlin
// build.gradle.kts  ->  buildConfigField
// debug:   PERMITIR_SOFTWARE_DEV = true
// release: PERMITIR_SOFTWARE_DEV = false
```

Y el servidor **también** lo valida, no solo la app: un cliente modificado puede
mentir sobre su propio build. En producción, `hardware_nivel = 'SOFTWARE_DEV'`
se rechaza del lado del servidor.

Con esto, en tus emuladores `Pixel9_API36` y `Pixel10_API37` la identidad se
deriva igual (par de claves + SSAID, que sí son distintos entre AVDs), solo que
marcada como `SOFTWARE_DEV`. Puedes probar el chat completo, y la regla de
"una cuenta por hardware" sigue siendo real en dispositivos reales.

---

## Nota legal (Ley 29733)

Vincular una cuenta a un dispositivo es, técnicamente, *fingerprinting*. Para un
despliegue dentro de EducaD hay que declararlo en el aviso de privacidad: qué se
guarda (un hash, no un identificador reversible), para qué (evitar cuentas
duplicadas), y cuánto tiempo. No se almacena el SSAID ni la clave en claro —
solo el hash — y eso es lo que hace la medida proporcional.
