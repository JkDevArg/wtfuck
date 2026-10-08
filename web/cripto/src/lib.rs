//! libsignal para el cliente web de wtfuck.
//!
//! ## Por qué existe
//!
//! Signal no publica libsignal para el navegador: `@signalapp/libsignal-client`
//! es nativo de Node y Electron. Pero el crate `libsignal-protocol` es Rust
//! puro y compila a `wasm32-unknown-unknown` sin tocarlo. Este crate es el
//! envoltorio mínimo que lo expone a JavaScript con `wasm-bindgen`.
//!
//! ## La misma versión que Android
//!
//! Se compila la **v0.86.5**, la que usa la app (`gradle/libs.versions.toml`).
//! No es un capricho: el formato de sesión y el ratchet poscuántico cambian
//! entre versiones, y un mensaje que la web cifra lo tiene que abrir el
//! teléfono. `web/interop-jvm` lo comprueba contra la libsignal de la JVM.
//!
//! ## Lo que se parece a la app a propósito
//!
//! - La dirección de Signal es el **dispositivo**: su UUID va en el nombre y el
//!   deviceId es siempre 1 (ver `CifradorSignal.dir` en la app).
//! - Los JSON de claves son los del contrato (`protocol/Claves.kt`):
//!   `PublicarClavesReq` y `PaqueteClaves`, con los mismos nombres de campo.
//! - La huella usa la versión 1 y 5200 iteraciones, como `CifradorSignal`.
//!
//! ## El reloj
//!
//! En `wasm32-unknown-unknown`, `SystemTime::now()` entra en pánico: no hay
//! reloj del sistema. Por eso toda función que necesita la hora la recibe de
//! JavaScript (`Date.now()`), y las prekeys Kyber se arman a mano en vez de
//! con `KyberPreKeyRecord::generate`, que lee el reloj por dentro.
//!
//! ## El almacén
//!
//! [almacen::Almacen]: se exporta e importa como JSON, y la web lo guarda
//! cifrado en IndexedDB después de cada operación. Ver `almacen.rs`.

mod almacen;

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use futures::executor::block_on;
use libsignal_protocol::{
    CiphertextMessage, DeviceId, Fingerprint, GenericSignedPreKey, IdentityKey, IdentityKeyPair,
    IdentityKeyStore, KeyPair, KyberPreKeyRecord, KyberPreKeyStore,
    PreKeyBundle, PreKeyRecord, PreKeySignalMessage, PreKeyStore, ProtocolAddress, PublicKey,
    SenderKeyDistributionMessage, SessionStore, SignalMessage, SignedPreKeyRecord,
    SignedPreKeyStore, Timestamp, create_sender_key_distribution_message, group_decrypt,
    group_encrypt, kem, message_decrypt, message_encrypt, process_prekey_bundle,
    process_sender_key_distribution_message,
};
use rand::TryRngCore as _;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use wasm_bindgen::prelude::*;

use crate::almacen::Almacen;

/// El deviceId de Signal. Siempre 1: el dispositivo va en el nombre.
const DEVICE: u32 = 1;
/// Formato de la huella. Ver `CifradorSignal.VERSION_HUELLA`.
const VERSION_HUELLA: u32 = 1;
/// Las de Signal. Con otro número las huellas no coinciden entre aparatos.
const ITERACIONES_HUELLA: u32 = 5200;

/// La versión de libsignal compilada. La muestra la página de pruebas.
pub const VERSION_LIBSIGNAL: &str = "v0.86.5";

type R<T> = Result<T, String>;

fn e<E: std::fmt::Display>(x: E) -> String {
    x.to_string()
}

fn dec(b64: &str) -> R<Vec<u8>> {
    B64.decode(b64).map_err(|x| format!("base64: {x}"))
}

fn dir(dispositivo: &str) -> R<ProtocolAddress> {
    let d = DeviceId::try_from(DEVICE).map_err(|_| "deviceId".to_string())?;
    Ok(ProtocolAddress::new(dispositivo.to_owned(), d))
}

/// La hora que manda JavaScript, en milisegundos desde 1970.
fn instante(ms: f64) -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(ms.max(0.0) as u64)
}

