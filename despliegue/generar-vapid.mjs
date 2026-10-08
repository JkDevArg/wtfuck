// Genera el par VAPID para los avisos de la version web (Web Push).
//
//   node despliegue/generar-vapid.mjs
//
// Imprime las dos variables en el formato que lee el servidor (`WebPush.kt`).
// La PRIVADA es un secreto: va al .env del servidor y a ningun otro lado.
// Cambiar el par invalida todas las suscripciones de los navegadores; la web
// vuelve a suscribirse sola la proxima vez que se abre.
import { generateKeyPairSync } from 'node:crypto';

const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const jwk = privateKey.export({ format: 'jwk' });
const b = (s) => Buffer.from(s, 'base64url');
const publica = Buffer.concat([Buffer.from([4]), b(jwk.x), b(jwk.y)]).toString('base64url');

console.log(`WTFUCK_VAPID_PUBLICA=${publica}`);
console.log(`WTFUCK_VAPID_PRIVADA=${jwk.d}`);
console.log('WTFUCK_VAPID_CONTACTO=mailto:tu-correo@ejemplo.com');
