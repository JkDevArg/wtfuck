//! El almacén de Signal del navegador, que se puede GUARDAR.
//!
//! ## Por qué no `InMemSignalProtocolStore`
//!
//! El de libsignal no deja recorrer sus sesiones ni sus claves de emisor, así
//! que no hay forma de sacarlo de la memoria. Este guarda todo ya serializado
//! (los mismos bytes que usa libsignal) en mapas ordenados, y [Almacen] entero
//! se exporta e importa como JSON. La web lo guarda cifrado en IndexedDB
//! después de cada operación.
//!
//! ## Una parte por trait
//!
//! `message_decrypt` pide a la vez el almacén de sesiones, el de identidades,
//! el de prekeys… como `&mut` distintos. Con un solo struct que los implemente
//! todos, Rust no deja prestarlo varias veces. Por eso hay una parte por trait.
//!
//! ## Confianza
//!
//! Igual que la app (`AlmacenSignal.isTrustedIdentity`): se confía al primer
//! uso y se avisa si la identidad cambia. Rechazar haría que la app pareciera
//! rota; el aviso lo da la interfaz.

use std::collections::BTreeMap;

use async_trait::async_trait;
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use libsignal_protocol::{
    Direction, GenericSignedPreKey, IdentityChange, IdentityKey, IdentityKeyPair, IdentityKeyStore,
    KyberPreKeyId, KyberPreKeyRecord, KyberPreKeyStore, PreKeyId, PreKeyRecord, PreKeyStore,
    ProtocolAddress, PublicKey, SenderKeyRecord, SenderKeyStore, SessionRecord, SessionStore,
    SignalProtocolError, SignedPreKeyId, SignedPreKeyRecord, SignedPreKeyStore,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

type Res<T> = libsignal_protocol::error::Result<T>;

fn mal(que: &str) -> SignalProtocolError {
    SignalProtocolError::InvalidArgument(format!("almacén: {que} ilegible"))
}

fn enc(b: &[u8]) -> String {
    B64.encode(b)
}

fn dec(s: &str, que: &str) -> Res<Vec<u8>> {
    B64.decode(s).map_err(|_| mal(que))
}

fn clave(a: &ProtocolAddress) -> String {
    format!("{}.{}", a.name(), u32::from(a.device_id()))
}

#[derive(Serialize, Deserialize)]
pub struct Identidades {
    propia: String,
    registro: u32,
    /// dirección -> identidad pública conocida.
    conocidas: BTreeMap<String, String>,
    /// Direcciones cuya identidad cambió y nadie miró todavía.
    #[serde(default)]
    cambiaron: BTreeMap<String, bool>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct Unicas {
    claves: BTreeMap<u32, String>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct Firmadas {
    claves: BTreeMap<u32, String>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct Kyber {
    claves: BTreeMap<u32, String>,
    /// "kyber.firmada" -> claves base ya vistas: una repetida es un mensaje
    /// reenviado por alguien en el medio.
    bases: BTreeMap<String, Vec<String>>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct Sesiones {
    por_direccion: BTreeMap<String, String>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct Emisores {
    /// "dirección|distribución" -> registro de la clave de emisor.
    claves: BTreeMap<String, String>,
}

#[derive(Serialize, Deserialize)]
pub struct Almacen {
    pub identidades: Identidades,
    pub unicas: Unicas,
    pub firmadas: Firmadas,
    pub kyber: Kyber,
    pub sesiones: Sesiones,
    pub emisores: Emisores,
}

impl Almacen {
    pub fn nuevo(par: &IdentityKeyPair, registro: u32) -> Almacen {
        Almacen {
            identidades: Identidades {
                propia: enc(&par.serialize()),
                registro,
                conocidas: BTreeMap::new(),
                cambiaron: BTreeMap::new(),
            },
            unicas: Unicas::default(),
            firmadas: Firmadas::default(),
            kyber: Kyber::default(),
            sesiones: Sesiones::default(),
            emisores: Emisores::default(),
        }
    }

    pub fn exportar(&self) -> Result<String, String> {
        serde_json::to_string(self).map_err(|e| e.to_string())
    }

    pub fn importar(json: &str) -> Result<Almacen, String> {
        let a: Almacen = serde_json::from_str(json).map_err(|e| format!("almacén: {e}"))?;
        // Que la identidad se lea ya, y no en el primer mensaje.
        let b = B64.decode(&a.identidades.propia).map_err(|_| "almacén: identidad ilegible".to_string())?;
        IdentityKeyPair::try_from(b.as_slice()).map_err(|e| e.to_string())?;
        Ok(a)
    }

    /// Cuántas prekeys únicas quedan: para saber cuándo reponer.
    pub fn unicas_restantes(&self) -> usize {
        self.unicas.claves.len()
    }

    /// Si la identidad de ese aparato cambió desde que se la conoció.
    pub fn cambio(&self, a: &ProtocolAddress) -> bool {
        self.identidades.cambiaron.get(&clave(a)).copied().unwrap_or(false)
    }

    /// La persona ya vio el aviso de que cambió: no se repite.
    pub fn visto(&mut self, a: &ProtocolAddress) {
        self.identidades.cambiaron.remove(&clave(a));
    }
}

#[async_trait(?Send)]
impl IdentityKeyStore for Identidades {
    async fn get_identity_key_pair(&self) -> Res<IdentityKeyPair> {
        IdentityKeyPair::try_from(dec(&self.propia, "identidad")?.as_slice())
    }

    async fn get_local_registration_id(&self) -> Res<u32> {
        Ok(self.registro)
    }

    async fn save_identity(&mut self, a: &ProtocolAddress, id: &IdentityKey) -> Res<IdentityChange> {
        let k = clave(a);
        let nueva = enc(&id.serialize());
        let cambio = match self.conocidas.get(&k) {
            Some(vieja) if *vieja != nueva => IdentityChange::ReplacedExisting,
            _ => IdentityChange::NewOrUnchanged,
        };
        if matches!(cambio, IdentityChange::ReplacedExisting) {
            self.cambiaron.insert(k.clone(), true);
        }
        self.conocidas.insert(k, nueva);
        Ok(cambio)
    }

    async fn is_trusted_identity(&self, _a: &ProtocolAddress, _id: &IdentityKey, _d: Direction) -> Res<bool> {
        Ok(true)
    }

    async fn get_identity(&self, a: &ProtocolAddress) -> Res<Option<IdentityKey>> {
        match self.conocidas.get(&clave(a)) {
            None => Ok(None),
            Some(s) => Ok(Some(IdentityKey::try_from(dec(s, "identidad remota")?.as_slice())?)),
        }
    }
}

#[async_trait(?Send)]
impl PreKeyStore for Unicas {
    async fn get_pre_key(&self, id: PreKeyId) -> Res<PreKeyRecord> {
        let s = self.claves.get(&u32::from(id)).ok_or(SignalProtocolError::InvalidPreKeyId)?;
        PreKeyRecord::deserialize(&dec(s, "prekey")?)
    }

    async fn save_pre_key(&mut self, id: PreKeyId, r: &PreKeyRecord) -> Res<()> {
        self.claves.insert(u32::from(id), enc(&r.serialize()?));
        Ok(())
    }

    async fn remove_pre_key(&mut self, id: PreKeyId) -> Res<()> {
        self.claves.remove(&u32::from(id));
        Ok(())
    }
}

#[async_trait(?Send)]
impl SignedPreKeyStore for Firmadas {
    async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> Res<SignedPreKeyRecord> {
        let s = self.claves.get(&u32::from(id)).ok_or(SignalProtocolError::InvalidSignedPreKeyId)?;
        SignedPreKeyRecord::deserialize(&dec(s, "firmada")?)
    }

    async fn save_signed_pre_key(&mut self, id: SignedPreKeyId, r: &SignedPreKeyRecord) -> Res<()> {
        self.claves.insert(u32::from(id), enc(&r.serialize()?));
        Ok(())
    }
}

#[async_trait(?Send)]
impl KyberPreKeyStore for Kyber {
    async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> Res<KyberPreKeyRecord> {
        let s = self.claves.get(&u32::from(id)).ok_or(SignalProtocolError::InvalidKyberPreKeyId)?;
        KyberPreKeyRecord::deserialize(&dec(s, "kyber")?)
    }

    async fn save_kyber_pre_key(&mut self, id: KyberPreKeyId, r: &KyberPreKeyRecord) -> Res<()> {
        self.claves.insert(u32::from(id), enc(&r.serialize()?));
        Ok(())
    }

    /// La Kyber se usa como "de último recurso", como en la app: no se borra,
    /// pero la misma combinación con la misma clave base no vale dos veces.
    async fn mark_kyber_pre_key_used(&mut self, k: KyberPreKeyId, f: SignedPreKeyId, base: &PublicKey) -> Res<()> {
        let vistas = self.bases.entry(format!("{}.{}", u32::from(k), u32::from(f))).or_default();
        let b = enc(&base.serialize());
        if vistas.contains(&b) {
            return Err(SignalProtocolError::InvalidMessage(
                libsignal_protocol::CiphertextMessageType::PreKey,
                "clave base repetida",
            ));
        }
        vistas.push(b);
        Ok(())
    }
}

#[async_trait(?Send)]
impl SessionStore for Sesiones {
    async fn load_session(&self, a: &ProtocolAddress) -> Res<Option<SessionRecord>> {
        match self.por_direccion.get(&clave(a)) {
            None => Ok(None),
            Some(s) => Ok(Some(SessionRecord::deserialize(&dec(s, "sesión")?)?)),
        }
    }

    async fn store_session(&mut self, a: &ProtocolAddress, r: &SessionRecord) -> Res<()> {
        self.por_direccion.insert(clave(a), enc(&r.serialize()?));
        Ok(())
    }
}

#[async_trait(?Send)]
impl SenderKeyStore for Emisores {
    async fn store_sender_key(&mut self, a: &ProtocolAddress, d: Uuid, r: &SenderKeyRecord) -> Res<()> {
        self.claves.insert(format!("{}|{}", clave(a), d), enc(&r.serialize()?));
        Ok(())
    }

    async fn load_sender_key(&mut self, a: &ProtocolAddress, d: Uuid) -> Res<Option<SenderKeyRecord>> {
        match self.claves.get(&format!("{}|{}", clave(a), d)) {
            None => Ok(None),
            Some(s) => Ok(Some(SenderKeyRecord::deserialize(&dec(s, "clave de emisor")?)?)),
        }
    }
}