// ---------------------------------------------------------------------------
//  Los JSON del contrato (protocol/Claves.kt). Mismos nombres de campo.
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClavePublica {
    key_id: u32,
    publica: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClaveFirmada {
    key_id: u32,
    publica: String,
    firma: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PublicarClavesReq {
    registration_id: u32,
    identidad: String,
    firmada: ClaveFirmada,
    kyber: ClaveFirmada,
    unicas: Vec<ClavePublica>,
}

/// Lo que devuelve el servidor en `GET /v1/claves/{dispositivo}`. Los campos
/// que no hacen falta para abrir sesión (usuario, username) se ignoran.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PaqueteClaves {
    registration_id: u32,
    identidad: String,
    firmada: ClaveFirmada,
    kyber: ClaveFirmada,
    #[serde(default)]
    unica: Option<ClavePublica>,
}

/// Un cuerpo cifrado: el tipo de Signal (2, 3 o 7) y los bytes en base64.
/// Es lo que va en `CopiaCifrada` del contrato.
#[derive(Serialize)]
struct Copia {
    tipo: u8,
    cuerpo: String,
}

#[derive(Serialize)]
struct Huella {
    digitos: String,
    escaneable: String,
}

// ---------------------------------------------------------------------------
//  El aparato
// ---------------------------------------------------------------------------

/// Un aparato con su identidad de Signal y su almacén.
#[wasm_bindgen]
pub struct Cliente {
    almacen: Almacen,
}

/// La lógica, con errores en texto. Las pruebas nativas la llaman directo:
/// fuera del navegador no se puede construir un `JsError`.
impl Cliente {
    pub fn crear() -> R<Cliente> {
        let mut r = OsRng.unwrap_err();
        let identidad = IdentityKeyPair::generate(&mut r);
        // 1..=16380, el mismo rango que KeyHelper.generateRegistrationId.
        let registro = (rand::Rng::random::<u32>(&mut r) % 16380) + 1;
        Ok(Cliente { almacen: Almacen::nuevo(&identidad, registro) })
    }

    pub fn identidad_b64(&self) -> R<String> {
        let par = block_on(self.almacen.identidades.get_identity_key_pair()).map_err(e)?;
        Ok(B64.encode(par.identity_key().serialize()))
    }

    pub fn registro(&self) -> R<u32> {
        block_on(self.almacen.identidades.get_local_registration_id()).map_err(e)
    }

    pub fn claves(&mut self, firmada_id: u32, kyber_id: u32, desde: u32, cuantas: u32, ahora_ms: f64) -> R<String> {
        let mut r = OsRng.unwrap_err();
        let par = block_on(self.almacen.identidades.get_identity_key_pair()).map_err(e)?;
        let ts = Timestamp::from_epoch_millis(ahora_ms.max(0.0) as u64);

        let pf = KeyPair::generate(&mut r);
        let ff = par.private_key().calculate_signature(&pf.public_key.serialize(), &mut r).map_err(e)?;
        let rf = SignedPreKeyRecord::new(firmada_id.into(), ts, &pf, &ff);
        block_on(self.almacen.firmadas.save_signed_pre_key(firmada_id.into(), &rf)).map_err(e)?;

        // A mano y no con KyberPreKeyRecord::generate: esa lee el reloj.
        let pk = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut r);
        let fk = par.private_key().calculate_signature(&pk.public_key.serialize(), &mut r).map_err(e)?;
        let rk = KyberPreKeyRecord::new(kyber_id.into(), ts, &pk, &fk);
        block_on(self.almacen.kyber.save_kyber_pre_key(kyber_id.into(), &rk)).map_err(e)?;

        let mut unicas = Vec::with_capacity(cuantas as usize);
        for i in 0..cuantas {
            let id = desde + i;
            let p = KeyPair::generate(&mut r);
            block_on(self.almacen.unicas.save_pre_key(id.into(), &PreKeyRecord::new(id.into(), &p))).map_err(e)?;
            unicas.push(ClavePublica { key_id: id, publica: B64.encode(p.public_key.serialize()) });
        }

        let req = PublicarClavesReq {
            registration_id: self.registro()?,
            identidad: self.identidad_b64()?,
            firmada: ClaveFirmada { key_id: firmada_id, publica: B64.encode(pf.public_key.serialize()), firma: B64.encode(&ff) },
            kyber: ClaveFirmada { key_id: kyber_id, publica: B64.encode(pk.public_key.serialize()), firma: B64.encode(&fk) },
            unicas,
        };
        serde_json::to_string(&req).map_err(e)
    }

    pub fn sesion_desde_paquete(&mut self, dispositivo: &str, paquete_json: &str, ahora_ms: f64) -> R<()> {
        let p: PaqueteClaves = serde_json::from_str(paquete_json).map_err(|x| format!("paquete: {x}"))?;
        let unica = match &p.unica {
            Some(u) => Some((u.key_id.into(), PublicKey::deserialize(&dec(&u.publica)?).map_err(e)?)),
            None => None,
        };
        let d = DeviceId::try_from(DEVICE).map_err(|_| "deviceId".to_string())?;
        let paquete = PreKeyBundle::new(
            p.registration_id,
            d,
            unica,
            p.firmada.key_id.into(),
            PublicKey::deserialize(&dec(&p.firmada.publica)?).map_err(e)?,
            dec(&p.firmada.firma)?,
            p.kyber.key_id.into(),
            kem::PublicKey::deserialize(&dec(&p.kyber.publica)?).map_err(e)?,
            dec(&p.kyber.firma)?,
            IdentityKey::try_from(dec(&p.identidad)?.as_slice()).map_err(e)?,
        )
        .map_err(e)?;
        let mut r = OsRng.unwrap_err();
        let a = dir(dispositivo)?;
        let s = &mut self.almacen;
        block_on(process_prekey_bundle(
            &a,
            &mut s.sesiones,
            &mut s.identidades,
            &paquete,
            instante(ahora_ms),
            &mut r,
        ))
        .map_err(e)
    }

    pub fn hay_sesion(&self, dispositivo: &str) -> R<bool> {
        let a = dir(dispositivo)?;
        Ok(block_on(self.almacen.sesiones.load_session(&a)).map_err(e)?.is_some())
    }

    pub fn cifrar_para(&mut self, dispositivo: &str, claro: &[u8], ahora_ms: f64) -> R<String> {
        let mut r = OsRng.unwrap_err();
        let a = dir(dispositivo)?;
        let s = &mut self.almacen;
        let m = block_on(message_encrypt(
            claro,
            &a,
            &mut s.sesiones,
            &mut s.identidades,
            instante(ahora_ms),
            &mut r,
        ))
        .map_err(e)?;
        let c = Copia { tipo: m.message_type() as u8, cuerpo: B64.encode(m.serialize()) };
        serde_json::to_string(&c).map_err(e)
    }

    pub fn descifrar_de(&mut self, dispositivo: &str, tipo: u8, cuerpo_b64: &str) -> R<Vec<u8>> {
        let bytes = dec(cuerpo_b64)?;
        let msg = match tipo {
            3 => CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(bytes.as_slice()).map_err(e)?),
            2 => CiphertextMessage::SignalMessage(SignalMessage::try_from(bytes.as_slice()).map_err(e)?),
            otro => return Err(format!("el tipo {otro} no es un mensaje por pares")),
        };
        let mut r = OsRng.unwrap_err();
        let a = dir(dispositivo)?;
        let s = &mut self.almacen;
        block_on(message_decrypt(
            &msg,
            &a,
            &mut s.sesiones,
            &mut s.identidades,
            &mut s.unicas,
            &s.firmadas,
            &mut s.kyber,
            &mut r,
        ))
        .map_err(e)
    }

    pub fn distribucion(&mut self, mi_dispositivo: &str, dist_id: &str) -> R<String> {
        let id = Uuid::parse_str(dist_id).map_err(e)?;
        let mut r = OsRng.unwrap_err();
        let a = dir(mi_dispositivo)?;
        let skdm = block_on(create_sender_key_distribution_message(&a, id, &mut self.almacen.emisores, &mut r))
            .map_err(e)?;
        Ok(B64.encode(skdm.serialized()))
    }

    pub fn aceptar_distribucion(&mut self, remitente: &str, skdm_b64: &str) -> R<()> {
        let bytes = dec(skdm_b64)?;
        let skdm = SenderKeyDistributionMessage::try_from(bytes.as_slice()).map_err(e)?;
        let a = dir(remitente)?;
        block_on(process_sender_key_distribution_message(&a, &skdm, &mut self.almacen.emisores)).map_err(e)
    }

    pub fn cifrar_en_grupo(&mut self, mi_dispositivo: &str, dist_id: &str, claro: &[u8]) -> R<String> {
        let id = Uuid::parse_str(dist_id).map_err(e)?;
        let mut r = OsRng.unwrap_err();
        let a = dir(mi_dispositivo)?;
        let m = block_on(group_encrypt(&mut self.almacen.emisores, &a, id, claro, &mut r)).map_err(e)?;
        Ok(B64.encode(m.serialized()))
    }

    pub fn descifrar_de_grupo(&mut self, remitente: &str, cuerpo_b64: &str) -> R<Vec<u8>> {
        let bytes = dec(cuerpo_b64)?;
        let a = dir(remitente)?;
        block_on(group_decrypt(&bytes, &mut self.almacen.emisores, &a)).map_err(e)
    }

    pub fn huella_con(&self, mi_usuario: &str, otro_usuario: &str, otro_dispositivo: &str) -> R<String> {
        let mia = *block_on(self.almacen.identidades.get_identity_key_pair()).map_err(e)?.identity_key();
        let a = dir(otro_dispositivo)?;
        let suya = block_on(self.almacen.identidades.get_identity(&a))
            .map_err(e)?
            .ok_or_else(|| "no hay identidad de ese aparato todavía".to_string())?;
        let f = Fingerprint::new(
            VERSION_HUELLA,
            ITERACIONES_HUELLA,
            mi_usuario.as_bytes(),
            &mia,
            otro_usuario.as_bytes(),
            &suya,
        )
        .map_err(e)?;
        let h = Huella {
            digitos: f.display_string().map_err(e)?,
            escaneable: B64.encode(f.scannable.serialize().map_err(e)?),
        };
        serde_json::to_string(&h).map_err(e)
    }
}

