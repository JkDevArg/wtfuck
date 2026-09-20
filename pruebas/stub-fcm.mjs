// Un FCM de mentira, para poder probar el push sin hablar con Google.
//
// Levanta dos rutas:
//
//   POST /token   el intercambio OAuth2. Devuelve un token de acceso fijo.
//   POST /send    el envio. Guarda el cuerpo y lo expone en GET /recibidos.
//
// Se usa asi:
//
//   node pruebas/stub-fcm.mjs &
//   WTFUCK_FCM_PROYECTO=prueba \
//   WTFUCK_FCM_EMAIL=stub@prueba.iam.gserviceaccount.com \
//   WTFUCK_FCM_CLAVE="$(cat clave.pem)" \
//   WTFUCK_FCM_OAUTH=http://localhost:8399/token \
//   WTFUCK_FCM_ENDPOINT=http://localhost:8399/send \
//   ...arrancar el servidor...
//   node pruebas/push.mjs
//
// El stub NO valida la firma del JWT. Lo que interesa comprobar aqui no es que
// Google acepte la firma -eso lo dira Google- sino **que viaja en el aviso**, y
// sobre todo que no viaja el contenido del mensaje.
import { createServer } from 'node:http';

const PUERTO = Number(process.env.STUB_PUERTO ?? 8399);
const recibidos = [];

const leer = (req) =>
  new Promise((res) => {
    let d = '';
    req.on('data', (c) => { d += c; });
    req.on('end', () => res(d));
  });

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (req.method === 'POST' && url.pathname === '/token') {
    const cuerpo = await leer(req);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ access_token: 'token-de-mentira', expires_in: 3600 }));
    recibidos.push({ ruta: 'token', cuerpo });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/send') {
    const cuerpo = await leer(req);
    recibidos.push({
      ruta: 'send',
      autorizacion: req.headers.authorization ?? '',
      cuerpo: JSON.parse(cuerpo),
    });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ name: 'projects/prueba/messages/1' }));
    return;
  }

  if (req.method === 'GET' && url.pathname === '/recibidos') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(recibidos));
    return;
  }

  if (req.method === 'POST' && url.pathname === '/limpiar') {
    recibidos.length = 0;
    res.writeHead(204).end();
    return;
  }

  res.writeHead(404).end();
}).listen(PUERTO, () => console.log(`stub de FCM en http://localhost:${PUERTO}`));