impl Cliente {
    pub fn exportar_json(&self) -> R<String> {
        self.almacen.exportar()
    }

    pub fn importar_json(json: &str) -> R<Cliente> {
        Ok(Cliente { almacen: Almacen::importar(json)? })
    }

    pub fn cambio_de(&self, dispositivo: &str) -> R<bool> {
        Ok(self.almacen.cambio(&dir(dispositivo)?))
    }
}

fn js(x: String) -> JsError {
    JsError::new(&x)
}

/// Lo que ve JavaScript. Cada función devuelve JSON o bytes, y lanza un
/// `Error` con el motivo si algo falla.
#[wasm_bindgen]
impl Cliente {
    /// Un aparato nuevo: identidad y registrationId al azar.
    #[wasm_bindgen(constructor)]
    pub fn new() -> Result<Cliente, JsError> {
        Cliente::crear().map_err(js)
    }

    /// Base64 de la identidad pública.
    pub fn identidad(&self) -> Result<String, JsError> {
        self.identidad_b64().map_err(js)
    }

    #[wasm_bindgen(js_name = registrationId)]
    pub fn registration_id(&self) -> Result<u32, JsError> {
        self.registro().map_err(js)
    }

    /// Genera la firmada, la Kyber y `cuantas` únicas desde `desde`. Devuelve
    /// el JSON de `PublicarClavesReq`, listo para `PUT /v1/claves`.
    #[wasm_bindgen(js_name = generarClaves)]
    pub fn generar_claves(&mut self, firmada_id: u32, kyber_id: u32, desde: u32, cuantas: u32, ahora_ms: f64) -> Result<String, JsError> {
        self.claves(firmada_id, kyber_id, desde, cuantas, ahora_ms).map_err(js)
    }

    /// Abre sesión (PQXDH) con un aparato a partir de su `PaqueteClaves`.
    #[wasm_bindgen(js_name = abrirSesion)]
    pub fn abrir_sesion(&mut self, dispositivo: &str, paquete_json: &str, ahora_ms: f64) -> Result<(), JsError> {
        self.sesion_desde_paquete(dispositivo, paquete_json, ahora_ms).map_err(js)
    }

    #[wasm_bindgen(js_name = tieneSesion)]
    pub fn tiene_sesion(&self, dispositivo: &str) -> Result<bool, JsError> {
        self.hay_sesion(dispositivo).map_err(js)
    }

    /// Cifra para un aparato. Devuelve `{"tipo":2|3,"cuerpo":"base64"}`.
    pub fn cifrar(&mut self, dispositivo: &str, claro: &[u8], ahora_ms: f64) -> Result<String, JsError> {
        self.cifrar_para(dispositivo, claro, ahora_ms).map_err(js)
    }

    /// Descifra un mensaje por pares (tipo 2 o 3) de ese aparato.
    pub fn descifrar(&mut self, dispositivo: &str, tipo: u8, cuerpo: &str) -> Result<Vec<u8>, JsError> {
        self.descifrar_de(dispositivo, tipo, cuerpo).map_err(js)
    }

    /// La clave de emisor propia para un grupo, para repartirla por pares.
    #[wasm_bindgen(js_name = crearDistribucion)]
    pub fn crear_distribucion(&mut self, mi_dispositivo: &str, dist_id: &str) -> Result<String, JsError> {
        self.distribucion(mi_dispositivo, dist_id).map_err(js)
    }

    /// Guarda la clave de emisor que repartió otro aparato.
    #[wasm_bindgen(js_name = procesarDistribucion)]
    pub fn procesar_distribucion(&mut self, remitente: &str, skdm: &str) -> Result<(), JsError> {
        self.aceptar_distribucion(remitente, skdm).map_err(js)
    }

    /// Cifra una vez para todo el grupo (tipo 7). Devuelve el base64.
    #[wasm_bindgen(js_name = cifrarGrupo)]
    pub fn cifrar_grupo(&mut self, mi_dispositivo: &str, dist_id: &str, claro: &[u8]) -> Result<String, JsError> {
        self.cifrar_en_grupo(mi_dispositivo, dist_id, claro).map_err(js)
    }

    #[wasm_bindgen(js_name = descifrarGrupo)]
    pub fn descifrar_grupo(&mut self, remitente: &str, cuerpo: &str) -> Result<Vec<u8>, JsError> {
        self.descifrar_de_grupo(remitente, cuerpo).map_err(js)
    }

    /// Todo el almacén en JSON, CON las claves privadas: quien lo llame tiene
    /// que cifrarlo antes de guardarlo (la web lo hace con una clave de
    /// WebCrypto que no se puede exportar).
    pub fn exportar(&self) -> Result<String, JsError> {
        self.exportar_json().map_err(js)
    }

    /// Vuelve a armar el aparato con lo que devolvió [Cliente::exportar].
    pub fn importar(json: &str) -> Result<Cliente, JsError> {
        Cliente::importar_json(json).map_err(js)
    }

    /// Cuántas prekeys de un solo uso quedan sin gastar.
    #[wasm_bindgen(js_name = unicasRestantes)]
    pub fn unicas_restantes(&self) -> u32 {
        self.almacen.unicas_restantes() as u32
    }

    /// Si la identidad de ese aparato cambió: hay que avisar, como la app.
    #[wasm_bindgen(js_name = identidadCambio)]
    pub fn identidad_cambio(&self, dispositivo: &str) -> Result<bool, JsError> {
        self.cambio_de(dispositivo).map_err(js)
    }

    /// Los 60 dígitos y la forma escaneable. `{"digitos","escaneable"}`.
    pub fn huella(&self, mi_usuario: &str, otro_usuario: &str, otro_dispositivo: &str) -> Result<String, JsError> {
        self.huella_con(mi_usuario, otro_usuario, otro_dispositivo).map_err(js)
    }
}

/// La versión de libsignal que lleva este WebAssembly.
#[wasm_bindgen(js_name = versionLibsignal)]
pub fn version_libsignal() -> String {
    VERSION_LIBSIGNAL.to_string()
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[derive(Deserialize)]
    struct CopiaLeida {
        tipo: u8,
        cuerpo: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Publicadas {
        registration_id: u32,
        identidad: String,
        firmada: ClaveFirmada,
        kyber: ClaveFirmada,
        unicas: Vec<ClavePublica>,
    }

    /// El paquete que el servidor armaría con lo publicado, con una única.
    fn paquete(pub_json: &str) -> String {
        let p: Publicadas = serde_json::from_str(pub_json).unwrap();
        serde_json::json!({
            "usuarioId": "u", "username": "x", "dispositivoId": "d",
            "registrationId": p.registration_id,
            "identidad": p.identidad,
            "firmada": p.firmada,
            "kyber": p.kyber,
            "unica": p.unicas.first(),
        })
        .to_string()
    }

    const AHORA: f64 = 1_791_300_000_000.0;

    #[test]
    fn ida_y_vuelta_por_pares_con_pqxdh() {
        let mut ana = Cliente::crear().unwrap();
        let mut beto = Cliente::crear().unwrap();
        let claves_beto = beto.claves(1, 1, 1, 5, AHORA).unwrap();

        ana.sesion_desde_paquete("beto-1", &paquete(&claves_beto), AHORA).unwrap();
        let c: CopiaLeida = serde_json::from_str(&ana.cifrar_para("beto-1", b"hola", AHORA).unwrap()).unwrap();
        assert_eq!(c.tipo, 3, "el primero lleva la prekey");
        assert_eq!(beto.descifrar_de("ana-1", c.tipo, &c.cuerpo).unwrap(), b"hola");

        let r: CopiaLeida = serde_json::from_str(&beto.cifrar_para("ana-1", b"chau", AHORA).unwrap()).unwrap();
        assert_eq!(r.tipo, 2);
        assert_eq!(ana.descifrar_de("beto-1", r.tipo, &r.cuerpo).unwrap(), b"chau");
    }

    #[test]
    fn el_almacen_se_exporta_e_importa_y_la_sesion_sigue() {
        let mut ana = Cliente::crear().unwrap();
        let mut beto = Cliente::crear().unwrap();
        let claves_beto = beto.claves(1, 1, 1, 3, AHORA).unwrap();
        ana.sesion_desde_paquete("beto-1", &paquete(&claves_beto), AHORA).unwrap();
        let c: CopiaLeida = serde_json::from_str(&ana.cifrar_para("beto-1", b"uno", AHORA).unwrap()).unwrap();
        beto.descifrar_de("ana-1", c.tipo, &c.cuerpo).unwrap();
        assert_eq!(beto.almacen.unicas_restantes(), 2, "la unica usada se gasta");

        // Los dos "cierran la pestaña" y vuelven.
        let mut ana = Cliente::importar_json(&ana.exportar_json().unwrap()).unwrap();
        let mut beto = Cliente::importar_json(&beto.exportar_json().unwrap()).unwrap();

        let c: CopiaLeida = serde_json::from_str(&ana.cifrar_para("beto-1", b"dos", AHORA).unwrap()).unwrap();
        assert_eq!(beto.descifrar_de("ana-1", c.tipo, &c.cuerpo).unwrap(), b"dos");
        let r: CopiaLeida = serde_json::from_str(&beto.cifrar_para("ana-1", b"tres", AHORA).unwrap()).unwrap();
        assert_eq!(ana.descifrar_de("beto-1", r.tipo, &r.cuerpo).unwrap(), b"tres");

        let dist = "0b4a5f0e-5b8f-4f43-9a3d-6a1f2a6b7c8d";
        let skdm = ana.distribucion("ana-1", dist).unwrap();
        beto.aceptar_distribucion("ana-1", &skdm).unwrap();
        let mut beto = Cliente::importar_json(&beto.exportar_json().unwrap()).unwrap();
        let m = ana.cifrar_en_grupo("ana-1", dist, b"grupo").unwrap();
        assert_eq!(beto.descifrar_de_grupo("ana-1", &m).unwrap(), b"grupo");
    }

    #[test]
    fn un_almacen_roto_no_se_importa() {
        assert!(Cliente::importar_json("{}").is_err());
        assert!(Cliente::importar_json("no es json").is_err());
    }

    #[test]
    fn grupo_con_clave_de_emisor() {
        let mut ana = Cliente::crear().unwrap();
        let mut beto = Cliente::crear().unwrap();
        let dist = "0b4a5f0e-5b8f-4f43-9a3d-6a1f2a6b7c8d";
        let skdm = ana.distribucion("ana-1", dist).unwrap();
        beto.aceptar_distribucion("ana-1", &skdm).unwrap();
        let m = ana.cifrar_en_grupo("ana-1", dist, b"a todos").unwrap();
        assert_eq!(beto.descifrar_de_grupo("ana-1", &m).unwrap(), b"a todos");
    }

    #[test]
    fn la_huella_es_la_misma_de_los_dos_lados() {
        let mut ana = Cliente::crear().unwrap();
        let mut beto = Cliente::crear().unwrap();
        let claves_beto = beto.claves(1, 1, 1, 1, AHORA).unwrap();
        ana.sesion_desde_paquete("beto-1", &paquete(&claves_beto), AHORA).unwrap();
        let c: CopiaLeida = serde_json::from_str(&ana.cifrar_para("beto-1", b"x", AHORA).unwrap()).unwrap();
        beto.descifrar_de("ana-1", c.tipo, &c.cuerpo).unwrap();

        #[derive(Deserialize)]
        struct H {
            digitos: String,
        }
        let ha: H = serde_json::from_str(&ana.huella_con("uid-ana", "uid-beto", "beto-1").unwrap()).unwrap();
        let hb: H = serde_json::from_str(&beto.huella_con("uid-beto", "uid-ana", "ana-1").unwrap()).unwrap();
        assert_eq!(ha.digitos.len(), 60);
        assert_eq!(ha.digitos, hb.digitos);
    }
}
